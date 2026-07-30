# 04: Project Hook Trust — fingerprint pinning + approval pump + headless skip

Status: ready-for-human
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

Enable project-level `hooks.json` behind the trust model (项目 Hook 信任): project hook configs are untrusted until the user approves them once, pinned by content fingerprint.

On load, the project config's canonical content is fingerprinted (SHA-256). If the fingerprint is not pinned in the user-level trust file, the user is asked once — through the approval pump, showing the exact commands — before any project hook runs. Approve → pin + activate; decline → project hooks skipped for the session, session continues normally. Any content change invalidates the pin and re-prompts. Builtin and user-level tenants remain trusted by default.

Headless `run` mode never prompts: unpinned project hooks are skipped silently with a trace note (pinned ones run). Trust decisions and skips are audited.

## Acceptance criteria

- [x] Trust seam (seam 3, trust states): unpinned → approval requested with commands visible; approved → pinned and hooks run; declined → skipped, session continues; pinned → silent activation; content changed → re-prompt
- [x] Pin lives in a user-level trust file keyed by project; approving one project does not trust another
- [x] Headless `run`: unpinned project hooks skipped without any prompt, trace note emitted; pinned project hooks run
- [x] Trust prompt goes through the approval pump (no terminal reads outside the main thread)
- [x] Trust grants, declines, and headless skips written to the audit log
- [x] Demo path: project `hooks.json` in a repo → first session prompts → approve → hook fires; edit the config → next load re-prompts

## Blocked by

- 03 (`03-shellhook-config-loading.md`) — executor + config loading
