package fr.idee;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/outings")
public class ImportController {
    private final JdbcTemplate db;
    private final CalendarEngine calendar;
    private final ObjectMapper json;
    public ImportController(JdbcTemplate db, CalendarEngine calendar, ObjectMapper json) {
        this.db=db; this.calendar=calendar; this.json=json;
    }
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String,Object>> create(@RequestBody JsonNode body) throws Exception {
        if (body.toString().length()>200_000) fail("La fiche est trop volumineuse.");
        fields(body,"sourceName externalId sourceUrl slug title summary description kind status environment place categorySlugs prices schedules images minAge durationMinutes practicalInfo");
        String source=text(body,"sourceName",100), external=text(body,"externalId",200);
        String sourceUrl=text(body,"sourceUrl",2000); url(sourceUrl);
        String slug=text(body,"slug",180);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) fail("slug doit contenir des minuscules, chiffres et tirets.");
        String title=text(body,"title",220), summary=text(body,"summary",2000), description=text(body,"description",20000);
        String kind=choice(body,"kind","event",Set.of("event","permanent"));
        String status=choice(body,"status","published",Set.of("published","draft"));
        String environment=choice(body,"environment","mixed",Set.of("mixed","indoor","outdoor"));
        String practical=optional(body,"practicalInfo",5000);
        Integer minAge=number(body,"minAge",0,120), duration=number(body,"durationMinutes",1,525600);
        JsonNode categories=array(body,"categorySlugs",1,20), schedules=array(body,"schedules",kind.equals("event")?1:0,20), prices=array(body,"prices",0,20), images=array(body,"images",0,12);
        validatePlace(body.path("place"));
        for(JsonNode c:categories) if(!c.isTextual() || c.asText().length()>100) fail("Categorie invalide.");
        if (new HashSet<>(json.convertValue(categories,List.class)).size()!=categories.size()) fail("Categories dupliquees.");
        for(JsonNode p:prices) validatePrice(p);
        int primaryImages=0;
        Set<String> imageUrls=new HashSet<>();
        for(JsonNode image:images) { validateImage(image);if(!imageUrls.add(image.get("url").asText()))fail("Image dupliquee.");if(image.path("primary").asBoolean(false))primaryImages++; }
        if(images.size()>0&&primaryImages!=1)fail("Une sortie avec des images doit avoir exactement une image principale.");
        for(JsonNode s:schedules) validateSchedule(s);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(body).toString().getBytes(StandardCharsets.UTF_8)));
        // Serialize retries of one source record. The unique index also protects direct writes.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",source+":"+external);
        var existing=db.queryForList("SELECT id,slug,import_hash FROM idee_outing WHERE source_name=? AND external_id=?",source,external);
        if (!existing.isEmpty()) {
            var row=existing.getFirst();
            if (!hash.equals(row.get("import_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cette source existe avec un contenu different ; aucune donnee n'a ete ecrasee.");
            return ResponseEntity.ok(Map.of("id",row.get("id"),"slug",row.get("slug"),"created",false));
        }
        List<Long> categoryIds=new ArrayList<>();
        for(JsonNode c:categories) {
            var ids=db.queryForList("SELECT id FROM idee_category WHERE slug=?",Long.class,c.asText());
            if(ids.isEmpty()) fail("Categorie inconnue : "+c.asText());
            categoryIds.add(ids.getFirst());
        }
        Long placeId=insertPlace(body.get("place"));
        Long id=db.queryForObject("""
          INSERT INTO idee_outing(slug,title,summary,description,kind,status,environment,place_id,source_name,external_id,source_url,import_hash,min_age,duration_minutes,practical_info)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
          """,Long.class,slug,title,summary,description,kind,status,environment,placeId,source,external,sourceUrl,hash,minAge,duration,practical);
        for(Long category:categoryIds) db.update("INSERT INTO idee_outing_category VALUES (?,?)",id,category);
        int imagePosition=0;
        for(JsonNode image:images) db.update("INSERT INTO idee_media(outing_id,url,alt,credit,license,position,is_primary) VALUES (?,?,?,?,?,?,?)",
                id,image.get("url").asText(),image.get("alt").asText(),optional(image,"credit",500),optional(image,"license",200),imagePosition++,image.path("primary").asBoolean(false));
        for(JsonNode p:prices) db.update("INSERT INTO idee_price(outing_id,label,amount,price_type,currency,conditions) VALUES (?,?,?,?,?,?)",
                id,p.get("label").asText(),p.path("amount").isNumber()?p.get("amount").decimalValue():null,p.get("type").asText(),p.path("currency").asText("EUR"),optional(p,"conditions",2000));
        List<Map<String,Object>> definitions=new ArrayList<>();
        for(JsonNode s:schedules) {
            Long schedulePlace=s.hasNonNull("place")?insertPlace(s.get("place")):null;
            Long sid=db.queryForObject("INSERT INTO idee_schedule(outing_id,label,starts_local,ends_local,timezone,all_day,rrule,place_id) VALUES(?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,id,s.get("label").asText(),ts(s,"startsLocal"),ts(s,"endsLocal"),"Europe/Paris",s.path("allDay").asBoolean(false),optional(s,"rrule",1000),schedulePlace);
            Map<String,Object> definition=new HashMap<>();
            definition.put("id",sid);definition.put("timezone","Europe/Paris");definition.put("starts_local",ts(s,"startsLocal").toLocalDateTime().toString());definition.put("ends_local",ts(s,"endsLocal").toLocalDateTime().toString());definition.put("rrule",optional(s,"rrule",1000));definition.put("place_id",schedulePlace);
            List<Map<String,Object>> exceptions=new ArrayList<>();
            for(JsonNode e:array(s,"exceptions",0,200)) {
                Long exceptionPlace=e.hasNonNull("place")?insertPlace(e.get("place")):null;
                db.update("INSERT INTO idee_schedule_exception(schedule_id,original_start_local,action,starts_local,ends_local,place_id,note) VALUES(?,?,?,?,?,?,?)",sid,ts(e,"originalStartLocal"),e.get("action").asText(),e.hasNonNull("startsLocal")?ts(e,"startsLocal"):null,e.hasNonNull("endsLocal")?ts(e,"endsLocal"):null,exceptionPlace,optional(e,"note",2000));
                Map<String,Object> exception=new HashMap<>();
                exception.put("original_start_local",local(e,"originalStartLocal").format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
                exception.put("action",e.get("action").asText());exception.put("note",optional(e,"note",2000));exception.put("place_id",exceptionPlace);
                if(e.hasNonNull("startsLocal")){exception.put("starts_local",local(e,"startsLocal").toString());exception.put("ends_local",local(e,"endsLocal").toString());}
                exceptions.add(exception);
            }
            definition.put("exceptions",exceptions);definitions.add(definition);
        }
        LocalDate today=LocalDate.now(ZoneId.of("Europe/Paris"));
        for(var occurrence:calendar.expand(definitions,today.minusDays(31),today.plusDays(550))) {
            db.update("INSERT INTO idee_occurrence(schedule_id,original_start_local,starts_at,ends_at,status,place_id,note) VALUES(?,?,?,?,?,?,?)",
                    occurrence.get("schedule_id"),Timestamp.valueOf(LocalDateTime.parse((String)occurrence.get("original_start_local"))),OffsetDateTime.parse((String)occurrence.get("starts_at")),OffsetDateTime.parse((String)occurrence.get("ends_at")),occurrence.get("status"),occurrence.get("place_id"),occurrence.get("note"));
        }
        return ResponseEntity.created(URI.create("/api/outings/"+slug)).body(Map.of("id",id,"slug",slug,"created",true));
    }
    @PostMapping("/{slug}/images")
    @Transactional
    public ResponseEntity<Map<String,Object>> addImages(@PathVariable String slug,@RequestBody JsonNode body) {
        if(body.toString().length()>50_000)fail("La liste d'images est trop volumineuse.");
        fields(body,"images");JsonNode images=array(body,"images",1,12);
        Set<String> urls=new HashSet<>();int requestedPrimary=0;
        for(JsonNode image:images){validateImage(image);if(!urls.add(image.get("url").asText()))fail("Image dupliquee.");if(image.path("primary").asBoolean(false))requestedPrimary++;}
        var outings=db.queryForList("SELECT id FROM idee_outing WHERE slug=? FOR UPDATE",Long.class,slug);
        if(outings.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sortie introuvable.");
        Long id=outings.getFirst();
        var existing=db.queryForList("SELECT url,alt,credit,license,is_primary,position FROM idee_media WHERE outing_id=? ORDER BY position,id",id);
        if(existing.size()+images.size()>12)fail("Une sortie accepte au maximum 12 images.");
        int existingPrimary=(int)existing.stream().filter(row->Boolean.TRUE.equals(row.get("is_primary"))).count();
        List<JsonNode> fresh=new ArrayList<>();
        for(JsonNode image:images){
            var same=existing.stream().filter(row->Objects.equals(row.get("url"),image.get("url").asText())).findFirst();
            if(same.isPresent()){
                var row=same.get();
                if(!Objects.equals(row.get("alt"),image.get("alt").asText())||!Objects.equals(row.get("credit"),optional(image,"credit",500))||!Objects.equals(row.get("license"),optional(image,"license",200))||!Objects.equals(row.get("is_primary"),image.path("primary").asBoolean(false)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Cette image existe avec des metadonnees differentes.");
            } else fresh.add(image);
        }
        int freshPrimary=(int)fresh.stream().filter(image->image.path("primary").asBoolean(false)).count();
        if(existingPrimary+freshPrimary!=1)fail("La galerie doit avoir exactement une image principale.");
        int position=existing.stream().mapToInt(row->((Number)row.get("position")).intValue()).max().orElse(-1)+1;
        for(JsonNode image:fresh)db.update("INSERT INTO idee_media(outing_id,url,alt,credit,license,position,is_primary) VALUES (?,?,?,?,?,?,?)",id,image.get("url").asText(),image.get("alt").asText(),optional(image,"credit",500),optional(image,"license",200),position++,image.path("primary").asBoolean(false));
        var result=db.queryForList("SELECT url,alt,credit,license,is_primary AS \"primary\" FROM idee_media WHERE outing_id=? ORDER BY is_primary DESC,position,id",id);
        var payload=new LinkedHashMap<String,Object>();payload.put("id",id);payload.put("slug",slug);payload.put("added",fresh.size());payload.put("images",result);
        return fresh.isEmpty()?ResponseEntity.ok(payload):ResponseEntity.status(HttpStatus.CREATED).body(payload);
    }
    private void validateSchedule(JsonNode s) {
        fields(s,"label startsLocal endsLocal timezone allDay rrule exceptions place");text(s,"label",200);
        if(!s.path("timezone").asText("Europe/Paris").equals("Europe/Paris")) fail("Le fuseau des sorties est Europe/Paris.");
        if(s.has("allDay")&&!s.get("allDay").isBoolean()) fail("allDay doit etre un booleen.");
        boolean allDay=s.path("allDay").asBoolean(false); interval(s,allDay);
        if(s.hasNonNull("place"))validatePlace(s.get("place"));
        String rule=optional(s,"rrule",1000);
        if(rule!=null) {
            if(!rule.matches("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[^\\r\\n]+)*")) fail("RRULE invalide.");
            Set<String> seen=new HashSet<>();
            for(String part:rule.split(";")) {
                String[] item=part.split("=",2);
                if(item.length!=2 || !Set.of("FREQ","INTERVAL","COUNT","UNTIL","BYDAY","BYMONTH","BYMONTHDAY","BYSETPOS","WKST","BYYEARDAY","BYWEEKNO").contains(item[0]) || !seen.add(item[0])) fail("Propriete RRULE invalide.");
            }
            for(String part:rule.split(";")) {
                if(part.startsWith("INTERVAL=")||part.startsWith("COUNT=")) {
                    try { if(Long.parseLong(part.substring(part.indexOf('=')+1))<1)fail("COUNT et INTERVAL doivent etre positifs."); }
                    catch(NumberFormatException e){fail("COUNT ou INTERVAL invalide.");}
                }
            }
            if(seen.contains("COUNT") && seen.contains("UNTIL")) fail("RRULE : choisir COUNT ou UNTIL.");
        }
        Set<LocalDateTime> originals=new HashSet<>();
        for(JsonNode e:array(s,"exceptions",0,200)) {
            fields(e,"originalStartLocal action startsLocal endsLocal place note");
            if(!originals.add(local(e,"originalStartLocal")))fail("Exception dupliquee.");
            String action=text(e,"action",20);
            if(!Set.of("exclude","cancel","override","include").contains(action))fail("Action d'exception invalide.");
            if(Set.of("override","include").contains(action)){interval(e,allDay);if(e.hasNonNull("place"))validatePlace(e.get("place"));}
            else if(e.hasNonNull("startsLocal")||e.hasNonNull("endsLocal")||e.hasNonNull("place"))fail("Une annulation/exclusion ne deplace pas la seance.");
            optional(e,"note",2000);
        }
    }
    private void interval(JsonNode s,boolean allDay) {
        LocalDateTime start=local(s,"startsLocal"),end=local(s,"endsLocal");
        if(!end.isAfter(start))fail("La fin doit suivre le debut.");
        if(allDay&&(!start.toLocalTime().equals(LocalTime.MIDNIGHT)||!end.toLocalTime().equals(LocalTime.MIDNIGHT)))fail("Une journee entiere va de minuit a minuit (fin exclusive).");
        if(ZoneId.of("Europe/Paris").getRules().getValidOffsets(start).isEmpty()||ZoneId.of("Europe/Paris").getRules().getValidOffsets(end).isEmpty())fail("Heure inexistante lors du changement d'heure.");
    }
    private void validatePlace(JsonNode p) {
        fields(p,"name city department address postalCode");text(p,"name",200);text(p,"city",120);
        if(!Set.of("67","68").contains(text(p,"department",2)))fail("Departement attendu : 67 ou 68.");
        optional(p,"address",2000);optional(p,"postalCode",10);
    }
    private Long insertPlace(JsonNode p) { return db.queryForObject("INSERT INTO idee_place(name,city,department,address,postal_code) VALUES(?,?,?,?,?) RETURNING id",Long.class,p.get("name").asText(),p.get("city").asText(),p.get("department").asText(),optional(p,"address",2000),optional(p,"postalCode",10)); }
    private void validatePrice(JsonNode p) {
        fields(p,"label amount type currency conditions");text(p,"label",120);String type=text(p,"type",20);
        if(!Set.of("free","fixed","from","donation","unknown").contains(type))fail("Type de tarif invalide.");
        if(!p.path("currency").asText("EUR").equals("EUR"))fail("La devise attendue est EUR.");
        if(p.hasNonNull("amount")) {
            if(!p.get("amount").isNumber())fail("Montant invalide.");
            BigDecimal amount=p.get("amount").decimalValue();
            if(amount.signum()<0 || amount.compareTo(new BigDecimal("99999999.99"))>0 || amount.stripTrailingZeros().scale()>2)fail("Montant invalide.");
        }
        if(Set.of("free","fixed","from").contains(type)&&!p.path("amount").isNumber())fail("Un montant est requis pour ce tarif.");
        if(type.equals("free")&&p.get("amount").decimalValue().signum()!=0)fail("Un tarif gratuit vaut zero.");
        optional(p,"conditions",2000);
    }
    private void validateImage(JsonNode image) {
        fields(image,"url alt credit license primary");
        String imageUrl=text(image,"url",2000);url(imageUrl);
        if(!URI.create(imageUrl).getScheme().equals("https"))fail("Les images doivent utiliser HTTPS.");
        text(image,"alt",500);optional(image,"credit",500);optional(image,"license",200);
        if(image.has("primary")&&!image.get("primary").isBoolean())fail("primary doit etre un booleen.");
    }
    private JsonNode canonical(JsonNode value) {
        if(value.isObject()) { ObjectNode sorted=json.createObjectNode();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)sorted.set(name,canonical(value.get(name)));return sorted; }
        if(value.isArray()){ArrayNode sorted=json.createArrayNode();value.forEach(v->sorted.add(canonical(v)));return sorted;}return value;
    }
    private void fields(JsonNode body,String allowed) { if(body==null||!body.isObject())fail("Un objet JSON est requis.");Set<String> names=Set.of(allowed.split(" "));body.fieldNames().forEachRemaining(n->{if(!names.contains(n))fail("Champ inconnu : "+n);}); }
    private String text(JsonNode body,String key,int max) { String value=optional(body,key,max);if(value==null)fail("Champ requis : "+key);return value; }
    private String optional(JsonNode body,String key,int max) { JsonNode n=body.get(key);if(n==null||n.isNull())return null;if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>max)fail("Champ invalide : "+key);return n.asText(); }
    private String choice(JsonNode b,String key,String fallback,Set<String> values){String value=b.has(key)?text(b,key,40):fallback;if(!values.contains(value))fail("Valeur invalide : "+key);return value;}
    private JsonNode array(JsonNode b,String key,int min,int max){JsonNode a=b.get(key);if(a==null && min==0)return json.createArrayNode();if(a==null||!a.isArray()||a.size()<min||a.size()>max)fail("Liste invalide : "+key);return a;}
    private Integer number(JsonNode b,String key,int min,int max){JsonNode n=b.get(key);if(n==null||n.isNull())return null;if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)fail("Nombre invalide : "+key);return n.intValue();}
    private LocalDateTime local(JsonNode b,String key){try{LocalDateTime d=LocalDateTime.parse(text(b,key,30));if(d.getYear()<1900||d.getYear()>2200||d.getNano()!=0)fail("Date hors limites : "+key);return d;}catch(java.time.format.DateTimeParseException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date locale ISO attendue : "+key);}}
    private Timestamp ts(JsonNode b,String key){return Timestamp.valueOf(local(b,key));}
    private void url(String s){try{URI u=URI.create(s);if(u.getScheme()==null||!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)fail("URL source invalide.");}catch(IllegalArgumentException e){fail("URL source invalide.");}}
    private void fail(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
