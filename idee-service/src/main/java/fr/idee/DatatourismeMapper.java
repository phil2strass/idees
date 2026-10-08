package fr.idee;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatatourismeMapper {
    private final JdbcTemplate db;
    private final CalendarEngine calendar;
    private final DatatourismeDetails details;
    private static final ZoneId PARIS=ZoneId.of("Europe/Paris");
    private static final Map<String,String> DAYS=Map.of("Monday","MO","Tuesday","TU","Wednesday","WE",
            "Thursday","TH","Friday","FR","Saturday","SA","Sunday","SU");
    public DatatourismeMapper(JdbcTemplate db,CalendarEngine calendar,DatatourismeDetails details) { this.db=db; this.calendar=calendar; this.details=details; }

    public long upsert(JsonNode event,String department,UUID run) {
        UUID uuid=UUID.fromString(event.path("uuid").asText());
        String title=localized(event.path("label"));
        if (title.isBlank()) title="Événement DATAtourisme "+uuid;
        String description=joinDescriptions(event,"description"),summary=joinDescriptions(event,"shortDescription");
        if (summary.isBlank()) summary=description;
        boolean obsolete=event.path("isObsolete").asBoolean(false);
        Long id=db.queryForObject("""
            INSERT INTO idee_outing(slug,title,summary,description,kind,status,source_name,external_id,source_url)
            VALUES(?,?,?,?,'event',?,'datatourisme',?,?)
            ON CONFLICT(source_name,external_id) WHERE source_name IS NOT NULL DO UPDATE
            SET title=excluded.title,summary=excluded.summary,description=excluded.description,status=excluded.status,
                source_url=excluded.source_url,updated_at=now()
            RETURNING id
            """,Long.class,"datatourisme-"+uuid,clip(title,220),summary,description,obsolete?"archived":"published",uuid.toString(),event.path("uri").asText(null));
        db.update("""
            INSERT INTO idee_datatourisme_event(uuid,outing_id,payload) VALUES(?,?,?::jsonb)
            ON CONFLICT(uuid) DO UPDATE SET payload=excluded.payload,imported_at=now()
            """,uuid,id,event.toString());
        db.update("""
            INSERT INTO idee_datatourisme_presence(uuid,department,seen_run) VALUES(?,?,?)
            ON CONFLICT(uuid,department) DO UPDATE SET seen_run=excluded.seen_run,active=true
            """,uuid,department,run);
        place(id,event,department,title);
        db.update("DELETE FROM idee_datatourisme_period WHERE uuid=?",uuid);
        db.update("DELETE FROM idee_schedule WHERE outing_id=?",id);
        int position=0;
        for (JsonNode period:values(event.path("takesPlaceAt"))) {
            Long schedule=null; String error=null;
            Period parsed=null;
            try { parsed=parsePeriod(period); }
            catch (IllegalArgumentException | DateTimeException e) { error="Période non interprétable : "+e.getMessage(); }
            if (parsed!=null) {
                schedule=db.queryForObject("""
                    INSERT INTO idee_schedule(outing_id,label,starts_local,ends_local,all_day,rrule,start_time_known,end_time_known)
                    VALUES(?,?,?,?,?,?,?,?) RETURNING id
                    """,Long.class,id,"DATAtourisme — période "+(position+1),Timestamp.valueOf(parsed.start),Timestamp.valueOf(parsed.end),parsed.allDay,parsed.rule,parsed.startKnown,parsed.endKnown);
            }
            db.update("INSERT INTO idee_datatourisme_period(uuid,position,payload,schedule_id,parse_error) VALUES(?,?,?::jsonb,?,?)",uuid,position++,period.toString(),schedule,error);
        }
        details.replace(uuid,id,event);
        prices(id,event);
        categories(id,event);
        return id;
    }

    static record Period(LocalDateTime start,LocalDateTime end,boolean allDay,String rule,boolean startKnown,boolean endKnown) { }
    static Period parsePeriod(JsonNode p) {
        LocalDate first=LocalDate.parse(p.path("startDate").asText());
        LocalDate last=p.hasNonNull("endDate")?LocalDate.parse(p.get("endDate").asText()):first;
        if (last.isBefore(first)) throw new IllegalArgumentException("fin antérieure au début");
        boolean startKnown=p.hasNonNull("startTime"),endKnown=p.hasNonNull("endTime");
        boolean allDay=!startKnown && !endKnown;
        LocalTime start=startKnown?LocalTime.parse(p.get("startTime").asText()):LocalTime.MIDNIGHT;
        LocalTime end=endKnown?LocalTime.parse(p.get("endTime").asText()):LocalTime.MIDNIGHT;
        var days=new TreeSet<String>();
        for (JsonNode day:values(p.path("appliesOnDay"))) {
            String key=day.isTextual()?day.asText():day.path("key").asText();
            String value=DAYS.get(key);
            if (value==null) throw new IllegalArgumentException("jour non pris en charge");
            days.add(value);
        }
        String rule=null;
        LocalDateTime begins=first.atTime(start),ends;
        if (!days.isEmpty() || !allDay && last.isAfter(first)) {
            // DTSTART itself must be an allowed weekday, including for engines that include it unconditionally.
            while (!days.isEmpty() && !days.contains(DAYS.get(begins.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH)))) begins=begins.plusDays(1);
            if (begins.toLocalDate().isAfter(last)) throw new IllegalArgumentException("aucun jour dans la période");
            ends=begins.toLocalDate().atTime(end);
            if (!ends.isAfter(begins)) ends=ends.plusDays(1);
            String until=last.atTime(23,59,59).atZone(PARIS).toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            rule="FREQ="+(days.isEmpty()?"DAILY":"WEEKLY")+(days.isEmpty()?"":";BYDAY="+String.join(",",days))+";UNTIL="+until;
        } else {
            ends=allDay?last.plusDays(1).atStartOfDay():last.atTime(end);
            if (!ends.isAfter(begins)) ends=ends.plusDays(1);
        }
        if (PARIS.getRules().getValidOffsets(begins).isEmpty() || PARIS.getRules().getValidOffsets(ends).isEmpty())
            throw new IllegalArgumentException("heure inexistante au changement d'heure");
        return new Period(begins,ends,allDay,rule,startKnown,endKnown);
    }

    private void place(long id,JsonNode event,String department,String title) {
        JsonNode location=first(event.path("isLocatedAt")),address=first(location.path("address"));
        String city=localized(address.path("addressLocality"));
        if (city.isBlank()) city=localized(address.path("hasAddressCity").path("label"));
        if (city.isBlank()) return;
        Long place=db.queryForObject("SELECT place_id FROM idee_outing WHERE id=?",Long.class,id);
        String name=localized(location.path("label")); if (name.isBlank()) name=title;
        String street=localized(address.path("streetAddress"));
        if (place==null) place=db.queryForObject("INSERT INTO idee_place(name,city,department) VALUES(?,?,?) RETURNING id",Long.class,clip(name,200),clip(city,120),department);
        db.update("UPDATE idee_place SET name=?,city=?,department=?,address=?,postal_code=?,latitude=?,longitude=? WHERE id=?",
                clip(name,200),clip(city,120),department,street,clip(address.path("postalCode").asText(""),10),
                coordinate(location.path("geo").path("latitude"),90),coordinate(location.path("geo").path("longitude"),180),place);
        db.update("UPDATE idee_outing SET place_id=? WHERE id=?",place,id);
    }

    private void prices(long id,JsonNode event) {
        db.update("DELETE FROM idee_price WHERE outing_id=?",id);
        for (JsonNode offer:values(event.path("offers"))) for (JsonNode price:values(offer.path("priceSpecification"))) {
            String description=localized(price.path("additionalInformation"));
            JsonNode amount=price.hasNonNull("price")?price.get("price"):price.path("minPrice");
            java.math.BigDecimal number=null;
            try { number=new java.math.BigDecimal(amount.asText()); }
            catch (NumberFormatException ignored) { }
            if (number!=null && (number.signum()<0 || number.compareTo(new java.math.BigDecimal("99999999.99"))>0)) number=null;
            String type=number==null?"unknown":number.signum()==0?"free":price.hasNonNull("price")?"fixed":"from";
            String currency=price.path("priceCurrency").asText("EUR"); if (!currency.matches("[A-Z]{3}")) currency="EUR";
            db.update("INSERT INTO idee_price(outing_id,label,amount,currency,price_type,conditions) VALUES(?,?,?,?,?,?)",
                    id,clip(description.isBlank()?"Tarif communiqué":description,120),number,currency,type,description);
        }
    }

    private void categories(long id,JsonNode event) {
        db.update("DELETE FROM idee_outing_category WHERE outing_id=?",id);
        Set<String> categories=new HashSet<>();
        for (JsonNode type:values(event.path("type"))) {
            String category=switch (type.asText()) {
                case "Concert","ShowEvent","TheaterEvent","Festival" -> "spectacle";
                case "Exhibition","ExhibitionEvent","CulturalEvent","GuidedTour" -> "culture";
                case "Market","BricABrac","GarageSale" -> "marche";
                case "SportsEvent","WalkingTour" -> "nature";
                case "Course","TrainingWorkshop","Conference" -> "atelier";
                default -> null;
            };
            if (category!=null) categories.add(category);
        }
        for (String category:categories) db.update("INSERT INTO idee_outing_category(outing_id,category_id) SELECT ?,id FROM idee_category WHERE slug=?",id,category);
    }

    public void project(List<Long> ids) {
        if (ids.isEmpty()) return;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var definitions=db.queryForList("SELECT id,timezone,starts_local::text,ends_local::text,rrule,place_id FROM idee_schedule WHERE outing_id IN ("+placeholders+")",ids.toArray());
        definitions.forEach(s->s.put("exceptions",List.of()));
        LocalDate today=LocalDate.now(PARIS);
        for (var o:calendar.expand(definitions,today.minusDays(31),today.plusDays(550)))
            db.update("INSERT INTO idee_occurrence(schedule_id,original_start_local,starts_at,ends_at,status,place_id,note) VALUES(?,?,?,?,?,?,?)",
                    o.get("schedule_id"),Timestamp.valueOf(LocalDateTime.parse((String)o.get("original_start_local"))),OffsetDateTime.parse((String)o.get("starts_at")),OffsetDateTime.parse((String)o.get("ends_at")),o.get("status"),o.get("place_id"),o.get("note"));
    }

    static List<JsonNode> values(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) return List.of(node);
        List<JsonNode> result=new ArrayList<>(); node.forEach(result::add); return result;
    }
    static JsonNode first(JsonNode node) { return node.isArray()?(node.isEmpty()?com.fasterxml.jackson.databind.node.MissingNode.getInstance():node.get(0)):node; }
    static String localized(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isArray()) return String.join("\n",values(node).stream().map(DatatourismeMapper::localized).filter(s->!s.isBlank()).toList());
        if (node.has("@fr")) return localized(node.get("@fr"));
        if (node.has("fr")) return localized(node.get("fr"));
        return "";
    }
    private static String joinDescriptions(JsonNode event,String key) {
        return String.join("\n\n",values(event.path("hasDescription")).stream().map(d->localized(d.path(key))).filter(s->!s.isBlank()).toList());
    }
    private static String clip(String value,int size) { return value.substring(0,Math.min(size,value.length())); }
    private static Double coordinate(JsonNode n,int limit) { double d=n.asDouble(Double.NaN); return Double.isFinite(d)&&Math.abs(d)<=limit?d:null; }
}
