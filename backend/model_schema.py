"""Planning logic, parsing, safety validation and response construction."""
import json
import os
import re
import uuid
from typing import Optional

import httpx
from pydantic import BaseModel, Field

from contracts import Action, ActionType, PlanRequest, PlanResponse, Step, UIElement

MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:3b")
MODEL_NAME = MODEL
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
OLLAMA_TIMEOUT = float(os.getenv("OLLAMA_TIMEOUT", "60"))
STRICT_TARGETS = os.getenv("STRICT_TARGETS", "1") == "1"
MAX_ELEMENTS = 40
DEMO_GOAL = "send message to rahul saying hello"

SYSTEM_PROMPT = """You are an Android UI automation assistant.
Return JSON containing ordered steps for the user's goal and current screen.

Valid actions: open_app, tap, type_text, scroll, back, ask_user, done.
Rules:
1. The first step is the NEXT action to perform now.
2. For tap and type_text, use only targets present in Screen Elements.
3. Never invent UI element identifiers.
4. For passwords, OTPs, PINs, payment details, or uncertainty, use ask_user.
5. The last step should be done.
6. Return only JSON: {"steps":[{"action":"tap","target":"element id","reason":"..."}]}.
"""

SENSITIVE_KEYWORDS = (
    "password", "passcode", "pin", "upi pin", "otp", "cvv",
    "credit card", "debit card", "card number",
    "confirm payment", "pay now", "payment", "transfer",
)
_SENSITIVE_RE = re.compile(
    r"\\b(" + "|".join(re.escape(k) for k in SENSITIVE_KEYWORDS) + r")\\b",
    re.IGNORECASE,
)

ACTION_ALIASES = {
    "type": "type_text", "input": "type_text", "enter_text": "type_text",
    "click": "tap", "press": "tap",
    "open": "open_app", "launch": "open_app",
    "finish": "done", "complete": "done",
}


class SafetyPlan(BaseModel):
    steps: list[Step] = Field(default_factory=list)
    needs_user: bool = False


def _as_str(value) -> Optional[str]:
    return None if value is None else str(value)


def _extract_json(raw: str):
    text = raw.strip()
    fence = re.search(r"```(?:json)?\\s*([\\s\\S]*?)\\s*```", text)
    if fence:
        text = fence.group(1).strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    match = re.search(r"(\\{[\\s\\S]*\\}|\\[[\\s\\S]*\\])", text)
    if match:
        try:
            return json.loads(match.group(1))
        except Exception:
            return None
    return None


def parse_steps(raw: str) -> list[Step]:
    parsed = _extract_json(raw)
    if isinstance(parsed, dict):
        raw_steps = parsed.get("steps", [parsed] if "action" in parsed else [])
    elif isinstance(parsed, list):
        raw_steps = parsed
    else:
        raw_steps = []

    valid = {item.value for item in ActionType}
    result: list[Step] = []
    for item in raw_steps:
        if not isinstance(item, dict):
            continue
        action = str(item.get("action", "")).strip().lower()
        action = ACTION_ALIASES.get(action, action)
        if action not in valid:
            action = ActionType.ask_user.value
        result.append(Step(
            action=action,
            target=_as_str(item.get("target")),
            text=_as_str(item.get("text")),
            reason=_as_str(item.get("reason")) or "Executing next step",
            needs_approval=True,
        ))
    return result


def find_element(elements: list[UIElement], target: Optional[str]) -> Optional[UIElement]:
    if not target:
        return None
    needle = target.strip().lower()
    for element in elements:
        if element.id.lower() == needle:
            return element
    for element in elements:
        labels = ((element.text or "").lower(), (element.desc or "").lower())
        if needle in labels:
            return element
    for element in elements:
        labels = ((element.text or "").lower(), (element.desc or "").lower())
        if any(label and (needle in label or label in needle) for label in labels):
            return element
    return None


def _ask_user(step: Step, reason: str) -> Step:
    # Never pass text intended for a sensitive field to Android.
    return Step(
        action=ActionType.ask_user,
        target=step.target,
        text=None,
        reason=reason,
        needs_approval=True,
    )


def apply_safety(
    steps: list[Step],
    elements: Optional[list[UIElement]] = None,
) -> SafetyPlan:
    """Validate sensitive content throughout the plan and the immediate action against this screen.

    Only the first step is eligible for execution from the current observation.
    Future steps are previews and must be replanned after Android re-observes.
    """
    elements = elements or []
    safe: list[Step] = []

    for index, step in enumerate(steps):
        target = step.target or ""
        text = step.text or ""

        if _SENSITIVE_RE.search(target) or _SENSITIVE_RE.search(text):
            safe.append(_ask_user(step, "Sensitive action detected. Please complete this yourself."))
            continue

        if index == 0 and step.action in (ActionType.tap, ActionType.type_text):
            element = find_element(elements, step.target)
            if element is None:
                if STRICT_TARGETS:
                    reason = (
                        f"No screen elements were supplied for target '{step.target}'."
                        if not elements else
                        f"I could not find '{step.target}' on the current screen."
                    )
                    safe.append(_ask_user(step, reason))
                    continue
            elif (
                element.sensitive
                or _SENSITIVE_RE.search(element.text or "")
                or _SENSITIVE_RE.search(element.desc or "")
            ):
                safe.append(_ask_user(step, "That field is sensitive. Please complete this yourself."))
                continue
            else:
                step = step.model_copy(update={"target": element.id})

        safe.append(step.model_copy(update={
            "needs_approval": step.action != ActionType.done
        }))

    return SafetyPlan(
        steps=safe,
        needs_user=any(item.action == ActionType.ask_user for item in safe),
    )


