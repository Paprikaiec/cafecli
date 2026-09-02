# Deterministic Terminal Replay Oracle

Status: semantic conformance implemented; animated-fullscreen boundary decision required

## Problem

Terminal.app's animated fullscreen transition still leaves duplicated live-region rows in scrollback.
The existing regression loop is not a deterministic oracle:

- one `fullscreen 6` run varies from 28 to 118 copies on unchanged code;
- blocking DSR probes perturb the frame cadence;
- `TerminalScreenModel` encodes assumptions that have not been calibrated against the same real run.

The next diagnostic layer must turn one real, noisy run into immutable input with an attached ground
truth result.

## Approved Boundary

Use a hybrid diagnostic boundary:

- the shipped jar contains opt-in, in-memory recording and an offline replay CLI;
- recording is enabled only by an explicit trace path and does not alter normal renderer behavior;
- the terminal emulator is independent from `LiveRegionStrip` and renderer layout code;
- JUnit owns committed Terminal.app conformance fixtures and exact assertions;
- `scripts/resize-storm.sh` captures the trace and Terminal.app history from the same quiesced process:
  a request/ready handshake stops renderer producers, waits for an in-flight writer, preserves the last
  displayed live frame, persists the recorder, and only then exposes history for capture.

The trace is versioned JSONL:

1. a header records terminal type, initial rows/columns, UTF-8, and tty output flags;
2. write events contain the exact Java-side UTF-8 bytes as Base64 plus sequence/time;
3. an opt-in daemon samples `TIOCGWINSZ` through JLine's bundled JNI bridge every 1 ms and appends
   only observed geometry transitions; delivered WINCH events remain ordered diagnostic no-ops and
   cannot roll sampled geometry backward;
4. an unresolved resize makes the trace incomplete and replay must reject it instead of guessing.

Terminal.app history contains login/prompt noise from before the JVM starts. The storm driver therefore
emits a stable, app-owned anchor; exact comparison slices both the model and the real history at the
first occurrence of that anchor. No whitespace, line-ending, blank-row, or ANSI-result normalization is
allowed after the slice.

## Acceptance Criteria

1. **Not met:** a recorded `fullscreen` run that contains N duplicated live-region copies must replay
   with the same history text and the same copies. The current Java boundary cannot observe when
   Terminal.app consumes queued PTY bytes relative to its window-reflow queue; see Calibration Result.
2. **Met for conformance fixtures:** write bytes, resize order, interior blank rows, trailing screen
   rows, and logical soft-wrap joins are compared exactly.
3. **Met:** recording does no JSON/Base64/filesystem work on the hot path. Trace persistence happens on
   explicit close or the JVM shutdown hook. The explicit trace path additionally runs the native
   geometry sampler; normal and metadata-only paths remain sampler-free.
4. **Pending:** recording-on and recording-off fullscreen runs must establish comparable copy-count
   distributions before a stronger trace boundary is trusted.
5. **Met:** the conformance suite is captured from Terminal.app and covers:
   - hard-newline rows versus auto-wrapped chains during reflow;
   - a cursor at an exact new-width multiple;
   - a double-width CJK glyph at the right edge;
   - `clr_eos` erasure without scrollback insertion;
   - rows moved to permanent scrollback by a height reduction;
   - `parm_down_cursor` clamping at the bottom row.

## Calibration Result

The six small Terminal.app fixtures match byte-for-byte. They calibrate terminal semantics, but they do
not prove that a fullscreen trace contains a complete causal schedule.

The final calibration repeated the experiment after the request/ready handshake had removed the
shutdown-suffix race:

| Exact-endpoint paired truth | Fixed events in fresh Terminal.app | Offline emulator |
|---:|---:|---:|
| 116 spinner copies | 23, then 23 again | 22 |

The two fresh Terminal.app playbacks were byte-identical to each other, but not to the paired truth.
The trace retained 1,136 ordered events: 446 writes and 690 resolved resize records (284 native
geometry transitions plus 406 delivered-WINCH markers). No resize was unresolved. Earlier exploratory
runs produced 13 versus 4 and 19 versus 1, but their STOP -> TERM/CONT persistence protocol had an
endpoint race; those numbers are retained only as history and are not acceptance evidence.

The renderer records a write before submitting/flushing it to the PTY. Terminal.app later consumes
that queued output asynchronously on a different event path from window animation. Therefore Java
write time and sampled tty geometry do not determine the order in which Terminal.app applies "draw"
and "reflow". The stable 23-copy real-terminal playback shows that replaying the captured Java ordering
is repeatable, while its divergence from the paired 116-copy truth shows that the ordering is incomplete.
Fitting an artificial write delay to the truth would be circular calibration, not an oracle.

The capture harness now closes a separate Java-side endpoint ambiguity that existed in the first
calibration runs: it no longer resumes an active renderer so a shutdown hook can persist the trace.
Its kernel-backed request watcher is idle during animation; after the request, the renderer retains the
last live frame, rejects queued/future producers, waits for any write already in flight, explicitly
persists the trace, and publishes readiness last. No application byte can follow readiness. This makes
the Java submission prefix and history-capture boundary exact, but it cannot order bytes already queued
in the PTY against Terminal.app's reflow processing and therefore does not cross the boundary above.

Crossing this boundary requires a new architecture decision. The realistic next experiment is an
opt-in terminal-processing fence (for example, asynchronous DSR acknowledgements bracketing emitted
frames) so the trace records when Terminal.app has consumed bytes, not merely when Java submitted
them. That mechanism changes the terminal workload, competes with the input reader, and must pass a
fresh recording-on/off perturbation study. Without such a fence—or instrumentation inside
Terminal.app—Acceptance Criterion 1 is not achievable from the current data.

## Scope Limits

- This work builds and calibrates the oracle; it does not fix the animated-resize defect.
- It does not optimize `TerminalCapabilities.safeSize()` or change the height-only erase path.
- A captured output trace can validate emulator fidelity only when it matches its paired truth. The
  current fullscreen trace does not and must not be promoted to a regression oracle. Re-running a
  future renderer implementation against the same producer-event schedule would additionally require
  a higher-level producer/render-input trace; replaying a fixed byte stream does not provide that layer.
- Transcript overlays, palettes, and approval readers are outside the `--storm` recording path. The
  recorder must not claim completeness for sessions that enter those direct terminal-writing modes.
- The current trace is complete only at the Java submission/native-geometry boundary. It must not be
  described as a complete Terminal.app processing schedule.

## Verification Gates

- focused replay/recorder/conformance tests;
- `mvn -o test -Pphase16-smoke`;
- `mvn -o test -Pquick -Dtest='!InteractiveReplTest' -DfailIfNoTests=false`;
- real Terminal.app `zoom`, `drag`, `height`, `heightdrag`, `storm`, and `fullscreen` runs;
- final line-by-line source diff review and cleanup of every spawned tty/process/window.
