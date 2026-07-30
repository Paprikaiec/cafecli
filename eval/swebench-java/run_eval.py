#!/usr/bin/env python3
"""SWE-bench-java 双臂评测编排（抽样 + docker 解题 + 汇总）。

本地可跑: --dry-run 只做抽样并写 instances.txt（不碰 Docker）。
docker 解题与评分在 x86 云机执行。
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
from lib import dataset, sampling  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ARMS = ("cafecli", "claude")


def cmd_sample(args):
    rows = dataset.fetch_java_verified()
    picked = sampling.stratified_sample(rows, k=args.k, seed=args.seed)
    out = os.path.join(HERE, "instances.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(picked) + "\n")
    print(f"sampled {len(picked)} -> {out}")
    return picked


def cmd_run(args):
    cmd_sample(args)
    if args.dry_run:
        print("[dry-run] skipping docker solve/score")
        return
    raise SystemExit("docker orchestration runs on the x86 cloud box; see README and solve_one.sh")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--k", type=int, default=25)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--dry-run", action="store_true")
    cmd_run(p.parse_args())


if __name__ == "__main__":
    main()
