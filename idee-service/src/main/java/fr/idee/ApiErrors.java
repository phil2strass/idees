package fr.idee;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"Requete refusee":e.getReason())); }
 @ExceptionHandler(DataIntegrityViolationException.class)
 public ResponseEntity<?> conflict(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error","Doublon ou relation invalide. Verifiez le slug, la source et les categories.")); }
 @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
 public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Requete invalide. Verifiez le JSON et les dates au format ISO.")); }
}
