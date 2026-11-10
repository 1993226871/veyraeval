import { useEffect, useState } from "react";
import type {
  CatalogResource,
  PublishedVersion,
  VersionSummary,
} from "../../api/types";
import { Dialog, ErrorNotice, Loading } from "../../components/ui";
import { DatasetFields } from "./DatasetFields";
import { objectValue, parseContent, pretty } from "./validation";

type Props = {
  resource: CatalogResource;
  versions: VersionSummary[];
  loadVersion: (id: string) => Promise<PublishedVersion>;
  onSave: (name: string, content: Record<string, unknown>) => Promise<unknown>;
  onPublish: () => Promise<unknown>;
  onArchive: () => Promise<unknown>;
  children?: React.ReactNode;
};

export function CatalogEditor({
  resource,
  versions,
  loadVersion,
  onSave,
  onPublish,
  onArchive,
  children,
}: Props) {
  const [version, setVersion] = useState("draft");
  const [name, setName] = useState(resource.name);
  const [content, setContent] = useState<Record<string, unknown>>(
    objectValue(resource.content),
  );
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [archive, setArchive] = useState(false);
  const [source, setSource] = useState(pretty(resource.content));
  const isRules = resource.kind !== "DATASET";
  const readonly = version !== "draft";
  const dirty =
    name !== resource.name ||
    (isRules ? source : pretty(content)) !== pretty(resource.content);
  useEffect(() => {
    setVersion("draft");
    setName(resource.name);
    setContent(objectValue(resource.content));
    setSource(pretty(resource.content));
    setError(undefined);
  }, [resource.id, resource.revision]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadFailed(false);
    setError(undefined);
    async function load() {
      const current =
        version === "draft" ? resource : await loadVersion(version);
      const value = { ...objectValue(current.content) };
      if (active) {
        setContent(value);
        setSource(pretty(value));
        setName(current.name);
      }
    }
    load()
      .catch((failure) => {
        if (active) {
          setError(failure);
          setLoadFailed(true);
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [resource.id, resource.revision, version, loadVersion]);
  async function act(action: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await action();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="editor-pane" aria-label="资源编辑器">
      <div className="editor-toolbar">
        <h2>{resource.name}</h2>
        <label>
          查看版本
          <select
            value={version}
            onChange={(event) => setVersion(event.target.value)}
          >
            <option value="draft">草稿（修订 {resource.revision}）</option>
            {versions.map((item) => (
              <option key={item.id} value={item.id}>
                v{item.number}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorNotice error={error} />
      {loading ? (
        <Loading />
      ) : loadFailed ? (
        <p>配置加载失败，请重新选择资源或刷新页面。</p>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void act(async () => {
              if (!name.trim()) throw new Error("请填写名称。");
              await onSave(
                name.trim(),
                parseContent(isRules ? source : pretty(content), resource.kind),
              );
            });
          }}
        >
          {readonly && (
            <>
              <p className="hint">已发布版本固定保存，修改请回到草稿。</p>
              {resource.kind === "SUITE" && (
                <label>
                  评分规则版本 ID
                  <input readOnly value={version} />
                </label>
              )}
            </>
          )}
          <fieldset disabled={readonly || busy} className="catalog-form-body">
            <label>
              资源名称
              <input
                required
                maxLength={200}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            {resource.kind === "DATASET" && (
              <DatasetFields content={content} onChange={setContent} />
            )}
            {isRules && (
              <label>
                评分规则 JSON
                <textarea
                  value={source}
                  onChange={(event) => setSource(event.target.value)}
                  rows={24}
                  spellCheck={false}
                  style={{ fontFamily: "monospace" }}
                />
              </label>
            )}
          </fieldset>
          {!readonly && (
            <div className="actions">
              <button type="submit" className="primary" disabled={busy}>
                保存草稿
              </button>
              <button
                type="button"
                disabled={busy || dirty}
                title={dirty ? "请先保存修改，再发布版本。" : undefined}
                onClick={() => void act(onPublish)}
              >
                发布版本
              </button>
              <button
                type="button"
                className="danger subtle"
                disabled={busy}
                onClick={() => setArchive(true)}
              >
                归档资源
              </button>
            </div>
          )}
          {!readonly && dirty && (
            <p className="hint">有未保存的修改，保存后可发布版本。</p>
          )}
          {!readonly && !dirty && children}
        </form>
      )}
      <Dialog open={archive} title="归档资源" onClose={() => setArchive(false)}>
        <p>归档后不再用于新实验，已发布版本和历史结果会保留。</p>
        <div className="actions">
          <button onClick={() => setArchive(false)}>取消</button>
          <button
            className="danger"
            disabled={busy}
            onClick={() =>
              void act(async () => {
                await onArchive();
                setArchive(false);
              })
            }
          >
            确认归档
          </button>
        </div>
      </Dialog>
    </section>
  );
}
