package fr.idee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static fr.idee.DatatourismeMapper.values;
import static fr.idee.DatatourismeMapper.localized;

/** Source-specific structured data; unknown fields always remain in the complete event JSON. */
@Service
public class DatatourismeDetails {
    private final JdbcTemplate db;
    private static final Map<String,String> CONTACTS=Map.of(
            "hasContact","contact","hasBookingContact","booking","hasBeenCreatedBy","creator",
            "hasBeenPublishedBy","publisher","hasOwner","owner","hasAdministrativeContact","administrative",
            "hasCommunicationContact","communication");
    private static final List<String> TERMS=List.of("type","hasTheme","hasGeographicReach","availableLanguage",
            "isDedicatedTo","hasAudience","hasClientTarget","isEquippedWith","hasFacility","hasPractice","hasReview");
    public DatatourismeDetails(JdbcTemplate db) { this.db=db; }

    public void replace(UUID uuid,long outing,JsonNode event) {
        db.update("""
            UPDATE idee_datatourisme_event SET selection_version=?,source_identifier=?,organization_identifier=?,
                source_created_on=?,source_updated_on=?,source_updated_at=?,obsolete=? WHERE uuid=?
            """,DatatourismeImporter.SELECTION_VERSION,scalar(event.path("identifier")),scalar(event.path("hasOrganizationIdentifier")),
                date(event.path("creationDate")),date(event.path("lastUpdate")),instant(event.path("lastUpdateDatatourisme")),event.path("isObsolete").asBoolean(false),uuid);
        translations(uuid,event);
        contacts(uuid,event);
        terms(uuid,event);
        locations(uuid,event);
        resources(uuid,outing,event);
        // A producer/publisher is not necessarily the event organizer. Keep their roles distinct.
        String website=null;
        for (JsonNode contact:values(event.path("hasContact"))) for (JsonNode home:values(contact.path("homepage"))) {
            String candidate=scalar(home);
            if (website==null && webUrl(candidate,false)) website=candidate;
        }
        db.update("UPDATE idee_outing SET website=? WHERE id=?",website,outing);
    }

