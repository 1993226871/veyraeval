"""HTTP control plane. Never include response bodies, URLs, or credentials in errors."""
import hashlib
import json
import ssl
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4
import httpx
from .contracts import validate_message


class ProtocolError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__("Platform request rejected (status %d)" % status)


class TransportUnavailable(RuntimeError):
    pass


class HttpTransport:
    heartbeat_seconds = 15

    def __init__(self, base_url, api_key, agent_version="v1", instance_id=None, client=None, *, ca_cert=None):
        url = urlsplit(base_url)
        if url.scheme not in {"https", "http"} or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("Invalid platform URL")
        if url.scheme == "http" and url.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Use HTTPS for remote platforms")
        if not api_key or not agent_version:
            raise ValueError("Missing worker configuration")
        self.base_url = base_url.rstrip("/")
        self.agent_version = agent_version
        self.instance_id = instance_id or str(uuid4())
        self._client = client or httpx.Client(timeout=httpx.Timeout(25, connect=5), follow_redirects=False,
                                              verify=ssl.create_default_context(cafile=ca_cert) if ca_cert else True)
        self._key = api_key

    def _request(self, method, path, body=None, assignment=None):
        headers = {"Authorization": "Bearer " + self._key}
        if assignment:
            headers["X-Attempt-Token"] = assignment["attempt_token"]
        try:
            with self._client.stream(method, self.base_url + "/api/v1" + path, json=body, headers=headers) as response:
                if response.status_code == 204:
                    return None
                if response.status_code < 200 or response.status_code >= 300:
                    raise ProtocolError(response.status_code)
                raw = bytearray()
                for chunk in response.iter_bytes():
                    if len(raw) + len(chunk) > 1024 * 1024:
                        raise ProtocolError(502)
                    raw.extend(chunk)
                return json.loads(raw)
        except httpx.HTTPError:
            raise TransportUnavailable("Platform connection failed") from None
        except (ValueError, UnicodeError):
            raise ProtocolError(502) from None

    def upload_run(self, body):
        return self._request("POST", "/sdk-runs:upload", body)

    def register(self, agent_id):
        identity = self.instance_id[:80] + ":" + hashlib.sha256(agent_id.encode()).hexdigest()[:32]
        request = {"agent_id": agent_id, "agent_version": self.agent_version, "instance_id": identity,
                   "protocol_version": "1.0", "sdk_language": "PYTHON", "sdk_version": "0.1.1", "max_concurrency": 1}
        validate_message("RegisterWorkerRequest", request)
        result = self._request("POST", "/workers/register", request)
        if not isinstance(result, dict) or set(result) != {"worker_id", "heartbeat_seconds", "lease_seconds"} or result["heartbeat_seconds"] != 15 or result["lease_seconds"] != 60:
            raise ProtocolError(502)
        return result["worker_id"]

    def claim(self, worker_id, agent_id):
        request = {"worker_id": worker_id, "agent_id": agent_id, "agent_version": self.agent_version, "max_wait_seconds": 20}
        validate_message("ClaimRequest", request)
        result = self._request("POST", "/workers/" + worker_id + "/claim", request)
        if result is not None:
            validate_message("LeaseGrant", result)
        return result

    def started(self, assignment):
        self._request("POST", "/trials/" + assignment["trial_id"] + "/started", assignment=assignment)

    def heartbeat(self, assignment):
        result = self._request("POST", "/trials/" + assignment["trial_id"] + "/heartbeat", assignment=assignment)
        if not isinstance(result, dict) or type(result.get("cancel_requested")) is not bool or not isinstance(result.get("lease_expires_at"), str):
            raise ProtocolError(502)
        return result

    def upload_events(self, assignment, events):
        for event in events:
            validate_message("TraceEvent", event)
        return self._request("POST", "/trials/" + assignment["trial_id"] + "/events:batch", {"events": events}, assignment)

    def complete(self, assignment, completion):
        validate_message("CompletionRequest", completion)
        self._request("POST", "/trials/" + assignment["trial_id"] + "/complete", completion, assignment)

    def upload_artifact(self, assignment, path, name=None, media_type="application/octet-stream"):
        path = Path(path)
        if not path.is_file() or path.stat().st_size > 100 * 1024 * 1024:
            raise ValueError("Invalid artifact file")
        # Reading a bounded file here also avoids hashing one version and uploading changed bytes.
        return self.upload_bytes(assignment, path.read_bytes(), name or path.name, media_type)

    def upload_bytes(self, assignment, content, name, media_type, idempotency_key=None):
        request = {"name": name, "media_type": media_type, "size_bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "idempotency_key": idempotency_key or str(uuid4())}
        validate_message("RegisterArtifactRequest", request)
        prefix = "/trials/" + assignment["trial_id"] + "/artifacts"
        grant = self._request("POST", prefix, request, assignment)
        url = urlsplit(grant["upload_url"])
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
            raise ProtocolError(502)
        try:
            # Deliberately use a separate client: platform Bearer credentials never reach object storage.
            with httpx.Client(timeout=httpx.Timeout(90, connect=5), follow_redirects=False) as upload_client:
                response = upload_client.put(grant["upload_url"], content=content, headers={"Content-Type": "application/octet-stream", "Content-Length": str(len(content))})
                if response.status_code < 200 or response.status_code >= 300:
                    raise ProtocolError(response.status_code)
        except httpx.HTTPError:
            raise TransportUnavailable("Artifact connection failed") from None
        result = self._request("POST", prefix + "/" + grant["artifact_id"] + "/complete", assignment=assignment)
        validate_message("ArtifactRef", result)
        return result["artifact_id"]

    def close(self):
        self._client.close()
