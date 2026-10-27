# 27: Contract test set

**Parent:** `.scratch/v2-architecture/PRD.md` ("Contracts that must survive"; switch-over criterion 1) · ADR 0020
**Blocked by:** 13, 14, 15, 16, 18, 19, 20, 21, 22, 25, 26
**Status:** done

**What to build:** One command that proves v2 keeps every contract in the PRD — the evidence for switch-over criterion
1.

- A named set (a Maven profile or a JUnit tag) with at least one test per item under "Contracts that must survive",
  bound to v2 entry points. Most already exist from earlier tickets; fill the gaps and make the set explicit.
- A table in this ticket's Comments mapping each contract to its test(s).
- Check that every slash command v1 accepts is registered in v2 (except the removed `serve --http`).
- Don't count the known `NO_COLOR` red (AGENTS.md) or try to fix it here.

- [x] The contract set runs with one command and is green against v2.
- [x] The table is complete: no contract without a test.
- [x] Every v1 slash command is reachable in v2.
- [x] `mvn test -Pquick` is green.

## Comments

**2026-10-21 (input parity correction found during ticket 13).** Include the actual composed submission path from ticket23: MCP resource expansion → local `@path` expansion → UserPromptSubmit, for ordinary and busy/steering input. The ticket10 handoff incorrectly claimed v1 steering bypassed UPS; `cli/InteractiveRepl` prepares before `session.steer`. Current v2's bypass and missing local expansion must be resolved before this contract set is green, not added to the parity whitelist. Test blocked busy input and run-completion races as well as the normal expansion order.

**2026-10-22 (combined context ordering checkpoint from ticket 14).** Verify combined LSP diagnostics, PostToolUse hook context and busy steering in one recorded request against v1's actual boundary order (LSP → Hook → Steering, nearest the next request). The new common inbox preserves arrival order, which is not automatically the same ordering. Test the composed capabilities, reconcile any difference in harness presentation without mutating source history or teaching core product kinds, and do not silently whitelist it. Keep each diagnostic in the first eligible next iteration; source-range compaction must still run before ordinary view ordering.


**2026-10-23 (model-window contract audit checkpoint).** V1's Agent.buildExternalContext gates the MCP resource
index through ContextProfile.mcpResourceIndexEnabled(); current v2 McpCapability.promptContext calls promptIndex()
unconditionally. Existing default provider windows all exceed the threshold, so ordinary provider/switch fixtures
cannot distinguish the paths. When filling this set's contract gaps, cover a configured small-window override and
model switching with actual MCP resources: emitted prompt content must agree with the model-derived index fact.
Reuse the neutral ContextProfile rule rather than duplicating its threshold, keep prompt invalidation observational
for trace, and do not whitelist a new request difference. This audit requires a composed regression before correction.


**2026-10-24 (interactive configuration-surface audit checkpoint).** When binding the CLI/config surface, compare
actual entry history/completion setup as well as slash names: v1 Main configures its history path/options and uses
CafeCliHistory/CafeCliHighlighter, while the current v2 Surface still constructs DefaultHistory and passes null
highlighter/completer (registry completion is26). Verify the observable history storage/filtering/editing behavior
and captured-home path against v1; don't assume JLine defaults prove parity or silently whitelist a difference.
Any needed leaf prefactor must remain behavior-preserving and keep process reads at the system entry.

**2026-10-25 (ticket26 integration checkpoint).** Include the actual registry/reader command regressions, not only
the extracted parser: dynamic completion, all legacy grammar forms, unknown/exit/source echo, async auxiliary
approval, FIFO/draft/modal wake behavior, and captured-directory image completion. `/context`, `/policy`, `/hooks`
and `/audit` use independent observation while MAIN runs; context descriptions are immutable owner captures carried
with SessionStatus. Actual v1 `/cancel` is a no-op with the constant idle notice, including while busy; cancellation
contracts use the existing key/signal/session cancellation route. Keep those two facts separate in the table.
Project changes also drain accepted old-project snapshot writes before replacing services; gate/commit-byte and
failure-retention regressions are in SnapshotSessionTest. Do not replace those with a successful new-root snapshot
as an indirect criterion. Ticket26 evidence and exact acceptance state belong in its issue.

