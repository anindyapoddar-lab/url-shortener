# Spring Boot URL Shortener and SDLC Workflow

A Spring Boot 3.5.16 and Java 21 prototype with Spring MVC, embedded Tomcat,
and a stateful workflow runner. Install JDK 21 and Maven 3.6.3 or later.
Run the commands from this directory. Maven needs access to Maven Central
on the first build. Git is optional unless you want the commit history.

## Start the service

```text
mvn clean package
java -jar target/shortener.jar
```

In a second terminal, use these commands. On Windows, use `curl.exe`.

```text
curl -i -H "Content-Type: text/plain" --data-binary "https://example.com/docs" http://127.0.0.1:8080/links
curl -i http://127.0.0.1:8080/r/REPLACE_WITH_CODE
curl -i http://127.0.0.1:8080/links/REPLACE_WITH_CODE/analytics
curl -i -H "Content-Type: text/plain" --data-binary "https://example.com/docs" "http://127.0.0.1:8080/links?ttl=120"
```

| API | Successful response |
|---|---|
| GET /health | 200 with a text health response |
| POST /links | 201 with the code and a relative Location header. The request body is the destination URL as plain text |
| GET /r/{code} | 302 with the destination in Location. Counts the redirect |
| GET /links/{code}/analytics | 200 with the aggregate click count as plain text |

Missing codes return 404. Expired redirects return 410. Invalid input returns
400, oversized input returns 413, and unsupported methods return 405.
TTL is optional and accepts 1 to 2,592,000 seconds.

## Run the assessment scenarios

Use fresh run directories to replay a scenario from the start. Each command
prints timestamped events and the next command reloads the persisted state.

### Greenfield with a transient release failure

```text
java tools/Workflow.java greenfield runs/green run
java tools/Workflow.java greenfield runs/green inject retry
java tools/Workflow.java greenfield runs/green approve reviewer
java tools/Workflow.java greenfield runs/green run
java tools/Workflow.java greenfield runs/green metrics
```

Review `revision-1/testing.txt` before approving. The build stage creates
`runs/green/revision-1/source/shortener.jar`, which you can also run with
`java -jar runs/green/revision-1/source/shortener.jar --server.port=8080 --shortener.data-dir=data`. The test and documentation
stages run concurrently. The release pauses at the join, retries once
after approval, and publishes `candidate.txt`.

### Brownfield analytics and release denial

```text
java tools/Workflow.java brownfield runs/brown run
java tools/Workflow.java brownfield runs/brown deny reviewer
java tools/Workflow.java brownfield runs/brown run
```

Inspect `revision-1/impact.txt` and `revision-1/testing.txt`. The regression
test loads a legacy two-column record, follows its original destination,
and verifies that the analytics count increases from zero to one. The denial
leaves the release receipt absent.

### Ambiguous expiration, revision, and rollback

```text
java tools/Workflow.java ambiguous runs/expiry run
java tools/Workflow.java ambiguous runs/expiry clarify 120 reviewer
java tools/Workflow.java ambiguous runs/expiry run
java tools/Workflow.java ambiguous runs/expiry approve reviewer
java tools/Workflow.java ambiguous runs/expiry run
java tools/Workflow.java ambiguous runs/expiry clarify 240 reviewer
java tools/Workflow.java ambiguous runs/expiry run
java tools/Workflow.java ambiguous runs/expiry inject rollback
java tools/Workflow.java ambiguous runs/expiry approve reviewer
java tools/Workflow.java ambiguous runs/expiry run
java tools/Workflow.java ambiguous runs/expiry metrics
```

The first run asks how expiration should work. The clarification records
a demo TTL and accepts the stated policy of preserving existing perpetual
links and returning 410 for expired links. A revised TTL reuses the impact
assessment, invalidates downstream stages, and requires new approval.
The injected write failure restores the previous candidate receipt.
Use `inject fallback` on a fresh pending release to observe a review-only
receipt after two failed attempts.

## Verify and inspect

```text
mvn clean package
java tools/ServiceTest.java
java tools/WorkflowTest.java
git log --reverse --oneline
```

See [architecture and engineering summary](docs/architecture.md) for design,
validation, governance, and limitations.

## Iterations

| Version | Milestone |
|---|---|
| 1 | Spring Boot application with a health endpoint |
| 2 | Short-link creation, validation, persistence, and redirects |
| 3 | Click analytics with support for version 2 link records |
| 4 | Optional expiration and HTTP 410 |
| 5 | Persisted workflow with builds, approvals, and recovery |
| 6 | Spring MVC and workflow verification suites |
| 7 | Explicit approval binding to the tested JAR and snapshot |
| 8 | Quickstart, scenario walkthroughs, and engineering summary |
| 9 | JUnit regression coverage for persistence, legacy records, expiration, and missing links |

Run the V9 JUnit suite with `mvn test`. Reports are in `target/surefire-reports`.

The workflow CLI launches Maven from PATH. To use another Maven executable,
set `SHORTENER_MAVEN` to its absolute path on Unix or configure Maven on PATH
on Windows. Use a fresh workflow directory for each replay.
