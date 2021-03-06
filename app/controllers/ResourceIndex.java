package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ListProcessingReport;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import helpers.JsonLdConstants;
import helpers.MimeTypes;
import models.Commit;
import models.Record;
import models.Resource;
import models.ResourceList;
import models.TripleCommit;
import org.apache.commons.lang3.StringUtils;
import play.Configuration;
import play.Environment;
import play.Logger;
import play.mvc.Result;
import play.mvc.With;
import services.QueryContext;
import services.SearchConfig;
import services.export.CalendarExporter;
import services.export.CsvExporter;
import services.export.GeoJsonExporter;
import services.export.JsonSchemaExporter;

import javax.inject.Inject;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author fo
 */
public class ResourceIndex extends OERWorldMap {

  private GeoJsonExporter mGeoJsonExporter = new GeoJsonExporter();

  @Inject
  public ResourceIndex(Configuration aConf, Environment aEnv) {
    super(aConf, aEnv);
  }

  @With(Cached.class)
  public Result list(String q, int from, int size, String sort, String extension,
    String iso3166, String region, String disposition) {

    Map<String, List<String>> filters = new HashMap<>();
    QueryContext queryContext = getQueryContext();

    // Handle ISO 3166 param
    if (!StringUtils.isEmpty(iso3166)) {
      if (!Arrays.asList(Locale.getISOCountries()).contains(iso3166.toUpperCase())) {
        return notFound("Not found");
      }
      queryContext.setIso3166Scope(iso3166.toUpperCase());
    }

    // Handle region param
    if (!StringUtils.isEmpty(iso3166) && !StringUtils.isEmpty(region)) {
      queryContext.setRegionScope(iso3166.toUpperCase().concat(".").concat(region.toUpperCase()));
    }

    // Extract filters directly from query params
    Pattern filterPattern = Pattern.compile("^filter\\.(.*)$");
    for (Map.Entry<String, String[]> entry : ctx().request().queryString().entrySet()) {
      Matcher filterMatcher = filterPattern.matcher(entry.getKey());
      if (filterMatcher.find()) {
        ArrayList<String> filter = new ArrayList<>();
        for (String value : entry.getValue()) {
          try {
            JsonNode jsonNode = mObjectMapper.readTree(value);
            if (jsonNode.isArray()) {
              for (JsonNode e : jsonNode) {
                filter.add(e.textValue());
              }
            } else {
              filter.add(jsonNode.textValue());
            }
          } catch (IOException e) {
            filter.add(value);
          }
        }
        filters.put(filterMatcher.group(1), filter);
      }
    }

    // Handle special filter case for event calendar
    if (filters.containsKey("about.@type")
      && filters.get("about.@type").size() == 1
      && filters.get("about.@type").contains("Event")
      && !filters.containsKey("about.startDate.GTE")
    ) {
      filters.put("about.startDate.GTE", Collections.singletonList("now/d"));
    } else if (filters.containsKey("about.@type")
      && (!filters.get("about.@type").contains("Event")
        || filters.get("about.@type").size() != 1)
    ) {
      filters.remove("about.startDate.GTE");
    }

    // Check for bounding box
    String[] boundingBoxParam = ctx().request().queryString().get("boundingBox");
    if (boundingBoxParam != null && boundingBoxParam.length > 0) {
      String boundingBox = boundingBoxParam[0];
      if (boundingBox != null) {
        try {
          queryContext.setBoundingBox(boundingBox);
        } catch (NumberFormatException e) {
          Logger.trace("Invalid bounding box: ".concat(boundingBox), e);
        }
      }
    }

    // Check for fields to fetch
    if (ctx().request().queryString().containsKey("fields")) {
      queryContext.setFetchSource(ctx().request().queryString().get("fields"));
    }
    String searchConfigFile = mConf.getString("search.conf.file");
    SearchConfig searchConfig = searchConfigFile != null
      ? new SearchConfig(searchConfigFile)
      : new SearchConfig();
    queryContext.setElasticsearchFieldBoosts(searchConfig.getBoostsForElasticsearch());
    ResourceList resourceList = mBaseRepository.query(q, from, size, sort, filters, queryContext);

    String baseUrl = mConf.getString("proxy.host");
    String filterString = "";
    for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
      String filterKey = "filter.".concat(filter.getKey());
      for (String filterValue : filter.getValue()) {
        try {
          filterString = filterString.concat("&".concat(filterKey).concat("=").concat(URLEncoder.encode(filterValue,
            StandardCharsets.UTF_8.name())));
        } catch (UnsupportedEncodingException e) {
          Logger.error("Unhandeled encoding", e);
        }
      }
    }

