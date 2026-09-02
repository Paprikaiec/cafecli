#!/usr/bin/env bash
# 缩放风暴：在真实 Terminal.app 里反复改窗口尺寸，然后直接读回它的回卷来判定活区有没有留下副本。
#
# 为什么需要它：屏幕模型（TerminalScreenModel）只是我们对 Terminal.app 重排规则的**假设**，三次修复
# 都是在假设上验绿、在真机上翻车。这个脚本把真机变成可无人值守重跑的回归：
#
#   1. 用 AppleScript 开一个 Terminal 窗口跑 RenderReproDriver --storm（不接 LLM，形态与真实回合一致：
#      spinner + 多行流式思考预览 + 输入行 + 状态坞，屏幕已铺满转写）；
#   2. 往那个窗口的 tty 写 `CSI 8 ; rows ; cols t`（Terminal.app 认这个序列）来改尺寸——与人手拖窗口、
#      按 Option+绿键走的是同一条 SIGWINCH 通路；
#   3. 用 AppleScript 把该 tab 的 history（= 回卷 + 当前屏，用户滚轮往上能看到的全部）取回来；
#   4. 两个方向一起断言：活区专属的行（spinner / 状态坞）**至多出现一次**，且驱动写过的每一条
#      编号转写行**恰好出现一次**。只数副本是不够的——见 check() 上方那段。
#
# 用法：
#   scripts/resize-storm.sh [zoom|drag|height|heightdrag|fullscreen|storm|all] [cycles]
#
#   zoom        两个尺寸之间来回**阶跃**（CSI 8 t 一步到位），宽高同时变（默认）
#   drag        拖窗口边缘：每步 DRAG_STEP 列（默认 2），宽度变、高度不变
#   height      只改高度、宽度不变。高度与宽度不再被分成「相关 / 不相关」两类：任何一维变化都
#               结束一个 [[geometry epoch]]，走同一次 [[destructive resize reset]]
#   heightdrag  逐行拖上下边缘，宽度不变
#   fullscreen  **用户真正复现 bug 的手势**：AppleScript `set zoomed` 最大化再还原。
#               与上面所有 pattern 的关键区别是它**带窗口动画**：一次转换连发几十个中间尺寸
#               （实测 32 个），CSI 8 t 那种一步到位的尺寸设置模拟不出来。
#               不需要辅助功能权限（zoomed 是窗口属性，不是按键模拟）。
#   storm       随机尺寸乱跳，宽高一起变，间隔 0.12s
#   all         依次跑上面全部
#
# ⚠️ 只跑 zoom / drag 是不够的：它们都是**阶跃式**改宽，对动画式缩放没有证伪能力。
#    2026-08 就是因为回归只覆盖这两个 pattern，把一个 fullscreen 下必现的缺陷判成了「已修」。
#
# 确定性回放录制是显式开启的，不改变普通回归：
#   TRACE=/tmp/cafecli-replay scripts/resize-storm.sh fullscreen 6
# 会生成同一次运行的 `/tmp/cafecli-replay.fullscreen.jsonl`（Java 字节提交 + 1ms 原生尺寸采样
# + WINCH 诊断标记）与
# `/tmp/cafecli-replay.fullscreen.history.txt`（Terminal.app 原始 history）。回放模型尚未校准前，
# 本脚本仍只用下面的真机 history 检查判定成败，不采信模型输出。注意这条边界没有观测
# Terminal.app 何时真正消费 PTY 字节，不能把 recorder 顺序称为 Terminal.app 的内部处理顺序。
# TRACE 收尾走显式 request/ready 握手：目标进程先停掉 spinner/producer、等待在途写完成、保持最后
# 一帧不清屏、原子落盘 trace，最后才创建 ready 文件；ready 出现后应用不会再写一个终端字节，
# Terminal.app 的 history 仍可读取。它修的是 Java 端点竞态，不是 PTY 消费 / reflow 的已知边界。
#
# 录制器的扰动对照必须成组跑，不能拿 fullscreen 的单次结果做 A/B：
#   for i in {1..6}; do TRACE="/tmp/recording-on-$i" scripts/resize-storm.sh fullscreen 6; done
#   for i in {1..6}; do env -u TRACE scripts/resize-storm.sh fullscreen 6; done
# 对比两组 spinner / dock 副本数的分布；调整轮数时两组必须保持一致。
#
# 退出码 0 = 干净；1 = 发现副本（会把回卷里重复的片段打出来）。
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
JAR="$ROOT/target/cafecli-1.0-SNAPSHOT.jar"
PATTERN="${1:-zoom}"
CYCLES="${2:-12}"
TRACE_BASE="${TRACE:-}"
REPLAY_ANCHOR='[cafecli-replay-start:v1]'

