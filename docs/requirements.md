# Requirements and acceptance criteria

The service creates short links, redirects visitors, and records aggregate
click counts. The workflow demonstrates controlled execution of requirements,
design, implementation, testing, documentation, and release readiness.

| ID | Requirement | Acceptance criterion |
|---|---|---|
| URL-1 | Create and resolve a short URL | A valid HTTP(S) destination creates a code and GET /r/{code} returns 302 with Location |
| URL-2 | Reject invalid input | Reject malformed or unsupported URLs with 400, oversized input with 413, and return 404 for unknown codes |
| URL-3 | Add aggregate click counts | A baseline link can still redirect after the upgrade and each successful GET adds one click |
| URL-4 | Clarify expiration | Record an approved TTL, retain perpetual links, and return 410 at the expiration boundary |
| WF-1 | Orchestrate the SDLC | A persisted dependency graph forks testing and documentation, joins before release, and resumes across invocations |
| WF-2 | Govern the release | Pause for human approval, stop on denial, and expose bounded retry, fallback, and rollback evidence |
| WF-3 | Handle revised requirements | Invalidate changed stages and downstream outputs while preserving independent outputs and previous revisions |
| WF-4 | Report reliability | Record stage and run events and derive success, retry, rollback, recovery time, and latency metrics |

The prototype uses a local server and local file storage. It follows no
destination URL on the server. Approval controls a local release receipt.
The greenfield scenario builds the core API. The brownfield scenario adds
analytics while preserving old records. The ambiguous scenario asks how
expiration should work before applying a bounded, optional TTL.
