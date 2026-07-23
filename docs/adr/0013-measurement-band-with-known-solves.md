# 0013 — Fixed measurement band (with known-solves) replaces fail-only hunting

> Status: **accepted** (2026-07-23; user-confirmed composition "~15 tested + ~5 fresh", N=20)

## Context

Pass-rate work has so far been measured on hand-picked core-fail bands (n=9 on 2026-06-08, n=6 from Phase 0 onward). That method was right for causal debugging but is exhausted as a yardstick:

- The upstream pool is frozen: HF `SWE-bench-Live/MultiLang` java split still has **109 instances** (checked 2026-07-21, identical to the 2026-06-07 export). The anti-contamination window (2026-03/04) holds 52, ≈26 after the static noise filter. Waiting for new months is not a strategy.
- Fail-hunting starved because the clean pool solves at a high rate: the tested universe is ≈25 instances with verified outcomes (≈14 solve / ≈11 fail); only 6 discriminating core-fails ever surfaced. The scarcity was a property of the goal (finding failures), not of the pool.
- Measured noise ≈ effect size on n=6: floci-352 flipped pass↔fail across runs of the same config; floci-248 control flipped across repetitions in the 2026-07-20 safe-edit A/B, whose scoring inference bottomed out at exact McNemar p=1.0 with a single discordant pair.
- After safe-edit, every remaining core-fail is a repair-quality failure; the next levers (repository topology, verification relevance, trajectory compaction, best-of-N) need a band on which their effect is measurable and regressions are visible.

## Decision

1. **The yardstick is a fixed 20-instance measurement band (v1)**, all inside the 2026-03/04 anti-contamination window: 15 previously tested instances (7 known-solve + 8 known-fail) + 5 fresh (`alibaba__nacos-14917`, `alibaba__nacos-14778`, `spring-projects__spring-boot-50119`, `spring-projects__spring-framework-36550`, `strimzi__strimzi-kafka-operator-12590`).
2. **Known solves are band members by design.** Progress = fails flipping while solves hold; solves are the regression guard; a fail-only band has survivor bias and cannot show harm.
3. **Repo cap ≤ 6** (floci sits exactly at the cap, including the five core-fail floci); 13 distinct repos; uncertainty is reported **repo-clustered** — repetitions of one instance are correlated observations, the repo/instance is the sampling unit.
4. **The Phase-0 core-6 remains a subset** and keeps serving as the fast causal-debug suite, comparable with all historical reports.
5. **Admission of fresh instances requires 3× gold validation** (`resolved=True`, 0 P2P regressions, three times — the official project recommends repeated gold runs due to environment instability). A candidate failing admission is replaced from the clean window under the repo cap; backups: `opendataloader-pdf-383/-386`, `jetty-14652/-14748` (deliberate hard/long-horizon tier).
6. **Run protocol** (inherited from the 2026-07-20 safe-edit study): paired arms, ≥2 repetitions, arm order reversed across repetitions, exact McNemar on discordant pairs, Wilson intervals on arm proportions, and automatic trajectory labels (localization / patch-apply / context-loss / irrelevant-verification / iteration-exhaustion).
7. **Extension tier, only if the band saturates:** the 2025-07/08 tranche (46 instances) may be admitted for *paired-only* conclusions — contamination is symmetric inside a same-model A/B — and stays excluded from absolute-rate claims.

## Rejected alternatives

- **Keep hunting discriminating fails**: starved at 6; cannot measure regressions; every selected instance is conditioned on past failures, which reads as optimizing the band rather than the agent.
- **Wait for new upstream months**: pool frozen at 109.
- **All 20 from the tested universe**: zero setup cost, but floci ≈ 9/20 and every member already influenced design decisions.
- **Leaderboard-first**: full-split infra before any product work, and selected-band numbers never were leaderboard estimates anyway.

## Consequences

- Absolute band numbers are not leaderboard estimates (selected, small, clustered sample); claims stay paired and structural — same honesty discipline as the 2026-07-14 and 2026-07-20 reports.
- Default-flip and go/no-go decisions for future levers (capability closure, patch admissibility, repository topology, repair-ledger compaction, best-of-N/worktrees) cite band A/Bs under the §6 protocol.
- Roster changes require an amendment note here (comparability across reports depends on a stable roster).
- `helidon-11407` gives trajectory-compaction work its long-horizon measurable instance; the jetty backups are the designated horizon-stress tier.
- Band provisioning on the eval machine (image pulls for the 5 fresh, `band.v1.txt` id pinning, canonical wrapper with `-Dpaicli.safe_edit.enabled=true`) is the first cloud session's queue.
