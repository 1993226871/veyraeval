import { test, expect } from "@playwright/test";
import path from "node:path";

test("explains real SDK results and exports linked manual reviews", async ({
  page,
}) => {
  const email = process.env.WEB_REPORT_EMAIL;
  const password = process.env.WEB_REPORT_PASSWORD;
  const ids = process.env.WEB_REPORT_IDS?.split(",");
  if (!email || !password || ids?.length !== 2)
    throw new Error(
      "Use scripts/verify_phase_b.py --web-smoke with disposable SDK fixtures.",
    );
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/login");
  await page.getByLabel("邮箱", { exact: true }).fill(email);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "实验", exact: true }),
  ).toBeVisible();
  await page.goto("/workspace/reports/" + ids[0]);
  await expect(
    page.getByRole("heading", { name: /的评测报告$/ }),
  ).toBeVisible();
  await expect(
    page.locator(".metrics").getByText("1 / 1", { exact: true }),
  ).toHaveCount(2);
  await page.getByRole("button", { name: / · 第 1 次$/ }).click();
  await expect(page.getByRole("heading", { name: "执行轨迹" })).toBeVisible();
  await expect(page.getByText("证据完整", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看实际回答", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "固定快照中的证据：output" }),
  ).toBeVisible();
  await page.getByText("人工复核（保留原始自动评分）", { exact: true }).click();
  await page.getByLabel("复核分数（0～1）").fill("0.5");
  await page.getByLabel("复核依据").fill("Browser calibration fixture");
  await page.getByRole("button", { name: "追加复核" }).click();
  await expect(
    page
      .locator(".review-list")
      .getByText("Browser calibration fixture", { exact: true }),
  ).toBeVisible();
  const downloadEvent = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出 JSON 与证据" }).click();
  const download = await downloadEvent;
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
  const exported = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  expect(exported.report.pass_rate_planned.denominator).toBe(1);
  expect(exported.reviews[0].score).toBe(0.5);
  expect(
    exported.result_identities.some(
      (item: { result_id: string }) =>
        item.result_id === exported.reviews[0].result_id,
    ),
  ).toBe(true);
  expect(exported.evidence[0].manifest.completion.output.amount).toBe(100);
  await page.screenshot({
    path: path.resolve("../.tools/web-report-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "评测看板", exact: true }).click();
  await page.getByLabel("第一轮").selectOption(ids[0]!);
  await page.getByLabel("第二轮").selectOption(ids[1]!);
  await page.getByRole("link", { name: "查看可比性与差异" }).click();
  await expect(page.getByText("不能直接比较", { exact: true })).toBeVisible();
  await expect(page.getByText("任务集版本不同", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "不可直接比较", exact: true }),
  ).toHaveCount(2);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: path.resolve("../.tools/web-comparison-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("link", { name: "个人额度", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "执行与存储预算" }),
  ).toBeVisible();
  await expect(
    page.getByText(/每个 Python 作业按 30 秒上界预留/),
  ).toBeVisible();
  await page.screenshot({
    path: path.resolve("../.tools/web-resource-mobile.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("link", { name: "管理后台", exact: true }).click();
  await page.getByRole("button", { name: "资源上限", exact: true }).click();
  await page.getByLabel("选择用户空间").selectOption({ label: email });
  await expect(page.getByLabel("累计评测作业", { exact: true })).toHaveValue(
    "50000",
  );
  await page.getByLabel("累计评测作业", { exact: true }).fill("50001");
  await page
    .getByLabel("调整说明", { exact: true })
    .fill("Browser resource fixture");
  await page.getByRole("button", { name: "保存资源上限" }).click();
  await expect(page.getByRole("status")).toHaveText(
    "资源限制已更新，等待任务可继续调度。",
  );
  await page.screenshot({
    path: path.resolve("../.tools/web-admin-resources.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "操作记录", exact: true }).click();
  await expect(page.getByText("调整资源限制", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "实验", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: ids[0]!.slice(0, 8) })
    .getByRole("button", { name: "删除实验", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("无法访问");
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await page.getByRole("link", { name: "查看删除进度" }).click();
  await expect(
    page.getByRole("heading", { name: "实验删除进度" }),
  ).toBeVisible();
  await expect(page.getByText("正在清理", { exact: true })).toBeVisible();
  expect(
    (await page.request.get("/api/v1/reports/experiments/" + ids[0])).status(),
  ).toBe(404);
  await page.screenshot({
    path: path.resolve("../.tools/web-deletion-status.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "操作记录", exact: true }).click();
  await expect(page.getByText("请求删除实验", { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
