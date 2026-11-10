import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import {
  Badge,
  Dialog,
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { DeletionControl } from "./DeletionControl";
import { executionPlan, executionStatus } from "./presentation";
import { objectValue } from "../catalog/validation";

export function ExperimentsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [requestedDeletion, setRequestedDeletion] = useState("");
  const experiments = useQuery({
    queryKey: [workspace_id, "experiments", offset],
    queryFn: () =>
      request(
        api.GET("/api/v1/experiment-summaries", {
          params: { query: { offset, limit: 25 } },
        }),
      ),
    refetchInterval: 10000,
  });
  const [selected, setSelected] = useState("");
  const [cancelId, setCancelId] = useState("");
  const selectedExperiment = useQuery({
    queryKey: [workspace_id, "experiment", selected],
    enabled: !!selected,
    queryFn: () =>
      request(
        api.GET("/api/v1/experiment-summaries/{id}", {
          params: { path: { id: selected } },
        }),
      ),
  });
  const trials = useQuery({
    queryKey: [workspace_id, "trials", selected],
    enabled: !!selected,
    queryFn: () =>
      request(
        api.GET("/api/v1/experiments/{id}/trials", {
          params: { path: { id: selected } },
        }),
      ),
    refetchInterval: 10000,
  });
  const cancel = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/experiments/{id}/cancel", {
          params: { path: { id: cancelId } },
        }),
      ),
    onSuccess: async () => {
      setCancelId("");
      await cache.invalidateQueries({
        queryKey: [workspace_id, "experiments"],
      });
      await cache.invalidateQueries({ queryKey: [workspace_id, "trials"] });
    },
  });
  return (
    <>
      <PageHeading
        title="实验"
        description="应用或 CI 通过 SDK 上报后，在这里查看任务执行、评分和报告。"
      >
        <Link className="button-link" to="/workspace/agents">
          配置 SDK 接入
        </Link>
      </PageHeading>
      {requestedDeletion && (
        <p className="warning-notice">
          实验已停止访问，正在清理历史数据。
          <Link to={"/workspace/deletions/" + requestedDeletion}>
            查看删除进度
          </Link>
        </p>
      )}
      <ErrorNotice error={experiments.error} />
      {experiments.isPending ? (
        <Loading />
      ) : experiments.data?.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>评测 Agent</th>
                <th>评测配置</th>
                <th>任务安排</th>
                <th>执行状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {experiments.data.map((item) => (
                <tr key={item.id}>
                  <td>
                    <Link
                      className="experiment-title"
                      to={"/workspace/reports/" + item.id}
                    >
                      {item.run_name ?? item.agent_name}
                    </Link>
                    {item.sdk_project && (
                      <small>
                        {item.sdk_project} / {item.namespace} ·{" "}
                        {item.agent_name}
                      </small>
                    )}
                    <small>Agent 版本：{item.agent_version}</small>
                    <small>
                      创建于 {new Date(item.created_at).toLocaleString("zh-CN")}
                    </small>
                    <details className="identity-details">
                      <summary>编号与截止时间</summary>
                      <p>
                        实验编号：<code>{item.id}</code>
                      </p>
                      <p>
                        Agent 编号：<code>{item.agent_id}</code>
                      </p>
                      <p>
                        截止时间：
                        {new Date(item.deadline).toLocaleString("zh-CN")}
                      </p>
                    </details>
                  </td>
                  <td>
                    <p>
                      任务集：{item.dataset_name} · v
                      {item.dataset_version_number}
                    </p>
                    <p>
                      评分规则集：{item.suite_name} · v
                      {item.suite_version_number}
                    </p>
                  </td>
                  <td>
                    <strong>{item.task_count} 次执行</strong>
                    <small>
                      {item.case_count} 道任务，每道 {item.repeat_count} 次
                    </small>
                  </td>
                  <td>
                    <Badge>
                      {item.evaluation_authorized === false &&
                      item.status !== "CANCELLED"
                        ? "待评测"
                        : item.sdk_project && item.status === "ACTIVE"
                          ? "已上报"
                          : executionStatus(item.status)}
                    </Badge>
                  </td>
                  <td>
                    <Link
                      className="button-link"
                      to={"/workspace/reports/" + item.id}
                    >
                      查看报告
                    </Link>
                    <button onClick={() => setSelected(item.id)}>
                      查看 {item.task_count} 次任务执行
                    </button>
                    <DeletionControl
                      experimentId={item.id}
                      onDelete={(key) =>
                        request(
                          api.DELETE("/api/v1/experiments/{id}", {
                            params: { path: { id: item.id } },
                            body: { idempotency_key: key },
                          }),
                        )
                      }
                      onRequested={(status) => {
                        setRequestedDeletion(status.experiment_id);
                        void cache.invalidateQueries({
                          queryKey: [workspace_id, "experiments"],
                        });
                      }}
                    />
                    {!["CANCELLED", "COMPLETED", "DONE", "DELETED"].includes(
                      item.status,
                    ) && (
                      <button
                        className="danger subtle"
                        onClick={() => setCancelId(item.id)}
                      >
                        取消
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="还没有实验">
          发布评分规则后，在应用中使用账号登录 SDK 并上报任务。
        </Empty>
      )}
      <div className="pagination">
        <button
          disabled={!offset}
          onClick={() => setOffset(Math.max(0, offset - 25))}
        >
          上一页
        </button>
        <button
          disabled={experiments.data?.length !== 25}
          onClick={() => setOffset(offset + 25)}
        >
          下一页
        </button>
      </div>
      {selected && (
        <section className="panel">
          <h2>
            {selectedExperiment.data
              ? `${selectedExperiment.data.agent_name} 的任务执行列表`
              : "任务执行列表"}
          </h2>
          {selectedExperiment.data && (
            <p className="hint">
              {executionPlan(selectedExperiment.data)}
              。每行是一次任务执行，点击“查看报告”可看评分与调用轨迹。
            </p>
          )}
          <ErrorNotice error={trials.error ?? selectedExperiment.error} />
          {trials.isPending ? (
            <Loading />
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>任务要求</th>
                    <th>执行次数</th>
                    <th>执行状态</th>
                  </tr>
                </thead>
                <tbody>
                  {trials.data?.map((trial) => (
                    <tr key={trial.id}>
                      <td>
                        <strong>任务 {trial.case_index + 1}</strong>
                        <p className="task-instruction">
                          {String(objectValue(trial.task).instruction)}
                        </p>
                        <details className="identity-details">
                          <summary>执行编号</summary>
                          <code>{trial.id}</code>
                        </details>
                      </td>
                      <td>第 {trial.attempt} 次</td>
                      <td>
                        <Badge>{executionStatus(trial.status)}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      <Dialog
        open={!!cancelId}
        title="取消实验"
        onClose={() => setCancelId("")}
      >
        <p>排队和等待评测的任务会停止。已发送的模型请求按实际用量结算。</p>
        <ErrorNotice error={cancel.error} />
        <button
          className="danger"
          disabled={cancel.isPending}
          onClick={() => cancel.mutate()}
        >
          确认取消
        </button>
      </Dialog>
    </>
  );
}
