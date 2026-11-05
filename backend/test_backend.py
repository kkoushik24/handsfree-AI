"""Pytest test suite for backend planning, safety filters, and endpoints."""
import pytest
from fastapi.testclient import TestClient

from app import app
from contracts import ActionType, PlanRequest
from model_schema import apply_safety, parse_steps, Step

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "model" in data


def test_plan_whatsapp_demo():
    payload = {"goal": "send message to Rahul saying hello"}
    response = client.post("/plan", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert len(data["steps"]) >= 3
    assert data["action"]["type"] == "open_app"


def test_safety_blocks_password():
    steps = [
        Step(action=ActionType.type_text, target="password field", text="secret123", reason="enter password")
    ]
    safe_plan = apply_safety(steps)
    assert safe_plan.needs_user is True
    assert safe_plan.steps[0].action == ActionType.ask_user
    assert safe_plan.steps[0].needs_approval is True


def test_safety_blocks_payment_tap():
    steps = [
        Step(action=ActionType.tap, target="confirm payment button", reason="pay now")
    ]
    safe_plan = apply_safety(steps)
    assert safe_plan.needs_user is True
    assert safe_plan.steps[0].action == ActionType.ask_user


def test_parse_steps_handles_fences():
    raw_output = """Here are the steps:
    ```json
    {
      "steps": [
        {"action": "open_app", "target": "YouTube", "reason": "Open app"},
        {"action": "done", "reason": "Complete"}
      ]
    }
    ```"""
    steps = parse_steps(raw_output)
    assert len(steps) == 2
    assert steps[0].action == ActionType.open_app
    assert steps[1].action == ActionType.done


def test_transcribe_without_key_returns_502(monkeypatch):
    import voice
    monkeypatch.setattr(voice, "ELEVENLABS_API_KEY", "")
    response = client.post("/transcribe", files={"file": ("test.mp3", b"fakeaudio", "audio/mpeg")})
    assert response.status_code == 502