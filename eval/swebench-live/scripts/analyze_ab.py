#!/usr/bin/env python3
"""Analyze A/B builtin-hook runs.

Per (arm, instance) from ~/swebench/ab-hooks/<arm>/<iid>/:
  - trace.jsonl  -> writes, verify-commands, ended_without_testing (instance-agnostic)
  - audit/       -> hook_block counts by tenant (finish-gate / read-ledger)

The 'ended_without_testing' detector mirrors FinishGateTenant EXACTLY and is
INSTANCE-AGNOSTIC: it keys only on the agent's OWN tool calls (write tools +
execute_command matched against a generic build/test regex). It never references
gold patches, FAIL_TO_PASS, or any scoring test set.
"""
import glob, json, os, re, sys

# Ported verbatim from FinishGateTenant.DEFAULT_VERIFY_REGEX (generic build/test entrypoints).
VERIFY = re.compile(
    r"(\bmvn\b|\./mvnw|\bmvnw\b|\bgradle\b|\./gradlew|\bgradlew\b"
    r"|npm\s+(run\s+)?test|yarn\s+test|pnpm\s+(run\s+)?test"
    r"|\bpytest\b|py\.test|python\s+-m\s+pytest|\bgo\s+test\b|\bcargo\s+test\b"
    r"|\bctest\b|make\s+(test|check))", re.IGNORECASE)
WRITE_TOOLS = {"write_file", "edit_file"}
FAIL_MARK = re.compile(r"(读前写台账|Read-before-Write|先读|陈旧|越界|PathGuard|拒绝|denied|Error executing|错误：)", re.IGNORECASE)

ROOT = os.path.expanduser("~/swebench/ab-hooks")
IIDS = ["floci-io__floci-164","floci-io__floci-248","floci-io__floci-88",
        "floci-io__floci-299","floci-io__floci-352","grpc__grpc-java-12735"]
ARMS = [a for a in ["noedit","ctrl","hooks"]
        if os.path.isdir(os.path.join(ROOT, a))]


def load_trace(path):
    events = []
    if not os.path.exists(path):
        return events
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except Exception:
                pass
    return events


def cmd_of(args):
    """command string from a tool_call arguments blob (JSON string or dict)."""
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except Exception:
            return args
    if isinstance(args, dict):
        return args.get("command") or args.get("cmd") or json.dumps(args, ensure_ascii=False)
    return str(args)


def analyze_trace(events):
    """Return dict of process metrics. Sequence of (kind, ok) over tool calls in order."""
    # index tool_result content by tool_call_id for success inference
    res_by_id = {}
    for ev in events:
        if ev.get("type") == "tool_result":
            res_by_id[ev.get("tool_call_id")] = ev.get("content") or ""
    seq = []  # list of ('write'|'verify'|'other', ok_bool)
    writes = verifies = 0
    for ev in events:
        if ev.get("type") != "assistant":
            continue
        for tc in ev.get("tool_calls") or []:
            name = tc.get("name")
            rid = tc.get("id")
            content = res_by_id.get(rid, "")
            ok = not bool(FAIL_MARK.search(content or ""))
            if name in WRITE_TOOLS:
                seq.append(("write", ok))
                if ok:
                    writes += 1
            elif name == "execute_command":
                c = cmd_of(tc.get("arguments"))
                is_verify = bool(VERIFY.search(c or ""))
                seq.append(("verify" if is_verify else "other", ok))
                if is_verify and ok:
                    verifies += 1
            else:
                seq.append(("other", ok))
    # ended_without_testing: exists a successful write, and no successful verify AFTER the last successful write
    last_write = -1
    for i, (k, ok) in enumerate(seq):
        if k == "write" and ok:
            last_write = i
    ended_wo = False
    if last_write >= 0:
        ended_wo = not any(k == "verify" and ok for k, ok in seq[last_write+1:])
    finish_verified = (last_write >= 0) and not ended_wo
    turns = sum(1 for ev in events if ev.get("type") == "assistant")
    return dict(writes=writes, verifies=verifies, turns=turns,
                had_write=(last_write >= 0), ended_without_testing=ended_wo,
                finished_with_verification=finish_verified)


def analyze_audit(audit_dir):
    fg = rl = other = total = 0
    for path in glob.glob(os.path.join(audit_dir, "audit-*.jsonl")):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    e = json.loads(line)
                except Exception:
                    continue
                if e.get("outcome") != "hook_block":
                    continue
                total += 1
                tool = (e.get("tool") or "")
                if tool.startswith("Stop"):
                    fg += 1
                elif tool.startswith("PreToolUse"):
                    rl += 1
                else:
                    other += 1
    return dict(fg_block=fg, rl_block=rl, other_block=other, total_block=total)


def main():
    print(f"{'iid':28} {'arm':5} {'turns':>5} {'wr':>3} {'vf':>3} {'endWOtest':>9} {'fgBlk':>5} {'rlBlk':>5}")
    tot = {a: dict(endwo=0, hadwrite=0, finver=0, fg=0, rl=0, n=0) for a in ARMS}
    for arm in ARMS:
        for iid in IIDS:
            d = os.path.join(ROOT, arm, iid)
            tr = analyze_trace(load_trace(os.path.join(d, "trace.jsonl")))
            au = analyze_audit(os.path.join(d, "audit"))
            print(f"{iid:28} {arm:5} {tr['turns']:>5} {tr['writes']:>3} {tr['verifies']:>3} "
                  f"{('YES' if tr['ended_without_testing'] else 'no'):>9} {au['fg_block']:>5} {au['rl_block']:>5}")
            t = tot[arm]
            t['n'] += 1
            t['endwo'] += 1 if tr['ended_without_testing'] else 0
            t['hadwrite'] += 1 if tr['had_write'] else 0
            t['finver'] += 1 if tr['finished_with_verification'] else 0
            t['fg'] += au['fg_block']
            t['rl'] += au['rl_block']
    print("\n== TOTALS ==")
    for arm in ARMS:
        t = tot[arm]
        print(f"{arm:5} n={t['n']} had_write={t['hadwrite']} ended_without_testing={t['endwo']}/{t['n']} "
              f"finished_with_verification={t['finver']}/{t['n']} finish_gate_block={t['fg']} read_ledger_block={t['rl']}")


if __name__ == "__main__":
    main()
