#!/usr/bin/env bash
# GIF #1 — the destructive resize reset, filmed on a real Terminal.app.
#
# This one cannot be recorded with vhs: vhs 0.11 has no mid-tape resize command,
# and it renders in headless ttyd/xterm.js rather than Terminal.app. The claim
# being demonstrated — that a geometry change leaves clean scrollback instead of
# reflow smear — is a property of the emulator (ADR 0010 A6 refuses a universal
# guarantee), so it has to be filmed in the emulator we claim it for.
#
#   scripts/record-demo.sh auto            # hands-free: the whole GIF #1, start to finish
#
# `auto` needs BOTH Screen Recording and Accessibility granted to the terminal
# that runs it, and that terminal restarted afterwards — macOS does not apply
# either grant to an already-running process.
#
# The manual path, if you would rather not grant those:
#
#   scripts/record-demo.sh check
#   scripts/record-demo.sh stage           # open + size a Terminal.app window, print the shot list
#   scripts/record-demo.sh record demo.mov 45
#   scripts/record-demo.sh shrink          # run in ANOTHER terminal, mid-recording
#   scripts/record-demo.sh convert demo.mov docs/assets/resize-and-overlay.gif
#
# GIF #2 (the vhs one) is driven from here too, so it runs from the repo root
# and its output directory exists before the take rather than after it:
#
#   scripts/record-demo.sh tape
#
# `record` needs Screen Recording permission for your terminal
# (System Settings → Privacy & Security → Screen Recording).
# `stage`/`shrink` need Automation permission for Terminal.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_ROOT/target/cafecli-1.0.0.jar"

# Staging geometry, in screen POINTS rather than rows: the take moves the window
# between two states a viewer can name — half the screen (left half, full height)
# and a quarter of it (top-left quadrant).
#
# Left half rather than top half on purpose. A full-width window on this display
# is ~190 columns; scaled down to a README-sized GIF the glyphs land around four
# pixels tall and nothing is readable. Half-width keeps the GIF near 1:1. The
# cost is a portrait aspect ratio, which is the trade to revisit if it reads
# badly on the page — swap WIN_X/HALF_W for a top-half rect to flip it.
#
# The quarter state is also what makes the scrollback beat work: the retained
# replay window is 3 screenfuls (ReplayWindow.DEFAULT_SCREENFULS), so it
# overflows the halved window and spills into real scrollback. Resize to
# something taller and scrolling up finds an empty screen, which reads as
# "history lost" rather than "history intact".
WIN_X=20
WIN_Y=70

# Used only by the manual `stage`/`shrink` path, which still works in rows.
COLS=100
ROWS=38
SHRINK_TO=20

# Margin around the window in the capture region, so the window visibly gets
# smaller against a static desktop. Crop tight and the resize becomes invisible.
MARGIN=48

# Where to park the demo window. Not cosmetic: Terminal.app opens wherever it
# likes, and the first take landed at x=862 on a 1728-point screen — the window
# ran 54 points off the right edge, screencapture clamped the region to the
# display, and the right-hand column of every line was cut out of the recording.
POS_X=140
POS_Y=120

FPS=12
WIDTH=900

# Crop applied to the auto take, as ffmpeg expressions so it is independent of
# the Retina pixel doubling. The bottom third of the region is matte once the
# window has shrunk: dead pixels that inflate the file, stretch the aspect ratio,
# and are where the mouse pointer parks.
AUTO_CROP="iw:ih*0.62:0:0"

# Enough tool calls and prose to fill several screenfuls, so the shrunk window
# has real scrollback above it. No quotes: this is interpolated into AppleScript.
DEMO_PROMPT="walk me through the block pipeline in render/inline and show me the key types"

# Set by open_target_tab(), used by everything that talks to the demo session.
TARGET_TTY=""
TARGET_WINDOW=""
TARGET_TAB=""

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '\033[2m%s\033[0m\n' "$*"; }

