# Repeated safe-edit A/B — existing `PathGuard` rule

## Executive result

The existing safe-edit Guard produced a **strong and repeated process improvement**, but only a
**small, statistically inconclusive resolution-rate improvement** on this selected band.

| Metric | Control | Safe-edit | Paired reading |
|---|---:|---:|---|
| Resolved | **3/12 (25.0%)** | **4/12 (33.3%)** | 1 safe-only, 0 control-only, 3 both-pass, 8 both-fail |
| Wilson 95% interval | 8.9%–53.2% | 13.8%–60.9% | Wide and overlapping |
| Exact McNemar, resolution | | | **p=1.0000** — no pass-rate claim |
| Runs submitting test changes | **8/12** | **0/12** | 8 control-only pairs, **p=0.0078** |
| Safe-edit policy blocks | 0 | **8** | Every treatment test-write attempt was blocked |
| Harness patch-apply failures | **5/12** | **0/12** | 5 control-only pairs, p=0.0625 |

**Decision:** enable the current Guard for **SWE-bench evaluation runs**, while keeping the
interactive product default off. It deterministically removes the intended self-harm, caused no
paired resolution losses, and yielded one paired win. Do not claim that it has proven an 8.3-point
general pass-rate lift: there was only one discordant scoring pair, and the point improvement occurred
in repetition 1 but not repetition 2.

## Experiment design

- Dataset: SWE-bench-Live / MultiLang Java, the same six Phase-0 core-fail instances.
- Repetitions: two stochastic runs per instance and arm: **24 solve + official-evaluation cases**.
- Paired scheduling: control and treatment for each instance entered the two-slot queue adjacently;
  arm order was reversed in repetition 2 to reduce time drift.
- Fixed variables:
  - `deepseek-v4-pro`;
  - the same `cafecli-hooks.jar` used by the 2026-07-14 hook A/B;
  - SHA-256 `e8ed73879f75ef58d54832155626904b94c5616fb1a36503e699f6c167eba085`
    (MD5 `f85316e1636bf5705326eb81099ffc26`);
  - identical task prompt, official harness, 80-turn hard limit, `edit_file` enabled, lifecycle hooks off;
  - the prompt deliberately continued to say “and tests if needed,” so model behavior was not nudged
    differently between arms.
- Only treatment variable:
  - control: `-Dcafecli.safe_edit.enabled=false`;
  - treatment: `-Dcafecli.safe_edit.enabled=true`.
- Integrity checks: 24/24 complete traces, patches, official reports, matching jar/model/turn metadata,
  and inner Agent exit code 0. No configuration mismatches or missing pairs.

The experiment is intentionally a **selected failure-shape band**, not a representative leaderboard
sample. Repetitions of the same six instances are correlated observations.

## Per-instance results

| Instance | Repeat 1: control → safe | Repeat 2: control → safe | Interpretation |
|---|---|---|---|
| `floci-164` | pass → pass | pass → pass | Guard removed test changes and patch conflicts; score unchanged |
| `floci-248` | **fail 0/0 → pass 1/0** | pass 1/0 → pass 1/0 | Only safe-only win; treatment was 2/2 vs control 1/2 |
| `floci-88` | fail 0/0 → fail 0/0 | fail 0/0 → fail 0/0 | Test self-harm removed, underlying production repair still wrong/incomplete |
| `floci-299` | fail 0/0 → fail 0/0 | fail 0/0 → fail 0/0 | Neither arm tried to edit tests; pure repair-quality gap |
| `floci-352` | fail 1/1 → fail 1/1 | fail 1/1 → fail 1/1 | Test changes removed; same behavioral near-miss remained |
| `grpc-12735` | fail 0/0 → fail 0/0 | fail 0/0 → fail 0/0 | No test edit; nested Gradle layout remained a negative control |

By repetition:

- Repeat 1: control **1/6**, safe-edit **2/6**.
- Repeat 2: control **2/6**, safe-edit **2/6**.

The score direction is positive and showed no harm, but the score improvement itself did **not**
repeat. The process effect did repeat exactly: each repetition had four control patches with test
changes, four treatment blocks, and zero treatment patches with test changes.

## Causal reading of the only scoring difference

In `floci-248` repeat 1, both arms edited the same two gold-relevant production files:

- `AcmJsonHandler.java`;
- `model/Certificate.java`.

Control also added its own `exportImportedCertificate` test to `AcmImportExportTest.java`, overlapping
the official test patch's new method. The official report contained 1,496 passing regression tests but
no result for the required F2P test, so the run scored 0/0. Treatment attempted one test edit, the Guard
blocked it, the submitted patch contained only the two production files, and the required F2P test ran
and passed.