**2026-10-25 (native Ctrl+C diagnostic checkpoint).** Ticket26 packaged acceptance exposed a shared baseline
terminal gap beyond the pipe-driven rigs: Ctrl+C during JLine's ExecPty raw-mode/cleanup window can interrupt or
kill an `stty` / JDK spawn-helper subprocess, producing an uncaught terminal IOError. The same `stty` failure was
reproduced twice against the immutable accepted25 jar, so this is not established as a new26 regression. Balanced
standalone runs passed 5/5 per version; a loaded 20/20 comparison had additional failures in both versions, with
exact classifications/artifacts under `/tmp/cafecli26-native-cancel-stress/` and the script/log beside it. The
original26 failure is `/tmp/cafecli26-25-entry.log`; accepted25 reference is `/tmp/cafecli-accepted25-reference.jar`.
Bind a meaningful real input-boundary regression and resolve this cancellation-contract gap before calling the
contract set complete. A passing pipe rig or one native rerun cannot establish that it is fixed. Preserve unrelated
terminal I/O errors, single-reader ownership and actual cancellation/draft behavior; no new parity waiver is implied.
The same loaded run also caught an alive26 process with a visible pending approval card but a stale `steer` right
prompt (`v26-16.log`); verify the actual captured input readiness/refresh contract, rather than depending on a raw
substring wait or accepting an indefinitely stale prompt. Exact classifications are in `classified-results.json`.

**2026-10-25 (native diagnostic artifact provenance correction).** A ticket27 replay of the old fixed-output stress
script overwrote the original directory's `v25-{0..19}.py/.log`, `v26-{0..19}.py/.log` and `results.json`. The original
aggregate `/tmp/cafecli26-native-cancel-stress.log`, `classified-results.json`, first26 failure and balanced5+5
artifacts remain unchanged; the old classifications must not be applied to the overwritten individual logs. The
new replay is copied to `/tmp/cafecli-ticket27/native-baseline/` with console `/tmp/cafecli-ticket27/native-baseline.log`
(10/20 baseline25 passes, 8/20 baseline26 passes). Its copied old classification file is a preserved prior summary,
not classifications of that replay. Further experiments use ticket27-specific unique output directories. Reviewed
source, Maven reports, accepted26 jar and its feature acceptance evidence were not changed by this diagnostic replay.

**2026-10-25 (implementation started from accepted26).** A fresh ticket27 implementer owns source/tests/build
configuration and Maven; root owns documentation, independent review, acceptance and the local commit. Existing
v2 entry contracts will be selected and mapped explicitly, with new composed regressions for the gaps recorded
below. The first combined request regression reproduced Steering → Hook → LSP; correction must preserve source
arrival history while presenting LSP → Hook → Steering. No contract is accepted from this checkpoint alone.

**2026-10-25 (freeze1 review; correction open).** Root independently verified the 25 frozen source/test/POM hashes,
unchanged before/after hashes, profile log hash, all 68 XML hashes and times, and exact POM includes = Maven Running
classes = XML classes: 711/0/0/0. Standards review is clean. Spec found one P2: sorting only adjacent recognized
context entries leaves steering ahead of LSP/hook context when a task notification intervenes. The same implementer
is adding a real interleaved-completion request regression and a bounded custom-entry cohort projection, with
unrelated entries kept in their slots and core message/compaction boundaries preserved. Source history stays intact.
Both original reviewers will check freeze2 before the single final full suite. No acceptance box is checked yet.

**2026-10-25 (freeze2 final reviews clean).** Both original reviewers are clean after the Spec P2 correction.
The real interleaved child completion and another registered custom kind first reproduced two ordering failures;
the corrected projection sorts only the selected context slots inside cohorts bounded by real log entries and
normalized summaries. Unrelated custom entries keep their exact slots and source arrival history remains intact.
BoundaryContextSessionTest also checks recorded requests across user/assistant, assistant/tool-result and normalized
summary boundaries. Root independently verified all 26 frozen hashes, before/after equality, focused log hash and
six fresh XML hashes/times/class set: 47/0/0/0. The final explicit profile then passed 715/0/0/0 across its 69 named
classes; the single final full suite is running. Final leader/profile/package acceptance remains pending.