    Set<String> alternates = MimeTypes.all().keySet();
    if (!resourceList.containsType("Event")) {
      alternates.remove("ics");
    }
    List<String> links = new ArrayList<>();
    for (String alternate : alternates) {
      String linkUrl = baseUrl.concat(routes.ResourceIndex.list(q, 0, -1, sort, alternate,
        iso3166, region, disposition).url().concat(filterString));
      links.add(String.format("<%s>; rel=\"alternate\"; type=\"%s\"", linkUrl,
        MimeTypes.fromExtension(alternate)));
    }

    response().setHeader("Link", String.join(", ", links));
    if (!StringUtils.isEmpty(extension)) {
      response()
        .setHeader("Content-Disposition", "inline".equals(disposition) ? "inline" : "attachment");
    }

    String format = StringUtils.isEmpty(extension)
      ? MimeTypes.fromRequest(request())
      : MimeTypes.fromExtension(extension);

    if (format == null) {
      return notFound("Not found");
    } else if (format.equals("text/csv")) {
      CsvExporter csvExporter = request().hasHeader("X-CSV-HEADERS")
        ? new CsvExporter(Arrays.stream(request().getHeader("X-CSV-HEADERS").split(","))
          .map(Pattern::compile).collect(Collectors.toList()))
        : new CsvExporter();
      return ok(csvExporter.export(resourceList)).as("text/csv");
    } else if (format.equals("text/calendar")) {
      return ok(new CalendarExporter(Locale.ENGLISH).export(resourceList)).as("text/calendar");
    } else if (format.equals("application/json")) {
      Resource result = resourceList.toResource();
      if (!StringUtils.isEmpty(iso3166)) {
        if (!StringUtils.isEmpty(iso3166)) {
          result.put("iso3166", iso3166.toUpperCase());
        }
      }
      JsonNode rString = result.toJson();
      return ok(rString).as("application/json");
    } else if (format.equals("application/geo+json")) {
      return ok(mGeoJsonExporter.export(resourceList)).as("application/geo+json");
    } else if (format.equals("application/schema+json")) {
      return ok(new JsonSchemaExporter().export(resourceList)).as("application/schema+json");
    }

