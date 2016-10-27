import BeautifulSoup, urllib2, json, re, os, sys, uuid, urlparse


grant_mapping = {
    'Amount': 'frapo:hasMonetaryValue',
    'Date Awarded': 'frapo:hasAwardDate'
}


page_regex = re.compile(r"&page=([0-9]+)$")
url_page_regex = re.compile(r"page/[0-9]+/")
url_term_regex = re.compile(r"/grants/([0-9a-zA-Z-]+)/.*")
month_duration_regex = re.compile(r"([0-9]+) [Mm]onths")
address_without_span_regex = re.compile(r'(?<=(<div class="aboutgrantee-address">\n))(.*)(?=(</div>))')
address_split_regex = re.compile(r'<br ?/?>')
oer_regex = re.compile(r'([\W]*)(OER)([\W]*)')


uuid_file = "id_map.json"
uuids = {}


def get_soup_from_page(url):
    try:
        page = urllib2.urlopen(url).read()
    except urllib2.URLError, e:
        if hasattr(e, 'reason'):
            print 'We failed to reach a server.'
            print 'Reason: ', e.reason
        elif hasattr(e, 'code'):
            print 'The server couldn\'t fulfill the request.'
            print 'Error code: ', e.code
    else:
        soup = BeautifulSoup.BeautifulSoup(page)
        soup.prettify()
        return soup


def get_header():
    return {
        "@context":{
            "frapo":"http://purl.org/cerif/frapo/",
            "rdf":"http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs":"http://www.w3.org/2000/01/rdf-schema#",
            "schema":"http://schema.org/",
            "xsd":"http://www.w3.org/2001/XMLSchema#"
        },
        "@type": "frapo:Grant"
    }


def get_uuid(key):
    global uuids
    try:
        a_uuid = uuids[key]
    except KeyError, e:
        # id does not yet exist
        a_uuid = uuid.uuid4().urn
        put_uuid(key, a_uuid)
    return a_uuid


def put_uuid(key, uuid):
    global uuids
    uuids[key] = uuid


def load_ids_file():
    global uuids, uuid_file
    if not os.path.isfile(uuid_file):
        open(uuid_file, 'a').close()
    try:
        with open(uuid_file, 'r') as file:
            uuids = json.loads(file.read())
    except ValueError, e:
        if not str(e).__eq__('No JSON object could be decoded'):
            raise ValueError('Unexpected error while loading ids file: ' + str(e))


def save_ids_file():
    global uuids, uuid_file
    with open(uuid_file, 'w') as file:
        file.write(json.dumps(uuids))


def get_grant_id(url):
    parsed_url = urlparse.urlparse(url)
    if parsed_url[2]:
        match = re.search(url_term_regex, parsed_url[2])
        if match.group(1):
            return match.group(1)
    return 0


def get_grant_duration(value):
    match = re.search(month_duration_regex, value)
    if match.group(1):
        return 'P' + match.group(1) + 'M'
    else:
        raise ValueError('Unknown duration format:' + value)
        return value


def is_desired(beautiful_soup):
    strategies = beautiful_soup.findAll('li', { "class" : "highlight-strategy" })
    for strategy in strategies:
        match = re.search(oer_regex, strategy.getText())
        if match:
            if match.group(2):
                return True
    return False

