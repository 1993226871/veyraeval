import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, Loading, PageHeading } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import type { Schema } from "../../api/types";

export function SdkProjectsPage() {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const key = [workspace_id, "sdk-projects"];
  const projects = useQuery({
    queryKey: key,
    queryFn: ({ signal }) =>
      request(api.GET("/api/v1/sdk-projects", { signal })),
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
  return (
    <>
      <PageHeading
        title="SDK 接入"
        description="在应用中使用平台账号登录 SDK，并为每轮指定项目名称。首次成功上报时，平台会在你的工作空间创建项目。"
      />
      <ErrorNotice error={projects.error ?? configure.error} />
      {projects.isPending ? (
        <Loading />
      ) : projects.data?.length ? (
        projects.data.map((project) => (
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
          </section>
        ))
      ) : (
        <section className="panel">
          <p>完成第一次 SDK 上报后，项目会显示在这里。</p>
        </section>
      )}
    </>
  );
}
