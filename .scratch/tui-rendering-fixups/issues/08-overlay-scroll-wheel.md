# 08 — 浏览层滚轮 / 行滚动

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`（决策见 ADR 0012）

## What to build

[[转写浏览层]]从 vi 式块光标模型改为可滚动的行视口，支持鼠标滚轮与按键滚动。

- 新增**纯函数**解析 SGR（1006）鼠标事件，把滚轮上 / 下映射为行滚动 Action，与既有键 `decode` 同构、可单测。
- `TranscriptOverlayModel` 改为行滚动视口。
- `TranscriptOverlay` 在 raw + alt-screen 块内开关鼠标追踪（DECSET 1000 + 1006），退出时在 `finally` 复位。
- 键位：j/k/方向键（行）、PgUp/PgDn（页）、g/G（首尾）。

## Acceptance criteria

- [ ] 鼠标滚轮上下滑动浏览层，按行滚动
- [ ] j/k/方向键行滚动、PgUp/PgDn 页滚动、g/G 到首尾
- [ ] 退出浏览层时鼠标追踪被复位（`finally` 保证，异常路径也复位）
- [ ] 单测：SGR 滚轮纯解析函数 → 滚动 Action；`TranscriptOverlayModel` 行滚动 `reduce`

## Blocked by

- 切片 `06`（Ctrl+O 打开浏览层、退役原地折叠）
