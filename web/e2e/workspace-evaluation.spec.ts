import { test, expect } from "@playwright/test";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { randomUUID } from "node:crypto";
import path from "node:path";

test("HTTPS app pushes runs, evaluates automatically or manually, compares and deletes", async ({
  page,
}) => {
  test.setTimeout(180000);
  page.setDefaultTimeout(15000);
  const admin = process.env.WEB_ACCEPTANCE_ADMIN;
  const password = process.env.WEB_ACCEPTANCE_PASSWORD;
  const python = process.env.WEB_ACCEPTANCE_PYTHON;
  if (!admin || !password || !python)
    throw new Error(
      "Run the owned Compose e2e fixture for HTTPS browser acceptance",
    );
  const email = `browser-${randomUUID()}@example.test`;
  const temporary = randomUUID() + "-Temporary-1";
  const next = randomUUID() + "-Changed-1";
  await page.goto("/login");
  await page.getByLabel("邮箱", { exact: true }).fill(admin);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("link", { name: "管理后台", exact: true }).click();
  await page.getByLabel("新账户邮箱").fill(email);
  await page.getByLabel("临时密码", { exact: true }).fill(temporary);
  await page.getByRole("button", { name: "创建账户", exact: true }).click();
  await expect(page.getByText("账户已创建。", { exact: true })).toBeVisible();
  await page
    .getByRole("combobox", { name: "用户工作空间", exact: true })
    .selectOption({ label: email });
  await page.getByLabel("调整金额（元）", { exact: true }).fill("1");
  await page
    .getByLabel("调整原因", { exact: true })
    .fill("Browser acceptance budget");
  await page.getByRole("button", { name: "提交额度调整", exact: true }).click();
  await expect(
    page.getByText("调整后可用：¥6.00", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page.getByLabel("邮箱", { exact: true }).fill(email);
  await page.getByLabel("密码", { exact: true }).fill(temporary);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByLabel("新密码", { exact: true }).fill(next);
  await page.getByLabel("确认新密码").fill(next);
  await page.getByRole("button", { name: "保存新密码", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "管理后台", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "评分规则集", exact: true }).click();
  await page.getByText("从平台示例开始", { exact: true }).click();
  const copied = page.waitForResponse(
    (response) =>
      response.url().endsWith("/templates/starter-rules/copy") &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "复制规则评分示例", exact: true })
    .click();
  const suite = ((await (await copied).json()) as { id: string }).id;
  await page.getByRole("link", { name: "SDK 接入", exact: true }).click();
  await page.getByLabel("项目名称").fill("Browser orders");
  await page.getByRole("button", { name: "创建上报项目" }).click();
  const key = page.getByLabel("本次上报密钥");
  await expect(key).not.toBeEmpty();
  const secret = await key.inputValue();
  await page.getByRole("button", { name: "我已保存" }).click();
  const auto = page.getByRole("switch", { name: "自动评测" });
  await expect(auto).toBeChecked();
  async function push(version: string, amount: number) {
    const script = [
      "import os",
      "from agent_eval import PushClient, span",
      "with PushClient(os.environ['WEB_BASE_URL'], os.environ['UPLOAD_KEY'], ca_cert=os.environ.get('WEB_ACCEPTANCE_CA')) as client:",
      "    run = client.start_run(namespace='regression', name='Orders ' + os.environ['AGENT_VERSION'], agent_name='Browser order agent', agent_version=os.environ['AGENT_VERSION'], dataset_version='orders-v1', suite_version_id=os.environ['SUITE_VERSION'])",
      "    with run.case(id='A', instruction='Query order A', input={'order_id':'A'}, expected={'amount':100}) as case:",
      "        with span('tool','query_order', {'order_id':'A'}) as tool:",
      "            tool.output = {'amount':100}",
      "        case.output = {'amount':int(os.environ['ORDER_AMOUNT'])}",
      "    print(run.submit())",
    ].join("\n");
    const { stdout } = await promisify(execFile)(python!, ["-c", script], {
      cwd: path.resolve(".."),
      env: {
        ...process.env,
        PYTHONPATH: path.resolve("../sdks/python/src"),
        UPLOAD_KEY: secret,
        SUITE_VERSION: suite,
        AGENT_VERSION: version,
        ORDER_AMOUNT: String(amount),
      },
      windowsHide: true,
    });
    return stdout.trim();
  }
  async function waitScored(id: string, passed: number) {
    await expect
      .poll(
        async () => {
          const result = await page.request.get(
            "/api/v1/reports/experiments/" + id,
          );
          if (!result.ok()) return null;
          const data = await result.json();
          return [data.determinable_attempts, data.passed_attempts];
        },
        { timeout: 70000, intervals: [1000] },
      )
      .toEqual([1, passed]);
  }
  const first = await push("v1", 120);
  await waitScored(first, 0);
  await auto.click();
  await expect(auto).not.toBeChecked();
  const second = await push("v2", 100);
  await page.goto("/workspace/reports/" + second);
  await expect(page.getByText(/已接收上报，待评测/)).toBeVisible();
  const before = await page.request.get(
    "/api/v1/reports/experiments/" + second,
  );
  expect((await before.json()).determinable_attempts).toBe(0);
  await page.getByRole("button", { name: "开始评测", exact: true }).click();
  await page.getByRole("button", { name: "确认开始评测", exact: true }).click();
  await waitScored(second, 1);
  await page.reload();
  await expect(
    page
      .getByRole("region", { name: "Agent 评分趋势" })
      .getByText(/100 分/)
      .last(),
  ).toBeVisible();
  await page.getByLabel("对比最近 100 次实验").selectOption(first);
  await page
    .getByRole("link", { name: "查看可比性与差异", exact: true })
    .click();
  await expect(page.getByText("可以直接比较", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "实验", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: "Orders v1" })
    .getByRole("button", { name: "删除实验", exact: true })
    .click();
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await page.getByRole("link", { name: "查看删除进度", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "实验删除进度", exact: true }),
  ).toBeVisible();
  expect(
    (await page.request.get("/api/v1/reports/experiments/" + first)).status(),
  ).toBe(404);
});
