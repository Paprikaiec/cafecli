# 活动区合成器建在 JLine Display 之上：不 fork、不进主屏 alt-screen

Inline 渲染当前有两处结构性问题：Ctrl+O 折叠/展开走 `printAbove` 追加式重放整轮 transcript（scrollback 里留重复内容），且只有最后一个注册块可切换；流式期间转写流、活动区 ticker、状态栏各自持锁直写 stdout，每行触发独立 flush / JLine 重绘，无帧合并。参考的 8 层终端渲染架构（retained 节点树 + cell buffer + damage diff + 帧调度）描述的是**全屏 compositor**，与 AGENTS.md「不进 alternate screen、主屏直接输出」的承诺在极端处不相容。

## 决策

采用**活动区合成器（live-region compositor）**，四条边界一次定死：

1. **转写区不可变**：写入原生 scrollback 的输出一经写出永不重绘。折叠/展开语义只在[[活动区]]与[[转写浏览层]]内成立（见 CONTEXT.md「终端渲染」段）。
2. **活动区 ≤ 可视区**：底部连续区域（流式输出、活动指示、可折叠块、输入行、状态坞）由单一渲染路径独占重绘；内容滚出活动区顶端即冻结入转写区。物理约束：主屏模式下滚出可视区的行不可寻址，任何设计不得假装能原地重绘 scrollback。
3. **保留窗口 + 转写浏览层**：内存保留最近 5 个用户回合的可重渲染模型；切换已滚出内容经全屏 overlay（alternate screen 仅用于 overlay，ESC 退出，主屏 scrollback 原样保留）。
4. **diff 引擎复用 `org.jline.utils.Display`，不自建 cell buffer、不 fork JLine**：Display 是 public API，已提供行级 diff（oldLines 对比、insert/delete-line capability 探测、行内前后缀裁剪、wcwidth、折行），等价参考架构第 4–5 层的行粒度实现。渲染线程持有自己的 Display 实例驱动活动区条带（strip），与 LineReader 的 display 栈式共存（approach A）；BSU/ESU（`ESC[?2026`）在 update 调用外层包裹，无需改 JLine 内部。

## 被否决的方案

- **全屏 compositor（自持 scrollback）**：重造滚动/复制/搜索，推翻 AGENTS.md inline 承诺与全部 `printAbove`/`Status` 集成点，回归面最大。
- **fork JLine 加第一类 strip API**：初始 3–5 周（`LineReaderImpl` ~6.5k 行折行/光标数学），且商业产品关键路径上背永久维护税（自建 artifact、每次上游 release rebase、跨终端回归矩阵）。fork 独有收益（typing 态与 streaming 态统一区域）可用 approach B 近似。
- **subclass `LineReaderImpl` 覆写 `redisplay(boolean)`（approach B）**：`redisplay` 依赖 private helper，覆写即抄 ~200 行方法体 = 单方法 mini-fork。保留为**升级路径**：仅当「用户输入期后台 push 输出」两个 Display 栈式共存出现可感知抖动时再上，A 的条带内部结构原样迁移。
- **只修视口内重绘（保守修补）**：保留 renderedRows 估算算术（当前 bug 源头），对流式延迟热点无帮助。

## 后果

- 折叠块的「frozen」从估算兜底变成架构事实：块在活动区内可原地切换，滚出后只能经转写浏览层。
- `printAbove` 从每行热路径降级为条带顶端的**冻结刷出机制**（每帧批量，非每行）。
- 自研范围收窄：参考架构第 4–5 层（cell buffer / pool / damage diff）出栈，第 3 层布局引擎（Yoga）失去必要性前提（待后续决策确认）。
- 两个 Display 栈式共存的锚定（输入行高度变化、resize）是 approach A 的固有 seam，靠单渲染线程纪律约束；此处出问题优先升级到 approach B，而非引入第三方协调机制。
