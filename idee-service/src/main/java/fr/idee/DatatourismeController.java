package fr.idee;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

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