def collect(url):
    awarder = {
        "@id":"urn:uuid:0801e4d4-3c7e-11e5-9f0e-54ee7558c81f"
    }
    action = {
        "@type":"schema:Action"
    }
    agent = {}
    location = {
        "@type":"schema:Place"
    }
    address = {
        "@type":"schema:PostalAddress"
    }

    result = get_header()
    grant_id = get_grant_id(url)
    result['frapo:hasGrantNumber'] = grant_id
    result['@id'] = get_uuid('hewlett_grant_' + grant_id)
    action['@id'] = get_uuid('hewlett_action_' + grant_id)
    agent['@id'] = get_uuid('hewlett_agent_' + grant_id)
    soup = get_soup_from_page(url)

    if not is_desired(soup):
        return None

    if hasattr(soup, 'h1'):
        agent['schema:name'] = {
            "@language": "en",
            "@value": soup.find('h1').getText()
        }
        print(soup.find('h1').getText()) # for status control
    if hasattr(soup, 'h3'):
        action['schema:name'] = {
            "@language": "en",
            "@value": soup.find('h3').getText()
        }
    address_tags = soup.findAll('div', { "class" : "aboutgrantee-address" })
    for address_tag in address_tags:
        address_no_span = re.search(address_without_span_regex, (str(address_tag)))
        address_split = re.split(address_split_regex, address_no_span.group(0))
        # TODO: addresses are formatted even more heterogeneous in the current hewlett page design
        # TODO: ==> find 'intelligent' rules to match street vs. locality vs. country
        for i, splitpart in enumerate(address_split):
            if i == 0:
                address['schema:streetAddress'] = address_split[0]
            if i == 1:
                address['schema:addressLocality'] = address_split[1]
            if i == 2:
                address['schema:addressCountry'] = address_split[2]
    overviews = soup.findAll('div', { "class" : "grant-overview" })
    for overview in overviews:
        result['schema:description'] = {
            "@language":"en",
            "@value":overview.getText()
        }
    for divs in soup.findAll('li', {'class' : 'highlight'}):
        label = None
        value = None
        for div in divs.findAll('div', {'class' : 'highlights-label'}):
            label = div.getText()
        for div in divs.findAll('div', {'class' : 'highlights-value'}):
            value = div.getText()
        if label in grant_mapping:
            result[grant_mapping[label]] = value
        elif label.__eq__('Term'):
            duration = get_grant_duration(value)
            result['schema:duration'] = duration
            action['schema:duration'] = duration
    location['schema:address'] = address
    agent['schema:location'] = location
    action['schema:agent'] = agent
    result['frapo:funds'] = action
    result['frapo:is_awarded_by'] = awarder
    return json.dumps(result, indent=2)


def crawl_page(url):
    links = []
    soup = get_soup_from_page(url)
    if soup:
        for anchor in soup.findAll('a', {'class' : 'listing-highlight-link'}):
            link = anchor.get('href')
            # print ('href: ' + `link`)
            links.append(link)
    return links


def next_page(url, count):
    url_split = re.split(re.compile(r"[?]"), url)
    page = url_split[0]
    page += 'page/' + str(count) + '/?' + url_split[1]
    print('page: ' + `page`) # for status control
    return crawl_page(page)


def get_page_number(url):
    match = re.search(page_regex, url)
    if match:
        if match.group(1):
            return int(match.group(1))
    return 1


def get_url_without_page(url):
    match = re.sub(url_page_regex, '', url)
    if not match:
        return url
    return match


def import_hewlett_data(url):
    imports = []
    current_number = get_page_number(url)
    url_no_page = get_url_without_page(url)
    while 1:
        links = next_page(url_no_page, current_number)
        if links.__eq__([]):
            break
        for link in links:
            json = collect(link)
            if json != None:
                imports.append(json)
        current_number += 1
    return imports


def write_into_file(imports, filename):
    output_file = open(filename, "w")
    count = 1;
    output_file.write("[")
    for import_entry in imports:
        output_file.write(import_entry)
        if count < len(imports):
            output_file.write(",\n")
            count += 1
    output_file.write("]")
    output_file.close()


def main():
    if len(sys.argv) != 3:
        print 'Usage: python <path>/<to>/import.py <import_url> <path>/<to>/<destination_file.json>'
        # typical usage:
        # python import/hewlett/import.py 'http://www.hewlett.org/grants/?search=&search_year=&search_program=31392' 'import/hewlett/testconsole_01.json'
        return
    load_ids_file()
    imports = import_hewlett_data(sys.argv[1])
    write_into_file(imports, sys.argv[2])
    save_ids_file()

if __name__ == "__main__":
    main()
