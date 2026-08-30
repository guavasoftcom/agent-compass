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
