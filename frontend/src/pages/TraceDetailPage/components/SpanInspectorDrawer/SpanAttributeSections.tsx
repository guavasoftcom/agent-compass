import { Box } from '@mui/material';
import CollapsibleSection from './CollapsibleSection';
import { AttrRows } from './drawerParts';

// Keys already shown in the drawer's meta grid — and *_tokens (covered by the
// Tokens section) — are dropped so the attribute list doesn't duplicate them.
const LEFT_KEYS = new Set(['status', 'duration', 'kind', 'scope', 'started', 'ended', 'spanid']);

const isRedundant = (key: string) => {
  const normalized = key.replace(/_(ms|us|ns|sec|seconds)$/i, '').split('.').pop()!.toLowerCase().replace(/\s+/g, '');
  return LEFT_KEYS.has(normalized) || /tokens?$/i.test(key);
};

const isToolAttr = (key: string) => /tool|path|command/i.test(key);

const wrenchIcon = (
  <Box
    component="svg"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    sx={{ width: 13, height: 13, flexShrink: 0 }}
  >
    <path d="M14.7 6.3a4 4 0 0 0-5.2 5.2L3 18l3 3 6.5-6.5a4 4 0 0 0 5.2-5.2l-2.4 2.4-2.1-.6-.6-2.1 2.4-2.4z" />
  </Box>
);

// Span attributes split into a collapsible Tool section and a collapsible
// Attributes section, with redundant keys filtered out.
const SpanAttributeSections = ({ attributes }: { attributes: Record<string, unknown> | undefined }) => {
  const kept = Object.entries(attributes ?? {}).filter(([key]) => !isRedundant(key));
  const toolEntries = kept.filter(([key]) => isToolAttr(key));
  const otherEntries = kept.filter(([key]) => !isToolAttr(key));
  return (
    <>
      {toolEntries.length ? (
        <CollapsibleSection title="Tool" tone="tool" count={toolEntries.length} icon={wrenchIcon}>
          <AttrRows tone="tool" attrs={Object.fromEntries(toolEntries)} />
        </CollapsibleSection>
      ) : null}
      {otherEntries.length ? (
        <CollapsibleSection title="Attributes" count={otherEntries.length}>
          <AttrRows attrs={Object.fromEntries(otherEntries)} />
        </CollapsibleSection>
      ) : null}
    </>
  );
};

export default SpanAttributeSections;
