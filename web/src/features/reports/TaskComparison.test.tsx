import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { TaskComparison } from "./TaskComparison";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
const row = (id: string, index: number, verdict: string) => ({
  trial_id: id,
  case_index: index,
  attempt: 1,
  execution_status: "SUCCEEDED",
  run_id: null,
  snapshot_id: null,
  evaluation_state: "COMPLETED",
  verdict,
  dimensions: {},
  criteria:
    verdict === "FAIL"
      ? [
          {
            criterion_id: "amount",
            status: "SCORED",
            passed: false,
            score: 0,
            reason: "金额与参考答案不同",
            evidence_refs: ["output"],
          },
        ]
      : [],
  suite_version_id: "suite",
});
function setup(comparable = true) {
  const paths: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      const url = new URL(req.url);
      paths.push(url.pathname + url.search);
      let body: unknown = [];
      if (url.pathname.endsWith("/experiment-summaries/left"))
        body = { agent_name: "旧订单助手", agent_version: "v1" };
      if (url.pathname.endsWith("/experiment-summaries/right"))
        body = { agent_name: "新订单助手", agent_version: "v2" };
      if (url.pathname.endsWith("/catalog/versions/dataset"))
        body = {
          content: {
            cases: [
              { instruction: "查询订单金额" },
              { instruction: "查不存在的订单" },
            ],
          },
        };
      if (url.pathname.endsWith("/comparison/trials"))
        body = url.searchParams.has("rightCursor")
          ? {
              left: { items: [], next_cursor: null },
              right: { items: [row("R1", 1, "FAIL")], next_cursor: null },
            }
          : {
              left: {
                items: [row("L0", 0, "FAIL"), row("L1", 1, "PASS")],
                next_cursor: null,
              },
              right: { items: [row("R0", 0, "PASS")], next_cursor: "next" },
            };
      return new Response(JSON.stringify(body), {
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter>
        <TaskComparison
          leftId="left"
          rightId="right"
          datasetVersionId="dataset"
          comparable={comparable}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return paths;
}
it("shows readable task pairs, diagnoses failures and loads remaining attempts without declaring absent rows regressions", async () => {
  const paths = setup();
  const user = userEvent.setup();
  await screen.findByText("查询订单金额");
  expect(await screen.findByText("改善")).toBeVisible();
  expect(screen.getByText("尚未配对")).toBeVisible();
  expect(screen.queryByText("退步")).not.toBeInTheDocument();
  expect(screen.getByText("金额与参考答案不同")).toBeVisible();
  expect(screen.getByText("旧订单助手 · v1")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "加载更多任务" }));
  expect(await screen.findByText("退步")).toBeVisible();
  const rows = screen.getAllByRole("row");
  expect(within(rows[2]!).getByText("查不存在的订单")).toBeVisible();
  expect(paths.filter((path) => path.includes("/comparison/trials"))).toEqual([
    "/api/v1/reports/comparison/trials?left=left&right=right",
    "/api/v1/reports/comparison/trials?left=left&right=right&rightCursor=next",
  ]);
  expect(paths.some((path) => /\/experiments\/[^/]+\/trials/.test(path))).toBe(
    false,
  );
  expect(paths.some((path) => path.includes("/evidence/"))).toBe(false);
});
it("never claims improvement when the comparison configuration is incompatible", async () => {
  setup(false);
  await screen.findByText("查询订单金额");
  expect(await screen.findByText("不可直接比较")).toBeVisible();
  expect(screen.queryByText("改善")).not.toBeInTheDocument();
});
