import json
import unittest
from lib.predictions import to_jsonl


class TestToJsonl(unittest.TestCase):
    def test_fields_and_model_name(self):
        out = to_jsonl([{"instance_id": "google__gson-1", "patch": "diff x"}], "paicli-dsv4pro")
        obj = json.loads(out.strip())
        self.assertEqual(obj, {
            "instance_id": "google__gson-1",
            "model_name_or_path": "paicli-dsv4pro",
            "model_patch": "diff x",
        })

    def test_empty_patch_becomes_empty_string(self):
        out = to_jsonl([{"instance_id": "a-1", "patch": None}], "m")
        self.assertEqual(json.loads(out.strip())["model_patch"], "")

    def test_patch_with_newlines_and_quotes_roundtrips(self):
        patch = 'diff --git a\n+ "x"\n'
        out = to_jsonl([{"instance_id": "a-1", "patch": patch}], "m")
        self.assertEqual(json.loads(out.strip())["model_patch"], patch)

    def test_line_count_and_trailing_newline(self):
        out = to_jsonl([{"instance_id": "a", "patch": "p"},
                        {"instance_id": "b", "patch": "q"}], "m")
        self.assertEqual(out.count("\n"), 2)
        self.assertTrue(out.endswith("\n"))

    def test_empty_records(self):
        self.assertEqual(to_jsonl([], "m"), "")


if __name__ == "__main__":
    unittest.main()
