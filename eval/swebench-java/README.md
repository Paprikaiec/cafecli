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
抽样（写 instances.txt，需联网拉数据集，不碰 Docker）：
```bash
python3 eval/swebench-java/run_eval.py --dry-run
```

## 云机（L2，x86_64 + Docker + DeepSeek key + multi-swe-bench-env）
1. 装 harness：`git clone .../multi-swe-bench-env && cd multi-swe-bench-env && pip install -e .`
2. 先验环境：`eval/swebench-java/score.sh gold validate-gold`（单实例 gold 应 resolved）
3. 双臂解题：逐条调 `solve_one.sh`（arm=paicli|claude）
4. 评分 + 报告：`score.sh predictions/<arm>.jsonl <run_id>` → `lib/report.py` 出 `compare.md`

镜像 build/拉取方式与 Claude Code 真实 stream-json 字段在首次云机运行时确认。
