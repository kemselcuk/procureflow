import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Critical browser journeys only. The suite expects a disposable, already-ready stack
 * (the Python runner owns Compose). It never starts product services itself.
 *
 * Outputs are opt-in through the runner:
 * - P6_PLAYWRIGHT_OUTPUT points at an approved temporary directory;
 * - P6_PLAYWRIGHT_JSON is the machine-readable result file;
 * - P6_BROWSER_EXECUTABLE optionally reuses an explicitly supplied Chromium binary.
 *
 * Trace, video, HAR and storage state are deliberately disabled: authentication data must
 * not become a persisted test artifact.
 */
const baseURL = process.env.P6_BASE_URL;
if (!baseURL) throw new Error('P6_BASE_URL is required');

const jsonOutput = process.env.P6_PLAYWRIGHT_JSON;
const outputDir = process.env.P6_PLAYWRIGHT_OUTPUT ?? path.resolve(__dirname, '../target/p6-browser/playwright-results');
const executablePath = process.env.P6_BROWSER_EXECUTABLE;

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir,
  reporter: jsonOutput
    ? [['list'], ['json', { outputFile: jsonOutput }]]
    : [['list']],
  use: {
    baseURL,
    headless: true,
    viewport: { width: 1280, height: 900 },
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    testIdAttribute: 'data-testid',
    launchOptions: executablePath ? { executablePath } : {},
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } },
    },
  ],
});