usage() {
  awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

require_macos() {
  [[ "$(uname -s)" == "Darwin" ]] || die "this script drives Terminal.app; macOS only"
}

# Usable desktop as "w h" in points, for clamping the capture region.
screen_size() {
  osascript -e 'tell application "Finder" to get bounds of window of desktop' 2>/dev/null \
    | awk -F', *' '{print $3, $4}'
}

# Front Terminal window bounds as "x y w h" (screen points, not pixels).
window_rect() {
  osascript <<'APPLESCRIPT' 2>/dev/null || die "could not read the Terminal window bounds (grant Automation permission, or resize by hand)"
tell application "Terminal"
  set b to bounds of front window
  return ((item 1 of b) as string) & " " & ((item 2 of b) as string) & " " & ¬
         (((item 3 of b) - (item 1 of b)) as string) & " " & ¬
         (((item 4 of b) - (item 2 of b)) as string)
end tell
APPLESCRIPT
}

cmd_check() {
  require_macos
  local ok=0
  for tool in ffmpeg osascript screencapture java; do
    if command -v "$tool" >/dev/null 2>&1; then
      printf '  ok    %s\n' "$tool"
    else
      printf '  MISS  %s\n' "$tool"; ok=1
    fi
  done
  if [[ -f "$JAR" ]]; then
    printf '  ok    %s\n' "${JAR#"$REPO_ROOT"/}"
  else
    printf '  MISS  %s  (run: mvn clean package)\n' "${JAR#"$REPO_ROOT"/}"; ok=1
  fi
  [[ -n "${GLM_API_KEY:-}${DEEPSEEK_API_KEY:-}${STEP_API_KEY:-}${KIMI_API_KEY:-}${FREELLMAPI_API_KEY:-}" ]] \
    && printf '  ok    an API key is exported\n' \
    || { printf '  MISS  no API key exported\n'; ok=1; }
  return $ok
}

# --- driving a specific Terminal.app session -------------------------------
# Same targeting as scripts/resize-storm.sh: identify the tab by tty AND window
# id, never by "front window", so a stray window cannot be driven by accident.

open_target_tab() {
  local identity
  identity="$(osascript <<'APPLESCRIPT'
tell application "Terminal"
  activate
  set t to do script ""
  set targetTty to tty of t
  -- Terminal publishes the tab before its owning window joins the collection.
  delay 0.3
  repeat with w in windows
    set i to 0
    repeat with tb in tabs of w
      set i to i + 1
      try
        if tty of tb is targetTty then return targetTty & "|" & (id of w) & "|" & i
      end try
    end repeat
  end repeat
end tell
error "new Terminal.app tab has no owning window"
APPLESCRIPT
)" || die "could not open a Terminal.app tab (Automation permission?)"
  IFS='|' read -r TARGET_TTY TARGET_WINDOW TARGET_TAB <<<"$identity"
  [[ -n "$TARGET_TTY" && -n "$TARGET_WINDOW" && -n "$TARGET_TAB" ]] \
    || die "could not identify the target tab"
}

# The visible screen of the target tab.
#
# Must use a DIRECT specifier (`tab N of window id M`). Inside a
# `repeat with tb in tabs of w` loop, `contents of tb` is AppleScript's
# reference-dereferencing operator, not Terminal's `contents` property, and
# silently yields the string "tab 1 of window id 10271" instead of the screen.
# That is why the tab index is resolved once, up front.
tab_contents() {
  osascript -e "tell application \"Terminal\" to get contents of tab $TARGET_TAB of window id $TARGET_WINDOW" 2>/dev/null
}

# Poll the screen for an anchor. This is what makes `auto` reproducible instead
# of a pile of hopeful sleeps: the same anchors the vhs tape uses.
wait_for_screen() {
  local pattern="$1" timeout="${2:-90}" label="${3:-$1}" waited=0
  while (( waited < timeout )); do
    if tab_contents | grep -qE "$pattern"; then return 0; fi
    sleep 1; waited=$((waited + 1))
  done
  die "timed out after ${timeout}s waiting for: $label"
}

# Text input. `do script` appends a return, which is exactly a submit.
send_line() {
  osascript -e "tell application \"Terminal\" to do script \"$1\" in tab $TARGET_TAB of window id $TARGET_WINDOW" >/dev/null
}

# Geometry. Writing XTWINOPS to the tty makes Terminal.app itself resize and the
# app sees only SIGWINCH — the same path as a hand drag (resize-storm.sh:357).
# Kept for row/column work; the take itself resizes in points, below.
xtwinops() { printf '\033[8;%d;%dt' "$1" "$2" > "$TARGET_TTY"; }

