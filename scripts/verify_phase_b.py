"""Verify both SDK examples against disposable local PostgreSQL/API/worker processes.

Run after `mvn verify` using a Python environment with the SDK dependencies.
All credentials are generated in memory, never printed or written to the result.
Only processes and the Docker container created by this invocation are stopped.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import httpx

ROOT = Path(__file__).resolve().parents[1]


class DemoFailure(RuntimeError):
    pass


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--output", type=Path, default=ROOT / ".tools/phase-b-demo-result.json")
    parser.add_argument("--web-smoke", action="store_true", help="Also verify the browser with a disposable ordinary account")
    args = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java or not shutil.which("docker"):
        raise DemoFailure("JDK21 and a running Docker daemon are required")
    api_jars = list((ROOT / "platform/api/target").glob("*.jar"))
    worker_jars = list((ROOT / "platform/worker/target").glob("*.jar"))
    if len(api_jars) != 1 or len(worker_jars) != 1:
        raise DemoFailure("Build executable API/worker jars with Maven verify first")
    libraries = [
        "com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar",
        "com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar",
        "com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar",
        "com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.21.4/jackson-datatype-jsr310-2.21.4.jar",
        "org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar",
        "org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar",
    ]
    jar_paths = [args.maven_repo.resolve() / item for item in libraries]
    if any(not path.is_file() for path in jar_paths):
        raise DemoFailure("Required SDK jars missing; pass the Maven repository used for the build")
    classpath = os.pathsep.join(str(path) for path in [ROOT / "examples/java-order-agent/target/classes", ROOT / "sdks/java/target/classes", ROOT / "platform/protocol/target/classes", *jar_paths])
    run = ROOT / ".tools/phase-b-demo" / uuid4().hex
    run.mkdir(parents=True, mode=0o700)
    children, logs, container = [], [], None
    hidden = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}

    def start(label, command, environment):
        log = (run / (label + ".log")).open("wb")
        logs.append(log)
        process = subprocess.Popen([str(item) for item in command], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, **hidden)
        children.append(process)
        return process

    try:
        database_password = secrets.token_urlsafe(24)
        docker_env = dict(os.environ, POSTGRES_PASSWORD=database_password)
        container = subprocess.check_output(["docker", "run", "--detach", "--rm", "--name", "agent-eval-phase-b-" + uuid4().hex[:12], "--label", "io.agenteval.demo=phase-b", "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_USER=agenteval", "--env", "POSTGRES_DB=agenteval", "--publish", "127.0.0.1::5432", "postgres:17.11@sha256:b8faeff7712663c2773a190181e1246e37e364b887611c04658c28b44e2408fe"], env=docker_env, text=True, **hidden).strip()
        if len(container) != 64 or any(char not in "0123456789abcdef" for char in container):
            raise DemoFailure("Docker did not return a container identity")
        database_port = subprocess.check_output(["docker", "port", container, "5432/tcp"], text=True, **hidden).strip().rsplit(":", 1)[1]
        deadline = time.monotonic() + 45
        while subprocess.run(["docker", "exec", container, "pg_isready", "-U", "agenteval"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **hidden).returncode:
            if time.monotonic() > deadline:
                raise DemoFailure("Disposable PostgreSQL did not become ready")
            time.sleep(0.2)
        api_port = available_port()
        base = "http://127.0.0.1:" + str(api_port)
        temporary_password, password = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
        email = "phase-b-demo@test.local"
        environment = dict(os.environ, DATABASE_URL="jdbc:postgresql://127.0.0.1:" + database_port + "/agenteval", DATABASE_USER="agenteval", DATABASE_PASSWORD=database_password, SECURE_COOKIES="false", BOOTSTRAP_ADMIN_EMAIL=email, BOOTSTRAP_ADMIN_PASSWORD=temporary_password)
        api = start("api", [java, "-Xms32m", "-Xmx256m", "-jar", api_jars[0], "--server.address=127.0.0.1", "--server.port=" + str(api_port), "--app.bootstrap.enabled=true"], environment)
        with httpx.Client(base_url=base, timeout=10, trust_env=False) as client:
            deadline = time.monotonic() + 60
            while True:
                if api.poll() is not None:
                    raise DemoFailure("API exited; inspect the private demo log")
                try:
                    ready = client.get("/api/v1/auth/csrf")
                    if ready.status_code == 200:
                        break
                except httpx.HTTPError:
                    pass
                if time.monotonic() > deadline:
                    raise DemoFailure("API startup timeout")
                time.sleep(0.25)

            def request(method, path, body=None, form=None):
                headers = {}
                if method != "GET":
                    csrf = client.get("/api/v1/auth/csrf").json()
                    headers[csrf["header_name"]] = csrf["token"]
                response = client.request(method, path, headers=headers, json=body, data=form)
                if response.status_code < 200 or response.status_code >= 300:
                    raise DemoFailure("Demo request rejected: " + method + " " + path + " status=" + str(response.status_code))
                return response.json() if response.content else None

            # Tomcat accepts requests shortly before bootstrap ApplicationRunner completes.
            login_deadline = time.monotonic() + 10
            while True:
                csrf = client.get("/api/v1/auth/csrf").json()
                response = client.post("/api/v1/auth/login", data={"username": email, "password": temporary_password}, headers={csrf["header_name"]: csrf["token"]})
                if response.status_code == 200:
                    break
                if response.status_code != 401 or time.monotonic() > login_deadline:
                    raise DemoFailure("Generated administrator could not log in")
                time.sleep(0.25)
            request("POST", "/api/v1/auth/password", {"current_password": temporary_password, "new_password": password})
            if args.web_smoke:
                node = shutil.which("node")
                if not node:
                    raise DemoFailure("Node.js is required for browser verification")
                web_port = available_port()
                browser_email = "web-smoke@test.local"
                browser_password, browser_next = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
                request("POST", "/api/v1/admin/users", {"email": browser_email, "temporary_password": browser_password})
                web_environment = dict(os.environ, API_TARGET=base)
                start("web", [node, ROOT / "web/node_modules/vite/bin/vite.js", str(ROOT / "web"), "--host", "127.0.0.1", "--port", str(web_port), "--strictPort"], web_environment)
                web_base = "http://127.0.0.1:" + str(web_port)
                ready_until = time.monotonic() + 30
                while True:
                    try:
                        if httpx.get(web_base, trust_env=False).status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    if time.monotonic() > ready_until:
                        raise DemoFailure("Web startup timeout")
                    time.sleep(0.25)
                browser_environment = dict(os.environ, WEB_BASE_URL=web_base, WEB_TEST_EMAIL=browser_email, WEB_TEST_PASSWORD=browser_password, WEB_TEST_NEW_PASSWORD=browser_next)
                # No traces, video or failure screenshots: the one-time secret must not be persisted.
                subprocess.run([node, str(ROOT / "web/node_modules/@playwright/test/cli.js"), "test", "private-workspace.spec.ts"], cwd=ROOT / "web", env=browser_environment, check=True, **hidden)
                print("Browser session, CSRF and private resource actions verified.", flush=True)
            worker_env = {key: value for key, value in environment.items() if not key.startswith("BOOTSTRAP_ADMIN_")}
            start("worker", [java, "-Xms32m", "-Xmx256m", "-jar", worker_jars[0]], worker_env)
            if args.web_smoke:
                push_environment = dict(os.environ, WEB_BASE_URL=web_base, WEB_ACCEPTANCE_ADMIN=email,
                    WEB_ACCEPTANCE_PASSWORD=password, WEB_ACCEPTANCE_PYTHON=sys.executable)
                subprocess.run([node, str(ROOT / "web/node_modules/@playwright/test/cli.js"), "test", "workspace-evaluation.spec.ts"],
                    cwd=ROOT / "web", env=push_environment, check=True, **hidden)
                print("SDK push, automatic/manual scoring and trend verified in browser.", flush=True)
            examples = []
            for language in ("python", "java"):
                agent = request("POST", "/api/v1/agents", {"name": "phase-b-" + language, "version": "v1"})
                bundle = request("POST", "/api/v1/templates/starter/copy")
                credential = request("POST", "/api/v1/worker-credentials", {"agent_ids": [agent["id"]]})
                experiment = request("POST", "/api/v1/experiments", {"agent_id": agent["id"], "agent_version": "v1", "dataset_version_id": bundle["dataset_version"]["id"], "suite_version_id": bundle["suite_version"]["id"], "repeat_count": 1, "deadline": (datetime.now(timezone.utc) + timedelta(seconds=180)).isoformat(), "idempotency_key": uuid4().hex})
                sdk_env = dict(os.environ, AGENT_EVAL_URL=base, AGENT_EVAL_API_KEY=credential["secret"], AGENT_EVAL_AGENT_VERSION="v1", AGENT_EVAL_AGENT_ID=agent["id"], AGENT_EVAL_SPOOL=str(run / (language + ".sqlite")), PYTHONPATH=str(ROOT / "sdks/python/src"))
                command = [sys.executable, ROOT / "examples/python-order-agent/main.py"] if language == "python" else [java, "-Xms16m", "-Xmx128m", "-cp", classpath, "io.agenteval.examples.OrderAgent"]
                process = start(language, command, sdk_env)
                examples.append((language, experiment["id"], process))
            print("Both SDK Workers launched; waiting for evidence snapshots and grading.", flush=True)
            completed = {}
            deadline, next_update = time.monotonic() + 110, time.monotonic() + 10
            while len(completed) != 2:
                for language, experiment, process in examples:
                    if language in completed:
                        continue
                    if process.poll() is not None:
                        raise DemoFailure(language + " SDK exited before verification")
                    report = request("GET", "/api/v1/reports/experiments/" + experiment)
                    if report["passed_attempts"] != 1 or report["determinable_attempts"] != 1:
                        continue
                    page = request("GET", "/api/v1/reports/experiments/" + experiment + "/trials?limit=1")
                    trial = page["items"][0]
                    trace = request("GET", "/api/v1/traces/" + trial["trial_id"] + "?limit=100")
                    roots = [span for span in trace["spans"] if span["kind"] == "agent" and span["parent_span_id"] is None]
                    if trial["verdict"] != "PASS" or len(roots) != 1 or roots[0]["status"] != "COMPLETED" or len(trace["spans"]) != 2:
                        raise DemoFailure(language + " trace/report invariant failed")
                    if trial["criteria"][0]["score"] != 1 or not trial["snapshot_id"]:
                        raise DemoFailure(language + " scoring invariant failed")
                    if report["planned_attempts"] != 1 or report["started_attempts"] != 1 or report["efficiency"]["observed_durations"] != 1 or report["efficiency"]["source"] != "worker_reported":
                        raise DemoFailure(language + " denominator or frozen efficiency invariant failed")
                    completed[language] = {"sdk": language, "verdict": trial["verdict"], "score": trial["criteria"][0]["score"], "span_count": len(trace["spans"]), "root_count": len(roots), "planned_attempts": report["planned_attempts"], "determinable_attempts": report["determinable_attempts"], "started_attempts": report["started_attempts"], "observed_durations": report["efficiency"]["observed_durations"], "evaluation_state": trial["evaluation_state"]}
                    print(language + ": PASS; root trace and rule score verified.", flush=True)
                if time.monotonic() > deadline:
                    raise DemoFailure("Timed out waiting for both SDK reports; inspect private logs")
                if time.monotonic() > next_update:
                    print("Waiting for remaining evidence/grading (30s terminal grace applies).", flush=True)
                    next_update = time.monotonic() + 10
                time.sleep(0.25)
            if args.web_smoke:
                browser_environment = dict(os.environ, WEB_BASE_URL=web_base, WEB_REPORT_EMAIL=email, WEB_REPORT_PASSWORD=password, WEB_REPORT_IDS=",".join(item[1] for item in examples))
                subprocess.run([node, str(ROOT / "web/node_modules/@playwright/test/cli.js"), "test", "report.spec.ts"], cwd=ROOT / "web", env=browser_environment, check=True, **hidden)
                print("Browser report, evidence, review, export and comparison verified.", flush=True)
            result = {"verified_at": datetime.now(timezone.utc).isoformat(), "postgres": "17.11", "results": list(completed.values())}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("Phase B verification complete. Temporary services will be stopped.", flush=True)
    finally:
        for process in reversed(children):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in logs:
            log.close()
        if container and len(container) == 64 and all(char in "0123456789abcdef" for char in container):
            subprocess.run(["docker", "rm", "--force", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **hidden)


if __name__ == "__main__":
    try:
        main()
    except (DemoFailure, httpx.HTTPError, subprocess.SubprocessError) as failure:
        raise SystemExit(str(failure)) from None
