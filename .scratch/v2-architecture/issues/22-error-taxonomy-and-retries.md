# 22: Error taxonomy and retries

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` error classification) · ADR 0020 D8
**Blocked by:** 06, 12
**Status:** ready-for-agent

**What to build:** A transient 429 or 5xx is retried with a visible retry status instead of ending the run, and every
provider's context-overflow error triggers compaction and one retry (intentional changes 3 and 4).

- Each adapter classifies failures as retryable (429, 5xx, network), context overflow (map the provider's specific
  error; where the exact body is unknown, record the assumption in the test name and a comment), aborted, or fatal.
- Retries live inside `ai`: backoff with a cap, a retry event per attempt, and cancellation that interrupts the
  backoff wait.

- [ ] Seam 2: a 429 then success yields retry events and a normal completion.
- [ ] Seam 2: cancelling during backoff ends promptly with "aborted".
- [ ] Seam 2: each provider's overflow response is classified as overflow; at seam 1 that leads to compaction and one
      retry.
- [ ] No retry logic outside `ai` (architecture or code-search check).
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04a review).** `StreamEvent.Retry` may appear "anywhere in between", but the stream's partial
message is **not reset** on retry: a retry after content was already streamed would show the consumer that content
twice and duplicate it in the final message. Either retry only before the first content event (document it as the
contract), or add an explicit reset to the event contract that core handles. Note too that `Emitter` is final with a
private constructor, so retries are currently per-adapter `Source` logic, not one shared wrapper — and the fake
provider never retries on its own, so intentional change 3 (visible retry status) needs a scripted `retry` step in a
`FakeResponse` builder to be shown at seam 1.

**2026-10-21 (from ticket 06 review).** `ai.openai.OpenAiCompatibleProvider` has initial classification only: 429/5xx and HTTP transport/read failures → RETRYABLE, other non-2xx → FATAL; no retries, backoff, or provider-specific overflow mapping. HTTP failure presentation currently contains only status, without echoing the response body/credential. Add structured provider-error parsing for overflow here while keeping sensitive values out of diagnostics. The `CancellationToken` registration cancels the OkHttp Call for both blocked response headers and body reads; keep it active until exchange close. Tool-call chunks are buffered by index and replayed on finish, so cancellation before finish delivers no partial tool-call blocks. Existing malformed-stream/finish-reason tests cover initial FATAL behavior.


**2026-10-23 (ticket21 packaged transport checkpoint).** A Python HTTP/1.0 fixture without explicit connection
closure reproduced a pooled-connection EOF on the second plain Kimi request, independently of tools, switching or
trace. Targeted transport evidence showed neither interruption nor cancellation; HTTP/1.1 alone, or HTTP/1.0 with
`Connection: close`, made the same two requests pass. The adapter reports the EOF as RETRYABLE with
retryOnConnectionFailure(false), keeping retries owned by this ticket rather than silently enabling library retries.
The corrected provider/model-switch fixture uses HTTP/1.1. Include a deterministic local closed-connection/transport
retry case at seam2, with visible bounded attempts and cancellation; Maven need not depend on Python or copy the
throwaway diagnostic harness. Do not retry after content was already delivered without an explicit reset contract.
