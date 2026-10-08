package fr.idee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CalendarEngine {
    private final ObjectMapper json;
    private final String python, script;
    public CalendarEngine(ObjectMapper json, @Value("${idee.calendar-python}") String python,
                          @Value("${idee.calendar-script}") String script) {
        this.json = json; this.python = python; this.script = script;
    }
    public List<Map<String,Object>> expand(List<Map<String,Object>> schedules, LocalDate from, LocalDate until) {
        if (schedules.isEmpty()) return List.of();
        if (schedules.size()>100) {
            List<Map<String,Object>> result=new ArrayList<>();
            for (int start=0;start<schedules.size();start+=100)
                result.addAll(expand(schedules.subList(start,Math.min(start+100,schedules.size())),from,until));
            return result;
        }
        try { return expandBatch(schedules,from,until); }
        catch (ResponseStatusException e) {
            String reason=Objects.toString(e.getReason(),"");
            if (schedules.size()<2 || !(reason.contains("too many occurrences") || reason.equals("Trop de séances.") || reason.equals("Calendrier trop complexe."))) throw e;
            int middle=schedules.size()/2;
            List<Map<String,Object>> result=new ArrayList<>(expand(schedules.subList(0,middle),from,until));
            result.addAll(expand(schedules.subList(middle,schedules.size()),from,until));
            return result;
        }
    }
    private List<Map<String,Object>> expandBatch(List<Map<String,Object>> schedules, LocalDate from, LocalDate until) {
        Process process = null;
        try {
            process = new ProcessBuilder(python, script).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process running = process;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var output = executor.submit(() -> running.getInputStream().readNBytes(8_000_001));
                try (var input = process.getOutputStream()) {
                    json.writeValue(input, Map.of("from",from.toString(),"until",until.toString(),"schedules",schedules));
                }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Calendrier trop complexe.");
                }
                byte[] bytes = output.get(2, TimeUnit.SECONDS);
                if (bytes.length > 8_000_000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Trop de séances.");
                if (process.exitValue() != 0) {
                    var result = json.readTree(bytes);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Calendrier invalide : " + result.path("error").asText());
                }
                return json.readValue(bytes, new TypeReference<>() {});
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Calcul du calendrier indisponible.");
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
