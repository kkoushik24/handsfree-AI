"""Optional Sarvam adapter. Credentials and raw audio never enter logs."""
import base64
import binascii
import os
from typing import Literal

import httpx
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

router = APIRouter(prefix="/voice")
Language = Literal["en-IN", "kn-IN"]


class SynthesisRequest(StrictModel):
    text: str = Field(min_length=1, max_length=1000)
    language_code: Language = "en-IN"


class TranscriptionRequest(StrictModel):
    audio_base64: str = Field(min_length=1, max_length=2_700_000)
    language_code: Language = "en-IN"
    audio_format: Literal["m4a", "wav"] = "m4a"


def key_headers():
    key = os.getenv("SARVAM_API_KEY", "").strip()
    if not key:
        raise VoiceDisabledError("voice disabled: SARVAM_API_KEY not set")
    return {"api-subscription-key": key}


class VoiceDisabledError(ValueError):
    """Raised when voice was requested without an optional Sarvam credential."""


async def _synthesize(text: str, client: httpx.AsyncClient) -> bytes:
    response = await client.post(
        "https://api.sarvam.ai/text-to-speech",
        headers=key_headers(),
        json={"text": text, "language_code": "en-IN", "model": "bulbul:v3",
              "speaker": "shubh", "output_audio_codec": "wav"},
    )
    response.raise_for_status()
    encoded = response.json()["audios"][0]
    audio = base64.b64decode(encoded, validate=True)
    if not audio:
        raise ValueError("Speech playback is unavailable. Read the text response.")
    return audio


async def text_to_speech(text: str) -> bytes:
    """Return Sarvam WAV audio for the FastAPI `/speak` route."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        return await _synthesize(text, client)


async def _transcribe(audio: bytes, filename: str, content_type: str,
                      client: httpx.AsyncClient) -> str:
    headers = key_headers()
    audio_format = "wav" if "wav" in content_type.lower() or filename.lower().endswith(".wav") else "m4a"
    mime_type = "audio/wav" if audio_format == "wav" else "audio/mp4"
    response = await client.post(
        "https://api.sarvam.ai/speech-to-text",
        headers=headers,
        data={"model": "saaras:v3", "mode": "transcribe", "language_code": "en-IN"},
        files={"file": (filename, audio, mime_type)},
    )
    response.raise_for_status()
    transcript = response.json()["transcript"]
    if not isinstance(transcript, str) or not transcript.strip():
        raise ValueError("No speech recognized")
    return transcript[:2000]


async def transcribe_audio(audio: bytes, filename: str = "audio.m4a",
                           content_type: str = "audio/mp4") -> str:
    """Transcribe uploaded audio through Sarvam for the FastAPI route."""
    if not audio or len(audio) > 2_000_000:
        raise ValueError("Record a shorter instruction")
    async with httpx.AsyncClient(timeout=30.0) as client:
        return await _transcribe(audio, filename, content_type, client)


@router.post("/transcribe")
async def transcribe(request: TranscriptionRequest, connection: Request):
    key_headers()
    try:
        audio = base64.b64decode(request.audio_base64, validate=True)
    except (ValueError, binascii.Error):
        raise HTTPException(422, "Invalid audio encoding") from None
    if not audio or len(audio) > 2_000_000:
        raise HTTPException(413, "Record a shorter instruction")
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            transcript = await _transcribe(audio, "instruction." + request.audio_format,
                "audio/wav" if request.audio_format == "wav" else "audio/mp4", client)
        return {"transcript": transcript, "language_code": request.language_code}
    except (httpx.HTTPError, ValueError, KeyError, TypeError):
        raise HTTPException(502, "Speech recognition failed. Please use text input.") from None


@router.post("/synthesize")
async def synthesize(request: SynthesisRequest, connection: Request):
    key_headers()
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            audio = await _synthesize(request.text, client)
        if len(audio) > 6_000_000:
            raise ValueError("Invalid audio response")
        audio = base64.b64encode(audio).decode("ascii")
        return {"audio_base64": audio, "mime_type": "audio/wav"}
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError, binascii.Error):
        raise HTTPException(502, "Speech playback is unavailable. Read the text response.") from None
