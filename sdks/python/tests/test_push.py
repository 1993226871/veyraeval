import json
from uuid import uuid4

import httpx
import pytest

from agent_eval import PushClient, observe


def test_push_run_replays_captured_results_without_running_agent_again(tmp_path):
    submitted = []
    report = str(uuid4())

    def handle(request):
        assert request.url.path == "/api/v1/sdk-runs:upload"
        assert request.headers["Authorization"] == "Bearer upload-secret"
        submitted.append(json.loads(request.content))
        return httpx.Response(202, json={"id": report})

    @observe("tool")
    def query(order_id):
        return {"order_id": order_id, "amount": 100}

    with httpx.Client(transport=httpx.MockTransport(handle)) as http:
        client = PushClient("https://example.test", "upload-secret", client=http)
        run = client.start_run(namespace="regression", name="Run", agent_name="orders",
                               agent_version="v2", dataset_version="orders-v1", suite_version_id=uuid4())
        with run.case(id="a", instruction="Read order", input={"order_id": "A"}, expected={"amount": 100}) as case:
            case.output = query("A")
        with pytest.raises(RuntimeError), run.case(id="b", instruction="Test failure", input={}):
            raise RuntimeError("private error")
        file = tmp_path / "run.json"
        run.save(file)
        assert client.upload_file(file) == report == run.submit()
    assert submitted[0] == submitted[1]
    first, second = submitted[0]["cases"]
    assert [event["span_kind"] for event in first["events"]] == ["agent", "tool", "tool", "agent"]
    assert [event["sequence"] for event in first["events"]] == [1, 2, 3, 4]
    assert second["status"] == "FAILED"
    assert "private error" not in file.read_text(encoding="utf-8")
    assert "upload-secret" not in file.read_text(encoding="utf-8")
