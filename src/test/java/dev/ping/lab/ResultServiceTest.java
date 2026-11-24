package dev.ping.lab;

import static org.junit.jupiter.api.Assertions.*;
import static dev.ping.lab.ResultService.Role.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ResultServiceTest {
    private String url;
    private ResultService service;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
        service = new ResultService(url);
    }

    private static ResultService.Sample sample(String op, String id, boolean qc) {
        return new ResultService.Sample(op, id, new BigDecimal("42.125"), qc);
    }

    @Test void createsDraftAndAudits() throws Exception {
        var created = service.submit(OPERATOR, sample("op-1", "SIM-A", true));
        assertFalse(created.replay());
        assertEquals("DRAFT", created.result().state());
        var events = service.audit(REVIEWER, "SIM-A");
        assertEquals(1, events.size());
        assertEquals("CREATED", events.get(0).event());
        assertEquals("OPERATOR", events.get(0).actorRole());
    }

    @Test void releaseRequiresQcAndReviewer() throws Exception {
        service.submit(OPERATOR, sample("op-1", "SIM-A", true));
        assertEquals(403, assertThrows(ResultService.Rejected.class,
            () -> service.release(OPERATOR, "SIM-A")).status());
        assertEquals("RELEASED", service.release(REVIEWER, "SIM-A").state());
        service.release(REVIEWER, "SIM-A");
        assertEquals(2, service.audit(REVIEWER, "SIM-A").size());
        service.submit(OPERATOR, sample("op-2", "SIM-B", false));
        assertEquals(409, assertThrows(ResultService.Rejected.class,
            () -> service.release(REVIEWER, "SIM-B")).status());
        assertEquals("DRAFT", service.get(OPERATOR, "SIM-B").state());
        assertEquals(1, service.audit(REVIEWER, "SIM-B").size());
    }

    @Test void replayIsIdempotentAndRejectsChangedContent() throws Exception {
        var input = sample("op-1", "SIM-A", true);
        service.submit(OPERATOR, input);
        assertTrue(service.submit(OPERATOR, input).replay());
        assertEquals(1, service.audit(REVIEWER, "SIM-A").size());
        assertEquals(409, assertThrows(ResultService.Rejected.class,
            () -> service.submit(OPERATOR, sample("op-1", "SIM-B", true))).status());
        assertEquals(409, assertThrows(ResultService.Rejected.class,
            () -> service.submit(OPERATOR, sample("op-2", "SIM-A", true))).status());
    }

    @Test void unauthorizedDomainCallsAreRejected() {
        assertEquals(403, assertThrows(ResultService.Rejected.class,
            () -> service.submit(REVIEWER, sample("op-1", "SIM-A", true))).status());
        assertEquals(403, assertThrows(ResultService.Rejected.class,
            () -> service.get(null, "SIM-A")).status());
        assertEquals(403, assertThrows(ResultService.Rejected.class,
            () -> service.audit(OPERATOR, "SIM-A")).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.001", "1000.001", "0.0001", "123456789012345678901234567890"})
    void rejectsSignalsOutsideSyntheticContract(String signal) {
        var input = new ResultService.Sample("op-1", "SIM-A", new BigDecimal(signal), true);
        assertEquals(400, assertThrows(ResultService.Rejected.class,
            () -> service.submit(OPERATOR, input)).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1000", "0.001", "999.999"})
    void acceptsSignalBoundaries(String signal) throws Exception {
        var input = new ResultService.Sample("op-1", "SIM-A", new BigDecimal(signal), true);
        assertEquals(new BigDecimal(signal).setScale(3), service.submit(OPERATOR, input).result().signal());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "John Smith", "SIM-", "SIM-a", "SIM-A' OR '1'='1"})
    void invalidIdentifiersDoNotChangeStorage(String id) throws Exception {
        assertThrows(ResultService.Rejected.class, () -> service.submit(OPERATOR, sample("op-1", id, true)));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT COUNT(*) FROM lab_result")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test void injectedFailureRollsBackResultAndAudit() throws Exception {
        var failing = new ResultService(url, Clock.systemUTC(), () -> { throw new IllegalStateException("test"); });
        assertThrows(IllegalStateException.class, () -> failing.submit(OPERATOR, sample("op-1", "SIM-A", true)));
        assertEquals(404, assertThrows(ResultService.Rejected.class, () -> service.get(OPERATOR, "SIM-A")).status());
        assertTrue(service.audit(REVIEWER, "SIM-A").isEmpty());
        assertFalse(service.submit(OPERATOR, sample("op-1", "SIM-A", true)).replay());
    }

    @Test void releaseFailureRollsBackStatusAndAudit() throws Exception {
        service.submit(OPERATOR, sample("op-1", "SIM-A", true));
        var failing = new ResultService(url, Clock.systemUTC(), () -> { throw new IllegalStateException("test"); });
        assertThrows(IllegalStateException.class, () -> failing.release(REVIEWER, "SIM-A"));
        assertEquals("DRAFT", service.get(OPERATOR, "SIM-A").state());
        assertEquals(1, service.audit(REVIEWER, "SIM-A").size());
    }

    @Test void concurrentRetriesCreateOneResultAndEvent() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var go = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return service.submit(OPERATOR, sample("same-op", "SIM-A", true)).replay();
            });
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var job : jobs) futures.add(pool.submit(job));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int replays = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) replays++;
            assertEquals(7, replays);
            assertEquals(1, service.audit(REVIEWER, "SIM-A").size());
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    @Test void concurrentReleaseCreatesOneAuditEvent() throws Exception {
        service.submit(OPERATOR, sample("op-1", "SIM-A", true));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> service.release(REVIEWER, "SIM-A").state());
            for (var future : pool.invokeAll(tasks)) assertEquals("RELEASED", future.get());
            assertEquals(2, service.audit(REVIEWER, "SIM-A").size());
        } finally { pool.shutdownNow(); }
    }

    @Test void fileDatabaseSurvivesReopen(@TempDir Path directory) throws Exception {
        String fileUrl = "jdbc:h2:file:" + directory.resolve("lab");
        var initial = new ResultService(fileUrl, Clock.fixed(Instant.ofEpochMilli(123456), ZoneOffset.UTC), () -> {});
        initial.submit(OPERATOR, sample("op-1", "SIM-A", true));
        var reopened = new ResultService(fileUrl);
        assertEquals("DRAFT", reopened.get(OPERATOR, "SIM-A").state());
        assertEquals(123456, reopened.audit(REVIEWER, "SIM-A").get(0).timestampMs());
    }
}
