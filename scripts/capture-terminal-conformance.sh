#!/usr/bin/env bash
# Capture small, exact Terminal.app truths for the deterministic replay emulator.
#
#   OUTPUT_DIR=/tmp/cafecli-terminal-conformance scripts/capture-terminal-conformance.sh all
#   OUTPUT_DIR=/tmp/cafecli-terminal-conformance scripts/capture-terminal-conformance.sh cjk-right-edge
#
# Every case creates <case>.jsonl and <case>.history.txt from the same stopped JVM. Existing
# artifacts are never overwritten. The output is intentionally captured outside the repository;
# reviewed fixtures can then be added under src/test/resources with apply_patch.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
JAR="$ROOT/target/cafecli-1.0-SNAPSHOT.jar"
OUTPUT_DIR="${OUTPUT_DIR:-}"
REQUESTED_CASE="${1:-all}"
ANCHOR='[cafecli-conf:v1]'
INITIAL_ROWS=8
INITIAL_COLUMNS=24

if [[ "$(uname)" != "Darwin" ]]; then
    echo "This conformance capture requires macOS Terminal.app." >&2
    exit 2
fi
if [[ ! -f "$JAR" ]]; then
    echo "Missing $JAR; run mvn -o -DskipTests package first." >&2
    exit 2
fi
if [[ -z "$OUTPUT_DIR" ]]; then
    echo "Set OUTPUT_DIR to a new capture directory outside the repository." >&2
    exit 2
fi
mkdir -p -- "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

CASES=(
    hard-soft-wrap
    exact-multiple-cursor
    cjk-right-edge
    clear-to-end-of-screen
    height-shrink-scrollback
    cursor-down-clamp
    saved-cursor-reflow
    saved-cursor-reflow-shift
    erase-saved-scrollback
    erase-screen-pushes-scrollback
    reset-erase-leaves-nothing
)

TTY_PATH=""
WINDOW_ID=""
TARGET_PAUSED=0
READY_FILE=""

require_target_identity() {
    [[ -n "$TTY_PATH" && -n "$WINDOW_ID" ]] || return 1
    local matches
    matches="$(CAFECLI_CONFORMANCE_TTY="$TTY_PATH" \
        CAFECLI_CONFORMANCE_WINDOW_ID="$WINDOW_ID" osascript <<'EOF' 2>/dev/null || true
set targetTty to system attribute "CAFECLI_CONFORMANCE_TTY"
set targetWindowId to (system attribute "CAFECLI_CONFORMANCE_WINDOW_ID") as integer
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
    [[ "${matches:-0}" == "1" ]]
}

target_java_pids() {
    local tty_name="${TTY_PATH#/dev/}"
    ps -t "$tty_name" -o pid=,comm= 2>/dev/null \
        | awk '$2 == "java" || $2 ~ /\/java$/ {print $1}' || true
}

close_window() {
    [[ -n "$TTY_PATH" && -n "$WINDOW_ID" ]] || return 0
    CAFECLI_CONFORMANCE_TTY="$TTY_PATH" \
        CAFECLI_CONFORMANCE_WINDOW_ID="$WINDOW_ID" osascript <<'EOF' >/dev/null 2>&1 || true
set targetTty to system attribute "CAFECLI_CONFORMANCE_TTY"
set targetWindowId to (system attribute "CAFECLI_CONFORMANCE_WINDOW_ID") as integer
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
    TTY_PATH=""
    WINDOW_ID=""
}

stop_target() {
    local pids i state all_stopped
    if ! require_target_identity; then
        echo "Conformance Terminal.app target identity no longer matches." >&2
        return 1
    fi
    pids="$(target_java_pids)"
    if [[ -z "$pids" ]]; then
        echo "Conformance JVM is absent from $TTY_PATH." >&2
        return 1
    fi
    kill -STOP $pids
    TARGET_PAUSED=1
    for ((i = 0; i < 50; i++)); do
        all_stopped=1
        for pid in $pids; do
            state="$(ps -p "$pid" -o state= 2>/dev/null || true)"
            [[ "$state" == *T* ]] || all_stopped=0
        done
        [[ "$all_stopped" -eq 1 ]] && return 0
        sleep 0.02
    done
    echo "Conformance JVM did not stop in time." >&2
    return 1
}

terminate_target() {
    local expected_trace="$1" pids i
    if ! require_target_identity; then
        echo "Refusing to terminate a reused or missing conformance tty: $TTY_PATH." >&2
        return 1
    fi
    pids="$(target_java_pids)"
    if [[ -n "$pids" ]]; then
        kill -TERM $pids 2>/dev/null || true
        if [[ "$TARGET_PAUSED" -eq 1 ]]; then
            kill -CONT $pids 2>/dev/null || true
            TARGET_PAUSED=0
        fi
    fi
    for ((i = 0; i < 100; i++)); do
        [[ -z "$(target_java_pids)" ]] && break
        sleep 0.1
    done
    pids="$(target_java_pids)"
    if [[ -n "$pids" ]]; then
        echo "Conformance JVM ignored SIGTERM; forcing cleanup." >&2
        kill -KILL $pids 2>/dev/null || true
        close_window
        return 1
    fi
    for ((i = 0; i < 100; i++)); do
        [[ -s "$expected_trace" ]] && break
        sleep 0.05
    done
    if [[ ! -s "$expected_trace" ]]; then
        echo "Recorder did not persist $expected_trace." >&2
        close_window
        return 1
    fi
    close_window
}