def _build_response(
    req: PlanRequest,
    steps: list[Step],
    error: Optional[str] = None,
) -> PlanResponse:
    if not steps:
        steps = [Step(
            action=ActionType.ask_user,
            reason="No safe next action was produced.",
            needs_approval=True,
        )]

    first = steps[0]
    wire_type = "type" if first.action == ActionType.type_text else first.action.value
    action = Action(
        type=wire_type,
        target_id=first.target,
        text=first.text,
        reason=first.reason,
        needs_approval=first.needs_approval,
    )
    return PlanResponse(
        task_id=req.task_id or f"task-{uuid.uuid4().hex[:8]}",
        step=req.step,
        screen_hash=req.screen_hash or "",
        action=action,
        steps=steps,
        error=error,
    )


def _error_response(req: PlanRequest, error: str, reason: str) -> PlanResponse:
    step = Step(
        action=ActionType.ask_user,
        reason=reason,
        needs_approval=True,
    )
    # Error responses are safe user-handoff responses, not executable UI actions.
    return _build_response(req, [step], error=error)


def _s(
    action: ActionType,
    target: Optional[str] = None,
    text: Optional[str] = None,
    reason: str = "",
) -> Step:
    return Step(
        action=action,
        target=target,
        text=text,
        reason=reason,
        needs_approval=action != ActionType.done,
    )


def _mock_steps(req: PlanRequest) -> list[Step]:
    goal = req.goal.lower()
    if "whatsapp" in goal or "rahul" in goal or "message" in goal:
        return [
            _s(ActionType.open_app, "WhatsApp", reason="Open WhatsApp"),
            _s(ActionType.tap, "Rahul", reason="Select chat with Rahul"),
            _s(ActionType.type_text, "Message", "hello", "Type hello message"),
            _s(ActionType.done, reason="Task complete"),
        ]
    return [
        _s(ActionType.open_app, "App", reason=f"Open app for goal: {req.goal}"),
        _s(ActionType.done, reason="Task complete"),
    ]


async def _call_ollama(prompt: str) -> str:
    async with httpx.AsyncClient(timeout=OLLAMA_TIMEOUT) as client:
        response = await client.post(
            f"{OLLAMA_HOST}/api/generate",
            json={
                "model": MODEL,
                "system": SYSTEM_PROMPT,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "options": {"temperature": 0},
            },
        )
        response.raise_for_status()
        return response.json().get("response", "")


async def ollama_status() -> tuple[bool, bool]:
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            response = await client.get(f"{OLLAMA_HOST}/api/tags")
            response.raise_for_status()
            names = [item.get("name", "") for item in response.json().get("models", [])]
            return True, any(name == MODEL or name.startswith(MODEL) for name in names)
    except Exception:
        return False, False


async def plan(req: PlanRequest, force_mock: bool = False) -> PlanResponse:
    """Generate a plan, then ALWAYS run the same safety pipeline."""
    use_mock = force_mock or req.goal.strip().lower() == DEMO_GOAL

    if use_mock:
        steps = _mock_steps(req)
    else:
        elements = [
            {
                "id": element.id,
                "text": element.text,
                "desc": element.desc,
                "sensitive": element.sensitive,
            }
            for element in req.elements[:MAX_ELEMENTS]
        ]
        prompt = (
            f"Goal: {req.goal}\\n"
            f"Current App: {req.app_package or 'Home Screen'}\\n"
            f"Screen Elements: {json.dumps(elements)}\\n"
            f"History: {req.history[-6:]}"
        )

        steps: list[Step] = []
        for _ in range(2):
            try:
                raw = await _call_ollama(prompt)
            except Exception as exc:
                return _error_response(
                    req,
                    f"ollama_unavailable: {exc}",
                    "The AI model is not reachable right now.",
                )
            steps = parse_steps(raw)
            if steps:
                break

        if not steps:
            return _error_response(
                req,
                "malformed_model_output",
                "I could not work out a safe next step.",
            )

    if steps[-1].action != ActionType.done:
        steps.append(Step(
            action=ActionType.done,
            reason="Task complete",
            needs_approval=False,
        ))

    # Shared safety gate for AI, mock query, and demo-goal paths.
    safety_result = apply_safety(steps, req.elements)
    return _build_response(req, safety_result.steps)


def mock_plan(req: PlanRequest) -> PlanResponse:
    """Compatibility wrapper; mock plans also pass through apply_safety()."""
    steps = _mock_steps(req)
    if steps[-1].action != ActionType.done:
        steps.append(_s(ActionType.done, reason="Task complete"))
    safety_result = apply_safety(steps, req.elements)
    return _build_response(req, safety_result.steps)