    private void translations(UUID uuid,JsonNode event) {
        db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=?",uuid);
        Map<String,Map<String,List<String>>> translated=new TreeMap<>();
        text(translated,"title",event.path("label"));
        text(translated,"comment",event.path("comment"));
        for (JsonNode d:values(event.path("hasDescription"))) {
            text(translated,"description",d.path("description"));
            text(translated,"summary",d.path("shortDescription"));
        }
        for (var language:translated.entrySet()) {
            var fields=language.getValue();
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,title,summary,description,comment) VALUES(?,?,?,?,?,?)",
                    uuid,language.getKey(),joined(fields,"title"),joined(fields,"summary"),joined(fields,"description"),joined(fields,"comment"));
        }
    }
    private static void text(Map<String,Map<String,List<String>>> result,String field,JsonNode node) {
        if (node.isArray()) { node.forEach(n->text(result,field,n)); return; }
        if (node.isTextual()) { addText(result,"und",field,node.asText()); return; }
        if (node.isObject()) node.fields().forEachRemaining(e->{
            String language=e.getKey().replaceFirst("^@","");
            if (language.matches("[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*"))
                for (JsonNode value:values(e.getValue())) if (value.isTextual()) addText(result,language,field,value.asText());
        });
    }
    private static void addText(Map<String,Map<String,List<String>>> result,String language,String field,String value) {
        if (!value.isBlank()) result.computeIfAbsent(language,k->new HashMap<>()).computeIfAbsent(field,k->new ArrayList<>()).add(value);
    }
    private static String joined(Map<String,List<String>> fields,String key) {
        return fields.containsKey(key)?String.join("\n\n",fields.get(key)):null;
    }

    private void contacts(UUID uuid,JsonNode event) {
        db.update("DELETE FROM idee_datatourisme_contact WHERE uuid=?",uuid);
        for (var mapping:CONTACTS.entrySet()) {
            int position=0;
            for (JsonNode contact:values(event.path(mapping.getKey()))) {
                String name=localized(contact.path("legalName"));
                if (name.isBlank()) name=localized(contact.path("label"));
                Long id=db.queryForObject("INSERT INTO idee_datatourisme_contact(uuid,role,position,name,payload) VALUES(?,?,?,?,?::jsonb) RETURNING id",
                        Long.class,uuid,mapping.getValue(),position++,name,contact.toString());
                for (String channel:List.of("email","telephone","fax","homepage")) for (JsonNode value:values(contact.path(channel))) {
                    String content=scalar(value);
                    if (content!=null && !content.isBlank()) db.update("INSERT INTO idee_datatourisme_contact_channel(contact_id,kind,value) VALUES(?,?,?) ON CONFLICT DO NOTHING",id,channel,content);
                }
            }
        }
    }
    private void terms(UUID uuid,JsonNode event) {
        db.update("DELETE FROM idee_datatourisme_term WHERE uuid=?",uuid);
        for (String property:TERMS) {
            int position=0;
            for (JsonNode term:values(event.path(property))) {
                String key=term.isValueNode()?scalar(term):scalar(term.path("key"));
                if (key==null) key=scalar(term.path("uri"));
                db.update("INSERT INTO idee_datatourisme_term(uuid,property,position,term_key,labels,payload) VALUES(?,?,?,?,?::jsonb,?::jsonb)",
                        uuid,property,position++,key,term.has("label")?term.get("label").toString():"{}",term.toString());
            }
        }
    }
    private void locations(UUID uuid,JsonNode event) {
        db.update("DELETE FROM idee_datatourisme_location WHERE uuid=?",uuid);
        int position=0;
        for (JsonNode location:values(event.path("isLocatedAt"))) {
            List<JsonNode> addresses=values(location.path("address"));
            if (addresses.isEmpty()) addresses=List.of(MissingNode.getInstance());
            int addressPosition=0;
            for (JsonNode address:addresses) {
                JsonNode city=address.path("hasAddressCity");
                String cityName=localized(address.path("addressLocality"));
                if (cityName.isBlank()) cityName=localized(city.path("label"));
                db.update("""
                    INSERT INTO idee_datatourisme_location(uuid,position,address_position,name,street_address,postal_code,city,
                        insee_code,department_code,latitude,longitude,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    """,uuid,position,addressPosition++,localized(location.path("label")),localized(address.path("streetAddress")),scalar(address.path("postalCode")),cityName,
                        scalar(city.path("insee")),scalar(city.path("isPartOfDepartment").path("insee")),
                        coordinate(location.path("geo").path("latitude"),90),coordinate(location.path("geo").path("longitude"),180),location.toString());
            }
            position++;
        }
    }

    private void resources(UUID uuid,long outing,JsonNode event) {
        db.update("DELETE FROM idee_datatourisme_resource WHERE uuid=?",uuid);
        db.update("DELETE FROM idee_media WHERE outing_id=?",outing);
        Set<String> rendered=new HashSet<>(); int imagePosition=0;
        // Explicit main images take precedence; other image resources extend the gallery.
        for (String field:List.of("hasMainRepresentation","hasRepresentation")) {
            int representationPosition=0;
            for (JsonNode representation:values(event.path(field))) {
                String credit=annotations(representation,"credits"),license=annotations(representation,"rights");
                String title=localized(representation.path("label"));
                if (title.isBlank()) title=localized(event.path("label"));
                int resourcePosition=0;
                JsonNode related=representation.has("hasRelatedResource")?representation.get("hasRelatedResource"):representation.path("ebucore:hasRelatedResource");
                for (JsonNode resource:values(related)) {
                    String mime=scalar(resource.path("mimeType"));
                    if (mime==null) mime=scalar(resource.path("format"));
                    String resourceLicense=localized(resource.path("dc:rights"));
                    if (resourceLicense.isBlank()) resourceLicense=license;
                    int locatorPosition=0;
                    for (JsonNode locator:values(resource.has("locator")?resource.get("locator"):resource.path("ebucore:locator"))) {
                        String url=scalar(locator); if (url==null || url.isBlank()) continue;
                        boolean image=isImage(url,mime);
                        db.update("""
                            INSERT INTO idee_datatourisme_resource(uuid,role,representation_position,resource_position,locator_position,
                                url,media_type,is_image,title,credit,license,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                            """,uuid,field.equals("hasMainRepresentation")?"main":"representation",representationPosition,resourcePosition,locatorPosition++,
                                url,mime,image,title,credit,resourceLicense,representation.toString());
                        if (image && webUrl(url,true) && rendered.add(url)) {
                            db.update("INSERT INTO idee_media(outing_id,url,alt,credit,license,position,is_primary) VALUES(?,?,?,?,?,?,?)",
                                    outing,url,title,credit,resourceLicense,imagePosition,imagePosition==0);
                            imagePosition++;
                        }
                    }
                    resourcePosition++;
                }
                representationPosition++;
            }
        }
    }
    static boolean isImage(String url,String mime) {
        if (mime!=null && !mime.isBlank()) return mime.toLowerCase(Locale.ROOT).startsWith("image/");
        try { return Objects.toString(URI.create(url).getPath(),"").toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|gif|webp|avif|svg)"); }
        catch (IllegalArgumentException e) { return false; }
    }
    static boolean webUrl(String value,boolean httpsOnly) {
        if (value==null) return false;
        try {
            URI uri=URI.create(value);
            return uri.getHost()!=null && uri.getUserInfo()==null && ("https".equals(uri.getScheme()) || !httpsOnly && "http".equals(uri.getScheme()));
        } catch (IllegalArgumentException e) { return false; }
    }
    private static String annotations(JsonNode node,String property) {
        return String.join("\n",values(node.path("hasAnnotation")).stream().map(a->localized(a.path(property))).filter(s->!s.isBlank()).toList());
    }
    private static String scalar(JsonNode value) { return value.isValueNode() && !value.isNull()?value.asText():null; }
    private static LocalDate date(JsonNode value) {
        try { return LocalDate.parse(value.asText()); } catch (DateTimeException e) { return null; }
    }
    private static OffsetDateTime instant(JsonNode value) {
        try { return OffsetDateTime.parse(value.asText()); } catch (DateTimeException e) { return null; }
    }
    private static Double coordinate(JsonNode value,int max) {
        double d=value.asDouble(Double.NaN); return Double.isFinite(d)&&Math.abs(d)<=max?d:null;
    }
}
