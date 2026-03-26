"""
Meeting Recorder – FastAPI Backend
POST /upload-audio          → saves file, starts async Whisper + GPT analysis
GET  /transcript/{filename} → poll for transcript + analysis (summary, tasks)
GET  /health                → status check

Render free-tier note: The 30s proxy timeout is bypassed by returning immediately
from POST and running Whisper + GPT in a BackgroundTask.
"""

import os
import json
import logging
import smtplib
import threading
from datetime import datetime
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from dotenv import load_dotenv

load_dotenv()

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
UPLOAD_DIR       = "uploads"
MAX_FILE_SIZE_MB = 500
WHISPER_MAX_MB   = 24
OPENAI_API_KEY   = os.environ.get("OPENAI_API_KEY", "")
SMTP_EMAIL       = os.environ.get("SMTP_EMAIL", "")
SMTP_PASSWORD    = os.environ.get("SMTP_PASSWORD", "")
ALLOWED_TYPES    = {
    "audio/mp4", "audio/m4a", "audio/mpeg", "audio/aac",
    "audio/x-m4a", "application/octet-stream",
}

GPT_MODEL = "gpt-4o-mini"   # fast + cheap; swap to "gpt-4o" for higher accuracy

ANALYSIS_PROMPT = """You are an expert meeting analyst. Analyse the transcript below and return
a single, valid JSON object — no markdown fences, no extra text — in this exact schema:

{
  "summary": "<concise bullet-point summary; each bullet on its own line starting with •>",
  "tasks": [
    {
      "task": "<clear action item>",
      "owner": "<person responsible, or Unassigned if unclear>",
      "deadline": "<deadline if explicitly mentioned, otherwise Not specified>"
    }
  ]
}

Rules:
- summary should have 3-7 bullet points covering the key discussion points.
- Extract EVERY actionable task, even if vague.
- owner must be a name from the transcript. Use "Unassigned" only if no person is linked.
- deadline must be a verbatim quote from the transcript or "Not specified".
- If the transcript is too short or incoherent, return an empty tasks array and a 1-bullet summary.
- Return ONLY the JSON object, nothing else.

TRANSCRIPT:
{transcript}"""

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s – %(message)s",
)
logger = logging.getLogger("meeting_recorder")

app = FastAPI(title="Meeting Recorder API", version="3.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# In-memory stores  (keyed by saved filename)
# ---------------------------------------------------------------------------
# Sentinel convention:
#   Key absent        → file unknown
#   Value == ""       → processing in progress
#   Value == non-empty string → done (or error message)
_transcript_store: dict[str, str] = {}
_analysis_store:   dict[str, dict] = {}   # filename → {"summary": ..., "tasks": [...]}
_store_lock = threading.Lock()


@app.on_event("startup")
async def startup() -> None:
    Path(UPLOAD_DIR).mkdir(exist_ok=True)
    logger.info("Upload dir: %s", Path(UPLOAD_DIR).resolve())
    if not OPENAI_API_KEY:
        logger.warning("OPENAI_API_KEY not set – Whisper + GPT will be skipped.")


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "upload_dir": str(Path(UPLOAD_DIR).resolve()),
        "transcription_enabled": bool(OPENAI_API_KEY),
        "email_enabled": bool(SMTP_EMAIL and SMTP_PASSWORD),
        "gpt_model": GPT_MODEL,
    }


class MomRequest(dict):
    pass


