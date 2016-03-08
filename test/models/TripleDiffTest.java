package models;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.jena.riot.RiotException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by fo on 11.02.16.
 */
public class TripleDiffTest {

  @Test
  public void testPlainLiteralLine() {
    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff.fromString(
        " + <http://example.org/show/218> <http://www.w3.org/2000/01/rdf-schema#label> \"That Seventies Show\" .");
  }

  @Test
  public void testAddLanguageLiteralLine() {
    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff
        .fromString("+ <http://example.org/show/218> <http://example.org/show/localName> \"That Seventies Show\"@en .");
  }

  @Test
  public void testAddTypedLiteralLine() {
    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff.fromString(
        "+ <http://en.wikipedia.org/wiki/Helium> <http://example.org/elements/specificGravity> \"1.663E-4\"^^<http://www.w3.org/2001/XMLSchema#double> .");
  }

  @Test(expected = RiotException.class)
  public void testInvalidLiteral() {
    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff.fromString(
        " + <http://example.org/show/218> <http://www.w3.org/2000/01/rdf-schema#label> \"That Seventies Show .");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidOp() {
    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff.fromString(
        " | <http://example.org/show/218> <http://www.w3.org/2000/01/rdf-schema#label> \"That Seventies Show\" .");
  }

  @Test
  public void testReadHeader() {

    TripleDiff tripleDiff = new TripleDiff();
    tripleDiff.fromString( //
        "Author: unittest@oerworldmap.org\n" //
            + "Date: " + now() + "\n" //
            + "\n" //
            + "+ <http://en.wikipedia.org/wiki/Helium> <http://example.org/elements/specificGravity> \"1.663E-4\"^^<http://www.w3.org/2001/XMLSchema#double> .");
    Assert.assertNotNull(tripleDiff.getHeader());
  }

  private String now() {
    Calendar calendar = Calendar.getInstance();
    SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.getDefault());
    dateFormat.setTimeZone(TimeZone.getDefault());
    String result = dateFormat.format(calendar.getTime());

    // TODO: delete this hack
    // for an unknown reason, a date time string ending with "CET" can not be
    // parsed by LocalDate.parse(now(), DateTimeFormatter.RFC_1123_DATE_TIME);
    result = result.replace("CET", "GMT");

    return result;
  }

}
