"""ElevenLabs voice engine: text-to-speech (spoken confirmations) and speech-to-text (voice commands)."""
import os
from typing import Optional

import httpx

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "")
ELEVENLABS_VOICE_ID = os.getenv("ELEVENLABS_VOICE_ID", "pNInz6obpgDQGcFmaJgB")  # default: Adam
TTS_MODEL = os.getenv("ELEVENLABS_TTS_MODEL", "eleven_flash_v2_5")  # low latency, good for live demos
STT_MODEL = os.getenv("ELEVENLABS_STT_MODEL", "scribe_v1")
API_BASE = "https://api.elevenlabs.io/v1"

# Tiny in-memory cache so repeated phrases ("Done", "Approve?") cost no extra credits or latency.
_tts_cache: dict[tuple[str, str], bytes] = {}


async def text_to_speech(text: str, voice_id: Optional[str] = None) -> bytes:
    """Convert text to speech audio (mp3 bytes) using ElevenLabs."""
    if not ELEVENLABS_API_KEY:
        raise ValueError("ELEVENLABS_API_KEY is not configured")

    v_id = voice_id or ELEVENLABS_VOICE_ID
    key = (v_id, text)
    if key in _tts_cache:
        return _tts_cache[key]

    url = f"{API_BASE}/text-to-speech/{v_id}"
    headers = {
        "Accept": "audio/mpeg",
        "Content-Type": "application/json",
        "xi-api-key": ELEVENLABS_API_KEY,
    }
    payload = {
        "text": text,
        "model_id": TTS_MODEL,
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        audio = resp.content

    if len(_tts_cache) < 100:
        _tts_cache[key] = audio
    return audio


async def transcribe_audio(audio_bytes: bytes, filename: str = "audio.mp3",
                           content_type: str = "audio/mpeg") -> str:
    """Transcribe audio bytes to text using ElevenLabs speech-to-text."""
    if not ELEVENLABS_API_KEY:
        raise ValueError("ELEVENLABS_API_KEY is not configured")

    url = f"{API_BASE}/speech-to-text"
    headers = {"xi-api-key": ELEVENLABS_API_KEY}
    files = {"file": (filename, audio_bytes, content_type)}
    data = {"model_id": STT_MODEL}  # required by the API

    async with httpx.AsyncClient(timeout=60.0) as client:
        resp = await client.post(url, headers=headers, files=files, data=data)
        resp.raise_for_status()
        return resp.json().get("text", "")