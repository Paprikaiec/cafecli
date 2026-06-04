# SWE-bench-java 评测 Part 2（编排流水线）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 SWE-bench-java 评测流水线的本地部分：L1 五个纯逻辑 Python 模块（数据集加载/分层抽样/predictions 汇总/trace 归一/对比报告）用 stdlib `unittest` TDD，外加 L2 集成脚本骨架（命令确切但本地不执行，留云机验证）。

**Architecture:** 纯 Python 3 标准库，零第三方依赖。`eval/swebench-java/lib/` 放可独立测试的纯函数模块，`tests/` 放 unittest。L2 脚本（`solve_one.sh`/`run_eval.py`/`score.sh`）调用 Docker + multi-swe-bench-env harness，写成可读骨架。

**Tech Stack:** Python 3（`unittest`/`urllib`/`json`/`random`/`collections`）、bash、jq。

**测试运行约定（连字符目录无法做包名，统一用 discover + PYTHONPATH）：**
- 单文件：`PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_sampling.py" -v`
- 全部：`PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -v`
- 含 integration：`RUN_INTEGRATION=1 PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -v`

测试文件统一 `from lib.<module> import ...`（PYTHONPATH 提供 `lib`）。

---

## File Structure

**L1 模块（`eval/swebench-java/lib/`）：**
- `__init__.py` — 空，使 `lib` 成包
- `sampling.py` — `stratified_sample(instances, k, seed)`
- `predictions.py` — `to_jsonl(records, model_name)`
- `dataset.py` — `parse_rows(payload)` + `fetch_java_verified(page_size)`
- `normalize.py` — 工具类别表 + `normalize_paicli` / `normalize_claude` + 私有 helper
- `report.py` — `build_compare(...)`

**测试（`eval/swebench-java/tests/`）：** 每个模块一个 `test_*.py`

**L2 骨架（`eval/swebench-java/`）：** `solve_one.sh`、`run_eval.py`、`score.sh`、`prompt_template.md`、`README.md`

---

## Task 1: 骨架 + 分层抽样 `sampling.py`

**Files:**
- Create: `eval/swebench-java/lib/__init__.py`（空文件）
- Create: `eval/swebench-java/lib/sampling.py`
- Test: `eval/swebench-java/tests/test_sampling.py`

- [ ] **Step 1: 写失败测试**

```python
# eval/swebench-java/tests/test_sampling.py
import unittest
from lib.sampling import stratified_sample


def _instances(counts):
    out = []
    for repo, n in counts.items():
        for i in range(n):
            out.append({"instance_id": f"{repo}-{i}", "repo": repo})
    return out


class TestStratifiedSample(unittest.TestCase):
    def test_total_equals_k(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertEqual(len(stratified_sample(insts, k=25, seed=42)), 25)

    def test_deterministic_same_seed(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertEqual(stratified_sample(insts, k=25, seed=42),
                         stratified_sample(insts, k=25, seed=42))

    def test_different_seed_differs(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertNotEqual(stratified_sample(insts, k=25, seed=1),
                            stratified_sample(insts, k=25, seed=2))

    def test_proportional_allocation(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        picked = stratified_sample(insts, k=10, seed=42)
        by_repo = {}
        for iid in picked:
            by_repo[iid.rsplit("-", 1)[0]] = by_repo.get(iid.rsplit("-", 1)[0], 0) + 1
        self.assertEqual(by_repo, {"a": 1, "b": 2, "c": 7})

    def test_k_ge_total_returns_all(self):
        insts = _instances({"a": 3, "b": 2})
        self.assertEqual(len(stratified_sample(insts, k=99, seed=42)), 5)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_sampling.py" -v`
Expected: FAIL/ERROR（`lib.sampling` 不存在）。

- [ ] **Step 3: 写实现**

```python
# eval/swebench-java/lib/__init__.py
# (空文件)
```

