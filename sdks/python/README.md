# VeyraEval · Python SDK

Connect your own Agent to private evaluations. Your Agent runs in your environment; the platform assigns tasks and collects execution evidence.

## Install

Python 3.11 or newer. Install the published SDK from PyPI:

```bash
python -m pip install veyraeval-sdk==0.1.2
```

## Push runs from your application

Use `PushClient.login` with your platform account, then pass a project name to `start_run`. The platform creates the project on the first successful submission. See the [push integration guide](https://github.com/1993226871/veyraeval/blob/main/docs/guides/tasks-and-rule-sets.md) for a runnable order-agent example.

## Connect with a configuration file (0.1.1+)

SDK 0.1.1 adds `from_config`; 0.1.0 does not include this method. Install 0.1.1 or newer in your Agent's Python environment. For source development, run `python -m pip install ./sdks/python` from the repository root.

After registering an Agent, issue a Worker credential in the platform and choose **复制配置**. Save the JSON as `agent-eval.local.json` in your own Agent project:

```json
{
  "platform_url": "https://localhost:8443",
  "agent_id": "YOUR_AGENT_UUID",
  "agent_version": "v1",
  "api_key": "YOUR_WORKER_KEY"
}
```

Pass your existing task handler directly; the Agent ID is read from the file:

```python
worker = EvaluationWorker.from_config("agent-eval.local.json", handler=handle)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

Import `Event` and `EvaluationWorker` as in the example below. No username/password or `AGENT_EVAL_*` environment variables are needed. The file is read once at construction; changing/revoking credentials requires updating it and restarting the Worker. A credential belongs to an Agent and can serve multiple experiments, rather than being recreated for each task.

Optional string fields: `spool_path` (default `.agent-eval/spool.sqlite`), `ca_cert` (your development certificate), and `instance_id`. `agent_version` defaults to `v1`. Relative spool/certificate paths resolve from the configuration file's directory. TLS verification remains enabled. Use the platform address reachable from the Agent machine; `localhost` refers to that machine.

Add `agent-eval.local.json` and `.agent-eval/` to your Agent project's `.gitignore`. The private file contains the Worker key, not a model-provider key. Keep one spool per Worker process. The platform displays the secret and complete configuration only at issuance; it does not store another browser copy for later retrieval.

## Connect an Agent

Create an Agent and a scoped Worker credential in the platform. Set `AGENT_EVAL_URL`, `AGENT_EVAL_API_KEY`, `AGENT_EVAL_AGENT_VERSION`, and optionally `AGENT_EVAL_SPOOL` and `AGENT_EVAL_INSTANCE_ID` in your environment. Use HTTPS outside localhost.

```python
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, observe

@observe("tool", name="lookup")
def lookup(order_id):
    return {"amount": 100, "status": "paid"}

def handle(task):
    if task.cancelled:
        return AgentResult({"status": "cancelled"})
    return AgentResult(lookup(task.input["order_id"]))

worker = EvaluationWorker.from_env()
worker.register_agent("YOUR_AGENT_UUID", handle)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

The platform starts experiments. The SDK long-polls for work, confirms `started`, invokes your registered handler, renews the lease independently, flushes evidence, and submits the result. It never executes task-provided Python or applies task fields as SDK configuration. A handler may also be async. Check `task.cancelled` or wait on `task.cancellation` during long operations; Python threads cannot safely force-stop a noncooperative handler.

## Instrument calls

Use `@observe("model")`, `@observe("tool")`, or `with span("retrieval", "search", input_value=...): ...`. Inside a Span, assign `scope.output` when using the context manager. Async tasks inherit context through contextvars. Calls outside an active evaluation are passed through. Instrumentation captures visible inputs, outputs, error types, and elapsed time; it does not capture hidden model reasoning. Generator streaming is not instrumented automatically; wrap the actual consumption in an explicit span.

The default redactor masks credential-like keys and the current platform/attempt credentials, including occurrences in values and labels. It does not prove all domain-sensitive data is removed: shape the values you pass into explicit spans to suit your application. Final `AgentResult.output` goes to the platform unchanged and must be JSON-compatible; telemetry redaction does not rewrite that business result. Exception messages are excluded from traces.

## Evidence and recovery

- Events are saved before upload and removed only after a persisted acknowledgement. Retry keeps the same event IDs. The default spool budget is 100MiB of queued event/payload bytes; SQLite metadata and its bounded write journal require additional disk headroom. Restrict the spool directory to the Worker account and retain it across process restarts.
- Oversized inline payloads are queued as redacted JSON attachments, then linked by `payload_ref`. To bound capture, individual strings are limited to 1MiB, nesting to16 and collections to128 entries. Truncation remains explicit, including when an attachment replaces inline evidence. Capacity loss increments dropped_count and does not rewrite Agent output.
- `task.artifact(path, name=..., media_type=...)` uploads an explicitly selected file and includes its ID in completion. File contents are the caller's responsibility; automatic telemetry attachments have already passed through redaction.
- Durable execution markers contain token hashes. Repeated delivery does not invoke the handler again. A crash after the marker is written may leave execution unknown; the SDK provides no exactly-once guarantee and does not automatically replay business operations.
- Undelivered events remain available with their original IDs after restart. Resubmission requires a valid assignment/token, which is intentionally not stored in plaintext. The platform does not automatically reassign expired executions. This can leave old evidence locally queued until explicit recovery or cleanup; it never grants renewed execution authority.
- Shutdown signals cooperative cancellation and waits up to6s for pollers. A long poll or noncooperative handler may outlive this wait; daemon threads stop on process exit. Active resources are retained until a later `stop()` can close them. Flush attempts have a finite request timeout and cannot guarantee delivery during outages.

## Try the example

```bash
python examples/python-order-agent/main.py --local
```

Without `--local`, also set `AGENT_EVAL_AGENT_ID` and start a platform experiment using the order-query template. The example only queries a local dictionary.

## Develop

```bash
python -m pip install -r sdks/python/requirements-test.lock
python -m pytest sdks/python/tests -q
```

Schemas in `src/agent_eval/schemas` are generated copies of `contracts/schemas`; tests fail if they drift. After a contract change run `python sdks/python/scripts/sync_contracts.py` and re-run both Java and Python contract tests.

## Registry publication

Version `0.1.2` is available on [PyPI](https://pypi.org/project/veyraeval-sdk/0.1.2/) and [TestPyPI](https://test.pypi.org/project/veyraeval-sdk/0.1.2/). Version `0.1.1` introduced configuration-file connection and explicit development CA support. The first release, `0.1.0`, is available on [PyPI](https://pypi.org/project/veyraeval-sdk/0.1.0/) and [TestPyPI](https://test.pypi.org/project/veyraeval-sdk/0.1.0/). The Python import name remains `agent_eval`. Registry downloads, artifact SHA-256, dependencies, imports and packaged schemas have been verified in fresh environments. See the [publishing guide](https://github.com/1993226871/veyraeval/blob/main/docs/guides/sdk-publishing.md) for future releases. Contributors can still install from this repository with `python -m pip install ./sdks/python`. The Java SDK `0.1.1` is also published to Maven Central.

## License

Apache License 2.0; see the repository LICENSE and the LICENSE included with this distribution.
