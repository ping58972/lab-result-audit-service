package dev.ping.lab;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LabServerTest {
    private static final String OPERATOR = "test-only-operator-token-0000000000";
    private static final String REVIEWER = "test-only-reviewer-token-0000000000";
    private static final String BODY =
        "{\"operationId\":\"op-1\",\"sampleId\":\"SIM-A\",\"signal\":42.125,\"qcPassed\":true}";
    private LabServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void setup() throws Exception {
        var service = new ResultService("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        server = new LabServer(service, 0, OPERATOR, REVIEWER);
    }
    @AfterEach void close() { server.close(); }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(java.time.Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
            HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void createReadReleaseAndAuditOverHttp() throws Exception {
        assertEquals(201, request("POST", "/results", OPERATOR, BODY).statusCode());
        assertEquals(200, request("POST", "/results", OPERATOR, BODY).statusCode());
        assertTrue(request("GET", "/results/SIM-A", OPERATOR, null).body().contains("DRAFT"));
        assertEquals(403, request("POST", "/results/SIM-A/release", OPERATOR, null).statusCode());
        assertEquals(200, request("POST", "/results/SIM-A/release", REVIEWER, null).statusCode());
        assertTrue(request("GET", "/results/SIM-A", OPERATOR, null).body().contains("RELEASED"));
        var audit = request("GET", "/results/SIM-A/audit", REVIEWER, null);
        assertEquals(200, audit.statusCode());
        assertTrue(audit.body().contains("CREATED") && audit.body().contains("RELEASED"));
        assertEquals("no-store", audit.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void authenticationAndPermissions() throws Exception {
        assertEquals(401, request("POST", "/results", null, BODY).statusCode());
        assertEquals(401, request("POST", "/results", "wrong-token", BODY).statusCode());
        assertEquals(403, request("POST", "/results", REVIEWER, BODY).statusCode());
        assertEquals(404, request("GET", "/results/SIM-A", OPERATOR, null).statusCode());
        assertEquals(403, request("GET", "/results/SIM-A/audit", OPERATOR, null).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{'operationId':'a'}",
        "{\"operationId\":\"a\",\"operationId\":\"b\"}",
        "{\"operationId\":\"op-1\",\"sampleId\":\"SIM-A\",\"signal\":\"42\",\"qcPassed\":true}",
        "{\"operationId\":\"op-1\",\"sampleId\":\"SIM-A\",\"signal\":42,\"qcPassed\":\"true\"}",
        "{\"operationId\":\"op-1\",\"sampleId\":\"SIM-A\",\"signal\":null,\"qcPassed\":true}"})
    void rejectsMalformedAndAmbiguousJson(String payload) throws Exception {
        assertEquals(400, request("POST", "/results", OPERATOR, payload).statusCode());
    }

    @Test void sizeLimitsAndRoutes() throws Exception {
        assertEquals(413, request("POST", "/results", OPERATOR, " ".repeat(4097)).statusCode());
        assertEquals(405, request("GET", "/results", OPERATOR, null).statusCode());
        assertEquals(404, request("GET", "/missing", OPERATOR, null).statusCode());
        assertEquals(400, request("GET", "/results/SIM-A?debug=true", OPERATOR, null).statusCode());
        assertEquals(400, request("POST", "/results/SIM-A/release", REVIEWER, "{}").statusCode());
    }

    @Test void requiresJsonMediaType() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/results"))
            .header("Authorization", "Bearer " + OPERATOR)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertEquals(415, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void rejectsAmbiguousRoleCredentialsAtStartup() throws Exception {
        var service = new ResultService("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        assertThrows(IllegalArgumentException.class, () -> new LabServer(service, 0, OPERATOR, OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> new LabServer(service, 0, null, REVIEWER));
    }
}