```python
# eval/swebench-java/lib/sampling.py
import random


def stratified_sample(instances, k=25, seed=42):
    """按 repo 分层随机抽样，返回 k 个 instance_id（确定性，升序）。

    instances: list of {"instance_id": str, "repo": str}
    名额按各 repo 占比用最大余数法分配；每组用 random.Random(seed) 抽样。
    k >= 总数时返回全部。
    """
    by_repo = {}
    for inst in instances:
        by_repo.setdefault(inst["repo"], []).append(inst["instance_id"])
    for repo in by_repo:
        by_repo[repo].sort()

    total = sum(len(v) for v in by_repo.values())
    if k >= total:
        return sorted(iid for pool in by_repo.values() for iid in pool)

    repos = sorted(by_repo)
    quotas = {}
    remainders = []
    allocated = 0
    for repo in repos:
        exact = len(by_repo[repo]) * k / total
        base = int(exact)
        quotas[repo] = base
        allocated += base
        remainders.append((exact - base, repo))
    remainders.sort(reverse=True)
    for i in range(k - allocated):
        quotas[remainders[i][1]] += 1

    rng = random.Random(seed)
    selected = []
    for repo in repos:
        pool = by_repo[repo]
        selected.extend(rng.sample(pool, min(quotas[repo], len(pool))))
    return sorted(selected)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_sampling.py" -v`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/__init__.py eval/swebench-java/lib/sampling.py eval/swebench-java/tests/test_sampling.py
git commit -m "$(cat <<'EOF'
Add stratified sampling for SWE-bench-java eval

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: predictions 汇总 `predictions.py`

**Files:**
- Create: `eval/swebench-java/lib/predictions.py`
- Test: `eval/swebench-java/tests/test_predictions.py`

- [ ] **Step 1: 写失败测试**

```python
# eval/swebench-java/tests/test_predictions.py
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_predictions.py" -v`
Expected: FAIL（`lib.predictions` 不存在）。

- [ ] **Step 3: 写实现**

```python
# eval/swebench-java/lib/predictions.py
import json


def to_jsonl(records, model_name):
    """records: list of {"instance_id", "patch"}. 返回 SWE-bench predictions JSONL。

    每行: {"instance_id","model_name_or_path","model_patch"}。
    patch 为 None/缺省时写空串（计未解决）。
    """
    lines = []
    for r in records:
        obj = {
            "instance_id": r["instance_id"],
            "model_name_or_path": model_name,
            "model_patch": r.get("patch") or "",
        }
        lines.append(json.dumps(obj, ensure_ascii=False))
    return "".join(line + "\n" for line in lines)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_predictions.py" -v`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/predictions.py eval/swebench-java/tests/test_predictions.py
git commit -m "$(cat <<'EOF'
Add predictions JSONL serializer for SWE-bench-java eval

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 数据集加载 `dataset.py`（解析 mock 测 + integration 真连测）

**Files:**
- Create: `eval/swebench-java/lib/dataset.py`
- Test: `eval/swebench-java/tests/test_dataset.py`

- [ ] **Step 1: 写失败测试**

```python
# eval/swebench-java/tests/test_dataset.py
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_dataset.py" -v`
Expected: FAIL（`lib.dataset` 不存在）；integration 测试 skipped。

- [ ] **Step 3: 写实现**

