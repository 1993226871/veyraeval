import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { Dialog, ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import type { Schema } from "../../api/types";

export function SdkProjectsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const key = [workspace_id, "sdk-projects"];
  const [name, setName] = useState("");
  const [issued, setIssued] = useState<string>();
  const [rotation, setRotation] = useState<Schema<"SdkProject">>();
  const projects = useQuery({
    queryKey: key,
    queryFn: ({ signal }) =>
      request(api.GET("/api/v1/sdk-projects", { signal })),
  });
  const create = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/sdk-projects", { body: { name: name.trim() } }),
      ),
    onSuccess: async (result) => {
      setIssued(result.secret);
      setName("");
      await cache.invalidateQueries({ queryKey: key });
    },
  });
  const configure = useMutation({
    mutationFn: (project: Schema<"SdkProject">) =>
      request(
        api.PUT("/api/v1/sdk-projects/{id}", {
          params: { path: { id: project.id } },
          body: { auto_evaluate: !project.auto_evaluate },
        }),
      ),
    onSuccess: () => cache.invalidateQueries({ queryKey: key }),
  });
  const rotate = useMutation({
    mutationFn: (id: string) =>
      request(
        api.POST("/api/v1/sdk-projects/{id}/rotate", {
          params: { path: { id } },
        }),
      ),
    onSuccess: (result) => {
      setRotation(undefined);
      setIssued(result.secret);
    },
  });
  return (
    <>
      <PageHeading
        title="SDK 接入"
        description="创建项目后，将上报密钥配置到应用或 CI。每轮的 Agent、任务与运行名称由 SDK 提交。"
      />
      <ErrorNotice error={projects.error ?? create.error ?? configure.error} />
      <form
        className="panel"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <label>
          项目名称
          <input
            required
            maxLength={100}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <button className="primary" disabled={create.isPending || !name.trim()}>
          创建上报项目
        </button>
      </form>
      {projects.isPending ? (
        <Loading />
      ) : (
        projects.data?.map((project) => (
          <section className="panel" key={project.id}>
            <h2>{project.name}</h2>
            <label className="check-label">
              <input
                type="checkbox"
                role="switch"
                checked={project.auto_evaluate}
                disabled={configure.isPending}
                onChange={() => configure.mutate(project)}
              />
              自动评测
            </label>
            <p className="hint">
              开启后，新上报的完整运行自动评分。关闭后，打开该轮报告并点击“开始评测”。
            </p>
            <button
              onClick={() => {
                rotate.reset();
                setRotation(project);
              }}
            >
              更换上报密钥
            </button>
          </section>
        ))
      )}
      <Dialog
        open={!!rotation}
        title="更换上报密钥"
        onClose={() => setRotation(undefined)}
      >
        <p>更换后，请更新 {rotation?.name} 的应用配置，旧密钥立即失效。</p>
        <ErrorNotice error={rotate.error} />
        <button
          disabled={rotate.isPending}
          onClick={() => {
            if (rotation) rotate.mutate(rotation.id);
          }}
        >
          确认更换
        </button>
      </Dialog>
      <Dialog
        open={!!issued}
        title="保存上报密钥"
        onClose={() => setIssued(undefined)}
      >
        <p>密钥只显示一次。将其保存为应用环境变量 VEYRAEVAL_UPLOAD_KEY。</p>
        <label>
          本次上报密钥
          <textarea readOnly value={issued ?? ""} />
        </label>
        <button onClick={() => setIssued(undefined)}>我已保存</button>
      </Dialog>
    </>
  );
}
