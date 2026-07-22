#!/usr/bin/env python3
"""Analyze repeated PaiCLI safe-edit A/B artifacts.

The analysis is instance-agnostic. Gold data is read only from official report.json
outcomes; safe-edit behavior is measured from agent tool calls, audit entries, and
the submitted model.patch.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable


VERIFY = re.compile(
    r"(\bmvn\b|\./mvnw|\bmvnw\b|\bgradle\b|\./gradlew|\bgradlew\b"
    r"|npm\s+(run\s+)?test|yarn\s+test|pnpm\s+(run\s+)?test"
    r"|\bpytest\b|py\.test|python\s+-m\s+pytest|\bgo\s+test\b|\bcargo\s+test\b"
    r"|\bctest\b|make\s+(test|check))",
    re.IGNORECASE,
)
WRITE_TOOLS = {"write_file", "edit_file"}
ROOT_TEST = re.compile(r"^(?:\./)?src/test(?:/|$)")
ANY_TEST = re.compile(
    r"(?:^|/)(?:src/(?:test|integrationTest)|test|tests|compatibility-tests)(?:/|$)"
)
PATCH_PATH = re.compile(r"^diff --git a/(.+?) b/(.+)$")
PATCH_APPLY_FAILURE = re.compile(
    r"PATCH FAILED TO APPLY CLEANLY|already exists in working directory|patch does not apply",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class RunMetrics:
    instance_id: str
    repeat: int
    arm: str
    resolved: bool | None
    f2p_pass: int
    f2p_fail: int
    p2p_fail: int
    turns: int
    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    wall_ms: int
    write_attempts: int
    root_test_write_attempts: int
    any_test_write_attempts: int
    safe_edit_blocks: int
    verify_attempts: int
    submitted_test_paths: tuple[str, ...]
    patch_apply_failed: bool
    patch_lines: int
    solve_exit: int | None
    configured_safe_edit: bool | None
    hard_iterations: int | None
    model: str
    jar_sha256: str


def _read_json(path: Path) -> dict[str, Any]:
    try:
        with path.open() as f:
            value = json.load(f)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def _iter_jsonl(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
    for path in paths:
        try:
            with path.open() as f:
                for raw in f:
                    try:
                        value = json.loads(raw)
                    except ValueError:
                        continue
                    if isinstance(value, dict):
                        yield value
        except OSError:
            continue


def _args(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except ValueError:
            return {}
    return {}


def _report_metrics(report: dict[str, Any]) -> tuple[bool | None, int, int, int]:
    if not report:
        return None, 0, 0, 0
    f2p = report.get("FAIL_TO_PASS") or {}
    p2p = report.get("PASS_TO_PASS") or {}
    return (
        bool(report.get("resolved")),
        len(f2p.get("success") or []),
        len(f2p.get("failure") or []),
        len(p2p.get("failure") or []),
    )


def _key_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(errors="replace").splitlines()
    except OSError:
        return values
    for line in lines:
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values


def _optional_int(value: str | None) -> int | None:
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None


def _patch_test_paths(text: str) -> tuple[str, ...]:
    paths: set[str] = set()
    for line in text.splitlines():
        match = PATCH_PATH.match(line)
        if not match:
            continue
        for path in match.groups():
            if ANY_TEST.search(path):
                paths.add(path)
    return tuple(sorted(paths))


def analyze_run(run_dir: Path, arm: str, repeat: int, iid: str) -> RunMetrics:
    report = _read_json(run_dir / "report.json")
    resolved, f2p_pass, f2p_fail, p2p_fail = _report_metrics(report)
    turns = input_tokens = cached_input_tokens = output_tokens = wall_ms = 0
    write_attempts = root_test_attempts = any_test_attempts = verify_attempts = 0
    for event in _iter_jsonl([run_dir / "trace.jsonl"]):
        if event.get("type") == "assistant":
            turns += 1
            for call in event.get("tool_calls") or []:
                name = call.get("name")
                args = _args(call.get("arguments"))
                if name in WRITE_TOOLS:
                    write_attempts += 1
                    path = str(args.get("path") or args.get("file_path") or "")
                    root_test_attempts += int(bool(ROOT_TEST.search(path)))
                    any_test_attempts += int(bool(ANY_TEST.search(path)))
                elif name == "execute_command":
                    command = str(args.get("command") or args.get("cmd") or "")
                    verify_attempts += int(bool(VERIFY.search(command)))
        elif event.get("type") == "result":
            tokens = event.get("tokens") or {}
            input_tokens = int(tokens.get("in") or 0)
            cached_input_tokens = int(tokens.get("cache") or 0)
            output_tokens = int(tokens.get("out") or 0)
            wall_ms = int(event.get("wall_ms") or 0)

    safe_edit_blocks = 0
    audit_paths = [Path(p) for p in glob.glob(str(run_dir / "audit" / "audit-*.jsonl"))]
    for event in _iter_jsonl(audit_paths):
        if (
            event.get("outcome") == "deny"
            and event.get("approver") == "policy"
            and "测试目录保护" in str(event.get("reason") or "")
        ):
            safe_edit_blocks += 1

    try:
        patch = (run_dir / "model.patch").read_text(errors="replace")
    except OSError:
        patch = ""
    try:
        patch_lines = int((run_dir / "patch-lines.txt").read_text().strip() or 0)
    except (OSError, ValueError):
        patch_lines = len(patch.splitlines())
    eval_log = ""
    for path in run_dir.glob("eval-*.log"):
        try:
            eval_log += path.read_text(errors="replace")
        except OSError:
            pass
    experiment = _key_values(run_dir / "experiment.txt")
    exit_values = _key_values(run_dir / "exit.txt")
    configured = experiment.get("safe_edit")
    configured_safe_edit = None if configured not in {"true", "false"} else configured == "true"

    return RunMetrics(
        instance_id=iid,
        repeat=repeat,
        arm=arm,
        resolved=resolved,
        f2p_pass=f2p_pass,
        f2p_fail=f2p_fail,
        p2p_fail=p2p_fail,
        turns=turns,
        input_tokens=input_tokens,
        cached_input_tokens=cached_input_tokens,
        output_tokens=output_tokens,
        wall_ms=wall_ms,
        write_attempts=write_attempts,
        root_test_write_attempts=root_test_attempts,
        any_test_write_attempts=any_test_attempts,
        safe_edit_blocks=safe_edit_blocks,
        verify_attempts=verify_attempts,
        submitted_test_paths=_patch_test_paths(patch),
        patch_apply_failed=bool(PATCH_APPLY_FAILURE.search(eval_log)),
        patch_lines=patch_lines,
        solve_exit=_optional_int(exit_values.get("exit")),
        configured_safe_edit=configured_safe_edit,
        hard_iterations=_optional_int(experiment.get("hard_iterations")),
        model=experiment.get("model", ""),
        jar_sha256=experiment.get("jar_sha256", ""),
    )


def discover(root: Path) -> list[RunMetrics]:
    runs: list[RunMetrics] = []
    for arm in ("ctrl", "safe"):
        arm_dir = root / arm
        if not arm_dir.is_dir():
            continue
        for repeat_dir in sorted(arm_dir.glob("r*")):
            try:
                repeat = int(repeat_dir.name[1:])
            except ValueError:
                continue
            for iid_dir in sorted(p for p in repeat_dir.iterdir() if p.is_dir()):
                runs.append(analyze_run(iid_dir, arm, repeat, iid_dir.name))
    return runs


def wilson(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total == 0:
        return 0.0, 0.0
    p = successes / total
    denom = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denom
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denom
    return max(0.0, center - margin), min(1.0, center + margin)


def exact_mcnemar(ctrl_only: int, safe_only: int) -> float:
    discordant = ctrl_only + safe_only
    if discordant == 0:
        return 1.0
    tail = sum(math.comb(discordant, k) for k in range(0, min(ctrl_only, safe_only) + 1)) / (2**discordant)
    return min(1.0, 2 * tail)


def summarize(runs: list[RunMetrics]) -> dict[str, Any]:
    by_arm: dict[str, list[RunMetrics]] = defaultdict(list)
    by_pair: dict[tuple[int, str], dict[str, RunMetrics]] = defaultdict(dict)
    for run in runs:
        by_arm[run.arm].append(run)
        by_pair[(run.repeat, run.instance_id)][run.arm] = run

    arms: dict[str, Any] = {}
    for arm in ("ctrl", "safe"):
        valid = [r for r in by_arm.get(arm, []) if r.resolved is not None]
        passed = sum(r.resolved is True for r in valid)
        lo, hi = wilson(passed, len(valid))
        arms[arm] = {
            "runs": len(valid),
            "resolved": passed,
            "rate": passed / len(valid) if valid else 0.0,
            "wilson95": [lo, hi],
            "safe_edit_blocks": sum(r.safe_edit_blocks for r in valid),
            "test_write_attempts": sum(r.any_test_write_attempts for r in valid),
            "runs_submitting_test_changes": sum(bool(r.submitted_test_paths) for r in valid),
            "median_turns": _median([r.turns for r in valid]),
            "median_wall_ms": _median([r.wall_ms for r in valid]),
            "total_turns": sum(r.turns for r in valid),
            "total_wall_ms": sum(r.wall_ms for r in valid),
            "total_patch_lines": sum(r.patch_lines for r in valid),
            "total_verify_attempts": sum(r.verify_attempts for r in valid),
            "total_input_tokens": sum(r.input_tokens for r in valid),
            "total_cached_input_tokens": sum(r.cached_input_tokens for r in valid),
            "total_uncached_input_tokens": sum(
                max(0, r.input_tokens - r.cached_input_tokens) for r in valid
            ),
            "total_output_tokens": sum(r.output_tokens for r in valid),
        }

    paired = Counter()
    patch_pairs = Counter()
    apply_failure_pairs = Counter()
    missing_pairs: list[str] = []
    for (repeat, iid), pair in sorted(by_pair.items()):
        ctrl, safe = pair.get("ctrl"), pair.get("safe")
        if ctrl is None or safe is None or ctrl.resolved is None or safe.resolved is None:
            missing_pairs.append(f"r{repeat}/{iid}")
            continue
        if ctrl.resolved and safe.resolved:
            paired["both_pass"] += 1
        elif ctrl.resolved:
            paired["ctrl_only"] += 1
        elif safe.resolved:
            paired["safe_only"] += 1
        else:
            paired["both_fail"] += 1
        _classify_pair(bool(ctrl.submitted_test_paths), bool(safe.submitted_test_paths), patch_pairs)
        _classify_pair(ctrl.patch_apply_failed, safe.patch_apply_failed, apply_failure_pairs)
    paired["complete"] = sum(paired[k] for k in ("both_pass", "ctrl_only", "safe_only", "both_fail"))
    patch_pairs["complete"] = sum(patch_pairs[k] for k in ("both", "ctrl_only", "safe_only", "neither"))
    apply_failure_pairs["complete"] = sum(
        apply_failure_pairs[k] for k in ("both", "ctrl_only", "safe_only", "neither")
    )

    return {
        "arms": arms,
        "paired": dict(paired),
        "mcnemar_exact_p": exact_mcnemar(paired["ctrl_only"], paired["safe_only"]),
        "process_pairs": {
            "submitted_test_changes": dict(patch_pairs),
            "submitted_test_changes_mcnemar_exact_p": exact_mcnemar(
                patch_pairs["ctrl_only"], patch_pairs["safe_only"]
            ),
            "patch_apply_failures": dict(apply_failure_pairs),
            "patch_apply_failures_mcnemar_exact_p": exact_mcnemar(
                apply_failure_pairs["ctrl_only"], apply_failure_pairs["safe_only"]
            ),
        },
        "missing_pairs": missing_pairs,
        "configuration": {
            "jar_sha256": sorted({r.jar_sha256 for r in runs if r.jar_sha256}),
            "models": sorted({r.model for r in runs if r.model}),
            "hard_iterations": sorted({r.hard_iterations for r in runs if r.hard_iterations is not None}),
            "wrong_treatment": [
                f"r{r.repeat}/{r.instance_id}/{r.arm}"
                for r in runs
                if r.configured_safe_edit is None or r.configured_safe_edit != (r.arm == "safe")
            ],
            "nonzero_or_missing_solve_exit": [
                f"r{r.repeat}/{r.instance_id}/{r.arm}:{r.solve_exit}"
                for r in runs
                if r.solve_exit != 0
            ],
        },
    }


def _classify_pair(ctrl: bool, safe: bool, counts: Counter[str]) -> None:
    if ctrl and safe:
        counts["both"] += 1
    elif ctrl:
        counts["ctrl_only"] += 1
    elif safe:
        counts["safe_only"] += 1
    else:
        counts["neither"] += 1


def _median(values: list[int]) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return float(ordered[middle])
    return (ordered[middle - 1] + ordered[middle]) / 2


def render(runs: list[RunMetrics], summary: dict[str, Any]) -> str:
    lines = [
        "instance_id                 rep arm   result  F2P    P2Pfail turns testTry blocks patchTest applyFail",
        "--------------------------- --- ----- ------- ------ ------- ----- ------- ------ --------- ---------",
    ]
    for run in sorted(runs, key=lambda r: (r.repeat, r.instance_id, r.arm)):
        result = "PASS" if run.resolved else ("FAIL" if run.resolved is False else "MISSING")
        lines.append(
            f"{run.instance_id:27} {run.repeat:>3} {run.arm:5} {result:7} "
            f"{run.f2p_pass}/{run.f2p_fail:<4} {run.p2p_fail:>7} {run.turns:>5} "
            f"{run.any_test_write_attempts:>7} {run.safe_edit_blocks:>6} "
            f"{('yes' if run.submitted_test_paths else 'no'):>9} "
            f"{('yes' if run.patch_apply_failed else 'no'):>9}"
        )
    lines.append("\n== ARM TOTALS ==")
    for arm in ("ctrl", "safe"):
        data = summary["arms"][arm]
        lo, hi = data["wilson95"]
        lines.append(
            f"{arm:5}: {data['resolved']}/{data['runs']} = {data['rate']:.1%} "
            f"(Wilson 95% {lo:.1%}–{hi:.1%}); blocks={data['safe_edit_blocks']}; "
            f"test-write-attempts={data['test_write_attempts']}; "
            f"submitted-test-change-runs={data['runs_submitting_test_changes']}; "
            f"median-turns={data['median_turns']:.1f}; median-wall={data['median_wall_ms']/1000:.1f}s; "
            f"patch-lines={data['total_patch_lines']}; verify-calls={data['total_verify_attempts']}; "
            f"uncached-input={data['total_uncached_input_tokens']}; output={data['total_output_tokens']}"
        )
    paired = summary["paired"]
    lines.extend(
        [
            "\n== PAIRED OUTCOMES ==",
            f"complete={paired.get('complete', 0)} both_pass={paired.get('both_pass', 0)} "
            f"safe_only={paired.get('safe_only', 0)} ctrl_only={paired.get('ctrl_only', 0)} "
            f"both_fail={paired.get('both_fail', 0)} exact_McNemar_p={summary['mcnemar_exact_p']:.4f}",
        ]
    )
    if summary["missing_pairs"]:
        lines.append("missing/incomplete: " + ", ".join(summary["missing_pairs"]))
    process = summary["process_pairs"]
    test_changes = process["submitted_test_changes"]
    apply_failures = process["patch_apply_failures"]
    lines.extend(
        [
            "\n== PAIRED PROCESS OUTCOMES ==",
            "submitted test changes: "
            f"ctrl_only={test_changes.get('ctrl_only', 0)} safe_only={test_changes.get('safe_only', 0)} "
            f"both={test_changes.get('both', 0)} neither={test_changes.get('neither', 0)} "
            f"exact_McNemar_p={process['submitted_test_changes_mcnemar_exact_p']:.4f}",
            "patch apply failures: "
            f"ctrl_only={apply_failures.get('ctrl_only', 0)} safe_only={apply_failures.get('safe_only', 0)} "
            f"both={apply_failures.get('both', 0)} neither={apply_failures.get('neither', 0)} "
            f"exact_McNemar_p={process['patch_apply_failures_mcnemar_exact_p']:.4f}",
        ]
    )
    config = summary["configuration"]
    lines.extend(
        [
            "\n== CONFIGURATION CHECK ==",
            f"jar_sha256={','.join(config['jar_sha256']) or 'missing'}",
            f"models={','.join(config['models']) or 'missing'} hard_iterations={config['hard_iterations']}",
            f"wrong_treatment={config['wrong_treatment'] or 'none'}",
            f"nonzero_or_missing_solve_exit={config['nonzero_or_missing_solve_exit'] or 'none'}",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.home() / "swebench" / "safe-edit-ab")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    runs = discover(args.root)
    summary = summarize(runs)
    print(render(runs, summary))
    if args.json_out:
        payload = {"runs": [asdict(r) for r in runs], "summary": summary}
        args.json_out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
