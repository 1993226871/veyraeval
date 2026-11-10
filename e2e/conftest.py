"""Owned disposable Compose deployment. Credentials stay in its ignored private directory."""
import importlib.util
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sdks/python/src"))
sys.path.insert(0, str(ROOT / "scripts"))
from configure_deployment import configure
from manage_backup import manage
from agent_eval.transport import HttpTransport


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); return listener.getsockname()[1]


def wait_for(load, condition=bool, timeout=150):
    deadline = time.monotonic() + timeout
    while True:
        value = load()
        if condition(value): return value
        if time.monotonic() > deadline: raise AssertionError("Acceptance condition timed out")
        time.sleep(0.5)


class Platform:
    def __init__(self):
        self.project = "aev-e2e-" + uuid4().hex[:12]
        self.root = ROOT / ".tools/deployment-e2e" / self.project
        self.root.mkdir(mode=0o700, parents=True)
        self.children, self.logs, self.restore_projects = [], [], []
        self.runner_container = None
        self.https_port, self.s3_port = port(), port()
        openssl = os.environ.get("OPENSSL_BINARY") or shutil.which("openssl")
        if not openssl and os.name == "nt": openssl = "D:/Git/usr/bin/openssl.exe"
        self.env = configure(self.root, "acceptance-admin@example.test", self.https_port, self.s3_port, True, openssl)
        self.env["COMPOSE_PROJECT_NAME"] = self.project
        self.env["MODEL_ENDPOINT_ORIGINS"] = "http://judge:8089"
        self.runner_image = os.environ.get("RUNNER_TEST_IMAGE", "")
        self.ca = self.root / "tls/server.crt"
        self.tls = ssl.create_default_context(cafile=str(self.ca))
        self.base = f"https://localhost:{self.https_port}"
        self.override = self.root / "acceptance.json"
        self.override.write_text(json.dumps({"services": {"judge": {
            "image": self.project + "-ops:0.1.0", "entrypoint": ["python", "-B", "/opt/ops/judge_stub.py"],
            "networks": ["backend"], "read_only": True, "user": "10001:10001", "cap_drop": ["ALL"],
            "security_opt": ["no-new-privileges:true"], "mem_limit": "128m"}}}), encoding="utf-8")
        self.user_email, self.user_password = "acceptance-user@example.test", secrets.token_urlsafe(24)
        self.admin_password = secrets.token_urlsafe(24)
        self.models = {}

    def write_env(self):
        (self.root / ".env").write_text("\n".join(key + "=" + value for key, value in self.env.items()) + "\n", encoding="utf-8")
        os.chmod(self.root / ".env", 0o600)

    def command(self, *args, root=None, override=True):
        directory = root or self.root
        result = ["docker", "compose", "--env-file", str(directory / ".env"), "-f", str(ROOT / "deploy/compose.yaml")]
        if override and (directory / "acceptance.json").exists(): result += ["-f", str(directory / "acceptance.json")]
        return result + list(args)

    def compose(self, *args, root=None, override=True, timeout=300):
        with (self.root / "compose.log").open("ab") as output:
            result = subprocess.run(self.command(*args, root=root, override=override), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            with (self.root / "diagnostics.log").open("ab") as output:
                subprocess.run(self.command("logs", "--no-color", "--tail", "100", root=root, override=override), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=20)
                identities = subprocess.check_output(self.command("ps", "--all", "--quiet", root=root, override=override), cwd=ROOT, text=True).splitlines()
                for identity in identities:
                    if len(identity) == 64 and all(char in "0123456789abcdef" for char in identity):
                        subprocess.run(["docker", "inspect", "--format", "{{.Name}} {{json .State.Health}}", identity], stdout=output, stderr=subprocess.STDOUT, timeout=10)
            raise AssertionError(f"Compose operation failed; inspect {self.root / 'diagnostics.log'}")

    def start_child(self, label, command, environment):
        log = (self.root / (label + ".log")).open("wb"); self.logs.append(log)
        hidden = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        child = subprocess.Popen([str(value) for value in command], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, **hidden)
        self.children.append(child); return child

    def start(self):
        if self.runner_image:
            if sys.platform != "linux": raise AssertionError("Real runsc acceptance requires a qualified Linux VM")
            gateway = subprocess.check_output(["docker", "network", "inspect", "bridge", "--format", "{{(index .IPAM.Config 0).Gateway}}"], text=True).strip()
            runner_port = port()
            runner_root = self.root / "runner"
            runner_root.mkdir(mode=0o700)
            runner_env = dict(os.environ, RUNNER_IMAGE_DIGEST=self.runner_image, RUNNER_WORK_ROOT=str(runner_root),
                              RUNNER_INTERNAL_KEY=self.env["RUNNER_INTERNAL_KEY"], PYTHONPATH=str(ROOT / "runner"),
                              RUNNER_ARTIFACT_ORIGINS=f"https://localhost:{self.s3_port}", SSL_CERT_FILE="/etc/agenteval-ca.crt")
            with (self.root / "runner-build.log").open("wb") as output:
                subprocess.run(["docker", "build", "-f", str(ROOT / "deploy/Dockerfile.runner"), "-t", self.project + "-runner:0.1.0", str(ROOT / "runner")], check=True, stdout=output, stderr=subprocess.STDOUT, timeout=300)
                subprocess.run(["docker", "run", "--rm", "--user", "0:0", "--mount", "type=bind,src=" + str(runner_root) + ",dst=/state", "python:3.11-slim@sha256:b7a9e5f6a93d6d4a31f129bbd4bdee69efccb6ded0fb230d78aafaf37af98377", "python", "-c", "import os;os.chown('/state',10001,10001)"], check=True, stdout=output, stderr=subprocess.STDOUT, timeout=30)
            command = ["docker", "run", "--detach", "--label", "io.agenteval.e2e=" + self.project, "--network", "host", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "10001:10001",
                "--group-add", str(os.stat("/var/run/docker.sock").st_gid), "--memory", "512m", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001",
                "--mount", "type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock", "--mount", "type=bind,src=" + str(runner_root) + ",dst=" + str(runner_root),
                "--mount", "type=bind,src=" + str(self.ca) + ",dst=/etc/agenteval-ca.crt,readonly"]
            for key in ("RUNNER_IMAGE_DIGEST", "RUNNER_WORK_ROOT", "RUNNER_INTERNAL_KEY", "RUNNER_ARTIFACT_ORIGINS", "SSL_CERT_FILE"): command += ["--env", key]
            command += ["--entrypoint", "python", self.project + "-runner:0.1.0", "-B", "-m", "uvicorn", "agent_eval_runner.api:create_application", "--factory", "--host", gateway, "--port", str(runner_port), "--workers", "1", "--no-access-log"]
            self.runner_container = subprocess.check_output(command, env=runner_env, text=True).strip()
            assert len(self.runner_container) == 64 and all(char in "0123456789abcdef" for char in self.runner_container)
            self.env.update(RUNNER_ENABLED="true", RUNNER_BASE_URL=f"http://host.docker.internal:{runner_port}", RUNNER_IMAGE_DIGEST=self.runner_image)
        self.write_env()
        self.compose("build")
        self.compose("up", "-d", "--wait", "--wait-timeout", "180", timeout=240)
        self.admin = httpx.Client(base_url=self.base, verify=self.tls, trust_env=False, timeout=30)
        self.login(self.admin, self.env["BOOTSTRAP_ADMIN_EMAIL"], self.env["BOOTSTRAP_ADMIN_PASSWORD"])
        self.request(self.admin, "POST", "/auth/password", {"current_password": self.env["BOOTSTRAP_ADMIN_PASSWORD"], "new_password": self.admin_password})
        self.request(self.admin, "POST", "/admin/users", {"email": self.user_email, "temporary_password": self.user_password})
        self.user = httpx.Client(base_url=self.base, verify=self.tls, trust_env=False, timeout=30)
        self.login(self.user, self.user_email, self.user_password)
        next_password = secrets.token_urlsafe(24)
        self.request(self.user, "POST", "/auth/password", {"current_password": self.user_password, "new_password": next_password})
        self.user_password = next_password
        self.workspace = self.request(self.user, "GET", "/auth/me")["workspace_id"]
        self.previous_ca = os.environ.get("SSL_CERT_FILE")
        os.environ["SSL_CERT_FILE"] = str(self.ca)
        self.request(self.admin, "POST", f"/admin/workspaces/{self.workspace}/quota/adjustments", {"amount": "1", "reason": "Disposable acceptance budget", "idempotency_key": str(uuid4())})
        print("Verified HTTPS Compose deployment and private accounts ready.", flush=True)

    def login(self, client, email, password):
        csrf = client.get("/api/v1/auth/csrf").json()
        response = client.post("/api/v1/auth/login", data={"username": email, "password": password}, headers={csrf["header_name"]: csrf["token"]})
        assert response.status_code == 200, "Fixture login failed"
        assert any(cookie.secure for cookie in client.cookies.jar), "Session cookie must be Secure over verified TLS"

    def request(self, client, method, path, body=None):
        headers = {}
        if method != "GET":
            csrf = client.get("/api/v1/auth/csrf").json(); headers[csrf["header_name"]] = csrf["token"]
        response = client.request(method, "/api/v1" + path, json=body, headers=headers)
        assert 200 <= response.status_code < 300, f"Platform {method} {path.split('?')[0]} rejected: {response.status_code}"
        return None if response.status_code == 204 else response.json()

    def sql(self, query, root=None):
        return subprocess.check_output(self.command("exec", "-T", "postgres", "psql", "-U", "agenteval", "-d", "agenteval", "-Atc", query, root=root), cwd=ROOT, text=True).strip()

    def model(self, name="fixture-judge"):
        if name not in self.models:
            self.models[name] = self.request(self.admin, "POST", "/admin/models", {"name": name, "endpoint": "http://judge:8089/v1/chat/completions", "api_key": "disposable-test-key-only", "model": name,
                "input_token_bound": 4096, "max_output_tokens": 100, "max_request_bytes": 65536, "max_concurrent": 2,
                "bound_reference": "Local deterministic acceptance fixture", "input_rate": 1, "output_rate": 1, "denominator": 1})
        return self.models[name]

    def publish(self, kind, name, content):
        resource = self.request(self.user, "POST", "/catalog", {"kind": kind, "name": name, "content": content})
        return self.request(self.user, "POST", f"/catalog/{resource['id']}/versions")["id"]

    def resources(self, name, types=("RULE", "LLM", "PYTHON"), repeat=1, model_name="fixture-judge", python_code=None):
        agent = self.request(self.user, "POST", "/agents", {"name": name, "version": "v1"})["id"]
        credential = self.request(self.user, "POST", "/worker-credentials", {"agent_ids": [agent]})
        dataset = self.publish("DATASET", name, {"cases": [{"instruction": "Find order A and report its amount and status", "input": {"order_id": "A"}, "expected": {"amount": 100, "status": "paid"}}]})
        definitions = {}
        for kind in types:
            config = {"operator": "eq", "selector": "/output/amount", "expected": 100}
            if kind == "PYTHON":
                config = {"code": 'def evaluate(context):\n    return {"status":"SCORED","score":1 if context["output"].get("amount")==100 else 0,"reason":"Custom amount check","evidence_refs":["output"]}\n'}
                if python_code is not None: config["code"] = python_code
            elif kind == "LLM":
                model = self.model(model_name)
                config = {"model_profile_id": model["id"], "price_version_id": model["price"]["id"], "prompt_version": "rubric-v1", "max_input_bytes": 16384, "max_output_tokens": 50,
                          "rubric": {"metric": "Order objective completion", "levels": [{"grade": grade, "description": f"Objective coverage grade {grade}"} for grade in range(5)]}}
            definitions[self.publish("EVALUATOR", name + " " + kind, {"type": kind, "criterion_id": kind.lower(), "threshold": 0.75, "required_evidence": ["output"], "config": config})] = kind
        suite = self.publish("SUITE", name, {"entries": [{"evaluator_version": version, "dimension": "process" if kind == "PYTHON" else "outcome", "required_to_pass": True, "weight": 1} for version, kind in definitions.items()]})
        experiment = self.request(self.user, "POST", "/experiments", {"agent_id": agent, "agent_version": "v1", "dataset_version_id": dataset, "suite_version_id": suite,
            "repeat_count": repeat, "deadline": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(), "idempotency_key": str(uuid4())})["id"]
        return {"agent": agent, "credential": credential, "experiment": experiment, "definitions": definitions}

    def transport(self, resources):
        return HttpTransport(self.base, resources["credential"]["secret"], client=httpx.Client(verify=self.tls, trust_env=False, timeout=30))

    def assignment(self, resources):
        transport = self.transport(resources)
        worker = transport.register(resources["agent"])
        assignment = transport.claim(worker, resources["agent"])
        assert assignment is not None
        return transport, assignment

    def java_command(self):
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        keytool = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
        truststore = self.root / "sdk-trust.p12"
        if not truststore.exists():
            subprocess.run([keytool, "-importcert", "-noprompt", "-alias", "fixture", "-file", str(self.ca), "-keystore", str(truststore), "-storepass", "acceptance-only", "-storetype", "PKCS12"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        repo = ROOT / ".tools/m2" if (ROOT / ".tools/m2").exists() else Path.home() / ".m2/repository"
        libraries = ["com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar", "com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar", "com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar", "com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.21.4/jackson-datatype-jsr310-2.21.4.jar", "org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar", "org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar"]
        classpath = os.pathsep.join(str(path) for path in [ROOT / "examples/java-order-agent/target/classes", ROOT / "sdks/java/target/classes", ROOT / "platform/protocol/target/classes", *(repo / item for item in libraries)])
        return [java, "-Xmx128m", "-Djavax.net.ssl.trustStore=" + str(truststore), "-Djavax.net.ssl.trustStorePassword=acceptance-only", "-cp", classpath, "io.agenteval.examples.OrderAgent"]

    def start_sdk(self, language, resources, label=None):
        environment = dict(os.environ, AGENT_EVAL_URL=self.base, AGENT_EVAL_API_KEY=resources["credential"]["secret"], AGENT_EVAL_AGENT_ID=resources["agent"],
            AGENT_EVAL_AGENT_VERSION="v1", AGENT_EVAL_SPOOL=str(self.root / ((label or language) + ".sqlite")), PYTHONPATH=str(ROOT / "sdks/python/src"), SSL_CERT_FILE=str(self.ca), PYTHONUNBUFFERED="1")
        command = self.java_command() if language == "java" else [sys.executable, ROOT / "examples/python-order-agent/main.py"]
        return self.start_child(label or language, command, environment)

    def trial_report(self, resources):
        path = "/reports/experiments/" + resources["experiment"]
        return wait_for(lambda: self.request(self.user, "GET", path + "/trials?limit=1")["items"], lambda values: bool(values) and values[0]["evaluation_state"] in {"COMPLETED", "PARTIAL"})[0]

    def sdk_report(self, language):
        if not self.runner_image: raise AssertionError("NOT VERIFIED: triple-evaluator SDK acceptance requires RUNNER_TEST_IMAGE on a qualified Linux/runsc node")
        resources = self.resources("SDK " + language)
        self.start_sdk(language, resources)
        trial = self.trial_report(resources)
        assert len(trial["criteria"]) == 3 and all(item["status"] == "SCORED" for item in trial["criteria"]), "Every evaluator must actually score"
        trace = self.request(self.user, "GET", "/traces/" + trial["trial_id"] + "?limit=100")
        return {"evaluator_types": set(resources["definitions"].values()), "verdict": trial["verdict"],
            "root_spans": sum(span["kind"] == "agent" and span["parent_span_id"] is None for span in trace["spans"]), "tool_spans": sum(span["kind"] == "tool" for span in trace["spans"])}

    def browser(self):
        node = os.environ.get("E2E_NODE") or shutil.which("node")
        if not node: raise AssertionError("Node and installed Playwright Chromium are required")
        openssl = os.environ.get("OPENSSL_BINARY") or shutil.which("openssl") or "D:/Git/usr/bin/openssl.exe"
        public_key = subprocess.check_output([openssl, "x509", "-in", str(self.ca), "-pubkey", "-noout"])
        der = subprocess.check_output([openssl, "pkey", "-pubin", "-outform", "DER"], input=public_key)
        pin = base64.b64encode(hashlib.sha256(der).digest()).decode()
        environment = dict(os.environ, WEB_BASE_URL=self.base, WEB_ACCEPTANCE_ADMIN=self.env["BOOTSTRAP_ADMIN_EMAIL"], WEB_ACCEPTANCE_PASSWORD=self.admin_password,
            WEB_ACCEPTANCE_ROOT=str(self.root), WEB_ACCEPTANCE_PYTHON=sys.executable, WEB_ACCEPTANCE_CA=str(self.ca), WEB_TLS_SPKI=pin, NODE_EXTRA_CA_CERTS=str(self.ca))
        subprocess.run([node, str(ROOT / "web/node_modules/@playwright/test/cli.js"), "test", "workspace-evaluation.spec.ts"], cwd=ROOT / "web", env=environment, check=True)
        print("HTTPS browser provisioning, CNY, SDK upload, automatic/manual evaluation, comparison and deletion passed.", flush=True)

    def capacity(self):
        for credential in self.request(self.user, "GET", "/worker-credentials?limit=100"):
            if not credential["revoked"]: self.request(self.user, "DELETE", "/worker-credentials/" + credential["id"])
        configured = self.request(self.admin, "GET", f"/admin/workspaces/{self.workspace}/resource-limits")["limits"]
        configured.update(max_online_workers=50, max_running_trials=20)
        self.request(self.admin, "POST", f"/admin/workspaces/{self.workspace}/resource-limits", {"limits": configured, "reason": "Disposable capacity acceptance"})
        resources = self.resources("Capacity acceptance", types=("RULE",), repeat=20)
        timings, report_timings = [], []
        with httpx.Client(base_url=self.base + "/api/v1", verify=self.tls, trust_env=False, timeout=30, headers={"Authorization": "Bearer " + resources["credential"]["secret"]}) as control:
            def register(index):
                response = control.post("/workers/register", json={"agent_id": resources["agent"], "agent_version": "v1", "instance_id": "capacity-" + str(index), "protocol_version": "1.0", "sdk_language": "PYTHON", "sdk_version": "0.1.0", "max_concurrency": 1})
                assert response.status_code == 200
                return response.json()["worker_id"]
            with ThreadPoolExecutor(max_workers=10) as pool: workers = list(pool.map(register, range(50)))
            response = control.post("/workers/register", json={"agent_id": resources["agent"], "agent_version": "v1", "instance_id": "capacity-over-limit", "protocol_version": "1.0", "sdk_language": "PYTHON", "sdk_version": "0.1.0", "max_concurrency": 1})
            assert response.status_code == 429
            online = int(self.sql("select count(*) from worker_instances w join worker_credentials c on c.id=w.credential_id where not c.revoked and w.last_seen_at>now()-interval '60 seconds'"))
            def claim(worker):
                response = control.post(f"/workers/{worker}/claim", json={"worker_id": worker, "agent_id": resources["agent"], "agent_version": "v1", "max_wait_seconds": 0})
                assert response.status_code in (200, 204)
                return response.json() if response.status_code == 200 else None
            with ThreadPoolExecutor(max_workers=20) as pool: assignments = [value for value in pool.map(claim, workers) if value is not None]
            assert len(assignments) == 20
            running = int(self.sql("select count(*) from trials where status in ('LEASED','RUNNING')"))
            def send(assignment):
                prefix = "/trials/" + assignment["trial_id"]
                headers = {"X-Attempt-Token": assignment["attempt_token"]}
                assert control.post(prefix + "/started", headers=headers).status_code == 204
                span = uuid4().hex[:16]
                events = [{"schema_version": "1.0", "event_id": str(uuid4()), "trial_id": assignment["trial_id"], "trace_id": assignment["trace_id"], "span_id": span,
                    "parent_span_id": None, "span_kind": "agent", "name": "Capacity fixture", "event_type": "span.started" if sequence == 1 else "span.completed" if sequence == 500 else "log",
                    "sequence": sequence, "occurred_at": datetime.now(timezone.utc).isoformat(), "payload": {"duration_ms": 100, "value": "x" * 128}, "capture": {"redacted": False, "truncated": False}} for sequence in range(1, 501)]
                for offset in range(0, 500, 100):
                    start = time.monotonic()
                    response = control.post(prefix + "/events:batch", json={"events": events[offset:offset + 100]}, headers=headers)
                    timings.append((time.monotonic() - start) * 1000)
                    assert response.status_code == 200, "Capacity telemetry request failed"
                assert control.post(prefix + "/complete", json={"status": "SUCCEEDED", "output": {"amount": 100}, "final_sequence": 500, "event_count": 500, "dropped_count": 0, "artifact_ids": []}, headers=headers).status_code == 204
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=20) as pool: list(pool.map(send, assignments))
        accepted = int(self.sql("select count(*) from trace_events v join trials t on t.id=v.trial_id where t.experiment_id='" + resources["experiment"] + "'"))
        elapsed = time.monotonic() - started
        graded = wait_for(lambda: self.request(self.user, "GET", "/reports/experiments/" + resources["experiment"]), lambda report: report["passed_attempts"] == 20)
        assert graded["determinable_attempts"] == 20 and graded["efficiency"]["observed_durations"] == 20
        complete = int(self.sql("select count(*) from evidence_snapshots s join trials t on t.id=s.trial_id where t.experiment_id='" + resources["experiment"] + "' and s.completeness='COMPLETE'"))
        assert complete == 20
        for _ in range(10):
            start = time.monotonic(); self.request(self.user, "GET", "/reports/experiments/" + resources["experiment"])
            report_timings.append((time.monotonic() - start) * 1000)
        identities = subprocess.check_output(self.command("ps", "--quiet"), cwd=ROOT, text=True).splitlines()
        memory = subprocess.check_output(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}", *identities], text=True)
        percentile = lambda values: round(sorted(values)[max(0, int(len(values) * .95) - 1)], 2)
        result = {"online_workers": online, "running_trials": running, "accepted_events": accepted, "unexpected_errors": 0, "fully_graded_attempts": 20, "complete_snapshots": complete,
            "batch_p95_ms": percentile(timings), "report_p95_ms": percentile(report_timings), "event_ingest_seconds": round(elapsed, 2), "memory_snapshot": memory,
            "limits": "Single fixture, small events, no paid provider; not a sustained throughput or peak RSS guarantee"}
        (self.root / "capacity-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        self.request(self.user, "DELETE", "/worker-credentials/" + resources["credential"]["id"])
        print("Capacity acceptance metrics recorded without private event bodies.", flush=True)
        return result

    def crashed_execution_calls(self):
        resources = self.resources("Unknown SDK execution", types=("RULE",))
        transport, assignment = self.assignment(resources)
        payload = self.root / "crash-input.json"
        payload.write_text(json.dumps({"base": self.base, "key": resources["credential"]["secret"], "agent": resources["agent"], "assignment": assignment}), encoding="utf-8")
        os.chmod(payload, 0o600)
        script = self.root / "crash_worker.py"
        script.write_text('''import json, os, sys
from pathlib import Path
from agent_eval import AgentResult, EvaluationWorker
from agent_eval.transport import HttpTransport
from agent_eval.spool import FileSpool
root=Path(sys.argv[1]); data=json.loads((root/"crash-input.json").read_text())
def handler(context):
    with (root/"handler-calls.txt").open("a") as output: output.write("called\\n"); output.flush(); os.fsync(output.fileno())
    if sys.argv[2]=="crash": os._exit(73)
    return AgentResult({"amount":100})
worker=EvaluationWorker(HttpTransport(data["base"],data["key"]),FileSpool(root/"crash.sqlite"))
worker.register_agent(data["agent"],handler)
worker.execute(data["agent"],data["assignment"])
worker.stop()
''', encoding="utf-8")
        environment = dict(os.environ, PYTHONPATH=str(ROOT / "sdks/python/src"), SSL_CERT_FILE=str(self.ca))
        child = self.start_child("crash-first", [sys.executable, script, self.root, "crash"], environment)
        assert child.wait(timeout=30) == 73
        resumed = self.start_child("crash-restarted", [sys.executable, script, self.root, "resume"], environment)
        assert resumed.wait(timeout=30) == 0
        count = len((self.root / "handler-calls.txt").read_text().splitlines())
        self.sql("update trial_leases set expires_at=now()-interval '1 second' where trial_id='" + assignment["trial_id"] + "'")
        wait_for(lambda: self.sql("select status from trials where id='" + assignment["trial_id"] + "'"), lambda status: status == "EXECUTION_UNKNOWN", timeout=20)
        transport.close()
        return count

    def unknown_model_restart(self):
        resources = self.resources("Unknown Judge request", types=("LLM",), model_name="disconnect-after-accept")
        before = int(self.request(self.user, "GET", "/quota")["consumed"])
        self.start_sdk("python", resources, "unknown-judge-sdk")
        pending = wait_for(lambda: self.request(self.admin, "GET", "/admin/model-calls"), lambda values: any(value["workspace_id"] == self.workspace for value in values))
        call = next(value for value in pending if value["workspace_id"] == self.workspace)
        call_id = call["call_id"]
        self.compose("restart", "worker")
        self.compose("up", "-d", "--wait", "worker")
        assert int(self.sql("select count(*) from model_calls where call_id='" + call_id + "'")) == 1
        body = {"input_tokens": "10", "output_tokens": "5", "reason": "Verified deterministic local stub usage", "idempotency_key": str(uuid4())}
        self.request(self.admin, "POST", f"/admin/model-calls/{call_id}/reconciliation", body)
        self.request(self.admin, "POST", f"/admin/model-calls/{call_id}/reconciliation", body)
        self.compose("restart", "worker")
        self.compose("up", "-d", "--wait", "worker")
        output = subprocess.check_output(self.command("exec", "-T", "judge", "python", "-c", "import urllib.request;print(urllib.request.urlopen('http://localhost:8089/counts').read().decode())"), cwd=ROOT, text=True)
        counts = json.loads(output)
        wallet = self.request(self.user, "GET", "/quota")
        return {"provider_calls": counts[call_id], "consumed_delta": int(wallet["consumed"]) - before, "reserved_after": int(wallet["reserved"])}

    def backup_restore(self):
        resources = self.resources("Restore evidence", types=("RULE",))
        transport, assignment = self.assignment(resources)
        content = b"private acceptance artifact: verified bytes"
        transport.started(assignment)
        artifact = transport.upload_bytes(assignment, content, "private-result.txt", "text/plain")
        transport.complete(assignment, {"status": "SUCCEEDED", "output": {"amount": 100}, "final_sequence": 0, "event_count": 0, "dropped_count": 0, "artifact_ids": [artifact]})
        transport.close()
        download = self.request(self.user, "GET", f"/artifacts/{artifact}/download")["url"]
        with httpx.Client(verify=self.tls, trust_env=False) as reader:
            response = reader.get(download); assert response.status_code == 200 and response.content == content
        row_query = "select (select count(*) from users),(select count(*) from experiments),(select count(*) from artifacts),(select count(*) from quota_ledger)"
        before = self.sql(row_query)
        backup_dir = self.root / "backup"
        manage("backup", self.root / ".env", backup_dir, (self.override,))
        manifest = json.loads((backup_dir / "objects/manifest.json").read_text(encoding="utf-8"))
        assert len(manifest["objects"]) >= 2
        restored = self.root / "restore"
        restored.mkdir(mode=0o700)
        values = dict(self.env, COMPOSE_PROJECT_NAME=self.project + "-restore", HTTPS_PORT=str(port()), S3_HTTPS_PORT=str(port()))
        values["PUBLIC_S3_ENDPOINT"] = "https://localhost:" + values["S3_HTTPS_PORT"]
        (restored / ".env").write_text("\n".join(key + "=" + value for key, value in values.items()) + "\n", encoding="utf-8")
        os.chmod(restored / ".env", 0o600)
        (restored / "acceptance.json").write_text(json.dumps({"services": {name: {"image": self.project + "-" + ("ops" if name == "storage-init" else name) + ":0.1.0"} for name in ("api", "worker", "web", "storage-init")} | {"migrate": {"image": self.project + "-api:0.1.0"}}}), encoding="utf-8")
        self.restore_projects.append(restored)
        self.compose("up", "-d", "--wait", "postgres", "s3", "redis", root=restored)
        self.compose("run", "--rm", "--no-deps", "storage-init", "init", root=restored)
        manage("restore", restored / ".env", backup_dir, (restored / "acceptance.json",))
        after = self.sql(row_query, root=restored)
        self.compose("up", "-d", "--no-deps", "api", root=restored)
        self.compose("up", "-d", "--wait", "web", root=restored)
        with httpx.Client(base_url="https://localhost:" + values["HTTPS_PORT"], verify=self.tls, trust_env=False, timeout=30) as reader:
            self.login(reader, self.user_email, self.user_password)
            detail = self.request(reader, "GET", "/experiments/" + resources["experiment"])
            restored_url = self.request(reader, "GET", f"/artifacts/{artifact}/download")["url"]
            response = reader.get(restored_url)
            assert response.status_code == 200
            equal_bytes = response.content == content
        # Repeat restore must refuse the populated database; never drop existing private data.
        with pytest.raises(ValueError, match="database must be empty"):
            manage("restore", restored / ".env", backup_dir, (restored / "acceptance.json",))
        self.compose("up", "-d", "--wait", "api", "worker", "web")
        result = {"database_rows_equal": before == after, "object_bytes_equal": equal_bytes, "private_data_restored": detail["id"] == resources["experiment"]}
        (self.root / "restore-result.json").write_text(json.dumps(result), encoding="utf-8")
        print("Database and private object backup restored into a fresh owned deployment.", flush=True)
        return result

    def stop(self):
        if (self.root / ".env").exists():
            with (self.root / "service-final.log").open("wb") as output:
                subprocess.run(self.command("logs", "--no-color", "--tail", "150"), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=20)
        for child in reversed(self.children):
            if child.poll() is None:
                child.terminate()
                try: child.wait(timeout=10)
                except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)
        for log in self.logs: log.close()
        if self.runner_container:
            owner = subprocess.check_output(["docker", "inspect", "--format", '{{index .Config.Labels "io.agenteval.e2e"}}', self.runner_container], text=True).strip()
            if owner != self.project: raise AssertionError("Refusing to remove an unowned Runner")
            with (self.root / "runner-final.log").open("wb") as output:
                subprocess.run(["docker", "logs", "--tail", "100", self.runner_container], stdout=output, stderr=subprocess.STDOUT, timeout=10)
            subprocess.run(["docker", "stop", "--time", "15", self.runner_container], check=True, stdout=subprocess.DEVNULL, timeout=25)
            subprocess.run(["docker", "rm", self.runner_container], check=True, stdout=subprocess.DEVNULL, timeout=10)
        for directory in [*self.restore_projects, self.root]:
            env_file = directory / ".env"
            if not env_file.exists(): continue
            values = dict(line.split("=", 1) for line in env_file.read_text(encoding="utf-8").splitlines() if "=" in line)
            if not values.get("COMPOSE_PROJECT_NAME", "").startswith("aev-e2e-"): raise AssertionError("Refusing cleanup of unowned Compose project")
            with (self.root / "cleanup.log").open("ab") as output:
                subprocess.run(self.command("down", "--volumes", "--remove-orphans", root=directory), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=90, check=True)
        for name in ("admin", "user"):
            client = getattr(self, name, None)
            if client: client.close()
        if hasattr(self, "previous_ca"):
            if self.previous_ca is None: os.environ.pop("SSL_CERT_FILE", None)
            else: os.environ["SSL_CERT_FILE"] = self.previous_ca


@pytest.fixture(scope="session")
def platform():
    instance = Platform()
    try:
        instance.start(); yield instance
    finally:
        instance.stop()
