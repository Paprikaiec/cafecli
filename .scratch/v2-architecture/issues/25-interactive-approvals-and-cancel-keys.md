# 25: Interactive approvals and cancel keys

**Parent:** `.scratch/v2-architecture/PRD.md` (`ui` Approver; contracts — cancel keys) · ADR 0020 D6
**Blocked by:** 08, 10, 24
**Status:** done

**What to build:** In v2 interactive mode, approval cards appear in source order and are answered from the input box,
and ESC / Ctrl+C / Ctrl+D behave exactly as the CONTEXT.md Cancel key table says.

- The interactive `Approver` built on the existing renderer-backed approval handler. An approval arriving while the
  transcript overlay is open closes the overlay, as today.
- Cancel keys and the session-level signal guard call the session's cancel; cancelling releases pending approvals as
  denied and keeps the input box text.
- The interactive project-hook trust confirmer.

- [x] REPL rig (`repl.run()` on a background thread; wait on the run guard before a closing Ctrl+D):
      `InteractiveReplTest`, `CancelKeyTableTest` (including "Ctrl+D mid-run does nothing"),
      `SessionInterruptGuardTest`, and `CancellationScopeTest` pass against the v2 entry.
- [x] Seam 3: a three-call batch shows its approval cards one at a time in source order.
- [x] Seam 1: cancel during a pending approval ends the run cancelled with the approval released as denied.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 03 review).** After ticket 03, `render` still imports `hitl.ApprovalRequest`,
`ApprovalResult` and `ApprovalPolicy` (for `Renderer.promptApproval` and `PlainRenderer`). That is the last v1-shaped
surface in render's API; the interactive `Approver` in `ui` will need render-owned approval types the same way 03 did
for tool events.

**2026-10-20 (from ticket 04b review).** v2 cancels **mid-stream** (PRD intentional change 9; v1 cancels only at the
boundary). The screen must handle a half-streamed answer followed by the cancellation notice, and CONTEXT.md 取消键 /
转向消息 ("落在回合边界而非流式回答中途", "流式回答不可中途打断") need rewording at switch-over (ticket 29).

**2026-10-21 (from ticket 08 review).** `Approver.approve(ApprovalRequest, CancellationToken)` is blocking and must release when the token cancels. `PermissionCapability` serializes session requests, rechecks queued cancellation/grants, and rejects pending/queued calls on cancellation. `setEnabled(false)` clears grants and invalidates replies from the old enabled period so late always-allow replies cannot restore them. Interactive UI must use this policy rather than maintaining another grant set. Close the session after the run to drain owned listeners; do not close it on its own loop callback.

**2026-10-22 (ticket 15 integration checkpoint).** Auxiliary tool invocations from freshness, nested tools and idle
browser commands use the same cancellable permission route. Their preflight/completion callbacks run on the owner,
and they publish no ordinary tool blocks. Browser sensitive-page notices force per-call approval even after a
remembered grant on the normal enabled route; v1's Hook ALLOW and disabled-HITL preview bypass still apply.
Execution-time browser refusal remains absolute on every route. Command cancellation tokens are explicit, including
for auxiliary PostToolUse; do not infer cancellation solely from model-run state or clear the input box.


**2026-10-24 (ticket24 integration checkpoint).** UI event processing is a queued consumer, separate from the
resident input thread and render loop. Reuse this queue for notices/approval presentation; never block it waiting
for approval input or project on a session subscriber. AgentSession.isBusy covers the complete run/command/control/
preparation/delivery admission lifetime for synchronous key decisions. SessionStatus is an immutable owner snapshot;
active dock metrics are cumulative and idle blanks usage/cost while retaining context. Add the actual approval-mode
state through the facade when wiring HITL rather than leaving the adapter's temporary false value. Preserve
close/drain and previous INT-handler restoration. Architecture rules explicitly allow facade/data types; extend
them only for the declared approval port/data needed here, not the whole capability implementation package.


**2026-10-24 (implementation and final-review checkpoint; leader acceptance pending).** InteractiveApprover owns
only pending reply rendezvous; the same PermissionCapability retains grants, generations and serial cancellable
preflight. UI replies/typed cards preserve caller attribution, exact y/n/a/s/m behavior, empty/invalid re-asks and
sensitive restrictions; no answer becomes a model user input. Mode query/change goes through the session facade
and immutable status. Render-owned approval data and neutral ToolApprovalPolicy retain v1 shims without duplicating
policy. Startup trust uses the same lazy captured reader before the resident loop, after provider validation.

