"""ElevenLabs voice engine integration for text-to-speech and speech-to-text transcription."""
import os
from typing import Optional
import httpx

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "")
ELEVENLABS_VOICE_ID = os.getenv("ELEVENLABS_VOICE_ID", "pNInz6obpgDQGcFmaJgB")  # Adam voice


async def text_to_speech(text: str, voice_id: Optional[str] = None) -> bytes:
    """Convert text to speech audio bytes using ElevenLabs API."""
    v_id = voice_id or ELEVENLABS_VOICE_ID
    url = f"[https://api.elevenlabs.io/v1/text-to-speech/](https://api.elevenlabs.io/v1/text-to-speech/){v_id}"

    headers = {
        "Accept": "audio/mpeg",
        "Content-Type": "application/json",
        "xi-api-key": ELEVENLABS_API_KEY,
    }

    payload = {
        "text": text,
        "model_id": "eleven_monolingual_v1",
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        return resp.content


async def transcribe_audio(audio_bytes: bytes) -> str:
    """Transcribe audio bytes to text using ElevenLabs Speech-to-Text API."""
    if not ELEVENLABS_API_KEY:
        raise ValueError("ELEVENLABS_API_KEY is not configured")

    url = "[https://api.elevenlabs.io/v1/speech-to-text](https://api.elevenlabs.io/v1/speech-to-text)"
    headers = {"xi-api-key": ELEVENLABS_API_KEY}
    files = {"file": ("audio.mp3", audio_bytes, "audio/mpeg")}

    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(url, headers=headers, files=files)
        resp.raise_for_status()
        data = resp.json()
        return data.get("text", "")
    