"""Voice helpers using ElevenLabs: speech-to-text (Scribe) and text-to-speech."""
import os
from typing import Optional
from dotenv import load_dotenv
import httpx

# Automatically loads ELEVENLABS_API_KEY from .env
load_dotenv()

BASE_URL = "https://api.elevenlabs.io/v1"
DEFAULT_VOICE_ID = "pNInz6obpgDQGcFmaJgB"  # Adam (free tier premade voice)


class VoiceError(RuntimeError):
    """Raised when ElevenLabs credentials are missing or the API request fails."""
    pass


def get_api_key() -> str:
    """Retrieve the ElevenLabs API key dynamically."""
    key = os.getenv("ELEVENLABS_API_KEY", "").strip()
    if not key:
        raise VoiceError("ELEVENLABS_API_KEY not set in environment or .env file")
    return key


def get_voice_id() -> str:
    """Retrieve voice ID from environment or fallback to default."""
    return os.getenv("ELEVENLABS_VOICE_ID", DEFAULT_VOICE_ID).strip()


async def transcribe(audio: bytes, filename: str = "audio.wav", content_type: str = "audio/wav") -> str:
    """Send recorded speech audio to ElevenLabs Scribe STT and return the transcript string."""
    key = get_api_key()
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(
                f"{BASE_URL}/speech-to-text",
                headers={"xi-api-key": key},
                data={"model_id": "scribe_v1"},
                files={"file": (filename, audio, content_type)},
            )
    except httpx.RequestError as exc:
        raise VoiceError(f"STT network error: {exc}") from exc

    if response.status_code != 200:
        raise VoiceError(f"STT failed ({response.status_code}): {response.text[:200]}")

    payload = response.json()
    return payload.get("text", "").strip()


async def speak(text: str, voice_id: Optional[str] = None) -> bytes:
    """Convert text into spoken speech and return MP3 audio bytes."""
    if not text.strip():
        raise VoiceError("Cannot generate speech for empty text")

    key = get_api_key()
    selected_voice = voice_id or get_voice_id()

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(
                f"{BASE_URL}/text-to-speech/{selected_voice}",
                headers={
                    "xi-api-key": key,
                    "accept": "audio/mpeg",
                },
                json={
                    "text": text,
                    "model_id": "eleven_multilingual_v2",
                },
            )
    except httpx.RequestError as exc:
        raise VoiceError(f"TTS network error: {exc}") from exc

    if response.status_code != 200:
        raise VoiceError(f"TTS failed ({response.status_code}): {response.text[:200]}")

    return response.content


if __name__ == "__main__":
    import asyncio

    async def _test():
        print("Checking ElevenLabs configuration...")
        try:
            key = get_api_key()
            masked_key = key[:4] + "..." + key[-4:] if len(key) > 8 else "***"
            print(f"Loaded API Key: {masked_key}")

            print("Generating test speech sample...")
            audio_data = await speak("Handsfree AI voice engine is active.")
            out_file = "test_output.mp3"
            with open(out_file, "wb") as f:
                f.write(audio_data)
            print(f"Success! Audio saved to {out_file} ({len(audio_data)} bytes).")
        except VoiceError as err:
            print(f"Configuration or API error: {err}")

    asyncio.run(_test())
    