import { expect, test } from "@playwright/test";

test("Business User enters and switches Workspace using Java authorization context", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/realms\/askmetric\/protocol\/openid-connect\/auth/);
  await page.locator("#username").fill("alice");
  await page.locator("#password").fill("askmetric-demo");
  await page.locator("#kc-login").click();

  await expect(page.getByRole("heading", { name: "你好，Alice Chen" })).toBeVisible();
  const workspace = page.getByLabel("当前工作区");
  await expect(workspace).toHaveValue("workspace-demo");
  await expect(page.getByText("membership-demo", { exact: true })).toBeVisible();

  const confirmed = page.waitForResponse(
    (response) =>
      response.url().includes("/api/v1/session") &&
      response.request().headers()["x-workspace-id"] === "workspace-growth",
  );
  await workspace.selectOption("workspace-growth");
  const response = await confirmed;
  expect(response.ok()).toBe(true);
  const session = await response.json();

  expect(session.currentMembership.workspaceId).toBe("workspace-growth");
  await expect(workspace).toHaveValue(session.currentMembership.workspaceId);
  await expect(page.getByText(session.currentMembership.membershipId, { exact: true })).toBeVisible();
});
