# What gets sent to Ollama when you analyze a trace

The Trace Detail page can send one trace to a local Ollama model ("Analyze trace") and get back a
written review. This document is the answer to "what data leaves the database and reaches the
model" — useful both for judging what a local model can see and for anyone deciding whether this
feature is safe to run against sensitive telemetry.

Nothing here reaches a third party: `OllamaClient` talks only to the `base-url` configured on the
Settings page (`ollama.base-url`, default `http://localhost:11434`) or on `application.yml`. If that
points at a local Ollama install, the prompt never leaves the machine.

The prompt itself is assembled by `TraceAnalysisPromptBuilder.build()`
([backend/src/main/java/com/guavasoft/agentcompass/service/TraceAnalysisPromptBuilder.java](../backend/src/main/java/com/guavasoft/agentcompass/service/TraceAnalysisPromptBuilder.java))
from the Mustache template
[backend/src/main/resources/templates/trace-analysis-prompt.mustache](../backend/src/main/resources/templates/trace-analysis-prompt.mustache),
using the same span/log lists the trace detail page already fetched for that trace — no new queries
run to build it.

## The shape: a projection, not a dump

The builder does **not** send every span and log verbatim. It projects the trace into nine sections,
in this order:

1. **Overview** — the same headline figures the trace detail page's summary strip shows: root span
   name, duration, span/tool/model call counts, max depth, error count, cost (with the
   background/sub-agent split called out separately), the four-way token breakdown, prompt cache
   reuse percentage, main-loop context size (starting vs. peak), time attribution (LLM generation vs.
   tool execution vs. blocked-on-user), and — if one call or sub-agent dominated spend — a cost
   breakdown by branch.
2. **What the agent said just before this request** — the tail (last ~1,500 characters) of the
   previous assistant turn in the same session, shown only when there is a real, human-written
   request to resolve it against. This is what lets a reply like "yes fix it" be judged as an answer
   to a question rather than a vague request.
3. **Skills that ran** — name, source (project-defined vs. bundled with Claude Code) and trigger for
   each skill activated during the trace. The skill's own instructions are never included, only the
   fact that it ran.
4. **How this trace was started** — for a `<task-notification>` continuation (a background task
   waking the session) or a slash command, a short section explaining that, plus the dispatching tool
   call's name and input when the trace is a continuation.
5. **User prompt (full text)** — the literal text of the `user_prompt` log record that started this
   trace, sent in full, unredacted and untruncated (beyond the overall prompt budget below). Omitted
   entirely for a sub-agent run, a continuation, or a slash command — see "What is deliberately
   withheld" below for why.
