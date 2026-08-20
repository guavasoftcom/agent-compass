# Handoff: Trace Detail — markdown-rendered assistant_response logs

## Overview
`claude_code.assistant_response` log records carry their full text in a `response`
attribute that is markdown, not JSON or plain text. In the span inspector drawer's
Logs section, this attribute now:

1. Always shows the **view formatted** link, regardless of length — every other
   long attribute only gets the link once it trips the 240-char clamp.
2. Opens in a modal that renders the text **as markdown** (headings, lists, `code`,
   **bold**, links) instead of the plain/JSON-repair modal every other value uses.

Every other log attribute (or event/span attribute elsewhere in the drawer) is
unaffected — this is scoped to the one key on the one event.

## About the design files
`Aurora Trace Detail Mockup.html` + `trace-detail.js` are **design references built
in plain HTML/CSS/JS** — not production code to copy directly. The patches below
translate that behavior into the real React/MUI v9 codebase's existing
`longValue.tsx` / `LogEntry.tsx` components.

## Fidelity
**High-fidelity.** Reuse the existing modal chrome (title bar, copy/close buttons,
`Dialog` sizing) untouched — only the body swaps between the JSON `<pre>` and a
markdown render. Don't introduce new colors; the markdown typography sx pulls from
the existing theme (`text.primary`/`text.secondary`, `action.hover`, `divider`,
`primary.main`).

## New dependency
Add **`react-markdown`** to `frontend/package.json` (React 19–compatible, no
plugins needed for this — assistant responses use plain CommonMark: headings,
lists, bold/italic, inline/block code, links). It renders to real React elements
rather than `dangerouslySetInnerHTML`, which is the safer default for text that
originated from a model response. The mockup uses `marked` (it has no framework
to render into); the real codebase should use `react-markdown` instead, not port
`marked` + manual HTML injection.

## Screens / Components touched
Both in `frontend/src/pages/TraceDetailPage/components/SpanInspectorDrawer/`:

| File | Change |
|---|---|
| `longValue.tsx` | `LongValueRequest`/`LongAttrValueProps` gain `format?: 'json' \| 'markdown'` (default `'json'`); `LongAttrValueProps` gains `force?: boolean` to show the button under the clamp threshold. `LongValueModalProvider`'s dialog body renders `<ReactMarkdown>` (new `markdownSx` styles) when `format === 'markdown'`, skipping `tryParseJson`/the "repaired from truncated JSON" alert entirely for that path. |
| `LogEntry.tsx` | Detects `eventName === 'assistant_response'`; for that log's `response` attribute only, passes `force` and `format="markdown"` through to `LongAttrValue`. |

No other files change — `AttrRows` (`drawerParts.tsx`), `SpanEventsList.tsx`, and
`SpanAttributeSections.tsx` all call `LongAttrValue` without the new props, so they
default to today's behavior (`format="json"`, `force=false`) unchanged.

## Interactions & Behavior
- On any log row, expanding it (click) reveals its attribute rows same as today.
- For a normal attribute: unchanged — clamps past its limit, and only then shows
  "view formatted (N chars)", opening the existing JSON/plain-text modal.
- For `response` on an `assistant_response` log: the "view formatted (N chars)"
  link appears immediately, even if the value is short. The preview line still
  clamps at `LONG_VALUE_LOG` (240 chars) the same as any other log attribute — only
  the button's visibility condition changes.
- Clicking that link opens the same shared `Dialog` (one instance, hosted by
  `LongValueModalProvider` at the drawer root — no new dialogs mounted), but the
  body renders the raw text through `ReactMarkdown` instead of the JSON path: no
  "Repaired from truncated JSON" alert can appear for this path, since it never
  attempts `tryParseJson`.
- Copy button in the modal copies the raw markdown source text (not rendered
  HTML), consistent with copying the raw/parsed text today.

## Design Tokens
No new tokens. The markdown body's `markdownSx` (in `longValue.tsx`) reuses:
`text.primary` / `text.secondary` (body + blockquote), `action.hover` (inline
`code` and \`\`\`code fences\`\`\` background), `divider` (blockquote rule),
`primary.main` (links), and the existing `mono` typography variant for code.

## Files
- `Aurora Trace Detail Mockup.html`, `trace-detail.js` — HTML/JS design reference
  (`marked` via CDN, `#jmmd` markdown container swapped in for `#jmpre`).
- `patches/` — full updated file contents for `longValue.tsx` and `LogEntry.tsx`,
  ready to diff against `frontend/src/pages/TraceDetailPage/components/SpanInspectorDrawer/`
  in `main`.
