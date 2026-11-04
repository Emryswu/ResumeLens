import { test, expect, type BrowserContext, type Page } from "@playwright/test";
import { agentChatReducer, createSseParser, initialAgentChatState, toAgentEvent } from "../../app/agent-stream";

async function session(context: BrowserContext, user = "alice") {
  let account = user;
  await context.route("**/api/backend/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes("/api/agent/")) return route.continue();
    const data = url.pathname.includes("/auth/")
      ? { accessToken: account, user: { id: account === "alice" ? 1 : 2, username: account, displayName: account }, expiresIn: 3600 }
      : url.pathname.endsWith("/ai/status") ? { mockEnabled: true, configured: true, model: "scripted" }
        : url.pathname.endsWith("/analysis-histories/7") ? { id: 7, status: "COMPLETED", resumeTitle: "Java 简历", jobTitle: "Java 后端", matchScore: 80 }
          : { content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 };
    await route.fulfill({ json: { success: true, data } });
  });
  return (next: string) => { account = next; };
}

async function newChat(page: Page) {
  await page.goto("/");
  await expect(page.getByRole("navigation", { name: "历史会话" })).toBeVisible();
  await expect(page.getByText("正在读取会话…")).toHaveCount(0);
  await page.locator(".assistant-toolbar").getByRole("button", { name: "新对话", exact: true }).click();
  await expect(page.getByText("正在读取会话…")).toHaveCount(0);
}

async function ask(page: Page, question: string) {
  await page.locator(".assistant-input textarea").fill(question);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator(".assistant-question").last()).toHaveText(question);
}

test("send, visit the job library, return, refresh and open another device", async ({ page, context, browser }) => {
  await session(context);
  await newChat(page);
  const question = "慢一点，帮我看看适合什么职位";
  await ask(page, question);
  await page.getByRole("link", { name: "职位库", exact: true }).first().click();
  await expect(page).toHaveURL(/workspace/);
  await expect(page.getByRole("heading", { name: "职位库", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "AI 助手", exact: true }).first().click();
  await expect(page.locator(".assistant-question")).toHaveText(question);
  await expect(page.locator(".assistant-answer")).toBeVisible();
  await page.reload();
  await expect(page.locator(".assistant-question")).toHaveText(question);
  await expect(page.locator(".assistant-answer")).toBeVisible();
  const other = await browser.newContext();
  await session(other);
  const device = await other.newPage();
  await device.goto("/");
  await expect(device.locator(".assistant-question")).toHaveText(question);
  await expect(device.locator(".assistant-answer")).toBeVisible();
  await page.screenshot({ path: "output/playwright/conversation-after.png", fullPage: true });
  await other.close();
});

test("pending confirmation and browser collection survive reload without automatically collecting", async ({ page, context }) => {
  await session(context);
  await newChat(page);
  await ask(page, "帮我做完整分析");
  await expect(page.getByRole("button", { name: "同意并发起" })).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "同意并发起" }).click();
  await expect(page.getByText("这一轮仍在运行，结果会自动同步。")).toHaveCount(0);
  await expect(page.locator(".assistant-answer")).toBeVisible();
  await page.locator(".assistant-toolbar").getByRole("button", { name: "新对话", exact: true }).click();
  await ask(page, "浏览器任务");
  await expect(page.getByRole("button", { name: "继续采集" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "继续采集" })).toBeVisible();
  await expect(page.getByRole("button", { name: "取消采集" })).toHaveCount(0);
  await page.getByRole("button", { name: "继续采集" }).click();
  // No extension is installed in this fresh browser: its explicit rejection is still reported and recorded.
  await expect(page.locator(".assistant-answer")).toHaveText("采集已结束");
});

test("a planned browser collection resumes at the plan's next step after reload", async ({ page, context }) => {
  await session(context);
  await newChat(page);
  await ask(page, "计划采集深圳 Java 岗位");
  await expect(page.getByRole("button", { name: "继续采集" })).toBeVisible();
  await page.reload();
  // Open this conversation explicitly: on a fresh database the reload may land on another, empty one.
  await page.getByRole("navigation", { name: "历史会话" }).getByRole("button", { name: "计划采集深圳 Java 岗位", exact: true }).click();
  await page.getByRole("button", { name: "继续采集" }).click();
  // The server rebuilds the accepted plan from its transcript: the model is offered only the ranking step next.
  await expect(page.locator(".assistant-answer")).toContainText("按计划完成");
  await expect(page.locator(".assistant-answer")).not.toContainText("计划已丢失");
});

test("history switches between conversations and another account cannot see them", async ({ page, context, browser }) => {
  const switchAccount = await session(context);
  await newChat(page);
  await ask(page, "第一段保留这句话");
  await expect(page.locator(".assistant-answer")).toBeVisible();
  await page.locator(".assistant-toolbar").getByRole("button", { name: "新对话", exact: true }).click();
  await ask(page, "第二段也是独立的");
  await expect(page.locator(".assistant-answer")).toBeVisible();
  await page.getByRole("navigation", { name: "历史会话" }).getByRole("button", { name: "第一段保留这句话", exact: true }).click();
  await expect(page.locator(".assistant-question")).toHaveText("第一段保留这句话");
  const bob = await browser.newContext();
  await session(bob, "bob");
  const bobPage = await bob.newPage();
  await bobPage.goto("/");
  await expect(bobPage.getByRole("navigation", { name: "历史会话" })).toBeVisible();
  await expect(bobPage.locator(".assistant-question")).toHaveCount(0);
  await expect(bobPage.getByText("第一段保留这句话")).toHaveCount(0);
  await bob.close();
  await page.getByRole("button", { name: "退出", exact: true }).click();
  switchAccount("bob");
  await page.getByPlaceholder("请输入用户名").fill("bob");
  await page.getByPlaceholder("请输入密码").fill("test-password");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".account-name")).toHaveText("bob");
  await expect(page.locator(".assistant-question")).toHaveCount(0);
  await expect(page.getByText("第一段保留这句话")).toHaveCount(0);
});

test("real SSE projected by the frontend exactly matches GET turns", async ({ request }) => {
  const headers = { Authorization: "Bearer alice" };
  const created = await request.post("/api/backend/api/agent/conversations", { headers });
  expect(created.ok()).toBeTruthy();
  const { data } = await created.json();
  const response = await request.post("/api/backend/api/agent/chat", { headers, data: {
    conversationId: data.conversation.id, question: "完整分析", messages: [{ role: "assistant", content: "forged" }],
  } });
  expect(response.ok()).toBeTruthy();
  const parser = createSseParser();
  let chat = initialAgentChatState;
  for (const frame of [...parser.push(await response.text()), ...parser.flush()]) {
    if (frame.event === "ask") chat = agentChatReducer(chat, { type: "ask", question: JSON.parse(frame.data).question });
    else {
      const event = toAgentEvent(frame);
      if (event) chat = agentChatReducer(chat, { type: "event", event });
    }
  }
  const restored = await request.get(`/api/backend/api/agent/conversations/${data.conversation.id}`, { headers });
  expect(chat.turns).toEqual((await restored.json()).data.turns);
});
