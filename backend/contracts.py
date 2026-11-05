"""Core data contracts and Pydantic models for FastAPI endpoints."""
from enum import Enum
from typing import Any, Optional
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


class PlanRequest(BaseModel):
    task_id: Optional[str] = None
    goal: str
    app_package: Optional[str] = None
    elements: list[UIElement] = Field(default_factory=list)
    screen_hash: Optional[str] = ""
    history: list[str] = Field(default_factory=list)
    step: int = 0


class Action(BaseModel):
    type: str
    target_id: Optional[str] = None
    text: Optional[str] = None
    reason: Optional[str] = None
    needs_approval: bool = False


class Step(BaseModel):
    action: ActionType
    target: Optional[str] = None
    text: Optional[str] = None
    reason: Optional[str] = None
    needs_approval: bool = False


class PlanResponse(BaseModel):
    task_id: str
    step: int
    screen_hash: str
    action: Optional[Action] = None
    steps: list[Step] = Field(default_factory=list)
    error: Optional[str] = None