```python
# eval/swebench-java/lib/dataset.py
import json
import urllib.parse
import urllib.request

_BASE = "https://datasets-server.huggingface.co/rows"
_DATASET = "Daoguang/Multi-SWE-bench"
_CONFIG = "default"
_SPLIT = "java_verified"


def parse_rows(payload):
    """datasets-server /rows 响应 → [{instance_id, repo, problem_statement, base_commit}]。"""
    out = []
    for item in payload.get("rows", []):
        row = item.get("row", {})
        out.append({
            "instance_id": row.get("instance_id"),
            "repo": row.get("repo"),
            "problem_statement": row.get("problem_statement", "") or "",
            "base_commit": row.get("base_commit", "") or "",
        })
    return out


def _fetch_page(offset, length):
    params = urllib.parse.urlencode({
        "dataset": _DATASET, "config": _CONFIG, "split": _SPLIT,
        "offset": offset, "length": length,
    })
    with urllib.request.urlopen(f"{_BASE}?{params}", timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_java_verified(page_size=100):
    """分页拉取全部 java_verified 行（标准库 urllib，无第三方依赖）。"""
    first = _fetch_page(0, page_size)
    rows = parse_rows(first)
    total = first.get("num_rows_total", len(rows))
    offset = page_size
    while offset < total:
        rows.extend(parse_rows(_fetch_page(offset, page_size)))
        offset += page_size
    return rows
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_dataset.py" -v`
Expected: PASS（3 个 parse 测试 PASS，1 个 integration SKIPPED）。
可选真连验证：`RUN_INTEGRATION=1 PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_dataset.py" -v`（需要网络，断言 91 行）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/dataset.py eval/swebench-java/tests/test_dataset.py
git commit -m "$(cat <<'EOF'
Add datasets-server loader for SWE-bench-java (parse + integration)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: trace 归一（工具表 + helper + `normalize_paicli`）

**Files:**
- Create: `eval/swebench-java/lib/normalize.py`
- Test: `eval/swebench-java/tests/test_normalize.py`

PaiCLI `AgentTraceWriter` 的 NDJSON 每行：`{type:"system"/"user",text}`、`{type:"assistant",text?,reasoning?,tool_calls:[{id,name,arguments}]}`、`{type:"tool_result",tool_call_id,content}`、末行 `{type:"result",provider,model,wall_ms,tokens:{in,out,cache},turns}`。

- [ ] **Step 1: 写失败测试**

```python
# eval/swebench-java/tests/test_normalize.py
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


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_normalize.py" -v`
Expected: FAIL（`lib.normalize` 不存在）。

- [ ] **Step 3: 写实现**

```python
# eval/swebench-java/lib/normalize.py
import json

PAICLI_TOOL_CLASS = {
    "read_file": "read", "list_dir": "read",
    "glob_files": "search", "grep_code": "search", "search_code": "search",
    "write_file": "edit", "create_project": "edit",
    "execute_command": "exec",
    "web_search": "other", "web_fetch": "other", "revert_turn": "other",
}

CLAUDE_TOOL_CLASS = {
    "Read": "read", "LS": "read",
    "Glob": "search", "Grep": "search",
    "Write": "edit", "Edit": "edit", "MultiEdit": "edit",
    "Bash": "exec",
    "WebSearch": "other", "WebFetch": "other", "Task": "other", "TodoWrite": "other",
}

_CLASSES = ("read", "edit", "exec", "search", "other")


def classify(name, table):
    return table.get(name, "other")


def _safe_obj(value):
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            obj = json.loads(value)
            return obj if isinstance(obj, dict) else {}
        except (ValueError, TypeError):
            return {}
    return {}


def _track_file(cls, args, files_read, files_edited):
    path = args.get("path") or args.get("file_path")
    if not path:
        return
    if cls == "read":
        files_read.add(path)
    elif cls == "edit":
        files_edited.add(path)


def _is_build_cmd(cmd):
    c = cmd.lower()
    return any(t in c for t in ("mvn", "gradle", "gradlew"))


def _is_test_cmd(cmd):
    c = cmd.lower()
    return "test" in c and _is_build_cmd(cmd)


def _new_breakdown():
    return {c: 0 for c in _CLASSES}


def _summary(instance_id, arm, turns, tb, total, ran_build, ran_tests, fr, fe, tokens):
    return {
        "instance_id": instance_id, "arm": arm, "turns": turns,
        "tool_calls_total": total, "tool_breakdown": tb,
        "ran_build": ran_build, "ran_tests": ran_tests,
        "files_read": len(fr), "files_edited": len(fe), "tokens": tokens,
    }


def normalize_paicli(lines, instance_id):
    """解析 PaiCLI AgentTraceWriter NDJSON → 统一行为摘要。"""
    turns = 0
    tb = _new_breakdown()
    total = 0
    fr, fe = set(), set()
    ran_build = ran_tests = False
    tokens = {"in": 0, "out": 0, "cache": 0}
    for line in lines:
        line = line.strip()
        if not line:
            continue
        ev = json.loads(line)
        t = ev.get("type")
        if t == "assistant":
            turns += 1
            for tc in ev.get("tool_calls", []) or []:
                cls = classify(tc.get("name", ""), PAICLI_TOOL_CLASS)
                tb[cls] += 1
                total += 1
                args = _safe_obj(tc.get("arguments"))
                _track_file(cls, args, fr, fe)
                if cls == "exec":
                    cmd = str(args.get("command", ""))
                    ran_build = ran_build or _is_build_cmd(cmd)
                    ran_tests = ran_tests or _is_test_cmd(cmd)
        elif t == "result":
            tk = ev.get("tokens", {}) or {}
            tokens = {"in": tk.get("in", 0), "out": tk.get("out", 0), "cache": tk.get("cache", 0)}
    return _summary(instance_id, "paicli", turns, tb, total, ran_build, ran_tests, fr, fe, tokens)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_normalize.py" -v`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/normalize.py eval/swebench-java/tests/test_normalize.py
