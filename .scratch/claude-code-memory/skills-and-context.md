# Skills: prompt placement and runtime effects

Research date: 2026-10-18. Scope: local `claude-code/` source snapshot at commit `4b9d30f7953273e567a18eb819f4eddd45fcc877`. This is source analysis, not a live product trace. Feature-gated discovery and fork branches are described conditionally.

## Answer relative to the earlier C notation

`C` meant the `getUserContext()` result inserted at the start of model-facing messages by `prependUserContext()`: discovered CLAUDE.md/instruction content, ordinary memory index content when enabled, and the cached date. Skills are not collected into that object. They use listing/discovery attachments and invocation-time prompt messages in the conversation. Both mechanisms eventually produce API user content, but their insertion point, content, and lifetime differ. Sources: [user context builder](../../claude-code/src/context.ts:155), [prefix insertion](../../claude-code/src/utils/api.ts:449), [skill listing producer](../../claude-code/src/utils/attachments.ts:2661), [inline invocation](../../claude-code/src/utils/processUserInput/processSlashCommand.tsx:869).

The system prompt includes guidance on using skills, and the API has a Skill tool schema; neither is equivalent to injecting every full SKILL.md into C. Source: [session skill guidance](../../claude-code/src/constants/prompts.ts:352).

## Progressive loading

| Stage | What the model gets | Where it enters |
| --- | --- | --- |
| Listing | Skill names and descriptions, formatted within a budget | `skill_listing` attachment converted to meta user text in a system-reminder |
| Gated discovery | Relevant discovered-skill information; full behavior depends on the experimental discovery path | User-input attachments or asynchronous query-loop prefetch |
| Inline invocation | Expanded skill Markdown body, including substituted arguments and supported dynamic content | New meta user message(s) returned by slash-command processing / SkillTool |
| Skill `context: fork` | Expanded skill body given as the delegated prompt to a separate runAgent loop | Child conversation; parent receives extracted result text |
| Agent frontmatter `skills:` | Explicitly requested skill contents preloaded for that agent | Child initial meta user messages |
| After compaction | Budgeted content from skills invoked by this agent | `invoked_skills` attachment converted to reminder user content |

Sources: [listing format](../../claude-code/src/tools/SkillTool/prompt.ts:65), [listing wire conversion](../../claude-code/src/utils/messages.ts:3728), [discovery attachments](../../claude-code/src/utils/attachments.ts:789), [inline body message](../../claude-code/src/utils/processUserInput/processSlashCommand.tsx:902), [fork dispatch](../../claude-code/src/tools/SkillTool/SkillTool.ts:621), [agent preloads](../../claude-code/src/tools/AgentTool/runAgent.ts:577), [compaction restoration](../../claude-code/src/services/compact/compact.ts:1488).

The listing is sent only when the loop has the Skill tool. A module-level map tracks sent names per agent ID, so the listing is initially announced and subsequently sent as deltas. Resume suppression avoids re-announcing an already persisted listing. Experimental skill search changes the listing to bundled/MCP skills (with a bundled-only fallback when large); other skills go through discovery. Sources: [eligibility and filtering](../../claude-code/src/utils/attachments.ts:2668), [per-agent sent set](../../claude-code/src/utils/attachments.ts:2699), [resume behavior](../../claude-code/src/utils/attachments.ts:2617).

## Inline invocation example

Illustrative request history, with adjacent user-message merging omitted:

```text
user:      C
user:      "Review this change"
user:      <system-reminder>Available skills: review: ...</system-reminder>
assistant: tool_use Skill(skill="review", args="...")
user:      tool_result for Skill
user(meta): expanded review SKILL.md instructions
assistant: follows those instructions and uses other tools
```

The exact API block layout can merge consecutive user records and reorder attachment text around tool results. The important placement is that the skill body is added at invocation time, rather than replacing the cached user-context prefix. SkillTool returns `newMessages` tagged with the source tool-use ID. Sources: [inline dispatch](../../claude-code/src/tools/SkillTool/SkillTool.ts:634), [returned messages](../../claude-code/src/tools/SkillTool/SkillTool.ts:728), [normalization](../../claude-code/src/utils/messages.ts:1989).

Skill body generation is executable host logic: `getPromptForCommand()` adds the skill base directory, substitutes arguments and supported session/directory variables, and can execute inline shell substitutions for non-MCP skill sources. This creates prompt content; it does not imply the model has received all scripts or other files in the skill directory. Source: [skill command expansion](../../claude-code/src/skills/loadSkillsDir.ts:344).

## Forked skill is a separate run

Despite the word `fork`, this SkillTool path calls `runAgent()` with the expanded skill prompt and a selected base agent, without passing `forkContextMessages`. Therefore this path does not copy the full parent transcript. It obtains the ordinary child context and returns `extractResultText()` to the caller. This differs from `runForkedAgent()` used for memory extraction, which explicitly carries the parent transcript and cache-critical parameters. Sources: [skill runAgent call](../../claude-code/src/tools/SkillTool/SkillTool.ts:205), [result extraction](../../claude-code/src/tools/SkillTool/SkillTool.ts:264), [runAgent initial history](../../claude-code/src/tools/AgentTool/runAgent.ts:368), [internal full-context fork](../../claude-code/src/utils/forkedAgent.ts:506).

Agent frontmatter skill preloading is another route: `runAgent()` resolves names in `agentDefinition.skills`, loads each matching skill body, and appends it to that child's `initialMessages` as a meta user message. It is explicit preloading, independent of the ordinary C prefix. Source: [preloading](../../claude-code/src/tools/AgentTool/runAgent.ts:577).

## Effects outside prompt text

Inline SkillTool execution can modify the running context's permission view, model selection, and effort through a `contextModifier`. Skill frontmatter hooks can be registered in runtime session-hook state. These are host-side execution effects; putting prose instructions into messages is only one part of skill execution. Sources: [contextModifier](../../claude-code/src/tools/SkillTool/SkillTool.ts:775), [hook registration](../../claude-code/src/utils/processUserInput/processSlashCommand.tsx:871), [registerSkillHooks](../../claude-code/src/utils/hooks/registerSkillHooks.ts:20).

Invocation records are separately stored in `bootstrap/state.ts` as a map keyed by agent ID plus skill name, including expanded content and invocation time. Compaction uses only the current agent's entries, truncating per-skill content and applying an overall budget; restoration is therefore not guaranteed to retain every full skill body. Listing bookkeeping and invoked-skill restoration are distinct state mechanisms. Sources: [invocation map](../../claude-code/src/bootstrap/state.ts:1501), [per-agent filter](../../claude-code/src/bootstrap/state.ts:1530), [restoration limits](../../claude-code/src/services/compact/compact.ts:1503), [restored message](../../claude-code/src/utils/messages.ts:3644).

## Companion reports

- [Main conversation assembly](context-assembly.md)
- [Memory extraction and recall](research.md)
- [Agent context variants](agent-contexts.md)
- [Runtime agent state](agent-runtime-state.md)