A real reader/screen regression found a UI queue waiting on the reader lock while transcript held it, leaving the
following approval notification unable to close that modal. A narrow tryRefreshInputBox ingress now shares the old
redisplay body and never waits on that lock; existing blocking refresh and render frame/lease algorithms remain.
Signal regressions observed cancellation-listener RuntimeException and later Error escaping INT delivery; the
handler now contains both and only queues feedback. Genuine behavior reds are archived in /tmp/cafecli-ticket25/:
approval-red.xml, signal-red.xml, overlay-queue-red.xml and signal-error-red.xml with their logs. Fixture errors
(missing model, manual lease pump wait, child capability closure and closing EOF racing a Detached notification)
are separately labeled and are not claimed as product reds.

Initial independent Spec review found selected v2 cases insufficient to rebind the named v1 contracts. Same-context
correction adds V2InteractiveContractsTest through the real background resident reader, public session/provider
and actual spawned tasks. Final focused **39/0/0/0**:29 real v2 reader cases + trust1 + architecture9, archived in
/tmp/cafecli-ticket25/contracts-complete-focused.log and its reports. Freeze3 has45 source/test SHA-256 entries in
/tmp/cafecli-ticket25/source-freeze-3.json. Both same-context reviewers reread actual source, verified all45 hashes
and report clean Standards / Spec (0 remaining findings). Final broad suites and leader package acceptance are next.

The applicable legacy-to-v2 coverage mapping follows. Command routing / exit outcomes are explicitly Ticket26;
streaming cancellation keeps the already approved PRD exception9, not a new parity waiver.

### Legacy observable contracts to v2 public seams

All v2 reader tests use the actual background `ui.InteractiveRepl`, piped terminal and `CafeCliLineReader`, the public harness session, and `TerminalScreenModel` for visible content. No v1 registry/agent, private reflection or production testing hook is involved. Closing Ctrl+D waits on `AgentSession.awaitIdle` plus its full `isBusy` guard and asserts REPL termination. The intentional mid-run Ctrl+D test asserts the opposite. Task cancellation is initiated by real input keys, not a direct substitute call to session.cancel.

#### cli.InteractiveReplTest

| Legacy test | V2 contract test |
|---|---|
| aPlainMessageIsPostedToTheEventLoopAsAUserSubmit | V2InteractiveReplTest.residentReaderKeepsTheDraftWhileBusyAndEofDrainsFinalPresentationAndRestoresSignalHandler (fake provider records the actual submission) |
| shiftEnterAndOptionEnterInsertANewlineInsteadOfSubmitting | V2InteractiveContractsTest.modifiedEnterInsertsANewlineAndPlainEnterStillSubmits (three legacy escape encodings) |
| plainEnterStillSubmits | same test: a following ordinary Enter yields a distinct provider request |
| theSubmittedInputIsWrittenBackToTheTranscriptBeforeTheTurnRuns | residentReaderKeepsTheDraftWhileBusyAndEofDrainsFinalPresentationAndRestoresSignalHandler plus busyInputIsEchoedWithoutAnotherRunAndReachesTheNextBoundaryOrANaturalFollowUp. Source prompt is queued before run events, appears while work is blocked and stays source text rather than model presentation. Owner execution does not wait for physical frames: ticket24's accepted queued presentation boundary applies. |
| yesNoAndAllTypedIntoTheBoxBecomeApproveRejectAndApproveAll | V2InteractiveContractsTest.inputBoxYesNoAndAllHaveDistinctEffectsAndAllRemembersOnlyTheSameTool (file effects and a subsequent remembered invocation) |
| anEmptySubmissionWhileAnApprovalIsPendingAsksAgainWithoutDeciding | V2InteractiveReplTest.emptyAndInvalidApprovalRepliesStayPendingAndModifiedArgumentsReachTheGuardedTool |
| aBackgroundApprovalIsAnsweredThroughTheSameBoxAndKeepsItsCallerAttribution | V2InteractiveContractsTest.aDetachedChildApprovalUsesTheSameInputBoxAndShowsItsTaskAttribution (actual Detached child, shared permission policy, task_1 card, file effect, no forged MAIN input) |
| aSlashCommandGetsItsTranscriptBlockAndNeverReachesTheEventLoop | Ticket26: full registry/grammar/command transcript port |
| anUnknownCommandIsRoutedAndNeverFallsBackToTheAgent | Ticket26; existing temporary rejection remains, no grammar widening in25 |
| anExitOutcomeEndsTheLoopAndClosesTheRenderer | Ticket26 command/exit dispatch; ticket24 entry resource lifetime already owns renderer close |
| escDuringAForegroundTurnCancelsIt | V2InteractiveReplTest.cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled[escape] and cancellingAHalfStreamedAnswerStopsTheRequestAndKeepsTheTextBeforeTheCancellationNotice |
| aSubmissionDuringARunningTurnSteersItInsteadOfQueueingBehindIt | V2InteractiveContractsTest.busyInputIsEchoedWithoutAnotherRunAndReachesTheNextBoundaryOrANaturalFollowUp[true,false] (same-run iteration consumption and natural-final-boundary follow-up) |
| ctrlYStillTogglesHitlWhileATurnIsRunning | V2InteractiveContractsTest.ctrlYChangesTheActualApprovalModeWhileTheModelIsRunning (real policy + facade snapshot + draft) |

