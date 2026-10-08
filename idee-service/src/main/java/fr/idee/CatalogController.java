package fr.idee;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api")
public class CatalogController {
 private final JdbcTemplate db;
 private final CalendarEngine calendar;
 private final OutingSourceDetails sourceDetails;
 private final OutingDescriptions descriptions;
 public CatalogController(JdbcTemplate db, CalendarEngine calendar,OutingSourceDetails sourceDetails,OutingDescriptions descriptions) { this.db = db; this.calendar=calendar; this.sourceDetails=sourceDetails; this.descriptions=descriptions; }
 @GetMapping("/health") public Map<String,Object> health() { db.queryForObject("SELECT 1",Integer.class);return Map.of("status","ok"); }
 @GetMapping("/categories") public List<Map<String,Object>> categories() {
  return db.queryForList("SELECT id, slug, name, icon FROM idee_category ORDER BY id");
 }
 @GetMapping("/calendar") public Map<String,Object> calendar() {
  var rows=db.queryForList("SELECT from_date::text AS \"from\", until_date::text AS until, generated_at::text AS \"generatedAt\" FROM idee_calendar_projection");
  return rows.isEmpty()? Map.of("available",false,"dateQuery","on-demand"):rows.getFirst();
 }
 @GetMapping("/outings")
 @Transactional(readOnly=true)
 public List<Map<String,Object>> outings(
  @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
  @RequestParam(defaultValue="false") boolean includePermanent,
  @RequestParam(defaultValue="false") boolean includeCancelled,
  @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
  if(limit<1||limit>200||offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit doit etre entre 1 et 200, offset positif.");
  if(date!=null&&(date.getYear()<1900||date.getYear()>2200))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date attendue entre 1900 et 2200.");
  Map<Long,List<Map<String,Object>>> occurrences=date==null?null:forDay(date,includeCancelled);
  List<Object> parameters=new ArrayList<>();String filter="";
  if(occurrences!=null) {
   String ids=occurrences.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
   filter=" AND (o.id IN ("+(ids.isEmpty()?"NULL":ids)+")"+(includePermanent?" OR o.kind='permanent'":"")+")";
  }
  parameters.add(limit);parameters.add(offset);
  var rows=db.queryForList(base()+" WHERE o.status='published'"+filter+" ORDER BY o.id LIMIT ? OFFSET ?",parameters.toArray());
  for(var row:rows) enrich(row,occurrences,includeCancelled,date);
  return rows;
 }
 @GetMapping("/outings/{slug}")
 @Transactional(readOnly=true)
 public Map<String,Object> outing(@PathVariable String slug) {
  var rows=db.queryForList(base()+" WHERE o.status='published' AND o.slug=?",slug);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sortie introuvable");
  var row=rows.getFirst();enrich(row,null,true,null,true);
  row.put("sourceDetails",sourceDetails.forOuting(row.get("id")));
  row.put("descriptions",descriptions.forOuting(row.get("id")));
  row.put("translations",descriptions.translationsForOuting(row.get("id")));
  return row;
 }
 @GetMapping("/catalog")
 @Transactional(readOnly=true)
 public Map<String,Object> catalog(
   @RequestParam(required=false) String period,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
   @RequestParam(defaultValue="") String department, @RequestParam(defaultValue="") String city,
   @RequestParam(defaultValue="") String category, @RequestParam(defaultValue="") String query,
   @RequestParam(defaultValue="fr") String language,
   @RequestParam(defaultValue="false") boolean freeOnly,
   @RequestParam(defaultValue="40") int limit, @RequestParam(defaultValue="0") int offset) {
   if(!Set.of("fr","en","de","it","nl","es").contains(language))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Langue invalide.");
   if(limit<1||limit>40||offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Pagination invalide.");
   if(period==null)period=date==null?"today":"date";
   if(date!=null&&(date.getYear()<1900||date.getYear()>2200))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date attendue entre 1900 et 2200.");
   if(period.equals("date")&&date==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Une date précise est requise.");
   LocalDate today=LocalDate.now(ZoneId.of("Europe/Paris"));
   LocalDate[] range=period.equals("date")?new LocalDate[]{date,date.plusDays(1)}:periodRange(period,today);
   Map<Long,List<Map<String,Object>>> occurrences=range==null?null:forRange(range[0],range[1],false);
   StringBuilder where=new StringBuilder(" WHERE o.status='published'");
   List<Object> args=new ArrayList<>();
   if(occurrences!=null) {
     String ids=occurrences.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
     where.append(" AND o.id IN (").append(ids.isEmpty()?"NULL":ids).append(")");
   }
   if(period.equals("permanent"))where.append(" AND o.kind='permanent'");
   if(!department.isBlank()){where.append(" AND p.department=?");args.add(department);}
   if(!city.isBlank()){where.append(" AND ").append(normalizedSql("p.city")).append("=?");args.add(normalize(city));}
   if(!category.isBlank()) {
     where.append(" AND EXISTS (SELECT 1 FROM idee_outing_category oc JOIN idee_category c ON c.id=oc.category_id WHERE oc.outing_id=o.id AND c.slug=?)");args.add(category);
   }
   if(!query.isBlank()) {
     where.append(" AND (strpos(").append(normalizedSql("concat_ws(' ',o.title,o.summary,p.city)")).append(",?)>0");args.add(normalize(query));
     if(!language.equals("fr")) {
       where.append(" OR EXISTS (SELECT 1 FROM idee_outing_translation t JOIN idee_translation_source ts ON ts.outing_id=t.outing_id AND ts.source_hash=t.source_hash WHERE t.outing_id=o.id AND t.language=? AND strpos(")
         .append(normalizedSql("concat_ws(' ',t.title,t.description_courte)")).append(",?)>0)");
       args.add(language);args.add(normalize(query));
     }
     where.append(")");
   }
   if(freeOnly) {
     String valid=" FROM idee_price price WHERE price.outing_id=o.id AND (valid_from IS NULL OR valid_from<=?) AND (valid_until IS NULL OR valid_until>=?)";
     where.append(" AND EXISTS (SELECT 1").append(valid).append(") AND NOT EXISTS (SELECT 1").append(valid).append(" AND price_type<>'free')");
     LocalDate priceDate=range==null?today:range[0];
     args.addAll(List.of(priceDate,priceDate,priceDate,priceDate));
   }
   long total=db.queryForObject("SELECT count(*) FROM idee_outing o LEFT JOIN idee_place p ON p.id=o.place_id"+where,Long.class,args.toArray());
   args.add(limit);args.add(offset);
   var items=db.queryForList(base()+where+" ORDER BY o.id LIMIT ? OFFSET ?",args.toArray());
   for(var item:items)enrich(item,occurrences,false,range==null?null:range[0]);
   descriptions.addCatalogueTranslations(items,language);
   return Map.of("items",items,"total",total,"hasMore",offset+items.size()<total);
 }
 static LocalDate[] periodRange(String period,LocalDate today) {
   LocalDate monday=today.minusDays(today.getDayOfWeek().getValue()-1);
   return switch(period) {
     case "today" -> new LocalDate[]{today,today.plusDays(1)};
     case "weekend" -> new LocalDate[]{monday.plusDays(5),monday.plusDays(7)};
     case "week" -> new LocalDate[]{monday,monday.plusDays(7)};
     case "next-week" -> new LocalDate[]{monday.plusDays(7),monday.plusDays(14)};
     case "month" -> new LocalDate[]{today,today.withDayOfMonth(1).plusMonths(1)};
     case "", "permanent" -> null;
     default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Période invalide.");
   };
 }
 private static String normalize(String value) {
   return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
 }
 private static String normalizedSql(String expression) {
   return "translate(lower("+expression+"),'àâäéèêëîïôöùûüçÿ','aaaeeeeiioouuucy')";
 }
 private String base(){return """
   SELECT o.id, o.slug,
    COALESCE((SELECT r.path FROM idee_public_route r WHERE r.outing_id=o.id AND r.language='fr' AND r.canonical), '/sorties/'||o.slug) AS url,
    o.title, o.summary, o.description, o.kind, o.environment,
    (SELECT g.description_courte FROM idee_outing_description g
      JOIN idee_outing_description_source s ON s.outing_id=g.outing_id AND s.language=g.language AND s.description=g.source_description
      WHERE g.outing_id=o.id AND g.language='fr') AS description_courte,
    o.min_age AS "minAge", o.duration_minutes AS "durationMinutes", o.is_demo AS "isDemo",
    o.practical_info AS "practicalInfo", p.name AS "placeName", p.city, p.department,
    p.address, p.postal_code AS "postalCode", p.latitude, p.longitude,
    o.accessibility, o.booking_url AS "bookingUrl", o.website, o.source_url AS "sourceUrl",
    o.source_name AS "sourceName", o.external_id AS "externalId"
   FROM idee_outing o LEFT JOIN idee_place p ON p.id=o.place_id
   """;}
 private void enrich(Map<String,Object> row,Map<Long,List<Map<String,Object>>> daily,boolean includeCancelled,LocalDate date) {
   enrich(row,daily,includeCancelled,date,false);
 }
 private void enrich(Map<String,Object> row,Map<Long,List<Map<String,Object>>> daily,boolean includeCancelled,LocalDate date,boolean includeCurrentWeek) {
   Object id=row.get("id");
   var urls=new LinkedHashMap<String,String>(); urls.put("fr",(String)row.get("url"));
   for(var route:db.queryForList("SELECT language,path FROM idee_public_route WHERE outing_id=? AND canonical",id))
     urls.put((String)route.get("language"),(String)route.get("path"));
   row.put("urls",urls);
   row.put("categories", db.queryForList("SELECT c.slug,c.name FROM idee_category c JOIN idee_outing_category oc ON oc.category_id=c.id WHERE oc.outing_id=? ORDER BY c.id",id));
   LocalDate priceDate=date==null?LocalDate.now(ZoneId.of("Europe/Paris")):date;
   row.put("prices",db.queryForList("SELECT label,amount,price_type AS type,currency,conditions FROM idee_price WHERE outing_id=? AND (valid_from IS NULL OR valid_from<=?) AND (valid_until IS NULL OR valid_until>=?) ORDER BY amount NULLS LAST",id,priceDate,priceDate));
   row.put("images",db.queryForList("SELECT COALESCE(d.local_url,m.url) AS url,m.alt,m.credit,m.license,m.is_primary AS \"primary\" FROM idee_media m LEFT JOIN idee_image_download d ON d.source_url=m.url AND d.state='ready' WHERE m.outing_id=? ORDER BY m.is_primary DESC,m.position,m.id",id));
   row.put("schedules", db.queryForList("SELECT label,timezone,all_day AS \"allDay\" FROM idee_schedule WHERE outing_id=? AND enabled ORDER BY id",id));
   if(daily!=null) {
     var sessions=daily.getOrDefault(((Number)id).longValue(),List.of());
     row.put("occurrences",sessions);
     row.put("availability",sessions.isEmpty()?"to_confirm":"scheduled");
   } else row.put("occurrences",db.queryForList("""
     SELECT to_char(oc.starts_at, 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM') AS "startsAt", to_char(oc.ends_at, 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM') AS "endsAt", oc.status, oc.note,
      s.all_day AS "allDay", s.timezone, s.start_time_known AS "startTimeKnown", s.end_time_known AS "endTimeKnown",
      COALESCE(p.city,base.city) AS city, COALESCE(p.name,base.name) AS "placeName"
     FROM idee_occurrence oc JOIN idee_schedule s ON s.id=oc.schedule_id
     JOIN idee_outing o ON o.id=s.outing_id
     LEFT JOIN idee_place p ON p.id=COALESCE(oc.place_id,s.place_id)
     LEFT JOIN idee_place base ON base.id=o.place_id
     WHERE s.outing_id=? AND s.enabled AND oc.ends_at >
       CASE WHEN ? THEN date_trunc('week',now() AT TIME ZONE s.timezone) AT TIME ZONE s.timezone ELSE now() END
     """+(includeCancelled?"":" AND oc.status <> 'cancelled'")+" ORDER BY oc.starts_at",id,includeCurrentWeek));
 }
 private Map<Long,List<Map<String,Object>>> forDay(LocalDate day,boolean includeCancelled) {
   return forRange(day,day.plusDays(1),includeCancelled);
 }
 private Map<Long,List<Map<String,Object>>> forRange(LocalDate from,LocalDate until,boolean includeCancelled) {
   var schedules=db.queryForList("""
     SELECT s.id,s.outing_id,s.timezone,s.starts_local::text,s.ends_local::text,s.rrule,s.all_day,s.start_time_known,s.end_time_known,
       COALESCE(s.place_id,o.place_id) AS place_id
     FROM idee_schedule s JOIN idee_outing o ON o.id=s.outing_id WHERE s.enabled AND o.status='published' ORDER BY s.id
     """);
   Map<Long,Map<String,Object>> byId=new HashMap<>();
   for(var s:schedules){byId.put(((Number)s.get("id")).longValue(),s);s.put("exceptions",new ArrayList<Map<String,Object>>());}
   for(var exception:db.queryForList("SELECT schedule_id,original_start_local::text,action,starts_local::text,ends_local::text,place_id,note FROM idee_schedule_exception ORDER BY id")){
     var schedule=byId.get(((Number)exception.get("schedule_id")).longValue());
     if(schedule!=null){ @SuppressWarnings("unchecked") var list=(List<Map<String,Object>>)schedule.get("exceptions");list.add(exception); }
   }
   Map<Long,Map<String,Object>> places=new HashMap<>();
   for(var place:db.queryForList("SELECT id,name,city FROM idee_place"))places.put(((Number)place.get("id")).longValue(),place);
   Map<Long,List<Map<String,Object>>> result=new HashMap<>();
   for(var occurrence:calendar.expand(schedules,from,until)) {
     if(!includeCancelled&&"cancelled".equals(occurrence.get("status")))continue;
     var schedule=byId.get(((Number)occurrence.get("schedule_id")).longValue());
     var item=new HashMap<String,Object>();
     item.put("startsAt",occurrence.get("starts_at"));item.put("endsAt",occurrence.get("ends_at"));item.put("status",occurrence.get("status"));item.put("note",occurrence.get("note"));item.put("allDay",schedule.get("all_day"));item.put("timezone",schedule.get("timezone"));
     item.put("startTimeKnown",schedule.get("start_time_known"));item.put("endTimeKnown",schedule.get("end_time_known"));
     var place=occurrence.get("place_id")==null?null:places.get(((Number)occurrence.get("place_id")).longValue());
     item.put("city",place==null?null:place.get("city"));item.put("placeName",place==null?null:place.get("name"));
     result.computeIfAbsent(((Number)schedule.get("outing_id")).longValue(),k->new ArrayList<>()).add(item);
   }
   for(var sessions:result.values())sessions.sort(Comparator.comparing(x->OffsetDateTime.parse((String)x.get("startsAt")).toInstant()));
   return result;
 }
}
