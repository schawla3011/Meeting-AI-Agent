"""
Meeting Recorder – FastAPI Backend with Whisper Transcription
POST /upload-audio  → saves file + transcribes via OpenAI Whisper
GET  /health        → status check

Deployment: Works locally (port 8000) and on cloud platforms (Render, Railway, etc.)
The PORT environment variable is injected automatically by cloud platforms.
"""

import os
import logging
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from dotenv import load_dotenv

load_dotenv()   # reads .env file if present

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
UPLOAD_DIR          = "uploads"
MAX_FILE_SIZE_MB    = 500
WHISPER_MAX_MB      = 24        # OpenAI hard limit is 25 MB; stay just under
OPENAI_API_KEY      = os.environ.get("OPENAI_API_KEY", "")
ALLOWED_TYPES       = {
    "audio/mp4", "audio/m4a", "audio/mpeg", "audio/aac",
    "audio/x-m4a", "application/octet-stream",
}

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s – %(message)s",
)
logger = logging.getLogger("meeting_recorder")

app = FastAPI(title="Meeting Recorder API", version="2.0.0")

# Allow requests from any origin (Android app, local testing, etc.)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def startup() -> None:
    Path(UPLOAD_DIR).mkdir(exist_ok=True)
    logger.info("Upload dir: %s", Path(UPLOAD_DIR).resolve())
    if not OPENAI_API_KEY:
        logger.warning(
            "OPENAI_API_KEY is not set – transcription will be skipped. "
            "Create backend/.env with OPENAI_API_KEY=sk-..."
        )


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "upload_dir": str(Path(UPLOAD_DIR).resolve()),
        "transcription_enabled": bool(OPENAI_API_KEY),
    }


@app.post("/upload-audio")
async def upload_audio(file: UploadFile = File(...)) -> JSONResponse:
    """
    1. Validates MIME type and file size.
    2. Saves audio to `uploads/`.
    3. Sends audio to OpenAI Whisper and returns the transcript.
    """

    # 1. MIME guard
    ctype = file.content_type or "application/octet-stream"
    if ctype not in ALLOWED_TYPES:
        raise HTTPException(415, f"Unsupported type: {ctype}")

    # 2. Read & size-check
    try:
        contents = await file.read()
    except Exception as exc:
        raise HTTPException(500, f"Read error: {exc}") from exc

    size_bytes = len(contents)
    size_mb    = size_bytes / (1024 * 1024)

    if size_bytes == 0:
        raise HTTPException(400, "Uploaded file is empty.")
    if size_mb > MAX_FILE_SIZE_MB:
        raise HTTPException(413, f"File too large: {size_mb:.1f} MB (max {MAX_FILE_SIZE_MB} MB)")

    # 3. Build safe filename
    safe_orig  = os.path.basename(file.filename or "recording.m4a")
    name, ext  = os.path.splitext(safe_orig)
    ext        = ext or ".m4a"
    timestamp  = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_name  = f"{name}_{timestamp}{ext}" if (Path(UPLOAD_DIR) / safe_orig).exists() else safe_orig
    save_path  = Path(UPLOAD_DIR) / safe_name

    # 4. Write to disk
    try:
        save_path.write_bytes(contents)
    except OSError as exc:
        raise HTTPException(500, f"Disk write error: {exc}") from exc

    size_kb = round(size_bytes / 1024, 1)
    logger.info("Saved '%s' (%.1f KB)", safe_name, size_kb)

    # 5. Transcribe with Whisper
    transcript = _transcribe(save_path, size_mb)

    # 6. Save transcript as a .txt file alongside the audio
    if transcript:
        txt_path = save_path.with_suffix(".txt")
        try:
            txt_path.write_text(transcript, encoding="utf-8")
            logger.info("Transcript saved → %s", txt_path)
        except OSError as exc:
            logger.warning("Could not save transcript file: %s", exc)

    return JSONResponse(
        status_code=200,
        content={
            "success":    True,
            "filename":   safe_name,
            "size_kb":    size_kb,
            "transcript": transcript,
            "message":    "Audio uploaded and transcribed successfully."
                          if transcript else "Audio uploaded (transcription skipped).",
        },
    )


# ---------------------------------------------------------------------------
# Whisper helper
# ---------------------------------------------------------------------------

def _transcribe(audio_path: Path, size_mb: float) -> str:
    """
    Calls OpenAI Whisper (whisper-1). Returns transcript text or error string.
    Compatible with openai SDK v1 and v2.
    """
    if not OPENAI_API_KEY:
        logger.info("Skipping transcription – OPENAI_API_KEY not set.")
        return ""

    if size_mb > WHISPER_MAX_MB:
        logger.warning(
            "File %.1f MB exceeds Whisper limit (%d MB) – skipping.",
            size_mb, WHISPER_MAX_MB,
        )
        return f"[File too large for transcription: {size_mb:.1f} MB > {WHISPER_MAX_MB} MB limit]"

    try:
        import openai  # lazy import
        client = openai.OpenAI(api_key=OPENAI_API_KEY)

        logger.info("Sending '%s' to Whisper …", audio_path.name)
        with open(audio_path, "rb") as f:
            response = client.audio.transcriptions.create(
                model="whisper-1",
                file=f,
                response_format="text",
            )

        # openai v1: response is a plain str
        # openai v2: response may be a Transcription object with .text
        if isinstance(response, str):
            transcript = response.strip()
        else:
            transcript = getattr(response, "text", str(response)).strip()

        logger.info("Transcript (%d chars):\n%s", len(transcript), transcript)
        return transcript

    except Exception as exc:
        logger.error("Whisper transcription failed: %s", exc)
        return f"[Transcription error: {exc}]"
