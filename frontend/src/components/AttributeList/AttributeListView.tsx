/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import React from 'react';
import { Box } from '@mui/material';
import type { ValueDialogState } from './types';
import { AttributeValue } from './AttributeValue';
import { ExpandedValueDialog } from './ExpandedValueDialog';
import { radii } from '../../theme/theme';

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
        typography: 'mono',
        m: 0,
        p: 1,
        borderRadius: radii.sm,
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