#### cli.CancelKeyTableTest

| Legacy test | V2 contract test |
|---|---|
| escDuringATurnStopsIt | cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled[escape]; streamed-answer cancellation |
| escWhileIdleDoesNothingAndTheDraftSurvives | idleKeysKeepEscAndCtrlDHarmlessWithTextAndCtrlCClearsThenConfirmsExit |
| escWhileIdleAndEmptyDoesNothingAtAll | emptyIdleEscIsHarmlessAndTheExitConfirmationRearmsAfterItsWindow |
| ctrlCDuringATurnStopsItAndKeepsWhatWasTyped | cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled[ctrlC] |
| ctrlCWhileIdleClearsTheBox | idleKeysKeepEscAndCtrlDHarmlessWithTextAndCtrlCClearsThenConfirmsExit |
| ctrlCOnAnEmptyBoxHintsFirstAndTheSecondPressExits | same test (visible hint, first press stays alive, second must terminate) |
| theExitConfirmationReArmsAfterItsWindowPasses | emptyIdleEscIsHarmlessAndTheExitConfirmationRearmsAfterItsWindow (two independently visible hints separated by >2s, then immediate third press exits) |
| escDuringATurnRejectsThePendingApproval | cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled[escape] |
| cancellingDiscardsSteeringThatNeverTookEffect | cancellingDiscardsSteeringWhichNeverReachedAnIterationAndTheSessionStillServesNextInput (following provider request excludes it) |
| ctrlCDuringATurnRejectsThePendingApprovalToo | cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled[ctrlC] |
| ctrlDDuringATurnDoesNothing | ctrlDMidRunDoesNothingAndEscInsideTheOverlayOnlyClosesTheOverlay (Ctrl+D pressed during a pending run and REPL must stay alive) |
| ctrlDWithTextInTheBoxDoesNotExit | idleKeysKeepEscAndCtrlDHarmlessWithTextAndCtrlCClearsThenConfirmsExit |
| ctrlDOnAnEmptyBoxExits | ApprovalRig.exit used by v2 scenarios after full session guard; real exit asserted |
| aDeadInputStreamExitsEvenWhileATurnIsRunning | aDeadInputStreamEndsABusyReplAndCancelsItsRequest |

#### cli.SessionInterruptGuardTest

