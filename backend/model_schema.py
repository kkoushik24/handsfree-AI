"""Gemma (via Ollama) structured action generation, validation and safe fallbacks."""
import json
import os

import httpx
from pydantic import ValidationError

from contracts import Action, PlanRequest, PlanResponse

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
MODEL = os.getenv("OLLAMA_MODEL", "gemma3n:e2b")  # set to the exact name from `ollama list`
TIMEOUT = float(os.getenv("OLLAMA_TIMEOUT", "90"))
MAX_ELEMENTS = 40
MAX_ATTEMPTS = 2

SYSTEM_PROMPT = (
    "You control an Android phone for a user who cannot use their hands. "
    "Given the user's goal, the current screen elements and the history, choose exactly ONE next action. "
    "Rules: use ONLY element ids from the list; never interact with sensitive elements "
    "(passwords, OTPs, payments) - use ask_user instead; use done when the goal is complete; "
    "use ask_user if you are unsure. Keep reason to one short sentence."
)


def _build_messages(req: PlanRequest) -> list[dict]:
    elements = [
        {"id": e.id, "text": e.text, "desc": e.desc, "clickable": e.clickable,
         "editable": e.editable, "sensitive": e.sensitive}
        for e in req.elements[:MAX_ELEMENTS]
    ]
    user = (
        f"Goal: {req.goal}\n"
        f"App: {req.app_package}\n"
        f"History: {req.history[-6:]}\n"
        f"Screen elements: {json.dumps(elements)}"
    )
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user}]


async def _call_ollama(req: PlanRequest) -> str:
    payload = {
        "model": MODEL,
        "messages": _build_messages(req),
        "stream": False,
        "format": Action.model_json_schema(),
        "options": {"temperature": 0},
    }
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        r = await client.post(f"{OLLAMA_URL}/api/chat", json=payload)
        r.raise_for_status()
        return r.json()["message"]["content"]


def _apply_safety(req: PlanRequest, action: Action) -> Action:
    """Returns a safe action, or raises ValueError if the model output is unusable."""
    ids = {e.id: e for e in req.elements}
    if action.type in ("tap", "type"):
        if action.target_id not in ids:
            raise ValueError(f"target_id {action.target_id!r} not on screen")
        if ids[action.target_id].sensitive:
            return Action(type="ask_user", reason="This field is sensitive. Please do it yourself.")
    if action.type == "type" and not action.text:
        raise ValueError("type action without text")
    action.needs_approval = True  # every action is approved by the user
    return action


def _fallback(req: PlanRequest, error: str, reason: str = "I couldn't decide a safe next step.") -> PlanResponse:
    return PlanResponse(
        task_id=req.task_id, step=req.step, screen_hash=req.screen_hash,
        action=Action(type="ask_user", reason=reason), error=error,
    )


async def plan(req: PlanRequest) -> PlanResponse:
    last_error = "unknown"
    for _ in range(MAX_ATTEMPTS):
        try:
            raw = await _call_ollama(req)
            action = _apply_safety(req, Action.model_validate_json(raw))
            return PlanResponse(task_id=req.task_id, step=req.step,
                                screen_hash=req.screen_hash, action=action)
        except httpx.HTTPError as e:
            return _fallback(req, f"model_unreachable: {e}", "The AI model is not reachable right now.")
        except (ValidationError, ValueError, KeyError) as e:
            last_error = f"malformed_model_output: {e}"
    return _fallback(req, last_error)


def mock_plan(req: PlanRequest) -> PlanResponse:
    """Canned response so Android and tests can run without Ollama."""
    clickable = next((e for e in req.elements if e.clickable and not e.sensitive), None)
    if clickable:
        action = Action(type="tap", target_id=clickable.id, reason="Mock: tapping first clickable element.")
    else:
        action = Action(type="done", reason="Mock: nothing left to do.")
    return PlanResponse(task_id=req.task_id, step=req.step, screen_hash=req.screen_hash, action=action)


async def ollama_status() -> tuple[bool, bool]:
    """Returns (ollama_reachable, model_ready)."""
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
            r.raise_for_status()
            names = [m.get("name", "") for m in r.json().get("models", [])]
            return True, any(n == MODEL or n.startswith(MODEL + ":") for n in names)
    except httpx.HTTPError:
        return False, False
