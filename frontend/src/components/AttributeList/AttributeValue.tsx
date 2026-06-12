import { useState } from 'react';
import { Box, Button } from '@mui/material';
import type { ValueDialogState } from './types';
import { formatAttrValue } from './utils';

export type { ValueDialogState };

const LONG_VALUE_THRESHOLD = 200;

export interface AttributeValueProps {
  attrKey: string;
  value: unknown;
  truncate: boolean;
  onExpand: (state: ValueDialogState) => void;
  // When true, a long value expands IN PLACE (preview ⇄ full) instead of opening
  // the dialog. Used inside ExpandedValueDialog so the modal never stacks a
  // second dialog on top of itself.
  inlineExpand?: boolean;
}

const fullValueSx = {
  m: 0,
  display: 'inline',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontFamily: 'inherit',
  fontSize: 'inherit',
} as const;

const previewSx = { fontFamily: 'inherit', fontSize: 'inherit' } as const;

const inlineButtonSx = {
  minWidth: 0,
  p: 0,
  fontSize: 'inherit',
  textTransform: 'none',
  verticalAlign: 'baseline',
} as const;

export const AttributeValue = ({
  attrKey,
  value,
  truncate,
  onExpand,
  inlineExpand = false,
}: AttributeValueProps): React.ReactElement => {
  const [open, setOpen] = useState(false);
  const formatted = formatAttrValue(value);
  const isLong = truncate && formatted.length > LONG_VALUE_THRESHOLD;

  if (!isLong) {
    return (
      <Box component="pre" sx={fullValueSx}>
        {formatted}
      </Box>
    );
  }

  // Inside the modal: grow the value in place rather than opening another dialog.
  if (inlineExpand && open) {
    return (
      <>
        <Box component="pre" sx={fullValueSx}>
          {formatted}
        </Box>{' '}
        <Button
          size="small"
          variant="text"
          onClick={() => setOpen(false)}
          sx={inlineButtonSx}
        >
          Show less
        </Button>
      </>
    );
  }

  const preview = formatted.slice(0, LONG_VALUE_THRESHOLD).replace(/\s+/g, ' ');
  return (
    <>
      <Box component="span" sx={previewSx}>
        {preview}…{' '}
      </Box>
      <Button
        size="small"
        variant="text"
        onClick={
          inlineExpand
            ? () => setOpen(true)
            : () => onExpand({ key: attrKey, value: formatted })
        }
        sx={inlineButtonSx}
      >
        View more
      </Button>
    </>
  );
};
