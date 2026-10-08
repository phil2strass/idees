package fr.idee;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutingDescriptions {
    private final JdbcTemplate db;
    private final MistralDescriptions mistral;
    private final TransactionTemplate transaction;
    public OutingDescriptions(JdbcTemplate db,MistralDescriptions mistral,PlatformTransactionManager manager) {
        this.db=db; this.mistral=mistral; transaction=new TransactionTemplate(manager);
    }
    public List<Map<String,Object>> forOuting(Object id) {
        return db.queryForList("""
            SELECT s.language,s.description,g.description_longue,g.description_courte
            FROM idee_outing_description_source s
            LEFT JOIN idee_outing_description g ON g.outing_id=s.outing_id AND g.language=s.language
                AND g.source_description=s.description
            WHERE s.outing_id=? ORDER BY s.language
            """,id);
    }
    public List<Map<String,Object>> translationsForOuting(Object id) {
        return db.queryForList("""
            SELECT t.language,t.title,t.description_longue,t.description_courte
            FROM idee_outing_translation t JOIN idee_translation_source s
              ON s.outing_id=t.outing_id AND s.source_hash=t.source_hash
            WHERE t.outing_id=? ORDER BY t.language
            """,id);
    }
    // One query for a catalogue page; no long descriptions in list responses.
    public void addCatalogueTranslations(List<Map<String,Object>> outings, String language) {
        if (outings.isEmpty() || language.equals("fr")) return;
        var ids=outings.stream().map(row -> ((Number)row.get("id")).longValue()).toList();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var parameters=new ArrayList<Object>(); parameters.add(language); parameters.addAll(ids);
        var translations=db.queryForList("""
            SELECT t.outing_id,t.language,t.title,t.description_courte
            FROM idee_outing_translation t JOIN idee_translation_source s
              ON s.outing_id=t.outing_id AND s.source_hash=t.source_hash
            WHERE t.language=? AND t.outing_id IN (
            """+placeholders+")",parameters.toArray());
        for (var outing:outings) outing.put("translations", translations.stream()
            .filter(t -> ((Number)t.get("outing_id")).longValue()==((Number)outing.get("id")).longValue())
            .map(t -> { var copy=new LinkedHashMap<>(t);copy.remove("outing_id");return copy; }).toList());
    }
    public Map<String,Object> generateFrench(String slug) {
        return transaction.execute(status -> {
            // Serializes concurrent paid requests and blocks source changes during generation.
            var outings=db.queryForList("SELECT id FROM idee_outing WHERE slug=? FOR UPDATE NOWAIT",slug);
            if (outings.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sortie introuvable.");
            Object id=outings.getFirst().get("id");
            var sources=db.queryForList("SELECT description FROM idee_outing_description_source WHERE outing_id=? AND language='fr'",id);
            if (sources.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Description source francaise absente.");
            String source=(String)sources.getFirst().get("description");
            var saved=db.queryForList("""
                SELECT description_longue,description_courte FROM idee_outing_description
                WHERE outing_id=? AND language='fr' AND source_description=? AND model=? AND prompt_version=?
                """,id,source,mistral.model,MistralDescriptions.PROMPT_VERSION);
            if (!saved.isEmpty()) {
                clearJob(id,source);
                return response(slug,saved.getFirst(),true);
            }
            var texts=mistral.rewrite(source);
            db.update("""
                INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
                VALUES(?,'fr',?,?,?,?,?) ON CONFLICT(outing_id,language) DO UPDATE SET
                source_description=excluded.source_description,description_longue=excluded.description_longue,
                description_courte=excluded.description_courte,model=excluded.model,prompt_version=excluded.prompt_version,generated_at=now()
                """,id,source,texts.description_longue(),texts.description_courte(),mistral.model,MistralDescriptions.PROMPT_VERSION);
            clearJob(id,source);
            return response(slug,Map.of("description_longue",texts.description_longue(),"description_courte",texts.description_courte()),false);
        });
    }
    private void clearJob(Object id,String source) {
        db.update("DELETE FROM idee_outing_description_job WHERE outing_id=? AND source_description=?",id,source);
    }
    private Map<String,Object> response(String slug,Map<String,Object> texts,boolean reused) {
        return Map.of("slug",slug,"language","fr","model",mistral.model,"reused",reused,
            "description_longue",texts.get("description_longue"),"description_courte",texts.get("description_courte"));
    }
}
