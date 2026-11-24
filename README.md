# Lab Result Audit Service

A Java 17 and SQL verification project for a synthetic laboratory result lifecycle. A local HTTP API accepts a draft result, records an audit event in the same database transaction, and allows a reviewer to release it after a synthetic QC flag passes.

The service makes repeated requests, concurrent clients, authorization boundaries, and transaction failures reproducible through automated tests.

## Role alignment

The Werfen Software Test Engineer I role requests software implementation, test frameworks, databases, security principles, design patterns, and documentation. This project pairs a small service with domain, persistence, concurrency, HTTP, and packaged-application tests. [Official posting](https://careers-werfen.icims.com/jobs/11400/software-test-engineer-i/job)

| Area | Implementation and verification |
| --- | --- |
| Multiple programming languages | Java application and JUnit tests, SQL persistence, Python packaged-service smoke test. |
| Architecture | HTTP adapter, service/domain layer, typed records, injected clock, and a commit fault-injection hook. |
| Database technologies | H2/JDBC, prepared statements, unique keys, foreign keys, transactions, and row locks. |
| Automated verification | JUnit Jupiter parameterized tests, real loopback HTTP, concurrency tests, database reopen, and Surefire XML reports. |
| Security practices | Separate operator/reviewer bearer tokens, role checks at service methods, bounded JSON bodies, duplicate-field rejection, and input validation. |
| Quality and documentation | Requirements-to-test matrix, manual procedures, fault-injection evidence, and Docker reproduction. |

## Build and test

Requires JDK 17+, Maven, and Python 3.10+ for the packaged smoke test.

~~~sh
git clone https://github.com/ping58972/lab-result-audit-service.git
cd lab-result-audit-service
mvn test package
python3 tools/smoke.py
~~~

The Python smoke test starts the actual shaded JAR, exercises HTTP authorization, creates and replays a result, releases it, inspects its audit events, restarts the application, and verifies persistence. It uses temporary files and generated test credentials. Port 8091 must be available.

### Linux reproduction

~~~sh
docker build -t lab-result-audit-service .
docker run --rm lab-result-audit-service
~~~

The Docker runner executes JUnit and the packaged-application smoke test inside Linux.

## Run the API

Generate separate local credentials in a terminal and start the service.

~~~sh
export LAB_OPERATOR_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export LAB_REVIEWER_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
java -jar target/lab-result-audit-service-1.0.0.jar ./demo-results
~~~

Keep those environment variables available in the shell used for the requests.

~~~sh
curl --fail-with-body http://127.0.0.1:8091/results \
  -H "Authorization: Bearer $LAB_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"operationId":"demo-1","sampleId":"SIM-A","signal":42.125,"qcPassed":true}'

curl --fail-with-body -X POST http://127.0.0.1:8091/results/SIM-A/release \
  -H "Authorization: Bearer $LAB_REVIEWER_TOKEN"

curl --fail-with-body http://127.0.0.1:8091/results/SIM-A/audit \
  -H "Authorization: Bearer $LAB_REVIEWER_TOKEN"
~~~

The first submission returns 201. Replaying identical content with the same operation ID returns 200 and creates one result and one CREATED event overall. Reusing an operation ID with different content returns 409.

| Endpoint | Role | Behavior |
| --- | --- | --- |
| POST /results | Operator | Validate and create DRAFT, or replay an identical operation. |
| GET /results/{sample} | Either | Read one synthetic result. |
| POST /results/{sample}/release | Reviewer | Require passed synthetic QC and release once. |
| GET /results/{sample}/audit | Reviewer | Read ordered audit events. |

Sample IDs follow `SIM-[A-Z0-9]{1,16}`. Operation IDs allow 1-40 letters, digits, or hyphens. Synthetic signals range from 0 through 1000 with at most three decimal places. These bounds are application test fixtures.

## Transaction and concurrency design

A submission inserts the result and CREATED audit event inside one transaction. A pre-commit hook lets tests raise an exception after both writes, then check that both roll back.

A unique operation ID serializes competing inserts. When eight clients submit the same operation concurrently, one creates the row and seven observe replays. A repeated operation must match the original sample, normalized signal, and QC flag.

Release locks the sample row with `SELECT ... FOR UPDATE`. Competing reviewers see one committed RELEASED transition and one corresponding audit event. Releasing an already released sample returns its current state.

## Validation snapshot

Validated on 2026-11-24 on macOS ARM64 and Linux ARM64 Docker using Java 17.

- 36 JUnit test executions passed with zero failures, errors, or skips.
- Domain tests cover boundary values, identifier validation, role checks, atomic rollback, concurrent retries, concurrent release, and persistence.
- HTTP tests exercise real sockets, authentication, response status, malformed JSON, duplicate fields, payload limits, route handling, and media types.
- The shaded-JAR smoke test passed, including database persistence across a process restart.

See [verification procedures and traceability](docs/verification.md). JUnit XML files are generated under `target/surefire-reports/`.

## Engineering scope

This is a local educational service with synthetic sample IDs, signals, and QC flags. The loopback HTTP listener, local environment credentials, and H2 database support reproducible testing. A deployed clinical application would require its own identity system, transport protections, retention controls, and validation process. Audit events are transactionally coupled to result changes and readable through the reviewer API.

## Source guide

- [Domain and transactional persistence](src/main/java/dev/ping/lab/ResultService.java)
- [HTTP adapter and JSON checks](src/main/java/dev/ping/lab/LabServer.java)
- [JUnit domain and HTTP tests](src/test/java/dev/ping/lab/)
- [Packaged-service smoke test](tools/smoke.py)

### Resume bullet

Built and verified a Java/SQL lab-result service with role-based access, idempotent requests, transactional audit events, concurrency tests, and automated persistence checks across application restarts.

Created by [Ping](https://github.com/ping58972).
