import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { EvaluationDashboard } from "./EvaluationDashboard";

vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));

afterEach(() => vi.unstubAllGlobals());

it("filters project history and offers only matching runs for comparison", async () => {
  const common = {
    agent_id: "agent",
    agent_name: "订单助手",
    dataset_version_id: "dataset",
    dataset_name: "订单题库",
    dataset_version_number: 1,
    suite_version_id: "suite",
    suite_name: "订单评分",
    suite_version_number: 1,
    created_at: "2026-11-10T00:00:00Z",
    agent_version: "v1",
  };
  let activeReads = 0;
  let overlappingReads = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const pathname = new URL(request.url).pathname;
      if (pathname.endsWith("/reports/comparison")) {
        activeReads += 1;
        if (activeReads > 1) overlappingReads += 1;
        await new Promise((resolve) => setTimeout(resolve, 15));
        activeReads -= 1;
        return Response.json({
          directly_comparable: true,
          reasons: [],
          right: { dimensions: { outcome: { score: 0.5 } } },
        });
      }
      const body = pathname.endsWith("experiment-summaries")
        ? [
            {
              ...common,
              id: "first",
              sdk_project_id: "a",
              sdk_project: "项目 A",
              run_name: "运行 A",
            },
            {
              ...common,
              id: "second",
              sdk_project_id: "b",
              sdk_project: "项目 B",
              run_name: "运行 B",
            },
            ...["2026-11-08", "2026-11-09"].map((day) => ({
              ...common,
              id: day,
              sdk_project_id: "b",
              sdk_project: "项目 B",
              created_at: day + "T00:00:00Z",
            })),
          ]
        : {
            pending_attempts: 0,
            finished_attempts: 1,
            planned_attempts: 1,
            dimensions: { outcome: { score: 1 } },
          };
      return Response.json(body);
    }),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <EvaluationDashboard />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByRole("option", { name: "项目 B" });
  const trendSelect = within(screen.getByLabelText("选择趋势"));
  expect(
    trendSelect.getByRole("option", { name: "订单助手 · 项目 A" }),
  ).toBeVisible();
  expect(
    trendSelect.getByRole("option", { name: "订单助手 · 项目 B" }),
  ).toBeVisible();
  await userEvent.selectOptions(screen.getByLabelText("项目"), "b");
  expect(trendSelect.getByRole("option", { name: "订单助手" })).toBeVisible();
  const choices = within(screen.getByLabelText("第一轮"));
  expect(choices.getByRole("option", { name: /运行 B/ })).toBeVisible();
  expect(choices.queryByRole("option", { name: /运行 A/ })).toBeNull();
  const trend = within(screen.getByRole("region", { name: "Agent 评分趋势" }));
  await waitFor(() => expect(trend.getAllByText(/50 分/)).toHaveLength(2));
  expect(overlappingReads).toBe(0);
});
