package fr.idee;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/outings")
public class OutingDescriptionController {
    private final OutingDescriptions descriptions;
    public OutingDescriptionController(OutingDescriptions descriptions) { this.descriptions=descriptions; }
    @PostMapping("/{slug}/descriptions/generate")
    public Map<String,Object> generate(@PathVariable String slug) {
        try { return descriptions.generateFrench(slug); }
        catch (CannotAcquireLockException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Cette sortie est en cours de modification. Reessayez plus tard.");
        }
    }
}
