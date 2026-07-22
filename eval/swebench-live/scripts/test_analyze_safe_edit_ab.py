import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import analyze_safe_edit_ab as analyzer


class AnalyzeSafeEditAbTest(unittest.TestCase):
    def make_run(self, root: Path, arm: str, resolved: bool, *, blocked: bool = False) -> None:
        run = root / arm / "r1" / "example__repo-1"
        (run / "audit").mkdir(parents=True)
        (run / "report.json").write_text(json.dumps({
            "resolved": resolved,
            "FAIL_TO_PASS": {"success": ["T"] if resolved else [], "failure": [] if resolved else ["T"]},
            "PASS_TO_PASS": {"failure": []},
        }))
        events = [
            {"type": "assistant", "tool_calls": [
                {"name": "edit_file", "arguments": json.dumps({"path": "src/test/java/T.java"})},
                {"name": "execute_command", "arguments": json.dumps({"command": "./mvnw test"})},
            ]},
            {"type": "result", "wall_ms": 1200, "tokens": {"in": 10, "cache": 8, "out": 5}},
        ]
        (run / "trace.jsonl").write_text("\n".join(json.dumps(e) for e in events) + "\n")
        if blocked:
            (run / "audit" / "audit-2026-07-20.jsonl").write_text(json.dumps({
                "outcome": "deny", "approver": "policy", "reason": "测试目录保护: denied",
            }) + "\n")
            (run / "model.patch").write_text("diff --git a/src/main/java/A.java b/src/main/java/A.java\n")
        else:
            (run / "model.patch").write_text("diff --git a/src/test/java/T.java b/src/test/java/T.java\n")
            (run / "eval-example.log").write_text("<<<<<<PATCH FAILED TO APPLY CLEANLY\n")
        (run / "patch-lines.txt").write_text("1\n")
        (run / "exit.txt").write_text("exit=0\n")
        (run / "experiment.txt").write_text(
            f"safe_edit={'true' if arm == 'safe' else 'false'}\n"
            "hard_iterations=80\n"
            "model=deepseek-v4-pro\n"
            "jar_sha256=abc123\n"
        )

    def test_discovers_behavior_and_paired_result(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_run(root, "ctrl", False)
            self.make_run(root, "safe", True, blocked=True)

            runs = analyzer.discover(root)
            summary = analyzer.summarize(runs)

            self.assertEqual(2, len(runs))
            safe = next(r for r in runs if r.arm == "safe")
            ctrl = next(r for r in runs if r.arm == "ctrl")
            self.assertEqual(1, safe.safe_edit_blocks)
            self.assertEqual((), safe.submitted_test_paths)
            self.assertEqual(("src/test/java/T.java",), ctrl.submitted_test_paths)
            self.assertTrue(ctrl.patch_apply_failed)
            self.assertFalse(safe.patch_apply_failed)
            self.assertEqual(1, summary["paired"]["safe_only"])
            self.assertEqual(1.0, summary["mcnemar_exact_p"])
            self.assertEqual(1, summary["process_pairs"]["submitted_test_changes"]["ctrl_only"])
            self.assertEqual(1, summary["process_pairs"]["patch_apply_failures"]["ctrl_only"])
            self.assertEqual(["abc123"], summary["configuration"]["jar_sha256"])
            self.assertEqual([], summary["configuration"]["wrong_treatment"])
            self.assertEqual([], summary["configuration"]["nonzero_or_missing_solve_exit"])
            self.assertEqual(2, summary["arms"]["safe"]["total_uncached_input_tokens"])

    def test_wilson_and_no_discordant_pairs(self):
        lo, hi = analyzer.wilson(1, 2)
        self.assertLess(lo, 0.5)
        self.assertGreater(hi, 0.5)
        self.assertEqual(1.0, analyzer.exact_mcnemar(0, 0))


if __name__ == "__main__":
    unittest.main()