| Legacy test | V2 contract test |
|---|---|
| anInterruptStopsTheRunningTurnAndItsAttachedTasksAndNothingElse | cancellingFromTheInputBoxStopsAttachedTasksAndPreservesDetachedTasks[escape,ctrlC] (real spawned Attached/Detached tasks; Detached explicitly cancelled through public control command afterwards) |
| theUserIsToldImmediatelyThroughTheTranscriptRatherThanTheTerminal | cancellationStopsWorkEvenWithoutPresentationAndOtherwiseFeedbackAppearsBeforeCleanupEnds[true] (visible notice before provider cleanup releases admission); modalCtrlC also observes notice inside still-open transcript |
| anInterruptWithNothingToCancelDoesNothingAtAll | ctrlDMidRunDoesNothingAndEscInsideTheOverlayOnlyClosesTheOverlay + idle/empty ESC cases; aDetachedChildApprovalUsesTheSameInputBoxAndShowsItsTaskAttribution presses idle ESC while only Detached work remains and confirms its approval stays pending |
| aRendererThatCannotSpeakStillGetsTheTurnStoppedAndThrowsNothingAtTheSignalThread | cancellationStopsWorkEvenWithoutPresentationAndOtherwiseFeedbackAppearsBeforeCleanupEnds[false] (actual renderer closed, token still cancelled and signal returns); throwing runtime/Error cancellation listeners separately covered below |
| jlineOwnsTheInterruptWhileItIsReadingAndTheSessionGetsItBackAfterwards | idle draft Ctrl+C and empty-box hint prove JLine delivers readLine interruption; theSignalGuardCancelsSubmissionPreparationOutsideReadLineWithoutArmingExit[runtime,error] proves handler restoration in the preparation gap; residentReaderKeepsTheDraft... restores the prior INT handler after REPL close |
| aWidgetBlockedOnItsOwnReadIsInterruptedByJlineAndNotBySessionGuard | actual transcript widget via cancellingAPendingApproval...[overlayCtrlC] and ctrlDMidRunDoesNothingAndEscInsideTheOverlayOnlyClosesTheOverlay. Tests observable modal key semantics rather than duplicating JLine's internal handler-count mechanism. |
| ctrlCDuringAForegroundTurnStopsTheTurnAndTheSessionKeepsServing | cancellingDiscardsSteeringWhichNeverReachedAnIterationAndTheSessionStillServesNextInput; pending approval ctrlC and idle key tests |

#### cli.CancellationScopeTest

| Legacy test | V2 contract test |
|---|---|
| cancellingTouchesTheForegroundTurnAndItsAttachedTasksAndNothingElse | cancellingFromTheInputBoxStopsAttachedTasksAndPreservesDetachedTasks[escape,ctrlC] |
| cancellationLandsOnATurnBoundaryAndDoesNotTruncateTheStreamingAnswer | Explicit PRD exception9: cancellingAHalfStreamedAnswerStopsTheRequestAndKeepsTheTextBeforeTheCancellationNotice verifies v2 aborts the active request, retains already visible text, and ends cancelled. No false v1 boundary-parity claim. |
| theControlGroupWithNobodyCancellingRunsOnIntoTheNextIteration | busyInputIsEchoedWithoutAnotherRunAndReachesTheNextBoundaryOrANaturalFollowUp[true], source-order three-call batch completion, and yes/no/all test all observe uncancelled subsequent provider iterations |

#### Additional Ticket25 contracts

- Source-order three-call approvals with no execution before the final decision: approvalsAppearOneAtATimeInSourceOrderAndNoToolRunsBeforeTheLastAnswer.
- Approval while transcript is open, with activity refresh already ahead in the UI queue: anApprovalArrivingWhileBrowsingClosesTheOverlayEvenWithActivityRefreshQueuedAheadOfIt (red before narrow nonblocking reader-refresh ingress).
- Pending cancellation releases denied with the complete guard held until lifecycle finishes: pending-approval parameterized tests and cleanup-delayed feedback test.
- Shared approval parser parity on malformed MCP server names: anEmptyMcpServerCannotTurnTheInputBoxAllReplyIntoAServerGrant.
- Interactive project Hook trust command list, fingerprint reuse and changed-byte re-prompt: InteractiveTrustCompositionTest.interactiveProjectHooksRequireActualCommandTrustAndChangedBytesRequireNewTrust. Actual startup terminal lifetime remains independently packaged-fixture acceptance owned by root.
- Permission grant/cancellation/Hook/browser/auxiliary semantics remain covered by existing v2 session tests, not counted as a substitute for UI key/attribution tests.


**2026-10-24 (late leader parity finding; previous final checks superseded for corrected source).** The initial
freeze3 final suites passed full2077 / quick1973 / smoke456 / named screen87, but leader review found startup trust
Ctrl+C / EOF being caught as ordinary false. Actual v1 propagates those reader exceptions through the whole loader
call, so no configured shell registrations are installed; explicit n instead retains user hooks and declines only
project hooks. Same-context Spec review confirmed P2: no whitelist entry authorizes the changed activation behavior.

Root preserved accepted24's real v1 reference jar at /tmp/cafecli-accepted24-reference.jar. The implementer packaged
unfixed freeze3 at /tmp/cafecli-ticket25/parity-red-v2.jar (SHA-256
792ea42844130d12293d6bec319dbffec60b16d899629986def11d0a9991b79d), then released Maven for root's actual comparison.
/tmp/cafecli25-trust-interrupt-check.py drives both actual interactive entries via native PTY and local Step HTTP:
explicit n executes the configured user hook in both; Ctrl+C and EOF execute it in unfixed v2 but not v1 (six-run
positive/negative controls). Observed red: /tmp/cafecli25-trust-interrupt-red.log; fixture directory
/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket25-trust-parity-l_h09kap.
This is a product regression, distinct from the earlier task/reader fixture errors. Root transferred Maven back to
the same implementer for propagation correction, durable real-prompt/composition tests, renewed reviews and checks.


