import { useState } from 'react';
import type { TraceSortKey } from '../../tracesApi';
import TraceSortDropdownView from './TraceSortDropdownView';

export interface TraceSortDropdownProps {
  sort: TraceSortKey;
  onSortChange: (sort: TraceSortKey) => void;
}

const TraceSortDropdown = ({ sort, onSortChange }: TraceSortDropdownProps) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <TraceSortDropdownView
      sort={sort}
      isOpen={isOpen}
      onToggleOpen={() => setIsOpen((previous) => !previous)}
      onClose={() => setIsOpen(false)}
      onSelect={(selectedSort) => {
        onSortChange(selectedSort);
        setIsOpen(false);
      }}
    />
  );
};

export default TraceSortDropdown;
