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
