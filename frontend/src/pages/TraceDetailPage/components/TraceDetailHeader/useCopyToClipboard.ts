import { useState } from 'react';

// Copy-to-clipboard with a transient "copied" flag that auto-resets, so a chip
// or button can show momentary confirmation after a click.
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
