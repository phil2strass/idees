package fr.idee;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutingTranslationWorker {
    @org.springframework.beans.factory.annotation.Value("${idee.jobs.scheduled-enabled:true}")
    private boolean scheduledEnabled = true;
    private final JdbcTemplate db;
    private final OpenAiTranslations client;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    public OutingTranslationWorker(JdbcTemplate db,OpenAiTranslations client,PlatformTransactionManager manager,
            @Value("${idee.openai.translations-enabled:true}") boolean enabled) {
        this.db=db; this.client=client; this.transaction=new TransactionTemplate(manager); this.enabled=enabled;
    }
    boolean isEnabled() { return enabled && client.isConfigured(); }

    public Map<String,Object> enqueueMissing(int limit) {
        if(limit<1 || limit>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limite comprise entre 1 et 500 sorties.");
        return enqueueMissingOutings(limit);
    }

    public Map<String,Object> enqueueAllMissing() {
        return enqueueMissingOutings(null);
    }

    private Map<String,Object> enqueueMissingOutings(Integer limit) {
        if(!isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Traductions OpenAI désactivées ou clé absente.");
        return transaction.execute(status -> {
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(67468016)",Boolean.class)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Une demande de traduction est en cours.");
            var result=db.queryForMap("""
                WITH candidates AS (
                    SELECT o.id,s.source,s.source_hash FROM idee_outing o JOIN idee_translation_source s ON s.outing_id=o.id
                    WHERE EXISTS (
                      SELECT 1 FROM unnest(ARRAY['en','de','it','nl','es']) lang
                      WHERE NOT EXISTS(SELECT 1 FROM idee_outing_translation t WHERE t.outing_id=o.id AND t.language=lang AND t.source_hash=s.source_hash)
                        AND NOT EXISTS(SELECT 1 FROM idee_outing_translation_job j WHERE j.outing_id=o.id AND j.language=lang))
                    ORDER BY o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                ), added AS (
                INSERT INTO idee_outing_translation_job(outing_id,language,source_hash,source)
                SELECT c.id,lang,c.source_hash,c.source FROM candidates c CROSS JOIN unnest(ARRAY['en','de','it','nl','es']) lang
                WHERE NOT EXISTS(SELECT 1 FROM idee_outing_translation t WHERE t.outing_id=c.id AND t.language=lang AND t.source_hash=c.source_hash)
                ON CONFLICT(outing_id,language) DO NOTHING RETURNING outing_id
                )
                SELECT count(DISTINCT outing_id) AS accepted,count(*) AS requests FROM added
                """,limit);
            if(limit==null) result.put("scope","all");
            else result.put("limit",limit);
            result.put("languages",List.of("en","de","it","nl","es"));
            result.put("statusUrl","/api/admin/translations/status");
            return result;
        });
    }

    public Map<String,Object> status() {
        var status=db.queryForMap("""
            SELECT count(*) AS pending,count(*) FILTER(WHERE batch_id IS NULL AND next_attempt_at<=now()) AS ready,
              count(*) FILTER(WHERE batch_id IS NOT NULL) AS "inBatch",count(*) FILTER(WHERE attempts>0) AS retrying
            FROM idee_outing_translation_job
            """);
        status.put("provider","openai"); status.put("mode","batch");
        status.put("enabled",isEnabled()); status.put("configured",client.isConfigured());
        status.put("activeBatches",db.queryForObject("SELECT count(*) FROM idee_translation_batch WHERE completed_at IS NULL",Long.class));
        status.put("batches",db.queryForList("""
            SELECT id,provider_id AS "providerId",state,total,succeeded,failed,created_at AS "createdAt",
              completed_at AS "completedAt",next_poll_at AS "nextPollAt",last_error AS "lastError"
            FROM idee_translation_batch ORDER BY created_at DESC LIMIT 20
            """));
        return status;
    }

    @Scheduled(initialDelay=60000,fixedDelay=5000)
    public void tick() { if (scheduledEnabled) processNext(); }
    public boolean processNext() {
        if(!isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection -> {
                try(var statement=connection.createStatement();var lock=statement.executeQuery("SELECT pg_try_advisory_lock(67468015)")) {
                    lock.next(); if(!lock.getBoolean(1)) return false;
                }
                try {
                    var due=db.queryForList("SELECT * FROM idee_translation_batch WHERE completed_at IS NULL AND next_poll_at<=now() ORDER BY next_poll_at,created_at LIMIT 1");
                    var batch=due.isEmpty()?prepare():due.getFirst();
                    if(batch==null) return false;
                    advance(batch); return true;
                } finally { try(var statement=connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(67468015)"); } }
            }));
        } catch(Exception error) {
            LoggerFactory.getLogger(getClass()).warn("Batch de traductions différé : {}",error.getClass().getSimpleName());
            return false;
        }
    }

    private Map<String,Object> prepare() {
        return transaction.execute(status -> {
            var candidates=db.queryForList("""
                SELECT o.id,j.language FROM idee_outing_translation_job j JOIN idee_outing o ON o.id=j.outing_id
                WHERE j.batch_id IS NULL AND j.next_attempt_at<=now()
                ORDER BY j.next_attempt_at,o.id,j.language LIMIT 2500 FOR UPDATE OF o SKIP LOCKED
                """);
            var items=new ArrayList<Map<String,Object>>(); int characters=0;
            for(var candidate:candidates) {
                Object outing=candidate.get("id"); String language=(String)candidate.get("language");
                db.queryForList("SELECT idee_queue_outing_translation(?,?)",outing,language);
                var rows=db.queryForList("SELECT * FROM idee_outing_translation_job WHERE outing_id=? AND language=? AND batch_id IS NULL AND next_attempt_at<=now()",outing,language);
                if(rows.isEmpty()) continue;
                var row=rows.getFirst(); int size=row.get("source").toString().length();
                var reused=db.queryForList("""
                    SELECT jsonb_build_object('description_longue',t.description_longue,'description_courte',t.description_courte) AS descriptions
                    FROM idee_outing_translation t
                    WHERE t.outing_id=? AND t.language=?
                      AND t.source->'description_longue'=?::jsonb->'description_longue'
                      AND t.source->'description_courte'=?::jsonb->'description_courte'
                    """,outing,language,row.get("source").toString(),row.get("source").toString());
                if(!reused.isEmpty()) row.put("reused_descriptions",reused.getFirst().get("descriptions"));
                if(size>50000) { retry(row,null,"source_too_long"); continue; }
                if(characters+size>2000000) break;
                characters+=size; items.add(row);
            }
            if(items.isEmpty()) return null;
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO idee_translation_batch(id,state,model,prompt_version,payload,total) VALUES(?,'PREPARED',?,?,?,?)",
                id,client.model,OpenAiTranslations.PROMPT_VERSION,client.jsonl(items),items.size());
            for(var item:items) {
                db.update("INSERT INTO idee_translation_batch_item(batch_id,outing_id,language,source_hash,source,reused_descriptions) VALUES(?,?,?,?,?::jsonb,?::jsonb)",
                    id,item.get("outing_id"),item.get("language"),item.get("source_hash"),item.get("source").toString(),
                    item.get("reused_descriptions")==null?null:item.get("reused_descriptions").toString());
                db.update("UPDATE idee_outing_translation_job SET batch_id=? WHERE outing_id=? AND language=?",id,item.get("outing_id"),item.get("language"));
            }
            return db.queryForMap("SELECT * FROM idee_translation_batch WHERE id=?",id);
        });
    }

    private void advance(Map<String,Object> batch) {
        UUID id=(UUID)batch.get("id");
        try {
            if("PREPARED".equals(batch.get("state"))) {
                String file=(String)batch.get("input_file_id");
                if(file==null) {
                    file=client.upload((String)batch.get("payload"),id);
                    db.update("UPDATE idee_translation_batch SET input_file_id=? WHERE id=?",file,id);
                }
                db.update("UPDATE idee_translation_batch SET state='SUBMITTING',next_poll_at=now()+interval '1 minute' WHERE id=?",id);
                JsonNode remote;
                try { remote=client.createBatch(file,id); }
                catch(OpenAiTranslations.ApiException error) {
                    if(error.rejected()) db.update("UPDATE idee_translation_batch SET state='PREPARED' WHERE id=?",id);
                    throw error;
                }
                saveProvider(id,remote); return;
            }
            if(batch.get("provider_id")==null) {
                var found=client.findBatch(id);
                if(found==null) { defer(id,"submission_unknown"); return; }
                saveProvider(id,found); return;
            }
            var remote=client.getBatch((String)batch.get("provider_id"));
            String state=remote.path("status").asText();
            if(!Set.of("validating","in_progress","finalizing","completed","failed","expired","cancelling","cancelled").contains(state))
                throw new IllegalArgumentException("Invalid batch state");
            db.update("UPDATE idee_translation_batch SET state=?,next_poll_at=now()+interval '1 minute' WHERE id=?",state,id);
            if(!Set.of("completed","failed","expired","cancelled").contains(state)) {
                db.update("UPDATE idee_translation_batch SET attempts=0,last_error=NULL WHERE id=?",id); return;
            }
            List<JsonNode> outputs;
            try { outputs=client.outputs(remote); }
            catch(OpenAiTranslations.ApiException error) { if(error.status!=404) throw error; outputs=List.of(); }
            var results=new HashMap<String,JsonNode>(); var duplicate=new HashSet<String>();
            for(var output:outputs) {
                String custom=output.path("custom_id").asText();
                if(results.putIfAbsent(custom,output)!=null) duplicate.add(custom);
            }
            for(var item:db.queryForList("SELECT * FROM idee_translation_batch_item WHERE batch_id=? AND outcome='pending' ORDER BY outing_id,language",id)) {
                String custom=item.get("outing_id")+":"+item.get("language");
                var output=duplicate.contains(custom)?null:results.get(custom);
                OpenAiTranslations.Texts texts=null;
                if(output!=null && output.path("response").path("status_code").asInt()==200) {
                    try { texts=client.parse(output.path("response").path("body"),item.get("reused_descriptions")); }
                    catch(IllegalArgumentException ignored) { /* only this language is retried */ }
                }
                apply(batch,item,texts);
            }
            db.update("""
                UPDATE idee_translation_batch SET completed_at=now(),payload='',attempts=0,last_error=NULL,
                  succeeded=(SELECT count(*) FROM idee_translation_batch_item WHERE batch_id=? AND outcome IN ('applied','reused')),
                  failed=(SELECT count(*) FROM idee_translation_batch_item WHERE batch_id=? AND outcome='failed')
                WHERE id=? AND NOT EXISTS(SELECT 1 FROM idee_translation_batch_item WHERE batch_id=? AND outcome='pending')
                """,id,id,id,id);
        } catch(Exception error) { defer(id,error instanceof OpenAiTranslations.ApiException http?"openai_http_"+http.status:error.getClass().getSimpleName()); }
    }

    private void saveProvider(UUID id,JsonNode remote) {
        String provider=remote.path("id").asText();
        if(!provider.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Missing provider ID");
        db.update("UPDATE idee_translation_batch SET provider_id=?,state='validating',attempts=0,last_error=NULL,next_poll_at=now()+interval '1 minute' WHERE id=?",provider,id);
    }
    private void defer(UUID id,String reason) {
        db.update("UPDATE idee_translation_batch SET attempts=attempts+1,last_error=?,next_poll_at=now()+make_interval(secs => LEAST(3600,60*power(2,LEAST(attempts,6)))::int) WHERE id=?",reason,id);
    }
    private void retry(Map<String,Object> item,UUID batch,String reason) {
        db.update("""
            UPDATE idee_outing_translation_job SET batch_id=NULL,attempts=attempts+1,last_error=?,
              next_attempt_at=now()+make_interval(secs => LEAST(3600,30*power(2,LEAST(attempts,7)))::int)
            WHERE outing_id=? AND language=? AND source_hash=? AND batch_id IS NOT DISTINCT FROM ?::uuid
            """,reason,item.get("outing_id"),item.get("language"),item.get("source_hash"),batch);
    }
    private void apply(Map<String,Object> batch,Map<String,Object> item,OpenAiTranslations.Texts texts) {
        UUID id=(UUID)batch.get("id"); Object outing=item.get("outing_id"),language=item.get("language"),hash=item.get("source_hash");
        try {
            transaction.executeWithoutResult(status -> {
                if(db.queryForList("SELECT id FROM idee_outing WHERE id=? FOR UPDATE NOWAIT",outing).isEmpty()) return;
                boolean current=db.queryForObject("SELECT EXISTS(SELECT 1 FROM idee_translation_source WHERE outing_id=? AND source_hash=?)",Boolean.class,outing,hash);
                String outcome="stale";
                if(current) {
                    boolean exists=db.queryForObject("SELECT EXISTS(SELECT 1 FROM idee_outing_translation WHERE outing_id=? AND language=? AND source_hash=?)",Boolean.class,outing,language,hash);
                    if(exists) outcome="reused";
                    else if(texts!=null) {
                        db.update("""
                            INSERT INTO idee_outing_translation(outing_id,language,source_hash,title,description_longue,description_courte,model,prompt_version,source)
                            VALUES(?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(outing_id,language) DO UPDATE SET
                            source_hash=excluded.source_hash,title=excluded.title,description_longue=excluded.description_longue,
                            description_courte=excluded.description_courte,model=excluded.model,prompt_version=excluded.prompt_version,source=excluded.source,generated_at=now()
                            """,outing,language,hash,texts.title(),texts.description_longue(),texts.description_courte(),batch.get("model"),batch.get("prompt_version"),item.get("source").toString());
                        outcome="applied";
                    } else { retry(item,id,"translation_failed"); outcome="failed"; }
                }
                if(!outcome.equals("failed")) {
                    int removed=db.update("DELETE FROM idee_outing_translation_job WHERE outing_id=? AND language=? AND source_hash=? AND batch_id=?",outing,language,hash,id);
                    if(!current && removed>0) db.queryForList("SELECT idee_queue_outing_translation(?,?)",outing,language);
                }
                db.update("UPDATE idee_translation_batch_item SET outcome=? WHERE batch_id=? AND outing_id=? AND language=?",outcome,id,outing,language);
            });
        } catch(CannotAcquireLockException ignored) { /* collect again after the concurrent update */ }
    }
}
