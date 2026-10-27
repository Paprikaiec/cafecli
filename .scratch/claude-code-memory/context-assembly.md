# Claude Code main-conversation context assembly

Research date: 2026-10-17. Scope: local `claude-code/` snapshot, commit `4b9d30f7953273e567a18eb819f4eddd45fcc877`, dated 2026-03-31. The [archive README](../../claude-code/README.md:1) describes an unofficial source mirror. Findings follow checked-out implementation paths; feature gates and runtime rollout are not established. The snapshot omits some imported modules, including the central message type declaration, so the message categories below describe verified conversion behavior rather than claiming an exhaustive exported type definition. No application code was changed or execution test performed.

## The model-facing context

The main request is assembled from three different layers:

1. **Application history:** persisted or displayed message records, including user/assistant content, tool interactions, attachment records, progress, and system records.
2. **Current query view:** the retained conversation after the latest compaction boundary, with configured tool-result reduction and other context management applied. System instructions and user context are supplied separately to the query loop.
3. **API request:** top-level `system`, `tools`, model/thinking/output settings, and normalized `messages` containing API user/assistant roles. Local message metadata and display records are not equivalent to API roles.

Sources: [query projection](../../claude-code/src/query.ts:365), [normalizer](../../claude-code/src/utils/messages.ts:1989), [API payload](../../claude-code/src/services/api/claude.ts:1699).

There is a fresh request construction on each model round trip. During ordinary operation the retained earlier conversation is included again, followed by new assistant/tool/user content. Prompt caching adds cache metadata to the supplied prefix; it does not make this client send only the newest messages. Source: [next loop state](../../claude-code/src/query.ts:1714), [all-message conversion and cache marker](../../claude-code/src/services/api/claude.ts:3089).

## Initial assembly

Text submission produces a user message followed by generated attachment records. Attachments can describe mentioned files/resources/agents, relevant nested instructions, dates, skills, and other current state. These are conditional additions, rather than a fixed full inventory injected on every turn. The REPL appends the new records to its history, resolves system instructions and user/system context, and passes them to `query()`. Sources: [processTextPrompt.ts](../../claude-code/src/utils/processUserInput/processTextPrompt.ts:89), [attachment generation](../../claude-code/src/utils/attachments.ts:773), [history append](../../claude-code/src/screens/REPL.tsx:2891), [context resolution and query call](../../claude-code/src/screens/REPL.tsx:2767).

| Component | Contents and placement | Source |
| --- | --- | --- |
| System instructions | Built-in behavior, actions, tools, tone/output guidance, memory policy, environment/model details, language/output style, session guidance, and optional MCP instructions; explicit custom/append system settings can alter the effective instructions | [prompts.ts:491](../../claude-code/src/constants/prompts.ts:491), [effective prompt call](../../claude-code/src/screens/REPL.tsx:2781) |
| System context | Git status/branch/recent commit snapshot and conditional additions; appended to system text | [context.ts:116](../../claude-code/src/context.ts:116), [appendSystemContext](../../claude-code/src/utils/api.ts:437) |
| User context | Discovered instruction/memory content rendered as `claudeMd`, current date, and conditional coordinator information; prepended as a synthetic meta **user** message wrapped in `<system-reminder>` | [context.ts:155](../../claude-code/src/context.ts:155), [prependUserContext](../../claude-code/src/utils/api.ts:449) |
| Tool definitions | Separate top-level API tool schemas, with filtering/loading handled by API preparation | [request tools](../../claude-code/src/services/api/claude.ts:1711) |
| Conversation | Actual user prompts, assistant blocks including tool calls, tool results, and converted attachments | [normalizer](../../claude-code/src/utils/messages.ts:1989) |

