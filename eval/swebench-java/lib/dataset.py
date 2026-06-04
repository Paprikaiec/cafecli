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
            "instance_id": row.get("instance_id") or None,
            "repo": row.get("repo") or None,
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
