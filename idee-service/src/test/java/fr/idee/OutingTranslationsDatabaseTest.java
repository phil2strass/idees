package fr.idee;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false"})
class OutingTranslationsDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    private boolean safe;
    @BeforeEach void checkSchema() {
        safe=db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"); assertTrue(safe);
    }
    @AfterEach void cleanup() {
        if(safe) new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.update("DELETE FROM idee_datatourisme_event WHERE outing_id IN (SELECT id FROM idee_outing WHERE slug LIKE 'translation-%')");
            db.update("DELETE FROM idee_outing WHERE slug LIKE 'translation-%'");
            db.update("DELETE FROM idee_translation_batch"); db.update("DELETE FROM idee_description_batch");
        });
    }
    long outing(String slug) {
        long id=db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status) VALUES(?,'Exposition Wurth','Résumé','Source française','event','published') RETURNING id",Long.class,slug);
        db.update("INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version) VALUES(?,'fr','Source française','Présentation française','Résumé français','test',1)",id);
        // Simulate an existing outing from before automatic translation, for backfill tests.
        db.update("DELETE FROM idee_outing_translation_job WHERE outing_id=?",id);
        db.update("DELETE FROM idee_outing_description_job WHERE outing_id=?",id);
        return id;
    }
    OutingTranslationWorker worker(OpenAiTranslations client) { return new OutingTranslationWorker(db,client,manager,true); }
    void due() { db.update("UPDATE idee_translation_batch SET next_poll_at=now() WHERE completed_at IS NULL"); }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    class Fake extends OpenAiTranslations {
        final ObjectMapper json=new ObjectMapper();
        final Map<String,JsonNode> remotes=new LinkedHashMap<>();
        final Map<String,List<JsonNode>> outputs=new HashMap<>();
        final AtomicInteger posts=new AtomicInteger();
        int uploads;
        String input;
        boolean lost,reject,waiting;
        final Set<String> failing=new HashSet<>(),invalid=new HashSet<>();
        Fake() { super(new ObjectMapper(),"fake","test-openai-model"); }
        @Override public String upload(String text,UUID id) { uploads++; input=text; return "file-"+id; }
        @Override public JsonNode createBatch(String file,UUID id) {
            posts.incrementAndGet(); if(reject) throw new ApiException(429);
            try {
                String provider="batch_"+UUID.randomUUID();
                var result=new ArrayList<JsonNode>();
                for(String line:input.split("\\R")) {
                    var request=json.readTree(line); String custom=request.path("custom_id").asText();
                    String lang=custom.split(":")[1];
                    var source=json.readTree(request.path("body").path("messages").path(1).path("content").asText());
                    var content=Map.of("title",lang+" "+source.path("title").asText(),
                        "description_longue",lang+" "+source.path("description_longue").asText(),
                        "description_courte",invalid.contains(lang)?"a".repeat(301):lang+" "+source.path("description_courte").asText());
                    if(source.size()==1) content=Map.of("title",lang+" "+source.path("title").asText());
                    result.add(json.valueToTree(Map.of("custom_id",custom,"response",Map.of("status_code",failing.contains(lang)?500:200,
                        "body",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(content)))))))));
                }
                Collections.reverse(result); outputs.put(provider,result);
                var remote=json.valueToTree(Map.of("id",provider,"status",waiting?"in_progress":"completed","metadata",Map.of("idee_translation_batch",id.toString())));
                remotes.put(provider,remote);
                if(lost) throw new IllegalStateException("Uncertain POST");
                return remote;
            } catch(java.io.IOException error) { throw new IllegalStateException(error); }
        }
        @Override public JsonNode getBatch(String provider) { return remotes.get(provider); }
        @Override public JsonNode findBatch(UUID local) {
            return remotes.values().stream().filter(row->local.toString().equals(row.path("metadata").path("idee_translation_batch").asText())).findFirst().orElse(null);
        }
        @Override public List<JsonNode> outputs(JsonNode remote) { return outputs.get(remote.path("id").asText()); }
    }
    @Test void explicitRequestTranslatesTitleAndBothTextsInFiveLanguages() {
        long first=outing("translation-first"); outing("translation-second");
        var client=new Fake(); var worker=worker(client);
        assertFalse(worker.processNext()); assertEquals(0,client.posts.get()); assertEquals(0,count("idee_outing_translation_job"));
        var accepted=worker.enqueueMissing(1);
        assertEquals(1L,accepted.get("accepted")); assertEquals(5L,accepted.get("requests")); assertEquals(0,client.uploads);
        assertTrue(worker.processNext()); assertEquals(1,client.posts.get());
        due(); assertTrue(worker(newWorkerClient(client)).processNext());
        var translations=new OutingDescriptions(db,null,manager).translationsForOuting(first);
        assertEquals(5,translations.size());
        for(var text:translations) {
            assertEquals(text.get("language")+" Exposition Wurth",text.get("title"));
            assertEquals(text.get("language")+" Présentation française",text.get("description_longue"));
            assertEquals(text.get("language")+" Résumé français",text.get("description_courte"));
        }
        assertEquals("Exposition Wurth",db.queryForObject("SELECT title FROM idee_outing WHERE id=?",String.class,first));
        assertEquals("Présentation française",db.queryForObject("SELECT description_longue FROM idee_outing_description WHERE outing_id=?",String.class,first));
        assertEquals(1L,worker.enqueueMissing(500).get("accepted"));
        assertEquals(0L,worker.enqueueMissing(500).get("accepted"));
        assertEquals(5,count("idee_outing_translation_job"));
    }
    @Autowired CatalogController catalog;
    @Test void catalogueTranslationsSearchAndFreshness() {
        long id=outing("translation-catalogue");
        var client=new Fake();var worker=worker(client);
        worker.enqueueMissing(1);assertTrue(worker.processNext());due();assertTrue(worker.processNext());
        db.update("UPDATE idee_outing_translation SET title='Art exhibition',description_courte='English summary' WHERE outing_id=? AND language='en'",id);
        var page=catalog.catalog("",null,"","","","exhibition","en",false,40,0);
        assertEquals(1L,page.get("total"));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)page.get("items");
        @SuppressWarnings("unchecked") var texts=(List<Map<String,Object>>)items.getFirst().get("translations");
        assertEquals(1,texts.size());assertEquals("Art exhibition",texts.getFirst().get("title"));
        assertEquals("English summary",texts.getFirst().get("description_courte"));
        assertFalse(texts.getFirst().containsKey("description_longue"));
        assertEquals(0L,catalog.catalog("",null,"","","","exhibition","fr",false,40,0).get("total"));
        assertEquals("Exposition Wurth",catalog.outing("translation-catalogue").get("title"));
        assertThrows(ResponseStatusException.class,()->catalog.catalog("",null,"","","","","xx",false,40,0));
        db.update("UPDATE idee_outing SET title='Titre modifié' WHERE id=?",id);
        assertEquals(0L,catalog.catalog("",null,"","","","exhibition","en",false,40,0).get("total"));
        var rows=new ArrayList<Map<String,Object>>();rows.add(new LinkedHashMap<>(Map.of("id",id)));
        new OutingDescriptions(db,null,manager).addCatalogueTranslations(rows,"en");
        assertEquals(List.of(),rows.getFirst().get("translations"));
    }
    @Autowired PublicRouteController routes;
    void translate(long id,String language,String title) {
        db.update("""
            INSERT INTO idee_outing_translation(outing_id,language,source_hash,title,description_longue,description_courte,model,prompt_version)
            SELECT outing_id,?,source_hash,?,'Translated description','Translated summary','test',1 FROM idee_translation_source WHERE outing_id=?
            ON CONFLICT(outing_id,language) DO UPDATE SET title=EXCLUDED.title,source_hash=EXCLUDED.source_hash
            """,language,title,id);
    }
    @SuppressWarnings("unchecked") String translatedPath(String slug,String language) {
        return ((Map<String,String>)catalog.outing(slug).get("urls")).get(language);
    }
    @Test void translatedUrlsAreStableResolveInFullAndKeepOldLinks() throws Exception {
        long place=db.queryForObject("INSERT INTO idee_place(name,city,department) VALUES('Salle','Rossfeld','67') RETURNING id",Long.class);
        long id=outing("translation-readable");
        db.update("UPDATE idee_outing SET title='Don du sang',place_id=? WHERE id=?",place,id);
        translate(id,"en","Blood Donation");
        String english="/en/bas-rhin/rossfeld/blood-donation";
        assertEquals(english,translatedPath("translation-readable","en"));
        assertEquals("translation-readable",routes.resolve(english));
        assertEquals("translation-readable",routes.resolve("/en/bas-rhin/rossfeld/don-du-sang"));
        assertEquals("translation-readable",routes.resolve("/en/sorties/translation-readable"));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/en/haut-rhin/rossfeld/blood-donation"));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/de/bas-rhin/rossfeld/blood-donation"));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/pt/bas-rhin/rossfeld/blood-donation"));
        assertEquals(english,routes.page("/en/bas-rhin/rossfeld/don-du-sang").getHeaders().getFirst("Location"));
        assertEquals(200,routes.page(english).getStatusCode().value());
        translate(id,"en","Updated blood donation title");
        assertEquals(english,translatedPath("translation-readable","en"));
        // Explicit editorial correction keeps the old translated address as an alias.
        db.update("UPDATE idee_outing_url SET slug='give-blood' WHERE outing_id=? AND language='en'",id);
        db.execute("SELECT idee_publish_translated_outing_url("+id+",'en')");
        assertEquals("/en/bas-rhin/rossfeld/give-blood",translatedPath("translation-readable","en"));
        assertEquals("translation-readable",routes.resolve(english));
        assertEquals("/en/bas-rhin/rossfeld/give-blood",routes.page(english).getHeaders().getFirst("Location"));
        // Identical translated titles cannot take another outing's current or historical URL.
        long second=outing("translation-readable-second");db.update("UPDATE idee_outing SET place_id=? WHERE id=?",place,second);
        translate(second,"en","Blood Donation");
        assertEquals(english+"-"+second,translatedPath("translation-readable-second","en"));
        // A French slug must not conflict with a previously published translated path.
        long third=outing("translation-french-conflict");db.update("UPDATE idee_outing SET title='Give blood',place_id=? WHERE id=?",place,third);
        assertEquals("/bas-rhin/rossfeld/give-blood-"+third,catalog.outing("translation-french-conflict").get("url"));
        // Geographic translations are used when available.
        db.update("INSERT INTO idee_department_translation VALUES('67','de','Niederrhein','niederrhein')");
        db.update("INSERT INTO idee_city_translation SELECT city_id,'de','Rossfeld','rossfeld' FROM idee_place WHERE id=?",place);
        translate(id,"de","Blutspende");
        assertEquals("/de/niederrhein/rossfeld/blutspende",translatedPath("translation-readable","de"));
        // No place: a translated title still has a stable readable legacy-shaped URL.
        long missing=outing("translation-no-place");translate(missing,"en","Art show");
        assertEquals("/en/sorties/art-show",translatedPath("translation-no-place","en"));
        assertEquals("translation-no-place",routes.resolve("/en/sorties/art-show"));
        db.update("UPDATE idee_outing SET status='archived' WHERE id=?",id);
        assertThrows(ResponseStatusException.class,()->routes.resolve(english));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/en/bas-rhin/rossfeld/don-du-sang"));
    }
    OpenAiTranslations newWorkerClient(Fake client) { return client; } // Same remote provider; a fresh worker resumes the persisted ID.
    @Test void titleAndFrenchTextChangesDiscardStaleResultsAndMaskOldTranslations() {
        long id=outing("translation-stale"); var client=new Fake(); var worker=worker(client);
        worker.enqueueMissing(1); assertTrue(worker.processNext());
        db.update("UPDATE idee_outing SET title='Titre corrigé' WHERE id=?",id);
        due(); assertTrue(worker.processNext()); assertEquals(0,count("idee_outing_translation"));
        assertEquals(5,count("idee_outing_translation_job"));
        assertTrue(worker.processNext()); due(); assertTrue(worker.processNext());
        assertEquals(5,new OutingDescriptions(db,null,manager).translationsForOuting(id).size());
        db.update("UPDATE idee_outing_description SET description_longue='Présentation modifiée' WHERE outing_id=?",id);
        assertEquals(0,new OutingDescriptions(db,null,manager).translationsForOuting(id).size());
        assertEquals(5,count("idee_outing_translation_job"),"A French edit automatically queues translation");
        worker.enqueueMissing(1); assertTrue(worker.processNext()); due(); assertTrue(worker.processNext());
        assertEquals("en Présentation modifiée",db.queryForObject("SELECT description_longue FROM idee_outing_translation WHERE outing_id=? AND language='en'",String.class,id));
        db.update("UPDATE idee_outing SET description='Nouvelle source brute' WHERE id=?",id);
        assertEquals(0,new OutingDescriptions(db,null,manager).translationsForOuting(id).size());
        assertEquals(0L,worker.enqueueMissing(1).get("accepted"));
    }
    @Test void retriesOnlyFailedLanguagesAndPreservesBackoff() {
        outing("translation-errors"); var client=new Fake(); client.failing.add("de"); client.invalid.add("nl");
        var worker=worker(client); worker.enqueueMissing(1); assertTrue(worker.processNext());
        due(); assertTrue(worker.processNext());
        assertEquals(3,count("idee_outing_translation")); assertEquals(2,count("idee_outing_translation_job"));
        var before=db.queryForList("SELECT language,attempts,next_attempt_at FROM idee_outing_translation_job ORDER BY language");
        db.update("UPDATE idee_outing SET title=title,summary='Résumé sans changement de source' WHERE slug='translation-errors'");
        assertEquals(0L,worker.enqueueMissing(500).get("accepted"));
        assertEquals(before,db.queryForList("SELECT language,attempts,next_attempt_at FROM idee_outing_translation_job ORDER BY language"));
        assertFalse(worker.processNext());
        db.update("UPDATE idee_outing_translation_job SET next_attempt_at=now()"); client.failing.clear(); client.invalid.clear();
        assertTrue(worker.processNext()); assertEquals(2,client.input.lines().count());
        due(); assertTrue(worker.processNext()); assertEquals(5,count("idee_outing_translation"));
        assertEquals(0,count("idee_outing_translation_job")); assertEquals(2,client.posts.get());
    }
    @Test void uncertainSubmissionAndRejectedSubmissionResumeWithoutDuplicatePaidRequests() {
        outing("translation-lost"); var client=new Fake(); client.lost=true;
        worker(client).enqueueMissing(1); assertTrue(worker(client).processNext());
        assertEquals("SUBMITTING",db.queryForObject("SELECT state FROM idee_translation_batch",String.class));
        due(); assertTrue(worker(client).processNext()); due(); assertTrue(worker(client).processNext());
        assertEquals(1,client.posts.get()); assertEquals(5,count("idee_outing_translation"));
        outing("translation-rejected"); client.lost=false; client.reject=true;
        worker(client).enqueueMissing(1); assertTrue(worker(client).processNext());
        assertEquals("PREPARED",db.queryForObject("SELECT state FROM idee_translation_batch WHERE completed_at IS NULL",String.class));
        client.reject=false; due(); assertTrue(worker(client).processNext());
        due(); assertTrue(worker(client).processNext());
        assertEquals(2,client.uploads,"A rejected batch reuses its uploaded input file");
        assertEquals(10,count("idee_outing_translation"));
    }
    @Test void draftDemoMissingFrenchAndMissingKeyAreExcludedAndLimitsAreBounded() {
        long draft=outing("translation-draft"),demo=outing("translation-demo"),missing=outing("translation-missing");
        db.update("UPDATE idee_outing SET status='draft' WHERE id=?",draft);
        db.update("UPDATE idee_outing SET is_demo=true WHERE id=?",demo);
        db.update("DELETE FROM idee_outing_description WHERE outing_id=?",missing);
        var client=new Fake(); var worker=worker(client);
        assertEquals(0L,worker.enqueueMissing(500).get("accepted"));
        assertThrows(ResponseStatusException.class,()->worker.enqueueMissing(0));
        assertThrows(ResponseStatusException.class,()->worker.enqueueMissing(501));
        assertThrows(ResponseStatusException.class,()->worker(new OpenAiTranslations(new ObjectMapper(),"","test")).enqueueMissing(1));
        db.update("""
            INSERT INTO idee_outing(slug,title,summary,description,kind,status)
            SELECT 'translation-bulk-'||n,'Titre','Résumé','Source','event','published' FROM generate_series(1,501) n
            """);
        db.update("""
            INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
            SELECT id,'fr','Source','Texte','Court','test',1 FROM idee_outing WHERE slug LIKE 'translation-bulk-%'
            """);
        db.update("DELETE FROM idee_outing_translation_job"); // Simulate a pre-migration catalogue.
        assertEquals(0,count("idee_outing_translation_job"));
        assertEquals(500L,worker.enqueueMissing(500).get("accepted")); assertEquals(2500,count("idee_outing_translation_job"));
        assertEquals(1L,worker.enqueueMissing(500).get("accepted")); assertEquals(2505,count("idee_outing_translation_job"));
        assertEquals(0L,worker.enqueueMissing(500).get("accepted"));
    }
    @Test void allMissingQueuesMoreThan500OutingsAndSkipsCurrentAndPendingLanguages() {
        long current=outing("translation-all-current"),pending=outing("translation-all-pending");
        translate(current,"en","Current title");
        db.queryForList("SELECT idee_queue_outing_translation(?,'de')",pending);
        db.update("UPDATE idee_outing_translation_job SET attempts=2,next_attempt_at=now()+interval '1 hour' WHERE outing_id=?",pending);
        var before=db.queryForMap("SELECT * FROM idee_outing_translation_job WHERE outing_id=?",pending);
        db.update("""
            INSERT INTO idee_outing(slug,title,summary,description,kind,status)
            SELECT 'translation-all-'||n,'Titre','Résumé','Source','event','published' FROM generate_series(1,501) n
            """);
        db.update("""
            INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
            SELECT id,'fr','Source','Texte','Court','test',1 FROM idee_outing WHERE slug ~ '^translation-all-[0-9]+$'
            """);
        db.update("DELETE FROM idee_outing_translation_job WHERE outing_id<>? OR language<>'de'",pending);
        var client=new Fake(); var worker=worker(client);
        var result=worker.enqueueAllMissing();
        assertEquals("all",result.get("scope")); assertEquals(503L,result.get("accepted"));
        assertEquals(2513L,result.get("requests")); assertEquals(2514,count("idee_outing_translation_job"));
        assertEquals(before,db.queryForMap("SELECT * FROM idee_outing_translation_job WHERE outing_id=? AND language='de'",pending));
        assertEquals(0L,worker.enqueueAllMissing().get("accepted"));
        assertEquals(0,client.uploads); assertEquals(0,client.posts.get());
        assertThrows(ResponseStatusException.class,()->worker(new OpenAiTranslations(new ObjectMapper(),"","test")).enqueueAllMissing());
    }
    @Test void automaticPipelineWaitsForMistralThenTranslatesAndPreservesIdenticalImports() {
        var json=new ObjectMapper(); var remotes=new HashMap<String,JsonNode>();
        var mistral=new MistralDescriptions(json,"fake","test") {
            @Override public JsonNode createBatch(String payload) {
                try {
                    var request=json.readTree(payload); String remote=UUID.randomUUID().toString();
                    var outputs=new ArrayList<Object>();
                    for(var item:request.path("requests")) {
                        String source=item.path("body").path("messages").path(1).path("content").asText();
                        var content=json.writeValueAsString(Map.of("description_longue","Long "+source,"description_courte","Court "+source));
                        outputs.add(Map.of("custom_id",item.path("custom_id").asText(),"response",Map.of("status_code",200,
                            "body",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))))));
                    }
                    var result=json.valueToTree(Map.of("id",remote,"status","SUCCESS","outputs",outputs));
                    remotes.put(remote,result); return result;
                } catch(Exception error) { throw new IllegalStateException(error); }
            }
            @Override public JsonNode getBatch(String id) { return remotes.get(id); }
        };
        var descriptions=new OutingDescriptionWorker(db,new OutingDescriptions(db,mistral,manager),mistral,manager,true);
        var client=new Fake(); var translations=worker(client);
        UUID uuid=UUID.randomUUID(),run=UUID.randomUUID(); var tx=new TransactionTemplate(manager);
        db.update("INSERT INTO idee_import_run(id,department,mode) VALUES(?,'67','update')",run);
        long id=tx.execute(status -> {
            db.queryForObject("SELECT set_config('idee.import_run_id',?,true)",String.class,run.toString());
            long outing=db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status) VALUES('translation-pipeline','Titre','Résumé','Brut','event','published') RETURNING id",Long.class);
            db.update("INSERT INTO idee_datatourisme_event(uuid,outing_id,payload) VALUES(?,?,?::jsonb)",uuid,outing,"{}");
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Source importée')",uuid);
            db.update("INSERT INTO idee_import_run_outing(run_id,outing_id,new_outing,updated_outing) VALUES(?,?,true,false)",run,outing);
            return outing;
        });
        assertEquals(1L,db.queryForObject("SELECT mistral_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertEquals(1,count("idee_outing_description_job")); assertEquals(0,count("idee_outing_translation_job"));
        UUID replay=UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO idee_import_run(id,department,mode) VALUES(?,'67','update')",replay);
            db.update("INSERT INTO idee_import_run_outing(run_id,outing_id,new_outing,updated_outing) VALUES(?,?,false,false)",replay,id);
            db.queryForObject("SELECT set_config('idee.import_run_id',?,true)",String.class,replay.toString());
            db.update("UPDATE idee_outing SET title=title WHERE id=?",id);
        });
        assertEquals(0L,db.queryForObject("SELECT mistral_outings FROM idee_import_run WHERE id=?",Long.class,replay));
        assertEquals(run,db.queryForObject("SELECT import_run_id FROM idee_outing_description_job WHERE outing_id=?",UUID.class,id));
        assertFalse(translations.processNext()); assertTrue(descriptions.processNext());
        assertEquals(0,count("idee_outing_translation_job"));
        db.update("UPDATE idee_description_batch SET next_poll_at=now()"); assertTrue(descriptions.processNext());
        assertEquals(5,count("idee_outing_translation_job"));
        assertEquals(1L,db.queryForObject("SELECT description_translation_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertEquals(1L,db.queryForObject("SELECT title_translation_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertTrue(translations.processNext()); due(); assertTrue(translations.processNext());
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=?",uuid);
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Source importée')",uuid);
            db.update("UPDATE idee_outing SET title=title WHERE id=?",id);
        });
        assertEquals(0,count("idee_outing_description_job")); assertEquals(0,count("idee_outing_translation_job"));
        tx.executeWithoutResult(status -> {
            db.update("UPDATE idee_outing SET title='Titre et texte corrigés' WHERE id=?",id);
            db.update("UPDATE idee_datatourisme_translation SET description='Source modifiée' WHERE uuid=?",uuid);
        });
        assertEquals(1,count("idee_outing_description_job")); assertEquals(0,count("idee_outing_translation_job"));
        assertTrue(descriptions.processNext()); db.update("UPDATE idee_description_batch SET next_poll_at=now()"); assertTrue(descriptions.processNext());
        assertEquals(5,count("idee_outing_translation_job"));
        assertTrue(translations.processNext());
        assertTrue(client.input.contains("description_longue")); due(); assertTrue(translations.processNext());
        assertEquals("en Long Source modifiée",db.queryForObject("SELECT description_longue FROM idee_outing_translation WHERE outing_id=? AND language='en'",String.class,id));
        tx.executeWithoutResult(status -> { db.update("UPDATE idee_outing SET title='Annulé' WHERE id=?",id); status.setRollbackOnly(); });
        assertEquals(0,count("idee_outing_translation_job"));
    }
    @Test void automaticTitleChangesReuseDescriptionsAndDoNotQueueMistral() throws Exception {
        long id=outing("translation-title-only"); var client=new Fake(); var worker=worker(client);
        db.queryForList("SELECT idee_queue_outing_translations(?)",id);
        assertTrue(worker.processNext()); due(); assertTrue(worker.processNext());
        var before=db.queryForList("SELECT language,description_longue,description_courte FROM idee_outing_translation ORDER BY language");
        UUID run=UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.update("INSERT INTO idee_import_run(id,department,mode) VALUES(?,'67','update')",run);
            db.update("INSERT INTO idee_import_run_outing(run_id,outing_id,new_outing,updated_outing) VALUES(?,?,false,true)",run,id);
            db.queryForObject("SELECT set_config('idee.import_run_id',?,true)",String.class,run.toString());
            db.update("UPDATE idee_outing SET title='Titre corrigé' WHERE id=?",id);
        });
        assertEquals(0L,db.queryForObject("SELECT mistral_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertEquals(0L,db.queryForObject("SELECT description_translation_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertEquals(1L,db.queryForObject("SELECT title_translation_outings FROM idee_import_run WHERE id=?",Long.class,run));
        assertEquals(0,count("idee_outing_description_job"));
        assertEquals(5,count("idee_outing_translation_job"));
        assertTrue(worker.processNext());
        for(String line:client.input.split("\\R")) {
            var request=client.json.readTree(line);
            var source=client.json.readTree(request.path("body").path("messages").path(1).path("content").asText());
            assertEquals(Set.of("title"),new HashSet<>(source.properties().stream().map(Map.Entry::getKey).toList()));
            assertEquals("Titre corrigé",source.path("title").asText());
        }
        due(); assertTrue(worker(client).processNext()); // Resuming with persisted reused descriptions.
        assertEquals(before,db.queryForList("SELECT language,description_longue,description_courte FROM idee_outing_translation ORDER BY language"));
        assertEquals("en Titre corrigé",db.queryForObject("SELECT title FROM idee_outing_translation WHERE outing_id=? AND language='en'",String.class,id));
        db.update("UPDATE idee_outing SET summary='Résumé corrigé',title=title WHERE id=?",id);
        assertEquals(0,count("idee_outing_translation_job")); assertFalse(worker.processNext());
    }
    @Test void concurrentWorkersSubmitOnceWithoutBlockingTitleUpdates() throws Exception {
        long id=outing("translation-concurrent");
        var started=new CountDownLatch(1); var finish=new CountDownLatch(1);
        var client=new Fake() {
            @Override public JsonNode createBatch(String file,UUID batch) {
                started.countDown();
                try { assertTrue(finish.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException error) { throw new RuntimeException(error); }
                return super.createBatch(file,batch);
            }
        };
        worker(client).enqueueMissing(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->worker(client).processNext());
            try { assertTrue(started.await(10,TimeUnit.SECONDS)); assertFalse(worker(client).processNext());
                db.update("UPDATE idee_outing SET title='Modifié pendant HTTP' WHERE id=?",id);
            } finally { finish.countDown(); }
            assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,client.posts.get());
    }
}