if [[ ! -f "$JAR" ]]; then
    echo "缺少 $JAR —— 先跑 mvn -o clean package" >&2
    exit 2
fi
if [[ "$(uname)" != "Darwin" ]]; then
    echo "这个脚本驱动的是 macOS 的 Terminal.app" >&2
    exit 2
fi

# 目标窗口的起止尺寸。宽度跨度要足够大，缩放时活区才会真的重排。
BIG_ROWS=44;  BIG_COLS=170
SMALL_ROWS=24; SMALL_COLS=88

# 脚本被 Ctrl+C / timeout 打断时也要收干净，否则又会留下挂着模态框的窗口。
TTY_PATH=""
WINDOW_ID=""
TARGET_PAUSED=0
ACTIVE_TRACE_FILE=""
ACTIVE_CAPTURE_REQUEST=""
ACTIVE_CAPTURE_READY=""
cleanup_capture_control() {
    [[ -z "$ACTIVE_CAPTURE_REQUEST" ]] || rm -f -- "$ACTIVE_CAPTURE_REQUEST"
    [[ -z "$ACTIVE_CAPTURE_READY" ]] || rm -f -- "$ACTIVE_CAPTURE_READY"
    ACTIVE_CAPTURE_REQUEST=""
    ACTIVE_CAPTURE_READY=""
}
cleanup_on_exit() {
    if [[ -n "$TTY_PATH" ]]; then close_target "$ACTIVE_TRACE_FILE" || true; fi
    cleanup_capture_control
}
trap cleanup_on_exit EXIT INT TERM

# `scripts/resize-storm.sh cleanup` —— 收拾此前留下的、进程已经退掉的被测窗口。
# （窗口若还挂着 Terminal 的模态确认框，只能人手点一下 Terminate：osascript 没有辅助功能权限。）
if [[ "$PATTERN" == "cleanup" ]]; then
    osascript <<'EOF'
tell application "Terminal"
    set n to 0
    -- 倒序遍历：close 会就地改动 windows 集合，正序遍历会跳过条目。
    -- 只处理还有 tab 的窗口：Terminal 会把已经关掉的窗口作为 visible=false / tabs=0 的幽灵条目
    -- 留在脚本对象模型里直到它自己重启，对幽灵条目 close 永远"成功"但什么也没发生，
    -- 于是 cleanup 会报出一个永远清不掉的假数字。
    repeat with i from (count of windows) to 1 by -1
        try
            set w to window i
            if (count of tabs of w) > 0 then
                set nm to name of w
                if nm contains "RenderReproDriver" or nm contains "--storm" then
                    close w saving no
                    set n to n + 1
                end if
            end if
        end try
    end repeat
    return "closed " & n
end tell
EOF
    exit 0
fi

