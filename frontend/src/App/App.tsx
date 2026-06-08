import { Navigate, Route, Routes } from 'react-router-dom';
import AppShell from './AppShell';
import ToolActivitySection from '../pages/ToolActivitySection';
import ToolCallsPage from '../pages/ToolCallsPage';
import ToolReliabilityPage from '../pages/ToolReliabilityPage';
import SkillsAgentsPage from '../pages/SkillsAgentsPage';
import PermissionDenialsPage from '../pages/PermissionDenialsPage';
import TokensPage from '../pages/TokensPage';
import SessionsPage from '../pages/SessionsPage';
import MetricsPage from '../pages/MetricsPage';
import LogsPage from '../pages/LogsPage';
import TracesPage from '../pages/TracesPage';
import TraceDetailPage from '../pages/TraceDetailPage';
import ReportPage from '../pages/ReportPage';

const App = () => {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/tool-activity/calls" replace />} />

        <Route path="/tool-activity" element={<ToolActivitySection />}>
          <Route index element={<Navigate to="calls" replace />} />
          <Route path="calls" element={<ToolCallsPage />} />
          <Route path="reliability" element={<ToolReliabilityPage />} />
          <Route path="skills-agents" element={<SkillsAgentsPage />} />
          <Route path="permissions" element={<PermissionDenialsPage />} />
        </Route>

        <Route path="/tokens" element={<TokensPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/insights" element={<MetricsPage />} />
        <Route path="/logs" element={<LogsPage />} />
        <Route path="/traces" element={<TracesPage />} />
        <Route path="/traces/:traceId" element={<TraceDetailPage />} />
        <Route path="/report" element={<ReportPage />} />

        {/* Legacy paths from before the Tool activity section existed. */}
        <Route path="/tool-calls" element={<Navigate to="/tool-activity/calls" replace />} />
        <Route path="/tool-reliability" element={<Navigate to="/tool-activity/reliability" replace />} />
        <Route path="/skills-agents" element={<Navigate to="/tool-activity/skills-agents" replace />} />
      </Route>
    </Routes>
  );
};

export default App;
