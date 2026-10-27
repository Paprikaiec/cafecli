# Assisted v2 smoke checks — 2026-10-27

**Pre-switch checks accepted; Ticket29 formal switch-over implemented and validated on 2026-10-27.**

The accepted Ticket28 JAR runs successfully through the local `start-v2.sh` helper, which selects v2 explicitly and uses a disposable project and private configuration. The launcher reused the maintainer-identified `.env` without displaying its values. Its storage and test writes are isolated from the development checkout.

For the remaining checks, run this in your normal terminal:

```bash
bash /Users/wyw/Project/cafecli-v2/.scratch/v2-architecture/check-v2-terminal.sh
```

The local wizard (`check-v2-terminal.sh`, an ephemeral helper) has three stages: approve/deny writes; streaming/resize/cancellation keys; overlay/approval/cancellation notice. It copies prompts to the clipboard, starts v2, checks file bytes, and records your observations. The maintainer has now run it; the interpretation below preserves both attempts. There is no need to repeat the whole wizard to clarify resize.

To start v2 directly instead:

```bash
bash /Users/wyw/Project/cafecli-v2/.scratch/v2-architecture/start-v2.sh
```

## Results

| Check | Result and evidence boundary |
|---|---|
| v2 contracts | 727 tests / 72 classes; zero failures, errors or skips. Includes the named screen-truth, reader, task, memory, permissions, snapshot, trace and compaction contracts. |
| v1/v2 parity | 34 tests / 4 classes; zero failures, errors or skips. These are the counts from this fresh invocation. |
| Packaged headless + real DeepSeek | PASS: actual read/write/read, exact `HEADLESS_OK\n` bytes, skill loading, connector status, and synchronous child completion with `background=true`. |
| Compatible trace + actual eval normalizer | PASS for headless, MCP and fresh-process memory recall. Strict JSON parsing, final result record, call/result ID sets, assistant counts and tool counts checked. |
| Save and restart | PASS: `/save`, session close/reopen, then a fresh JAR JVM recalled the exact stored code without it appearing in the prompt. |
| Model switch | PASS: an actual DeepSeek model-ID change, retained conversation marker, and a successful read tool on the newly selected model. Only DeepSeek credentials were available; cross-provider switching was covered by offline contracts. |
| Snapshot restore | PASS: real model read/write, `/snapshot`, `/restore 1`, exact baseline bytes restored. |
| Compaction | PASS: real provider, local 12000-token metadata window, observed typed Compaction event and Summary entry, early marker retained, successful tool use afterward. This is an accelerated local-window check. |
| Background task | PASS at the interactive session seam: real parent/child model calls, delayed child, initial completion, then autonomous main completion with no further user submission. This did not exercise terminal notification drawing. |
| MCP | PASS for the actual local HTTP MCP exchange: server received the expected echo argument; trace contains the exact returned value under the matching call ID. A real external Chrome MCP server was not exercised. |
| Browser | The installed connector reported isolated mode and an unconfigured Chrome server. Browser connection/navigation remains untested. |
| Human terminal | First Ghostty attempt: approve/deny, ESC/draft, idle Ctrl+C, overlay following/approval wake and visible overlay cancellation notice reported PASS. Busy Ctrl+D was labeled FAIL for doing nothing, which matches its intended no-op if output continues. Resize remains unclear from text notes alone. |

MCP formatting observation: the model wrapped the correct marker in prose and a code fence despite a request for a bare answer. The original strict-format probe records FAIL; the separate protocol/result evaluation records PASS. Both are retained. This is not reported as an exact-answer formatting pass.

The initial temporary compaction probe failed before making its compaction requests because it derived metadata for the previous session's switched model instead of the fresh session's active model. Correcting that probe setup produced the passing compaction run. The original source, failure, logs and retest are preserved. No product source was changed.

## Maintainer's Ghostty observations

Read from `human-terminal-results.tsv` on 2026-10-27; two attempts are present. The raw TSV remains unchanged.