@app.post("/send-mom")
async def send_mom(payload: dict) -> JSONResponse:
    """
    Sends a Meeting Minutes (MOM) email to the user.
    Payload: {to_email, user_name, company, designation, meeting_date, summary, tasks[], transcript}
    """
    to_email     = payload.get("to_email", "").strip()
    user_name    = payload.get("user_name", "there")
    company      = payload.get("company", "")
    designation  = payload.get("designation", "")
    meeting_date = payload.get("meeting_date", datetime.now().strftime("%d %b %Y"))
    summary      = payload.get("summary", "")
    tasks        = payload.get("tasks", [])
    transcript   = payload.get("transcript", "")

    if not to_email:
        raise HTTPException(400, "to_email is required")
    if not (SMTP_EMAIL and SMTP_PASSWORD):
        raise HTTPException(503, "SMTP not configured on server (SMTP_EMAIL / SMTP_PASSWORD env vars)")

    html = _build_mom_html(
        user_name=user_name, company=company, designation=designation,
        meeting_date=meeting_date, summary=summary, tasks=tasks, transcript=transcript
    )

    try:
        msg = MIMEMultipart("alternative")
        msg["Subject"] = f"📋 MOM – {meeting_date} | Anti Gravity Meeting Recorder"
        msg["From"]    = SMTP_EMAIL
        msg["To"]      = to_email
        msg.attach(MIMEText(html, "html", "utf-8"))

        with smtplib.SMTP_SSL("smtp.gmail.com", 465) as server:
            server.login(SMTP_EMAIL, SMTP_PASSWORD)
            server.sendmail(SMTP_EMAIL, to_email, msg.as_string())

        logger.info("MOM sent to %s", to_email)
        return JSONResponse({"success": True, "message": f"MOM sent to {to_email}"})

    except Exception as exc:
        logger.error("SMTP error: %s", exc)
        raise HTTPException(500, f"Email send failed: {exc}") from exc


def _build_mom_html(
    user_name: str, company: str, designation: str,
    meeting_date: str, summary: str, tasks: list, transcript: str
) -> str:
    """Returns a clean HTML email with summary, tasks table, and transcript."""

    # Summary bullets
    summary_html = ""
    if summary:
        bullets = [b.strip().lstrip("•").strip() for b in summary.split("\n") if b.strip()]
        summary_html = "".join(f"<li>{b}</li>" for b in bullets if b)

    # Tasks table rows
    tasks_rows = ""
    if tasks:
        for t in tasks:
            task_text = t.get("task", "")
            owner     = t.get("owner", "Unassigned")
            deadline  = t.get("deadline", "Not specified")
            tasks_rows += f"""
            <tr>
              <td style="padding:10px 12px;border-bottom:1px solid #2C2C2C;color:#E0E0E0;">{task_text}</td>
              <td style="padding:10px 12px;border-bottom:1px solid #2C2C2C;color:#64B5F6;">{owner}</td>
              <td style="padding:10px 12px;border-bottom:1px solid #2C2C2C;color:#FFAB40;">{deadline}</td>
            </tr>
            """

    # Transcript (truncate if very long)
    transcript_short = transcript[:3000] + ("\n\n…[truncated]" if len(transcript) > 3000 else "")
    transcript_html  = transcript_short.replace("\n", "<br>")

    profile_line = " | ".join(filter(None, [designation, company]))

    return f"""
<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"></head>
<body style="margin:0;padding:0;background:#121212;font-family:Helvetica,Arial,sans-serif;">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#121212;padding:32px 0;">
  <tr><td align="center">
    <table width="600" cellpadding="0" cellspacing="0" style="background:#1E1E1E;border-radius:12px;overflow:hidden;">

      <!-- Header -->
      <tr><td style="background:#BB86FC;padding:24px 32px;">
        <h1 style="margin:0;color:#000;font-size:22px;">🎙 Meeting Minutes</h1>
        <p style="margin:6px 0 0;color:#1a1a1a;font-size:14px;">{meeting_date}</p>
      </td></tr>

      <!-- Greeting -->
      <tr><td style="padding:24px 32px 0;">
        <p style="margin:0;color:#E0E0E0;font-size:15px;">Hi <strong>{user_name}</strong>,</p>
        {f'<p style="margin:4px 0 0;color:#9E9E9E;font-size:13px;">{profile_line}</p>' if profile_line else ''}
        <p style="margin:12px 0 0;color:#9E9E9E;font-size:14px;">Here are the minutes from your meeting. Review the summary, action items, and full transcript below.</p>
      </td></tr>

      <!-- Summary -->
      {'<tr><td style="padding:24px 32px 0;"><h2 style="margin:0 0 12px;color:#03DAC6;font-size:14px;letter-spacing:1px;">📋 MEETING SUMMARY</h2><hr style="border:none;border-top:1px solid #2C2C2C;margin-bottom:12px;"><ul style="margin:0;padding-left:20px;color:#E0E0E0;line-height:1.8;font-size:14px;">' + summary_html + '</ul></td></tr>' if summary_html else ''}

      <!-- Tasks -->
      {'<tr><td style="padding:24px 32px 0;"><h2 style="margin:0 0 12px;color:#4CAF7D;font-size:14px;letter-spacing:1px;">✅ ACTION ITEMS</h2><hr style="border:none;border-top:1px solid #2C2C2C;margin-bottom:0;"><table width="100%" cellpadding="0" cellspacing="0"><tr style="background:#252525;"><th style="padding:10px 12px;text-align:left;color:#9E9E9E;font-size:12px;">Task</th><th style="padding:10px 12px;text-align:left;color:#9E9E9E;font-size:12px;">Owner</th><th style="padding:10px 12px;text-align:left;color:#9E9E9E;font-size:12px;">Deadline</th></tr>' + tasks_rows + '</table></td></tr>' if tasks_rows else ''}

      <!-- Transcript -->
      <tr><td style="padding:24px 32px;">
        <h2 style="margin:0 0 12px;color:#BB86FC;font-size:14px;letter-spacing:1px;">📝 FULL TRANSCRIPT</h2>
        <hr style="border:none;border-top:1px solid #2C2C2C;margin-bottom:12px;">
        <p style="margin:0;color:#9E9E9E;font-size:13px;line-height:1.6;">{transcript_html}</p>
      </td></tr>

      <!-- Footer -->
      <tr><td style="background:#121212;padding:20px 32px;text-align:center;">
        <p style="margin:0;color:#616161;font-size:11px;">Sent by Anti Gravity AI Meeting Recorder</p>
      </td></tr>

    </table>
  </td></tr>
</table>
</body></html>
    """