6. **Call timeline** — every span in the trace, numbered in order, condensed to one line each:
   - A tool call: the tool name, its actual input (from the matching `tool_result` log's
     `tool_input`, or the span's own `full_command`/`file_path` when no log matched), success/failure,
     and duration.
   - A model call: the model name, duration, output tokens, and cost (joined from the `api_request`
     log sharing the same `request_id`).
   - Exact-repeat and revisit markers (`× N`, `repeat of call …`, `same file, different input at …`),
     a `[SubagentName]` prefix on calls made inside a dispatched sub-agent, and a summary line on the
     `Agent` call that dispatched one.
   - A tool result at or above 20,000 bytes gets a `[N.NKB result]` tag on its line (the same bar
     the markdown report's oversized-result list uses), and a call that sat waiting on a real user
     approval for 10 seconds or more gets a `[blocked N.Ns on user approval]` tag — both computed
     from data the model would otherwise have to infer from the overview's aggregate figures alone.
7. **Verified observations** — facts computed in code, not left for the model to spot: revisited
   files, failed tool/model calls (with environmental failures like HTTP 429/408/5xx marked "not a
   finding"), shell commands with a dedicated-tool equivalent, an outlier-duration model call, cost
   concentration, poor cache reuse, an oversized starting/peak context, a trace whose approval waits
   dominated its own duration (carrying a paste-ready rule about pre-authorizing or batching those
   prompts), and — on the minority of traces that clear a measured bar — a couple of "went well"
   positives (a directed first tool call, a sub-agent that earned its cost).
8. **Errors** — one deduplicated line per distinct failure (source, model, HTTP status, count).
9. **What the agent said, in order** — every `assistant_response` log's text, not just the final one,
   each capped (1,500 characters for intermediate turns, 4,000 for the final one) and the whole
   section trimmed to a 12,000-character budget.

Everything above it is instructions: what to judge, the answer format, and the closed list of valid
"Instruction rule" targets (`CLAUDE.md` plus any project-defined skill this trace shows ran).

## What is deliberately withheld

- **Raw OTLP request/response bodies (`api_request_body` / `api_response_body`) are never read.**
  Measured on this database, a trace's request bodies alone run to 2.0M characters at the median and
  39.4M at the 95th percentile — every request re-sends the whole conversation history. The
  assistant's own prose is available far more cheaply from the `assistant_response` logs (section 9
  above), which capture the same content (within 1%) without the base64 signature blobs and redacted
  thinking that make up over a third of a response body.
- **A slash command's text is never shown as a "request" to be judged for wording.** `/ship` is a
  name for a skill, not prose someone wrote; the trace still says *that* a slash command started it,
  just not as material for the wording critique.
- **A `<task-notification>` continuation's own envelope is never shown as a request either** — only
  the fact that the trace continues earlier work, what the harness reported, and (when resolvable)
  the tool call that dispatched it.
- **A machine-authored or missing `user_prompt`** (sub-agent run, resume/heartbeat trace) drops the
  whole "User prompt" section and the request-quality half of the review; only execution and
  cost/time analysis still run.
- **Skill definitions themselves** are never included — only that a skill ran and whether its
  definition file is editable.

## Size limits

- `ollama.max-prompt-chars` (**60,000**, both in `application.yml` and as the `OllamaProperties` field
  default) is a hard cap enforced after rendering. Over budget, **the timeline gives up per-call
  detail before it gives up calls**: it is re-rendered at successively tighter detail levels — the
  tool-input cap drops 200 → 80 → 30, the `-> ok` and duration come off calls that succeeded, and a
  model call's token/cost/effort breakdown comes off — and the level needing the fewest review windows
  (see below) is used. Every call therefore keeps its own numbered line, which is what the review's
  observations and its "cite call N" contract are written against. Failures, error bodies, rejections,
  blocked-on-user waits, oversized results and sub-agent dispatch summaries are never thinned at any
  level.
- If even the tightest level does not fit in one prompt, the timeline is **partitioned into
  consecutive, non-overlapping review windows** (`TraceAnalysisPromptBuilder#partitionToBudget`)
  instead of eliding a stretch of it — each window is a complete, independent "DRAFTING" model call
  (its own header, its own carry-over block summarizing files/failures/spend before it, and its own
  slice of the timeline; the Overview/observations/errors sections render identically in every
  window), and the per-window findings are merged in code (`TraceAnalysisFindingsMerge`, deduping on
  shared call citations and, for citation-less findings, on label) before the unchanged "Apply this"
  call runs once against the merged result. No call number is ever dropped or left uncitable. The
  assistant narration still elides middle-out against its own 12k budget (`OMITTED_MESSAGES_NOTE`) —
  that section has no equivalent of "a second review call" to split across — and long individual
  values (a shell command, a tool input) are still truncated middle-out rather than from the front.
- How many review windows an analysis was generated from is stored
  (`trace_analyses.review_pass_count`) alongside the trace's total call count
  (`trace_analyses.timeline_call_count`). `timeline_truncated` / `omitted_line_count` are legacy
  columns: no analysis generated after the `V30` migration sets them to anything but `false` / `0` —
  see that migration's comment.
- `ollama.context-tokens` (default **24,576**, sent as Ollama's `options.num_ctx`) has to stay large
  enough to hold `max-prompt-chars`. Ollama silently left-truncates any prompt longer than its
  context window with no error — raising one without the other reintroduces that silent truncation.
- Every duration in the prompt is pre-formatted (`812ms`, `36.4s`, `2m 5s`) rather than left as raw
  milliseconds, so the model isn't asked to do the arithmetic itself.

## Two answer formats, same input

`ollama.structured-output` (default off) only changes what the model is asked to produce — a JSON
document matching a fixed schema instead of Markdown headings. Every section described above is
identical either way; see [backend/CLAUDE.md](../backend/CLAUDE.md) for the trade-off between the two.

## Where the response goes

The model's reply is streamed back over Server-Sent Events, rendered live in the Analyze Trace
dialog, and the finished answer is stored in `trace_analyses` (keyed by `trace_id`, latest only) so
reopening the dialog shows it again without re-running inference. Nothing in this pipeline sends the
trace or the answer anywhere outside this application and the configured Ollama endpoint.