# Pixel-exact resize, so the take can hit named targets (half screen, a quarter
# of it) that rows and columns cannot express. Also a real window resize, so the
# app still sees nothing but SIGWINCH.
set_window_bounds() { # x y w h
  osascript -e "tell application \"Terminal\" to set bounds of window id $TARGET_WINDOW to {$1, $2, $(( $1 + $3 )), $(( $2 + $4 ))}" >/dev/null 2>&1 || true
}

# Keystrokes Terminal.app owns rather than the app: scrollback paging, and the
# app's own Ctrl+O / ESC. Needs Accessibility for the host terminal.
send_key() {
  osascript <<APPLESCRIPT >/dev/null 2>&1
tell application "Terminal" to activate
delay 0.15
tell application "System Events" to $1
APPLESCRIPT
}

# Terminal.app's own scrollback paging.
#
# The binding is PLAIN Page Up, not Shift+Page Up: `View → Page Up` reports
# AXMenuItemCmdModifiers = 0 with the Page Up glyph. Sending it with {shift down}
# left the screen byte-identical, which is what made the first take's scroll beat
# a no-op. Clicking the menu item works too, but flashes the dropdown into frame.
scroll_page_up() { send_key 'key code 116'; }
scroll_to_bottom() {
  osascript -e 'tell application "System Events" to tell process "Terminal" to click menu item "Scroll to Bottom" of menu 1 of menu bar item "View" of menu bar 1' >/dev/null 2>&1
}

# Everything the shrinking window uncovers is on camera. Hiding every other app
# was tried first and rejected: it is invasive, it misses floating utility
# windows (a proxy log window survived it, with the user's request endpoints
# legible on screen), and hiding reveals desktop widgets anyway. A matte window
# behind the demo is deterministic and touches nothing outside Terminal.
BACKDROP_WINDOW=""
open_backdrop() { # left top right bottom, already clamped to the display
  local bl="$1" bt="$2" br="$3" bb="$4"
  BACKDROP_WINDOW="$(osascript <<APPLESCRIPT 2>/dev/null || true
tell application "Terminal"
  set t to do script ""
  delay 0.4
  set bid to id of front window
  -- Bounds must already fit the display. macOS clamps an oversized window, and
  -- that clamp is itself a resize: it fires after the clear below would have
  -- run, pulling the login banner of this matte back down out of scrollback
  -- and into the bottom of frame.
  set bounds of window id bid to {$bl, $bt, $br, $bb}
  set background color of window id bid to {0, 0, 0}
  delay 0.4
  -- Clear AFTER resizing, never before: growing a Terminal window pulls lines
  -- back down out of scrollback, so a matte cleared first comes back covered in
  -- the login banner. The cat parks the shell so no prompt is redrawn either.
  -- No apostrophes or backticks in this heredoc: it sits inside a command
  -- substitution, where bash 3.2 mis-parses the first and executes the second.
  do script "clear; tput civis; cat > /dev/null" in t
  return bid
end tell
APPLESCRIPT
)"
}
close_backdrop() {
  [[ -n "$BACKDROP_WINDOW" ]] || return 0
  osascript -e "tell application \"Terminal\" to close window id $BACKDROP_WINDOW" >/dev/null 2>&1 || true
  BACKDROP_WINDOW=""
}
# Both halves are required. `set frontmost` only orders windows WITHIN Terminal;
# without `activate`, whatever app was in front (the terminal you launched this
# from) stays on top and the opening seconds of the take film that instead.
raise_demo_window() {
  osascript -e 'tell application "Terminal" to activate' >/dev/null 2>&1 || true
  osascript -e "tell application \"Terminal\" to set frontmost of window id $TARGET_WINDOW to true" >/dev/null 2>&1 || true
}

require_accessibility() {
  local enabled
  enabled="$(osascript -e 'tell application "System Events" to get UI elements enabled' 2>/dev/null)"
  [[ "$enabled" == "true" ]] || die "Accessibility is not granted to this terminal.
  System Settings → Privacy & Security → Accessibility, then RESTART this terminal.
  (Checked via 'UI elements enabled'; the grant does not apply to an already-running process.)"
}

