"""Core data contracts and Pydantic models for the FastAPI endpoints."""
from enum import Enum
from typing import Literal, Optional

from pydantic import BaseModel, Field


class ActionType(str, Enum):
    open_app = "open_app"
    tap = "tap"
    type_text = "type_text"
    scroll = "scroll"
    back = "back"
    ask_user = "ask_user"
    done = "done"


class UIElement(BaseModel):
    id: str
    text: Optional[str] = None
    desc: Optional[str] = None
    bounds: Optional[list[int]] = None
    clickable: bool = True
    editable: bool = False
    sensitive: bool = False  # password / OTP / payment field, set by Android


class PlanRequest(BaseModel):
    task_id: Optional[str] = None
    goal: str = Field(min_length=1)
    app_package: Optional[str] = None
    elements: list[UIElement] = Field(default_factory=list)
    screen_hash: Optional[str] = ""
    history: list[str] = Field(default_factory=list)
    step: int = 0


class Action(BaseModel):
    type: str  # tap | type | scroll | back | open_app | ask_user | done
    target_id: Optional[str] = None
    text: Optional[str] = None
    reason: Optional[str] = None
    needs_approval: bool = True


class Step(BaseModel):
    action: ActionType
    target: Optional[str] = None
    text: Optional[str] = None
    reason: Optional[str] = None
    needs_approval: bool = True


class PlanResponse(BaseModel):
    task_id: str
    step: int
    screen_hash: str  # echoed back so Android can detect stale actions
    action: Optional[Action] = None  # the NEXT action to execute (first step)
    steps: list[Step] = Field(default_factory=list)  # full plan preview
    error: Optional[str] = None


class Health(BaseModel):
    status: Literal["ok", "degraded"]
    model: str
    ollama_reachable: bool
    model_ready: bool