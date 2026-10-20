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
