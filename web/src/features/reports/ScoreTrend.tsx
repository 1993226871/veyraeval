import { useQueries } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import { ErrorNotice } from "../../components/ui";

type Summary = Schema<"ExperimentSummary">;
type Report = Schema<"ExperimentReport">;

export function ScoreTrend({
  current,
  report,
  history,
}: {
  current: Summary;
  report: Report;
  history: Summary[];
}) {
  const runs = history
    .filter(
      (run) =>
        run.id !== current.id &&
        run.agent_name === current.agent_name &&
        run.dataset_version_id === current.dataset_version_id &&
        run.suite_version_id === current.suite_version_id,
    )
    .sort((a, b) => b.created_at.localeCompare(a.created_at))
    .slice(0, 7);
  const comparisons = useQueries({
    queries: runs.map((run) => ({
      queryKey: [current.workspace_id, "comparison", current.id, run.id],
      refetchInterval: ({
        state,
      }: {
        state: { data?: Schema<"ComparisonReport"> };
      }) =>
        state.data?.reasons.includes("EXECUTION_OR_EVALUATION_PENDING")
          ? 10000
          : false,
      queryFn: ({ signal }) =>
        request(
          api.GET("/api/v1/reports/comparison", {
            params: { query: { left: current.id, right: run.id } },
            signal,
          }),
        ),
    })),
  });
  const points = [
    ...runs.map((run, index) => {
      const check = comparisons[index]?.data;
      const score = check?.directly_comparable
        ? check.right.dimensions.outcome?.score
        : null;
      return {
        run,
        score: score ?? null,
        pending: comparisons[index]?.isPending,
        error: comparisons[index]?.error,
      };
    }),
    {
      run: current,
      score:
        report.pending_attempts === 0 &&
        report.finished_attempts === report.planned_attempts
          ? (report.dimensions.outcome?.score ?? null)
          : null,
      pending: false,
      error: null,
    },
  ].sort((a, b) => a.run.created_at.localeCompare(b.run.created_at));
  const x = (index: number) =>
    42 + (index * 516) / Math.max(points.length - 1, 1);
  const y = (score: number) => 16 + 124 * (1 - score);
  return (
    <section className="panel" aria-label="Agent 评分趋势">
      <h2>Agent 评分趋势</h2>
      <p className="hint">
        最近八轮结果质量分，按运行时间排列。历史运行须通过与本轮的可比性检查。
      </p>
      <ErrorNotice error={points.find((point) => point.error)?.error} />
      {runs.length === 0 ? (
        <p>使用相同任务集和评分规则集再运行一次，即可查看变化。</p>
      ) : (
        <>
          <svg
            viewBox="0 0 600 160"
            width="100%"
            height="180"
            role="img"
            aria-label="历次结果质量分折线图"
          >
            {[0, 0.5, 1].map((tick) => (
              <g key={tick}>
                <text x="1" y={y(tick) + 4} fontSize="12" fill="currentColor">
                  {tick * 100}
                </text>
                <line
                  x1="42"
                  y1={y(tick)}
                  x2="558"
                  y2={y(tick)}
                  stroke="var(--line)"
                />
              </g>
            ))}
            {points.map(({ run, score }, index) => {
              const previous = points[index - 1]?.score;
              return score === null ? null : (
                <g key={run.id}>
                  {previous != null && (
                    <line
                      x1={x(index - 1)}
                      y1={y(previous)}
                      x2={x(index)}
                      y2={y(score)}
                      stroke="var(--teal)"
                      strokeWidth="2"
                    />
                  )}
                  <circle cx={x(index)} cy={y(score)} r="5" fill="var(--teal)">
                    <title>
                      {run.agent_version} · {Math.round(score * 100)} 分
                    </title>
                  </circle>
                </g>
              );
            })}
          </svg>
          <ul>
            {points.map(({ run, score, pending, error }) => (
              <li key={run.id}>
                <Link to={"/workspace/reports/" + run.id}>
                  {run.agent_version} ·{" "}
                  {new Date(run.created_at).toLocaleString("zh-CN")}
                </Link>
                {" · "}
                {score != null
                  ? `${Math.round(score * 100)} 分`
                  : pending
                    ? "正在核对"
                    : error
                      ? "载入失败"
                      : "不可比或暂无分数"}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