| Case | Recorded observation | Interpretation |
|---|---|---|
| M01 approval/denial | First attempt: visible approvals PASS, approved-file bytes PASS, denied-file bytes PASS. Second attempt: SKIP with note that it already passed; approved-file check FAIL. | First completed attempt passes. The wizard resets fixtures before every stage; a skipped action does not satisfy the later byte check's precondition. The later raw FAIL is preserved, rather than presented as a reproduced product regression. |
| M02/M04 | First attempt PASS; second attempt SKIP. | ESC/draft preservation and idle Ctrl+C clear/exit have a recorded human pass. |
| M03 busy Ctrl+D | Both attempts labeled FAIL, with notes that Ctrl+D had no effect. | During an active run and with an empty input box, Ctrl+D is deliberately ignored. No visible reaction is expected. If output continued and Cafecli stayed open, the functional result is PASS. The notes do not establish a defect or prove whether a key was intercepted by the terminal. Idle, empty-box Ctrl+D has a different contract: exit. |
| M05 resize | Both attempts labeled FAIL: no reaction / did not see resize. | Needs clarification: did the maintainer physically drag the window edge, and did the layout fail to follow the changed geometry? No resize command, key or notification is expected. There is no screen/video capture in this evidence directory to decide from. |
| M06/M07 | First attempt PASS; second attempt SKIP. Overlay victim-file bytes PASS on both attempts. | Human smoke pass for following, approval wake, cancellation without closing, visible notice and retained draft. This does not prove the historical intermittent notice race fixed. |

The wizard's wording now explicitly states that busy Ctrl+D's no-op is the pass condition and that resize is performed by dragging the terminal window edge. Its shell syntax was checked; it has not been rerun for the maintainer. Ghostty is the reported terminal for these attempts; there is no evidence that switching terminal apps is necessary.

## Evidence and gate

Evidence directory:
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-assisted-_iif334y/evidence/`

Primary files: `automated-results.json`, `contracts.log`, `parity.log`, copied XML reports, `live-headless.jsonl`, `live-headless.normalized.json`, `live-session-results.json`, `live-session-retest-results.json`, `live-memory-restart-result.json`, `live-mcp-result.json`, `live-mcp-evaluation.json` and `live-mcp-rpc.json`. Human observations are in `human-terminal-results.tsv`.

The temporary Java driver called the accepted artifact's session composition and command seams with real providers; it compiled outside the repository against that JAR. Headless, MCP and process-restart checks invoked the actual JAR entry. These evidence types remain distinct from maintainer terminal observation.

Commit remains `cb6628b7e26b1fbf8ef10aea108ac84bd2672eac`. JAR SHA-256 rechecked after testing:
`bccb4d91be91cf7109b977ad79884d45ae61f7d469179e930b7c2100bc9b42f3`.

At the pre-switch observation checkpoint, no switch-over commit had been made. Issue29 records the confirmed approval and overlay passes; the combined key and resize items await the remaining clarification. Other automated/session checks retain their original evidence boundaries. ADR0016/0017's rendering acceptance remains open, and Ticket27's historical modal-notice race is not claimed fixed. The maintainer's explicit switch-over decision remains separate from using this working v2 launcher.

## Maintainer acceptance

After reviewing the observations, the maintainer explicitly confirmed all tests passed and requested the formal v2
switch-over on 2026-10-27. This subsequent acceptance closes the pending smoke decision above; it does not alter
the raw TSV, add missing screen evidence or turn the historical race into a fix claim. Ticket29 records the
implementation and post-switch validation. The local helper scripts and session-state JSON are ephemeral and are
not part of the switch-over commit.

## Formal switch-over validation

The default packaged entry now reaches v2 without a migration flag. The v1 spine, parity harness and HTTP runtime
are retired; leaves retain their package locations. Fresh quick1780, contracts729 and named screen-truth87 passed
with zero failures/errors/skips; clean package and both independent review axes passed. Actual packaged fake and
real DeepSeek runs without a flag verified file bytes and compatible trace normalization. The old accepted28 JAR
failed the same no-flag fake fixture, so the default-entry check is discriminatory.

Final JAR SHA-256: `35c95c7e4530489ed4ba9e888b64fd065151a6683bd50039443420159f7d7d6c`.
Evidence: `/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-ticket29-9l4n3d87/`. The single-line post-test
Javadoc cleanup was followed by a rebuild and verification that all 680 application class files remain byte-identical.
This is the local feat/v2-architecture switch-over; it does not close the broader rendering acceptance.