open_target() {
    local seconds="$1" trace_file="${2:-}" capture_id="${3:-}"
    local capture_request="${4:-}" capture_ready="${5:-}"
    local target_command quoted_trace quoted_capture quoted_request quoted_ready size i target_identity
    # 先开空 tab、拿 tty、定好初始尺寸，最后才启动 JVM。否则「do script java → resize」会让
    # recorder 的 header 偶尔读到旧尺寸，而这次早到的 WINCH 又发生在 handler 安装之前。
    # 命令通过环境变量交给 AppleScript，避免路径里的空格或引号穿透两层字符串解析。
    # `exec` removes the interactive shell from the target process tree. Without it, SIGSTOP makes
    # zsh print a "suspended job" notice into the very history snapshot we are trying to preserve.
    printf -v target_command 'cd %q && exec java -cp %q com.cafecli.render.inline.RenderReproDriver --storm=%q' \
        "$ROOT" "$JAR" "$seconds"
    if [[ -n "$trace_file" ]]; then
        printf -v quoted_trace '%q' "$trace_file"
        target_command+=" --trace=$quoted_trace"
    fi
    if [[ -n "$capture_id" ]]; then
        printf -v quoted_capture '%q' "$capture_id"
        target_command+=" --capture-id=$quoted_capture"
    fi
    if [[ -n "$capture_request" ]]; then
        printf -v quoted_request '%q' "$capture_request"
        printf -v quoted_ready '%q' "$capture_ready"
        target_command+=" --capture-request=$quoted_request --capture-ready=$quoted_ready"
    fi
    target_identity="$(osascript <<'EOF'
tell application "Terminal"
    set t to do script ""
    set targetTty to tty of t
    -- Terminal publishes the new tab before adding its owning window to the scriptable collection.
    delay 0.2
    repeat with w in windows
        repeat with tb in tabs of w
            try
                if tty of tb is targetTty then return targetTty & "|" & (id of w)
            end try
        end repeat
    end repeat
end tell
error "new Terminal.app tab has no owning window"
EOF
)"
    IFS='|' read -r TTY_PATH WINDOW_ID <<< "$target_identity"
    if [[ -z "$TTY_PATH" || -z "$WINDOW_ID" ]]; then
        echo "拿不到 Terminal 窗口的 tty" >&2
        exit 2
    fi
    # 起始尺寸必须在 recorder 读取 header 之前真实生效；这里的 stty 只发生在目标 JVM 启动前。
    resize "$BIG_ROWS" "$BIG_COLS"
    for ((i = 0; i < 50; i++)); do
        size="$(stty size < "$TTY_PATH" 2>/dev/null || true)"
        [[ "$size" == "$BIG_ROWS $BIG_COLS" ]] && break
        sleep 0.05
    done
    if [[ "$size" != "$BIG_ROWS $BIG_COLS" ]]; then
        echo "Terminal.app 初始尺寸未稳定到 $BIG_ROWS x $BIG_COLS（实际：${size:-unknown}）" >&2
        close_target
        return 1
    fi
    if ! CAFECLI_STORM_COMMAND="$target_command" CAFECLI_STORM_TTY="$TTY_PATH" \
        CAFECLI_STORM_WINDOW_ID="$WINDOW_ID" osascript <<'EOF' >/dev/null
set targetCommand to system attribute "CAFECLI_STORM_COMMAND"
set targetTty to system attribute "CAFECLI_STORM_TTY"
set targetWindowId to (system attribute "CAFECLI_STORM_WINDOW_ID") as integer
tell application "Terminal"
    repeat with w in windows
        if id of w is targetWindowId then
            repeat with tb in tabs of w
                try
                    if tty of tb is targetTty then
                        do script targetCommand in tb
                        return
                    end if
                end try
            end repeat
        end if
    end repeat
end tell
error "target Terminal.app tab disappeared before JVM launch"
EOF
    then
        echo "无法在 $TTY_PATH 启动 RenderReproDriver" >&2
        close_target
        return 1
    fi
    # 给 JVM、recorder、WINCH handler 与首屏留出时间；此处不再改初始尺寸。
    sleep 6
}

# 窗口一律按 tty 定位：custom title 在 `do script` 刚返回时还没挂上，按标题找会扑空。
#
# **关窗前必须先让窗口里的进程退干净。** Terminal.app 在窗口还有子进程时关窗会弹一个模态确认框
# （"Do you want to terminate running processes in this window?"），AppleScript 的 close 会「成功返回」
# 但窗口其实留在屏上挂着对话框；而 osascript 默认没有辅助功能权限，点不掉那个按钮，只能人手点。
# 所以顺序是：SIGTERM 掉该 tty 上的被测进程（shell 已被 `exec java` 替换）→ 等该 tty 上再无进程
# → 再关窗。这时没有运行中的进程，关窗静默完成。
target_java_pids() {
    local tty_name="$1"
    # 这个 tty 是脚本新建并独占的；按 executable 选 Java，避免 args 里的类名同时命中父 shell。
    ps -t "$tty_name" -o pid=,comm= 2>/dev/null \
        | awk '$2 == "java" || $2 ~ /\/java$/ {print $1}'
}

