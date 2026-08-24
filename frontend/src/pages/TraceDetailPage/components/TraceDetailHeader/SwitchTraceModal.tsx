import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchSessionPrompts } from '../../../../api';
import SwitchTraceModalView, { hasTraceAndPrompt } from './SwitchTraceModalView';

interface Props {
  open: boolean;
  onClose: () => void;
  sessionId: string;
  currentTraceId: string;
}

// Container: fetches the session's full prompt timeline (same fetcher/shape
// PromptTimelinePanel on the Sessions page already uses) and filters it down
// to rows with both a trace and a prompt — pre-tracing sessions and
// prompt-capture-off rows have neither and aren't traces a reader can jump
// to. `enabled: open` means nothing fetches until the pill is first clicked;
// the query key matches SessionsPage's own `['session-prompts', sessionId]`
// so opening the switcher for a session already inspected there reads from
// cache instead of refetching.
const SwitchTraceModal = ({ open, onClose, sessionId, currentTraceId }: Props) => {
  const navigate = useNavigate();

  const { data: prompts, isLoading } = useQuery({
    queryKey: ['session-prompts', sessionId],
    queryFn: () => fetchSessionPrompts(sessionId),
    enabled: open,
  });

  const rows = useMemo(() => (prompts ?? []).filter(hasTraceAndPrompt), [prompts]);

  const onSelectTrace = (traceId: string) => {
    onClose();
    navigate(`/traces/${traceId}`);
  };

  return (
    <SwitchTraceModalView
      open={open}
      onClose={onClose}
      sessionId={sessionId}
      currentTraceId={currentTraceId}
      rows={rows}
      isLoading={isLoading}
      onSelectTrace={onSelectTrace}
    />
  );
};

export default SwitchTraceModal;