Repeat 2 is the necessary calibration: control again submitted a test change but happened to score
1/0, while treatment again blocked its test edit and scored 1/0. Therefore the Guard removes a real
evaluation hazard, but any individual overlapping test edit is not guaranteed to flip a pass into a
failure.

## Process and cost effects

| Process metric | Control | Safe-edit | Change |
|---|---:|---:|---:|
| Test-write attempts | 11 | 8 | Treatment stopped after the first rejected attempt in each affected run |
| Policy blocks | 0 | 8 | 8/8 treatment attempts blocked |
| Runs with test files in submitted patch | 8 | 0 | **−100%** |
| Harness patch-apply failures | 5 | 0 | **−100% observed** |
| Submitted patch lines | 1,823 | 767 | **−57.9%** |
| Verification-command calls | 51 | 42 | −17.6% |
| Median turns/run | 24.0 | 26.5 | +2.5 turns |
| Median solve wall time | 151.2s | 151.7s | effectively unchanged |
| Total solve wall time | 2,048.2s | 2,018.0s | −1.5% |
| Uncached input tokens | 414,318 | 405,510 | −2.1% |
| Output tokens | 108,949 | 111,987 | +2.8% |

No treatment trajectory bypassed the Guard through `execute_command`; zero treatment patches contained
test changes. This is an empirical observation for these 12 treatment runs, not a policy guarantee:
the current Guard only mediates `write_file` / `edit_file`.

## What this changes—and what it does not

### Supported

1. **GO for eval-only activation.** The Guard reliably enforces a production-only submitted patch on
   the current floci failure shape, with no paired scoring regressions in this experiment.
2. **The Phase-0 mechanism was real.** Test-patch overlap was not merely a one-off retrospective story:
   control reproduced test changes in 8/12 runs and patch-apply failure in 5/12.
3. **Safe-edit is a hygiene lever, not a reasoning lever.** It rescued one scored run but could not fix
   the repeated wrong/incomplete repairs in 88, 299, 352, or grpc.

### Not supported

1. A general +8.3 percentage-point pass-rate claim. The selected sample is small and the exact paired
   resolution test is uninformative (`p=1.0`).
2. Enabling the Guard in normal interactive use. Users legitimately edit tests there.
3. Treating root `src/test/**` as a complete test-layout model. The current implementation misses paths
   such as `xds/src/test/**`, `core/src/test/**`, Gradle custom source sets, and non-Java test trees.

## Recommended next slice

1. Add `-Dcafecli.safe_edit.enabled=true` to the canonical SWE-bench solve wrapper only; keep the Java
   product default unchanged.
2. Replace the root-only rule with repository-aware **patch admissibility**:
   allow temporary regression tests for reasoning, then restore or exclude discovered test roots from
   the scorer patch. This preserves test-driven work instead of rejecting it.
3. Run the generalized policy on a broader rotating clean Java band. Keep paired repetitions, but treat
   repository/instance as the sampling unit and report clustered uncertainty.

## Reproduction and artifacts

Checked-in scripts:

- `scripts/csolve_cafecli_safe_edit_ab.sh` — container-side treatment switch and artifact capture;
- `scripts/solve_one_safe_edit_ab.sh` — one solve and persistent archive;
- `scripts/run_safe_edit_ab.sh` — resumable repeated paired driver;
- `scripts/analyze_safe_edit_ab.py` — paired outcome/process analysis;
- `scripts/test_analyze_safe_edit_ab.py` — analyzer regression tests.

Cloud artifacts:

- results: `~/swebench/safe-edit-ab-results.tsv`
  - SHA-256 `9aa83370d7eda7605cd0ee1326c9b04b514b9d698fa80c0925c23ad4e0e55c79`;
- analysis: `~/swebench/safe-edit-ab-analysis.json`
  - SHA-256 `bc5540c97ec2c8238d9eb216d4e72700250c28e4880d7f14e9a9c67b91308316`;
- per-run solve artifacts:
  `~/swebench/safe-edit-ab/<arm>/r<repeat>/<instance_id>/`;
- official reports:
  `~/SWE-bench-Live/logs/safeedit-<arm>-r<repeat>-<instance_id>/<instance_id>/report.json`.

### Disclosed runner anomaly

The first `floci-164` pair was already inside the Agent container when the host-side solve wrapper was
hot-updated to harden credential transport. After the completed inner solve, the old wrapper process
attempted an extra empty container launch and returned 127. Both inner Agent runs recorded `exit=0`,
complete traces/patches/configuration metadata, and valid official reports; both arms were affected
symmetrically, and the clean repeat-2 pair independently reproduced the both-pass outcome. These two
scores are retained, with the anomaly disclosed rather than hidden.
