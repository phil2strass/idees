package fr.idee;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/translations")
public class OutingTranslationController {
    private final OutingTranslationWorker worker;
    public OutingTranslationController(OutingTranslationWorker worker) { this.worker=worker; }
    @GetMapping("/status") public Map<String,Object> status() { return worker.status(); }
    @PostMapping("/generate-missing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> generateMissing(@RequestParam(defaultValue="500") int limit) { return worker.enqueueMissing(limit); }
}
