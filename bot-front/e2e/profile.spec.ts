import { expect, test } from "@playwright/test";
import { conversation, fakeApi, PASSWORD } from "./fakeApi";

async function openProfile(page: import("@playwright/test").Page) {
  await page.getByRole("button", { name: "Account menu" }).click();
  await page.getByRole("menuitem", { name: "Profile" }).click();
  await expect(page.getByRole("heading", { name: "Your profile" })).toBeVisible();
}

test("edits the name and birthday on the profile page", async ({ page }) => {
  const requests = await fakeApi(page, { signedInAs: "eleni", history: conversation(2) });
  await page.goto("/");
  await openProfile(page);

  const save = page.getByRole("button", { name: "Save changes" });
  await expect(save).toBeDisabled();

  await page.getByLabel("What should RoboFriend call you?").fill("Lena");
  await page.getByLabel("Birthday").fill("1996-10-24");
  await save.click();

  await expect(page.getByText(/Profile saved/)).toBeVisible();
  await expect(save).toBeDisabled();
  expect(requests.find((r) => r.method === "PUT" && r.path === "/api/profile")?.body).toEqual({
    displayName: "Lena",
    birthday: "1996-10-24",
  });

  await page.getByRole("button", { name: "Back to chat" }).click();
  await expect(page.getByTestId("conversation")).toBeVisible();
});

test("deleting the account asks if you're sure and needs the password", async ({ page }) => {
  const requests = await fakeApi(page, { signedInAs: "eleni", history: conversation(2) });
  await page.goto("/");
  await openProfile(page);

  await page.getByRole("button", { name: "Delete my account" }).click();
  const dialog = page.getByRole("dialog", { name: "Delete your account?" });
  await expect(dialog.getByText(/Are you sure\?/)).toBeVisible();
  const confirm = dialog.getByRole("button", { name: "Delete forever" });
  await expect(confirm).toBeDisabled();

  // Changing your mind keeps everything.
  await dialog.getByRole("button", { name: "Keep my account" }).click();
  await expect(dialog).toBeHidden();
  expect(requests.some((r) => r.method === "DELETE")).toBe(false);

  await page.getByRole("button", { name: "Delete my account" }).click();
  await dialog.getByLabel("Your password").fill("wrong-password");
  await confirm.click();
  await expect(dialog.getByText("Wrong password.")).toBeVisible();

  await dialog.getByLabel("Your password").fill(PASSWORD);
  await confirm.click();

  await expect(page.getByText(/Your account has been deleted/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
