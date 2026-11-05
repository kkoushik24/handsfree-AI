"""FastAPI application server for HandsFree AI.

Development:
    uvicorn app:app --host 0.0.0.0 --port 8000 --reload
"""
from dotenv import load_dotenv

load_dotenv()

import httpx
import os
from fastapi import FastAPI, File, HTTPException, Query, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import model_schema
from contracts import Health, PlanRequest, PlanResponse
from model_schema import ollama_status, plan
from voice import text_to_speech, transcribe_audio

app = FastAPI(title="HandsFree AI Backend", version="1.2.0")

# For local development, set CORS_ORIGINS="*" if necessary.
# For deployment, provide a comma-separated allowlist of trusted origins.
_cors_origins = [
    origin.strip()
    for origin in os.getenv("CORS_ORIGINS", "*").split(",")
    if origin.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "Authorization"],
)


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception):
    # Avoid returning exception internals to clients.
    return JSONResponse(
        status_code=500,
        content={"error": "internal_error", "details": "An unexpected server error occurred."},
    )


@app.get("/health", response_model=Health)
async def health_check():
    reachable, ready = await ollama_status()
    return Health(
        status="ok" if reachable and ready else "degraded",
        model=model_schema.MODEL,
        ollama_reachable=reachable,
        model_ready=ready,
    )


@app.post("/plan", response_model=PlanResponse)
async def generate_plan(req: PlanRequest, mock: bool = False):
    """Return one next action. Mock and demo routes use the same safety pipeline."""
    return await plan(req, force_mock=mock)


@app.post("/speak")
async def speak(text: str = Query(..., min_length=1, max_length=500)):
    try:
        audio_bytes = await text_to_speech(text)
    except ValueError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except httpx.HTTPError:
        raise HTTPException(status_code=502, detail="speech_provider_error")
    return Response(content=audio_bytes, media_type="audio/mpeg")


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    try:
        audio_bytes = await file.read()
        text = await transcribe_audio(
            audio_bytes,
            filename=file.filename or "audio.mp3",
            content_type=file.content_type or "audio/mpeg",
        )
        return {"text": text}
    except ValueError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except httpx.HTTPError:
        raise HTTPException(status_code=502, detail="speech_provider_error")
