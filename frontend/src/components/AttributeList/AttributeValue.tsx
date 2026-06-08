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
}

export const AttributeValue = ({
  attrKey,
  value,
  truncate,
  onExpand,
}: AttributeValueProps): React.ReactElement => {
  const formatted = formatAttrValue(value);
  const isLong = truncate && formatted.length > LONG_VALUE_THRESHOLD;
  if (!isLong) {
    return (
      <Box
        component="pre"
        sx={{
          m: 0,
          display: 'inline',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          fontFamily: 'inherit',
          fontSize: 'inherit',
        }}
      >
        {formatted}
      </Box>
    );
  }
  const preview = formatted.slice(0, LONG_VALUE_THRESHOLD).replace(/\s+/g, ' ');
  return (
    <>
      <Box
        component="span"
        sx={{
          fontFamily: 'inherit',
          fontSize: 'inherit',
        }}
      >
        {preview}…{' '}
      </Box>
      <Button
        size="small"
        variant="text"
        onClick={() => onExpand({ key: attrKey, value: formatted })}
        sx={{
          minWidth: 0,
          p: 0,
          fontSize: 'inherit',
          textTransform: 'none',
          verticalAlign: 'baseline',
        }}
      >
        View more
      </Button>
    </>
  );
};