The distinction between actual API placement and descriptive prompt wording matters: `<system-reminder>` is text inside user content, not an API system role. Ordinary CLAUDE.md/auto-memory content lives in this user-context prefix; memory behavior policy lives in the system instructions. The prefix is created for each request and prepended to the projected history; it is not repeatedly appended as new persistent conversation turns. Sources: [prependUserContext implementation](../../claude-code/src/utils/api.ts:449), [request preparation](../../claude-code/src/query.ts:660), [system memory policy](../../claude-code/src/constants/prompts.ts:495).

Many dynamic system sections are computed once until `/clear` or `/compact`. User/system context are memoized. Git status explicitly describes itself as a start-of-conversation snapshot that does not continuously update; model-request construction does not automatically mean rescan every file and rerun git status. Volatile MCP instructions are an explicit exception, or can arrive through persisted delta attachments. Sources: [system section cache](../../claude-code/src/constants/systemPromptSections.ts:16), [git snapshot](../../claude-code/src/context.ts:97), [MCP exception](../../claude-code/src/constants/prompts.ts:508).

Context also expands as files are accessed. Read registers the target path; after tool execution, attachment collection can load nested `CLAUDE.md` files and matching path-conditional rules for that file. Thus a request involving `src/auth/login.ts` can acquire instructions from `src/auth/CLAUDE.md` on a later model call without loading every nested instruction file at startup. UserPromptSubmit hooks can additionally contribute context attachments before the first call. Sources: [Read trigger](../../claude-code/src/tools/FileReadTool/FileReadTool.ts:1038), [nested instruction loading](../../claude-code/src/utils/attachments.ts:1777), [post-tool collection](../../claude-code/src/query.ts:1580), [prompt-hook context](../../claude-code/src/utils/processUserInput/processUserInput.ts:228).

## Normalization into API messages

The API preparation calls `normalizeMessagesForAPI()` and then `ensureToolResultPairing()`. It converts the richer local records to user/assistant messages, removes or repairs invalid blocks, and finally converts those records to API `MessageParam` values. Sources: [normalization call](../../claude-code/src/services/api/claude.ts:1265), [pairing repair](../../claude-code/src/services/api/claude.ts:1298), [wire role conversion](../../claude-code/src/services/api/claude.ts:588).

| Local record/content | API behavior |
| --- | --- |
| User text, images, tool-result messages | API user content; consecutive users are merged |
| Assistant response fragments | Tool inputs normalized; fragments belonging to the same API `message.id` merged back into one assistant message |
| Attachment | Type-specific converter yields user message(s), often meta text wrapped in `<system-reminder>`; merged with an adjacent user message where possible |
| Local-command system record | Converted to user content so later model calls can see the output |
| Ordinary system/progress record | Filtered from API conversation messages |
| Virtual user/assistant record | Filtered because it is display-only |

Sources: [initial filters](../../claude-code/src/utils/messages.ts:1996), [record filter and local command](../../claude-code/src/utils/messages.ts:2056), [user merge](../../claude-code/src/utils/messages.ts:2187), [same-ID assistant merge](../../claude-code/src/utils/messages.ts:2246), [attachment conversion](../../claude-code/src/utils/messages.ts:2269).

Consecutive user content is merged without inventing another human turn. The merger hoists `tool_result` blocks before ordinary text so tool results immediately follow their matching assistant tool calls. Assistant merging is based on matching API message IDs; “merge every adjacent assistant” would be an inaccurate account of this implementation. Additional gated normalization may fold reminder siblings into a tool result, so the exact content-block layout can vary while retaining the logical information. Sources: [mergeUserMessages](../../claude-code/src/utils/messages.ts:2411), [tool-result hoisting](../../claude-code/src/utils/messages.ts:2470), [optional reminder folding](../../claude-code/src/utils/messages.ts:2327).

Pairing repair strips duplicate tool-call IDs and orphaned results, and inserts synthetic error `tool_result` blocks for calls lacking a result. Thus a resumed or interrupted transcript can differ from the raw stored records before transmission. Sources: [pairing implementation](../../claude-code/src/utils/messages.ts:5133), [missing-result repair](../../claude-code/src/utils/messages.ts:5301).

