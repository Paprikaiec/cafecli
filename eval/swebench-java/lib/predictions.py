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
