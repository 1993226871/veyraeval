import type { Schema } from "../../api/types";

export function experimentTitle(experiment: Schema<"ExperimentSummary">) {
  return (
    experiment.run_name ??
    `${experiment.agent_name} · ${experiment.dataset_name} v${experiment.dataset_version_number}`
  );
}

export function executionPlan(experiment: Schema<"ExperimentSummary">) {
  return `${experiment.case_count} 道任务 × 每道 ${experiment.repeat_count} 次 = ${experiment.task_count} 次执行`;
}

const statuses: Record<string, string> = {
  QUEUED: "等待执行",
  LEASED: "已领取",
  RUNNING: "执行中",
  SUCCEEDED: "执行成功",
  FAILED: "执行失败",
  TIMED_OUT: "执行超时",
  CANCELLED: "已取消",
  EXECUTION_UNKNOWN: "执行结果待确认",
  COMPLETED: "已完成",
  DONE: "已完成",
  CREATED: "已创建",
  DELETING: "删除中",
  DELETED: "已删除",
};
export function executionStatus(status: string) {
  return statuses[status] ?? status;
}