# GIF #1, end to end and hands-free.
cmd_auto() {
  require_macos
  require_accessibility
  [[ -f "$JAR" ]] || die "missing $JAR — run: mvn clean package"
  local out="${1:-$REPO_ROOT/docs/assets/resize-and-overlay.gif}"
  local mov; mov="$(mktemp -t cafecli-demo).mov"

  local sw sh
  read -r sw sh <<<"$(screen_size)"
  local half_w=$(( sw / 2 ))
  local half_h=$(( sh - WIN_Y ))
  local quarter_h=$(( half_h / 2 ))

  note "opening the demo session at half screen (${half_w}x${half_h})"
  open_target_tab
  set_window_bounds "$WIN_X" "$WIN_Y" "$half_w" "$half_h"
  sleep 0.5
  send_line "cd $REPO_ROOT && clear && java -jar $JAR"
  wait_for_screen 'freshly brewed agents' 90 'the startup banner'
  wait_for_screen 'message / @path / @image' 60 'the idle prompt'

  # The region is fixed at the HALF-screen rect: the window shrinks inside a
  # stationary frame, and the area it uncovers is matte rather than desktop.
  local rx=$(( WIN_X > 20 ? WIN_X - 20 : 0 ))
  local ry=$WIN_Y
  local rw=$(( half_w + 40 )) rh=$half_h
  (( rx + rw > sw )) && rw=$(( sw - rx ))
  (( ry + rh > sh )) && rh=$(( sh - ry ))

  # Slack on every side (Terminal snaps to whole character cells, so an exact fit
  # can round DOWN and leave a seam), then clamped to the display.
  local bl=$(( rx - 40 )) bt=$(( ry - 30 )) br=$(( rx + rw + 40 )) bb=$(( ry + rh + 40 ))
  (( bl < 0 )) && bl=0
  (( bt < 40 )) && bt=40
  (( br > sw )) && br=$sw
  (( bb > sh )) && bb=$sh

  trap close_backdrop EXIT INT TERM
  note "placing the matte behind the demo window"
  open_backdrop "$bl" "$bt" "$br" "$bb"
  sleep 0.5
  raise_demo_window
  sleep 1

  # Submit and start filming while the model is still streaming — the resize has
  # to land mid-output, not after the turn settles.
  note "submitting the prompt"
  send_line "$DEMO_PROMPT"
  wait_for_screen 'steer' 90 'the turn to start'
  sleep 6

  # Park the pointer outside frame. `screencapture -v` always draws the cursor
  # (-C only applies to still capture), and System Events can only move the
  # pointer by clicking. The matte is deliberately 40pt wider than the capture
  # region, so there is a strip of it just out of frame that is safe to click;
  # the click raises the matte, so the demo window goes back on top after.
  osascript -e "tell application \"System Events\" to click at {$(( rx + rw + 20 )), $(( ry + rh / 2 ))}" >/dev/null 2>&1 || true
  sleep 0.4
  raise_demo_window
  sleep 0.6

  note "recording ${rw}x${rh} at ${rx},${ry}"
  screencapture -v -V 34 -x -R"${rx},${ry},${rw},${rh}" "$mov" &
  local cap=$!
  sleep 5

  # Eight steps, not one snap and not a per-row storm. Nineteen resizes inside a
  # second makes Terminal.app thrash and stretches the erase-to-repaint gap to
  # 2-3 seconds of blank screen — correct behaviour (ERASE_ONLY through
  # turbulence, one RESET on settle, ADR 0010 A3b) that reads as a crash on
  # camera. This is slow enough to look like a drag and to settle promptly.
  note "  resize half screen → quarter screen, mid-stream"
  local i h
  for (( i = 1; i <= 8; i++ )); do
    h=$(( half_h - (half_h - quarter_h) * i / 8 ))
    set_window_bounds "$WIN_X" "$WIN_Y" "$half_w" "$h"
    sleep 0.08
  done
  sleep 5

  # Cancel before browsing. Terminal.app pulls the view back to the bottom when
  # new output arrives, so scrolling up under a live stream fights the terminal
  # and the beat reads as a stutter.
  note "  cancel the turn, then scroll into scrollback"
  send_key 'key code 53'
  sleep 2
  for _ in 1 2 3; do scroll_page_up; sleep 0.9; done
  sleep 2
  scroll_to_bottom
  sleep 1

  # The overlay opens at the tail. Jumping to the very top with `g` is the whole
  # point of the beat: the terminal's own scrollback only holds what the reset
  # repainted, while the retained window holds the entire session — so the start
  # of the turn is still there to scroll back to.
  note "  Ctrl+O overlay, then g to the very start of the session"
  send_key 'keystroke "o" using {control down}'
  sleep 3
  # Written to the tty, NOT sent as a keystroke. System Events keystrokes pass
  # through the active input method, and under a Chinese IME a plain letter opens
  # a candidate bar instead of reaching the app: the take showed "closeg" in the
  # overlay footer and a pinyin popup on camera. Control-modified keys (Ctrl+O)
  # and key codes (ESC, Page Up) are not intercepted, so only letters need this.
  # The trailing newline do script appends is not a key the overlay binds.
  send_line "g"
  sleep 5
  send_key 'key code 53'
  sleep 2

  wait "$cap" 2>/dev/null || true
  [[ -s "$mov" ]] || die "screencapture produced nothing — is Screen Recording granted to this terminal, and was it restarted after granting?"
  note "captured $(du -h "$mov" | cut -f1)"
  # The .mov is KEPT on purpose. Cropping, trimming, and re-encoding are all
  # convert-time decisions, and deleting the source made every one of them cost
  # another live model turn.
  cmd_convert "$mov" "$out" ${AUTO_CROP:+--crop "$AUTO_CROP"}
  note "source kept at $mov — re-run convert on it to retune without re-recording"
}

