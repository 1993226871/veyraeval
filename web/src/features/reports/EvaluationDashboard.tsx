import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import { Empty, ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { experimentTitle } from "../experiments/presentation";
import { reportReadRetry, queueReportRead } from "./queries";
import { ScoreTrend } from "./ScoreTrend";

type Run = Schema<"ExperimentSummary">;
const filters = [
  {
    label: "项目",
    key: (run: Run) => run.sdk_project_id ?? "legacy",
    name: (run: Run) => run.sdk_project ?? "平台实验",
  },
  {
    label: "Agent",
    key: (run: Run) => run.agent_name,
    name: (run: Run) => run.agent_name,
  },
  {
    label: "命名空间",
    key: (run: Run) => run.namespace ?? "legacy",
    name: (run: Run) => run.namespace ?? "无命名空间",
  },
  {
    label: "任务集版本",
    key: (run: Run) => run.dataset_version_id,
    name: (run: Run) => `${run.dataset_name} v${run.dataset_version_number}`,
  },
  {
    label: "评分规则版本",
    key: (run: Run) => run.suite_version_id,
    name: (run: Run) => `${run.suite_name} v${run.suite_version_number}`,
  },
];
const seriesKey = (run: Run) =>
  JSON.stringify(filters.map(({ key }) => key(run)));
const runLabel = (run: Run) =>
  `${experimentTitle(run)} · ${new Date(run.created_at).toLocaleString("zh-CN")}`;

function trendLabel(run: Run, candidates: Run[]) {
  const sameAgent = candidates.filter(
    (item) => item.agent_name === run.agent_name,
  );
  const distinguishingNames = filters
    .filter(({ key }) => sameAgent.some((item) => key(item) !== key(run)))
    .map(({ name }) => name(run));
  return [run.agent_name, ...distinguishingNames].join(" · ");
}

export function EvaluationDashboard() {
  const { workspace_id } = useWorkspace();
  const [selection, setSelection] = useState<Record<string, string>>({});
  const [days, setDays] = useState("");
  const [series, setSeries] = useState("");
  const [left, setLeft] = useState("");
  const [right, setRight] = useState("");
  const history = useQuery({
    queryKey: [workspace_id, "experiments", "dashboard"],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries", {
          params: { query: { limit: 100 } },
          signal,
        }),
      ),
    refetchInterval: 10000,
  });
  const runs = history.data ?? [];
  const cutoff = days ? Date.now() - Number(days) * 86400000 : 0;
  const matching = runs
    .filter(
      (run) =>
        (!days || Date.parse(run.created_at) >= cutoff) &&
        filters.every(
          ({ label, key }) =>
            !selection[label] || selection[label] === key(run),
        ),
    )
    .sort((a, b) => b.created_at.localeCompare(a.created_at));
  const groups = new Map<string, Run>();
  for (const run of matching) {
    const key = seriesKey(run);
    if (!groups.has(key)) groups.set(key, run);
  }
  const current = groups.get(series) ?? groups.values().next().value;
  const report = useQuery({
    queryKey: [workspace_id, "report", current?.id],
    enabled: !!current,
    ...reportReadRetry,
    queryFn: ({ signal }) =>
      queueReportRead(signal, () =>
        request(
          api.GET("/api/v1/reports/experiments/{id}", {
            params: { path: { id: current!.id } },
            signal,
          }),
        ),
      ),
    refetchInterval: 10000,
  });

  function resetRunSelection() {
    setSeries("");
    setLeft("");
    setRight("");
  }

  return (
    <>
      <PageHeading
        title="评测看板"
        description="筛选最近 100 轮实验，查看评分趋势或选择两轮详细对比。"
      />
      <section className="panel dashboard-filters" aria-label="筛选实验">
        {filters.map(({ label, key, name }) => (
          <label key={label}>
            {label}
            <select
              value={selection[label] ?? ""}
              onChange={(event) => {
                setSelection({ ...selection, [label]: event.target.value });
                resetRunSelection();
              }}
            >
              <option value="">全部</option>
              {[...new Map(runs.map((run) => [key(run), name(run)]))].map(
                ([id, text]) => (
                  <option key={id} value={id}>
                    {text}
                  </option>
                ),
              )}
            </select>
          </label>
        ))}
        <label>
          时间范围
          <select
            value={days}
            onChange={(event) => {
              setDays(event.target.value);
              resetRunSelection();
            }}
          >
            <option value="">不限</option>
            <option value="7">最近 7 天</option>
            <option value="30">最近 30 天</option>
            <option value="90">最近 90 天</option>
          </select>
        </label>
      </section>
      <ErrorNotice error={history.error} />
      {history.isPending && <Loading />}
      {history.data && !current && (
        <Empty title="没有匹配的实验">调整筛选条件，或上报一轮新的评测。</Empty>
      )}
      {current && (
        <>
          <section className="panel">
            <label>
              选择趋势
              <select
                value={seriesKey(current)}
                onChange={(event) => setSeries(event.target.value)}
              >
                {[...groups].map(([key, run]) => (
                  <option key={key} value={key}>
                    {trendLabel(run, [...groups.values()])}
                  </option>
                ))}
              </select>
            </label>
          </section>
          <ErrorNotice error={report.error} />
          {report.isPending && <Loading />}
          {report.data && (
            <ScoreTrend
              current={current}
              report={report.data}
              history={matching.filter(
                (run) => seriesKey(run) === seriesKey(current),
              )}
            />
          )}
          <section className="panel" aria-label="对比两轮实验">
            <h2>对比两轮实验</h2>
            <div className="comparison-picker">
              <label>
                第一轮
                <select
                  value={left}
                  onChange={(event) => setLeft(event.target.value)}
                >
                  <option value="">选择实验</option>
                  {matching
                    .filter((run) => run.id !== right)
                    .map((run) => (
                      <option key={run.id} value={run.id}>
                        {runLabel(run)}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                第二轮
                <select
                  value={right}
                  onChange={(event) => setRight(event.target.value)}
                >
                  <option value="">选择实验</option>
                  {matching
                    .filter((run) => run.id !== left)
                    .map((run) => (
                      <option key={run.id} value={run.id}>
                        {runLabel(run)}
                      </option>
                    ))}
                </select>
              </label>
              {left && right && (
                <Link
                  className="button-link"
                  to={
                    "/workspace/comparisons?" +
                    new URLSearchParams({ left, right })
                  }
                >
                  查看可比性与差异
                </Link>
              )}
            </div>
          </section>
        </>
      )}
    </>
  );
}