## A typical two-user-turn example

The following is illustrative pseudocode derived from the traced assembly path, not a captured production request. It assumes ordinary uncropped history, one local Read tool, no exotic feature branches, and simplified text blocks. `S` means system instructions plus system context; `T` means current tool schemas; `C` means the synthetic user-context reminder.

The user asks: **“Read `src/auth.ts` and explain how login works.”** The model calls Read, receives the file, and answers. The user then asks: **“Now add a test for an expired token.”**

### Request R1: first model call

```text
model, thinking, max_tokens, ...
system = S
tools  = T
messages = [
  user: [C, "Read src/auth.ts and explain how login works.", initial attachments]
]
```

The synthetic context prefix, actual first prompt, and compatible attachment text can become a single user entry during normalization. The model returns, for example:

```text
assistant: [text("I'll inspect the login flow."),
            tool_use(id="read_1", name="Read", input={file_path:"src/auth.ts"})]
```

### Request R2: continuation after tool execution

```text
system = S
tools  = T
messages = [
  user:      [C, first user prompt, initial attachments],
  assistant: [text(...), tool_use(id="read_1", ...)],
  user:      [tool_result(tool_use_id="read_1", content="file content..."),
              any newly generated reminders/attachments]
]
```

Read executes in the host, not inside the model. Its output goes back as API user content containing `tool_result`; there is no separate `tool` role in this request format. The query loop extends its state with old projected messages, assistant messages, and tool results, then calls the model again. Source: [next-round concatenation](../../claude-code/src/query.ts:1714), [role conversion](../../claude-code/src/services/api/claude.ts:588).

The model returns an ordinary explanation with no further tool call. The main query finishes; the explanation remains in conversation history.

### Request R3: the next real user turn

```text
system = S                         # rebuilt/read from cached sections as applicable
tools  = T                         # current eligible tools
messages = [
  user:      [C, first user prompt, initial attachments],
  assistant: [text(...), tool_use(id="read_1", ...)],
  user:      [tool_result("read_1", "file content..."), reminders],
  assistant: [text("Here is how login works...")],
  user:      [text("Now add a test for an expired token."), new attachments]
]
```

The assistant can refer to the earlier file because that content is still in the retained history, not because the model retains hidden state between these client requests. `C` appears once at the front of each rebuilt request, not once per human turn. The foreground history is supplied again subject to projection/reduction. Sources: [REPL query history input](../../claude-code/src/screens/REPL.tsx:2793), [prefix creation](../../claude-code/src/utils/api.ts:461).

Further Edit/Bash/test tool cycles add assistant `tool_use` and user `tool_result` content in the same pattern. Parallel tools produce several blocks/results; normalization merges local records into a suitable API sequence. Additional tool output and reminders are why a single human turn can enlarge context substantially.

## Caching does not trim the conversation

The request builder maps **all** normalized messages into API messages. Ordinarily it puts a `cache_control` marker on the last message's eligible final content block; special fork options move the marker to an earlier shared-prefix position. System/tool caching is prepared separately. These markers allow prefix reuse server-side while the client still supplies the retained prefix. The cache reduces repeated processing, not the semantic amount of conversation retained. Sources: [all-message mapping](../../claude-code/src/services/api/claude.ts:3063), [content marker placement](../../claude-code/src/services/api/claude.ts:588), [system prompt blocks](../../claude-code/src/services/api/claude.ts:1374).

The actual model context size still includes system instructions, tool schemas, user-context prefix, and retained conversational content. The code explicitly warns that counting only post-compact message payload omits those other inputs. Source: [post-compact count caveat](../../claude-code/src/services/compact/compact.ts:633).

## Growth and compaction

