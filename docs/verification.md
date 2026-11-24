# Requirements traceability and verification plan

## Requirements and automated evidence

| ID | Acceptance criterion | Test evidence |
| --- | --- | --- |
| LAB-01 | Create a DRAFT and a CREATED audit event together. | createsDraftAndAudits |
| LAB-02 | Identical retries create one logical result. | replayIsIdempotentAndRejectsChangedContent |
| LAB-03 | Changed operation content or reused sample ID returns conflict. | replayIsIdempotentAndRejectsChangedContent |
| LAB-04 | Only reviewers can release, and synthetic QC must pass. | releaseRequiresQcAndReviewer; unauthorizedDomainCallsAreRejected |
| LAB-05 | Result and audit writes roll back together. | injectedFailureRollsBackResultAndAudit |
| LAB-06 | Failed release preserves DRAFT and original audit. | releaseFailureRollsBackStatusAndAudit |
| LAB-07 | Eight concurrent retries converge on one result and one event. | concurrentRetriesCreateOneResultAndEvent |
| LAB-08 | Concurrent releases create one RELEASED event. | concurrentReleaseCreatesOneAuditEvent |
| LAB-09 | Database content survives reopening and process restart. | fileDatabaseSurvivesReopen; tools/smoke.py |
| LAB-10 | Validate synthetic ID, numeric bounds, JSON structure, and request size. | Parameterized domain checks; rejectsMalformedAndAmbiguousJson; sizeLimitsAndRoutes |
| LAB-11 | Require distinct role credentials and JSON media type. | rejectsAmbiguousRoleCredentialsAtStartup; requiresJsonMediaType |
| LAB-12 | Authenticate requests and restrict audit access. | authenticationAndPermissions; createReadReleaseAndAuditOverHttp |

The test names refer to [ResultServiceTest](../src/test/java/dev/ping/lab/ResultServiceTest.java) and [LabServerTest](../src/test/java/dev/ping/lab/LabServerTest.java). Maven Surefire exports executed outcomes to XML.

## Risk priorities

High-priority checks cover duplicate result creation, unauthorized release, result/audit inconsistency, and release races. Medium-priority checks cover malformed input, persistence, route errors, and predictable operator-facing status codes.

The synthetic QC flag and synthetic signal have no clinical interpretation. Test boundaries are defined by the supplied service contract.

## Manual acceptance procedure

1. Build the project and launch the local API with generated operator and reviewer credentials.
2. Submit one result with operation demo-1 and sample SIM-A. Expect HTTP 201 and DRAFT.
3. Repeat the same payload. Expect HTTP 200 and replay=true.
4. Reuse demo-1 with another sample ID. Expect HTTP 409.
5. Try release with the operator credential. Expect HTTP 403.
6. Release with the reviewer credential. Expect HTTP 200 and RELEASED.
7. Release again and inspect the audit route. Expect exactly CREATED and RELEASED events.
8. Restart the application with the same database path and credentials. Expect the result to remain RELEASED.
9. Submit a different sample with qcPassed=false and attempt release. Expect HTTP 409 and DRAFT.
10. Send malformed or oversized JSON. Expect a bounded error response.

Record the implementation commit, test inputs, observed status/body, and expected-versus-actual comparison for each manual case.

## Troubleshooting and regression procedure

A bind error indicates port 8091 is occupied. A startup credential error requires two distinct environment tokens of at least 24 characters. A 401 indicates missing or incorrect credentials. A 403 indicates the credential's role cannot perform the operation. A 409 indicates a duplicate-content conflict or a failed synthetic QC gate.

For an observed defect, add the smallest deterministic JUnit reproduction, implement the change, run focused tests, then run the full suite and packaged smoke check.

## Backlog

- Add schema migration scripts when evolving the database format.
- Add an external identity adapter with token expiry and rotation.
- Add release cancellation and result amendment requirements.
- Expand fault injection to connection failures and database timeout behavior.
- Add reviewable risk decisions and execution evidence for each future release.
