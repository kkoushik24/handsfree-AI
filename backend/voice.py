"""Voice helpers using ElevenLabs: speech-to-text and text-to-speech."""
import os

import httpx

ELEVEN_KEY = os.getenv("ELEVENLABS_API_KEY", "")
VOICE_ID = os.getenv("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")  # default voice
BASE = "https://api.elevenlabs.io/v1"


class VoiceError(RuntimeError):
    pass


async def transcribe(audio: bytes, filename: str = "audio.wav", content_type: str = "audio/wav") -> str:
    if not ELEVEN_KEY:
        raise VoiceError("ELEVENLABS_API_KEY not set")
    async with httpx.AsyncClient(timeout=60) as c:
        r = await c.post(
            f"{BASE}/speech-to-text",
            headers={"xi-api-key": ELEVEN_KEY},
            data={"model_id": "scribe_v1"},
            files={"file": (filename, audio, content_type)},
        )
    if r.status_code != 200:
        raise VoiceError(f"STT failed: {r.status_code} {r.text[:200]}")
    return r.json().get("text", "").strip()


async def speak(text: str) -> bytes:
    """Returns MP3 bytes."""
    if not ELEVEN_KEY:
        raise VoiceError("ELEVENLABS_API_KEY not set")
    async with httpx.AsyncClient(timeout=60) as c:
        r = await c.post(
            f"{BASE}/text-to-speech/{VOICE_ID}",
            headers={"xi-api-key": ELEVEN_KEY, "accept": "audio/mpeg"},
            json={"text": text, "model_id": "eleven_multilingual_v2"},
        )
    if r.status_code != 200:
        raise VoiceError(f"TTS failed: {r.status_code} {r.text[:200]}")
    return r.content
