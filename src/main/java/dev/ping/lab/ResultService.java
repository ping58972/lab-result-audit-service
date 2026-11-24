package dev.ping.lab;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ResultService {
    public enum Role { OPERATOR, REVIEWER }
    public record Sample(String operationId, String sampleId, BigDecimal signal, boolean qcPassed) {}
    public record LabResult(String operationId, String sampleId, BigDecimal signal,
                            boolean qcPassed, String state) {}
    public record Submission(LabResult result, boolean replay) {}
    public record Audit(long id, String sampleId, String event, String actorRole, long timestampMs) {}

    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        public Rejected(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private final String databaseUrl;
    private final Clock clock;
    private final Runnable beforeCommit;

    public ResultService(String databaseUrl) throws SQLException {
        this(databaseUrl, Clock.systemUTC(), () -> {});
    }

    // The injected hook lets tests fail exactly between both writes and commit.
    public ResultService(String databaseUrl, Clock clock, Runnable beforeCommit) throws SQLException {
        this.databaseUrl = Objects.requireNonNull(databaseUrl);
        this.clock = Objects.requireNonNull(clock);
        this.beforeCommit = Objects.requireNonNull(beforeCommit);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lab_result (
                  sample_id VARCHAR(24) PRIMARY KEY,
                  operation_id VARCHAR(40) UNIQUE NOT NULL,
                  signal_value DECIMAL(12,3) NOT NULL CHECK(signal_value BETWEEN 0 AND 1000),
                  qc_passed BOOLEAN NOT NULL,
                  result_state VARCHAR(12) NOT NULL CHECK(result_state IN ('DRAFT','RELEASED'))
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS audit_event (
                  event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                  sample_id VARCHAR(24) NOT NULL REFERENCES lab_result(sample_id),
                  event_name VARCHAR(20) NOT NULL,
                  actor_role VARCHAR(12) NOT NULL,
                  timestamp_ms BIGINT NOT NULL
                )
                """);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(databaseUrl, "sa", "");
    }

    private static void requireRole(Role actual, Role expected) {
        if (actual != expected) throw new Rejected(403, "Role is not authorized for this operation.");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("SIM-[A-Z0-9]{1,16}"))
            throw new Rejected(400, "Expected a synthetic sample identifier.");
    }

    private static Sample validate(Sample sample) {
        if (sample == null || sample.operationId() == null ||
            !sample.operationId().matches("[A-Za-z0-9-]{1,40}"))
            throw new Rejected(400, "Invalid operation identifier.");
        validateId(sample.sampleId());
        if (sample.signal() == null || sample.signal().scale() > 3 ||
            sample.signal().compareTo(BigDecimal.ZERO) < 0 ||
            sample.signal().compareTo(new BigDecimal("1000")) > 0)
            throw new Rejected(400, "Synthetic signal must be 0..1000 with at most three decimal places.");
        return new Sample(sample.operationId(), sample.sampleId(), sample.signal().setScale(3), sample.qcPassed());
    }

    public Submission submit(Role role, Sample input) throws SQLException {
        requireRole(role, Role.OPERATOR);
        Sample sample = validate(input);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                LabResult existing = findByOperation(connection, sample.operationId());
                if (existing != null) {
                    assertReplay(existing, sample);
                    connection.commit();
                    return new Submission(existing, true);
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO lab_result VALUES (?, ?, ?, ?, 'DRAFT')")) {
                    insert.setString(1, sample.sampleId());
                    insert.setString(2, sample.operationId());
                    insert.setBigDecimal(3, sample.signal());
                    insert.setBoolean(4, sample.qcPassed());
                    insert.executeUpdate();
                }
                appendAudit(connection, sample.sampleId(), "CREATED", role);
                beforeCommit.run();
                connection.commit();
                return new Submission(new LabResult(sample.operationId(), sample.sampleId(),
                    sample.signal(), sample.qcPassed(), "DRAFT"), false);
            } catch (SQLException error) {
                connection.rollback();
                if ("23505".equals(error.getSQLState())) {
                    // Concurrent retries converge on the row committed by the winner.
                    LabResult existing = findByOperation(connection, sample.operationId());
                    if (existing != null) {
                        assertReplay(existing, sample);
                        return new Submission(existing, true);
                    }
                    throw new Rejected(409, "Sample identifier already exists.");
                }
                throw error;
            } catch (RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static void assertReplay(LabResult existing, Sample sample) {
        if (!existing.sampleId().equals(sample.sampleId()) || !existing.signal().equals(sample.signal())
                || existing.qcPassed() != sample.qcPassed())
            throw new Rejected(409, "Operation identifier was reused with different content.");
    }

    public LabResult release(Role role, String sampleId) throws SQLException {
        requireRole(role, Role.REVIEWER);
        validateId(sampleId);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                LabResult row;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM lab_result WHERE sample_id=? FOR UPDATE")) {
                    query.setString(1, sampleId);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) throw new Rejected(404, "Sample was not found.");
                        row = read(result);
                    }
                }
                if (!row.qcPassed()) throw new Rejected(409, "Synthetic QC must pass before release.");
                if (row.state().equals("RELEASED")) {
                    connection.commit();
                    return row;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE lab_result SET result_state='RELEASED' WHERE sample_id=?")) {
                    update.setString(1, sampleId);
                    update.executeUpdate();
                }
                appendAudit(connection, sampleId, "RELEASED", role);
                beforeCommit.run();
                connection.commit();
                return new LabResult(row.operationId(), row.sampleId(), row.signal(), true, "RELEASED");
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public LabResult get(Role role, String sampleId) throws SQLException {
        if (role == null) throw new Rejected(403, "Role is required.");
        validateId(sampleId);
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM lab_result WHERE sample_id=?")) {
            query.setString(1, sampleId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new Rejected(404, "Sample was not found.");
                return read(result);
            }
        }
    }

    public List<Audit> audit(Role role, String sampleId) throws SQLException {
        requireRole(role, Role.REVIEWER);
        validateId(sampleId);
        List<Audit> entries = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM audit_event WHERE sample_id=? ORDER BY event_id")) {
            query.setString(1, sampleId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) entries.add(new Audit(result.getLong("event_id"),
                    result.getString("sample_id"), result.getString("event_name"),
                    result.getString("actor_role"), result.getLong("timestamp_ms")));
            }
        }
        return List.copyOf(entries);
    }

    private void appendAudit(Connection connection, String sampleId, String event, Role role) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO audit_event(sample_id,event_name,actor_role,timestamp_ms) VALUES (?,?,?,?)")) {
            insert.setString(1, sampleId);
            insert.setString(2, event);
            insert.setString(3, role.name());
            insert.setLong(4, clock.millis());
            insert.executeUpdate();
        }
    }

    private static LabResult findByOperation(Connection connection, String operation) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM lab_result WHERE operation_id=?")) {
            query.setString(1, operation);
            try (ResultSet result = query.executeQuery()) { return result.next() ? read(result) : null; }
        }
    }

    private static LabResult read(ResultSet row) throws SQLException {
        return new LabResult(row.getString("operation_id"), row.getString("sample_id"),
            row.getBigDecimal("signal_value"), row.getBoolean("qc_passed"), row.getString("result_state"));
    }
}
