import json
import unittest
from lib.normalize import classify, normalize_cafecli, normalize_claude, CAFECLI_TOOL_CLASS


class TestClassify(unittest.TestCase):
    def test_cafecli_tool_classes(self):
        self.assertEqual(classify("read_file", CAFECLI_TOOL_CLASS), "read")
        self.assertEqual(classify("grep_code", CAFECLI_TOOL_CLASS), "search")
        self.assertEqual(classify("write_file", CAFECLI_TOOL_CLASS), "edit")
        self.assertEqual(classify("execute_command", CAFECLI_TOOL_CLASS), "exec")
        self.assertEqual(classify("unknown_tool", CAFECLI_TOOL_CLASS), "other")


class TestNormalizeCafecli(unittest.TestCase):
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
        s = normalize_cafecli(self._trace(), "google__gson-1")
        self.assertEqual(s["instance_id"], "google__gson-1")
        self.assertEqual(s["arm"], "cafecli")
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
        s = normalize_cafecli([], "x-1")
        self.assertEqual(s["turns"], 0)
        self.assertEqual(s["tool_calls_total"], 0)
        self.assertEqual(s["tokens"], {"in": 0, "out": 0, "cache": 0})
        self.assertFalse(s["ran_build"])
        self.assertFalse(s["ran_tests"])

    def test_malformed_line_is_skipped(self):
        trace = ["not json {", json.dumps(
            {"type": "result", "tokens": {"in": 5, "out": 1, "cache": 0}})]
        s = normalize_cafecli(trace, "x-1")
        self.assertEqual(s["tokens"], {"in": 5, "out": 1, "cache": 0})

    def test_skiptests_not_counted_as_ran_tests(self):
        trace = [json.dumps({"type": "assistant", "tool_calls": [
            {"id": "c1", "name": "execute_command",
             "arguments": "{\"command\":\"mvn -q package -DskipTests\"}"}]})]
        s = normalize_cafecli(trace, "x-1")
        self.assertTrue(s["ran_build"])
        self.assertFalse(s["ran_tests"])

    def test_test_compile_not_counted_as_ran_tests(self):
        trace = [json.dumps({"type": "assistant", "tool_calls": [
            {"id": "c1", "name": "execute_command",
             "arguments": "{\"command\":\"mvn -q test-compile\"}"}]})]
        s = normalize_cafecli(trace, "x-1")
        self.assertTrue(s["ran_build"])
        self.assertFalse(s["ran_tests"])


class TestNormalizeClaude(unittest.TestCase):
    def _trace(self):
        return [
            json.dumps({"type": "system", "subtype": "init", "model": "deepseek-v4-pro"}),
            json.dumps({"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "u1", "name": "Read", "input": {"file_path": "A.java"}}],
                "usage": {"input_tokens": 50, "output_tokens": 10, "cache_read_input_tokens": 3}}}),
            json.dumps({"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "u1", "content": "..."}]}}),
            json.dumps({"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "u2", "name": "Bash", "input": {"command": "mvn -q test"}},
                {"type": "tool_use", "id": "u3", "name": "Edit", "input": {"file_path": "A.java"}}],
                "usage": {"input_tokens": 60, "output_tokens": 12, "cache_read_input_tokens": 40}}}),
            json.dumps({"type": "assistant", "message": {"content": [
                {"type": "text", "text": "done"}],
                "usage": {"input_tokens": 5, "output_tokens": 4, "cache_read_input_tokens": 0}}}),
            json.dumps({"type": "result", "subtype": "success", "total_cost_usd": 0.01}),
        ]

    def test_summary_fields(self):
        s = normalize_claude(self._trace(), "apache__dubbo-3")
        self.assertEqual(s["arm"], "claude")
        self.assertEqual(s["turns"], 3)
        self.assertEqual(s["tool_calls_total"], 3)
        self.assertEqual(s["tool_breakdown"],
                         {"read": 1, "edit": 1, "exec": 1, "search": 0, "other": 0})
        self.assertTrue(s["ran_build"])
        self.assertTrue(s["ran_tests"])
        self.assertEqual(s["files_read"], 1)
        self.assertEqual(s["files_edited"], 1)
        self.assertEqual(s["tokens"], {"in": 115, "out": 26, "cache": 43})


    def test_empty_trace_returns_zero_state(self):
        s = normalize_claude([], "x-1")
        self.assertEqual(s["turns"], 0)
        self.assertEqual(s["tool_calls_total"], 0)
        self.assertEqual(s["tokens"], {"in": 0, "out": 0, "cache": 0})


if __name__ == "__main__":
    unittest.main()