@app.post("/upload-audio")
async def upload_audio(
    file: UploadFile = File(...),
    background_tasks: BackgroundTasks = BackgroundTasks(),
) -> JSONResponse:
    """
    1. Validate MIME + size.
    2. Save to disk.
    3. Return 200 immediately (avoids Render's 30 s proxy timeout).
    4. Whisper transcription + GPT analysis run in the background.
    5. Client polls GET /transcript/{filename} for results.
    """

    # MIME guard
    ctype = file.content_type or "application/octet-stream"
    if ctype not in ALLOWED_TYPES:
        raise HTTPException(415, f"Unsupported type: {ctype}")

    # Read & size-check
    try:
        contents = await file.read()
    except Exception as exc:
        raise HTTPException(500, f"Read error: {exc}") from exc

    size_bytes = len(contents)
    size_mb    = size_bytes / (1024 * 1024)

    if size_bytes == 0:
        raise HTTPException(400, "Uploaded file is empty.")
    if size_mb > MAX_FILE_SIZE_MB:
        raise HTTPException(413, f"File {size_mb:.1f} MB exceeds {MAX_FILE_SIZE_MB} MB limit.")

    # Safe filename
    safe_orig = os.path.basename(file.filename or "recording.m4a")
    name, ext = os.path.splitext(safe_orig)
    ext       = ext or ".m4a"
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_name = f"{name}_{timestamp}{ext}" if (Path(UPLOAD_DIR) / safe_orig).exists() else safe_orig
    save_path = Path(UPLOAD_DIR) / safe_name

    try:
        save_path.write_bytes(contents)
    except OSError as exc:
        raise HTTPException(500, f"Disk write error: {exc}") from exc

    size_kb = round(size_bytes / 1024, 1)
    logger.info("Saved '%s' (%.1f KB)", safe_name, size_kb)

    # Mark as pending
    with _store_lock:
        _transcript_store[safe_name] = ""
        _analysis_store[safe_name]   = {}

    # Kick off background pipeline: Whisper → GPT
    background_tasks.add_task(_pipeline, save_path, safe_name, size_mb)

    return JSONResponse(status_code=200, content={
        "success":  True,
        "filename": safe_name,
        "size_kb":  size_kb,
        "transcript": "",
        "analysis":   {},
        "message":  "Audio uploaded. Processing in background – poll /transcript/" + safe_name,
    })


@app.get("/transcript/{filename}")
async def get_transcript(filename: str) -> JSONResponse:
    """
    Returns processing status + results.
    {"ready": false}  → still working
    {"ready": true, "transcript": "...", "analysis": {...}}  → done
    """
    with _store_lock:
        if filename not in _transcript_store:
            # Check disk for previous runs
            txt_path  = Path(UPLOAD_DIR) / (Path(filename).stem + ".txt")
            json_path = Path(UPLOAD_DIR) / (Path(filename).stem + ".json")
            if txt_path.exists():
                transcript = txt_path.read_text("utf-8")
                analysis   = json.loads(json_path.read_text("utf-8")) if json_path.exists() else {}
                return JSONResponse({"ready": True, "transcript": transcript, "analysis": analysis})
            raise HTTPException(404, f"No record for '{filename}'.")

        transcript = _transcript_store[filename]
        analysis   = _analysis_store.get(filename, {})

    ready = transcript != ""
    return JSONResponse({
        "ready":      ready,
        "transcript": transcript if ready else "",
        "analysis":   analysis   if ready else {},
    })


