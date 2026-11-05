"""Planning logic: Ollama call, robust parsing, safety rules and fallbacks."""
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
STRICT_TARGETS = os.getenv("STRICT_TARGETS", "1") == "1"  # set STRICT_TARGETS=0 to allow unknown targets
MAX_ELEMENTS = 40
DEMO_GOAL = "send message to rahul saying hello"  # deterministic offline demo path

SYSTEM_PROMPT = """You are an Android UI automation assistant for a user who cannot use their hands.
Given the user's goal and the current screen elements, return a JSON object with ordered execution steps.

Valid actions: open_app, tap, type_text, scroll, back, ask_user, done.

Rules:
1. The first step is the NEXT action to perform right now. If the required app is not open, it should be open_app.
2. The last step must always have action "done".
3. For tap and type_text, use ONLY targets that appear in Screen Elements (by id or text). Never invent elements.
4. If you are unsure, or the field is a password, OTP, PIN or payment field, use ask_user.
5. Return ONLY valid JSON matching this schema:
{
  "steps": [
    {"action": "open_app", "target": "AppName", "reason": "Open App"},
    {"action": "tap", "target": "Button or element", "reason": "Why tapping"},
    {"action": "type_text", "target": "Input field", "text": "text to type", "reason": "Why typing"},
    {"action": "done", "reason": "Task complete"}
  ]
}
"""

SENSITIVE_KEYWORDS = (
    "password", "passcode", "pin", "upi pin", "otp", "cvv",
    "credit card", "debit card", "card number",
    "confirm payment", "pay now", "payment", "transfer",
)
_SENSITIVE_RE = re.compile(r"\b(" + "|".join(re.escape(k) for k in SENSITIVE_KEYWORDS) + r")\b", re.IGNORECASE)

ACTION_ALIASES = {
    "type": "type_text", "input": "type_text", "enter_text": "type_text",
    "click": "tap", "press": "tap",
    "open": "open_app", "launch": "open_app",
    "finish": "done", "complete": "done",
}


class SafetyPlan(BaseModel):
    steps: list[Step] = Field(default_factory=list)
    needs_user: bool = False


# --------------------------------------------------------------------------- parsing

def _as_str(value) -> Optional[str]:
    return None if value is None else str(value)


def _extract_json(raw: str):
    text = raw.strip()
    fence = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", text)
    if fence:
        text = fence.group(1).strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    braces = re.search(r"(\{[\s\S]*\}|\[[\s\S]*\])", text)
    if braces:
        try:
            return json.loads(braces.group(1))
        except Exception:
            return None
    return None


def parse_steps(raw: str) -> list[Step]:
    """Parse model output into steps. Returns [] if nothing usable was found."""
    parsed = _extract_json(raw)
    if isinstance(parsed, dict):
        if isinstance(parsed.get("steps"), list):
            steps_raw = parsed["steps"]
        elif "action" in parsed:
            steps_raw = [parsed]
        else:
            steps_raw = []
    elif isinstance(parsed, list):
        steps_raw = parsed
    else:
        steps_raw = []

    valid = {a.value for a in ActionType}
    steps: list[Step] = []
    for s in steps_raw:
        if not isinstance(s, dict):
            continue
        act = str(s.get("action", "")).strip().lower()
        act = ACTION_ALIASES.get(act, act)
        if act not in valid:
            act = ActionType.ask_user.value
        steps.append(Step(
            action=act,
            target=_as_str(s.get("target")),
            text=_as_str(s.get("text")),
            reason=_as_str(s.get("reason")) or "Executing step",
            needs_approval=True,
        ))
    return steps


# --------------------------------------------------------------------------- safety

def find_element(elements: list[UIElement], target: Optional[str]) -> Optional[UIElement]:
    """Match a model-provided target against screen elements by id, then exact text, then partial text."""
    if not target:
        return None
    t = target.strip().lower()
    for e in elements:
        if e.id.lower() == t:
            return e
    for e in elements:
        if t in ((e.text or "").lower(), (e.desc or "").lower()):
            return e
    for e in elements:
        for label in ((e.text or "").lower(), (e.desc or "").lower()):
            if label and (t in label or label in t):
                return e
    return None


def _ask_user(step: Step, reason: str) -> Step:
    # drop any text so a password or OTP is never carried forward
    return Step(action=ActionType.ask_user, target=step.target, text=None, reason=reason, needs_approval=True)


