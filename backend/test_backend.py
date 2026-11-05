import os
import sys

os.environ["MOCK"] = "1"
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import model_schema  # noqa: E402
from app import app  # noqa: E402
from contracts import ActionType, Step  # noqa: E402

client = TestClient(app)


def test_health():
    assert client.get("/health").json()["status"] == "ok"


def test_plan_whatsapp_demo():
    r = client.post("/plan", json={"goal": "send message to Rahul saying hello"})
    assert r.status_code == 200
    steps = r.json()["steps"]
    assert steps[0]["action"] == "open_app"
    assert steps[-1]["action"] == "done"


def test_safety_blocks_password():
    out = model_schema.apply_safety([Step(action=ActionType.type_text, target="Password", text="abc123")])
    assert out.steps[0].action == ActionType.ask_user
    assert out.needs_user


def test_safety_blocks_payment_tap():
    out = model_schema.apply_safety([Step(action=ActionType.tap, target="Confirm payment")])
    assert out.steps[0].action == ActionType.ask_user


def test_parse_steps_handles_fences():
    raw = '```json\n{"steps":[{"action":"open_app","target":"WhatsApp"}]}\n```'
    assert model_schema.parse_steps(raw)[0].target == "WhatsApp"


def test_transcribe_without_key_returns_502():
    r = client.post("/voice/transcribe", files={"file": ("a.wav", b"123", "audio/wav")})
    assert r.status_code == 502
