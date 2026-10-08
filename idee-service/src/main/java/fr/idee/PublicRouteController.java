package fr.idee;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class PublicRouteController {
    private final JdbcTemplate db;
    private final CatalogController catalog;

    public PublicRouteController(JdbcTemplate db, CatalogController catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    String resolve(String path) {
        if (path == null || path.length() > 1024 || !path.matches("/[a-z0-9/-]+"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        var rows = db.queryForList("""
            SELECT o.slug FROM idee_public_route r JOIN idee_outing o ON o.id=r.outing_id
            WHERE r.path=? AND r.language IN ('fr','en','de','it','nl','es') AND o.status='published'
            UNION
            SELECT slug FROM idee_outing WHERE '/sorties/'||slug=? AND status='published'
            """, path, path);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return (String) rows.getFirst().get("slug");
    }

    @GetMapping("/outing-by-path")
    public Map<String,Object> outing(@RequestParam String path) {
        return catalog.outing(resolve(path));
    }

    // Nginx checks the public path before internally serving the Angular shell.
    @GetMapping("/public-page")
    public ResponseEntity<Void> page(@RequestHeader(value="X-Original-URI", required=false) String path) {
        String slug = resolve(path);
        var outing=catalog.outing(slug);
        String language=path.matches("^/(en|de|it|nl|es)/.*")?path.split("/")[1]:"fr";
        @SuppressWarnings("unchecked") var urls=(Map<String,String>)outing.get("urls");
        String canonical=urls.getOrDefault(language,language.equals("fr")?(String)outing.get("url"):"/"+language+outing.get("url"));
        if (!path.equals(canonical))
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header("Location", canonical).build();
        return ResponseEntity.ok().header("X-Accel-Redirect", "/_outing-shell.html").build();
    }
}
