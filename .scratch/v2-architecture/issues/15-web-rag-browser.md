# 15: Web, RAG, and browser capabilities

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` capabilities) · ADR 0020 D4, D6 · ADR 0019
**Blocked by:** 08, 09
**Status:** done

**What to build:** Web search and fetch, semantic code search, and the browser tools work in v2 as in v1, including
the freshness search and the rule that a cancelled or hook-blocked search never silently falls back to a real one.

- Web: `web_search`, `web_fetch`; the freshness web search as a run-start log writer; the StepSearch fallback reads
  status only — usable means non-empty and not `ERROR` / `DENIED`, so `CANCELLED` / `BLOCKED` are passed back as they
  are.
- RAG: `search_code`.
- Browser: `browser_connect`, `browser_disconnect`, `browser_status`, and the browser check joining the permission
  chain from ticket 08.
- Each is its own capability; an agent whose profile doesn't attach one has none of its tools (Capability Closure,
  intentional change 8).

- [x] Seam 1: a cancelled or hook-blocked StepSearch call triggers no real web search.
- [x] Seam 1: a freshness-triggering question gets a freshness entry in the first request; others don't.
- [x] Seam 1: an agent without the browser capability has no `browser_*` tools in its recorded tool list.
- [x] Existing web, RAG, and browser leaf tests pass unchanged.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (approved implementation refinement; acceptance pending).** StepSearch cannot bypass the existing
permission path or submit children into the four-worker pool already occupied by its outer calls. Generic
invocation-scoped ToolInvoker supplies lookup/invoke over one executable snapshot: nested leaves execute inline on
their worker, while preflight and completion rendezvous with the owner. Standalone freshness/idle-command work uses
a cancellable worker and an owner callback pump. AuxiliaryToolCall carries effective call/result, parent identity
and the explicit invocation token; PostToolUse and the owned audit writer observe it without model ToolStart/End,
assistant/tool-result entries, trace iterations or model-only Stop summaries. Core learns no Web/MCP/browser terms.
Cancellation/close expire ports and settle or reject pending work; parent execution, rather than sibling batch
completion, bounds nested lifetime. Active failure formatting stays on the owner.

Browser guards decorate the final executable snapshot, including dynamic MCP tools. Absolute refusal runs inside
execution; sensitive-page notices force per-call approval only on the ordinary enabled route, preserving v1's
Hook ALLOW/disabled-HITL preview bypass. BrowserCommands and FreshnessSearch are approved behavior-preserving leaf
extractions with v1 delegating shims; existing assertions must remain unchanged. Production browser mode state and
commands share the connector, and `/tabs` invokes the auxiliary route instead of a raw MCP client. Hook state must
start before Web's freshness writer. Captured Web/RAG config keeps each existing leaf's distinct precedence rules.

Idle command dispatch reserves token/owner/admission under the session lock, runs the handler outside it and
releases/notifies in finally. Cancel/close/awaitIdle include that reservation; concurrent submit rejects instead of
steering into an idle agent. Only the dispatch owner may invoke command tools or `/clear` within its reservation.
Manager restarts retain their existing bounded I/O behavior, without a promise of immediate token abort. ADR 0020,
PRD, CONTEXT and reference documentation record these decisions; tickets23/25/26 carry integration checkpoints.
No new parity exception, UI implementation, real service or real-terminal acceptance is authorized by this seam.

**2026-10-22 (leader acceptance).** Web, RAG and Browser attach in actual production Composition with explicit
captured dependencies; Hooks precede Web. Freshness keeps v1's exact trigger/query/body, first-request placement and
stable prompt; its custom kind round-trips with the production registry. Web/RAG/browser profile closure, exact
leaf text, all six StepSearch statuses, empty OK fallback versus terminal empty CANCELLED, four nested calls without
pool exhaustion, owner callbacks, Pre/Post/audit and absence of duplicate model events are covered at the session
seam. Shared browser `/tabs` retains hooks, approval and audit. Browser refusal remains inside execution, sensitive
notices force ordinary-route per-call approval, and Hook ALLOW/disabled-HITL controls preserve v1 behavior. Navigation
mutates only on typed normal return; browser metadata reaches the owned writer. Existing leaf assertions are unchanged.

Two independent fresh Standards/Spec reviews found premature navigation mutation and real-search fallback after
cancellation; rereview also found the token-live abandonment variant. The same implementer reproduced failed
navigate_page/new_page state changes (2 red), actual cancellation fallback (1 red), and owner-interruption fallback
with two-member batches (3 cases, 2 red), then corrected all three paths. Abandoned generic invocations normalize
late results to content-free CANCELLED; Web supplies existing cancellation presentation before the unchanged leaf's
usability check. Regressions wait for the outer worker's finally before asserting zero provider calls. Owner
interruption exercises timeout's accepting=false/token-live branch without adding a test-only config knob. Both
review axes rereviewed the final full tracked/untracked diff clean. Per-writer retained-port misuse was separately
observed red then green; ports also expire with parent execution while siblings remain active. Callback failures,
command lock release, cancel/close drain, owner-only invocation and `/clear` admission are covered.

Implementer final named suite: **509/0/0/0**. Independent leader quick: **1744/0/0/0**; independent named
core/harness/app/architecture/Web/RAG/browser/tools/MCP plus v1 browser/freshness/HITL shims: **678/0/0/0**, including
architecture8, registry78 and MCP manager/transport suites excluded from quick. Clean package passed. A standalone
checker compiled against the jar exercised actual Composition with a local SearXNG fixture: freshness and explicit
queries issued exactly two requests; SSRF refusal issued none; browser unconfigured text and RAG unindexed behavior
matched. Actual packaged CLI made only the expected local search and produced exact stdout. Packaged ticket08/10/11/
12/13/14 guard, hook/session-identity, memory, budget, MCP and skill/LSP fixtures all passed. Logs:
`/tmp/cafecli15-broad-final2.log`, `/tmp/cafecli-ticket15-leader-{quick,named,package}.log`; packaged checks are in
`/tmp/cafecli15-jar-check.py` and the preceding ticket fixture scripts. No real keys/external services, render changes,
real-terminal claim or push. Unrelated `.scratch/sessions-context-memory/` remains untouched.