**2026-10-25 (leader boundary correction after freeze2 suites).** Before starting independent profiles, root
reproduced a new P2 at the public InputTerminal boundary: a metadata caller that was never interrupted acquired an
interrupt flag when the terminal INT handler interrupted a different input thread and native recovery succeeded.
The global signal epoch authorizes recovery but does not prove interruption of the caller. This can affect UI or
geometry queries; GeometrySampler's parked loop must not receive a fabricated interrupt. Evidence is
`/tmp/cafecli-ticket27/leader-interrupt-probe/CrossThreadInterruptProbe.java` and `leader-cross-thread-red.log`.
The same implementer reproduced it in JUnit before changing production code, and both same reviewers await freeze3.
The preceding contract715/full2278 runs passed on freeze2 but do not accept this pending behavioral correction.
Maven ownership returned to the implementer; root has not run independent27 profiles or packaged27 yet.

**2026-10-25 (freeze3 reviews clean; final validation running).** The original Standards and Spec reviewers both
verified all 26 freeze3 hashes and the three-file delta. Retry authorization is separate from caller interruption;
observed caller flags and interruption consumed by the native wait remain preserved, including failed retry paths.
The real input owner receives INT while an un-interrupted metadata caller retains its own state. Existing reader,
draft, FIFO wake, attributes, handler, error-identity and screen assertions remain intact; artificial consumed-flag
fixtures now carry the actual native wait cause. Root verified the focused log hash, all eight fresh XML hashes/
times/class set and before/after source hashes: 78/0/0/0. The unchanged original leader probe passes. Freeze2 suite
artifacts are preserved as superseded; final freeze3 profile/full runs use separate versioned paths. Independent
leader profiles, clean package and packaged acceptance have not started.

**2026-10-25 (independent quick failure; diagnosis open).** Freeze3 final profile717/full2280 passed, and root's
independent profile also passed717/0/0/0. The independent quick run then failed one existing reader case:
V2InteractiveReplTest.cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled, overlayCtrlC parameter,
waiting at line147 for the request notice on the visible modal screen. Its cancellation outcome, rejected approval,
unchanged victim file and still-open modal checks had already passed. Evidence is
`/tmp/cafecli-ticket27-leader-quick.log` and `/tmp/cafecli-ticket27/leader-quick-red-reports/`. The same implementer
owns diagnosis and Maven again; root has not started named/smoke/screen/package or packaged27. The feedback loop must
capture screen/document/frame and notice ordering, distinguishing a missing notice from one already rendered then
scrolled away. No timeout increase, removed behavioral assertion or passing-rerun fix claim is accepted.

**2026-10-25 (contract mapping; acceptance pending).** The single command is
`env -u NO_COLOR TERM=xterm mvn test -Pv2-contracts` on Java21; native PTY cases also require Python3 on macOS/Linux.
The explicit profile is bound to the following v2 entry seams and unchanged required leaves. The known NO_COLOR red
is neither counted nor changed. Independent reviews and final validation are recorded separately.

