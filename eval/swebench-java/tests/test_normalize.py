import json
import unittest
from lib.normalize import classify, normalize_paicli, PAICLI_TOOL_CLASS


class TestClassify(unittest.TestCase):
    def test_paicli_tool_classes(self):
        self.assertEqual(classify("read_file", PAICLI_TOOL_CLASS), "read")
        self.assertEqual(classify("grep_code", PAICLI_TOOL_CLASS), "search")
        self.assertEqual(classify("write_file", PAICLI_TOOL_CLASS), "edit")
        self.assertEqual(classify("execute_command", PAICLI_TOOL_CLASS), "exec")
        self.assertEqual(classify("unknown_tool", PAICLI_TOOL_CLASS), "other")


class TestNormalizePaicli(unittest.TestCase):
    def _trace(self):
        return [
            json.dumps({"type": "system", "text": "sys"}),
            json.dumps({"type": "user", "text": "fix"}),
            json.dumps({"type": "assistant", "text": "", "tool_calls": [
                {"id": "c1", "name": "read_file", "arguments": "{\"path\":\"A.java\"}"}]}),
            json.dumps({"type": "tool_result", "tool_call_id": "c1", "content": "..."}),
            json.dumps({"type": "assistant", "text": "", "tool_calls": [
                {"id": "c2", "name": "execute_command", "arguments": "{\"command\":\"mvn -q test\"}"},
                {"id": "c3", "name": "write_file", "arguments": "{\"path\":\"A.java\"}"}]}),
            json.dumps({"type": "tool_result", "tool_call_id": "c2", "content": "BUILD SUCCESS"}),
            json.dumps({"type": "assistant", "text": "done"}),
            json.dumps({"type": "result", "provider": "deepseek", "model": "deepseek-v4-pro",
                        "wall_ms": 1000, "tokens": {"in": 100, "out": 20, "cache": 5}, "turns": 3}),
        ]

    def test_summary_fields(self):
        s = normalize_paicli(self._trace(), "google__gson-1")
        self.assertEqual(s["instance_id"], "google__gson-1")
        self.assertEqual(s["arm"], "paicli")
        self.assertEqual(s["turns"], 3)
        self.assertEqual(s["tool_calls_total"], 3)
        self.assertEqual(s["tool_breakdown"],
                         {"read": 1, "edit": 1, "exec": 1, "search": 0, "other": 0})
        self.assertTrue(s["ran_build"])
        self.assertTrue(s["ran_tests"])
        self.assertEqual(s["files_read"], 1)
        self.assertEqual(s["files_edited"], 1)
        self.assertEqual(s["tokens"], {"in": 100, "out": 20, "cache": 5})


    def test_empty_trace_returns_zero_state(self):
        s = normalize_paicli([], "x-1")
        self.assertEqual(s["turns"], 0)
        self.assertEqual(s["tool_calls_total"], 0)
        self.assertEqual(s["tokens"], {"in": 0, "out": 0, "cache": 0})
        self.assertFalse(s["ran_build"])
        self.assertFalse(s["ran_tests"])

    def test_malformed_line_is_skipped(self):
        trace = ["not json {", json.dumps(
            {"type": "result", "tokens": {"in": 5, "out": 1, "cache": 0}})]
        s = normalize_paicli(trace, "x-1")
        self.assertEqual(s["tokens"], {"in": 5, "out": 1, "cache": 0})

    def test_skiptests_not_counted_as_ran_tests(self):
        trace = [json.dumps({"type": "assistant", "tool_calls": [
            {"id": "c1", "name": "execute_command",
             "arguments": "{\"command\":\"mvn -q package -DskipTests\"}"}]})]
        s = normalize_paicli(trace, "x-1")
        self.assertTrue(s["ran_build"])
        self.assertFalse(s["ran_tests"])


if __name__ == "__main__":
    unittest.main()
