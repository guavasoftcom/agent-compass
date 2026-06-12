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
