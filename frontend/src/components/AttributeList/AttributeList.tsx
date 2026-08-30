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
import { useState } from 'react';
import { AttributeListView } from './AttributeListView';
import type { ValueDialogState } from './types';

export interface AttributeListProps {
  attributes: Record<string, unknown>;
  fontSize?: string;
  truncate?: boolean;
  disableBackground?: boolean;
  // When true, long values expand inline instead of opening the value dialog.
  // The dialog passes this so nested long strings grow in place — no stacking.
  inlineExpand?: boolean;
}

export const AttributeList = ({
  attributes,
  fontSize = '0.75rem',
  truncate = true,
  disableBackground = false,
  inlineExpand = false,
}: AttributeListProps): React.ReactElement => {
  const [expanded, setExpanded] = useState<ValueDialogState | null>(null);
  return (
    <AttributeListView
      attributes={attributes}
      fontSize={fontSize}
      truncate={truncate}
      disableBackground={disableBackground}
      inlineExpand={inlineExpand}
      expanded={expanded}
      onExpand={setExpanded}
      onClose={() => setExpanded(null)}
      renderAttributeList={(attrs) => (
        <AttributeList attributes={attrs} truncate inlineExpand />
      )}
    />
  );
};
