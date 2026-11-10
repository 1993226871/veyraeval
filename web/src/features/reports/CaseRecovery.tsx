import { recoveryTask, type CaseSource } from "./diagnostics";

export function CaseRecovery({
  task,
  source,
}: {
  task: Record<string, unknown>;
  source: CaseSource;
}) {
  function download() {
    const record = recoveryTask(task, source);
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(record) + "\n"], {
        type: "application/x-ndjson",
      }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "regression-case.jsonl";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <button type="button" onClick={download}>
      下载回归样例
    </button>
  );
}
