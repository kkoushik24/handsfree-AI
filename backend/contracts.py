"""API contract shared with the Android app. Change field names only after telling the team."""
from __future__ import annotations

from enum import Enum
from typing import Literal, Optional

from pydantic import BaseModel, Field


# --- Action Types & Step Definitions ---

class ActionType(str, Enum):
    tap = "tap"
    type_text = "type_text"
    ask_user = "ask_user"
    scroll = "scroll"
    open_app = "open_app"
    back = "back"
    done = "done"


class Step(BaseModel):
    action: ActionType | str
    target: Optional[str] = None
    text: Optional[str] = None
    reason: str = ""
    needs_approval: bool = False


# --- Android App Contracts ---

class UiElement(BaseModel):
    id: str
    text: str = ""
    desc: str = ""
    clickable: bool = False
    editable: bool = False
    sensitive: bool = False  # password / OTP / payment field, set by Android


class PlanRequest(BaseModel):
    task_id: str = ""
    step: int = 0
    goal: str = Field(min_length=1)
    app_package: str = ""
    screen_hash: str = ""
    elements: list[UiElement] = []
    history: list[str] = []


class Action(BaseModel):
    type: Literal["tap", "type", "scroll", "back", "open_app", "ask_user", "done"]
    target_id: Optional[str] = None
    text: Optional[str] = None
    reason: str = ""
    needs_approval: bool = True


class PlanResponse(BaseModel):
    task_id: str = ""
    step: int = 0
    screen_hash: str = ""  # echoed back so Android can detect stale actions
    action: Optional[Action] = None
    steps: list[Step] = Field(default_factory=list)
    error: Optional[str] = None


class Health(BaseModel):
    status: Literal["ok", "degraded"]
    model: str
    ollama_reachable: bool
    model_ready: bool