# ---------------------------------------------------------------------------
# Background pipeline: Whisper → GPT
# ---------------------------------------------------------------------------

def _pipeline(audio_path: Path, filename: str, size_mb: float) -> None:
    """Runs Whisper transcription then GPT analysis; stores results."""

    # --- Step 1: Transcribe ---
    transcript = _transcribe(audio_path, size_mb)
    if not transcript:
        transcript = "[No speech detected or transcription skipped]"

    # --- Step 2: Analyse with GPT ---
    analysis = _analyze(transcript) if OPENAI_API_KEY else {}

    # --- Step 3: Store in memory ---
    with _store_lock:
        _transcript_store[filename] = transcript
        _analysis_store[filename]   = analysis

    # --- Step 4: Persist to disk ---
    try:
        audio_path.with_suffix(".txt").write_text(transcript, encoding="utf-8")
    except OSError as exc:
        logger.warning("Could not save .txt: %s", exc)

    if analysis:
        try:
            audio_path.with_suffix(".json").write_text(
                json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        except OSError as exc:
            logger.warning("Could not save .json: %s", exc)

    logger.info("Pipeline complete for '%s'", filename)


# ---------------------------------------------------------------------------
# Whisper helper
# ---------------------------------------------------------------------------

def _transcribe(audio_path: Path, size_mb: float) -> str:
    if not OPENAI_API_KEY:
        return ""
    if size_mb > WHISPER_MAX_MB:
        return f"[File too large for transcription: {size_mb:.1f} MB > {WHISPER_MAX_MB} MB limit]"
    try:
        import openai
        client = openai.OpenAI(api_key=OPENAI_API_KEY)
        logger.info("Sending '%s' to Whisper …", audio_path.name)
        with open(audio_path, "rb") as f:
            response = client.audio.transcriptions.create(
                model="whisper-1", file=f, response_format="text",
            )
        transcript = response.strip() if isinstance(response, str) else getattr(response, "text", str(response)).strip()
        logger.info("Transcript (%d chars)", len(transcript))
        return transcript
    except Exception as exc:
        logger.error("Whisper failed: %s", exc)
        return f"[Transcription error: {exc}]"


# ---------------------------------------------------------------------------
# GPT analysis helper
# ---------------------------------------------------------------------------

def _analyze(transcript: str) -> dict:
    """
    Calls GPT_MODEL to produce summary + task list from the transcript.
    Returns a dict: {"summary": str, "tasks": list[dict]}.
    Falls back to an empty dict on any error.
    """
    if not transcript or transcript.startswith("["):
        # Don't waste tokens on error strings
        return {}
    try:
        import openai
        client = openai.OpenAI(api_key=OPENAI_API_KEY)
        logger.info("Sending transcript to %s for analysis …", GPT_MODEL)

        prompt = ANALYSIS_PROMPT.replace("{transcript}", transcript)

        response = client.chat.completions.create(
            model=GPT_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,       # low temp for structured, deterministic output
            max_tokens=1500,
            response_format={"type": "json_object"},  # forces valid JSON output
        )

        raw = response.choices[0].message.content or "{}"
        result = json.loads(raw)

        # Validate and normalise schema
        summary = str(result.get("summary", "")).strip()
        tasks_raw = result.get("tasks", [])
        tasks = []
        if isinstance(tasks_raw, list):
            for t in tasks_raw:
                if isinstance(t, dict):
                    tasks.append({
                        "task":     str(t.get("task", "")).strip(),
                        "owner":    str(t.get("owner", "Unassigned")).strip(),
                        "deadline": str(t.get("deadline", "Not specified")).strip(),
                    })

        logger.info("GPT analysis: %d bullets, %d tasks", summary.count("•"), len(tasks))
        return {"summary": summary, "tasks": tasks}

    except Exception as exc:
        logger.error("GPT analysis failed: %s", exc)
        return {}
