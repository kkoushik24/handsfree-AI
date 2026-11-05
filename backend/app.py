"""HandsFree AI backend. Run: uvicorn app:app --host 0.0.0.0 --port 8000 --reload"""
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

import model_schema
from contracts import Health, PlanRequest, PlanResponse

app = FastAPI(title="HandsFree AI Backend", version="0.1.0")


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    return JSONResponse(status_code=422, content={"error": "invalid_request", "details": exc.errors()})


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception):
    return JSONResponse(status_code=500, content={"error": "internal_error", "details": str(exc)})


@app.get("/health", response_model=Health)
async def health():
    reachable, ready = await model_schema.ollama_status()
    return Health(
        status="ok" if reachable and ready else "degraded",
        model=model_schema.MODEL,
        ollama_reachable=reachable,
        model_ready=ready,
    )


@app.post("/plan", response_model=PlanResponse)
async def plan(req: PlanRequest, mock: bool = False):
    if mock:
        return model_schema.mock_plan(req)
    return await model_schema.plan(req)