close_target() {
    local expected_trace="${1:-}" rc=0
    # 用 `ps -t <tty>` 找 pid 再 kill，不要用 `pkill -t`：macOS 的 pkill 认的 tty 写法与 ps 不同
    # （`pkill -t ttys000` 直接报 "No such tty"），一路静默失败就又会留下挂模态框的窗口。
    local tty_name="${TTY_PATH#/dev/}" pids remaining window_count i
    pids="$(target_java_pids "$tty_name")"
    if [[ -n "$pids" ]]; then
        # SIGTERM 让 JVM shutdown hook 有机会把只存在内存里的 trace 完整落盘。
        kill -TERM $pids 2>/dev/null || true
        # DUMP-only controls may still use SIGSTOP. TRACE capture no longer does: its request/ready
        # handshake has already quiesced the renderer and persisted the recorder before we get here.
        if [[ "$TARGET_PAUSED" -eq 1 ]]; then
            kill -CONT $pids 2>/dev/null || true
            TARGET_PAUSED=0
        fi
    fi
    for ((i = 0; i < 100; i++)); do
        [[ -n "$(target_java_pids "$tty_name")" ]] || break
        sleep 0.1
    done
    if [[ -n "$(target_java_pids "$tty_name")" ]]; then
        echo "被测进程在 SIGTERM 后仍未退出；将强制清理，trace 可能不完整" >&2
        pids="$(target_java_pids "$tty_name")"
        [[ -z "$pids" ]] || kill -KILL $pids 2>/dev/null || true
        rc=1
    fi
    if [[ -n "$expected_trace" ]]; then
        # 进程退出意味着 shutdown hook 已结束；再等文件出现，兼容 recorder 的原子 rename。
        for ((i = 0; i < 100; i++)); do
            [[ -s "$expected_trace" ]] && break
            sleep 0.1
        done
        if [[ ! -s "$expected_trace" ]]; then
            echo "trace 未在被测进程退出后落盘：$expected_trace" >&2
            rc=1
        fi
    fi
    # `exec java` leaves no parent shell. If anything still owns this dedicated tty, stop it
    # before closing the window so Terminal.app cannot raise its unclickable process-confirmation modal.
    for ((i = 0; i < 50; i++)); do
        remaining="$(ps -t "$tty_name" -o pid= 2>/dev/null || true)"
        [[ -z "$remaining" ]] && break
        sleep 0.04
    done
    if [[ -n "$remaining" ]]; then
        kill -TERM $remaining 2>/dev/null || true
        sleep 0.2
        remaining="$(ps -t "$tty_name" -o pid= 2>/dev/null || true)"
        [[ -z "$remaining" ]] || kill -KILL $remaining 2>/dev/null || true
        rc=1
    fi
    window_count="1"
    for ((i = 0; i < 10; i++)); do
        CAFECLI_STORM_TTY="$TTY_PATH" CAFECLI_STORM_WINDOW_ID="$WINDOW_ID" \
            osascript <<'EOF' >/dev/null 2>&1 || true
set targetTty to system attribute "CAFECLI_STORM_TTY"
set targetWindowId to (system attribute "CAFECLI_STORM_WINDOW_ID") as integer
tell application "Terminal"
    repeat with w in windows
        if id of w is targetWindowId then
            repeat with tb in tabs of w
                try
                    if tty of tb is targetTty then close w saving no
                end try
            end repeat
        end if
    end repeat
end tell
EOF
        window_count="$(CAFECLI_STORM_TTY="$TTY_PATH" CAFECLI_STORM_WINDOW_ID="$WINDOW_ID" \
            osascript <<'EOF' 2>/dev/null || true
set targetTty to system attribute "CAFECLI_STORM_TTY"
set targetWindowId to (system attribute "CAFECLI_STORM_WINDOW_ID") as integer
set matches to 0
tell application "Terminal"
    repeat with w in windows
        if id of w is targetWindowId then
            repeat with tb in tabs of w
                try
                    if tty of tb is targetTty then set matches to matches + 1
                end try
            end repeat
        end if
    end repeat
end tell
return matches
EOF
        )"
        [[ "${window_count:-0}" == "0" ]] && break
        sleep 0.1
    done
    if [[ "${window_count:-0}" != "0" ]]; then
        echo "未能关闭 $TTY_PATH 对应的 Terminal.app 窗口" >&2
        rc=1
    fi
    TARGET_PAUSED=0
    TTY_PATH=""
    WINDOW_ID=""
    return $rc
}

pause_target() {
    local tty_name="${TTY_PATH#/dev/}" pids i pid state all_stopped
    pids="$(target_java_pids "$tty_name")"
    if [[ -z "$pids" ]]; then
        echo "找不到 $TTY_PATH 上的 RenderReproDriver，无法固定 history/trace 边界" >&2
        return 1
    fi
    kill -STOP $pids 2>/dev/null || return 1
    # Set this before verification so an error path still sends CONT after queuing TERM.
    TARGET_PAUSED=1
    for ((i = 0; i < 50; i++)); do
        all_stopped=1
        for pid in $pids; do
            state="$(ps -p "$pid" -o state= 2>/dev/null || true)"
            [[ "$state" == *T* ]] || all_stopped=0
        done
        if [[ "$all_stopped" -eq 1 ]]; then
            return 0
        fi
        sleep 0.02
    done
    echo "RenderReproDriver 未及时进入 stopped 状态" >&2
    return 1
}

