import {
  useReportDataset,
  useReportIdentity,
  useComparisonTrials,
} from "./queries";
import { useState } from "react";
import type { Schema } from "../../api/types";
import { Badge, ErrorNotice, Loading } from "../../components/ui";
import { objectValue } from "../catalog/validation";
import { TrialEvidence } from "./TrialEvidence";
import {
  compareTrials,
  diagnoseTrial,
  pairTrials,
  verdictLabel,
} from "./diagnostics";

export function TaskComparison({
  leftId,
  rightId,
  datasetVersionId,
  comparable,
}: {
  leftId: string;
  rightId: string;
  datasetVersionId: string;
  comparable: boolean;
}) {
  const trials = useComparisonTrials(leftId, rightId);
  const dataset = useReportDataset(datasetVersionId);
  const leftIdentity = useReportIdentity(leftId),
    rightIdentity = useReportIdentity(rightId);
  const [selected, setSelected] = useState<{
    caseIndex: number;
    attempt: number;
  }>();
  const rows = pairTrials(
    trials.data?.pages.flatMap((p) => p.left.items) ?? [],
    trials.data?.pages.flatMap((p) => p.right.items) ?? [],
  );
  const current = rows.find(
    (row) =>
      row.caseIndex === selected?.caseIndex && row.attempt === selected.attempt,
  );
  const cases = objectValue(dataset.data?.content).cases;
  const label = (side: number) => {
    const name = side === 0 ? leftIdentity.data : rightIdentity.data;
    return name
      ? `${name.agent_name} · ${name.agent_version}`
      : side === 0
        ? "左侧 Agent"
        : "右侧 Agent";
  };
  return (
    <section className="panel" aria-label="逐任务对比">
      <h2>逐任务对比</h2>
      <p className="hint">
        以左侧为基线，按同一道任务和执行次数配对。改善与退步仅表示通过结论的变化；评分配置不可比时不作判断。
      </p>
      <p className="hint">
        窄屏可左右滑动表格；点击“并排查看答案与证据”展开双方完整内容。
      </p>
      <ErrorNotice
        error={
          trials.error ??
          dataset.error ??
          leftIdentity.error ??
          rightIdentity.error
        }
      />
      {trials.isPending ? (
        <Loading />
      ) : rows.length === 0 ? (
        <p>尚无任务执行记录。</p>
      ) : (
        <div className="table-scroll">
          <table className="task-comparison">
            <thead>
              <tr>
                <th>任务</th>
                <th>{label(0)}</th>
                <th>{label(1)}</th>
                <th>变化</th>
                <th>证据</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.caseIndex}:${row.attempt}`}>
                  <th>
                    <p className="task-instruction">
                      {Array.isArray(cases)
                        ? String(
                            objectValue(cases[row.caseIndex]).instruction ??
                              `任务 ${row.caseIndex + 1}`,
                          )
                        : `任务 ${row.caseIndex + 1}`}
                    </p>
                    <small>第 {row.attempt} 次执行</small>
                  </th>
                  <td>
                    <TrialSummary trial={row.left} />
                  </td>
                  <td>
                    <TrialSummary trial={row.right} />
                  </td>
                  <td>
                    <Badge
                      tone={
                        compareTrials(row.left, row.right, comparable) ===
                        "退步"
                          ? "danger"
                          : compareTrials(row.left, row.right, comparable) ===
                              "改善"
                            ? "success"
                            : "neutral"
                      }
                    >
                      {compareTrials(row.left, row.right, comparable)}
                    </Badge>
                  </td>
                  <td>
                    <button
                      onClick={() =>
                        setSelected({
                          caseIndex: row.caseIndex,
                          attempt: row.attempt,
                        })
                      }
                    >
                      并排查看答案与证据
                    </button>
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
            disabled={trials.isFetching}
            onClick={() => void trials.fetchNextPage()}
          >
            加载更多任务
          </button>
        )}
      </div>
      {current && (
        <section aria-label="双方任务证据">
          <div className="section-heading">
            <h3>
              任务 {current.caseIndex + 1} · 第 {current.attempt} 次执行
            </h3>
            <button onClick={() => setSelected(undefined)}>收起双方证据</button>
          </div>
          <div className="two-column evidence-comparison">
            <div>
              <h3>{label(0)}</h3>
              {current.left ? (
                <TrialEvidence
                  key={current.left.trial_id}
                  trial={current.left}
                  experimentId={leftId}
                />
              ) : (
                <p>左侧任务尚未配对，请继续加载。</p>
              )}
            </div>
            <div>
              <h3>{label(1)}</h3>
              {current.right ? (
                <TrialEvidence
                  key={current.right.trial_id}
                  trial={current.right}
                  experimentId={rightId}
                />
              ) : (
                <p>右侧任务尚未配对，请继续加载。</p>
              )}
            </div>
          </div>
        </section>
      )}
    </section>
  );
}

export function TrialSummary({
  trial,
}: {
  trial: Schema<"TrialReport"> | undefined;
}) {
  if (!trial) return <p>尚未加载或无对应执行</p>;
  const diagnosis = diagnoseTrial(trial);
  return (
    <div className="diagnosis-summary">
      <Badge
        tone={
          trial.verdict === "PASS"
            ? "success"
            : trial.verdict === "FAIL"
              ? "danger"
              : "warning"
        }
      >
        {verdictLabel(trial.verdict)}
      </Badge>
      <p>{diagnosis.title}</p>
      {diagnosis.reasons.slice(0, 2).map((reason, index) => (
        <p className="task-instruction" key={index}>
          {reason}
        </p>
      ))}
      {diagnosis.reasons.length > 2 && (
        <small>
          另有 {diagnosis.reasons.length - 2} 条解释，查看证据了解全部。
        </small>
      )}
      <small>
        结果 {trial.dimensions.outcome?.score ?? "—"} · 过程{" "}
        {trial.dimensions.process?.score ?? "—"}
      </small>
    </div>
  );
}
