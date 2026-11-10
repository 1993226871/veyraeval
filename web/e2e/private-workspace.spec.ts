import { test, expect } from "@playwright/test";
import path from "node:path";

test("real session, CSRF, immutable rules, and one-time upload credential", async ({
  page,
}) => {
  const email = process.env.WEB_TEST_EMAIL;
  const password = process.env.WEB_TEST_PASSWORD;
  const next = process.env.WEB_TEST_NEW_PASSWORD;
  if (!email || !password || !next)
    throw new Error(
      "Run scripts/verify_phase_b.py --web-smoke to create disposable test services and credentials.",
    );
  const failures: string[] = [];
  page.on("pageerror", (error) => failures.push(error.message));
  const requests: { method: string; csrf: boolean }[] = [];
  page.on("request", (request) => {
    if (
      new URL(request.url()).pathname.startsWith("/api/") &&
      !["GET", "HEAD"].includes(request.method())
    )
      requests.push({
        method: request.method(),
        csrf: !!request.headers()["x-csrf-token"],
      });
  });
  await page.goto("/workspace/rule-sets");
  await page.getByLabel("邮箱", { exact: true }).fill(email);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "设置新密码" })).toBeVisible();
  await page.getByLabel("新密码", { exact: true }).fill(next);
  await page.getByLabel("确认新密码").fill(next);
  await page.getByRole("button", { name: "保存新密码" }).click();
  await expect(
    page.getByRole("heading", { name: "实验", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "管理后台" })).toHaveCount(0);
  await page.getByRole("link", { name: "评分规则集", exact: true }).click();
  await page.getByText("从平台示例开始", { exact: true }).click();
  await page.getByRole("button", { name: "复制规则评分示例" }).click();
  const versions = page.getByRole("combobox", { name: "查看版本" });
  await expect(versions.getByRole("option")).toHaveCount(2);
  await versions.selectOption({ label: "v1" });
  await expect(page.getByLabel("评分规则 JSON")).toBeDisabled();
  await expect(page.getByLabel("评分规则版本 ID")).not.toBeEmpty();
  await page.getByRole("link", { name: "SDK 接入" }).click();
  await page.getByLabel("项目名称").fill("浏览器上报项目");
  await page.getByRole("button", { name: "创建上报项目" }).click();
  const secret = page.getByLabel("本次上报密钥");
  await expect(secret).not.toBeEmpty();
  await page.getByRole("button", { name: "我已保存" }).click();
  await expect(secret).not.toBeVisible();
  await expect(page.getByRole("switch", { name: "自动评测" })).toBeChecked();
  await page.getByRole("switch", { name: "自动评测" }).click();
  await expect(
    page.getByRole("switch", { name: "自动评测" }),
  ).not.toBeChecked();
  await page.reload();
  await expect(
    page.getByRole("switch", { name: "自动评测" }),
  ).not.toBeChecked();
  await expect(secret).not.toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: path.resolve("../.tools/web-sdk-project-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(() => ({
      local: localStorage.length,
      session: sessionStorage.length,
    })),
  ).toEqual({ local: 0, session: 0 });
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(
    page.getByRole("heading", { name: "登录 VeyraEval" }),
  ).toBeVisible();
  expect(requests.length).toBeGreaterThan(5);
  expect(requests.every((request) => request.csrf)).toBe(true);
  expect(failures).toEqual([]);
});
