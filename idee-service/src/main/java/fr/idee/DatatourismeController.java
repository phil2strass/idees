package fr.idee;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/datatourisme")
public class DatatourismeController {
    private final JdbcTemplate db;
    private final DatatourismeImporter importer;
    public DatatourismeController(JdbcTemplate db, DatatourismeImporter importer) { this.db=db; this.importer=importer; }
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> sync() {
        int queued=importer.requestSync();
        return Map.of("queuedDepartments",queued,"statusUrl","/api/admin/datatourisme/status");
    }
    @GetMapping("/imports")
    public List<Map<String,Object>> imports(@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>200 || offset<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limite comprise entre 1 et 200, offset positif ou nul.");
        return db.queryForList("""
            SELECT id,source,department,mode,state,started_at AS "startedAt",completed_at AS "completedAt",
              last_processed_at AS "lastProcessedAt",pages,objects,new_outings AS "newOutings",
              updated_outings AS "updatedOutings",mistral_outings AS "mistralOutings",
              description_translation_outings AS "descriptionTranslationOutings",
              title_translation_outings AS "titleTranslationOutings",last_error AS "lastError"
            FROM idee_import_run ORDER BY started_at DESC,id LIMIT ? OFFSET ?
            """,limit,offset);
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.of("enabled",importer.isEnabled(),
                "nextRequestAt",db.queryForObject("SELECT next_request_at FROM idee_datatourisme_quota WHERE singleton",java.sql.Timestamp.class),
                "departments",db.queryForList("SELECT department,mode,selection_version,started_at,completed_at,full_completed_at,pages,objects,last_error,sync_requested,(next_url IS NOT NULL) AS running FROM idee_datatourisme_state ORDER BY department"),
                "completeEvents",db.queryForObject("SELECT count(*) FROM idee_datatourisme_event WHERE selection_version=1",Long.class),
                "contacts",db.queryForObject("SELECT count(*) FROM idee_datatourisme_contact",Long.class),
                "translations",db.queryForObject("SELECT count(*) FROM idee_datatourisme_translation",Long.class),
                "resources",db.queryForObject("SELECT count(*) FROM idee_datatourisme_resource",Long.class),
                "events",db.queryForObject("SELECT count(*) FROM idee_datatourisme_event",Long.class),
                "periods",db.queryForObject("SELECT count(*) FROM idee_datatourisme_period",Long.class),
                "unparsedPeriods",db.queryForObject("SELECT count(*) FROM idee_datatourisme_period WHERE parse_error IS NOT NULL",Long.class));
    }
}