git commit -m "$(cat <<'EOF'
Add trace normalizer: tool classes + normalize_paicli

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `normalize_claude`（Claude Code stream-json）

**Files:**
- Modify: `eval/swebench-java/lib/normalize.py`（追加函数）
- Modify: `eval/swebench-java/tests/test_normalize.py`（追加测试类）

Claude Code stream-json 每行 `{type:...}`：`assistant` = `{type:"assistant",message:{content:[{type:"text",text}|{type:"tool_use",id,name,input}],usage:{input_tokens,output_tokens,cache_read_input_tokens}}}`；`user` = `{type:"user",message:{content:[{type:"tool_result",tool_use_id,content}]}}`；`result` = `{type:"result",...}`。**真实字段细节留云机用真实 trace 复核。**

- [ ] **Step 1: 写失败测试（追加到 test_normalize.py 末尾，`if __name__` 之前）**

```python
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
        from lib.normalize import normalize_claude
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_normalize.py" -v`
Expected: FAIL（`normalize_claude` 不存在）。

- [ ] **Step 3: 写实现（追加到 normalize.py 末尾）**

```python
def normalize_claude(lines, instance_id):
    """解析 Claude Code stream-json NDJSON → 统一行为摘要。

    token 按各 assistant 事件的 message.usage 累加（in=input_tokens,
    out=output_tokens, cache=cache_read_input_tokens）。
    """
    turns = 0
    tb = _new_breakdown()
    total = 0
    fr, fe = set(), set()
    ran_build = ran_tests = False
    tokens = {"in": 0, "out": 0, "cache": 0}
    for line in lines:
        line = line.strip()
        if not line:
            continue
        ev = json.loads(line)
        if ev.get("type") != "assistant":
            continue
        turns += 1
        msg = ev.get("message", {}) or {}
        for block in msg.get("content", []) or []:
            if block.get("type") != "tool_use":
                continue
            cls = classify(block.get("name", ""), CLAUDE_TOOL_CLASS)
            tb[cls] += 1
            total += 1
            inp = _safe_obj(block.get("input"))
            _track_file(cls, inp, fr, fe)
            if cls == "exec":
                cmd = str(inp.get("command", ""))
                ran_build = ran_build or _is_build_cmd(cmd)
                ran_tests = ran_tests or _is_test_cmd(cmd)
        usage = msg.get("usage", {}) or {}
        tokens["in"] += usage.get("input_tokens", 0)
        tokens["out"] += usage.get("output_tokens", 0)
        tokens["cache"] += usage.get("cache_read_input_tokens", 0)
    return _summary(instance_id, "claude", turns, tb, total, ran_build, ran_tests, fr, fe, tokens)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_normalize.py" -v`
