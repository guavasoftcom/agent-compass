import { Box } from '@mui/material';
import { AttrRows, SectionTitle } from './dockParts';

// Keys already shown in the left meta column — and *_tokens (covered by the
// Tokens section) — are dropped so the attribute list doesn't duplicate them.
const LEFT_KEYS = new Set(['status', 'duration', 'kind', 'scope', 'started', 'ended', 'spanid']);

const isRedundant = (key: string) => {
  const normalized = key.replace(/_(ms|us|ns|sec|seconds)$/i, '').split('.').pop()!.toLowerCase().replace(/\s+/g, '');
  return LEFT_KEYS.has(normalized) || /tokens?$/i.test(key);
};

const isToolAttr = (key: string) => /tool|path|command/i.test(key);

// Span attributes split into a Tool group and an Attributes group, with
// redundant keys filtered out.
const SpanAttributesColumn = ({ attributes }: { attributes: Record<string, unknown> | undefined }) => {
  const kept = Object.entries(attributes ?? {}).filter(([key]) => !isRedundant(key));
  const toolEntries = kept.filter(([key]) => isToolAttr(key));
  const otherEntries = kept.filter(([key]) => !isToolAttr(key));
  return (
    <Box sx={{ flex: 1, minWidth: 240, display: 'flex', flexDirection: 'column', gap: 1.75 }}>
      {toolEntries.length ? (
        <Box>
          <SectionTitle count={toolEntries.length} tone="tool">Tool</SectionTitle>
          <AttrRows tone="tool" attrs={Object.fromEntries(toolEntries)} />
        </Box>
      ) : null}
      {otherEntries.length ? (
        <Box>
          <SectionTitle count={otherEntries.length}>Attributes</SectionTitle>
          <AttrRows attrs={Object.fromEntries(otherEntries)} />
        </Box>
      ) : null}
    </Box>
  );
};

export default SpanAttributesColumn;
