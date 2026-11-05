"""FastAPI application server for HandsFree AI. Run: uvicorn app:app --host 0.0.0.0 --port 8000 --reload"""
from dotenv import load_dotenv

load_dotenv()  # must run before the local imports below, which read environment variables

import httpx
from fastapi import FastAPI, File, HTTPException, Query, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import model_schema
from contracts import Health, PlanRequest, PlanResponse
from model_schema import mock_plan, ollama_status, plan
from voice import text_to_speech, transcribe_audio

app = FastAPI(title="HandsFree AI Backend", version="1.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception):
    return JSONResponse(status_code=500, content={"error": "internal_error", "details": str(exc)})


@app.get("/health", response_model=Health)
async def health_check():
    """Backend health and Ollama connectivity. 'degraded' means the AI model is not ready."""
    reachable, ready = await ollama_status()
    return Health(
        status="ok" if reachable and ready else "degraded",
        model=model_schema.MODEL,
        ollama_reachable=reachable,
        model_ready=ready,
    )


@app.post("/plan", response_model=PlanResponse)
async def generate_plan(req: PlanRequest, mock: bool = False):
    """Next action (plus plan preview) for the user's goal and current screen. ?mock=true skips the AI model."""
    if mock:
        return mock_plan(req)
    return await plan(req)


@app.post("/speak")
async def speak(text: str = Query(..., min_length=1, max_length=500)):
    """Text to speech (ElevenLabs). Returns mp3 audio."""
    try:
        audio_bytes = await text_to_speech(text)
    except ValueError as exc:  # missing API key
        raise HTTPException(status_code=503, detail=str(exc))
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"elevenlabs_error: {exc}")
    return Response(content=audio_bytes, media_type="audio/mpeg")


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Speech to text (ElevenLabs). Upload an audio file in the 'file' field."""
    try:
        audio_bytes = await file.read()
        text = await transcribe_audio(
            audio_bytes,
            filename=file.filename or "audio.mp3",
            content_type=file.content_type or "audio/mpeg",
        )
        return {"text": text}
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc))