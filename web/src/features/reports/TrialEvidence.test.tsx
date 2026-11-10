import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import type { Schema } from "../../api/types";
import { TrialEvidence } from "./TrialEvidence";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
const trial: Schema<"TrialReport"> = {
  trial_id: "trial",
  case_index: 0,
  attempt: 1,
  execution_status: "SUCCEEDED",
  run_id: "run",
  snapshot_id: "snapshot",
  evaluation_state: "COMPLETED",
  verdict: "FAIL",
  criteria: [],
  dimensions: {},
  reported_duration_ms: 1,
  suite_version_id: "suite",
  evidence_wait_reason: null,
};
it("shows input, reference, actual answer and linked step before offering a failed task for local regression", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      const path = new URL(req.url).pathname;
      let body: unknown = [];
      if (path.endsWith("evaluation-runs/run"))
        body = {
          snapshot_id: "snapshot",
          status: "COMPLETED",
          purpose: "INITIAL",
          wait_reasons: [],
          criteria: [
            {
              result_id: "result",
              result: {
                criterion_id: "amount",
                status: "SCORED",
                score: 0,
                passed: false,
                reason: "金额与工具结果矛盾",
                evaluator_version: "rule",
                evidence_refs: ["output", "event:tool-event"],
              },
            },
          ],
        };
      if (path.endsWith("evidence/snapshots/snapshot"))
        body = {
          content_hash: "hash",
          completeness: "COMPLETE",
          manifest: {
            task: {
              instruction: "查询订单金额",
              input: { order_id: "A" },
              expected: { amount: 100 },
            },
            completion: { output: { amount: 120 } },
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
      if (path.endsWith("traces/trial"))
        body = {
          spans: [
            {
              span_id: "tool-span",
              parent_span_id: null,
              name: "query_order",
              kind: "tool",
              status: "SUCCEEDED",
              input: { order_id: "A" },
              output: { amount: 100 },
              duration_ms: 1,
            },
          ],
          next_cursor: null,
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
        <TrialEvidence trial={trial} experimentId="exp" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const content = within(
    await screen.findByRole("region", { name: "任务与答案" }),
  );
  expect(content.getByText("查询订单金额")).toBeVisible();
  expect(content.getByText("100")).toBeVisible();
  expect(content.getByText("120")).toBeVisible();
  expect(content.getByRole("button", { name: "下载回归样例" })).toBeVisible();
  expect(await screen.findByText("金额与工具结果矛盾")).toBeVisible();
  await userEvent
    .setup()
    .click(screen.getByRole("button", { name: "查看关联执行步骤" }));
  expect(
    await screen.findByRole("heading", {
      name: "固定快照中的证据：event:tool-event",
    }),
  ).toBeVisible();
  expect(
    document.querySelector('[data-span="tool-span"] > .trace-node'),
  ).toHaveAttribute("data-highlighted", "true");
});
