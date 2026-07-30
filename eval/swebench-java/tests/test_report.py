import unittest
from lib.report import build_compare


class TestBuildCompare(unittest.TestCase):
    def _fixture(self):
        all_ids = ["g-1", "g-2", "d-3", "d-4"]
        cafecli_resolved = {"g-1", "d-3"}
        claude_resolved = {"g-1", "g-2"}
        repo_of = {"g-1": "google/gson", "g-2": "google/gson",
                   "d-3": "apache/dubbo", "d-4": "apache/dubbo"}
        summ = lambda iid, arm, turns, ran: {
            "instance_id": iid, "arm": arm, "turns": turns,
            "tool_calls_total": 5, "tool_breakdown": {"read": 2, "edit": 1, "exec": 1, "search": 1, "other": 0},
            "ran_build": ran, "ran_tests": ran, "files_read": 2, "files_edited": 1,
            "tokens": {"in": 100, "out": 20, "cache": 5}}
        cafecli_summaries = {i: summ(i, "cafecli", 4, True) for i in all_ids}
        claude_summaries = {i: summ(i, "claude", 6, False) for i in all_ids}
        return all_ids, cafecli_resolved, claude_resolved, repo_of, cafecli_summaries, claude_summaries

    def test_report_contains_rates_and_overlap(self):
        md = build_compare(*self._fixture())
        self.assertIn("Cafecli", md)
        self.assertIn("Claude Code", md)
        self.assertIn("- resolved: 2/4", md)
        self.assertIn("both resolved: 1", md)
        self.assertIn("only Cafecli: 1", md)
        self.assertIn("only Claude: 1", md)
        self.assertIn("both failed: 1", md)

    def test_per_repo_breakdown_present(self):
        md = build_compare(*self._fixture())
        self.assertIn("google/gson", md)
        self.assertIn("apache/dubbo", md)

    def test_model_label_is_configurable(self):
        md = build_compare(*self._fixture(), model_label="glm-5.1")
        self.assertIn("glm-5.1", md)


if __name__ == "__main__":
    unittest.main()