**2026-10-24 (corrected trust prompt review).** The real prompt body now lives in
ui.ProjectHookTrustPrompt(Renderer,LineReader), used by the same lazy Surface after opening; Ctrl+C / EOF propagate
and afterInput remains in finally. No terminal test injection or hidden process reads were added. Durable actual
reader red observed both interruptions returning false while explicit n passed; archived
/tmp/cafecli-ticket25/trust-interrupt-prompt-red.log / XML. Corrected focused **87/0/0/0** covers29 real REPL cases,
prompt3, composition4, CLI21, Hooks21 and architecture9, with7 fresh XML in trust-interrupt-green-reports.
Both same reviewers are clean against47 hashes in source-freeze-4.json, closing the late parity finding and the
earlier contract coverage finding. Fresh corrected final suites are in progress; earlier freeze3 totals are not
accepted as evidence for this corrected state.


**2026-10-25 (corrected leader acceptance).** Freeze4 final checks are all green: full **2083/0/0/0**
(252 XML), quick **1979/0/0/0** (247), smoke **456/0/0/0** (44), named AGENTS screens **87/0/0/0** (5).
Authoritative logs / XML / totals: /tmp/cafecli-ticket25/corrected-final-{full,quick,smoke,named-screen}*.
The corrected-final-audit.json and source-freeze-final-corrected.json retain47 reviewed source hashes; root
independently checked all47 and all552 report/log hashes, exact suite names against Maven logs, summed counts, and
XML mtimes within each actual logged run. The implementer explicitly released Maven after its no-process audit.

Independent leader quick **1979/0/0/0**, named **1184/0/0/0** (144 suites, including actual v2 reader/prompt
contracts and corresponding legacy cases), smoke **456/0/0/0**, named screen/isolation **89/0/0/0**, and
`mvn clean package` all passed. Logs: /tmp/cafecli-ticket25-leader-{quick,named,smoke,screen,package}.log.
No source edit followed the final review/hash snapshot; root rebuilt a corrected jar before running acceptance.

The same actual paired v1/v2 trust fixture now passes all six cases: explicit n retains the user hook in both;
Ctrl+C and EOF install no configured shell hooks in either. Evidence: /tmp/cafecli25-trust-interrupt-green.log,
/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket25-trust-parity-_moszaf_.
This closes the observed packaged red without expanding the parity whitelist.

/tmp/cafecli25-jar-check.py passed actual resident REPL / UI approver / guarded workspace: three cards in source
order, no file executes before all decisions, exact file bytes, no approval reply becomes a source user, ESC and
native INT release pending approvals as denied, typed main cancellation, retained draft, idle EOF and restored
handler. Evidence /tmp/cafecli25-jar-check.log; fixture
/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket25-zwetsjrq.
/tmp/cafecli25-entry-check.py passed the actual flagged jar in automated PTY: Ctrl+Y changes real policy/dock,
midrun Ctrl+D does not exit, approvals execute inside guards (outside victim bytes unchanged), pending Ctrl+C and
EOF cleanup, startup trust actual command visibility, denial, SHA-256 pin, unchanged reuse and changed-byte re-prompt.
Evidence /tmp/cafecli25-entry-check-final.log; fixture
/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket25-entry-pdiuqc5w.
The native helper initially expected typed PRESERVEDDRAFT as contiguous raw ANSI bytes; actual updates split it as
PRESE / RVEDDR / AFT. The helper wait was removed and its native claim narrowed; draft preservation is independently
asserted through the Java capture seam. This was a fixture error, not a product red or a product change.

All17 prior packaged scripts08,10,10-session,11–16,17-retained,18–24 and the earlier24 actual entry check passed
against the same stable corrected jar (/tmp/cafecli25-prior-regressions.log, cafecli25-regression-<ticket>.log,
cafecli25-regression-24-entry.log). Both review axes remain clean. Root synchronized README / reference / ADR,
carried command contract notes to26 and an entry configuration audit to27. No AGENTS edit, push, unrelated scratch
change or manual terminal acceptance occurred. Automated PTY/screen checks remain offline.
