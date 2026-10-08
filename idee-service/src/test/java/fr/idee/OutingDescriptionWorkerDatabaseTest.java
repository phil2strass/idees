package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false","idee.mistral.auto-enabled=false"})
class OutingDescriptionWorkerDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    private final AtomicInteger calls=new AtomicInteger();
    private boolean safe;

    @BeforeEach void isolatedOnly() {
        safe=db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_");
        assertTrue(safe);
    }
    @AfterEach void cleanup() {
        if(safe) new TransactionTemplate(manager).executeWithoutResult(s -> {
            db.update("DELETE FROM idee_datatourisme_event WHERE outing_id IN (SELECT id FROM idee_outing WHERE slug LIKE 'queue-%')");
            db.update("DELETE FROM idee_outing WHERE slug LIKE 'queue-%'");
        });
        if(safe) db.update("DELETE FROM idee_description_batch");
    }
    long create(String slug,String description,String status,boolean demo) {
        return db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status,is_demo) VALUES(?,'Titre','Résumé',?,'event',?,?) RETURNING id",Long.class,slug,description,status,demo);
    }
    long jobs(long id) { return db.queryForObject("SELECT count(*) FROM idee_outing_description_job WHERE outing_id=?",Long.class,id); }
    String source(long id) { return db.queryForObject("SELECT source_description FROM idee_outing_description_job WHERE outing_id=?",String.class,id); }
    OutingDescriptionWorker worker(MistralDescriptions client) {
        return new OutingDescriptionWorker(db,new OutingDescriptions(db,client,manager),client,manager,true);
    }
    FakeMistral client() { return new FakeMistral(); }
    class FakeMistral extends MistralDescriptions {
        final ObjectMapper json=new ObjectMapper();
        final Map<String,JsonNode> remotes=new LinkedHashMap<>();
        boolean fail,invalid,lost,unknown,reject,waiting;
        int submissions;
        FakeMistral() { super(new ObjectMapper(),"fake","test-model"); }
        @Override public Texts rewrite(String source) {
            calls.incrementAndGet(); return new Texts("Long : "+source,"Court : "+source);
        }
        @Override public JsonNode createBatch(String payload) {
            submissions++;
            if(reject) throw new BatchHttpException(429);
            try {
                var request=json.readTree(payload);
                String id=UUID.randomUUID().toString();
                var outputs=new ArrayList<Object>();
                for(var item:request.path("requests")) {
                    calls.incrementAndGet();
                    String source=item.path("body").path("messages").path(1).path("content").asText();
                    var body=Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",
                        json.writeValueAsString(Map.of("description_longue","Long : "+source,"description_courte",invalid?"a".repeat(301):"Court : "+source))))));
                    outputs.add(Map.of("custom_id",item.path("custom_id").asText(),"response",Map.of("status_code",fail?500:200,"body",body)));
                }
                Collections.reverse(outputs);
                var response=json.valueToTree(Map.of("id",id,"status",waiting?"RUNNING":"SUCCESS","metadata",request.path("metadata"),"outputs",outputs));
                if(!unknown) remotes.put(id,response);
                if(lost || unknown) throw new IllegalStateException("Simulated uncertain submission");
                return response;
            } catch(java.io.IOException error) { throw new IllegalStateException(error); }
        }
        @Override public JsonNode getBatch(String id) { return remotes.get(id); }
        @Override public JsonNode findBatch(UUID id) {
            return remotes.values().stream().filter(r->id.toString().equals(r.path("metadata").path("idee_batch_id").asText())).findFirst().orElse(null);
        }
    }
    void due() { db.update("UPDATE idee_description_batch SET next_poll_at=now() WHERE completed_at IS NULL"); }
    void generate(OutingDescriptionWorker worker) {
        assertTrue(worker.processNext()); due(); assertTrue(worker.processNext());
    }
    @Test void newChangedUnchangedAndManualGeneration() {
        long id=create("queue-new","Original","published",false);
        assertEquals("Original",source(id));
        var client=client(); var worker=worker(client);
        generate(worker); assertEquals(1,calls.get()); assertEquals(0,jobs(id));
        db.update("UPDATE idee_outing SET title='Titre corrigé',summary='Autre résumé' WHERE id=?",id);
        assertEquals(0,jobs(id)); assertFalse(worker.processNext()); assertEquals(1,calls.get());
        db.update("UPDATE idee_outing SET description='Modifiée' WHERE id=?",id);
        assertEquals("Modifiée",source(id));
        generate(worker); assertEquals(2,calls.get());
        assertEquals("Long : Modifiée",db.queryForObject("SELECT description_longue FROM idee_outing_description WHERE outing_id=?",String.class,id));
        assertEquals("Court : Modifiée",db.queryForObject("SELECT description_courte FROM idee_outing_description WHERE outing_id=?",String.class,id));
        db.update("UPDATE idee_outing SET description='Manuelle' WHERE id=?",id);
        new OutingDescriptions(db,client,manager).generateFrench("queue-new");
        assertEquals(0,jobs(id)); assertFalse(worker.processNext()); assertEquals(3,calls.get());
    }
    @Test void importedTranslationsAreComparedAfterReplacementAtCommit() {
        UUID uuid=UUID.randomUUID();
        var tx=new TransactionTemplate(manager);
        long id=tx.execute(s -> {
            long outing=create("queue-import","Original brut","published",false);
            db.update("INSERT INTO idee_datatourisme_event(uuid,outing_id,payload) VALUES(?,?,?::jsonb)",uuid,outing,"{}");
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Source française'),(?,'en','English')",uuid,uuid);
            assertEquals(0,jobs(outing),"Queue must only become visible when the import commits");
            return outing;
        });
        assertEquals("Source française",source(id));
        var worker=worker(client()); generate(worker); assertEquals(1,calls.get());
        tx.executeWithoutResult(s -> {
            db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=?",uuid);
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Source française'),(?,'en','Updated English')",uuid,uuid);
            db.update("UPDATE idee_outing SET title='Nouveau titre' WHERE id=?",id);
        });
        assertEquals(0,jobs(id)); assertFalse(worker.processNext()); assertEquals(1,calls.get());
        tx.executeWithoutResult(s -> {
            db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=?",uuid);
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Français modifié')",uuid);
        });
        assertEquals("Français modifié",source(id));
        generate(worker); assertEquals(2,calls.get());
        assertEquals("Français modifié",db.queryForObject("SELECT source_description FROM idee_outing_description WHERE outing_id=?",String.class,id));
        db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=? AND language='fr'",uuid);
        assertEquals(0,jobs(id)); assertFalse(worker.processNext());
    }
    @Test void failuresPersistAndIdenticalImportsDoNotResetBackoff() {
        long id=create("queue-failure","Première source","published",false);
        var client=client(); generate(worker(client));
        db.update("UPDATE idee_outing SET description='Source nouvelle' WHERE id=?",id);
        client.fail=true; generate(worker(client));
        var failed=db.queryForMap("SELECT attempts,last_error,next_attempt_at FROM idee_outing_description_job WHERE outing_id=?",id);
        assertEquals(1,failed.get("attempts")); assertEquals("batch_request_failed",failed.get("last_error"));
        assertFalse(worker(client).processNext());
        db.update("UPDATE idee_outing SET description=description,title='Titre changé' WHERE id=?",id);
        assertEquals(failed,db.queryForMap("SELECT attempts,last_error,next_attempt_at FROM idee_outing_description_job WHERE outing_id=?",id));
        assertEquals("Long : Première source",db.queryForObject("SELECT description_longue FROM idee_outing_description WHERE outing_id=?",String.class,id));
        assertNull(new OutingDescriptions(db,client,manager).forOuting(id).getFirst().get("description_longue"));
        db.update("UPDATE idee_outing_description_job SET next_attempt_at=now() WHERE outing_id=?",id);
        client.fail=false;
        // New worker resumes the durable request, then only this failed item is resubmitted.
        generate(worker(client)); assertEquals(0,jobs(id));
        assertEquals(3,client.submissions);
        db.update("UPDATE idee_outing SET description='Encore nouvelle' WHERE id=?",id);
        client.invalid=true; generate(worker(client));
        assertEquals(1,db.queryForObject("SELECT attempts FROM idee_outing_description_job WHERE outing_id=?",Integer.class,id));
        db.update("UPDATE idee_outing SET description='Source la plus récente' WHERE id=?",id);
        assertEquals(0,db.queryForObject("SELECT attempts FROM idee_outing_description_job WHERE outing_id=?",Integer.class,id));
        assertEquals("Source la plus récente",source(id));
    }
    @Test void draftsDemosMissingSourcesAndRollbacksNeverGenerate() {
        long draft=create("queue-draft","Texte","draft",false);
        long demo=create("queue-demo","Texte","published",true);
        long empty=create("queue-empty","","published",false);
        assertEquals(0,jobs(draft)+jobs(demo)+jobs(empty));
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(s -> { create("queue-rollback","Texte","published",false); s.setRollbackOnly(); });
        assertFalse(worker(client()).processNext()); assertEquals(0,calls.get());
        db.update("UPDATE idee_outing SET status='published' WHERE id=?",draft);
        assertEquals(1,jobs(draft));
        db.update("UPDATE idee_outing SET status='archived' WHERE id=?",draft);
        assertEquals(0,jobs(draft));
        long missing=create("queue-no-key","Texte","published",false);
        assertFalse(worker(new MistralDescriptions(new ObjectMapper(),"","test-model")).processNext());
        assertEquals(1,jobs(missing));
    }
    @Test void concurrentWorkersSubmitOnlyOneBatch() throws Exception {
        long id=create("queue-concurrent","Texte","published",false);
        var started=new CountDownLatch(1); var finish=new CountDownLatch(1);
        var client=new FakeMistral() {
            @Override public JsonNode createBatch(String payload) {
                started.countDown();
                try { assertTrue(finish.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return super.createBatch(payload);
            }
        };
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> worker(client).processNext());
            try {
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertFalse(worker(client).processNext());
                // No outing lock is held while the HTTP submission is in progress.
                db.update("UPDATE idee_outing SET title='Nouveau titre' WHERE id=?",id);
            } finally { finish.countDown(); }
            assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,client.submissions); assertEquals(1,calls.get());
        assertFalse(worker(client).processNext());
        due(); assertTrue(worker(client).processNext());
        assertEquals(0,jobs(id)); assertEquals(1,client.submissions);
    }
    @Test void staleResultsNeverOverwriteChangedSourcesOrManualGeneration() {
        long changed=create("queue-changed","Original","published",false);
        long manual=create("queue-manual","Manuelle","published",false);
        long archived=create("queue-archived","Archive","published",false);
        long removed=create("queue-removed","Supprimée","published",false);
        var client=client(); var worker=worker(client);
        assertTrue(worker.processNext());
        assertEquals(4,db.queryForObject("SELECT count(*) FROM idee_outing_description_job WHERE batch_id IS NOT NULL",Integer.class));
        db.update("UPDATE idee_outing SET description='Modifiée pendant le batch' WHERE id=?",changed);
        assertNull(db.queryForObject("SELECT batch_id FROM idee_outing_description_job WHERE outing_id=?",UUID.class,changed));
        new OutingDescriptions(db,client,manager).generateFrench("queue-manual");
        db.update("UPDATE idee_outing SET status='archived' WHERE id=?",archived);
        db.update("DELETE FROM idee_outing WHERE id=?",removed);
        due(); assertTrue(worker(client).processNext());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM idee_outing_description WHERE outing_id IN (?,?)",Integer.class,changed,archived));
        assertEquals("Modifiée pendant le batch",source(changed));
        assertEquals("Long : Manuelle",db.queryForObject("SELECT description_longue FROM idee_outing_description WHERE outing_id=?",String.class,manual));
        generate(worker(client));
        assertEquals("Long : Modifiée pendant le batch",db.queryForObject("SELECT description_longue FROM idee_outing_description WHERE outing_id=?",String.class,changed));
    }
    @Test void uncertainSubmissionIsRecoveredWithoutAnotherPaidPost() {
        long id=create("queue-lost","Texte","published",false);
        var client=client(); client.lost=true;
        assertTrue(worker(client).processNext());
        assertEquals("SUBMITTING",db.queryForObject("SELECT state FROM idee_description_batch",String.class));
        assertEquals(1,client.submissions);
        due(); assertTrue(worker(client).processNext()); // recover provider ID after restart
        due(); assertTrue(worker(client).processNext()); // collect
        assertEquals(1,client.submissions); assertEquals(0,jobs(id));
        long unknown=create("queue-unknown","Texte inconnu","published",false);
        client.unknown=true;
        assertTrue(worker(client).processNext());
        due(); assertTrue(worker(client).processNext());
        assertEquals("submission_unknown",db.queryForObject("SELECT last_error FROM idee_description_batch WHERE completed_at IS NULL",String.class));
        assertEquals(1,jobs(unknown)); assertEquals(2,client.submissions);
    }
    @Test void explicitSubmissionRejectionCanRetryWithoutBlockingOtherImports() {
        long id=create("queue-rejected","Texte","published",false);
        var client=client(); client.reject=true;
        assertTrue(worker(client).processNext());
        assertEquals("PREPARED",db.queryForObject("SELECT state FROM idee_description_batch",String.class));
        assertEquals(1,jobs(id)); assertEquals(0,calls.get());
        client.reject=false; due(); assertTrue(worker(client).processNext());
        due(); assertTrue(worker(client).processNext());
        assertEquals(0,jobs(id)); assertEquals(2,client.submissions); assertEquals(1,calls.get());
    }
    @Test void runningAndPartiallyFailedBatchOnlyRetriesMissingOrFailedRequests() {
        long first=create("queue-partial-1","Un","published",false);
        long second=create("queue-partial-2","Deux","published",false);
        long third=create("queue-partial-3","Trois","published",false);
        var client=client(); client.waiting=true;
        assertTrue(worker(client).processNext()); due(); assertTrue(worker(client).processNext());
        assertEquals(3,db.queryForObject("SELECT count(*) FROM idee_outing_description_job WHERE batch_id IS NOT NULL",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM idee_outing_description",Integer.class));
        var remote=(com.fasterxml.jackson.databind.node.ObjectNode)client.remotes.values().iterator().next();
        remote.put("status","FAILED");
        var outputs=(com.fasterxml.jackson.databind.node.ArrayNode)remote.path("outputs");
        // Fake output order is reversed; retain only the first outing's successful result.
        outputs.remove(0);
        ((com.fasterxml.jackson.databind.node.ObjectNode)outputs.get(0).path("response")).put("status_code",500);
        due(); assertTrue(worker(client).processNext());
        assertEquals(0,jobs(first)); assertEquals(1,jobs(second)); assertEquals(1,jobs(third));
        assertEquals(1,db.queryForObject("SELECT succeeded FROM idee_description_batch",Integer.class));
        assertEquals(2,db.queryForObject("SELECT failed FROM idee_description_batch",Integer.class));
        assertNotNull(db.queryForObject("SELECT completed_at FROM idee_description_batch",Object.class));
        assertFalse(worker(client).processNext());
        db.update("UPDATE idee_outing_description_job SET next_attempt_at=now()");
        client.waiting=false; generate(worker(client));
        assertEquals(5,calls.get()); assertEquals(2,client.submissions);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM idee_outing_description_job",Integer.class));
    }
    @Test void resultDownloadErrorsBackOffWithoutResubmittingTheBatch() {
        long id=create("queue-download","Texte","published",false);
        var failing=new java.util.concurrent.atomic.AtomicBoolean(true);
        var client=new FakeMistral() {
            @Override public List<JsonNode> batchOutputs(JsonNode job) {
                if(failing.get()) throw new BatchHttpException(503);
                return super.batchOutputs(job);
            }
        };
        assertTrue(worker(client).processNext());
        for(int attempt=1;attempt<=2;attempt++) {
            due(); assertTrue(worker(client).processNext());
            assertEquals(attempt,db.queryForObject("SELECT attempts FROM idee_description_batch",Integer.class));
            assertEquals("batch_http_503",db.queryForObject("SELECT last_error FROM idee_description_batch",String.class));
            assertEquals(1,client.submissions); assertEquals(1,jobs(id));
            assertFalse(worker(client).processNext());
        }
        failing.set(false); due(); assertTrue(worker(client).processNext());
        assertEquals(0,jobs(id)); assertEquals(1,client.submissions);
    }
    @Test void standaloneCollectorNeverSubmitsUnrelatedPendingOutings() {
        create("queue-standalone-1","Un","published",false);
        create("queue-standalone-2","Deux","published",false);
        create("queue-standalone-3","Trois","published",false);
        var client=client();
        var batches=new OutingDescriptionBatches(db,client,manager);
        UUID id=batches.submitNext(2);
        assertNotNull(id); assertEquals(2,calls.get());
        assertEquals(2,db.queryForObject("SELECT total FROM idee_description_batch WHERE id=?",Integer.class,id));
        due(); new OutingDescriptionBatches(db,client,manager).collect(id);
        assertNotNull(db.queryForObject("SELECT completed_at FROM idee_description_batch WHERE id=?",Object.class,id));
        batches.collect(id);
        assertEquals(1,client.submissions);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM idee_outing_description_job WHERE batch_id IS NULL",Integer.class));
    }
    @Test void backfillIsBoundedSkipsExistingJobsAndOnlySelectsMissingFrench() {
        db.update("""
            INSERT INTO idee_outing(slug,title,summary,description,kind,status,is_demo)
            SELECT 'queue-backfill-'||n,'Titre','Résumé','Source '||n,'event','published',false
            FROM generate_series(1,502) n
            """);
        // Historical outings: migration does not automatically backfill these.
        db.update("DELETE FROM idee_outing_description_job");
        long complete=create("queue-complete","Source complète","published",false);
        var client=client(); new OutingDescriptions(db,client,manager).generateFrench("queue-complete");
        long stale=create("queue-stale","Ancienne source","published",false);
        new OutingDescriptions(db,client,manager).generateFrench("queue-stale");
        db.update("UPDATE idee_outing SET description='Source récente' WHERE id=?",stale);
        db.update("DELETE FROM idee_outing_description_job WHERE outing_id=?",stale);
        long english=create("queue-english","Source anglaise seulement générée","published",false);
        db.update("""
            INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
            VALUES(?,'en','English','Long','Short','test',1)
            """,english);
        db.update("DELETE FROM idee_outing_description_job WHERE outing_id=?",english);
        long waiting=create("queue-waiting","En reprise","published",false);
        db.update("UPDATE idee_outing_description_job SET attempts=3,next_attempt_at=now()+interval '1 hour' WHERE outing_id=?",waiting);
        var before=db.queryForMap("SELECT attempts,next_attempt_at FROM idee_outing_description_job WHERE outing_id=?",waiting);
        create("queue-excluded-draft","Texte","draft",false);
        create("queue-excluded-demo","Texte","published",true);
        create("queue-excluded-empty","","published",false);
        long noFrench=new TransactionTemplate(manager).execute(s -> {
            long id=create("queue-no-french","Fallback brut","published",false);
            UUID uuid=UUID.randomUUID();
            db.update("INSERT INTO idee_datatourisme_event(uuid,outing_id,payload) VALUES(?,?,?::jsonb)",uuid,id,"{}");
            db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'en','English only')",uuid);
            return id;
        });
        var worker=worker(client);
        assertEquals(500,worker.enqueueMissingFrench(500).get("accepted"));
        assertEquals(4,worker.enqueueMissingFrench(500).get("accepted"));
        assertEquals(0,worker.enqueueMissingFrench(500).get("accepted"));
        assertEquals(0,jobs(complete)); assertEquals(0,jobs(noFrench));
        assertEquals("Source récente",source(stale)); assertEquals(1,jobs(english));
        assertEquals(before,db.queryForMap("SELECT attempts,next_attempt_at FROM idee_outing_description_job WHERE outing_id=?",waiting));
        assertEquals(2,calls.get(),"Enqueue must not call the provider synchronously");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,()->worker.enqueueMissingFrench(501)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,()->worker.enqueueMissingFrench(0)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->worker(new MistralDescriptions(new ObjectMapper(),"","test")).enqueueMissingFrench(500)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->new OutingDescriptionWorker(db,new OutingDescriptions(db,client,manager),client,manager,false).enqueueMissingFrench(500)).getStatusCode());
        assertTrue(worker.processNext());
        assertEquals(1,client.submissions);
        assertEquals(500,db.queryForObject("SELECT total FROM idee_description_batch",Integer.class));
        assertEquals(500L,worker.status().get("inBatch"));
        assertEquals("batch",worker.status().get("mode"));
        due(); assertTrue(worker.processNext());
        assertEquals(500,db.queryForObject("SELECT succeeded FROM idee_description_batch",Integer.class));
        assertEquals(502,calls.get());
    }
}