resume_target() {
    local tty_name="${TTY_PATH#/dev/}" pids
    pids="$(target_java_pids "$tty_name")"
    [[ -z "$pids" ]] || kill -CONT $pids 2>/dev/null || true
    TARGET_PAUSED=0
}

# 写 XTWINOPS 到目标窗口的 tty —— Terminal.app 自己解释这个序列并改尺寸，被测进程只收到 SIGWINCH，
# 和人手拖窗口完全同一条路径。
resize() { printf '\033[8;%d;%dt' "$1" "$2" > "$TTY_PATH"; }

history_text() {
    osascript <<EOF
tell application "Terminal"
    repeat with w in windows
        if id of w is $WINDOW_ID then
            repeat with tb in tabs of w
                try
                    if tty of tb is "$TTY_PATH" then return history of tb
                end try
            end repeat
        end if
    end repeat
    return "TARGET-WINDOW-GONE"
end tell
EOF
}

# 直接让 AppleScript 把 history 文本写成 UTF-8，避免 shell command substitution 丢掉末尾换行。
# 这份文件是 trace 的地面真相；检查逻辑随后只读取它，不再二次转写。
capture_history() {
    local output_path="$1" exact_anchor="$2"
    CAFECLI_STORM_TTY="$TTY_PATH" CAFECLI_STORM_HISTORY="$output_path" \
        CAFECLI_STORM_ANCHOR="$exact_anchor" CAFECLI_STORM_WINDOW_ID="$WINDOW_ID" \
        osascript <<'EOF' >/dev/null
set targetTty to system attribute "CAFECLI_STORM_TTY"
set outputPath to system attribute "CAFECLI_STORM_HISTORY"
set anchorText to system attribute "CAFECLI_STORM_ANCHOR"
set targetWindowId to (system attribute "CAFECLI_STORM_WINDOW_ID") as integer
set capturedHistory to "TARGET-WINDOW-GONE"

tell application "Terminal"
    repeat with w in windows
        if id of w is targetWindowId then
            repeat with tb in tabs of w
                try
                    if tty of tb is targetTty then
                        set candidateHistory to history of tb
                        if candidateHistory contains anchorText then set capturedHistory to candidateHistory
                    end if
                end try
            end repeat
        end if
    end repeat
end tell

set anchorOffset to offset of anchorText in capturedHistory
if anchorOffset is 0 then error "replay anchor is absent from Terminal.app history"
set capturedHistory to text anchorOffset thru -1 of capturedHistory

set outputHandle to missing value
try
    set outputHandle to open for access (POSIX file outputPath) with write permission
    set eof outputHandle to 0
    write capturedHistory to outputHandle as «class utf8»
    close access outputHandle
on error messageText number messageNumber
    try
        if outputHandle is not missing value then close access outputHandle
    end try
    error messageText number messageNumber
end try
EOF
}

# ZOOM_SLEEP 可调：用来分辨「算术错」与「与渲染节奏抢跑」——前者与间隔无关，后者随间隔变长而消失。
pattern_zoom() {
    local gap="${ZOOM_SLEEP:-0.45}"
    for ((i = 0; i < CYCLES; i++)); do
        resize "$SMALL_ROWS" "$SMALL_COLS"; sleep "$gap"
        resize "$BIG_ROWS" "$BIG_COLS";     sleep "$gap"
    done
}

pattern_drag() {
    local w
    for ((i = 0; i < CYCLES; i++)); do
        for ((w = BIG_COLS; w >= SMALL_COLS; w -= ${DRAG_STEP:-2})); do resize "$BIG_ROWS" "$w"; sleep 0.05; done
        for ((w = SMALL_COLS; w <= BIG_COLS; w += ${DRAG_STEP:-2})); do resize "$BIG_ROWS" "$w"; sleep 0.05; done
    done
}

