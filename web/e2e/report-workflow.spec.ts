import { test, expect } from "@playwright/test";
import path from "node:path";
import { readFile } from "node:fs/promises";

test("diagnoses failures, downloads a regression case and compares tasks", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const tasks = [
    {
      instruction: "查询订单金额",
      input: { order_id: "A" },
      expected: { amount: 100 },
      timeout_seconds: 30,
    },
    {
      instruction: "查询不存在的订单",
      input: { order_id: "missing" },
      expected: { found: false },
    },
  ];
  const criterion = {
    criterion_id: "amount",
    status: "SCORED",
    score: 0,
    passed: false,
    reason: "金额不正确：期望100元，实际120元。",
    evidence_refs: ["output", "event:tool-event"],
    evaluator_version: "rule",
  };
  const trials = (side: string) =>
    [0, 1].map((index) => ({
      trial_id: `${side}-${index}`,
      case_index: index,
      attempt: 1,
      execution_status: "SUCCEEDED",
      run_id: `run-${side}-${index}`,
      snapshot_id: `snapshot-${side}-${index}`,
      evaluation_state: "COMPLETED",
      verdict: (side === "left" ? index === 0 : index === 1) ? "FAIL" : "PASS",
      dimensions: {},
      criteria: (side === "left" ? index === 0 : index === 1)
        ? [criterion]
        : [],
      reported_duration_ms: 50,
      suite_version_id: "suite",
      evidence_wait_reason: null,
    }));
  const summary = (side: string) => ({
    id: side,
    agent_id: "agent-" + side,
    agent_name: "订单助手",
    agent_version: side === "left" ? "v1" : "v2",
    dataset_name: "订单回归测试",
    dataset_version_id: "dataset",
    dataset_version_number: 1,
    suite_name: "订单综合评分",
    suite_version_id: "suite",
    suite_version_number: 1,
    case_count: 2,
    repeat_count: 1,
    task_count: 2,
    created_at:
      side === "left" ? "2026-10-30T00:00:00Z" : "2026-10-31T00:00:00Z",
  });
  const report = (side: string) => ({
    experiment_id: side,
    agent_version: side === "left" ? "v1" : "v2",
    dataset_version_id: "dataset",
    suite_version_id: "suite",
    evaluation_suite_version_ids: ["suite"],
    experiment_status: "COMPLETED",
    planned_attempts: 2,
    started_attempts: 2,
    finished_attempts: 2,
    determinable_attempts: 2,
    pending_attempts: 0,
    undetermined_attempts: 0,
    cancelled_attempts: 0,
    pass_rate_planned: { numerator: 1, denominator: 2, value: 0.5 },
    pass_rate_determinable: { numerator: 1, denominator: 2, value: 0.5 },
    dimensions: {
      outcome: {
        score: side === "left" ? 0.5 : 0.75,
        scored_weight: 2,
        total_weight: 2,
        coverage: 1,
        scored_criteria: 2,
        planned_criteria: 2,
        partial: false,
      },
    },
    efficiency: {
      p50_duration_ms: 50,
      p95_duration_ms: 50,
      source: "worker_reported",
      observed_durations: 2,
    },
    execution_counts: { SUCCEEDED: 2 },
  });
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname.replace("/api/v1", "");
    let value: unknown = [];
    if (p === "/auth/me")
      value = {
        user_id: "user",
        workspace_id: "workspace",
        email: "demo@example.com",
        admin: false,
        must_change_password: false,
      };
    else if (p === "/auth/csrf")
      value = { header_name: "X-CSRF-TOKEN", token: "test-only" };
    else if (p === "/experiment-summaries")
      value = [summary("left"), summary("right")];
    else if (p.startsWith("/experiment-summaries/"))
      value = summary(p.split("/").pop()!);
    else if (p === "/reports/comparison")
      value = {
        left: report(url.searchParams.get("left") ?? "left"),
        right: report(url.searchParams.get("right") ?? "right"),
        directly_comparable: true,
        reasons: [],
        dimension_deltas: {},
        left_provenance: {},
        right_provenance: {},
      };
    else if (p === "/reports/comparison/trials")
      value = {
        left: {
          items: trials(url.searchParams.get("left")!),
          next_cursor: null,
        },
        right: {
          items: trials(url.searchParams.get("right")!),
          next_cursor: null,
        },
      };
    else if (p.match(/^\/reports\/experiments\/[^/]+\/trials$/))
      value = { items: trials(p.split("/")[3]!), next_cursor: null };
    else if (p.startsWith("/reports/experiments/"))
      value = report(p.split("/")[3]!);
    else if (p === "/catalog/versions/dataset")
      value = { id: "dataset", content: { cases: tasks } };
    else if (p.startsWith("/evaluation-runs/")) {
      const passed = p.endsWith("right-0");
      value = {
        snapshot_id: p.split("/").pop()!.replace("run-", "snapshot-"),
        status: "COMPLETED",
        purpose: "INITIAL",
        wait_reasons: [],
        criteria: [
          {
            result_id: "result",
            result: passed
              ? {
                  ...criterion,
                  score: 1,
                  passed: true,
                  reason: "金额与工具结果一致。",
                }
              : criterion,
          },
        ],
      };
    } else if (p.startsWith("/evidence/snapshots/"))
      value = {
        content_hash: "fixture-hash",
        completeness: "COMPLETE",
        manifest: {
          task: tasks[0],
          completion: { output: { amount: p.includes("left") ? 120 : 100 } },
          events: [
            {
              event_id: "tool-event",
              span_id: "tool-span",
              output: { amount: 100 },
            },
          ],
          issues: [],
        },
      };
    else if (p.startsWith("/traces/"))
      value = {
        spans: [
          {
            span_id: "tool-span",
            parent_span_id: null,
            kind: "tool",
            name: "query_order",
            status: "SUCCEEDED",
            duration_ms: 50,
            input: { order_id: "A" },
            output: { amount: 100 },
          },
        ],
        next_cursor: null,
      };
    if (!["GET", "HEAD"].includes(req.method()))
      throw new Error("Unexpected mutation: " + p);
    await route.fulfill({ json: value });
  });
  await page.goto("/workspace/reports/left");
  await expect(
    page.getByRole("region", { name: "Agent 评分趋势" }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "评测看板", exact: true }).click();
  const trend = page.getByRole("region", { name: "Agent 评分趋势" });
  const chart = trend.getByRole("img", { name: "历次结果质量分平滑面积图" });
  await expect(chart).toBeVisible();
  await expect(chart.locator("path[fill^='url']")).toHaveAttribute(
    "d",
    / C .+ Z$/,
  );
  await expect(chart.locator("path[fill='none']")).toHaveAttribute("d", / C /);
  await expect(trend.getByText(/75 分/).last()).toBeVisible();
  await expect(trend.getByRole("link", { name: /^v2/ })).toHaveAttribute(
    "href",
    "/workspace/reports/right",
  );

  await trend.getByRole("link", { name: /^v1/ }).click();
  await expect(
    page.getByRole("heading", { name: "订单助手 的评测报告" }),
  ).toBeVisible();
  await expect(page.getByText(criterion.reason).first()).toBeVisible();
  await page.getByRole("button", { name: "查询订单金额 · 第 1 次" }).click();
  const answers = page.getByRole("region", { name: "任务与答案" });
  await expect(answers.getByText("120", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看关联执行步骤" }).click();
  await expect(page.locator('[data-highlighted="true"]')).toBeVisible();
  const download = page.waitForEvent("download");
  await answers.getByRole("button", { name: "下载回归样例" }).click();
  const downloaded = await download;
  const regression = JSON.parse(
    await readFile((await downloaded.path())!, "utf8"),
  );
  expect(regression.expected).toEqual({ amount: 100 });
  expect(regression.source.trial_id).toBe("left-0");
  expect(regression).not.toHaveProperty("output");
  await page.goto("/workspace/comparisons?left=left&right=right");
  const comparison = page.getByRole("region", { name: "逐任务对比" });
  await expect(comparison.getByText("改善", { exact: true })).toBeVisible();
  await expect(comparison.getByText("退步", { exact: true })).toBeVisible();
  await comparison
    .getByRole("button", { name: "并排查看答案与证据" })
    .first()
    .click();
  await expect(
    page.getByRole("region", { name: "双方任务证据" }),
  ).toBeVisible();
  await expect(page.locator(".trace-tree")).toHaveCount(2);
  expect(
    await page.evaluate(() => {
      const ids = [...document.querySelectorAll("[id]")].map((node) => node.id);
      return new Set(ids).size === ids.length;
    }),
  ).toBe(true);
  await page.screenshot({
    path: path.resolve("../.tools/report-workflow-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: path.resolve("../.tools/report-workflow-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
});
