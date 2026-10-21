# 09: Message kinds, the boundary inbox, and steering

**Parent:** `.scratch/v2-architecture/PRD.md` (`core` agent log; `harness` context) · ADR 0020 D2, D3, D5
**Blocked by:** 05
**Status:** done (2026-10-21)

**What to build:** Input typed while a run is in flight (a Steering message) reaches the model at the next iteration
boundary, and is dropped if the run is cancelled first. Under the hood, this ticket lands the machinery every later
context feature uses.

- The harness kind registry: each custom message kind declares its type id, its serialization, and its presentation
  to the model (tagged user content, or hidden). The harness `convertToLlm` is composed from these declarations.
- A boundary inbox producer API that capabilities use.
- Steering: the session accepts input while a run is in flight; it lands at the next iteration boundary as a steering
  entry; cancelling discards steering that hasn't landed.
- One parameterized round-trip test over every registered kind, so later kinds are covered automatically.

- [x] Seam 1: steering submitted during a tool batch appears in the next recorded request, presented as the steering
      kind declares.
- [x] Seam 1: steering submitted and then cancelled never appears.
- [x] Seam 1: a hidden kind is in the agent log but not in the request.
- [x] The round-trip test iterates the registry and passes.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** `core.Agent` has `post(CustomEntry)` and drains the inbox at every iteration
boundary — but nothing can take entries back. Anything posted after a run's last drain stays queued and lands in the
**next** run, after that run's user entry, whatever the outcome (completed, cancelled, failed, cap). That breaks this
ticket's "steering submitted and then cancelled never appears" and two v1 behaviors: v1's `SteeringInbox.closeForTurn()`
hands undelivered steering back to be re-posted as an ordinary next submission ("不得静默丢失"), and on cancel it is
discarded (CONTEXT.md 取消键: 丢弃未生效的转向消息). Core must not discard by itself — task notifications (18 / 23) must
survive a cancel — so add a small additive core API (e.g. take undelivered entries back atomically at run end, or a
per-kind policy) and let the harness decide per kind. "有没有回合能接它" and "放进去" must stay one atomic action.

**2026-10-20 (from ticket 04c review).** `AgentSession.submit` throws `IllegalStateException` when a run is in flight
(synchronous mode only). Steering turns that into "accepted as steering" — the facade's return shape has to say which
happened (see the note on ticket 10 about a submit result type).

**2026-10-21 (leader acceptance).** Added per-agent `MessageKind` / `MessageKindRegistry`: type id, Jackson class, tagged-user/hidden presentation compose serialization and convertToLlm from one declaration set. `AgentScope.messageKind` and an enqueue-only `BoundaryInbox` producer honor profile closure and agent locality; intrinsic harness `Steering` retains v1's marker. The parameterized round-trip test discovers actual headless composition registrations and requires a fixture for each, so future registered kinds cannot silently bypass it. Generic custom JSON numeric equality and existing details deserialization remain unchanged.

`submit` now returns `SubmitResult.Completed(outcome)` or immediate `SteeringAccepted`. Admission and run-end selective inbox take share the session lock. Cancelled pending steering is discarded, nonsteering entries survive, and already-landed steering remains history. Generic core `takeInbox(predicate)` makes no product-policy decision. Noncancelled undelivered steering is handed back exactly once as ordinary input through `takeFollowUpInputs()`, also after a thrown callback/subscriber; caller must drain after submit returns/throws or awaitIdle, since RunEnd precedes finally-owned handoff. No automatic runs/event loop were added; ticket23 owns reposting UserSubmit events. CLI unpacks the completed synchronous branch and retains08 close/drain ownership.

Implementer observed initial concurrent-submit test red at the old IllegalStateException, then green. Focused12 suites **140/0/0/0**, including8 architecture rules. Independent leader quick **1583/0/0/0**. Independent mutants removing cancellation gating in core drain and in session handback each made the corresponding regression fail; exact source restored and all7 steering tests passed. Both Standards and Spec reviews clean. Clean package passed; packaged fake write/guard/malformed/unknown scenario still verifies exact bytes, root-external victim unchanged, expected3 audit records durable at exit. No network/API key use; no render implementation changes or real-terminal claim. Local commit only, no push.
