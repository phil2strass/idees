package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalendarVolumeTest {
    @Test void catalogCanExceedOnePythonBatchWithoutDroppingOccurrences() {
        var engine=new CalendarEngine(new ObjectMapper(),"python3",Path.of("..","scripts","expand_calendar_json.py").toAbsolutePath().toString());
        List<Map<String,Object>> schedules=new ArrayList<>();
        for (int i=0;i<40;i++) schedules.add(Map.of("id",i,"timezone","Europe/Paris","starts_local","2026-01-01T10:00:00",
                "ends_local","2026-01-01T11:00:00","rrule","FREQ=DAILY","exceptions",List.of()));
        LocalDate start=LocalDate.of(2026,1,1);
        var rows=engine.expand(schedules,start,start.plusDays(550));
        assertEquals(22000,rows.size());
        assertEquals(40,rows.stream().map(r->r.get("schedule_id")).distinct().count());
    }
}
