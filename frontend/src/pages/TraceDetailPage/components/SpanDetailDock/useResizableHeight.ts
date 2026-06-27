import { useCallback, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';

// Drag-to-resize height for the dock: returns the current height plus the grip's
// mouse-down handler. Clamps between 150px and 72% of the viewport.
export const useResizableHeight = (initialHeight: number) => {
  const [height, setHeight] = useState(initialHeight);
  const dragRef = useRef<{ startY: number; startH: number } | null>(null);

  const onGripDown = useCallback(
    (e: ReactMouseEvent) => {
      e.preventDefault();
      dragRef.current = { startY: e.clientY, startH: height };
      const onMove = (ev: MouseEvent) => {
        if (!dragRef.current) {
          return;
        }
        const next = dragRef.current.startH + (dragRef.current.startY - ev.clientY);
        setHeight(Math.max(150, Math.min(window.innerHeight * 0.72, next)));
      };
      const onUp = () => {
        dragRef.current = null;
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
      };
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    },
    [height],
  );

  return { height, onGripDown };
};
