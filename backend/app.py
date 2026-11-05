"""FastAPI application server for handsfree AI automation."""
import os
from fastapi import FastAPI, File, HTTPException, UploadFile, Response
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv

load_dotenv()

from contracts import PlanRequest, PlanResponse
from model_schema import ollama_status, plan
from voice import text_to_speech, transcribe_audio

app = FastAPI(title="Handsfree AI Backend", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health_check():
    """Verify backend health and Ollama connectivity status."""
    reachable, ready = await ollama_status()
    model_name = os.getenv("OLLAMA_MODEL", "qwen2.5:3b")
    return {
        "status": "ok",
        "model": model_name,
        "ollama_reachable": reachable,
        "model_ready": ready,
    }


@app.post("/plan", response_model=PlanResponse)
async def generate_plan(req: PlanRequest):
    """Generate task execution plan steps based on user goal and screen context."""
    return await plan(req)


@app.post("/speak")
async def speak(text: str):
    """Convert input text to speech audio stream."""
    try:
        audio_bytes = await text_to_speech(text)
        return Response(content=audio_bytes, media_type="audio/mpeg")
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Transcribe uploaded audio file to text."""
    try:
        audio_bytes = await file.read()
        text = await transcribe_audio(audio_bytes)
        return {"text": text}
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc))