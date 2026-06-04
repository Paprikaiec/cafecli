import os
import unittest
from lib.dataset import parse_rows, fetch_java_verified


class TestParseRows(unittest.TestCase):
    def test_maps_expected_fields(self):
        payload = {
            "num_rows_total": 2,
            "rows": [
                {"row": {"instance_id": "google__gson-1", "repo": "google/gson",
                         "problem_statement": "boom", "base_commit": "abc"}},
                {"row": {"instance_id": "apache__dubbo-2", "repo": "apache/dubbo",
                         "problem_statement": "bug", "base_commit": "def"}},
            ],
        }
        rows = parse_rows(payload)
        self.assertEqual(rows[0], {"instance_id": "google__gson-1", "repo": "google/gson",
                                   "problem_statement": "boom", "base_commit": "abc"})
        self.assertEqual(rows[1]["repo"], "apache/dubbo")

    def test_missing_optional_fields_default_empty(self):
        payload = {"rows": [{"row": {"instance_id": "a-1", "repo": "a"}}]}
        row = parse_rows(payload)[0]
        self.assertEqual(row["problem_statement"], "")
        self.assertEqual(row["base_commit"], "")

    def test_missing_identity_fields_become_none(self):
        payload = {"rows": [{"row": {"problem_statement": "x"}}]}
        row = parse_rows(payload)[0]
        self.assertIsNone(row["instance_id"])
        self.assertIsNone(row["repo"])

    def test_empty_payload(self):
        self.assertEqual(parse_rows({}), [])


class TestFetchIntegration(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("RUN_INTEGRATION"), "integration (set RUN_INTEGRATION=1)")
    def test_fetches_all_91_rows(self):
        rows = fetch_java_verified()
        self.assertEqual(len(rows), 91)
        self.assertTrue(all(r["instance_id"] and r["repo"] for r in rows))


if __name__ == "__main__":
    unittest.main()