# height —— **只改高度、宽度不变**。这条维度曾经完全没有覆盖，而它正是致命的那一维：只改高度
# 的一步会静默把光标下移一行，让紧随其后的每一次**宽度**变化都少擦一行。现在没有「只改高度不用
# 擦」这条路径了——任何一维变化都结束 epoch，走同一次重置。
pattern_height() {
    local gap="${ZOOM_SLEEP:-0.45}" r
    for ((i = 0; i < CYCLES; i++)); do
        resize "$SMALL_ROWS" "$BIG_COLS"; sleep "$gap"
        resize "$BIG_ROWS" "$BIG_COLS";   sleep "$gap"
    done
}

# height-drag —— 逐行拖上下边缘（宽度不变）。与 drag 对称，用来分辨「单次高度跳变」与「连续高度变化」。
pattern_heightdrag() {
    local r
    for ((i = 0; i < CYCLES; i++)); do
        for ((r = BIG_ROWS; r >= SMALL_ROWS; r -= ${DRAG_STEP:-2})); do resize "$r" "$BIG_COLS"; sleep 0.05; done
        for ((r = SMALL_ROWS; r <= BIG_ROWS; r += ${DRAG_STEP:-2})); do resize "$r" "$BIG_COLS"; sleep 0.05; done
    done
}

# fullscreen —— 用户手上真正复现的那个手势：先最大化/全屏，再缩回来。
#
# 注意它**不是** `CSI 8;rows;cols t`：AppleScript 的 `set zoomed` 让 Terminal.app 自己做窗口
# 动画，过程中会连发一串中间尺寸（SIGWINCH 合并是已知边界），这正是 CSI 8 t 那种一步到位的
# 尺寸设置模拟不出来的。`zoomed` 是窗口属性、不需要辅助功能权限（本机没有授予，且不能依赖它）。
zoom_window() {
    osascript <<EOF >/dev/null 2>&1 || true
tell application "Terminal"
    repeat with w in windows
        if id of w is $WINDOW_ID then
            repeat with tb in tabs of w
                try
                    if tty of tb is "$TTY_PATH" then set zoomed of w to $1
                end try
            end repeat
        end if
    end repeat
end tell
EOF
}

pattern_fullscreen() {
    local gap="${ZOOM_SLEEP:-1.2}"
    for ((i = 0; i < CYCLES; i++)); do
        zoom_window true;  sleep "$gap"
        zoom_window false; sleep "$gap"
    done
}

pattern_storm() {
    local r c
    for ((i = 0; i < CYCLES * 8; i++)); do
        r=$(( SMALL_ROWS + RANDOM % (BIG_ROWS - SMALL_ROWS + 1) ))
        c=$(( SMALL_COLS + RANDOM % (BIG_COLS - SMALL_COLS + 1) ))
        resize "$r" "$c"; sleep 0.12
    done
}