cleanup() {
    if [[ -n "$TTY_PATH" ]]; then
        local pids remaining i tty_name="${TTY_PATH#/dev/}"
        if require_target_identity; then
            pids="$(target_java_pids)"
            if [[ -n "$pids" ]]; then
                kill -TERM $pids 2>/dev/null || true
                kill -CONT $pids 2>/dev/null || true
                sleep 0.2
                pids="$(target_java_pids)"
                [[ -z "$pids" ]] || kill -KILL $pids 2>/dev/null || true
            fi
            # An error before `exec java` leaves the newly-created login shell alive. Never ask
            # Terminal.app to close a tab that still owns processes: it raises a modal confirmation
            # dialog that AppleScript cannot dismiss without Accessibility permission.
            remaining="$(ps -t "$tty_name" -o pid= 2>/dev/null || true)"
            if [[ -n "$remaining" ]]; then
                kill -HUP $remaining 2>/dev/null || true
                for ((i = 0; i < 20; i++)); do
                    remaining="$(ps -t "$tty_name" -o pid= 2>/dev/null || true)"
                    [[ -z "$remaining" ]] && break
                    sleep 0.02
                done
                [[ -z "$remaining" ]] || kill -KILL $remaining 2>/dev/null || true
            fi
        fi
        close_window
    fi
    if [[ -n "$READY_FILE" ]]; then
        rm -f -- "$READY_FILE"
        READY_FILE=""
    fi
}
trap cleanup EXIT INT TERM

capture_history() {
    local output_path="$1" exact_anchor="$2"
    CAFECLI_CONFORMANCE_TTY="$TTY_PATH" \
        CAFECLI_CONFORMANCE_WINDOW_ID="$WINDOW_ID" \
        CAFECLI_CONFORMANCE_HISTORY="$output_path" \
        CAFECLI_CONFORMANCE_ANCHOR="$exact_anchor" osascript <<'EOF' >/dev/null
set targetTty to system attribute "CAFECLI_CONFORMANCE_TTY"
set targetWindowId to (system attribute "CAFECLI_CONFORMANCE_WINDOW_ID") as integer
set outputPath to system attribute "CAFECLI_CONFORMANCE_HISTORY"
set anchorText to system attribute "CAFECLI_CONFORMANCE_ANCHOR"
set capturedHistory to missing value
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
if capturedHistory is missing value then error "target Terminal.app tab disappeared"
set anchorOffset to offset of anchorText in capturedHistory
if anchorOffset is 0 then error "conformance anchor is absent from Terminal.app history"
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

capture_case() {
    local fixture_case="$1"
    local trace_file="$OUTPUT_DIR/$fixture_case.jsonl"
    local history_file="$OUTPUT_DIR/$fixture_case.history.txt"
    local target_command size i capture_id exact_anchor target_identity
    if [[ -e "$trace_file" || -L "$trace_file" || -e "$history_file" || -L "$history_file" ]]; then
        echo "Refusing to overwrite $trace_file or $history_file." >&2
        return 1
    fi
    READY_FILE="$(mktemp "$OUTPUT_DIR/.ready.$fixture_case.XXXXXX")"
    capture_id="$fixture_case-$$-$RANDOM-$RANDOM"
    exact_anchor="$ANCHOR $fixture_case $capture_id"

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
        echo "Could not identify the conformance Terminal.app target." >&2
        return 1
    fi
    printf '\033[8;%d;%dt' "$INITIAL_ROWS" "$INITIAL_COLUMNS" > "$TTY_PATH"
    size=""
    for ((i = 0; i < 50; i++)); do
        size="$(stty size < "$TTY_PATH" 2>/dev/null || true)"
        [[ "$size" == "$INITIAL_ROWS $INITIAL_COLUMNS" ]] && break
        sleep 0.05
    done
    if [[ "$size" != "$INITIAL_ROWS $INITIAL_COLUMNS" ]]; then
        echo "Initial size did not settle for $fixture_case: ${size:-unknown}." >&2
        return 1
    fi

    printf -v target_command \
        'cd %q && exec java -cp %q com.cafecli.render.inline.replay.TerminalConformanceDriver --case=%q --trace=%q --ready=%q --capture-id=%q' \
        "$ROOT" "$JAR" "$fixture_case" "$trace_file" "$READY_FILE" "$capture_id"
    CAFECLI_CONFORMANCE_COMMAND="$target_command" \
        CAFECLI_CONFORMANCE_TTY="$TTY_PATH" \
        CAFECLI_CONFORMANCE_WINDOW_ID="$WINDOW_ID" osascript <<'EOF' >/dev/null
set targetCommand to system attribute "CAFECLI_CONFORMANCE_COMMAND"
set targetTty to system attribute "CAFECLI_CONFORMANCE_TTY"
set targetWindowId to (system attribute "CAFECLI_CONFORMANCE_WINDOW_ID") as integer
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
error "target Terminal.app tab disappeared before launch"
EOF

    for ((i = 0; i < 200; i++)); do
        [[ -s "$READY_FILE" ]] && break
        sleep 0.05
    done
    if [[ ! -s "$READY_FILE" ]]; then
        echo "Conformance case did not reach its capture boundary: $fixture_case." >&2
        return 1
    fi
    stop_target
    sleep 0.2
    capture_history "$history_file" "$exact_anchor"
    terminate_target "$trace_file"
    rm -f -- "$READY_FILE"
    READY_FILE=""
    echo "captured $fixture_case"
}

if [[ "$REQUESTED_CASE" == "all" ]]; then
    for fixture_case in "${CASES[@]}"; do
        capture_case "$fixture_case"
    done
else
    known=0
    for fixture_case in "${CASES[@]}"; do
        if [[ "$fixture_case" == "$REQUESTED_CASE" ]]; then
            known=1
            capture_case "$fixture_case"
            break
        fi
    done
    if [[ "$known" -eq 0 ]]; then
        echo "Unknown conformance case: $REQUESTED_CASE" >&2
        exit 2
    fi
fi
