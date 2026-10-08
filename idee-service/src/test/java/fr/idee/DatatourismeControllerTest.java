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
    @Test void importHistoryRequiresTokenAndValidatesPagination() throws Exception {
        var calls=new java.util.ArrayList<java.util.List<Object>>();
        var db=new org.springframework.jdbc.core.JdbcTemplate() {
            @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... args) {
                calls.add(java.util.Arrays.asList(args));
                return java.util.List.of(java.util.Map.of("newOutings",2,"updatedOutings",3,"mistralOutings",4,
                    "descriptionTranslationOutings",4,"titleTranslationOutings",5));
            }
        };
        var mvc=MockMvcBuilders.standaloneSetup(new DatatourismeController(db,null))
            .setControllerAdvice(new ApiErrors()).addFilters(new ImportSecurityFilter(token)).build();
        String url="/api/admin/datatourisme/imports";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).servletPath(url)).andExpect(status().isUnauthorized());
        assertTrue(calls.isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).servletPath(url).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].newOutings").value(2))
            .andExpect(jsonPath("$[0].titleTranslationOutings").value(5));
        assertEquals(java.util.List.of(50,0),calls.getFirst());
        for(String params:new String[]{"?limit=0","?limit=201","?offset=-1"})
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url+params).servletPath(url).header("Authorization","Bearer "+token))
                .andExpect(status().isBadRequest());
        assertEquals(1,calls.size());
    }
}
