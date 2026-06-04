from collections import defaultdict


def _mean(values):
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def _arm_block(name, resolved, all_ids, summaries):
    n = len(all_ids)
    r = sum(1 for i in all_ids if i in resolved)
    turns = _mean(summaries.get(i, {}).get("turns", 0) for i in all_ids)
    ran_tests = sum(1 for i in all_ids if summaries.get(i, {}).get("ran_tests"))
    lines = [
        f"### {name}",
        f"- resolved: {r}/{n} ({100.0 * r / n:.1f}%)" if n else f"- resolved: {r}/0",
        f"- avg turns: {turns:.1f}",
        f"- ran tests: {ran_tests}/{n}",
    ]
    return "\n".join(lines)


def build_compare(all_ids, paicli_resolved, claude_resolved, repo_of,
                  paicli_summaries, claude_summaries, model_label="deepseek-v4-pro"):
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
           f"Instances: {len(all_ids)} | model: {model_label} (bare run)", "",
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
