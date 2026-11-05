"""Backend regression tests. Run from the repository root with pytest."""
import httpx
from fastapi.testclient import TestClient

import app as app_module
import model_schema
from app import app
from contracts import ActionType, Step, UIElement
from model_schema import apply_safety, parse_steps

client = TestClient(app)

GOOD_MODEL_OUTPUT = (
    '{"steps":[{"action":"tap","target":"Settings","reason":"open","needs_approval":false},'
    '{"action":"done","reason":"ok"}]}'
)


# ------------------------------------------------------------------ health
def test_health_degraded_when_ollama_down(monkeypatch):
    async def fake():
        return False, False
    monkeypatch.setattr(app_module, "ollama_status", fake)
    data = client.get("/health").json()
    assert data["status"] == "degraded"
    assert data["ollama_reachable"] is False


def test_health_ok_when_ready(monkeypatch):
    async def fake():
        return True, True
    monkeypatch.setattr(app_module, "ollama_status", fake)
    assert client.get("/health").json()["status"] == "ok"


# ------------------------------------------------------------------ plan
def test_plan_whatsapp_demo():
    r = client.post("/plan", json={"goal": "send message to Rahul saying hello"})
    assert r.status_code == 200
    data = r.json()
    assert len(data["steps"]) >= 3
    assert data["action"]["type"] == "open_app"
    assert data["action"]["needs_approval"] is True


def test_plan_mock_flag():
    r = client.post("/plan?mock=true", json={"goal": "open settings"})
    assert r.status_code == 200
    assert r.json()["task_id"]


def test_plan_missing_goal_is_422():
    assert client.post("/plan", json={}).status_code == 422


def test_plan_forces_approval_and_resolves_id(monkeypatch):
    async def fake(prompt):
        return GOOD_MODEL_OUTPUT
    monkeypatch.setattr(model_schema, "_call_ollama", fake)
    body = {"goal": "open settings", "screen_hash": "h1",
            "elements": [{"id": "e1", "text": "Settings"}]}
    data = client.post("/plan", json=body).json()
    assert data["action"]["type"] == "tap"
    assert data["action"]["target_id"] == "e1"
    assert data["action"]["needs_approval"] is True
    assert data["screen_hash"] == "h1"
    assert data["error"] is None


def test_plan_hallucinated_target_asks_user(monkeypatch):
    async def fake(prompt):
        return '{"steps":[{"action":"tap","target":"Teleport","reason":"x"},{"action":"done"}]}'
    monkeypatch.setattr(model_schema, "_call_ollama", fake)
    body = {"goal": "open settings", "elements": [{"id": "e1", "text": "Settings"}]}
    assert client.post("/plan", json=body).json()["action"]["type"] == "ask_user"


def test_plan_ollama_down_returns_clean_error(monkeypatch):
    async def fake(prompt):
        raise httpx.ConnectError("down")
    monkeypatch.setattr(model_schema, "_call_ollama", fake)
    r = client.post("/plan", json={"goal": "open settings"})
    assert r.status_code == 200
    data = r.json()
    assert data["error"].startswith("ollama_unavailable")
    assert data["action"]["type"] == "ask_user"


def test_plan_malformed_output_asks_user(monkeypatch):
    async def fake(prompt):
        return "sorry I cannot help"
    monkeypatch.setattr(model_schema, "_call_ollama", fake)
    data = client.post("/plan", json={"goal": "open settings"}).json()
    assert data["error"] == "malformed_model_output"
    assert data["action"]["type"] == "ask_user"


# ------------------------------------------------------------------ safety
def test_safety_blocks_password():
    steps = [Step(action=ActionType.type_text, target="password field", text="secret123", reason="x")]
    plan = apply_safety(steps)
    assert plan.needs_user is True
    assert plan.steps[0].action == ActionType.ask_user
    assert plan.steps[0].text is None


def test_safety_blocks_payment_tap():
    plan = apply_safety([Step(action=ActionType.tap, target="confirm payment button", reason="pay")])
    assert plan.steps[0].action == ActionType.ask_user


def test_safety_word_boundary_allows_pinterest():
    plan = apply_safety(
        [Step(action=ActionType.tap, target="Pinterest", reason="open")],
        [UIElement(id="pinterest", text="Pinterest")],
    )
    assert plan.steps[0].action == ActionType.tap


def test_safety_sensitive_element_flag():
    els = [UIElement(id="e2", text="Account field", sensitive=True)]
    plan = apply_safety([Step(action=ActionType.tap, target="Account field", reason="x")], els)
    assert plan.steps[0].action == ActionType.ask_user


# ------------------------------------------------------------------ parsing
def test_parse_steps_handles_fences():
    raw = """Here are the steps:
    ```json
    {"steps": [
        {"action": "open_app", "target": "YouTube", "reason": "Open app"},
        {"action": "done", "reason": "Complete"}
    ]}
    ```"""
    steps = parse_steps(raw)
    assert len(steps) == 2
    assert steps[0].action == ActionType.open_app
    assert steps[1].action == ActionType.done


def test_parse_steps_garbage_returns_empty():
    assert parse_steps("no json here") == []


def test_parse_steps_aliases():
    steps = parse_steps('{"steps":[{"action":"type","target":"Box","text":"hi"}]}')
    assert steps[0].action == ActionType.type_text


# ------------------------------------------------------------------ voice
def test_speak_without_key_returns_503(monkeypatch):
    monkeypatch.delenv("SARVAM_API_KEY", raising=False)
    response = client.post("/speak", params={"text": "hello"})
    assert response.status_code == 503
    assert response.json() == {"error": "voice disabled: SARVAM_API_KEY not set"}


def test_speak_returns_audio(monkeypatch):
    async def fake(text):
        return b"abc"
    monkeypatch.setattr(app_module, "text_to_speech", fake)
    r = client.post("/speak", params={"text": "hello"})
    assert r.status_code == 200
    assert r.content == b"abc"
    assert r.headers["content-type"] == "audio/wav"


def test_transcribe_without_key_returns_502(monkeypatch):
    monkeypatch.delenv("SARVAM_API_KEY", raising=False)
    r = client.post("/transcribe", files={"file": ("t.mp3", b"fake", "audio/mpeg")})
    assert r.status_code == 503
    assert r.json() == {"error": "voice disabled: SARVAM_API_KEY not set"}


def test_voice_disabled_does_not_block_health_or_mock_plan(monkeypatch):
    monkeypatch.delenv("SARVAM_API_KEY", raising=False)
    health = client.get("/health")
    plan_response = client.post("/plan?mock=true", json={"goal": "open settings"})
    assert health.status_code == 200
    assert plan_response.status_code == 200