    return notFound("Not found");
  }

  public Result importResources() throws IOException {
    JsonNode json = ctx().request().body().asJson();
    List<Resource> resources = new ArrayList<>();
    if (json.isArray()) {
      for (JsonNode node : json) {
        resources.add(Resource.fromJson(node));
      }
    } else if (json.isObject()) {
      resources.add(Resource.fromJson(json));
    } else {
      return badRequest();
    }
    mBaseRepository.importResources(resources, getMetadata());
    Cached.updateEtag();
    return ok(Integer.toString(resources.size()).concat(" resources imported."));
  }

  public Result addResource() throws IOException {
    JsonNode jsonNode = getJsonFromRequest();
    if (jsonNode == null || (!jsonNode.isArray() && !jsonNode.isObject())) {
      return badRequest("Bad or empty JSON");
    } else if (jsonNode.isArray()) {
      return upsertResources();
    } else {
      return upsertResource(false);
    }
  }

  public Result updateResource(String aId) throws IOException {
    // If updating a resource, check if it actually exists
    Resource originalResource = mBaseRepository.getResource(aId);
    if (originalResource == null) {
      return notFound("Not found: ".concat(aId));
    }
    return upsertResource(true);
  }

  private Result upsertResource(boolean isUpdate) throws IOException {
    // Extract resource
    Resource resource = Resource.fromJson(getJsonFromRequest());
    resource.put(JsonLdConstants.CONTEXT, mConf.getString("jsonld.context"));

    // Person create /update only through UserIndex, which is restricted to admin
    if (!isUpdate && "Person".equals(resource.getType())) {
      return forbidden("Upsert Person forbidden.");
    }

    // Validate
    Resource staged = mBaseRepository.stage(resource);
    ProcessingReport processingReport = validate(staged);
    if (!processingReport.isSuccess()) {
      ListProcessingReport listProcessingReport = new ListProcessingReport();
      try {
        listProcessingReport.mergeWith(processingReport);
      } catch (ProcessingException e) {
        Logger.warn("Failed to create list processing report", e);
      }
      return badRequest(listProcessingReport.asJson());
    }

    // Save
    mBaseRepository.addResource(resource, getMetadata());
    Cached.updateEtag();

    // Allow to delete own likes
    // TODO: move to action index once implemented
    if (resource.getType().equals("LikeAction")) {
      mAccountService.setPermissions(resource.getId(), request().username());
    }

    // Respond
    if (isUpdate) {
      return ok(getRecord(mBaseRepository.getResource(resource.getId())).toJson());
    } else {
      response().setHeader(LOCATION, routes.ResourceIndex.read(resource.getId(), "HEAD", null, null)
        .url());
      return created(getRecord(mBaseRepository.getResource(resource.getId())).toJson());
    }
  }

  private Result upsertResources() throws IOException {

    // Extract resources
    List<Resource> resources = new ArrayList<>();
    for (JsonNode jsonNode : getJsonFromRequest()) {
      Resource resource = Resource.fromJson(jsonNode);
      resource.put(JsonLdConstants.CONTEXT, mConf.getString("jsonld.context"));
      resources.add(resource);
    }

    // Validate
    ListProcessingReport listProcessingReport = new ListProcessingReport();
    for (Resource resource : resources) {
      // Person create /update only through UserIndex, which is restricted to admin
      if ("Person".equals(resource.getType())) {
        return forbidden("Upsert Person forbidden.");
      }
      // Stage and validate each resource
      try {
        Resource staged = mBaseRepository.stage(resource);
        ProcessingReport processingMessages = validate(staged);
        if (!processingMessages.isSuccess()) {
          Logger.debug(processingMessages.toString());
          Logger.debug(staged.toString());
        }
        listProcessingReport.mergeWith(processingMessages);
      } catch (ProcessingException e) {
        Logger.error("Could not process validation report", e);
        return badRequest();
      }
    }

    if (!listProcessingReport.isSuccess()) {
      return badRequest(listProcessingReport.asJson());
    }
    mBaseRepository.addResources(resources, getMetadata());
    Cached.updateEtag();

    return ok("Added resources");
  }

  @With(Cached.class)
  public Result read(String id, String version, String extension, String disposition) {

    Resource resource = mBaseRepository.getResource(id, version);
    if (null == resource) {
      return notFound("Not found");
    }
    Set<String> alternates = MimeTypes.all().keySet();
    if (!resource.getType().equals("Event")) {
      alternates.remove("ics");
    }
    List<String> links = new ArrayList<>();
    for (String alternate : alternates) {
      String linkUrl = routes.ResourceIndex.read(id, version, alternate, disposition).url();
      links.add(String.format("<%s>; rel=\"alternate\"; type=\"%s\"", linkUrl,
        MimeTypes.fromExtension(alternate)));
    }

    response().setHeader("Link", String.join(", ", links));
    if (!StringUtils.isEmpty(extension)) {
      response()
        .setHeader("Content-Disposition", "inline".equals(disposition) ? "inline" : "attachment");
    }

    String format = StringUtils.isEmpty(extension)
      ? MimeTypes.fromRequest(request())
      : MimeTypes.fromExtension(extension);

    resource = getRecord(resource);
    if (format == null) {
      return notFound("Not found");
    } else if (format.equals("application/json")) {
      return ok(resource.toString()).as("application/json; charset=UTF-8");
    } else if (format.equals("text/csv")) {
      CsvExporter csvExporter = request().hasHeader("X-CSV-HEADERS")
        ? new CsvExporter(Arrays.stream(request().getHeader("X-CSV-HEADERS").split(","))
        .map(Pattern::compile).collect(Collectors.toList()))
        : new CsvExporter();
      return ok(csvExporter.export(resource)).as("text/csv; charset=UTF-8");
    } else if (format.equals("application/geo+json")) {
      String geoJson = mGeoJsonExporter.export(resource);
      return geoJson != null ? ok(geoJson) : status(406);
    } else if (format.equals("application/schema+json")) {
      return ok(new JsonSchemaExporter().export(resource)).as("application/schema+json");
    } else if (format.equals("text/calendar")) {
      String ical = new CalendarExporter(Locale.ENGLISH).export(resource);
      if (ical != null) {
        return ok(ical).as("text/calendar; charset=UTF-8");
      }
    }
    return notFound("Not found");
  }

  private Record getRecord(Resource aResource) {
    Record record = new Record(aResource);
    List<Commit> history = mBaseRepository.log(aResource.getId());
    record.put(Record.CONTRIBUTOR, mAccountService.getProfileId(history.get(0).getHeader().getAuthor()));
    try {
      record.put(Record.AUTHOR, mAccountService.getProfileId(history.get(history.size() - 1).getHeader().getAuthor()));
    } catch (NullPointerException e) {
      Logger.trace("Could not read author from commit " + history.get(history.size() - 1), e);
    }
    record.put(Record.DATE_MODIFIED, history.get(0).getHeader().getTimestamp()
      .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    try {
      record.put(Record.DATE_CREATED, history.get(history.size() - 1).getHeader().getTimestamp()
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    } catch (NullPointerException e) {
      Logger.trace("Could not read timestamp from commit " + history.get(history.size() - 1), e);
    }
    return record;
  }

  public Result delete(String aId) throws IOException {
    Resource resource = mBaseRepository.deleteResource(aId, getMetadata());
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    Cached.updateEtag();
    if (null != resource) {
      // If deleting personal profile, also delete corresponding user
      if ("Person".equals(resource.getType())) {
        String username = mAccountService.getUsername(aId);
        if (!mAccountService.removePermissions(aId)) {
          Logger.error("Could not remove permissions for " + aId);
        }
        if (!mAccountService.deleteUser(username)) {
          Logger.error("Could not delete user " + username);
        }
        result.put("message", "deleted user " + aId);
        return ok(result);
      } else {
        result.put("message", "deleted resource " + aId);
        return ok(result);
      }
    } else {
      result.put("message", "Failed to delete resource " + aId);
      return badRequest(result);
    }
  }

  public Result log(String aId, String compare, String to) {
    ArrayNode log = JsonNodeFactory.instance.arrayNode();
    List<Commit> commits = mBaseRepository.log(aId);
    for (Commit commit : commits) {
      ObjectNode entry = JsonNodeFactory.instance.objectNode();
      entry.put("commit", commit.getId());
      entry.put("author", commit.getHeader().getAuthor());
      entry.put("date", commit.getHeader().getTimestamp().toString());
      log.add(entry);
    }

    if (StringUtils.isEmpty(aId)) {
      return ok(log);
    }
    String v1 = StringUtils.isEmpty(compare) ? log.get(0).get("commit").textValue() : compare;
    String v2 =
      StringUtils.isEmpty(to) ? log.get(log.size() > 1 ? 1 : 0).get("commit").textValue() : to;
    Resource r1 = getRecord(mBaseRepository.getResource(aId, v1));
    Resource r2 = getRecord(mBaseRepository.getResource(aId, v2));
    r1.put("version", v1);
    r2.put("version", v2);

    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.set("compare", r1.toJson());
    result.set("to", r2.toJson());
    result.set("log", log);
    return ok(result);
  }

  public Result index(String aId) {
    mBaseRepository.index(aId);
    return ok("Indexed ".concat(aId));
  }

  public Result commentResource(String aId) throws IOException {

    Resource resource = mBaseRepository.getResource(aId);

    if (resource == null) {
      return notFound();
    }

    Resource comment = Resource.fromJson(getJsonFromRequest());
    comment.put(JsonLdConstants.CONTEXT, mConf.getString("jsonld.context"));
    comment.put("author", getUser());
    comment.put("dateCreated", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    comment.put("commentOn", resource);

    TripleCommit.Diff diff = (TripleCommit.Diff) mBaseRepository.getDiff(comment);

    Map<String, String> metadata = getMetadata();
    TripleCommit.Header header = new TripleCommit.Header(
      metadata.get(TripleCommit.Header.AUTHOR_HEADER),
      ZonedDateTime.parse(metadata.get(TripleCommit.Header.DATE_HEADER)));
    TripleCommit commit = new TripleCommit(header, diff);
    mBaseRepository.commit(commit);

    Cached.updateEtag();
    return created(comment.toJson());
  }

  public Result feed() throws IOException {
    ResourceList resourceList = mBaseRepository
      .query("", 0, 20, "dateCreated:DESC", null, getQueryContext());
    Map<String, Object> scope = new HashMap<>();
    scope.put("resources", resourceList.toResource());
    return ok(mObjectMapper.writeValueAsString(scope));
  }

  public Result label(String aId) throws UnsupportedEncodingException {
    return ok(StringUtils.isEmpty(aId)
      ? mBaseRepository.sparql(
      "SELECT DISTINCT * WHERE {?uri <http://schema.org/name> ?label}")
      : mBaseRepository.label(URLDecoder.decode(aId, "UTF-8"))
    );
  }

  public Result activity(String until) {
    List<Commit> activities = mBaseRepository.log(null);
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    String previousId = null;
    for (Commit commit : activities) {
      if (result.size() == 20 || (until != null && commit.getId().equals(until))) {
        break;
      }
      String id = ((TripleCommit) commit).getPrimaryTopic().getURI();
      TripleCommit.Header header = ((TripleCommit) commit).getHeader();
      // Skip empty commits, commits on same subject and deleted resources
      if (id == null
        || id.equals(previousId)
        || header.isMigration()
        || header.getAuthor().equals("System")
        || !mBaseRepository.hasResource(id))
      {
        continue;
      }
      previousId = id;
      Resource resource = mBaseRepository.getResource(id);
      ObjectNode entry = JsonNodeFactory.instance.objectNode();
      String profileId = mAccountService.getProfileId(commit.getHeader().getAuthor());
      if (profileId != null) {
        Resource user = mBaseRepository.getResource(profileId);
        if (user != null) {
          entry.set("user", user.toJson());
        }
      }
      ObjectNode action = JsonNodeFactory.instance.objectNode();
      action.put("time", commit.getHeader().getTimestamp().toString());
      action.put("type", (mBaseRepository.log(id).size() > 1 ? "edit" : "add"));
      entry.set("about", resource.toJson());
      entry.set("action", action);
      entry.put("id", commit.getId());
      result.add(entry);
    }
    return ok(result);
  }
}
