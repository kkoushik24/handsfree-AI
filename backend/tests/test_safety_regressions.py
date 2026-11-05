import asyncio

import model_schema
from contracts import ActionType, PlanRequest, Step, UIElement
from model_schema import apply_safety


SENSITIVE_TARGETS = [
    "password field",
    "OTP field",
    "card number field",
    "CVV field",
    "PIN field",
    "bank account field",
    "payment field",
]


def test_sensitive_fields_are_handed_to_user():
    for index, target in enumerate(SENSITIVE_TARGETS):
        element = UIElement(
            id=f"field-{index}",
            text=target,
            editable=True,
            sensitive=(target == "bank account field"),
        )
        result = apply_safety(
            [Step(action=ActionType.type_text, target=target, text="private-value")],
            [element],
        )
        assert result.steps[0].action == ActionType.ask_user, target
        assert result.steps[0].text is None, target


def test_harmless_tap_passes_through_with_grounded_id():
    element = UIElement(id="continue-button", text="Continue", clickable=True)
    result = apply_safety(
        [Step(action=ActionType.tap, target="Continue", reason="Next page")],
        [element],
    )
    assert result.steps[0].action == ActionType.tap
    assert result.steps[0].target == "continue-button"


def test_live_model_output_is_checked_by_safety(monkeypatch):
    async def fake_model(_prompt):
        return '{"steps":[{"action":"type_text","target":"CVV field","text":"123"}]}'

    monkeypatch.setattr(model_schema, "_call_ollama", fake_model)
    request = PlanRequest(
        goal="enter card verification code",
        elements=[UIElement(id="cvv", text="CVV field", editable=True)],
    )
    response = asyncio.run(model_schema.plan(request))
    assert response.action.type == "ask_user"
    assert response.action.text is None


def test_mock_path_is_checked_by_safety(monkeypatch):
    def unsafe_mock(_request):
        return [Step(action=ActionType.type_text, target="PIN field", text="4321")]

    monkeypatch.setattr(model_schema, "_mock_steps", unsafe_mock)
    response = asyncio.run(model_schema.plan(PlanRequest(goal="mock task"), force_mock=True))
    assert response.action.type == "ask_user"
    assert response.action.text is None


def test_demo_goal_uses_mock_plan_path():
    request = PlanRequest(goal=model_schema.DEMO_GOAL)
    response = asyncio.run(model_schema.plan(request))
    assert response.action.type == "open_app"
    assert response.action.target_id == "WhatsApp"
