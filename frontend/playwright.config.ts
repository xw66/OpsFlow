import { defineConfig, devices } from '@playwright/test'
const port = Number(process.env.OPSFLOW_E2E_PORT || 5274)
const baseURL = process.env.OPSFLOW_E2E_BASE_URL || `http://127.0.0.1:${port}`
const useWebServer = process.env.OPSFLOW_E2E_USE_EXTERNAL !== 'true'
export default defineConfig({
  testDir: './e2e', timeout: 90000, expect: { timeout: 12000 }, workers: 1, retries: 0, forbidOnly: true,
  reporter: [['list'], ['json', { outputFile: '../target/e2e/browser-results.json' }]],
  use: { baseURL, actionTimeout: 15000, navigationTimeout: 20000, trace: 'off', screenshot: 'only-on-failure', ...devices['Desktop Chrome'] },
  webServer: useWebServer ? { command: `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port ${port}`, url: baseURL, reuseExistingServer: false,
    env: { OPSFLOW_API_URL: process.env.OPSFLOW_E2E_API_URL || 'http://127.0.0.1:8182' } } : undefined,
})