Expected: PASS（3 个测试：classify + paicli + claude）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/normalize.py eval/swebench-java/tests/test_normalize.py
git commit -m "$(cat <<'EOF'
Add normalize_claude for Claude Code stream-json traces

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 对比报告 `report.py`

**Files:**
- Create: `eval/swebench-java/lib/report.py`
- Test: `eval/swebench-java/tests/test_report.py`

`build_compare` 输入用我们控制的结构（resolved 集合 + 归一摘要 map），从 harness `report.json` 提取 resolved 的 adapter 留 L2/云机（见 Task 7）。

- [ ] **Step 1: 写失败测试**

```python
# eval/swebench-java/tests/test_report.py
import unittest
from lib.report import build_compare


class TestBuildCompare(unittest.TestCase):
    def _fixture(self):
        all_ids = ["g-1", "g-2", "d-3", "d-4"]
        paicli_resolved = {"g-1", "d-3"}
        claude_resolved = {"g-1", "g-2"}
        repo_of = {"g-1": "google/gson", "g-2": "google/gson",
                   "d-3": "apache/dubbo", "d-4": "apache/dubbo"}
        summ = lambda iid, arm, turns, ran: {
            "instance_id": iid, "arm": arm, "turns": turns,
            "tool_calls_total": 5, "tool_breakdown": {"read": 2, "edit": 1, "exec": 1, "search": 1, "other": 0},
            "ran_build": ran, "ran_tests": ran, "files_read": 2, "files_edited": 1,
            "tokens": {"in": 100, "out": 20, "cache": 5}}
        paicli_summaries = {i: summ(i, "paicli", 4, True) for i in all_ids}
        claude_summaries = {i: summ(i, "claude", 6, False) for i in all_ids}
        return all_ids, paicli_resolved, claude_resolved, repo_of, paicli_summaries, claude_summaries

    def test_report_contains_rates_and_overlap(self):
        md = build_compare(*self._fixture())
        self.assertIn("PaiCLI", md)
        self.assertIn("Claude Code", md)
        # 两臂各 2/4 = 50%
        self.assertIn("2/4", md)
        # 重叠：都过 g-1；仅A d-3；仅B g-2；都挂 d-4
        self.assertIn("both resolved: 1", md)
        self.assertIn("only PaiCLI: 1", md)
        self.assertIn("only Claude: 1", md)
        self.assertIn("both failed: 1", md)

    def test_per_repo_breakdown_present(self):
        md = build_compare(*self._fixture())
        self.assertIn("google/gson", md)
        self.assertIn("apache/dubbo", md)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_report.py" -v`
Expected: FAIL（`lib.report` 不存在）。

- [ ] **Step 3: 写实现**