| PRD contract | v2 seam and concrete tests / observations |
| --- | --- |
| Guards absolute; Hook strictest-wins, semantics, switches and project trust asymmetry | Session: HooksSessionTest.hookAllowSkipsApprovalButCannotLiftThePathGuard, hookAllowCannotLiftTheCommandGuardOrProduceCommandSideEffects, mixedHookTenantsApplyTheStrictestDecisionThroughTheActualPermissionRoute, hookBlockIsModelFeedbackInTheNextRequest, hookAskForcesApprovalBeyondTheOrdinaryPolicy, disabledHooksLeaveRecordedRequestsIdentical. PermissionSessionTest.guardsStillDenyAnApprovedEditToAnOutsidePathAndAuditTheEffectiveArguments. Config loader: HooksConfigTest current/legacy truthy and all-default-off cases. Production composition: HooksHeadlessTest.projectTrustIsConfirmedOnceAndHeadlessRequiresTheExactConfigFingerprint and InteractiveTrustCompositionTest (exact SHA-256 pin, denial, changed content, interrupted startup). McpCapabilityTest resources + InteractiveInputCompositionTest connect project mcp.json without a trust confirmation, preserving the asymmetry. |
| Snapshot bare repository, per-call work tree, hijack detection, real-path and file-mode restore | v2 session: SnapshotSessionTest.aMainRunIsAnchoredBeforeItsFirstRequestAndRevertRestoresItsOriginalBytes, closingAnInterruptedSessionWaitsForTheRealPostWriterAndLeavesItsBytesDurable; project replacement cases assert old POST_TURN blob bytes, drain/factory failure retention, caller interruption and custom service scope. Production composition: SnapshotCompositionTest.actualCompositionAnchorsAndRestoresAndDrainsItsPostSnapshotOnClose and cliWarnsAboutAHijackedProjectOnStderrAndNeverTouchesItsGitfileOrSnapshotTarget. Unchanged required leaves: SnapshotDoesNotTouchProjectGitTest reads project gitfile bytes/bare config; SnapshotRestoreStaysInsideProjectRootTest reads root-external victim bytes and tests symlink mode restoration; HijackedProjectGitTest; SideGitManagerTest. Existing documented single-layer mutation coverage gap is not claimed repaired. |
| ESC / Ctrl+C / Ctrl+D and signal guard | Actual v2 resident reader: V2InteractiveContractsTest and V2InteractiveReplTest (key table, empty/draft idle behavior, busy EOF, approval cancel, modal semantics, attached/detached task scope, callback RuntimeException/Error containment). V2CommandReplTest.realCtrlCRacingACommandReleaseStillCancelsOrClearsTheDraftInsteadOfBecomingAnOwnedWake. NativeInputBoundaryTest drives 12 real controlling PTYs through production Main/SystemEntry and native ExecPty setup/cleanup. V2TerminalBoundaryTest tests fresh real INT/native geometry cancellation with preserved draft, restored terminal attributes/handler, plus owned FIFO wake during interrupted metadata. InputTerminalTest checks unrelated error identity, stale signal rejection, one-retry failure propagation, captured native command, raw mode original-attributes capture and inherited interruption preservation. `/cancel` intentionally stays v1's no-op; its busy observation is tested separately. |
| Live-region single writer, one key consumer, typed blocks, destructive resize | V2EventScreenTest feeds typed core events through EventRenderAdapter into the unchanged renderer and TerminalScreenModel (no surviving live tool/answer/thinking copies; input retained). Actual-reader suites V2InteractiveReplTest/V2InteractiveContractsTest/V2CommandReplTest/V2TerminalBoundaryTest feed session events and real key input. ScreenTruthRegressionTest, InputBoxLiveRegionTest, ToolCallBlockTest, CaptureDisplayInterceptionGuardTest, HardTerminationCouplingTest retain unchanged leaf screen/ownership/reset checks. V2TerminalBoundaryTest.anApprovalRefreshContendingWithRedisplayEventuallyPublishesThePendingPromptWithoutAnotherKey proves pending prompt readiness through captured frames AND the screen under reader-lock contention. RenderTestIsolationGuardTest pins test isolation; V2ArchitectureTest retains layer/reader boundaries. |
| Typed tool status; CANCELLED/BLOCKED usable for StepSearch; non-zero command exit OK | ToolSessionTest.everyLeafStatusAndTextImageAndStructuredBlocksSurviveWithoutReadingTextPrefixes. WorkspaceSessionTest.aCommandExitingOneStillHasAnOkOutcomeAndItsOriginalResultText uses a real process. WebSessionTest (actual StepSearch auxiliary adapter tests) verifies cancelled/blocked nested results return without fallback and no real network search. AuxiliarySessionTest and BrowserSessionCapabilityTest retain guarded nested execution/typed provenance. |
| Tool Batch parallel default4; source-order results; sibling invisibility | ToolSessionTest.aBatchExecutesAtMostFourCallsAtOnceAndDoesNotLoseQueuedCalls uses gates and checks the fifth call cannot start. twoGuardedReadsOverlapFinishOutOfOrderAndAreLoggedInSourceOrder gates real read leaves, proves no sibling completion opens an in-batch model request, first request contains no sibling result, then both results reach the next request in source order. PermissionSessionTest.threeApprovalsFollowSourceOrderAndTheEntireBatchWaitsForTheThirdAnswer binds source-ordered preflight. McpCapabilityTest aggregate budgeting checks immediate completions, finalized source-order model results and preserved statuses. |
| load_skill body before next real user input | SkillsSessionTest.loadedBodyWaitsForTheNextRealUserAndKeepsExactV1Bytes, bodyTargetsTheRealUserAcrossVisibleRunStartEntriesAndSummaryNormalization, pendingBodiesBelongToOneAgentAndClearDropsThem; SkillsLspCompositionTest attaches the actual leaf in production composition. Source SkillBody and UserEntry remain immutable; recorded request has exact combined bytes. |
| DeepSeek V4 / Kimi reasoning_content round-trip | Provider seam over local HTTP: DeepSeekProviderTest and RemainingProvidersTest send a thinking/tool-call assistant then assert the next wire request preserves reasoning_content for DeepSeek/Kimi and omits it for other providers. DeepSeekSessionTest.aConfiguredModelReadsARealFileAndReceivesItsThinkingAndToolResultOnTheNextIteration binds real file execution, configured model metadata and next adapter request. |
| Memory only explicit /save or user request | Production MemorySessionTest.saveRecallsInANewSessionAndOrdinaryRunsWriteNothing, explicitSaveMemoryUsesTheLeafToolAndPersistsItsFact, explicitBrowserHintUsesAssistantAndToolHistoryAndIsGlobal, longTermRecallFollowsEachUserWhileTheSystemPromptStaysByteIdentical; file persistence is observed through the long-term store/session facade. CompactionSessionTest does not enable extraction. |
| Unknown slash rejected and /plan retirement text | V2CommandReplTest.unknownAndMalformedCommandsGetTheLegacyRejectionAndNeverBecomeModelHistory and exitAliasesNormalizeCaseAndWhitespaceDrainTheirEchoAndCloseTheSession; CommandGrammarSessionTest binds complete accepted/rejected parser forms through the session dispatcher. CommandCompositionTest asserts the production registry has exactly 28 registered command names, including the requested /help builtin. Session's /plan uses neutral CommandNotices.planTombstone shared with v1; UI test observes the same notice. |
| CLI/config flags, locations, precedence/legacy fallback, disabled edit_file, Hook enable flags | CliTest and SystemEntryTest drive actual v2 startup/run/argument handling. ConfigLoaderTest covers file/property/environment precedence, config home fallback, captured project/user roots, aliases and edit-file closure (WorkspaceSessionTest/ToolSessionTest assert advertised/executable definitions). HooksConfigTest/SnapshotConfigTest/WebRagConfigTest retain config slices. CommandCompositionTest/CommandProjectScopeTest/ModelSwitchSessionTest/RemainingProvidersCompositionTest bind explicit provider generation, model metadata/history, project replacement, startup search/completion root and retained child scope. InteractiveReaderTest uses the production reader factory: captured-home history path, modern/legacy limits, safe history storage/filtering, highlighter styles, actual key-driven pair insertion, incremental save from real readLine returns, and reopen with only safe history retained. |
| Trace compatible JSONL and totals, one assistant per message | TraceCompatibilityTest.finalizedSourceOrderedToolResultsHaveExactlyTheLegacyWritersCompatibleFields; TraceSessionTest; production TraceCompositionTest.cliWritesCompatibleMessagesAndMainUsageBeforeReturningItsAnswer. These assert normalized records/fields, source-order finalized tool content, reasoning/images/prefix presentation, MAIN-only completed usage, writer draining and errors; pending prompt invalidation stays observational. |
| Headless no event loop, pump or push | AgentSessionTest blocking/default execution and caller thread events; BackgroundTasksSessionTest.interactiveSlowForegroundDetachesButHeadlessForegroundAndBackgroundStaySynchronous; SubAgentsCompositionTest and CliTest actual run --file. InteractiveSessionTest is explicit opt-in and does not redefine headless; V2ArchitectureTest retains forbidden v1 spine dependencies. |
| Task foreground/background, interactive-only auto background, Attached/Detached cancel, run→idle→reaped/continue | BackgroundTasksSessionTest.backgroundReturnsBeforeChildFinishesAndCompletionEntersTheNextBoundary, idleTasksContinueWithHistoryAndBoundedOutputUntilReaped, parentCancellationStopsAttachedAndPreservesDetachedUntilExplicitCancel, interactiveSlowForegroundDetachesButHeadlessForegroundAndBackgroundStaySynchronous, runEndEventDoesNotPublishIdleUntilChildFinallyFinishes, sessionShutdownStopsAndClosesEveryDetachedChildDespiteFailingListeners. SubAgentsSessionTest/SubAgentsCompositionTest bind actual TaskTools and child profiles. InteractiveTasksSessionTest and InteractiveLifecycleSessionTest bind final-boundary/idle push, blocked preparation, handoff, close and autonomous run source provenance. |

