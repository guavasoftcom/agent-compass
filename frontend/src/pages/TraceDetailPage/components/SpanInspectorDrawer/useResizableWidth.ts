import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';

const MINIMUM_WIDTH_PX = 340;
const MAXIMUM_WIDTH_FRACTION = 0.62;

// Drag-to-resize width for the inspector drawer: the grip sits on the drawer's
// left edge, so dragging left widens it. `widthPx` stays null until the first
// drag — the drawer falls back to its default `min(440px, 42vw)` — and then
// persists for the rest of the session (across span selections, not across
// reloads). `isResizing` is true while dragging so the drawer can disable its
// width transition and track the cursor 1:1. Clamps between 340px and 62% of
// the viewport width.
export const useResizableWidth = () => {
  const drawerRef = useRef<HTMLDivElement>(null);
  const [widthPx, setWidthPx] = useState<number | null>(null);
  const [isResizing, setIsResizing] = useState(false);
  const dragOriginRef = useRef({ startX: 0, startWidth: 0 });

  const onGripDown = useCallback((e: ReactMouseEvent) => {
    e.preventDefault();
    dragOriginRef.current = {
      startX: e.clientX,
      startWidth: drawerRef.current?.getBoundingClientRect().width ?? 0,
    };
    setIsResizing(true);
  }, []);

  // The document listeners live in an effect rather than being registered
  // inside the mousedown handler, so React tears them down for us: a drawer
  // unmounted mid-drag can't leave them attached. Ending the drag on
  // mouseleave/blur as well as mouseup matters because releasing the button
  // outside the viewport (or over browser chrome) never delivers a mouseup the
  // document sees, which used to leave the drawer resizing with no button held.
  useEffect(() => {
    if (!isResizing) {
      return undefined;
    }
    const onMouseMove = (event: MouseEvent) => {
      const { startX, startWidth } = dragOriginRef.current;
      const nextWidth = startWidth + (startX - event.clientX);
      setWidthPx(
        Math.max(
          MINIMUM_WIDTH_PX,
          Math.min(window.innerWidth * MAXIMUM_WIDTH_FRACTION, nextWidth),
        ),
      );
    };
    const stopResizing = () => setIsResizing(false);
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', stopResizing);
    document.addEventListener('mouseleave', stopResizing);
    window.addEventListener('blur', stopResizing);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', stopResizing);
      document.removeEventListener('mouseleave', stopResizing);
      window.removeEventListener('blur', stopResizing);
    };
  }, [isResizing]);

  return { drawerRef, widthPx, isResizing, onGripDown };
};
