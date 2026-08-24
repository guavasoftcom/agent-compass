const TASK_NOTIFICATION_SUMMARY_PATTERN = /<summary>([\s\S]*?)<\/summary>/;

// Renders a short, human-readable summary for a prompt that isn't really
// human-authored text — currently just the <task-notification> envelope the
// harness delivers when a background subagent finishes (see its format:
// <task-id>, <tool-use-id>, <status>, <summary>, ...), detected by its exact
// opening tag and summarized from its own <summary> tag. A generic name and
// entry point on purpose: other non-authored prompt shapes that need the same
// "don't show this raw" treatment belong here as additional cases, not as
// separate one-off detectors scattered across call sites. Returns null for an
// ordinary, human-authored prompt.
export const promptSummaryRenderer = (prompt: string): string | null => {
  if (!prompt.trimStart().startsWith('<task-notification>')) {
    return null;
  }
  const summaryMatch = TASK_NOTIFICATION_SUMMARY_PATTERN.exec(prompt);
  return summaryMatch ? summaryMatch[1].trim() : 'Subagent task notification';
};
