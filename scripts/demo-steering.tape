# GIF #2 — the resident input box, mid-turn steering, and the live transcript overlay.
#
#   scripts/record-demo.sh tape           # from anywhere — creates docs/assets/ first
#   vhs scripts/demo-steering.tape        # equivalent, but only from the repo root:
#                                         # `Output` is resolved against vhs's own cwd
#
# Prerequisites:
#   - target/cafecli-1.0.0.jar built (mvn clean package)
#   - an API key exported in the shell that launches vhs; vhs inherits its environment,
#     so nothing secret is written into this file
#
# What this films, in order:
#   1. a prompt is submitted and the turn starts
#   2. the user keeps typing WHILE the model streams — the input box never goes away
#   3. that mid-turn line is submitted as a 转向消息: it lands in the transcript at the
#      point where it interrupted the answer, and the agent changes course
#   4. Ctrl+O opens the 转写浏览层 DURING the live turn, and the overlay follows new output
#   5. scrolled up, the viewport holds position while output keeps arriving
#   6. G returns to the tail and following resumes
#   7. ESC closes the overlay without cancelling the turn
#
# Screen anchors used by Wait+Screen (keep these in sync if the chrome changes):
#   "freshly brewed agents"   startup banner        Main.startupBannerLines
#   "message / @path / ..."   idle right prompt     Renderer.inputRightPrompt
#   "steer"                   running right prompt  InteractiveRepl.currentRightPrompt
#   "Ctrl+T thinking"         overlay footer        TranscriptOverlay:46

Output docs/assets/steering-and-overlay.gif

Require java

Set Shell "bash"
Set FontSize 14
Set Width 900
Set Height 560
Set Padding 24
Set Framerate 15
Set TypingSpeed 40ms

# A blinking cursor costs a lot of GIF frames for nothing.
Set CursorBlink false

# The default 5s wait timeout is far too short for a model turn. Keep this tight
# anyway: every second spent waiting is a second of recorded GIF.
Set WaitTimeout 90s

# Optional: start the loop past the cold start so the GitHub thumbnail is mid-action.
# Set LoopOffset 20%

# ---------------------------------------------------------------------------
# Cold start is hidden. A 3–5s JVM + MCP boot is dead weight in a 30s budget,
# so the GIF opens on the ready banner instead.
# ---------------------------------------------------------------------------
Hide
# The shell inside vhs starts in vhs's cwd, which is not necessarily the repo
# root. Command substitution is typed literally and evaluated by bash, so this
# resolves the jar no matter where the tape was launched from. (A repo path
# containing spaces would need quoting here.)
Type "cd $(git rev-parse --show-toplevel) && clear"
Enter
Type "java -jar target/cafecli-1.0.0.jar"
Enter
# Shorter than the global timeout on purpose: a missing jar or a bad key should
# fail in a minute, not in three.
Wait+Screen@60s /freshly brewed agents/
Wait+Screen /message \/ @path \/ @image/
Sleep 1s
Show

Sleep 1500ms

# --- beat 1: a prompt that fans out across several tools -------------------
# Chosen for tool VARIETY at speed, not for difficulty: glob_files → parallel
# grep_code/read_file (ToolRegistry concurrency 4, so several blocks appear at
# once) → execute_command. All local, all read-only: no HITL approval interrupts
# the flow and the working tree stays clean.
#
# Deliberately no "recently" / "what changed": that phrasing trips the
# timeliness detector and the turn opens with a web_search detour, which is slow
# and off-message for a code demo.
Type "how is the replay row budget computed in render/inline, and who calls it"
Sleep 600ms
Enter

# The turn is running once the right prompt flips to the steering hint.
Wait+Screen /steer/
Sleep 4s

# --- beat 2: type while the model is streaming -----------------------------
# This is the point of the clip. Nothing is paused; the input box is live.
Type "and does it scale with terminal height?"
Sleep 1200ms
Enter

# The submitted line freezes into the transcript as a `›` block at the position
# where it interrupted, and is injected at the next iteration boundary.
Sleep 4s

# --- beat 3: open the overlay mid-turn -------------------------------------
Ctrl+O
Wait+Screen /Ctrl\+T thinking/
Sleep 1s

# Pinned at the tail, the viewport advances as output arrives.
Sleep 3s

# --- beat 4: scroll up — following stops, browsing is not yanked -----------
PageUp
Sleep 600ms
Up 4
Sleep 3s

# --- beat 5: back to the tail, following resumes ---------------------------
Type "G"
Sleep 2s

# --- beat 6: ESC closes the overlay and does NOT cancel the turn -----------
Escape
Sleep 500ms
# A plain Sleep, NOT a Wait on the input box. Two separate things make an anchor
# wrong here: the turn may have finished while the overlay was open (so /steer/
# never returns), and on one recorded run the main screen came back with no
# input box drawn at all for the full 90s timeout. Neither is worth asserting
# from a tape whose job is to film 30 seconds.
Sleep 2s

# --- tail ------------------------------------------------------------------
# Deliberately NOT waiting for the turn to finish. Wait time is RECORDED time:
# a multi-minute turn gets filmed frame for frame, which blows the size budget
# and has killed vhs's ttyd connection outright
# ("failed to get buffer: ... use of closed network connection").
# The story ends here regardless — the overlay closed and the turn is still
# streaming underneath it, which is the point of the beat.
Sleep 5s
