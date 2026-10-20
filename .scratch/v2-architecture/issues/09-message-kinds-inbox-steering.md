# 09: Message kinds, the boundary inbox, and steering

**Parent:** `.scratch/v2-architecture/PRD.md` (`core` agent log; `harness` context) · ADR 0020 D2, D3, D5
**Blocked by:** 05
**Status:** ready-for-agent

**What to build:** Input typed while a run is in flight (a Steering message) reaches the model at the next iteration
boundary, and is dropped if the run is cancelled first. Under the hood, this ticket lands the machinery every later
context feature uses.

- The harness kind registry: each custom message kind declares its type id, its serialization, and its presentation
  to the model (tagged user content, or hidden). The harness `convertToLlm` is composed from these declarations.
- A boundary inbox producer API that capabilities use.
- Steering: the session accepts input while a run is in flight; it lands at the next iteration boundary as a steering
  entry; cancelling discards steering that hasn't landed.
- One parameterized round-trip test over every registered kind, so later kinds are covered automatically.

- [ ] Seam 1: steering submitted during a tool batch appears in the next recorded request, presented as the steering
      kind declares.
- [ ] Seam 1: steering submitted and then cancelled never appears.
- [ ] Seam 1: a hidden kind is in the agent log but not in the request.
- [ ] The round-trip test iterates the registry and passes.
- [ ] `mvn test -Pquick` and the architecture tests are green.
