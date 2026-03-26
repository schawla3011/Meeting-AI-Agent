"""
Meeting Recorder – FastAPI Backend with Whisper Transcription

POST /upload-audio          → saves file, kicks off background transcription
GET  /transcript/{filename} → poll for transcript once upload succeeds
GET  /health                → status check

Deployment: Works locally (port 8000) and on cloud platforms (Render, Railway, etc.)
The PORT environment variable is injected automatically by cloud platforms.

WHY BACKGROUND TRANSCRIPTION?
Render's free tier proxy enforces a ~30 s request timeout. Whisper can take
longer than that for real recordings, which causes the connection to be closed
mid-response ("Stream Closed" on Android). Decoupling upload from transcription
lets the upload respond in < 5 s and the app polls for the transcript separately.
"""

import os
import logging
import threading
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
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

app = FastAPI(title="Meeting Recorder API", version="3.0.0")

# Allow requests from any origin (Android app, local testing, etc.)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# In-memory transcript store (keyed by saved filename)
# Survives the life of the process; cleared on each deployment/restart.
# ---------------------------------------------------------------------------
_transcript_store: dict[str, str] = {}
_transcript_lock  = threading.Lock()


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
async def upload_audio(
    file: UploadFile = File(...),
    background_tasks: BackgroundTasks = BackgroundTasks(),
) -> JSONResponse:
    """
    1. Validates MIME type and file size.
    2. Saves audio to `uploads/`.
    3. Returns immediately (HTTP 200) so Render's 30 s proxy timeout is not hit.
    4. Transcription runs in the background — poll GET /transcript/{filename}.
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

    # 5. Mark the transcript as "pending" so the poll endpoint knows it exists
    with _transcript_lock:
        _transcript_store[safe_name] = ""   # empty string = pending

    # 6. Kick off transcription in the background (won't block the HTTP response)
    background_tasks.add_task(_transcribe_and_store, save_path, safe_name, size_mb)

    # 7. Return immediately — Android will poll /transcript/{filename}
    return JSONResponse(
        status_code=200,
        content={
            "success":    True,
            "filename":   safe_name,
            "size_kb":    size_kb,
            "transcript": "",
            "message":    "Audio uploaded. Transcription in progress – poll /transcript/" + safe_name,
        },
    )


@app.get("/transcript/{filename}")
async def get_transcript(filename: str) -> JSONResponse:
    """
    Poll this endpoint after a successful upload.
    Returns {"ready": false} while transcription is running,
    {"ready": true, "transcript": "..."} when done.
    """
    with _transcript_lock:
        if filename not in _transcript_store:
            # Also check disk (handles server restarts between upload and poll)
            txt_path = Path(UPLOAD_DIR) / (Path(filename).stem + ".txt")
            if txt_path.exists():
                return JSONResponse({"ready": True, "transcript": txt_path.read_text("utf-8")})
            raise HTTPException(404, f"No record for '{filename}'. Was the file uploaded?")

        transcript = _transcript_store[filename]

    # Empty string = still running; any other value (including error strings) = done
    ready = transcript != ""
    return JSONResponse({"ready": ready, "transcript": transcript if ready else ""})


# ---------------------------------------------------------------------------
# Background transcription task
# ---------------------------------------------------------------------------

def _transcribe_and_store(audio_path: Path, filename: str, size_mb: float) -> None:
    """Runs in a background thread. Calls Whisper and stores the result."""
    transcript = _transcribe(audio_path, size_mb)

    # Ensure we never store empty string after transcription finishes
    # (empty string is our "pending" sentinel)
    if not transcript:
        transcript = "[No speech detected or transcription skipped]"

    with _transcript_lock:
        _transcript_store[filename] = transcript

    # Also save transcript to disk as .txt
    txt_path = audio_path.with_suffix(".txt")
    try:
        txt_path.write_text(transcript, encoding="utf-8")
        logger.info("Transcript saved → %s", txt_path)
    except OSError as exc:
        logger.warning("Could not save transcript file: %s", exc)


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
        return "[Transcription skipped: OPENAI_API_KEY not configured]"

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