# GIF #2. vhs resolves `Output` against its own cwd and does not create the
# directory, so a missing docs/assets/ would fail only after the full take.
cmd_tape() {
  local tape="${1:-$REPO_ROOT/scripts/demo-steering.tape}"
  command -v vhs >/dev/null 2>&1 || die "vhs not installed (brew install vhs)"
  [[ -f "$JAR" ]] || die "missing $JAR — run: mvn clean package"
  [[ -f "$tape" ]] || die "no such tape: $tape"
  mkdir -p "$REPO_ROOT/docs/assets"
  cd "$REPO_ROOT"
  note "vhs $tape (cwd: $REPO_ROOT)"
  vhs "$tape"
}

cmd_stage() {
  require_macos
  [[ -f "$JAR" ]] || die "missing $JAR — run: mvn clean package"

  osascript <<APPLESCRIPT >/dev/null
tell application "Terminal"
  activate
  do script "cd $REPO_ROOT && clear && java -jar $JAR"
  delay 0.5
  set number of columns of front window to $COLS
  set number of rows of front window to $ROWS
  set position of front window to {120, 80}
end tell
APPLESCRIPT

  local rect x y w h
  rect="$(window_rect)"; read -r x y w h <<<"$rect"

  cat <<EOF

Staged a ${COLS}x${ROWS} Terminal.app window at ${w}x${h} (points), origin ${x},${y}.

Shot list — do these in the staged window:

  1. Ask something that produces a few screenfuls of transcript, e.g.
       walk me through the render/inline block pipeline and show the key types

  2. Wait for the turn to finish (right prompt back to "message / @path / @image").

  3. Start the recording from a SECOND terminal:
       scripts/record-demo.sh record demo.mov 45

  4. Shrink the window. Either drag the bottom-right corner slowly by hand
     (most legible), or hands-free from the second terminal:
       scripts/record-demo.sh shrink

     Do NOT use the green fullscreen button. Animated fullscreen is the gesture
     scripts/resize-storm.sh targets and the noisiest one historically
     (.scratch/deterministic-replay-oracle/PRD.md); it is the wrong thing to
     film on the first take.

  5. Scroll UP with the trackpad. This is the payoff: clean scrollback, no
     duplicated live-region rows, no reflow smear.

  6. Press Ctrl+O. The full session is still there — the retained window has no
     line bound, and the destructive reset never touched it. Scroll, then ESC.

  7. Convert:
       scripts/record-demo.sh convert demo.mov docs/assets/resize-and-overlay.gif

Shoot three takes. AGENTS.md is explicit that this regression loop is
noise-dominated, so one clean take is not evidence — and a take that shows
smearing is a bug report, not a bad take.
EOF
}

