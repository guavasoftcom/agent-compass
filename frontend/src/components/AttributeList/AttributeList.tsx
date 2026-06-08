import { useState } from 'react';
import { AttributeListView } from './AttributeListView';
import type { ValueDialogState } from './types';

export interface AttributeListProps {
  attributes: Record<string, unknown>;
  fontSize?: string;
  truncate?: boolean;
  disableBackground?: boolean;
}

export const AttributeList = ({
  attributes,
  fontSize = '0.75rem',
  truncate = true,
  disableBackground = false,
}: AttributeListProps): React.ReactElement => {
  const [expanded, setExpanded] = useState<ValueDialogState | null>(null);
  return (
    <AttributeListView
      attributes={attributes}
      fontSize={fontSize}
      truncate={truncate}
      disableBackground={disableBackground}
      expanded={expanded}
      onExpand={setExpanded}
      onClose={() => setExpanded(null)}
      renderAttributeList={(attrs) => (
        <AttributeList attributes={attrs} truncate={false} />
      )}
    />
  );
};
