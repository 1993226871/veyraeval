import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CatalogEditor } from "./CatalogEditor";

const draft = {
  id: "resource-1",
  kind: "DATASET" as const,
  name: "订单任务集",
  revision: 2,
  archived: false,
  content: {
    cases: [
      {
        instruction: "查询订单",
        input: { order_id: "A" },
        expected: { amount: 100 },
      },
    ],
  },
};
function props() {
  return {
    resource: draft,
    versions: [
      { id: "v1", number: 1, draft_revision: 1, content_hash: "hash" },
    ],
    loadVersion: vi
      .fn<(id: string) => Promise<import("../../api/types").PublishedVersion>>()
      .mockResolvedValue({
        id: "v1",
        resource_id: draft.id,
        kind: "DATASET",
        name: draft.name,
        number: 1,
        content_hash: "hash",
        content: draft.content,
      }),
    onSave: vi
      .fn<
        (name: string, content: Record<string, unknown>) => Promise<unknown>
      >()
      .mockResolvedValue(undefined),
    onPublish: vi.fn(),
    onArchive: vi.fn(),
  };
}

it("edits tasks and typed inputs using fields without a JSON editor", async () => {
  const user = userEvent.setup();
  const handlers = props();
  render(<CatalogEditor {...handlers} />);
  expect(screen.queryByLabelText("草稿内容")).not.toBeInTheDocument();
  await user.clear(await screen.findByLabelText("任务要求 1"));
  await user.type(screen.getByLabelText("任务要求 1"), "查询订单金额");
  await user.clear(screen.getByLabelText("任务 1 输入.order_id 值"));
  await user.type(screen.getByLabelText("任务 1 输入.order_id 值"), "B");
  await user.click(screen.getByRole("button", { name: "新增任务" }));
  await user.type(screen.getByLabelText("任务要求 2"), "查询不存在订单");
  await user.click(screen.getByRole("button", { name: "保存草稿" }));
  await waitFor(() => expect(handlers.onSave).toHaveBeenCalled());
  const content = handlers.onSave.mock.calls[0]![1] as typeof draft.content;
  expect(content.cases[0]!.input.order_id).toBe("B");
  expect(content.cases[0]!.expected.amount).toBe(100);
  expect(content.cases).toHaveLength(2);
});

it("shows immutable published tasks in the same form", async () => {
  const user = userEvent.setup();
  render(<CatalogEditor {...props()} />);
  await user.selectOptions(screen.getByLabelText("查看版本"), "v1");
  expect(await screen.findByLabelText("任务要求 1")).toBeDisabled();
  expect(
    screen.queryByRole("button", { name: "保存草稿" }),
  ).not.toBeInTheDocument();
});

const rules = {
  entries: [
    {
      name: "金额一致",
      dimension: "outcome",
      required_to_pass: true,
      weight: 1,
      definition: {
        type: "RULE",
        criterion_id: "amount",
        threshold: 1,
        required_evidence: ["output", "expected"],
        config: {
          operator: "eq",
          selector: "/output/amount",
          expected_selector: "/expected/amount",
        },
      },
    },
  ],
};

it("validates pasted JSON before saving a complete rule set", async () => {
  const user = userEvent.setup();
  const handlers = props();
  render(
    <CatalogEditor
      {...handlers}
      resource={{ ...draft, kind: "SUITE", content: { entries: [] } }}
    />,
  );
  const editor = await screen.findByLabelText("评分规则 JSON");
  await user.clear(editor);
  await user.paste("{");
  await user.click(screen.getByRole("button", { name: "保存草稿" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "内容不是有效 JSON",
  );
  expect(handlers.onSave).not.toHaveBeenCalled();
  await user.clear(editor);
  await user.paste(JSON.stringify(rules));
  await user.click(screen.getByRole("button", { name: "保存草稿" }));
  await waitFor(() =>
    expect(handlers.onSave).toHaveBeenCalledWith(draft.name, rules),
  );
});

it("keeps published references unchanged and read-only", async () => {
  const handlers = props();
  const content = {
    entries: [
      {
        evaluator_version: "11111111-1111-4111-8111-111111111111",
        dimension: "outcome",
        required_to_pass: true,
        weight: 1,
      },
    ],
  };
  const published = await handlers.loadVersion("v1");
  handlers.loadVersion
    .mockClear()
    .mockResolvedValue({ ...published, kind: "SUITE", content });
  render(
    <CatalogEditor
      {...handlers}
      resource={{ ...draft, kind: "SUITE", content }}
    />,
  );
  expect(await screen.findByLabelText("评分规则 JSON")).toHaveValue(
    JSON.stringify(content, null, 2),
  );
  expect(handlers.loadVersion).not.toHaveBeenCalled();
  await userEvent
    .setup()
    .selectOptions(screen.getByLabelText("查看版本"), "v1");
  expect(await screen.findByLabelText("评分规则 JSON")).toBeDisabled();
  expect(handlers.loadVersion).toHaveBeenCalledTimes(1);
});