```python
# eval/swebench-java/lib/report.py
from collections import defaultdict


def _mean(values):
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def _arm_block(name, resolved, all_ids, summaries):
    n = len(all_ids)
    r = len(resolved)
    turns = _mean(summaries[i]["turns"] for i in all_ids if i in summaries)
    ran_tests = sum(1 for i in all_ids if summaries.get(i, {}).get("ran_tests")) 
    lines = [
        f"### {name}",
        f"- resolved: {r}/{n} ({100.0 * r / n:.1f}%)" if n else f"- resolved: {r}/0",
        f"- avg turns: {turns:.1f}",
        f"- ran tests: {ran_tests}/{n}",
    ]
    return "\n".join(lines)


def build_compare(all_ids, paicli_resolved, claude_resolved, repo_of,
                  paicli_summaries, claude_summaries):
    """生成 A(PaiCLI) vs B(Claude Code) 对比 markdown。"""
    both = sum(1 for i in all_ids if i in paicli_resolved and i in claude_resolved)
    only_p = sum(1 for i in all_ids if i in paicli_resolved and i not in claude_resolved)
    only_c = sum(1 for i in all_ids if i not in paicli_resolved and i in claude_resolved)
    neither = sum(1 for i in all_ids if i not in paicli_resolved and i not in claude_resolved)

    per_repo = defaultdict(lambda: {"n": 0, "p": 0, "c": 0})
    for i in all_ids:
        repo = repo_of.get(i, "?")
        per_repo[repo]["n"] += 1
        per_repo[repo]["p"] += 1 if i in paicli_resolved else 0
        per_repo[repo]["c"] += 1 if i in claude_resolved else 0

    out = ["# SWE-bench-java: PaiCLI vs Claude Code", "",
           f"Instances: {len(all_ids)} | model: deepseek-v4-pro (bare run)", "",
           _arm_block("PaiCLI", paicli_resolved, all_ids, paicli_summaries), "",
           _arm_block("Claude Code", claude_resolved, all_ids, claude_summaries), "",
           "### Overlap",
           f"- both resolved: {both}",
           f"- only PaiCLI: {only_p}",
           f"- only Claude: {only_c}",
           f"- both failed: {neither}", "",
           "### Per-repo (resolved/total)",
           "| repo | PaiCLI | Claude |", "|------|--------|--------|"]
    for repo in sorted(per_repo):
        s = per_repo[repo]
        out.append(f"| {repo} | {s['p']}/{s['n']} | {s['c']}/{s['n']} |")
    return "\n".join(out) + "\n"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -p "test_report.py" -v`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add eval/swebench-java/lib/report.py eval/swebench-java/tests/test_report.py
git commit -m "$(cat <<'EOF'
Add A/B compare report builder for SWE-bench-java eval

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: L2 集成脚本骨架 + README

**Files:**
- Create: `eval/swebench-java/prompt_template.md`
- Create: `eval/swebench-java/solve_one.sh`
- Create: `eval/swebench-java/score.sh`
- Create: `eval/swebench-java/run_eval.py`
- Create: `eval/swebench-java/README.md`

这些脚本**本地不执行**（依赖 x86 云机 + Docker + key + harness）；验收 = 文件创建、`run_eval.py` 通过 `python3 -m py_compile`、shell 脚本 `bash -n` 语法检查通过。

- [ ] **Step 1: 写 prompt_template.md**

```markdown
你当前位于一个 Java 项目仓库的根目录。下面是一个待解决的 GitHub issue。
请：定位相关代码 → 修改源码修复问题 → 用项目的构建命令确认能编译通过。
约束：
- 只修改必要的源码文件；不要修改任何测试文件；非必要不要改构建配置。
- 修复后请简述你改动了哪些文件、为什么。

<issue>
{problem_statement}
</issue>
```

- [ ] **Step 2: 写 solve_one.sh（容器内单实例解题，arm=paicli|claude）**

