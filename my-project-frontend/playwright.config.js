import { defineConfig } from '@playwright/test'
import { existsSync } from 'node:fs'

const systemChrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const chromeExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
  || (existsSync(systemChrome) ? systemChrome : undefined)

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    launchOptions: chromeExecutable ? { executablePath: chromeExecutable } : {},
    trace: 'retain-on-failure'
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: false,
    timeout: 120_000
  },
  projects: [{
    name: 'desktop-chromium',
    use: {
      browserName: 'chromium',
      viewport: { width: 1440, height: 900 }
    }
  }]
})
