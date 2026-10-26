"""Test bridge: execute the repository's real evaluator, without a second normalizer."""
import importlib.util
import json
import pathlib
import sys

repo = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("cafecli_eval_normalize", repo / "eval/swebench-java/lib/normalize.py")
normalize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(normalize)
for trace in sys.argv[2:]:
    with open(trace, encoding="utf-8") as lines:
        print(json.dumps(normalize.normalize_cafecli(lines, "parity-fixture"), sort_keys=True))
