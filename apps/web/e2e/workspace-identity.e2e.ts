import { expect, test } from "@playwright/test";

test("Business User enters and switches Workspace using Java authorization context", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/realms\/askmetric\/protocol\/openid-connect\/auth/);
  await page.locator("#username").fill("alice");
  await page.locator("#password").fill("askmetric-demo");
  await page.locator("#kc-login").click();

  await expect(page.getByText("Alice Chen", { exact: true })).toBeVisible();
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

test("Business User sees the routed Agent Run and new Analysis Task", async ({ page }) => {
  await page.goto("/");
  await page.locator("#username").fill("alice");
  await page.locator("#password").fill("askmetric-demo");
  await page.locator("#kc-login").click();

  await page.getByRole("button", { name: "新建对话" }).click();
  await expect(page.getByRole("heading", { name: "新对话" })).toBeVisible();
  await page.getByRole("textbox", { name: "消息" }).fill("为什么本月 MRR 下降？");
  await page.getByRole("button", { name: "发送消息" }).click();

  const context = page.locator(".context-panel");
  await expect(context.getByRole("heading", { name: "Agent 运行" })).toBeVisible();
  await expect(context.getByText("analysis", { exact: true })).toBeVisible();
  await expect(context.getByText("95%", { exact: true })).toBeVisible();
  await expect(context.getByRole("heading", { name: "分析任务" })).toBeVisible();
  await expect(context.getByText("为什么本月 MRR 下降？", { exact: true })).toBeVisible();
  await expect(context.getByText("active", { exact: true })).toBeVisible();
});

test("Business User sees Agent Run progress and the terminal snapshot", async ({ page }) => {
  await page.goto("/");
  await page.locator("#username").fill("alice");
  await page.locator("#password").fill("askmetric-demo");
  await page.locator("#kc-login").click();

  await page.getByRole("button", { name: "新建对话" }).click();
  await page.getByRole("textbox", { name: "消息" }).fill("为什么本月 MRR 下降？");
  const terminalSnapshot = page.waitForResponse(async (response) => {
    if (!response.url().match(/\/api\/v1\/conversations\/conversation_[^/]+$/) || response.request().method() !== "GET") {
      return false;
    }
    const snapshot = await response.json();
    return snapshot.agentRuns.some((run: { auditEvents: Array<{ eventType: string }> }) =>
      run.auditEvents.some((event) => event.eventType === "agent.run.completed"),
    );
  });
  await page.getByRole("button", { name: "发送消息" }).click();

  await terminalSnapshot;
  const timeline = page.getByLabel("Agent 运行记录");
  await expect(timeline.getByText("agent.run.completed", { exact: true })).toBeVisible();
  await expect(timeline.getByText("Synthetic Agent Run completed", { exact: true })).toBeVisible();
});
