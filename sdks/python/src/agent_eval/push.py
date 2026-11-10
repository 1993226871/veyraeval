"""Application-driven evaluation with bounded capture and explicit upload replay."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
from threading import Lock
from uuid import UUID, uuid4

from .spool import encode
from .telemetry import TelemetrySession, span
from .transport import HttpTransport, ProtocolError

MAX_UPLOAD_BYTES = 8 * 1024 * 1024
MAX_EVENTS = 2000


class _Capture:
    def __init__(self, run):
        self.run = run
        self.events = []
        self.dropped = 0

    def store_payload(self, trial_id, payload):
        # Push runs carry inline evidence. Large spans are marked truncated.
        return None

    def append(self, trial_id, event):
        size = len(encode(event))
        with self.run._lock:
            if self.run._event_count >= MAX_EVENTS or self.run._event_bytes + size > MAX_UPLOAD_BYTES:
                self.dropped += 1
                return
            self.run._event_count += 1
            self.run._event_bytes += size
            event["sequence"] = len(self.events) + 1
            self.events.append(event)

    def record_drop(self, trial_id):
        with self.run._lock:
            self.dropped += 1


class PushClient:
    """Upload completed runs using an authenticated account session."""

    def __init__(self, platform_url, upload_key=None, *, ca_cert=None, client=None):
        self._transport = HttpTransport(platform_url, upload_key, client=client, ca_cert=ca_cert,
                                        account_session=upload_key is None)
        self._upload_key = upload_key
        self._owns_client = client is None

    @classmethod
    def login(cls, platform_url, username, password, *, ca_cert=None, client=None):
        if not username or not password:
            raise ValueError("Username and password required")
        instance = cls(platform_url, ca_cert=ca_cert, client=client)
        try:
            instance._transport.login(username, password)
        except Exception:
            instance.close()
            raise
        return instance

    def close(self):
        if self._owns_client:
            self._transport.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def start_run(self, *, namespace, name, agent_name, agent_version, dataset_version, suite_version_id, project=None):
        if self._upload_key is None and (not isinstance(project, str) or not project.strip()):
            raise ValueError("Project name required")
        payload = {
            "run_id": str(uuid4()),
            "namespace": namespace,
            "name": name,
            "agent_name": agent_name,
            "agent_version": agent_version,
            "dataset_version": dataset_version,
            "suite_version_id": str(UUID(str(suite_version_id))),
            "cases": [],
        }
        if project is not None:
            payload["project"] = project
        return PushRun(self, payload)

    def upload(self, payload):
        if len(encode(payload)) > MAX_UPLOAD_BYTES:
            raise ValueError("Run exceeds the 8 MiB upload limit")
        reply = self._transport.upload_run(payload)
        try:
            return str(UUID(reply["id"]))
        except (TypeError, KeyError, ValueError, AttributeError):
            raise ProtocolError(502) from None

    def upload_file(self, path):
        with Path(path).open("rb") as stream:
            raw = stream.read(MAX_UPLOAD_BYTES + 1)
        if len(raw) > MAX_UPLOAD_BYTES:
            raise ValueError("Run exceeds the 8 MiB upload limit")
        return self.upload(json.loads(raw))


_UNSET = object()


class PushRun:
    def __init__(self, client, payload):
        self._client = client
        self._payload = payload
        self._ids = set()
        self._active = set()
        self._sealed_payload = None
        self._event_count = 0
        self._event_bytes = 0
        self._lock = Lock()

    @contextmanager
    def case(self, *, id, instruction, input, expected=_UNSET):
        item = {"case_id": id, "instruction": instruction, "input": json.loads(encode(input)), "output": None, "status": "FAILED"}
        if expected is not _UNSET:
            item["expected"] = json.loads(encode(expected))
        with self._lock:
            if self._sealed_payload is not None or not isinstance(id, str) or not id.strip() or len(id) > 128 or id in self._ids or len(self._ids) >= 1000:
                raise ValueError("Invalid, duplicate or sealed case")
            self._ids.add(id)
            self._active.add(id)
        capture = _Capture(self)
        session = TelemetrySession(str(uuid4()), uuid4().hex, capture, (self._client._upload_key,))
        try:
            with session.activate(), span("agent", self._payload["agent_name"], input) as root:
                yield root
                item["output"] = json.loads(encode(root.output))
                item["status"] = "SUCCEEDED"
        finally:
            with self._lock:
                self._active.remove(id)
                self._payload["cases"].append({**item, "events": list(capture.events), "dropped_count": capture.dropped})

    def _seal(self):
        with self._lock:
            if self._active or not self._payload["cases"]:
                raise ValueError("Complete at least one case before sealing the run")
            if self._sealed_payload is None:
                self._sealed_payload = encode(self._payload)
            return self._sealed_payload

    def save(self, path):
        """Write an exclusive local replay file before attempting the network upload."""
        payload = self._seal()
        if len(payload) > MAX_UPLOAD_BYTES:
            raise ValueError("Run exceeds the 8 MiB upload limit")
        with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "wb") as stream:
            stream.write(payload)

    def submit(self):
        return self._client.upload(json.loads(self._seal()))