Additional mandatory composed gaps:

- InteractiveInputCompositionTest.resourceLocalAndSubmitGateComposeForOrdinaryBusyAndBlockedInputs and preparationRacingTheFinalRunBoundaryIsNeitherLostNorRepeated use actual MCP resource HTTP, resource text containing local @path, real file expansion and a real shell UPS hook. Each input is prepared exactly once; BLOCK never enters history.
- LspSessionTest.postWriteDiagnosticsHookContextAndBusySteeringReachTheFirstEligibleRequestInLegacyOrder uses an actual guarded Java write, real parser diagnostics, actual PostToolUse Hook and busy session.submit; next recorded request has LSP→Hook→Steering, while source log keeps arrival order. Its summary-range variant verifies range normalization precedes ordinary ordering and preserves the correct retained diagnostic.
- McpCapabilityTest.configuredSmallWindowAndModelSwitchGateTheActualResourceIndexWithoutObservationConsumingInvalidation uses actual MCP resources and configured 16k/64k model overrides; /model output and actual prompt index agree in both directions, history remains and /context/status observations do not consume prompt rebuilds. Production reuses ContextProfile.forExactWindow, without a copied threshold.

- BoundaryContextSessionTest.aRealBackgroundCompletionBetweenSteeringAndPostWriteContextKeepsItsSlotAndTheNextRequestUsesLegacyOrder adds the interleaved real task completion. Its generic registered-kind and user/assistant/tool-result/normalized-summary boundary cases preserve unrelated slots and source history.
- InputTerminalTest.metadataCallerDoesNotAcquireAnInterruptDeliveredToADifferentInputOwner separates recovery authorization from caller interruption; the consumed-wait and failing-retry cases preserve only the caller's actual flag. The original leader probe will also run independently during packaged acceptance.

