package fr.idee;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DatatourismeControllerTest {
    private final String token="test-import-token-with-at-least-32-characters";

    @Test void syncRequiresTokenAndReturnsAcceptedWithoutBlockingOnImport() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var importer=new DatatourismeImporter(null,null,null,null,"",false) {
            @Override public int requestSync() { calls.incrementAndGet(); return 2; }
        };
        var mvc=MockMvcBuilders.standaloneSetup(new DatatourismeController(null,importer))
                .setControllerAdvice(new ApiErrors()).addFilters(new ImportSecurityFilter(token)).build();
        mvc.perform(post("/api/admin/datatourisme/sync")).andExpect(status().isUnauthorized());
        assertEquals(0,calls.get());
        mvc.perform(post("/api/admin/datatourisme/sync").header("Authorization","Bearer "+token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.queuedDepartments").value(2))
                .andExpect(jsonPath("$.statusUrl").value("/api/admin/datatourisme/status"));
        assertEquals(1,calls.get());
    }

    @Test void disabledImporterRejectsRequestsWithoutTouchingDatabase() {
        for (boolean enabled:new boolean[]{false,true}) {
            var importer=new DatatourismeImporter(null,null,null,null,"",enabled);
            var error=assertThrows(ResponseStatusException.class,importer::requestSync);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        }
    }
}
