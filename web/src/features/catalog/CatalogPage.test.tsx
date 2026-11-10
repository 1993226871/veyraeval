import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { CatalogPage } from "./CatalogPage";

vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));

it("copies a mixed starter only after selecting a model and never starts a paid experiment", async () => {
  const posts: { path: string; body: unknown }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request =
        input instanceof Request ? input : new Request(input, init);
      const path = new URL(request.url).pathname;
      let body: unknown = [];
      if (path.endsWith("/models"))
        body = [{ id: "model", name: "本人的 Judge", price: { id: "price" } }];
      if (path.endsWith("/auth/csrf"))
        body = { header_name: "X-CSRF-TOKEN", token: "fixture" };
      if (request.method === "POST") {
        posts.push({ path, body: await request.json() });
        body = {
          resource_id: "suite",
        };
      }
      if (path.endsWith("/catalog/suite"))
        body = {
          id: "suite",
          kind: "SUITE",
          name: "订单综合评分",
          revision: 1,
          archived: false,
          content: { entries: [] },
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
        <CatalogPage kind="SUITE" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByText("从平台示例开始"));
  expect(
    screen.getByRole("button", { name: "复制规则 + LLM 示例" }),
  ).toBeDisabled();
  await screen.findByRole("option", { name: "本人的 Judge" });
  await user.selectOptions(screen.getByLabelText("示例评审模型"), "model");
  await user.click(screen.getByRole("button", { name: "复制规则 + LLM 示例" }));
  await waitFor(() =>
    expect(posts).toEqual([
      {
        path: "/api/v1/templates/starter-rules/copy",
        body: { model_profile_id: "model", price_version_id: "price" },
      },
    ]),
  );
});