Every v1 slash command is registered in CommandCompositionTest.interactiveCompositionRegistersEveryLegacyCommandFamilyAndSessionBuiltins and dispatched with the shared complete v1 grammar by CommandGrammarSessionTest. Capability/reader reachability is bound as follows:

| Command names | Production registration / behavior coverage |
| --- | --- |
| /exit, /quit | SessionCapability; V2CommandReplTest actual normalized exit/drain/close |
| /clear | SessionCapability; MemorySessionTest clear + permission reset, SkillsSessionTest clear, SnapshotSessionTest; typed CLEAR_VIEW through command UI |
| /cancel | SessionCapability; V2CommandReplTest busy observation verifies constant idle notice and no cancellation |
| /transcript | SessionCapability; V2CommandReplTest transcript modal, deferred owner wake and queued command |
| /history | SessionCapability; HISTORY_CLEAR typed effect + InteractiveReaderTest persisted history/filter; shared grammar accepts only /history clear |
| /help | SessionCapability; registry/help/completion tests and actual capability command reader test |
| /plan | SessionCapability; actual UI retirement notice, shared neutral v1 literal |
| /context, /ctx | SessionCapability; SessionStatusTest, busy V2CommandReplTest, model/window MCP test |
| /model, /config | ModelCapability; ModelSwitchSessionTest, RemainingProvidersCompositionTest, CommandCompositionTest explicit transport generations/retained child history |
| /hitl | PermissionCapability; CommandRegistrySessionTest and V2CommandReplTest busy mode changes share actual permission state |
| /policy, /audit | WorkspaceCapability / AuditCapability; CommandCompositionTest and busy V2CommandReplTest, PermissionSessionTest owned audit records |
| /hooks | HooksCapability; CommandCompositionTest, busy V2CommandReplTest, configured Hook/trust suites |
| /mcp | McpCapability; McpCapabilityTest actual connected server resources/prompts/logs/enable/disable/restart |
| /memory, /mem, /save | MemoryCapability; MemorySessionTest scope aliases/list/search/delete/clear and explicit save |
| /skill | SkillsCapability; SkillsSessionTest actual show/list/enable/disable/reload + malformed arity |
| /browser | BrowserCapability; BrowserSessionCapabilityTest command status/mode/tabs via same auxiliary permission route |
| /task | SubAgentsCapability; BackgroundTasksSessionTest busy inspection/cancel and retention, V2CommandReplTest actual busy reader |
| /snapshot, /restore | SnapshotCapability; SnapshotSessionTest actual list/status/clean/restore, no model history |
| /index, /search, /graph | RagCapability; CommandCompositionTest command errors, CommandProjectScopeTest explicit project changes, RagSessionTest actual semantic tools and scoped storage |

