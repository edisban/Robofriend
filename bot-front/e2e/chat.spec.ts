import { expect, test } from "@playwright/test";
import { conversation, fakeApi } from "./fakeApi";

test("register, send a message and see RoboFriend's streamed reply", async ({ page }) => {
  const requests = await fakeApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "New here? Create a profile" }).click();
  await page.getByLabel("Username").fill("eleni");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-1");
  await page.getByRole("button", { name: "Create my profile" }).click();
  await page.getByRole("button", { name: "Let's talk" }).click();

  await page.getByRole("textbox", { name: "Message", exact: true }).fill("Hi! I'm Eleni");
  await page.getByRole("button", { name: "Send message" }).click();

  const conversationPane = page.getByTestId("conversation");
  await expect(conversationPane.getByText("Hi! I'm Eleni")).toBeVisible();
  await expect(conversationPane.getByText("Hey! Nice to meet you 😊")).toBeVisible();
  expect(requests.find((r) => r.path === "/api/auth/register")?.body).toMatchObject({ username: "eleni" });
  expect(requests.find((r) => r.path === "/api/chat/stream")?.body).toMatchObject({ message: "Hi! I'm Eleni" });
});

test("opens on the latest 50 messages and loads older ones when scrolling up", async ({ page }) => {
  const requests = await fakeApi(page, { signedInAs: "eleni", history: conversation(120) });
  await page.goto("/");

  const pane = page.getByTestId("conversation");
  await expect(pane.getByText("message 120", { exact: true })).toBeVisible();
  await expect(pane.getByText("message 71", { exact: true })).toBeAttached();
  await expect(pane.getByText("message 70", { exact: true })).not.toBeAttached();
  expect(requests.filter((r) => r.path.startsWith("/api/chat/history/"))[0].path).toContain("limit=50");

  // Like a user: wait until the chat has scrolled down to the newest message, then scroll up.
  // (Older messages load only when scrolling up, so they don't load while the chat opens.)
  const atBottom = () => pane.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight < 5);
  await expect.poll(atBottom).toBe(true);

  // Up to the top: the page before message 71 is fetched and added above.
  await pane.evaluate((el) => el.scrollTo({ top: 0 }));
  await expect(pane.getByText("message 21", { exact: true })).toBeAttached();
  expect(requests.some((r) => r.path.includes("beforeId=71"))).toBe(true);

  // The messages that were on screen stay put instead of jumping down to the new ones.
  await expect(pane.getByText("message 71", { exact: true })).toBeInViewport();
  expect(await pane.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);

  // Once more to the very start of the conversation.
  await pane.evaluate((el) => el.scrollTo({ top: 0 }));
  await expect(pane.getByText("message 1", { exact: true })).toBeAttached();
});

test("shows a friendly bubble when sending too fast", async ({ page }) => {
  await fakeApi(page, {
    signedInAs: "eleni",
    history: conversation(4),
    onStream: (route) =>
      route.fulfill({
        status: 429,
        contentType: "application/json",
        headers: { "Retry-After": "30" },
        body: JSON.stringify({ error: "Slow down a little. Try again in a moment.", limit: "minute" }),
      }),
  });
  await page.goto("/");

  await page.getByRole("textbox", { name: "Message", exact: true }).fill("one more!");
  await page.getByRole("button", { name: "Send message" }).click();

  await expect(page.getByTestId("conversation").getByText(/Slow down a little/)).toBeVisible();
});