```bash
#!/usr/bin/env bash
# 在某实例的官方 Docker 镜像内跑一个 agent，产出 model_patch 与 raw trace。
# 本脚本在 x86 云机执行；本地仅做 bash -n 语法检查。
set -euo pipefail

INSTANCE_ID="$1"; IMAGE="$2"; ARM="$3"
ISSUE_FILE="$4"; BASE_COMMIT="$5"; OUT_DIR="$6"
REPO_DIR="${REPO_DIR:-/workspace/repo}"   # 镜像内仓库路径，按 harness 镜像实际调整（云机确认）

mkdir -p "$OUT_DIR"
TRACE="$OUT_DIR/${INSTANCE_ID}.jsonl"
PATCH="$OUT_DIR/${INSTANCE_ID}.patch"

run_paicli() {
  docker run --rm --platform linux/amd64 \
    -e DEEPSEEK_API_KEY -e DEEPSEEK_MODEL=deepseek-v4-pro \
    -v "$PAICLI_JAR:/opt/paicli.jar:ro" \
    -v "$ISSUE_FILE:/tmp/issue.md:ro" \
    "$IMAGE" bash -lc \
    "cd $REPO_DIR && java -jar /opt/paicli.jar run --file /tmp/issue.md --trace-file /tmp/trace.jsonl >/tmp/answer.txt 2>/tmp/agent.err; \
     git add -A && git diff --cached $BASE_COMMIT > /tmp/model.patch; \
     cat /tmp/trace.jsonl" > "$TRACE" || true
}

run_claude() {
  docker run --rm --platform linux/amd64 \
    -e ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic \
    -e ANTHROPIC_AUTH_TOKEN="$DEEPSEEK_API_KEY" \
    -e ANTHROPIC_MODEL=deepseek-v4-pro \
    -e CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    -v "$ISSUE_FILE:/tmp/issue.md:ro" \
    "$IMAGE" bash -lc \
    "npm i -g @anthropic-ai/claude-code >/dev/null 2>&1; cd $REPO_DIR && \
     claude -p \"\$(cat /tmp/issue.md)\" --output-format stream-json --verbose --dangerously-skip-permissions > /tmp/trace.jsonl 2>/tmp/agent.err; \
     git add -A && git diff --cached $BASE_COMMIT > /tmp/model.patch; \
     cat /tmp/trace.jsonl" > "$TRACE" || true
}

case "$ARM" in
  paicli) run_paicli ;;
  claude) run_claude ;;
  *) echo "unknown arm: $ARM" >&2; exit 2 ;;
esac

# 从容器 stdout 已收 trace；补丁通过第二次 docker cp/exec 取（云机按镜像落地方式确认）。
echo "[skeleton] $INSTANCE_ID/$ARM done -> $TRACE (patch extraction finalized on cloud box)"
```

- [ ] **Step 3: 写 score.sh + run_eval.py**

```bash
#!/usr/bin/env bash
# 调用 multi-swe-bench-env 官方 harness 评分。云机执行。
set -euo pipefail
PRED="${1:?usage: score.sh <gold|predictions.jsonl> <run_id>}"
RUN_ID="${2:?run_id required}"
WORKERS="${MAX_WORKERS:-8}"

python -m swebench.harness.run_evaluation \
  --dataset_name Daoguang/Multi-SWE-bench \
  --predictions_path "$PRED" \
  --run_id "$RUN_ID" \
  --max_workers "$WORKERS" \
  --cache_level env
# 阶段 1 先验环境: ./score.sh gold validate-gold --instance_ids <one_id>
```

