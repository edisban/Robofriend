import { defineConfig, devices } from "@playwright/test";

const PORT = 5175;

/**
 * Browser tests of the app's main flows. The backend API is faked inside the browser
 * (see e2e/fakeApi.ts), so they need no backend, database, Groq key or embedding service.
 */
export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://localhost:${PORT}`,
    // Shortens the splash intro and other animations.
    reducedMotion: "reduce",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "phone", use: { ...devices["Pixel 7"] } },
  ],
  // The production build, served by `vite preview`: pages load at once (the dev server compiles on
  // first request, which made the first tests slow and flaky on CI) and it's what actually ships.
  webServer: {
    command: `npm run build && npm run preview -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