# 判定分两半，缺一不可。
#
# 1. **不留副本**：spinner 与状态坞是[[活区]]专属的行，只该活在当前屏，任何一份出现在回卷里都是缺陷。
# 2. **[[bounded replay window]] 完整**：缩放之后，被测驱动写过的每一条编号转写行都必须
#    **恰好出现一次**。少一条 = 缩放把它毁了；多一条 = 缩放把它复制了。
#
# 第二条不是可选的护栏，而是这个脚本此前最大的盲点。2026-08 有一版「修复」把上退距离统一 +1，
# 七个 pattern 的副本数全部归零——同一次跑却**永久烧掉了活区上方的两行转写**，而当时的 check 只数
# 副本、从不检查完整性，于是那个海市蜃楼被记成了成功。`clr_eos` 只擦不推回卷，被多擦的行是找不
# 回来的，丢转写比留副本严重。
#
# 注意这条完整性判据是**在新契约下**才成立的：[[destructive resize reset]] 会连同终端保存的回卷一起
# 擦掉，再把重画界按新尺寸画回来。驱动写的 60 行（每行一个条目、1 逻辑行）远小于 3 × 44 行的预算，
# 所以缩放收尾停在大尺寸时，它们必须一条不少地回来。若把驱动写的行数调大到超过预算，这里要改成
# 「最新的 N 条恰好各一次、更旧的一条都不在」。
#
# 用宽松的锚点，免得被重排从中间撕开之后反而数不到（撕开的副本仍会保留左半段）。
TRANSCRIPT_LINES=60   # 必须与 RenderReproDriver --storm 铺屏的行数一致
check() {
    local label="$1" text="$2" rc=0
    local spinner dock report missing duplicated present
    if [[ -z "$text" || "$text" == "TARGET-WINDOW-GONE" ]]; then
        printf '%-8s ' "$label"
        echo "FAIL —— 取不到目标窗口的回卷（被测进程可能已退出，把 --storm 秒数调大）"
        return 1
    fi
    spinner=$(grep -c 'esc to cancel' <<< "$text" || true)
    dock=$(grep -c 'Ctrl+Y to enable HITL' <<< "$text" || true)
    if [[ "$spinner" -eq 0 ]]; then
        printf '%-8s ' "$label"
        echo "FAIL —— 回卷里连一份活区都没有，说明这一轮根本没测到（被测进程已结束？）"
        return 1
    fi
    report=$(awk -v total="$TRANSCRIPT_LINES" '
        match($0, /transcript line [0-9]+ /) {
            seen[substr($0, RSTART + 16, RLENGTH - 17)]++
        }
        END {
            missing = ""; duplicated = ""; present = 0
            for (i = 0; i < total; i++) {
                c = (i in seen) ? seen[i] : 0
                if (c == 0) { missing = missing " " i }
                else { present++ }
                if (c > 1) { duplicated = duplicated " " i "(x" c ")" }
            }
            printf "%d|%s|%s", present, missing, duplicated
        }' <<< "$text")
    present="${report%%|*}"
    missing="${report#*|}"; missing="${missing%%|*}"
    duplicated="${report##*|}"
    printf '%-8s spinner=%-3s dock=%-3s replay=%s/%s ' \
        "$label" "$spinner" "$dock" "$present" "$TRANSCRIPT_LINES"
    # 三条判据各自独立报告，不是 elif 链。曾经是链，于是一次「活区被复制进回卷」会**掩盖**同一次跑里的
    # 转写重复：2026-08-21 那次 heightdrag 失败实际上把转写 23..59 也复制了一份，而输出里仍写着
    # `replay=60/60`（那个数字只表示 60 行都在，不表示各只有一份），差点把「沉积的是一整屏」这条线索藏掉。
    if [[ "$spinner" -gt 1 || "$dock" -gt 1 ]]; then
        echo "FAIL —— 活区被复制进回卷"
        echo "--- 回卷中命中的行 ---"
        grep -n -e 'esc to cancel' -e 'Ctrl+Y to enable HITL' <<< "$text" | head -40
        echo "----------------------"
        rc=1
    fi
    if [[ -n "$missing" ]]; then
        [[ "$rc" -eq 0 ]] || printf '%-8s ' "$label"
        echo "FAIL —— 重画界不完整：缩放毁掉了转写行$missing"
        rc=1
    fi
    if [[ -n "$duplicated" ]]; then
        [[ "$rc" -eq 0 ]] || printf '%-8s ' "$label"
        echo "FAIL —— 重画界重复：转写行被画了不止一次$duplicated"
        rc=1
    fi
    if [[ "$rc" -eq 0 ]]; then
        echo "ok"
    fi
    return $rc
}

run_pattern() {
    local name="$1" seconds="$2" trace_file="" history_file="" capture_id="" exact_anchor=""
    local capture_request="" capture_ready=""
    if [[ -n "$TRACE_BASE" ]]; then
        trace_file="$TRACE_BASE.$name.jsonl"
        history_file="$TRACE_BASE.$name.history.txt"
        capture_request="$trace_file.capture-request"
        capture_ready="$trace_file.capture-ready"
        if ! mkdir -p -- "$(dirname "$trace_file")"; then
            echo "无法创建 trace 目录：$(dirname "$trace_file")" >&2
            return 1
        fi
        # 既避免把旧文件误认成 shutdown hook 已完成，也绝不覆盖一条可能很难复现的既有 oracle。
        if [[ -e "$trace_file" || -L "$trace_file" || -e "$history_file" || -L "$history_file"
              || -e "$capture_request" || -L "$capture_request"
              || -e "$capture_ready" || -L "$capture_ready" ]]; then
            echo "trace 目标已存在；请换一个 TRACE base：$trace_file / $history_file" >&2
            return 1
        fi
        capture_id="$name-$$-$RANDOM-$RANDOM"
        exact_anchor="$REPLAY_ANCHOR $capture_id"
    fi
    ACTIVE_TRACE_FILE="$trace_file"
    ACTIVE_CAPTURE_REQUEST="$capture_request"
    ACTIVE_CAPTURE_READY="$capture_ready"
    if ! open_target "$seconds" "$trace_file" "$capture_id" "$capture_request" "$capture_ready"; then
        ACTIVE_TRACE_FILE=""
        cleanup_capture_control
        return 1
    fi
    case "$name" in
        zoom)       pattern_zoom ;;
        drag)       pattern_drag ;;
        height)     pattern_height ;;
        heightdrag) pattern_heightdrag ;;
        fullscreen) pattern_fullscreen ;;
        storm)      pattern_storm ;;
        *) echo "未知 pattern: $name" >&2; close_target; exit 2 ;;
    esac
    # 停在大尺寸收尾，让最后一帧稳定下来再取回卷。
    resize "$BIG_ROWS" "$BIG_COLS"
    sleep 1.2
    local text dump_text rc close_rc=0 i
    if [[ -n "$trace_file" ]]; then
        # Atomically publish the request. The target keeps its current live frame, stops every producer,
        # waits for an in-flight writer, closes/persists the recorder, and only then creates ready.
        if ! (set -o noclobber; printf 'quiesce\n' > "$capture_request") 2>/dev/null; then
            echo "无法创建 replay capture request：$capture_request" >&2
            close_target "$trace_file" || true
            cleanup_capture_control
            return 1
        fi
        for ((i = 0; i < 750; i++)); do
            [[ -s "$capture_ready" ]] && break
            [[ -n "$(target_java_pids "${TTY_PATH#/dev/}")" ]] || break
            sleep 0.02
        done
        if [[ ! -s "$capture_ready" ]]; then
            echo "被测进程未建立 replay capture endpoint：$capture_ready" >&2
            close_target "$trace_file" || true
            cleanup_capture_control
            return 1
        fi
        if [[ ! -s "$trace_file" ]]; then
            echo "capture ready 已出现但 trace 尚未落盘：$trace_file" >&2
            close_target "$trace_file" || true
            cleanup_capture_control
            return 1
        fi
        # No more app bytes can be submitted. Give Terminal.app time to consume the PTY bytes already
        # accepted by the kernel; this is not and does not pretend to be a terminal-processing fence.
        sleep 0.2
    elif [[ -n "${DUMP:-}" ]]; then
        # Historical DUMP-only controls still freeze the producer because they have no trace endpoint.
        if ! pause_target; then
            close_target "$trace_file" || true
            return 1
        fi
        # The process is unable to append bytes while this best-effort diagnostic snapshot is taken.
        sleep 0.2
    fi
    if [[ -n "$trace_file" ]]; then
        if ! capture_history "$history_file" "$exact_anchor"; then
            echo "无法把 Terminal.app history 写到 $history_file" >&2
            close_target "$trace_file" || true
            cleanup_capture_control
            return 1
        fi
        text="$(< "$history_file")"
        echo "trace history 已存到 $history_file"
    else
        text="$(history_text)"
    fi
    if [[ -n "${DUMP:-}" ]]; then
        # DUMP keeps its historical full-history shape even when TRACE also requests anchored truth.
        if [[ -n "$trace_file" ]]; then dump_text="$(history_text)"; else dump_text="$text"; fi
        printf '%s\n' "$dump_text" > "$DUMP.$name"
        echo "回卷已存到 $DUMP.$name"
    fi
    check "$name" "$text"
    rc=$?
    if [[ -n "$trace_file" ]]; then
        # TRACE ignores KEEP: the quiesced target exists only to keep Terminal.app history readable.
        # The trace was already persisted before ready; termination cannot extend its byte boundary.
        close_target "$trace_file" || close_rc=1
        if [[ "$close_rc" -eq 0 ]]; then
            echo "replay trace 已存到 $trace_file"
        fi
    elif [[ -z "${KEEP:-}" ]]; then
        close_target || close_rc=1
    elif [[ "$TARGET_PAUSED" -eq 1 ]]; then
        # KEEP remains compatible for DUMP controls: retain the window, but never leave its JVM stopped.
        resume_target
    fi
    if [[ "$close_rc" -ne 0 ]]; then rc=1; fi
    ACTIVE_TRACE_FILE=""
    cleanup_capture_control
    return $rc
}

overall=0
case "$PATTERN" in
    all)
        run_pattern zoom       40 || overall=1
        run_pattern drag       60 || overall=1
        run_pattern height     40 || overall=1
        run_pattern heightdrag 60 || overall=1
        run_pattern fullscreen 60 || overall=1
        run_pattern storm      40 || overall=1
        ;;
    *)
        run_pattern "$PATTERN" 60 || overall=1
        ;;
esac

exit $overall
