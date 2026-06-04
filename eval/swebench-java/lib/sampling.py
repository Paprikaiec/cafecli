import random


def stratified_sample(instances, k=25, seed=42):
    """按 repo 分层随机抽样，返回 k 个 instance_id（确定性，升序）。

    instances: list of {"instance_id": str, "repo": str}
    名额按各 repo 占比用最大余数法分配（同余数时按 repo 名倒序优先，tuple 比较，可重现）；
    每组用 random.Random(seed) 抽样。
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
