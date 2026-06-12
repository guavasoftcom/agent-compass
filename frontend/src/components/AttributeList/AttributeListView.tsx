import React from 'react';
import { Box } from '@mui/material';
import type { ValueDialogState } from './types';
import { AttributeValue } from './AttributeValue';
import { ExpandedValueDialog } from './ExpandedValueDialog';

export interface AttributeListViewProps {
  attributes: Record<string, unknown>;
  fontSize: string;
  truncate: boolean;
  disableBackground: boolean;
  inlineExpand: boolean;
  expanded: ValueDialogState | null;
  onExpand: (state: ValueDialogState) => void;
  onClose: () => void;
  renderAttributeList: (attrs: Record<string, unknown>) => React.ReactNode;
}

export const AttributeListView = ({
  attributes,
  fontSize,
  truncate,
  disableBackground,
  inlineExpand,
  expanded,
  onExpand,
  onClose,
  renderAttributeList,
}: AttributeListViewProps): React.ReactElement => {
  return (
    <Box
      sx={{
        m: 0,
        p: 1,
        borderRadius: 1,
        fontFamily: 'monospace',
        fontSize,
        bgcolor: disableBackground ? 'transparent' : 'action.hover',
        width: '100%',
      }}
    >
      {Object.entries(attributes).map(([key, value]) => (
        <Box key={key} sx={{ mb: 0.5 }}>
          <Box component="span" sx={{ color: 'text.secondary' }}>
            {key}:{' '}
          </Box>
          <AttributeValue
            attrKey={key}
            value={value}
            truncate={truncate}
            inlineExpand={inlineExpand}
            onExpand={onExpand}
          />
        </Box>
      ))}
      <ExpandedValueDialog
        state={expanded}
        onClose={onClose}
        renderAttributeList={renderAttributeList}
      />
    </Box>
  );
};
