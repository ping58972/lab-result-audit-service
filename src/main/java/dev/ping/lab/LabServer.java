package dev.ping.lab;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LabServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ResultService service;
    private final String operatorToken;
    private final String reviewerToken;
    private final Gson gson = new Gson();

    public LabServer(ResultService service, int port, String operatorToken, String reviewerToken) throws IOException {
        if (operatorToken == null || reviewerToken == null || operatorToken.length() < 24 ||
                reviewerToken.length() < 24 || operatorToken.equals(reviewerToken))
            throw new IllegalArgumentException("Configure two distinct tokens of at least 24 characters.");
        this.service = service;
        this.operatorToken = operatorToken;
        this.reviewerToken = reviewerToken;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    private ResultService.Role role(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.size() != 1) throw new ResultService.Rejected(401, "Authorization required.");
        String token = headers.get(0);
        if (same(token, "Bearer " + operatorToken)) return ResultService.Role.OPERATOR;
        if (same(token, "Bearer " + reviewerToken)) return ResultService.Role.REVIEWER;
        throw new ResultService.Rejected(401, "Authorization required.");
    }

    private static boolean same(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var role = role(exchange);
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestURI().getRawQuery() != null)
                throw new ResultService.Rejected(400, "Query parameters are unsupported.");
            if (path.equals("/results")) {
                if (!exchange.getRequestMethod().equals("POST"))
                    throw new ResultService.Rejected(405, "Use POST.");
                JsonObject body = body(exchange);
                if (!body.keySet().equals(Set.of("operationId", "sampleId", "signal", "qcPassed")))
                    throw new ResultService.Rejected(400, "Expected four result fields.");
                if (!body.get("operationId").isJsonPrimitive() || !body.get("operationId").getAsJsonPrimitive().isString()
                    || !body.get("sampleId").isJsonPrimitive() || !body.get("sampleId").getAsJsonPrimitive().isString()
                    || !body.get("signal").isJsonPrimitive() || !body.get("signal").getAsJsonPrimitive().isNumber()
                    || !body.get("qcPassed").isJsonPrimitive() || !body.get("qcPassed").getAsJsonPrimitive().isBoolean())
                    throw new ResultService.Rejected(400, "Incorrect field types.");
                var submission = service.submit(role, new ResultService.Sample(
                    body.get("operationId").getAsString(), body.get("sampleId").getAsString(),
                    new BigDecimal(body.get("signal").getAsString()), body.get("qcPassed").getAsBoolean()));
                send(exchange, submission.replay() ? 200 : 201, submission);
            } else {
                String[] parts = path.split("/", -1);
                if (parts.length < 3 || !parts[1].equals("results"))
                    throw new ResultService.Rejected(404, "Route was not found.");
                if (parts.length == 3 && exchange.getRequestMethod().equals("GET"))
                    send(exchange, 200, service.get(role, parts[2]));
                else if (parts.length == 4 && parts[3].equals("release") && exchange.getRequestMethod().equals("POST")) {
                    if (exchange.getRequestBody().read() != -1)
                        throw new ResultService.Rejected(400, "Release requires an empty body.");
                    send(exchange, 200, service.release(role, parts[2]));
                } else if (parts.length == 4 && parts[3].equals("audit") && exchange.getRequestMethod().equals("GET"))
                    send(exchange, 200, service.audit(role, parts[2]));
                else throw new ResultService.Rejected(405, "Unsupported operation.");
            }
        } catch (ResultService.Rejected error) {
            send(exchange, error.status(), Map.of("error", error.getMessage()));
        } catch (SQLException error) {
            send(exchange, 503, Map.of("error", "Storage operation failed."));
        } catch (RuntimeException error) {
            send(exchange, 500, Map.of("error", "Internal operation failed."));
        } finally {
            exchange.close();
        }
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new ResultService.Rejected(415, "Use application/json.");
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) throw new ResultService.Rejected(413, "Request body is too large.");
        // Parse top-level fields once to reject duplicate names explicitly.
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            JsonObject object = new JsonObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (object.has(name)) throw new ResultService.Rejected(400, "Duplicate JSON field.");
                object.add(name, JsonParser.parseReader(reader));
            }
            reader.endObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
                throw new ResultService.Rejected(400, "Trailing JSON content.");
            return object;
        } catch (ResultService.Rejected error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ResultService.Rejected(400, "Malformed JSON.");
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
