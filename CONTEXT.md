# PaiCLI

Java 实现的终端 AI 编程 Agent（ReAct 循环 + 工具调用），对标 Claude Code / pi agent 的核心能力。

## Language

### 工具执行

**文件互斥锁（File Mutation Lock）**:
对工作区文件做写入的工具必须持有的按文件粒度的互斥保证：同一文件的变更串行排队，不同文件照常并行。是文件写入并发安全的唯一正典机制。
_Avoid_: 读写分批、写串行、read/write partition（已废弃的全局串行方案）

**工具批次（Tool Batch）**:
同一条 assistant 消息里返回的一组 tool call，默认并行执行（上限 4 线程），结果按源顺序回灌对话历史。批内兄弟调用互相看不到对方的结果。