def apply_safety(steps: list[Step], elements: Optional[list[UIElement]] = None) -> SafetyPlan:
    """Block sensitive steps, check the next step against the real screen, and force approval."""
    elements = elements or []
    safe: list[Step] = []

    for i, s in enumerate(steps):
        target, text = s.target or "", s.text or ""

        if _SENSITIVE_RE.search(target) or _SENSITIVE_RE.search(text):
            safe.append(_ask_user(s, "Sensitive action detected. Please do this yourself."))
            continue

        # only the first step is executed next, so only it is checked against the live screen
        if i == 0 and elements and s.action in (ActionType.tap, ActionType.type_text):
            el = find_element(elements, s.target)
            if el is None:
                if STRICT_TARGETS:
                    safe.append(_ask_user(s, f"I could not find '{s.target}' on this screen."))
                    continue
            elif el.sensitive or _SENSITIVE_RE.search(el.text or "") or _SENSITIVE_RE.search(el.desc or ""):
                safe.append(_ask_user(s, "That field is sensitive. Please do this yourself."))
                continue
            else:
                s = s.model_copy(update={"target": el.id})

        safe.append(s.model_copy(update={"needs_approval": s.action != ActionType.done}))

    needs_user = any(s.action == ActionType.ask_user for s in safe)
    return SafetyPlan(steps=safe, needs_user=needs_user)


# --------------------------------------------------------------------------- responses

def _build_response(req: PlanRequest, steps: list[Step], error: Optional[str] = None) -> PlanResponse:
    first = steps[0]
    action_type = "type" if first.action == ActionType.type_text else first.action.value
    action = Action(
        type=action_type,
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
    step = Step(action=ActionType.ask_user, reason=reason, needs_approval=True)
    return _build_response(req, [step], error=error)


def _s(action: ActionType, target: Optional[str] = None, text: Optional[str] = None, reason: str = "") -> Step:
    return Step(action=action, target=target, text=text, reason=reason,
                needs_approval=action != ActionType.done)


def mock_plan(req: PlanRequest) -> PlanResponse:
    """Canned plan for the offline demo and for testing without the AI model."""
    goal = req.goal.lower()
    if "whatsapp" in goal or "rahul" in goal or "message" in goal:
        steps = [
            _s(ActionType.open_app, "WhatsApp", reason="Open WhatsApp"),
            _s(ActionType.tap, "Rahul", reason="Select chat with Rahul"),
            _s(ActionType.type_text, "Message", "hello", "Type hello message"),
            _s(ActionType.done, reason="Message sent successfully"),
        ]
    else:
        steps = [
            _s(ActionType.open_app, "App", reason=f"Open app for goal: {req.goal}"),
            _s(ActionType.done, reason="Task completed"),
        ]
    return _build_response(req, steps)


# --------------------------------------------------------------------------- Ollama

async def _call_ollama(prompt: str) -> str:
    async with httpx.AsyncClient(timeout=OLLAMA_TIMEOUT) as client:
        resp = await client.post(
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
        resp.raise_for_status()
        return resp.json().get("response", "")


async def ollama_status() -> tuple[bool, bool]:
    """Returns (ollama_reachable, model_ready)."""
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get(f"{OLLAMA_HOST}/api/tags")
            resp.raise_for_status()
            names = [m.get("name", "") for m in resp.json().get("models", [])]
            return True, any(n == MODEL or n.startswith(MODEL) for n in names)
    except Exception:
        return False, False


async def plan(req: PlanRequest) -> PlanResponse:
    """Generate the next action (plus plan preview) with validation, safety and clear errors."""
    if req.goal.strip().lower() == DEMO_GOAL:
        return mock_plan(req)

    elements = [
        {"id": e.id, "text": e.text, "desc": e.desc, "sensitive": e.sensitive}
        for e in req.elements[:MAX_ELEMENTS]
    ]
    prompt = (
        f"Goal: {req.goal}\n"
        f"Current App: {req.app_package or 'Home Screen'}\n"
        f"Screen Elements: {json.dumps(elements)}\n"
        f"History: {req.history[-6:]}"
    )

    steps: list[Step] = []
    for _ in range(2):  # retry once on malformed output
        try:
            raw = await _call_ollama(prompt)
        except Exception as exc:
            print(f"Ollama error: {exc}")
            return _error_response(req, f"ollama_unavailable: {exc}", "The AI model is not reachable right now.")
        steps = parse_steps(raw)
        if steps:
            break

    if not steps:
        return _error_response(req, "malformed_model_output", "I could not work out a safe next step.")

    if steps[-1].action != ActionType.done:
        steps.append(Step(action=ActionType.done, reason="Task completed", needs_approval=False))

    safety = apply_safety(steps, req.elements)
    return _build_response(req, safety.steps)