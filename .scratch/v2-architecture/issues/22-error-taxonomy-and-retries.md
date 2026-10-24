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

- [x] Seam 2: a 429 then success yields retry events and a normal completion.
- [x] Seam 2: cancelling during backoff ends promptly with "aborted".
- [x] Seam 2: each provider's overflow response is classified as overflow; at seam 1 that leads to compaction and one
      retry.
- [x] No retry logic outside `ai` (architecture or code-search check).
- [x] `mvn test -Pquick` and the architecture tests are green.

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


**2026-10-23 (primary-source error mapping checkpoint).** Checked the providers' current official documentation:

- [GLM error codes](https://docs.bigmodel.cn/cn/api/api-code) documents HTTP400 with nested error.code1261 for an
  overlong prompt; code1210 is generic invalid parameters and must not imply overflow.
- [Kimi error codes](https://platform.kimi.ai/docs/api/errors) documents HTTP400 invalid_request_error with the
  specific message `Input token length too long` for exceeded input context; the same type also describes unrelated
  validation errors, so the type alone is insufficient. The older official Moonshot documentation redirects here.
- [DeepSeek error codes](https://api-docs.deepseek.com/quick_start/error_codes/) documents429,500,503 as rate/server
  failures but does not provide an exact overflow body on that page. Step error-code pages could not be retrieved;
  Step and Free gateway overflow payloads remain explicitly named compatibility assumptions unless independently
  confirmed by a primary source. Do not expand supported models/endpoints from these current docs during ticket22.

The actual FakeResponse.retry builder already exists; the older ticket04a Comment predates it. Preserve that existing
scripted seam rather than adding a duplicate retry mechanism. Headless retry visibility must preserve enqueue-only
subscribers and owned resource cleanup; no user-facing retry strings or retry scheduling belong in core.

**2026-10-23 (leader acceptance).** The shared `ai` exchange retries HTTP429, HTTP500–599 and transport/read
failures with at most three total attempts and token-cancellable 250ms/500ms waits. Every additional attempt emits
Retry before continuing. Content already delivered is never replayed; unobserved buffered tools, finish state and
usage reset between attempts. Cancellation remains connected through response headers, success/error body reads and
backoff. Structured provider mappings use the documented GLM/Kimi cases and explicitly named compatibility assumptions
for the other three adapters; generic parameter errors do not become overflow. Error inspection is bounded, and
malformed streaming data reports `Invalid provider stream` without raw parser snippets, response bodies or keys.
The existing core overflow path still invokes compaction and retries that request once, then fails on another
overflow. Core/harness contain no transient retry policy.

The app-owned MAIN-only queued retry writer makes notices visible while the request is running and drains accepted
output before final answer. Close retains interruption and reports output-sink failures after model work; child
notices stay separate. Internal summarizer retries remain observable at `ai` only, not as ordinary MAIN Retry or
assistant events; a session regression explicitly pins that boundary. No neutral stream reset or duplicate fake
retry builder was introduced. The reference/ADR and ticket24 integration checkpoint record these contracts.

Observed missing retries, all five overflow mappings, absent live status, malformed-SSE secret leakage and HTTP600
wrongly treated as 5xx reds turned green. Controlled mutations removing backoff cancellation wake or returning early
from interrupted status close each failed the intended regression; both were restored before final checks. Forty-eight
new cases cover provider/session/composition behavior, including ten actual-adapter success/persistent-overflow
recovery cases. Both independent final Standards and Spec reviews are clean. Final focused: **110/0/0/0** including
eight architecture checks. Full240 suites: **1997/0/0/0**, BUILD SUCCESS, fresh XML `/tmp/ticket22-full-reports`.
Logs: `/tmp/ticket22-{production-focused,full}.log`, `/tmp/ticket22-red-*.log`,
`/tmp/ticket22-mutation-{backoff,status-drain}.log`.

Independent leader quick235 suites: **1893/0/0/0**; named128 suites: **1036/0/0/0**; clean package passed.
Logs: `/tmp/cafecli-ticket22-leader-{quick,named,package}.log`. `/tmp/cafecli22-jar-check.py` passed against the actual
jar: live CLI429→success, exactly three503 attempts, explicit closed-connection recovery, cancellable retry, no retry
or retained answer after partial content, and no malformed-SSE secret diagnostics. Actual composed runs preserved
append-only source through overflow compaction, performed exactly one recovery, and failed on repeated overflow.
The real eval normalizer over those packaged traces gave retry/exhaust/recovered/repeated-overflow assistant counts
**1/0/3/2**, with latest-run tokens **11/7/4** or zero, excluding internal summary usage999 and failed attempts.
Fixture root: `/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket22-pq3lns0d`.
Packaged08/10/10-session/11/12/13/14/15/16/17-retained/18/19/20/21 regressions all passed against the same jar.

No v1 spine change, real keys/external services, push, real-terminal claim, AGENTS edit or unrelated scratch change.
