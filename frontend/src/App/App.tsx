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
import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import AppShell from './AppShell';

// Route-level code splitting: every page is its own lazily-loaded chunk so the
// initial bundle only carries the app shell + routing. AppShell wraps the
// routed <Outlet /> in a <Suspense> (with the ErrorBoundary above it catching
// a failed chunk load), so no Suspense boundary is needed here.
const ToolActivitySection = lazy(() => import('../pages/ToolActivitySection'));
const ToolCallsPage = lazy(() => import('../pages/ToolCallsPage'));
const ToolReliabilityPage = lazy(() => import('../pages/ToolReliabilityPage'));
const SkillsAgentsPage = lazy(() => import('../pages/SkillsAgentsPage'));
const McpServersPage = lazy(() => import('../pages/McpServersPage'));
const PermissionDenialsPage = lazy(() => import('../pages/PermissionDenialsPage'));
const TokensPage = lazy(() => import('../pages/TokensPage'));
const CostPage = lazy(() => import('../pages/CostPage'));
const SessionsPage = lazy(() => import('../pages/SessionsPage'));
const MetricsPage = lazy(() => import('../pages/MetricsPage'));
const LogsPage = lazy(() => import('../pages/LogsPage'));
const TracesPage = lazy(() => import('../pages/TracesPage'));
const TraceDetailPage = lazy(() => import('../pages/TraceDetailPage'));
const ReportPage = lazy(() => import('../pages/ReportPage'));
const TrendReportPage = lazy(() => import('../pages/TrendReportPage'));
const SettingsPage = lazy(() => import('../pages/SettingsPage'));

const App = () => {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/tools/calls" replace />} />

        <Route path="/tools" element={<ToolActivitySection />}>
          <Route index element={<Navigate to="calls" replace />} />
          <Route path="calls" element={<ToolCallsPage />} />
          <Route path="reliability" element={<ToolReliabilityPage />} />
          <Route path="skills-agents" element={<SkillsAgentsPage />} />
          <Route path="mcp-servers" element={<McpServersPage />} />
          <Route path="permissions" element={<PermissionDenialsPage />} />
        </Route>

        <Route path="/tokens" element={<TokensPage />} />
        <Route path="/cost" element={<CostPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/metrics" element={<MetricsPage />} />
        <Route path="/logs" element={<LogsPage />} />
        <Route path="/traces" element={<TracesPage />} />
        <Route path="/traces/:traceId" element={<TraceDetailPage />} />
        <Route path="/report" element={<ReportPage />} />
        <Route path="/trend-report" element={<TrendReportPage />} />
        <Route path="/settings" element={<SettingsPage />} />

        {/* Legacy paths from before the Tool activity section existed. */}
        <Route path="/tool-calls" element={<Navigate to="/tools/calls" replace />} />
        <Route path="/tool-reliability" element={<Navigate to="/tools/reliability" replace />} />
        <Route path="/skills-agents" element={<Navigate to="/tools/skills-agents" replace />} />
      </Route>
    </Routes>
  );
};

export default App;
