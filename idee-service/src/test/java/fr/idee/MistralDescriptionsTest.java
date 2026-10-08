package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MistralDescriptionsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MistralDescriptions client=new MistralDescriptions(json,"","mistral-small-2603");
    private com.fasterxml.jackson.databind.JsonNode response(String longText,String shortText,String finish) throws Exception {
        return json.valueToTree(Map.of("choices",java.util.List.of(Map.of("finish_reason",finish,
            "message",Map.of("content",json.writeValueAsString(Map.of("description_longue",longText,"description_courte",shortText)))))));
    }
    @Test void validatesUnicodeLengthWithoutTruncatingTheResponse() throws Exception {
        String exactly300="é".repeat(299)+"🌿";
        assertEquals(exactly300,client.parse(response("Texte long",exactly300,"stop")).description_courte());
        assertThrows(IllegalArgumentException.class,()->client.parse(response("Texte long",exactly300+"!","stop")));
        assertThrows(IllegalArgumentException.class,()->client.parse(response("", "Court", "stop")));
        assertThrows(IllegalArgumentException.class,()->client.parse(response("Texte long","Court","length")));
    }
    @Test void rejectsMissingOrNonTextContent() throws Exception {
        assertThrows(IllegalArgumentException.class,()->client.parse(json.readTree("{}")));
        var invalid=json.valueToTree(Map.of("choices",java.util.List.of(Map.of("finish_reason","stop",
            "message",Map.of("content",json.writeValueAsString(Map.of("description_longue",12,"description_courte","ok")))))));
        assertThrows(IllegalArgumentException.class,()->client.parse(invalid));
    }
    @Test void requestUsesPinnedModelAndTreatsSourceAsUserData() {
        var request=json.valueToTree(client.request("Description originale"));
        assertEquals("mistral-small-2603",request.path("model").asText());
        assertEquals("Description originale",request.path("messages").get(1).path("content").asText());
        assertEquals(300,request.path("response_format").path("json_schema").path("schema").path("properties").path("description_courte").path("maxLength").asInt());
    }
    @Test void batchProtocolSupportsInlineResultsFilesAndRecoveryMetadata() throws Exception {
        var requests=new java.util.ArrayList<java.net.http.HttpRequest>();
        var responses=new java.util.ArrayDeque<String>();
        var statuses=new java.util.ArrayDeque<Integer>();
        var api=new MistralDescriptions(json,"fake-token","mistral-small-2603") {
            @Override java.net.http.HttpResponse<String> send(java.net.http.HttpRequest request) {
                requests.add(request);
                String body=responses.remove(); int status=statuses.isEmpty()?200:statuses.remove();
                return new java.net.http.HttpResponse<>() {
                    public int statusCode() { return status; }
                    public String body() { return body; }
                    public java.net.http.HttpRequest request() { return request; }
                    public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
                    public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(),(a,b)->true); }
                    public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
                    public java.net.URI uri() { return request.uri(); }
                    public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
                };
            }
        };
        var id=java.util.UUID.randomUUID();
        var payload=json.valueToTree(api.batchRequest(id,java.util.List.of(Map.of("outing_id",42L,"source_description","Source française"))));
        assertEquals("/v1/chat/completions",payload.path("endpoint").asText());
        assertEquals(24,payload.path("timeout_hours").asInt());
        assertEquals(id.toString(),payload.path("metadata").path("idee_batch_id").asText());
        assertEquals("42",payload.path("requests").get(0).path("custom_id").asText());
        assertEquals("mistral-small-2603",payload.path("model").asText());
        assertFalse(payload.path("requests").get(0).path("body").has("model"));
        assertEquals("json_schema",payload.path("requests").get(0).path("body").path("response_format").path("type").asText());
        responses.add("{\"id\":\"remote-1\",\"status\":\"QUEUED\"}");
        api.createBatch(payload.toString());
        assertEquals("POST",requests.getLast().method());
        assertEquals("https://api.mistral.ai/v1/batch/jobs",requests.getLast().uri().toString());
        assertEquals("Bearer fake-token",requests.getLast().headers().firstValue("Authorization").orElseThrow());
        responses.add("{\"status\":\"SUCCESS\",\"outputs\":[{\"custom_id\":\"42\"}]}");
        var remote=api.getBatch("remote-1");
        assertEquals("/v1/batch/jobs/remote-1",requests.getLast().uri().getPath());
        assertEquals("inline=true",requests.getLast().uri().getQuery());
        assertEquals("42",api.batchOutputs(remote).getFirst().path("custom_id").asText());
        responses.add("{\"custom_id\":\"42\"}\n{\"custom_id\":\"43\"}\n");
        assertEquals(2,api.batchOutputs(json.readTree("{\"output_file\":\"file-1\"}")).size());
        assertEquals("/v1/files/file-1/content",requests.getLast().uri().getPath());
        responses.add(json.writeValueAsString(Map.of("data",java.util.List.of(Map.of("id","remote-1","metadata",Map.of("idee_batch_id",id.toString()))))));
        assertEquals("remote-1",api.findBatch(id).path("id").asText());
        responses.add("{\"data\":[]}"); assertNull(api.findBatch(id));
        responses.add("Never expose provider response"); statuses.add(429);
        var error=assertThrows(MistralDescriptions.BatchHttpException.class,()->api.createBatch("{}"));
        assertTrue(error.rejected()); assertFalse(error.getMessage().contains("Never expose"));
        assertFalse(new MistralDescriptions.BatchHttpException(500).rejected());
        assertFalse(new MistralDescriptions.BatchHttpException(408).rejected());
        assertThrows(IllegalArgumentException.class,()->api.getBatch("../unexpected"));
    }
    @Test void generationEndpointRequiresPrivateImportToken() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=new OutingDescriptions(null,null,null) {
            @Override public Map<String,Object> generateFrench(String slug) { calls.incrementAndGet(); return Map.of("slug",slug); }
        };
        String token="private-test-token-at-least-32-characters";
        var mvc=MockMvcBuilders.standaloneSetup(new OutingDescriptionController(service))
            .addFilters(new ImportSecurityFilter(token)).build();
        mvc.perform(post("/api/admin/outings/example/descriptions/generate")).andExpect(status().isUnauthorized());
        assertEquals(0,calls.get());
        mvc.perform(post("/api/admin/outings/example/descriptions/generate").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("example"));
        assertEquals(1,calls.get());
    }
    @Test void queueStatusRequiresPrivateImportToken() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new OutingDescriptionWorker(null,null,null,null,false) {
            @Override public Map<String,Object> status() {
                calls.incrementAndGet(); return Map.of("enabled",true,"pending",2);
            }
        };
        String token="private-test-token-at-least-32-characters";
        var mvc=MockMvcBuilders.standaloneSetup(new OutingDescriptionQueueController(worker))
            .addFilters(new ImportSecurityFilter(token)).build();
        mvc.perform(get("/api/admin/descriptions/status").servletPath("/api/admin/descriptions/status"))
            .andExpect(status().isUnauthorized());
        assertEquals(0,calls.get());
        mvc.perform(get("/api/admin/descriptions/status").servletPath("/api/admin/descriptions/status")
                .header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(2));
        assertEquals(1,calls.get());
    }
    @Test void backfillRequiresTokenAndDefaultsTo500() throws Exception {
        var limits=new java.util.ArrayList<Integer>();
        var worker=new OutingDescriptionWorker(null,null,null,null,false) {
            @Override public Map<String,Object> enqueueMissingFrench(int limit) {
                limits.add(limit); return Map.of("accepted",limit);
            }
        };
        String token="private-test-token-at-least-32-characters";
        var mvc=MockMvcBuilders.standaloneSetup(new OutingDescriptionQueueController(worker))
            .addFilters(new ImportSecurityFilter(token)).build();
        String url="/api/admin/descriptions/generate-missing";
        mvc.perform(post(url).servletPath(url)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).servletPath(url).header("Authorization","Bearer incorrect"))
            .andExpect(status().isUnauthorized());
        assertTrue(limits.isEmpty());
        mvc.perform(post(url).servletPath(url).header("Authorization","Bearer "+token))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(500));
        mvc.perform(post(url).servletPath(url).param("limit","12").header("Authorization","Bearer "+token))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(12));
        mvc.perform(post(url).servletPath(url).param("limit","invalid").header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest());
        assertEquals(java.util.List.of(500,12),limits);
    }
}