cmd_record() {
  require_macos
  local out="${1:-demo.mov}" secs="${2:-45}"
  local rect x y w h
  rect="$(window_rect)"; read -r x y w h <<<"$rect"

  # Fix the region at the window's CURRENT (pre-shrink) size plus a margin, so
  # the shrink happens inside a stationary frame.
  local rx=$(( x > MARGIN ? x - MARGIN : 0 ))
  local ry=$(( y > MARGIN ? y - MARGIN : 0 ))
  local rw=$(( w + 2 * MARGIN ))
  local rh=$(( h + 2 * MARGIN ))

  note "recording ${rw}x${rh} at ${rx},${ry} for ${secs}s → $out"
  note "starting in 3s — switch to the staged window now"
  sleep 3
  screencapture -v -V "$secs" -C -x -R"${rx},${ry},${rw},${rh}" "$out"
  note "wrote $out ($(du -h "$out" | cut -f1))"
}

# Row-by-row shrink: many real SIGWINCHes rather than one snap, which is what
# exercises the deferred reset — ERASE_ONLY through the turbulence, exactly one
# RESET when the geometry settles (ADR 0010 A3b).
cmd_shrink() {
  require_macos
  local from="${1:-$ROWS}" to="${2:-$SHRINK_TO}" delay="${3:-0.05}"
  note "shrinking $from → $to rows"
  osascript <<APPLESCRIPT >/dev/null
tell application "Terminal"
  activate
  repeat with r from $from to $to by -1
    set number of rows of front window to r
    delay $delay
  end repeat
end tell
APPLESCRIPT
}

cmd_convert() {
  local in="${1:-}" out="${2:-}"; shift 2 || usage 1
  [[ -n "$in" && -n "$out" ]] || usage 1
  [[ -f "$in" ]] || die "no such file: $in"

  local ss="" dur="" crop=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --fps) FPS="$2"; shift 2 ;;
      --width) WIDTH="$2"; shift 2 ;;
      --ss) ss="$2"; shift 2 ;;      # trim: start offset, e.g. --ss 4
      --t) dur="$2"; shift 2 ;;      # trim: duration, e.g. --t 30
      --crop) crop="$2"; shift 2 ;;  # ffmpeg crop, e.g. --crop 904:760:0:0
      *) die "unknown flag: $1" ;;
    esac
  done

  local trim=()
  [[ -n "$ss" ]] && trim+=(-ss "$ss")
  [[ -n "$dur" ]] && trim+=(-t "$dur")

  mkdir -p "$(dirname "$out")"
  local palette; palette="$(mktemp -t cafecli-palette).png"

  # stats_mode=diff / diff_mode=rectangle are the pair that matters for screen
  # recordings: the palette is spent on the parts that actually change, and
  # static regions are not re-dithered frame to frame.
  ffmpeg -hide_banner -loglevel error ${trim[@]+"${trim[@]}"} -i "$in" \
    -vf "${crop:+crop=$crop,}fps=$FPS,scale=$WIDTH:-1:flags=lanczos,palettegen=stats_mode=diff" \
    -y "$palette"

  ffmpeg -hide_banner -loglevel error ${trim[@]+"${trim[@]}"} -i "$in" -i "$palette" \
    -lavfi "${crop:+crop=$crop,}fps=$FPS,scale=$WIDTH:-1:flags=lanczos[v];[v][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
    -y "$out"

  rm -f "$palette"
  local bytes; bytes="$(stat -f%z "$out")"
  printf 'wrote %s (%s, %sfps, %spx wide)\n' "$out" "$(du -h "$out" | cut -f1)" "$FPS" "$WIDTH"
  if (( bytes > 10485760 )); then
    die "over 10MB — GitHub will not inline this. Trim with --ss/--t, or drop --fps to 10."
  elif (( bytes > 5242880 )); then
    note "over 5MB — usable but slow to load. Consider --t to trim dead time."
  fi
}

case "${1:-}" in
  check)   shift; cmd_check "$@" ;;
  auto)    shift; cmd_auto "$@" ;;
  tape)    shift; cmd_tape "$@" ;;
  stage)   shift; cmd_stage "$@" ;;
  record)  shift; cmd_record "$@" ;;
  shrink)  shift; cmd_shrink "$@" ;;
  convert) shift; cmd_convert "$@" ;;
  ""|-h|--help|help) usage 0 ;;
  *) die "unknown command: $1 (try: check tape stage record shrink convert)" ;;
esac
