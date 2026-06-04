import json
import re

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
    return "mvn" in c or "gradle" in c


def _is_test_cmd(cmd):
    # 'test' 作为独立目标；排除 -DskipTests（前接字母）与 test-compile（后接连字符）
    return _is_build_cmd(cmd) and re.search(r"(?<![A-Za-z])test(?!-)", cmd.lower()) is not None


def _new_breakdown():
    return {c: 0 for c in _CLASSES}


def _summary(instance_id, arm, turns, tb, total, ran_build, ran_tests, fr, fe, tokens):
    return {
        "instance_id": instance_id, "arm": arm, "turns": turns,
        "tool_calls_total": total, "tool_breakdown": tb,
        "ran_build": ran_build, "ran_tests": ran_tests,
        "files_read": len(fr), "files_edited": len(fe), "tokens": tokens,
    }


def _iter_events(lines):
    """逐行解析 NDJSON，跳过空行与坏行（云机 trace 可能被 OOM/kill 截断）。"""
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            yield json.loads(line)
        except ValueError:
            continue


def normalize_paicli(lines, instance_id):
    """解析 PaiCLI AgentTraceWriter NDJSON → 统一行为摘要。"""
    turns = 0
    tb = _new_breakdown()
    total = 0
    fr, fe = set(), set()
    ran_build = ran_tests = False
    tokens = {"in": 0, "out": 0, "cache": 0}
    for ev in _iter_events(lines):
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


def normalize_claude(lines, instance_id):
    """解析 Claude Code stream-json NDJSON → 统一行为摘要。

    token 按各 assistant 事件的 message.usage 累加（in=input_tokens,
    out=output_tokens, cache=cache_read_input_tokens）。复用 _iter_events 跳过坏行。
    """
    turns = 0
    tb = _new_breakdown()
    total = 0
    fr, fe = set(), set()
    ran_build = ran_tests = False
    tokens = {"in": 0, "out": 0, "cache": 0}
    for ev in _iter_events(lines):
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
        tokens["in"] += int(usage.get("input_tokens") or 0)
        tokens["out"] += int(usage.get("output_tokens") or 0)
        tokens["cache"] += int(usage.get("cache_read_input_tokens") or 0)
    return _summary(instance_id, "claude", turns, tb, total, ran_build, ran_tests, fr, fe, tokens)
