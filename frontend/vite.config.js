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
import { defineConfig, coverageConfigDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  build: {
    rollupOptions: {
      output: {
        // React/MUI/Emotion churn far less often than the app's own pages, so
        // pulling them into their own chunk lets browsers cache them across
        // deploys instead of re-downloading them whenever a page changes.
        manualChunks: (id) => {
          if (!id.includes("node_modules")) {
            return undefined;
          }
          if (
            id.includes("/react/") ||
            id.includes("/react-dom/") ||
            id.includes("/scheduler/") ||
            id.includes("/@mui/") ||
            id.includes("/@emotion/")
          ) {
            return "vendor";
          }
          return undefined;
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setupTests.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      exclude: [
        ...coverageConfigDefaults.exclude,
        "src/main.tsx",
        "src/**/index.ts",
        "src/**/types.ts",
        "src/**/*Types.ts",
        "src/**/*SampleData.ts",
        "src/lib/sampleData.ts",
        // Containers (useQuery/useMemo/handler wiring) aren't covered by this pass —
        // that needs MSW/query-client mocking, deliberately out of scope. Views are
        // tested instead; see frontend/CLAUDE.md's testing section.
        "src/pages/CostPage/CostPage.tsx",
        "src/pages/LogsPage/LogsPage.tsx",
        "src/pages/McpServersPage/McpServersPage.tsx",
        "src/pages/MetricsPage/MetricsPage.tsx",
        "src/pages/PermissionDenialsPage/PermissionDenialsPage.tsx",
        "src/pages/ReportPage/ReportPage.tsx",
        "src/pages/SessionsPage/SessionsPage.tsx",
        "src/pages/SettingsPage/SettingsPage.tsx",
        "src/pages/SkillsAgentsPage/SkillsAgentsPage.tsx",
        "src/pages/TokensPage/TokensPage.tsx",
        "src/pages/ToolCallsPage/ToolCallsPage.tsx",
        "src/pages/ToolReliabilityPage/ToolReliabilityPage.tsx",
        "src/pages/TraceDetailPage/TraceDetailPage.tsx",
        "src/pages/TracesPage/TracesPage.tsx",
        "src/pages/TrendReportPage/TrendReportPage.tsx",
        "src/pages/ToolCallsPage/components/CallsOverTimeCard/CallsOverTimeCard.tsx",
        "src/pages/ToolCallsPage/components/StatsRow/StatsRow.tsx",
        "src/pages/ToolCallsPage/components/ToolLatencyCard/ToolLatencyCard.tsx",
        "src/pages/TraceDetailPage/components/TraceDetailHeader/TraceDetailHeader.tsx",
        "src/pages/TraceDetailPage/components/TraceDetailHeader/SwitchTraceModal.tsx",
        "src/pages/TracesPage/components/TraceFilterChips/TraceFilterChips.tsx",
        "src/pages/TracesPage/components/TraceHistogram/TraceHistogram.tsx",
        "src/pages/TracesPage/components/TraceSortDropdown/TraceSortDropdown.tsx",
        "src/pages/TracesPage/components/TraceStream/TraceStream.tsx",
        "src/pages/TracesPage/components/TraceSummaryInline/TraceSummaryInline.tsx",
        "src/pages/TracesPage/components/TraceTable/TraceTable.tsx",
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 80,
        statements: 80,
      },
    },
  },
});
