import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { Schema } from "../../api/types";
import {
  Badge,
  Dialog,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { TrialEvidence } from "./TrialEvidence";
import {
  experimentTitle,
  executionPlan,
  executionStatus,
} from "../experiments/presentation";
import { EstimateNotice } from "../experiments/estimate";
import { TrialSummary } from "./TaskComparison";
import { ScoreTrend } from "./ScoreTrend";
import { useReportDataset, useReportTrials } from "./queries";
import { objectValue } from "../catalog/validation";

export function ExperimentReport() {
  const { id = "" } = useParams();
  return <ExperimentReportPage key={id} id={id} />;
}
function ExperimentReportPage({ id }: { id: string }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [selected, setSelected] = useState<Schema<"TrialReport">>();
  const [compareId, setCompareId] = useState("");
  const [evaluateOpen, setEvaluateOpen] = useState(false);
  const report = useQuery({
    queryKey: [workspace_id, "report", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/reports/experiments/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
    refetchInterval: 10000,
  });
  const experiment = useQuery({
    queryKey: [workspace_id, "experiment", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
  });
  const others = useQuery({
    queryKey: [workspace_id, "comparison-options"],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries", {
          params: { query: { limit: 100 } },
          signal,
        }),
      ),
  });
  const trials = useReportTrials(id);
  const dataset = useReportDataset(experiment.data?.dataset_version_id);
  const cases = objectValue(dataset.data?.content).cases;
  const sdkRun = useQuery({
    queryKey: [workspace_id, "sdk-run", id],
    enabled: !!experiment.data?.sdk_project_id,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiments/{id}/sdk-run", {
          params: { path: { id } },
          signal,
        }),
      ),
    refetchInterval: (query) =>
      query.state.data?.phase === "PREPARING" ? 5000 : false,
  });
  const evaluate = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/experiments/{id}/sdk-run/evaluate", {
          params: { path: { id } },
        }),
      ),
    onSuccess: async () => {
      setEvaluateOpen(false);
      await cache.invalidateQueries({
        queryKey: [workspace_id, "sdk-run", id],
      });
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiments"],
      });
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiment", id],
      });
      await cache.invalidateQueries({ queryKey: [workspace_id, "report", id] });
    },
  });
  const download = useMutation({
    mutationFn: async () => {
      const value = await request(
        api.GET("/api/v1/reports/experiments/{id}/export", {
          params: { path: { id } },
        }),
      );
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(value, null, 2)], {
          type: "application/json;charset=utf-8",
        }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = `agenteval-${id}.json`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
  const value = report.data;
  const currentTrial =
    trials.data?.pages
      .flatMap((page) => page.items)
      .find((item) => item.trial_id === selected?.trial_id) ?? selected;
  return (
    <>
      <PageHeading
        title={
          experiment.data
            ? (experiment.data.run_name ??
              `${experiment.data.agent_name} 的评测报告`)
            : "实验报告"
        }
        description={
          experiment.data
            ? `${experiment.data.dataset_name} · v${experiment.data.dataset_version_number} ｜ ${experiment.data.suite_name} · v${experiment.data.suite_version_number}`
            : "正在加载实验信息"
        }
      >
        <button disabled={download.isPending} onClick={() => download.mutate()}>
          导出 JSON 与证据
        </button>
        {sdkRun.data?.phase === "WAITING" && (
          <button
            className="primary"
            onClick={() => {
              evaluate.reset();
              setEvaluateOpen(true);
            }}
          >
            开始评测
          </button>
        )}
      </PageHeading>
      <ErrorNotice error={sdkRun.error} />
      {sdkRun.data?.phase === "WAITING" && (
        <p className="warning-notice">
          已接收上报，待评测。可先查看任务和轨迹。
        </p>
      )}
      {sdkRun.data?.phase === "PREPARING" && (
        <p className="hint">正在收集本轮证据，齐全后统一入队评分。</p>
      )}
      {experiment.data && (
        <section className="panel experiment-context" aria-label="关联实验信息">
          <h2>本次评测对象</h2>
          {experiment.data.sdk_project && (
            <p>
              项目 {experiment.data.sdk_project} · Namespace{" "}
              {experiment.data.namespace}
            </p>
          )}
          <p>
            Agent：<strong>{experiment.data.agent_name}</strong> · 版本{" "}
            {experiment.data.agent_version}
          </p>
          <p>
            任务集：{experiment.data.dataset_name} · v
            {experiment.data.dataset_version_number}
          </p>
          <p>
            评分规则集：{experiment.data.suite_name} · v
            {experiment.data.suite_version_number}
          </p>
          <p>{executionPlan(experiment.data)}</p>
          <p>
            创建时间：
            {new Date(experiment.data.created_at).toLocaleString("zh-CN")}
          </p>
          <details className="identity-details">
            <summary>查看实验与 Agent 编号</summary>
            <p>
              实验编号：<code>{id}</code>
            </p>
            <p>
              Agent 编号：<code>{experiment.data.agent_id}</code>
            </p>
          </details>
        </section>
      )}
      {experiment.data?.source_experiment_id && (
        <p>
          重跑来源：
          <Link
            to={"/workspace/reports/" + experiment.data.source_experiment_id}
          >
            {experiment.data.source_experiment_id}
          </Link>
        </p>
      )}
      <ErrorNotice
        error={
          report.error ??
          experiment.error ??
          others.error ??
          trials.error ??
          dataset.error ??
          download.error
        }
      />
      {report.isPending ? (
        <Loading />
      ) : (
        value && (
          <>
            <div className="report-progress">
              <p>
                执行结束：
                <strong>
                  {value.finished_attempts} / {value.planned_attempts}
                </strong>{" "}
                · 已开始 {value.started_attempts} · 实验状态{" "}
                <Badge>{executionStatus(value.experiment_status)}</Badge>
              </p>
              <p>
                质量已判定：
                <strong>
                  {value.determinable_attempts} / {value.planned_attempts}
                </strong>{" "}
                · 待评 {value.pending_attempts} · 不确定{" "}
                {value.undetermined_attempts} · 取消 {value.cancelled_attempts}
              </p>
            </div>
            {(value.evaluation_suite_version_ids.length !== 1 ||
              value.evaluation_suite_version_ids[0] !==
                value.suite_version_id) && (
              <p className="warning-notice">
                当前报告包含重评配置或多个实际评分规则集，请先核对版本范围，再进行实验对比。
              </p>
            )}
            <dl className="metrics">
              <div>
                <dt>通过 / 计划尝试</dt>
                <dd>
                  {rate(value.pass_rate_planned.value)}
                  <small>
                    {value.pass_rate_planned.numerator} /{" "}
                    {value.pass_rate_planned.denominator}
                  </small>
                </dd>
              </div>
              <div>
                <dt>通过 / 可判定尝试</dt>
                <dd>
                  {rate(value.pass_rate_determinable.value)}
                  <small>
                    {value.pass_rate_determinable.numerator} /{" "}
                    {value.pass_rate_determinable.denominator}
                  </small>
                </dd>
              </div>
              <div>
                <dt>结果质量</dt>
                <dd>
                  {value.dimensions.outcome?.score ?? "—"}
                  <small>
                    已评分 {value.dimensions.outcome?.scored_criteria ?? 0} /{" "}
                    {value.dimensions.outcome?.planned_criteria ?? 0}
                  </small>
                </dd>
              </div>
              <div>
                <dt>执行过程</dt>
                <dd>
                  {value.dimensions.process?.score ?? "—"}
                  <small>
                    已评分 {value.dimensions.process?.scored_criteria ?? 0} /{" "}
                    {value.dimensions.process?.planned_criteria ?? 0}
                  </small>
                </dd>
              </div>
            </dl>
            <div className="two-column">
              <section className="panel">
                <h2>覆盖率</h2>
                {Object.entries(value.dimensions).map(([dimension, score]) => (
                  <div className="coverage-row" key={dimension}>
                    <strong>
                      {dimension === "outcome" ? "结果质量" : "执行过程"}
                    </strong>
                    <p>
                      权重覆盖 {rate(score.coverage)} · {score.scored_weight} /{" "}
                      {score.total_weight}
                    </p>
                    <p>
                      指标覆盖 {score.scored_criteria} /{" "}
                      {score.planned_criteria}
                      {score.partial ? " · 部分评分" : ""}
                    </p>
                  </div>
                ))}
                <p className="hint">
                  缺失、ERROR
                  和额度等待不计为零分。可选项高分不能抵消必需项失败。
                </p>
              </section>
              <section className="panel">
                <h2>执行效率</h2>
                <p>
                  P50：
                  {value.efficiency.p50_duration_ms === null
                    ? "未知"
                    : `${value.efficiency.p50_duration_ms} ms`}
                </p>
                <p>
                  P95：
                  {value.efficiency.p95_duration_ms === null
                    ? "未知"
                    : `${value.efficiency.p95_duration_ms} ms`}
                </p>
                <p>可用耗时样本：{value.efficiency.observed_durations}</p>
                <p className="hint">
                  来源：{value.efficiency.source}。仅统计冻结证据中可验证的根
                  Span 耗时。
                </p>
              </section>
            </div>
            <details className="panel">
              <summary>固定版本与执行状态</summary>
              <p>Agent 版本：{value.agent_version}</p>
              <p>
                任务集：<code>{value.dataset_version_id}</code>
              </p>
              <p>
                原始评分规则集编号：<code>{value.suite_version_id}</code>
              </p>
              <p>
                当前实际评分规则集编号：
                <code>{value.evaluation_suite_version_ids.join(", ")}</code>
              </p>
              <p>
                {Object.entries(value.execution_counts)
                  .map(([status, count]) => `${status}: ${count}`)
                  .join(" · ")}
              </p>
            </details>
            {experiment.data && others.data && (
              <ScoreTrend
                current={experiment.data}
                report={value}
                history={others.data}
              />
            )}
            <div className="comparison-picker">
              <label>
                对比最近 100 次实验
                <select
                  value={compareId}
                  onChange={(event) => setCompareId(event.target.value)}
                >
                  <option value="">选择另一次实验</option>
                  {others.data
                    ?.filter((item) => item.id !== id)
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {experimentTitle(item)} ·{" "}
                        {new Date(item.created_at).toLocaleString("zh-CN")}
                      </option>
                    ))}
                </select>
              </label>
              {compareId && (
                <Link
                  className="button-link"
                  to={
                    "/workspace/comparisons?left=" +
                    encodeURIComponent(id) +
                    "&right=" +
                    encodeURIComponent(compareId)
                  }
                >
                  查看可比性与差异
                </Link>
              )}
            </div>
          </>
        )
      )}
      <section className="panel">
        <h2>逐任务结果</h2>
        <p className="hint">
          每条执行展示质量结论和评分依据。点击任务查看参考答案、实际回答、关联步骤，或下载失败样例到本地题库。
        </p>
        {trials.isPending ? (
          <Loading />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>任务 / 执行次数</th>
                  <th>执行</th>
                  <th>评测</th>
                  <th>结论与原因</th>
                </tr>
              </thead>
              <tbody>
                {trials.data?.pages
                  .flatMap((page) => page.items)
                  .map((item) => (
                    <tr key={item.trial_id}>
                      <td>
                        <button
                          className="text-button"
                          onClick={() => setSelected(item)}
                        >
                          {Array.isArray(cases)
                            ? String(
                                objectValue(cases[item.case_index])
                                  .instruction ?? `任务 ${item.case_index + 1}`,
                              )
                            : `任务 ${item.case_index + 1}`}{" "}
                          · 第 {item.attempt} 次
                        </button>
                      </td>
                      <td>
                        <Badge>{executionStatus(item.execution_status)}</Badge>
                      </td>
                      <td>
                        <Badge>
                          {(
                            {
                              COMPLETED: "评分完成",
                              PARTIAL: "部分评分",
                              PENDING: "等待评分",
                              QUEUED: "等待评分",
                              RUNNING: "评分中",
                              WAITING_QUOTA: "等待额度",
                              WAITING_RESOURCES: "等待资源",
                              CANCELLED: "已取消",
                            } as Record<string, string>
                          )[item.evaluation_state] ?? item.evaluation_state}
                        </Badge>
                      </td>
                      <td>
                        <TrialSummary trial={item} />
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="actions">
          {trials.hasNextPage && (
            <button
              disabled={trials.isFetchingNextPage}
              onClick={() => trials.fetchNextPage()}
            >
              加载更多任务
            </button>
          )}
          {trials.isFetching && (
            <button
              onClick={() =>
                cache.cancelQueries({
                  queryKey: [workspace_id, "report-trials", id],
                })
              }
            >
              停止加载任务
            </button>
          )}
        </div>
      </section>
      {currentTrial && (
        <TrialEvidence
          key={id + currentTrial.trial_id}
          trial={currentTrial}
          experimentId={id}
        />
      )}
      <Dialog
        open={evaluateOpen}
        title="开始评测"
        onClose={() => setEvaluateOpen(false)}
      >
        <p>
          使用本轮固定的评分规则集版本 {experiment.data?.suite_name} · v
          {experiment.data?.suite_version_number}。
        </p>
        <EstimateNotice value={sdkRun.data?.estimate} error={evaluate.error} />
        <button
          className="primary"
          disabled={evaluate.isPending || sdkRun.data?.phase !== "WAITING"}
          onClick={() => evaluate.mutate()}
        >
          确认开始评测
        </button>
      </Dialog>
    </>
  );
}
function rate(value: number | null) {
  return value === null
    ? "—"
    : new Intl.NumberFormat("zh-CN", {
        style: "percent",
        maximumFractionDigits: 1,
      }).format(value);
}
