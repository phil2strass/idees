package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatatourismeTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void documentsAreNotDisplayedAsImagesAndUnsafeLinksAreNotPublished() {
        assertTrue(DatatourismeDetails.isImage("https://example.org/image.jpg?version=2",null));
        assertTrue(DatatourismeDetails.isImage("https://example.org/download?id=42","image/jpeg"));
        assertFalse(DatatourismeDetails.isImage("https://example.org/file.pdf",null));
        assertFalse(DatatourismeDetails.isImage("https://example.org/file.jpg","application/pdf"));
        assertFalse(DatatourismeDetails.webUrl("javascript:alert(1)",false));
        assertFalse(DatatourismeDetails.webUrl("https://user@example.org/image.jpg",true));
        assertFalse(DatatourismeDetails.webUrl("http://example.org/image.jpg",true));
    }
    @Test void fullImportHasExplicitFieldsAndNoDateWindow() {
        String url=DatatourismeImporter.firstUrl("67",null);
        assertTrue(url.contains("department=67")); assertTrue(url.contains("page_size=250"));
        assertTrue(url.contains("lang=*")); assertTrue(url.contains("fields=*"));
        assertFalse(url.contains("start=")); assertFalse(url.contains("end=")); assertFalse(url.contains("update="));
        assertTrue(DatatourismeImporter.firstUrl("68",LocalDate.of(2026,9,23)).contains("update=2026-09-23"));
    }
    @Test void cursorIsPreservedExactlyAndCannotLeakKey() {
        String url="https://api.datatourisme.fr/v1/entertainmentAndEvent?crs=a%2Bb%3D&page_size=250";
        assertEquals(url,DatatourismeImporter.trustedUrl(url).toString());
        for (String bad:new String[]{"http://api.datatourisme.fr/v1/catalog","https://evil.example/v1/catalog",
                "https://api.datatourisme.fr.evil.example/v1/catalog","https://user@api.datatourisme.fr/v1/catalog",
                "https://api.datatourisme.fr:444/v1/catalog","https://api.datatourisme.fr/redirect"})
            assertThrows(IllegalArgumentException.class,()->DatatourismeImporter.trustedUrl(bad));
    }
    @Test void quotaRetryHonorsLongerServerDelay() {
        assertEquals(3600,DatatourismeImporter.retryDelay(429,null));
        assertEquals(7200,DatatourismeImporter.retryDelay(429,"7200"));
        assertEquals(300,DatatourismeImporter.retryDelay(503,"invalid"));
    }
    @Test void inclusiveAllDayRange() throws Exception {
        var p=DatatourismeMapper.parsePeriod(json.readTree("""
            {"startDate":"2026-09-25","endDate":"2026-09-27"}
            """));
        assertEquals(LocalDateTime.parse("2026-09-28T00:00"),p.end()); assertTrue(p.allDay()); assertNull(p.rule());
    }
    @Test void openingDaysDoNotBecomeContinuousEvent() throws Exception {
        var p=DatatourismeMapper.parsePeriod(json.readTree("""
            {"startDate":"2026-01-25","endDate":"2027-01-10","startTime":"10:00","endTime":"17:00",
             "appliesOnDay":[{"key":"Tuesday"},{"key":"Friday"}]}
            """));
        assertEquals(LocalDateTime.parse("2026-01-27T10:00"),p.start());
        assertEquals(LocalDateTime.parse("2026-01-27T17:00"),p.end());
        assertEquals("FREQ=WEEKLY;BYDAY=FR,TU;UNTIL=20270110T225959Z",p.rule());
    }
    @Test void dailyOpeningAndOvernight() throws Exception {
        var p=DatatourismeMapper.parsePeriod(json.readTree("""
            {"startDate":"2026-09-25","endDate":"2026-09-27","startTime":"22:00","endTime":"02:00"}
            """));
        assertEquals(LocalDateTime.parse("2026-09-26T02:00"),p.end());
        assertTrue(p.rule().startsWith("FREQ=DAILY;"));
    }
    @Test void unknownOrIncompleteDatesAreNotInvented() throws Exception {
        for (String payload:new String[]{"{}",
                "{\"startDate\":\"2026-01-01\",\"endDate\":\"2025-12-31\"}",
                "{\"startDate\":\"2026-01-01\",\"appliesOnDay\":[{\"key\":\"PublicHoliday\"}]}"}) {
            var node=json.readTree(payload);
            assertThrows(RuntimeException.class,()->DatatourismeMapper.parsePeriod(node));
        }
    }
    @Test void frenchApiShape() throws Exception {
        assertEquals("Titre",DatatourismeMapper.localized(json.readTree("{\"@fr\":\"Titre\"}")));
        assertEquals("rue A\nrue B",DatatourismeMapper.localized(json.readTree("[\"rue A\",\"rue B\"]")));
    }
    @Test void missingEndTimeIsExplicitInsteadOfDroppingTheEvent() throws Exception {
        var p=DatatourismeMapper.parsePeriod(json.readTree("{\"startDate\":\"2026-01-01\",\"startTime\":\"10:00\"}"));
        assertTrue(p.startKnown()); assertFalse(p.endKnown()); assertFalse(p.allDay());
        assertEquals(LocalDateTime.parse("2026-01-01T10:00"),p.start());
        assertEquals(LocalDateTime.parse("2026-01-02T00:00"),p.end());
    }
    @Test void realApiOmitsNextOnLastPageOnly() throws Exception {
        assertNull(DatatourismeImporter.nextUrl(json.readTree("{\"objects\":[],\"meta\":{\"page\":7,\"total_pages\":7}}")));
        assertNull(DatatourismeImporter.nextUrl(json.readTree("{\"objects\":[],\"meta\":{\"total\":0}}")));
        var broken=json.readTree("{\"objects\":[],\"meta\":{\"page\":2,\"total_pages\":7}}");
        assertThrows(IllegalArgumentException.class,()->DatatourismeImporter.nextUrl(broken));
    }
}
