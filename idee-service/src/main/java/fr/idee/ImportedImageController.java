package fr.idee;

import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ImportedImageController {
    private final ImportedImages images;
    public ImportedImageController(ImportedImages images) { this.images=images; }

    @GetMapping("/api/admin/images/status")
    public Map<String,Object> status() { return images.status(); }

    @PostMapping("/api/admin/images/download-all-missing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> downloadAllMissing() { return images.enqueueAllMissing(); }

    @GetMapping("/api/media/images/{filename}")
    public ResponseEntity<Resource> image(@PathVariable String filename) {
        if (!ImageNames.valid(filename))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String stored=images.storedFilename(filename);
        if (stored==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!stored.equals(filename)) return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(java.net.URI.create("/api/media/images/"+stored)).build();
        var path=images.directory.resolve(stored);
        String type=switch(filename.substring(filename.lastIndexOf('.')+1)) {
            case "jpg" -> "image/jpeg"; case "png" -> "image/png"; case "gif" -> "image/gif";
            case "webp" -> "image/webp"; default -> "image/avif";
        };
        return ResponseEntity.ok().header("Cache-Control","public, max-age=31536000, immutable")
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(type))
                .body(new FileSystemResource(path));
    }
}
