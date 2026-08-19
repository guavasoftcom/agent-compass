import { useState } from 'react';

// Shared "click to copy, flash confirmation" behavior for IdChip. Resets after
// resetAfterMs (default 1.2s) so the confirmation doesn't linger indefinitely.
export const useCopyToClipboard = (resetAfterMs = 1200) => {
  const [copied, setCopied] = useState(false);
  const copy = (text: string) => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), resetAfterMs);
    }
  };
  return { copied, copy };
};
