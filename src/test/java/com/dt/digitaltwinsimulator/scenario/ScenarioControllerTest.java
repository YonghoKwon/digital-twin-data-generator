package com.dt.digitaltwinsimulator.scenario;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.files.base-dir=.", "simulator.scenario.max-runs=1", "simulator.http-logging.enabled=false"
})
@AutoConfigureMockMvc
class ScenarioControllerTest {
    static final Path DIRECTORY = temporary();
    static Path temporary() { try { return Files.createTempDirectory("scenario-library-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("simulator.scenario.dir", DIRECTORY::toString); }
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired ScenarioService service;
    @Autowired ScenarioLibrary library;
    @Autowired ScenarioCompiler compiler;
    @LocalServerPort int port;
    @BeforeEach void setup() throws Exception {
        service.stopAll();
        // Workers stop before the next test attempts to reuse the single active slot.
        Thread.sleep(30);
        try (var paths = Files.list(DIRECTORY)) { for (var path : paths.toList()) Files.deleteIfExists(path); }
    }
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.list(DIRECTORY)) { for (var path : paths.toList()) Files.deleteIfExists(path); }
        Files.deleteIfExists(DIRECTORY);
    }
    String mark() { return "{\"scenarioId\":\"test\",\"name\":\"Demo\",\"events\":[{\"at\":10000,\"type\":\"MARK\",\"label\":\"end\"}]}"; }
    JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }
    @Test void libraryCrudReturnsRawObjectAndSummaryIncludingInvalidFiles() throws Exception {
        var saved = call(put("/dt/scenarios/한글.json").contentType(MediaType.APPLICATION_JSON).content(mark()), 200);
        assertThat(saved.get("valid").asBoolean()).isTrue(); assertThat(saved.get("durationMs").asLong()).isEqualTo(10000);
        assertThat(call(get("/dt/scenarios/한글.json"), 200)).isEqualTo(mapper.readTree(mark()));
        Files.writeString(DIRECTORY.resolve("invalid.json"), "{bad");
        var listed = call(get("/dt/scenarios"), 200); assertThat(listed.size()).isEqualTo(2);
        assertThat(listed.get(0).get("valid").asBoolean()).isFalse();
        call(delete("/dt/scenarios/한글.json"), 204); call(get("/dt/scenarios/한글.json"), 404);
    }
    @Test void invalidSaveIsAtomicAndValidateCollectsErrorsAtHttp200() throws Exception {
        call(put("/dt/scenarios/test.json").contentType(MediaType.APPLICATION_JSON).content(mark()), 200);
        String invalid = "{\"speed\":0,\"onDisconnect\":\"BAD\",\"events\":[{\"at\":-1,\"type\":\"BOGUS\"}]}";
        var validation = call(post("/dt/scenarios/validate").contentType(MediaType.APPLICATION_JSON).content(invalid), 200);
        assertThat(validation.get("valid").asBoolean()).isFalse(); assertThat(validation.get("errors").size()).isGreaterThanOrEqualTo(4);
        call(put("/dt/scenarios/test.json").contentType(MediaType.APPLICATION_JSON).content(invalid), 400);
        assertThat(call(get("/dt/scenarios/test.json"), 200)).isEqualTo(mapper.readTree(mark()));
        call(post("/dt/scenarios/validate").contentType(MediaType.APPLICATION_JSON).content("["), 400);
    }
    @Test void blocksPathTraversalAndAbsolutePaths() {
        var root = mapper.readTree(mark());
        for (String path : List.of("../escape.json", "sub/file.json", "C:\\outside.json", "sub\\file.json")) {
            assertThatThrownBy(() -> library.save(path, root)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> library.read(path)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> library.delete(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void blocksOutsideFileSymlinksWhenWindowsPrivilegeIsAvailable() throws Exception {
        var root = mapper.readTree(mark());
        Path external = Files.createTempFile("scenario-outside-", ".json");
        try {
            Files.writeString(external, mark());
            Path link = DIRECTORY.resolve("linked.json");
            try { Files.createSymbolicLink(link, external); }
            catch (Exception exception) { Assumptions.abort("Windows symlink privilege unavailable: " + exception.getClass().getSimpleName()); }
            assertThatThrownBy(() -> library.read("linked.json")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> library.save("linked.json", root)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> library.delete("linked.json")).isInstanceOf(IllegalArgumentException.class);
        } finally { Files.deleteIfExists(external); }
    }
    @Test void runsExposeContractStatusesControlsAndErrorShapes() throws Exception {
        call(put("/dt/scenarios/test.json").contentType(MediaType.APPLICATION_JSON).content(mark()), 200);
        String body = "{\"runId\":\"http-run\",\"file\":\"test.json\",\"startPaused\":true}";
        var created = call(post("/dt/scenario-runs").contentType(MediaType.APPLICATION_JSON).content(body), 201);
        for (String key : List.of("runId", "scenarioId", "name", "state", "speed", "loop", "positionMs", "durationMs", "progress", "loopCount", "eventsTotal", "eventsDone", "sent", "failed", "skipped", "mockOps", "byTopic", "lastMark", "lastError", "startedAt", "lastEventAt", "lagMs")) assertThat(created.has(key)).as(key).isTrue();
        call(post("/dt/scenario-runs").contentType(MediaType.APPLICATION_JSON).content(body), 409);
        call(post("/dt/scenario-runs").contentType(MediaType.APPLICATION_JSON).content(body.replace("http-run", "another")), 429);
        var patched = call(patch("/dt/scenario-runs/http-run").contentType(MediaType.APPLICATION_JSON).content("{\"speed\":2,\"seekMs\":5000}"), 200);
        assertThat(patched.get("positionMs").asLong()).isEqualTo(5000);
        for (String bad : List.of("{\"speed\":101}", "{\"paused\":\"true\"}", "{\"seekMs\":1.5}", "{\"seekMs\":-1}")) call(patch("/dt/scenario-runs/http-run").contentType(MediaType.APPLICATION_JSON).content(bad), 400);
        assertThat(call(get("/dt/scenario-runs"), 200).isArray()).isTrue();
        call(get("/dt/scenario-runs/http-run/events?limit=0"), 400);
        assertThat(call(get("/dt/scenario-runs/http-run/events?limit=bad"), 400).has("error")).isTrue();
        call(delete("/dt/scenario-runs/http-run"), 200);
        call(patch("/dt/scenario-runs/http-run").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"), 409);
        assertThat(call(get("/dt/scenario-runs/missing"), 404).has("message")).isTrue();
        call(delete("/dt/scenario-runs"), 200);
        call(post("/dt/scenario-runs").contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":" + mark() + ",\"file\":\"test.json\"}"), 400);
    }
    @Test void mockUpdateDisableEnableAndReplaceChangeActualHttpResponse() throws Exception {
        String id = "scenario-route";
        var definition = mapper.createObjectNode(); var events = definition.putArray("events");
        for (int i = 0; i < 4; i++) {
            var event = events.addObject().put("at", i * 10000).put("routeId", id);
            if (i == 0 || i == 3) {
                event.put("type", "MOCK_UPDATE");
                event.set("route", mapper.readTree("{\"id\":\"" + id + "\",\"method\":\"GET\",\"path\":\"/scenario-status\",\"body\":\"" + (i == 0 ? "initial" : "changed") + "\"}"));
            } else event.put("type", i == 1 ? "MOCK_DISABLE" : "MOCK_ENABLE");
        }
        events.addObject().put("at", 40000).put("type", "MARK").put("label", "done");
        var request = mapper.createObjectNode().put("runId", "mock-http").put("startPaused", true); request.set("scenario", definition);
        call(post("/dt/scenario-runs").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)), 201);
        try (var client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 4; i++) {
                service.patch("mock-http", new ScenarioResponses.Patch(null, false, (long) i * 10000));
                int expected = i + 1;
                ScenarioServiceTest.await(() -> service.get("mock-http").eventsDone() == expected, 1000);
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mock/scenario-status")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(i == 1 ? 404 : 200);
                if (i != 1) assertThat(response.body()).isEqualTo(i == 3 ? "changed" : "initial");
            }
        }
        service.patch("mock-http", new ScenarioResponses.Patch(null, false, 40000L));
        ScenarioServiceTest.await(() -> service.get("mock-http").state() == ScenarioResponses.State.COMPLETED, 1000);
        assertThat(service.get("mock-http").mockOps()).isEqualTo(4); assertThat(service.get("mock-http").lastMark()).isEqualTo("done");
        assertThat(service.events("mock-http", 50)).extracting(ScenarioResponses.EventLog::result).containsExactly("MOCK_OK", "MOCK_OK", "MOCK_OK", "MOCK_OK", "MARK");
    }
    @Test void mockValidationHasNoSideEffectsAndRejectsInvalidRoute() throws Exception {
        var scenario = mapper.readTree("{\"events\":[{\"at\":0,\"type\":\"MOCK_UPDATE\",\"routeId\":\"validation-only\",\"route\":{\"id\":\"validation-only\",\"method\":\"GET\",\"path\":\"/validation-only\",\"body\":\"x\"}}]}");
        assertThat(compiler.validate(scenario).valid()).isTrue();
        call(get("/mock-admin/routes/validation-only"), 404);
        ((tools.jackson.databind.node.ObjectNode) scenario.get("events").get(0).get("route")).put("method", "TRACE");
        assertThat(compiler.validate(scenario).valid()).isFalse();
    }
    @Test void bothRepositoryExamplesValidateAndAppearUnderScenariosSwaggerTag() throws Exception {
        for (String file : List.of("coil-line-demo.json", "csv-track-demo.json")) {
            var result = compiler.validate(mapper.readTree(Files.readString(Path.of("scenarios", file))));
            assertThat(result.errors()).as(file).isEmpty(); assertThat(result.valid()).isTrue();
        }
        var api = call(get("/v3/api-docs"), 200);
        assertThat(api.get("paths").get("/dt/scenario-runs").get("post").get("tags").get(0).asString()).isEqualTo("Scenarios");
    }
}
