import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/browser",
  workers: 1,
  timeout: 45_000,
  use: { baseURL: "http://127.0.0.1:3307", viewport: { width: 1440, height: 1000 }, trace: "retain-on-failure" },
  outputDir: "output/playwright/results",
  webServer: { command: "npx vite --config vite.browser.config.ts --host 127.0.0.1 --port 3307", url: "http://127.0.0.1:3307", reuseExistingServer: false, timeout: 120_000 },
});
