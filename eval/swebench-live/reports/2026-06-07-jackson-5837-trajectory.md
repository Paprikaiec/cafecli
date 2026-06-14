# PaiCLI vs Claude Code — jackson-databind-5837 Trajectory 对比

- 数据集：**SWE-bench-Live / MultiLang**（java split），抗污染（实例创建于 2026-04，deepseek-v4 训练截止后）
- 实例：`FasterXML__jackson-databind-5837`（maven，gold 修改已有源码，已 gold 验证可干净评测）
- 两臂：**A = PaiCLI**（`run` 子命令，root）vs **B = Claude Code**（headless `-p`，agent 用户，共享 `/root/.m2`）
- 模型：两臂统一 **deepseek-v4-pro**（Anthropic 兼容端点）
- 评分：官方 SWE-bench-Live harness（apply test_patch + pred_patch → rebuild → test → log_parser）

## 0. 问题本身

> `readerForUpdating` 更新一个**已有的** local/匿名类实例时，Jackson 误报
> `Cannot deserialize Class X$1 (of type local/anonymous) as a Bean`。

4 个 FAIL_TO_PASS 测试拆成两半：

| 测试 | 期望 | 语义 |
|---|---|---|
| `testReaderForUpdatingLocalClass3229` / `...AnonymousClass3229` | 应**成功** | 更新已有实例（无需构造）应放行 |
| `testDeserializeLocalClassFails3229` / `...AnonymousClassFails3229` | 应**失败并抛精确异常** | 从零反序列化 local/匿名类仍须被拒 |

难点：**既要放行"更新已有实例"，又要保留"从零构造必须报错"** 的契约。

## 1. 结果：正确率打平（都 2/4）

| | PaiCLI | Claude Code |
|---|---|---|
| resolved | False | False |
| FAIL_TO_PASS | **2/4**（过"更新"半，挂"反序列化须失败"半） | **2/4**（同上） |
| PASS_TO_PASS 回归 | 0 | 0 |

两臂都吃透了"更新放行"，都没满足"从零反序列化仍须抛精确异常"。**pass/fail 打平，但 trajectory 差异显著——区分度在过程轴。**

## 2. 定位与修法

| | PaiCLI | Claude Code |
|---|---|---|
| 定位 | ✅ 一击命中 `BeanDeserializerFactory.java` | ✅ 同一文件 |
| 修法 | **整段删除** `isPotentialBeanType` 内的 local-type 检查（1 hunk / 17 行） | `throw`→`return false` **＋** `createBeanDeserializer` 加 local-type 放行（2 hunk）**＋ 加回归测试**（69 行） |
| 依据 | **推理假设**："删掉后反序列化会在更后面的阶段失败" | 显式区分"更新已有实例"与"从零构造"两条路径 |

PaiCLI 的 diff：
```diff
-        // also: can't deserialize some local classes ...
-        typeStr = ClassUtil.isLocalType(type, true);
-        if (typeStr != null) {
-            throw new IllegalArgumentException("Cannot deserialize Class "+...+" as a Bean");
-        }
         return true;
```
Claude 的 diff（更细致，但仍 2/4）：
```diff
   if (!isPotentialBeanType(type.getRawClass())) {
-      return null;
+      if (ClassUtil.isLocalType(type.getRawClass(), true) == null) { return null; }  // 放行 local 走 update 路径
   }
   ...
-      throw new IllegalArgumentException("Cannot deserialize Class "+...+" as a Bean");
+      return false;
```

## 3. 过程指标

| 指标 | PaiCLI | Claude Code |
|---|---|---|
| 轮数 | 50 | 47 |
| 工具调用 | ~55（22 grep / 18 read / 10 exec / 3 glob / 1 write / **1 revert**） | ~136（61 read / 65 bash / 3 edit / **3 子 Agent**） |
| 墙钟时间 | **4.4 min**(265s) | 11.6 min(697s)，约 **2.6×** |
| 成本 | deepseek 未回报 token | **$1.80** |
| **跑失败测试？** | ❌ 全程**未编译/未跑测试**（10 条 exec 全是 git/sed/cp/wc/diff） | ✅ **反复 `mvnw test -Dtest=UpdateViaObjectReaderTest`，用测试反馈迭代** |
| 加测试 | ❌ | ✅ |
| harness 摩擦 | ⚠️ `writefile` **覆盖整文件**→`revert_turn` 失败→靠 git checkout+cp+sed 救场；一度 `cd` 到幻觉路径 `/home/zhichaoyu/...` | 无明显摩擦 |

## 4. 失败归因

- **PaiCLI =「没用测试反馈」**：修复建立在**未经验证的推理假设**（"会在后面失败"）上，全程没编译没跑测试就交付——假设恰好错了（删太狠，根本不报错），没有测试闭环拦住它。叠加一次 **harness 工具摩擦**（`writefile` 整文件覆盖语义易误伤）。
- **Claude ≈「纯推理失败」**：用了测试反馈、探索更深、加了测试——harness 的验证闭环正常工作，但模型仍未推导出官方期望的精确异常契约（这是唯一对 harness "不提供信息"、归因于模型天花板的一类）。

## 5. 结论

**pass/fail 打平掩盖了真实的 harness 差异**，trajectory 上两臂走的是两条不同的路：

- **Claude Code 更"验证驱动"**：自带跑测试/看红绿的闭环，代价是慢 2.6×、$1.80、136 次工具调用；失败更"干净"（逼近模型天花板）。
- **PaiCLI 更"精简/果断"**：4.4min、55 次调用就收手，但这条上**省掉了测试验证**、凭假设交付——更快更省，但"快"恰恰让它错过自我纠错。

**对 PaiCLI 的 harness 改进信号**：
1. **编辑后默认跑一次相关测试再收手**（补上测试反馈闭环），尤其当修复依赖"行为会如何"的推理假设时。
2. **`writefile` 改为更安全的局部编辑语义**，避免整文件覆盖误伤（这次靠 git 救回，但浪费步数且有丢改动风险）。

> 方法论：本实例先经静态过滤（F2P 测试类在 test_patch 中）+ gold 验证（gold 干净 resolved、P2P 不 flaky）确认为**有效区分实例**，才用于对比。详见 [../README.md](../README.md)。