```python
#!/usr/bin/env python3
"""SWE-bench-java 双臂评测编排（抽样 + docker 解题 + 汇总）。

本地可跑: --dry-run 只做抽样并写 instances.txt（不碰 Docker）。
docker 解题与评分在 x86 云机执行。
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
from lib import dataset, sampling, predictions  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ARMS = ("paicli", "claude")


def cmd_sample(args):
    rows = dataset.fetch_java_verified()
    picked = sampling.stratified_sample(rows, k=args.k, seed=args.seed)
    out = os.path.join(HERE, "instances.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(picked) + "\n")
    print(f"sampled {len(picked)} -> {out}")
    return picked


def cmd_run(args):
    picked = cmd_sample(args)
    if args.dry_run:
        print("[dry-run] skipping docker solve/score")
        return
    # 云机执行点：逐条 × 两臂调 solve_one.sh，收集 patch+trace，再 predictions.to_jsonl，再 score.sh
    raise SystemExit("docker orchestration runs on the x86 cloud box; see README and solve_one.sh")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--k", type=int, default=25)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--dry-run", action="store_true")
    main_args = p.parse_args()
    cmd_run(main_args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 写 README + 语法检查**

````markdown
# SWE-bench-java 评测流水线

## 本地（L1，零依赖）
跑全部单元测试：
```bash
PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -v
```
含真连数据集的 integration 测试：
```bash
RUN_INTEGRATION=1 PYTHONPATH=eval/swebench-java python3 -m unittest discover -s eval/swebench-java/tests -v
```
抽样（写 instances.txt，不碰 Docker）：
```bash
python3 eval/swebench-java/run_eval.py --dry-run
```

## 云机（L2，x86_64 + Docker + DeepSeek key + multi-swe-bench-env）
1. 装 harness：`git clone .../multi-swe-bench-env && cd multi-swe-bench-env && pip install -e .`
2. 先验环境：`eval/swebench-java/score.sh gold validate-gold`（单实例 gold 应 resolved）
3. 双臂解题：`run_eval.py`（逐条调 `solve_one.sh`）
4. 评分 + 报告：`score.sh predictions/<arm>.jsonl <run_id>` → `lib/report.py` 出 `compare.md`

镜像 build/拉取方式与 Claude Code 真实 stream-json 字段在首次云机运行时确认。
````

Run（语法检查，不执行业务）:
```bash
bash -n eval/swebench-java/solve_one.sh && bash -n eval/swebench-java/score.sh && \
python3 -m py_compile eval/swebench-java/run_eval.py && echo "syntax OK"
```
Expected: `syntax OK`。

- [ ] **Step 5: 提交**

```bash
chmod +x eval/swebench-java/solve_one.sh eval/swebench-java/score.sh eval/swebench-java/run_eval.py
git add eval/swebench-java/prompt_template.md eval/swebench-java/solve_one.sh \
  eval/swebench-java/score.sh eval/swebench-java/run_eval.py eval/swebench-java/README.md
git commit -m "$(cat <<'EOF'
Add L2 orchestration skeletons (solve_one/score/run_eval) + README

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## 自审记录

**Spec coverage（对照 Part 2 spec）：**
- §5.1 dataset.py 解析 mock + integration 真连 → Task 3 ✓
- §5.2 sampling 分层抽样 → Task 1 ✓
- §5.3 predictions JSONL → Task 2 ✓
- §5.4 normalize 两臂 + 工具类别归一 → Task 4（paicli + 表 + helper）+ Task 5（claude）✓
- §5.5 report 成功率/重叠/按仓库/行为指标 → Task 6 ✓
- §6 L2 骨架（solve_one/run_eval/score/CC 镜像片段/prompt 模板）→ Task 7 ✓
- §7 验收（unittest 全绿 + 脚本语法）→ 各 task 测试命令 + Task 7 语法检查 ✓
- **范围外（spec 已声明）**：从 harness report.json 提取 resolved 的 adapter、镜像 build/拉、Claude 真实 stream-json 字段 → 留云机，report.build_compare 用我们控制的 resolved 集合输入绕开，非占位符遗漏。

**Placeholder 扫描：** 无 TBD/TODO；L2 脚本的「云机执行点」是有意的范围边界（spec §1/§8 已声明），非未完成代码。

**Type consistency：** `stratified_sample(instances,k,seed)->list[str]`、`to_jsonl(records,model_name)->str`、`parse_rows(payload)->list[dict]`/`fetch_java_verified(page_size)`、`classify(name,table)`、`normalize_paicli(lines,instance_id)`/`normalize_claude(lines,instance_id)->summary`、`build_compare(all_ids,paicli_resolved,claude_resolved,repo_of,paicli_summaries,claude_summaries)->str` 跨 task 与测试一致；统一 summary 字段（turns/tool_calls_total/tool_breakdown/ran_build/ran_tests/files_read/files_edited/tokens）在 Task 4/5/6 一致。