Before each model iteration, `query()` takes the portion of history from the latest compaction boundary, applies tool-result budgeting, optional history snipping, microcompact, optional context collapse, and autocompact before constructing the request. Their gates differ; the existence of a branch in this snapshot does not imply every installation runs it. Sources: [projection and reductions](../../claude-code/src/query.ts:365), [microcompact then autocompact](../../claude-code/src/query.ts:412).

### Microcompact

Microcompact is principally tool-result reduction. One configurable, disabled-by-default path detects a sufficiently long idle gap and replaces older compactable tool-result bodies with `[Old tool result content cleared]`, retaining tool IDs and a recent tail. A gated cache-editing path leaves local message content intact while queuing deletion instructions for the API layer. Neither path performs an LLM summary of the whole conversation. If neither is available, this function returns the messages unchanged. Sources: [microcompact dispatch](../../claude-code/src/services/compact/microCompact.ts:253), [time-based defaults](../../claude-code/src/services/compact/timeBasedMCConfig.ts:30), [body replacement](../../claude-code/src/services/compact/microCompact.ts:446), [cache-edit queue](../../claude-code/src/services/compact/microCompact.ts:298).

### Full autocompact

The threshold uses estimated input usage and a model-dependent context window with room reserved for summary output and a further buffer. Settings, environment variables, recursion guards, and experimental alternate context strategies affect when it runs. Autocompact first tries a session-memory compaction route, then falls back to `compactConversation()`; the latter generates an LLM summary. Sources: [threshold](../../claude-code/src/services/compact/autoCompact.ts:28), [eligibility](../../claude-code/src/services/compact/autoCompact.ts:160), [session-memory and fallback routes](../../claude-code/src/services/compact/autoCompact.ts:287), [summary request](../../claude-code/src/services/compact/compact.ts:440).

The resulting local sequence is:

```text
system record: compact boundary
user record:   compact summary + continuation instruction
optional:      messages preserved verbatim by this compaction strategy
attachments:   restored files, current mode, invoked skills, agents/MCP state, etc.
hook results:  SessionStart(compact) outputs
```

Source: [buildPostCompactMessages](../../claude-code/src/services/compact/compact.ts:325), [restoration](../../claude-code/src/services/compact/compact.ts:520), [summary message](../../claude-code/src/services/compact/compact.ts:614).

The next model request is schematically:

```text
system = S
tools  = T
messages = [
  user: [C, "This session is being continued...", summary,
         continuation instruction, restored attachment text],
  ...optional retained suffix...
]
```

Exact attachment/kept-message positions follow the local ordering above and role merging; this is a simplified full-compaction case. Original pre-boundary turns no longer appear verbatim in the model-facing messages. The boundary itself is a local system record filtered by normalization. The summary can include a path to the full transcript for explicit later reading. Sources: [boundary slicing](../../claude-code/src/utils/messages.ts:4631), [query replacement](../../claude-code/src/query.ts:528), [summary continuation/transcript text](../../claude-code/src/services/compact/prompt.ts:337).

UI scrollback is a separate concern: fullscreen mode can retain an earlier compact interval for display even while query projection excludes it; ordinary mode resets the active message list at the new boundary. Therefore “I can still scroll to it” does not prove the model sees those original messages. Source: [REPL boundary handling](../../claude-code/src/screens/REPL.tsx:2584).

Compaction also resets context caches so future turns can rebuild discovered instructions/memory and system sections. In the immediate compaction iteration, the existing `systemPrompt` and `userContext` parameters are still used; clearing caches does not itself rewrite variables already passed into the running query. Sources: [cleanup](../../claude-code/src/services/compact/postCompactCleanup.ts:51), [replace messages only](../../claude-code/src/query.ts:528), [request call](../../claude-code/src/query.ts:660).

## Relation to memory

Conversation growth and summarization are separate from durable memory extraction. Durable memory is selected saved information that may be loaded through the user-context index or relevance attachments. A compact summary is a new conversation message used to continue this session. Neither mechanism means the whole conversation is automatically converted into durable memory on every turn. See the companion [memory extraction and recall report](research.md).