**2026-10-26 (accepted after independent verification).** Standards: clean. Spec: clean. Both original reviewers
verified all 26 final frozen hashes. The final delta adds failure-only screen diagnostics; reversing that wrapper
reproduces the exact preceding test hash. The predicate, five-second timeout, assertions and production code remain
unchanged. This establishes **no fix** for the earlier single modal-notice timeout: it remains unresolved. A bounded
4,700 subsequent reader scenarios, forced notice orders and a full quick run did not reproduce it. Failure capture
now retains terminal bytes, screen/document, input frame, reader state/stack and write ordering. Original red evidence
is preserved in `/tmp/cafecli-ticket27-leader-quick.log` and `/tmp/cafecli-ticket27/leader-quick-red-reports/`.

Final implementer contract profile: 717 tests / 69 suites; full suite: 2,280 / 264. Root independently passed contract
profile717, quick recheck2,176, named1,392, phase16-smoke457, screen/isolation89, and clean package. All passing runs
had zero failures/errors/skips. The original independent quick result was 2,176 tests / one failure; its green
recheck does not explain it. Logs: `/tmp/cafecli-ticket27-leader-{contracts,quick,quick-recheck,named,smoke,screen,package}.log`.

Fresh jar SHA-256: `c8d981abc3900736a3d434351dfec836dadcd77fcc72ff3a18e4e4ce3e9f4038`.
Root's independent packaged checks passed actual guarded LSP→Hook→Steering request order with an intervening custom
entry and immutable source history; configured 64k→16k→64k MCP resource-index switching, explicit resource mention
and observational status; the original cross-thread interrupt probe; production history autosave/filter/reopen/
keyboard recall; and 20/20 original native cancellation cases under four workers. Evidence:
`/tmp/cafecli27-jar-check.log`. Separate actual entry24/25 and paired v1/v2 startup-trust checks also passed:
`/tmp/cafecli27-{24-entry,25-entry,trust-interrupt}.log`. These are automated PTYs, not human terminal acceptance.

The optional historical packaged sweep passed 13 available fixtures (14–17,19,21–26, including26 entry and snapshot
replacement). Eight checks were unavailable: old scripts08/10/10-session/11/12 no longer exist under `/tmp`; shared13
MCP server script is also absent, preventing13/18 and timing out20's MCP stage after its earlier trace cases passed.
This sweep is **not all green**, nor complete revalidation of those old fixtures. Evidence:
`/tmp/cafecli27-prior-regressions.log` and individual logs. No missing fixture/evidence was reconstructed for a pass.
Durable contract/full-suite coverage remains the acceptance basis;28 adds the requested repository parity harness.

All 26 frozen source/test/build/resource hashes and the jar hash were rechecked after fixtures. The exact local
commit includes those paths and five root-owned documentation paths; unrelated scratch is excluded. Continue28;
stop before29, manual terminal acceptance, AGENTS changes or default-path switch-over.
