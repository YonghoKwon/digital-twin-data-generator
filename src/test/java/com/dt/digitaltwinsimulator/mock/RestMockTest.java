package com.dt.digitaltwinsimulator.mock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.mock.base-path=/stub",
        "simulator.mock.journal-size=3",
        "simulator.mock.max-delay-ms=100",
        "simulator.http-logging.enabled=false"
})
@AutoConfigureMockMvc
class RestMockTest {
    private static final Path DIRECTORY = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired MockRouteStore store;
    @Autowired MockTemplate template;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("simulator.mock.routes-dir", DIRECTORY::toString);
    }

    @BeforeEach
    void reset() throws Exception {
        for (MockRoute route : store.list()) store.delete(route.id());
        mvc.perform(delete("/mock-admin/requests")).andExpect(status().isNoContent());
        try (var files = Files.walk(DIRECTORY)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(DIRECTORY)) Files.delete(file);
            }
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var files = Files.walk(DIRECTORY)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test
    void literalThenFewerCapturesThenRegistrationOrderAndMethod() throws Exception {
        create(route("two", "GET", "/api/{kind}/{id}", "two"));
        create(route("one-first", "GET", "/api/coil/{id}", "one-first"));
        create(route("one-second", "GET", "/api/coil/{other}", "one-second"));
        create(route("literal", "GET", "/api/coil/C1", "literal"));
        create(route("post", "POST", "/api/coil/C1", "post"));
        assertThat(call("GET", "/api/coil/C1").getResponse().getContentAsString()).isEqualTo("literal");
        assertThat(call("GET", "/api/coil/C2").getResponse().getContentAsString()).isEqualTo("one-first");
        assertThat(call("POST", "/api/coil/C1").getResponse().getContentAsString()).isEqualTo("post");
        assertThat(call("GET", "/api/plate/C2").getResponse().getContentAsString()).isEqualTo("two");
    }

    @Test
    void doubleWildcardMatchesNestedPathsAndNoMatchReturnsContract() throws Exception {
        create(route("wild", "GET", "/assets/**", "wild"));
        assertThat(call("GET", "/assets/a/b/c").getResponse().getContentAsString()).isEqualTo("wild");
        MvcResult missing = call("DELETE", "/absent");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(missing).get("error").asString()).isEqualTo("NO_MOCK_ROUTE");
        assertThat(json(missing).get("method").asString()).isEqualTo("DELETE");
        assertThat(json(missing).get("path").asString()).isEqualTo("/absent");
    }

    @Test
    void rendersAndEscapesPathQueryHeaderNestedBodyTimeSequenceAndRandom() throws Exception {
        String source = """
                {"path":"{{path.coilId}}","query":"{{query.x}}","header":"{{header.X-Name}}",
                 "body":"{{body.nested.value}}","number":{{body.count}},"now":"{{now}}","year":"{{now:yyyy}}",
                 "uuid":"{{uuid}}","seq":{{seq}},"random":{{random:Integer:10..20}},
                 "text":"{{random:String:4}}","missing":"{{body.missing}}"}
                """;
        create(route("template", "POST", "/coil/{coilId}", source));
        String escaped = "quote\" slash\\ newline\n한글";
        MvcResult response = mvc.perform(post("/stub/coil/C1").param("x", escaped).header("X-Name", "name\"\\")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("nested", Map.of("value", escaped), "count", 7))))
                .andExpect(status().isOk()).andReturn();
        JsonNode result = json(response);
        assertThat(result.get("path").asString()).isEqualTo("C1");
        assertThat(result.get("query").asString()).isEqualTo(escaped);
        assertThat(result.get("header").asString()).isEqualTo("name\"\\");
        assertThat(result.get("body").asString()).isEqualTo(escaped);
        assertThat(result.get("number").intValue()).isEqualTo(7);
        assertThat(result.get("now").asString()).matches("\\d{17}");
        assertThat(result.get("year").asString()).matches("\\d{4}");
        assertThat(result.get("uuid").asString()).matches("[0-9a-f-]{36}");
        assertThat(result.get("seq").intValue()).isEqualTo(1);
        assertThat(result.get("random").intValue()).isBetween(10, 20);
        assertThat(result.get("text").asString()).hasSize(4);
        assertThat(result.get("missing").asString()).isEmpty();
        assertThat(json(mvc.perform(post("/stub/coil/C2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":7}")).andReturn()).get("seq").intValue()).isEqualTo(2);
    }

    @Test
    void textTemplatesDoNotJsonEscapeAndMissingBodyIsAllowed() throws Exception {
        create(route("text", "GET", "/text/{id}", "Hi {{path.id}}: {{query.x}} {{body.x}}"));
        MvcResult response = mvc.perform(get("/stub/text/C1").param("x", "a\"b")).andReturn();
        assertThat(response.getResponse().getContentAsString()).isEqualTo("Hi C1: a\"b ");
        assertThat(response.getResponse().getContentType()).startsWith("text/plain");
    }

    @Test
    void responsesHoldLastOrCycleAndInheritRouteFields() throws Exception {
        Map<String, Object> route = route("states", "GET", "/states", "base");
        route.put("headers", Map.of("X-Shared", "yes"));
        route.put("responses", List.of(Map.of("status", 202, "body", "pending"), Map.of("body", "ready")));
        create(route);
        assertThat(call("GET", "/states").getResponse().getStatus()).isEqualTo(202);
        assertThat(call("GET", "/states").getResponse().getContentAsString()).isEqualTo("ready");
        assertThat(call("GET", "/states").getResponse().getContentAsString()).isEqualTo("ready");
        route.put("cycle", true);
        update(route);
        assertThat(call("GET", "/states").getResponse().getStatus()).isEqualTo(202);
        assertThat(call("GET", "/states").getResponse().getHeader("X-Shared")).isEqualTo("yes");
        assertThat(call("GET", "/states").getResponse().getContentAsString()).isEqualTo("pending");
    }

    @Test
    void faultRatesZeroAndOneAreDeterministic() throws Exception {
        Map<String, Object> route = route("fault", "GET", "/fault", "ok");
        route.put("fault", Map.of("errorRate", 0));
        create(route);
        assertThat(call("GET", "/fault").getResponse().getStatus()).isEqualTo(200);
        route.put("fault", Map.of("errorRate", 1, "errorStatus", 503, "errorBody", "failed {{seq}}"));
        update(route);
        MvcResult response = call("GET", "/fault");
        assertThat(response.getResponse().getStatus()).isEqualTo(503);
        assertThat(response.getResponse().getContentAsString()).isEqualTo("failed 1");
        route.put("fault", Map.of("errorRate", 1));
        update(route);
        assertThat(call("GET", "/fault").getResponse().getStatus()).isEqualTo(500);
    }

    @Test
    void fixedAndRandomDelayAreAppliedAndRecorded() throws Exception {
        Map<String, Object> route = route("delay", "GET", "/delay", "ok");
        route.put("delayMs", 15);
        create(route);
        long started = System.nanoTime();
        call("GET", "/delay");
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(15);
        assertThat(requests().get(0).get("delayMs").intValue()).isEqualTo(15);
        route.remove("delayMs");
        route.put("delayMinMs", 5);
        route.put("delayMaxMs", 10);
        update(route);
        call("GET", "/delay");
        assertThat(requests().get(0).get("delayMs").intValue()).isBetween(5, 10);
    }

    @Test
    void journalBoundsMasksAndTruncatesUtf8BodyAndCanBeCleared() throws Exception {
        create(route("journal", "POST", "/journal", "ok"));
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/stub/journal?access_token=query-secret&i=" + i)
                    .header("Authorization", "Bearer private").header("Cookie", "session=private")
                    .header("X-Api-Key", "private").header("X-Name", "visible")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("password", "private with spaces", "access_token", "private", "text", "한".repeat(2000)))));
        }
        JsonNode entries = requests();
        assertThat(entries.size()).isEqualTo(3);
        JsonNode latest = entries.get(0);
        assertThat(latest.get("routeId").asString()).isEqualTo("journal");
        assertThat(latest.get("query").asString()).contains("i=3").doesNotContain("query-secret");
        assertThat(latest.get("headers").get("Authorization").asString()).isEqualTo("****");
        assertThat(latest.get("headers").get("Cookie").asString()).isEqualTo("****");
        assertThat(latest.get("headers").get("X-Api-Key").asString()).isEqualTo("****");
        assertThat(latest.get("headers").get("X-Name").asString()).isEqualTo("visible");
        assertThat(latest.get("body").asString()).doesNotContain("private", "\uFFFD");
        assertThat(latest.get("body").asString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(json(mvc.perform(get("/mock-admin/requests?limit=1")).andReturn()).size()).isEqualTo(1);
        mvc.perform(get("/mock-admin/requests?limit=-1")).andExpect(status().isBadRequest());
        mvc.perform(delete("/mock-admin/requests")).andExpect(status().isNoContent());
        assertThat(requests().size()).isZero();
    }

    @Test
    void bodyFilesAreServedButAbsoluteAndTraversalPathsAreRejected() throws Exception {
        Files.createDirectories(DIRECTORY.resolve("bodies"));
        Files.writeString(DIRECTORY.resolve("bodies/coil.txt"), "{\"coil\":\"{{path.id}}\"}");
        Map<String, Object> route = route("file", "GET", "/file/{id}", "unused");
        route.remove("body");
        route.put("bodyFile", "bodies/coil.txt");
        create(route);
        assertThat(json(call("GET", "/file/C1")).get("coil").asString()).isEqualTo("C1");
        for (String path : List.of("../outside.txt", DIRECTORY.resolve("bodies/coil.txt").toString(), "missing.txt")) {
            route.put("bodyFile", path);
            mvc.perform(put("/mock-admin/routes/file").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(route))).andExpect(status().isBadRequest());
        }
        Path outside = Files.createTempFile(DIRECTORY.getParent(), "dts-s3-outside-", ".txt");
        try {
            Files.writeString(outside, "must not be served");
            route.put("bodyFile", "../" + outside.getFileName());
            mvc.perform(put("/mock-admin/routes/file").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(route))).andExpect(status().isBadRequest());
        } finally {
            Files.delete(outside);
        }
        Files.delete(DIRECTORY.resolve("bodies/coil.txt"));
        assertThat(call("GET", "/file/C1").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void invalidAdminInputsReturn400WithoutChangingExistingRoute() throws Exception {
        create(route("valid", "GET", "/valid", "ok"));
        List<Map<String, Object>> invalid = List.of(
                Map.of("status", 99), Map.of("status", 600), Map.of("delayMs", -1), Map.of("delayMs", 101),
                Map.of("delayMinMs", 10, "delayMaxMs", 5), Map.of("delayMinMs", 5),
                Map.of("delayMs", 1, "delayMinMs", 1, "delayMaxMs", 2),
                Map.of("fault", Map.of("errorRate", 1.1)), Map.of("fault", Map.of("errorStatus", 200)),
                Map.of("responses", List.of(Map.of("delayMs", 101))),
                Map.of("headers", Map.of("X-Bad", "line\r\ninjected")),
                Map.of("body", "{{now:invalid[}}"), Map.of("body", "{{random:Unknown:1}}"),
                Map.of("body", "{{random:Integer:abc}}"), Map.of("path", "relative"));
        for (Map<String, Object> fields : invalid) {
            Map<String, Object> route = route("valid", "GET", "/valid", "ok");
            route.putAll(fields);
            mvc.perform(put("/mock-admin/routes/valid").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(route))).andExpect(status().isBadRequest());
        }
        assertThat(call("GET", "/valid").getResponse().getContentAsString()).isEqualTo("ok");
        for (String invalidJson : List.of("{\"headers\":{\"X-Null\":null}}", "{\"responses\":[null]}", "{\"body\":{}}")) {
            var route = mapper.createObjectNode();
            route.put("id", "valid").put("method", "GET").put("path", "/valid");
            mapper.readTree(invalidJson).properties().forEach(e -> route.set(e.getKey(), e.getValue()));
            mvc.perform(put("/mock-admin/routes/valid").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(route))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void crudToggleAndEnabledOnlyAffectMatching() throws Exception {
        create(route("crud", "GET", "/crud", "first"));
        assertThat(json(mvc.perform(get("/mock-admin/routes/crud")).andReturn()).get("id").asString()).isEqualTo("crud");
        assertThat(json(mvc.perform(get("/mock-admin/routes")).andReturn()).size()).isEqualTo(1);
        mvc.perform(post("/mock-admin/routes/crud/toggle")).andExpect(status().isOk());
        assertThat(call("GET", "/crud").getResponse().getStatus()).isEqualTo(404);
        mvc.perform(patch("/mock-admin/routes/crud/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(call("GET", "/crud").getResponse().getStatus()).isEqualTo(200);
        update(route("crud", "GET", "/crud", "second"));
        assertThat(call("GET", "/crud").getResponse().getContentAsString()).isEqualTo("second");
        mvc.perform(delete("/mock-admin/routes/crud")).andExpect(status().isNoContent());
        mvc.perform(get("/mock-admin/routes/crud")).andExpect(status().isNotFound());
    }

    @Test
    void dtcoreImportIsCaseInsensitiveStripsEnumPrefixAndSupportsOverwrite() throws Exception {
        String rows = """
                [{"Name":"GetCoil","ApiMethod":"EApiMethod::Get","ApiUrl":"/coils/{id}","ApiResource":"Coil","ApiAction":"Get"},
                 {"name":"SetCoil","apimethod":"post","apiurl":"/coils"}]
                """;
        JsonNode first = json(mvc.perform(post("/mock-admin/import/dtcore-api-table").contentType(MediaType.APPLICATION_JSON).content(rows))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.get("imported").size()).isEqualTo(2);
        assertThat(call("GET", "/coils/C1").getResponse().getContentAsString()).isEqualTo("{}");
        update(route("dtcore-GetCoil", "GET", "/coils/{id}", "custom"));
        JsonNode skipped = json(mvc.perform(post("/mock-admin/import/dtcore-api-table").contentType(MediaType.APPLICATION_JSON).content(rows)).andReturn());
        assertThat(skipped.get("skipped").size()).isEqualTo(2);
        assertThat(call("GET", "/coils/C1").getResponse().getContentAsString()).isEqualTo("custom");
        JsonNode overwritten = json(mvc.perform(post("/mock-admin/import/dtcore-api-table?overwrite=true")
                .contentType(MediaType.APPLICATION_JSON).content(rows)).andReturn());
        assertThat(overwritten.get("imported").size()).isEqualTo(2);
        assertThat(call("GET", "/coils/C1").getResponse().getContentAsString()).isEqualTo("{}");
        mvc.perform(post("/mock-admin/import/dtcore-api-table").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loadsSingleAndArrayFilesAndFailedReloadKeepsPreviousRoutes() throws Exception {
        Files.writeString(DIRECTORY.resolve("a.json"), mapper.writeValueAsString(route("single", "GET", "/single", "single")));
        Files.writeString(DIRECTORY.resolve("b.json"), mapper.writeValueAsString(List.of(route("array", "GET", "/array", "array"))));
        mvc.perform(post("/mock-admin/routes/reload")).andExpect(status().isOk());
        assertThat(store.list()).hasSize(2);
        assertThat(call("GET", "/single").getResponse().getContentAsString()).isEqualTo("single");
        MockProperties startupProperties = new MockProperties();
        startupProperties.setRoutesDir(DIRECTORY.toString());
        MockRouteStore startupStore = new MockRouteStore(startupProperties, mapper, template);
        startupStore.initialize();
        assertThat(startupStore.list()).hasSize(2);
        Files.writeString(DIRECTORY.resolve("broken.json"), "not json");
        mvc.perform(post("/mock-admin/routes/reload")).andExpect(status().isBadRequest());
        assertThat(store.list()).hasSize(2);
        startupProperties.setRoutesDir(DIRECTORY.resolve("absent").toString());
        startupStore.initialize();
        assertThat(startupStore.list()).isEmpty();
    }

    @Test
    void concurrentCallsHaveUniqueSequencesAndConsumeEachResponseExactlyOnce() throws Exception {
        Map<String, Object> route = route("concurrent", "GET", "/concurrent", "{{seq}}");
        route.put("responses", List.of(Map.of("status", 201), Map.of("status", 202)));
        create(route);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 24; i++) calls.add(() -> call("GET", "/concurrent"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<MvcResult> results = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) results.add(future.get());
            assertThat(results.stream().map(r -> Integer.parseInt(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))).toList())
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, 24).boxed().toList());
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 202).count()).isEqualTo(23);
        }
    }

    @Test
    void realHttpSupportsAllMethodsAtConfiguredBaseAndPreservesHeadSemantics() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "PURGE")) {
                create(route("method-" + method, method, "/method", "from-" + method));
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stub/method"))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(method).isEqualTo(200);
                assertThat(response.body()).as(method).isEqualTo(method.equals("HEAD") ? "" : "from-" + method);
            }
            HttpResponse<String> outside = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mock-admin/routes"))
                    .method("TRACE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(outside.statusCode()).isEqualTo(405);
        }
    }

    @Test
    void swaggerShowsAdminTagAndHidesServingPaths() throws Exception {
        JsonNode docs = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        assertThat(docs.get("paths").has("/mock-admin/routes")).isTrue();
        assertThat(docs.get("paths").get("/mock-admin/routes").get("get").get("tags").get(0).asString()).isEqualTo("REST Mock Admin");
        assertThat(docs.get("paths").properties()).noneMatch(e -> e.getKey().startsWith("/stub"));
    }

    private Map<String, Object> route(String id, String method, String path, String body) {
        return new HashMap<>(Map.of("id", id, "method", method, "path", path, "body", body));
    }

    private void create(Map<String, Object> route) throws Exception {
        mvc.perform(post("/mock-admin/routes").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(route)))
                .andExpect(status().isCreated());
    }

    private void update(Map<String, Object> route) throws Exception {
        mvc.perform(put("/mock-admin/routes/" + route.get("id")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(route)))
                .andExpect(status().isOk());
    }

    private MvcResult call(String method, String path) throws Exception {
        return mvc.perform(request(method, URI.create("/stub" + path))).andReturn();
    }

    private JsonNode requests() throws Exception {
        return json(mvc.perform(get("/mock-admin/requests")).andExpect(status().isOk()).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("dts-s3-routes-");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
