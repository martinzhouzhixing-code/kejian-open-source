from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import io
import json
import os
import re
import secrets
import sqlite3
import string
import time
import uuid
import tempfile
from contextlib import contextmanager, suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import Response, Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from PIL import Image, ImageDraw, ImageFont
from pydantic import BaseModel, Field

from ai_recognition import (
    MAX_SOURCE_BYTES,
    MODEL as AI_MODEL,
    content_hash,
    new_job_id,
    recognize_timetable,
    verify_api_key,
)
from ai_schedule_ops import PROTOCOL as AI_TASK_PROTOCOL, parse_schedule_command
from audio_summary import NoteProviderError, ResummaryBody, regenerate_summary, validate_summary, summary_language_valid, ASR_MODEL, SUMMARY_MODEL, transcribe_and_summarize, verify_key as verify_audio_key
from ai_requests import AiRequestManager
from mindmap import MindMapBody, MindMap, generate_mindmap
from note_insights import NoteInsightsBody, generate_insights, retrieve_sources, validate_answer, validate_generated_answer


DB_PATH = Path(os.getenv("KEJIAN_DB_PATH", "/var/lib/kejian/kejian.db"))
APP_SECRET = os.environ.get("KEJIAN_APP_SECRET", "")
if len(APP_SECRET) < 32:
    raise RuntimeError("KEJIAN_APP_SECRET must contain at least 32 characters")

ACCESS_TTL = 15 * 60
REFRESH_TTL = 30 * 24 * 60 * 60
CAPTCHA_TTL = 5 * 60
MAX_SCHEDULE_BYTES = 8_000_000
RELEASE_VERSION = os.getenv("KEJIAN_RELEASE_VERSION", "1.8.1")
RELEASE_CODE = int(os.getenv("KEJIAN_RELEASE_CODE", "23"))
RELEASE_FILE = os.getenv("KEJIAN_RELEASE_FILE", f"kejian-{RELEASE_VERSION}.apk")
RELEASE_NOTES = os.getenv("KEJIAN_RELEASE_NOTES", "课间 AI 页面新增分层进场、弹性气泡、处理中呼吸与新消息平滑滚动动画；识别协议、预览确认和积分规则保持不变。")
RELEASE_SHA256 = os.getenv("KEJIAN_RELEASE_SHA256", "").lower()
PUBLIC_ORIGIN = os.getenv("KEJIAN_PUBLIC_ORIGIN", "https://example.invalid").rstrip("/")
AI_KEY_PATH = Path(os.getenv("KEJIAN_AI_KEY_PATH", "/var/lib/kejian/deepseek.key"))
AUDIO_KEY_PATH = Path(os.getenv("KEJIAN_AUDIO_API_KEY_PATH", "/var/lib/kejian/doubao-speech.key"))
AUDIO_QUEUE_DIR = Path(os.getenv("KEJIAN_AUDIO_QUEUE_DIR", "/var/lib/kejian/audio-queue"))
AUDIO_CONCURRENCY = max(1, min(8, int(os.getenv("KEJIAN_AUDIO_CONCURRENCY", "8"))))
AUDIO_QUEUE_LIMIT = max(8, min(100, int(os.getenv("KEJIAN_AUDIO_QUEUE_LIMIT", "30"))))
ADMIN_EMAIL = os.getenv("KEJIAN_ADMIN_EMAIL", "").strip().lower()
DEVELOPER_EMAIL = "unused@example.invalid"
PROMOTIONAL_SUPPORTER = os.getenv("KEJIAN_PROMOTIONAL_SUPPORTER", "1").strip().lower() not in {"0", "false", "off", "no"}
EMAIL_RE = re.compile(r"^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$", re.I)
CAPTCHA_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
ph = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2, hash_len=32, salt_len=16)

app = FastAPI(title="课间云同步", version="1.1.0", docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://example.invalid", "https://www.example.invalid"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type", "X-Audio-Duration"],
)

TEMP_AUDIO: dict[str, tuple[Path, int]] = {}
AUDIO_WORKERS: list[asyncio.Task] = []
AUDIO_WAKE: asyncio.Event | None = None
AUDIO_LEGACY_GATE = asyncio.Semaphore(1)


def now_ts() -> int:
    return int(time.time())


def iso_time(value: int | None = None) -> str:
    return datetime.fromtimestamp(value or now_ts(), timezone.utc).isoformat().replace("+00:00", "Z")


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def answer_hash(challenge_id: str, answer: str) -> str:
    return hmac.new(APP_SECRET.encode(), f"{challenge_id}:{answer.upper()}".encode(), hashlib.sha256).hexdigest()


def normalize_email(value: str) -> str:
    email = value.strip().lower()
    if len(email) > 254 or not EMAIL_RE.fullmatch(email):
        raise HTTPException(400, "请输入有效的邮箱地址")
    return email


def validate_password(value: str) -> None:
    if not 8 <= len(value) <= 72 or not re.search(r"[A-Za-z]", value) or not re.search(r"\d", value):
        raise HTTPException(400, "密码需为 8–72 位，并同时包含字母和数字")


@contextmanager
def db():
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


AI_REQUESTS = AiRequestManager(db, now_ts)


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with db() as c:
        c.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS admin_devices(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS admin_login_codes(code_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS users(
                id TEXT PRIMARY KEY,
                email TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions(
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                access_hash TEXT NOT NULL UNIQUE,
                refresh_hash TEXT NOT NULL UNIQUE,
                access_expires INTEGER NOT NULL,
                refresh_expires INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                device_name TEXT NOT NULL DEFAULT '',
                revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions(user_id);
            CREATE TABLE IF NOT EXISTS schedules(
                user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                data TEXT NOT NULL,
                revision INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS schedule_slots(
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 3),
                data TEXT NOT NULL,
                revision INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(user_id,slot)
            );
            CREATE TABLE IF NOT EXISTS captchas(
                id TEXT PRIMARY KEY,
                answer_hash TEXT NOT NULL,
                purpose TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                used INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS rate_events(
                rate_key TEXT NOT NULL,
                at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS rate_events_idx ON rate_events(rate_key,at);
            CREATE TABLE IF NOT EXISTS download_days(
                day TEXT NOT NULL,
                version TEXT NOT NULL,
                requests INTEGER NOT NULL DEFAULT 0,
                unique_devices INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(day,version)
            );
            CREATE TABLE IF NOT EXISTS download_visitors(
                day TEXT NOT NULL,
                version TEXT NOT NULL,
                visitor_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(day,version,visitor_hash)
            );
            CREATE TABLE IF NOT EXISTS ai_jobs(
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                source_hash TEXT NOT NULL,
                source_mime TEXT NOT NULL,
                model TEXT NOT NULL,
                status TEXT NOT NULL,
                course_count INTEGER NOT NULL DEFAULT 0,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                image_count INTEGER NOT NULL DEFAULT 0,
                latency_ms INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                created_at INTEGER NOT NULL,
                finished_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS ai_jobs_user_idx ON ai_jobs(user_id,created_at);
            CREATE TABLE IF NOT EXISTS admin_events(
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                action TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS client_errors(
                id TEXT PRIMARY KEY,
                user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                job_id TEXT,
                category TEXT NOT NULL,
                message TEXT NOT NULL,
                context_json TEXT NOT NULL,
                app_version TEXT NOT NULL,
                device TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS client_errors_created_idx ON client_errors(created_at);
            """
        )
        user_columns = {row["name"] for row in c.execute("PRAGMA table_info(users)").fetchall()}
        if "role" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'default'")
        if "membership_expires" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN membership_expires INTEGER")
        if "ai_points" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN ai_points INTEGER NOT NULL DEFAULT 0")
        if "points_reset_at" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN points_reset_at INTEGER")
        if "ai_token_used" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN ai_token_used INTEGER NOT NULL DEFAULT 0")
        if "audio_seconds_used" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN audio_seconds_used INTEGER NOT NULL DEFAULT 0")
        if "audio_bonus_seconds" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN audio_bonus_seconds INTEGER NOT NULL DEFAULT 0")
        if "quota_reset_at" not in user_columns:
            c.execute("ALTER TABLE users ADD COLUMN quota_reset_at INTEGER")
        job_columns = {row["name"] for row in c.execute("PRAGMA table_info(ai_jobs)").fetchall()}
        if "task_kind" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN task_kind TEXT NOT NULL DEFAULT 'image'")
        if "result_json" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN result_json TEXT")
        if "confirmed_at" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN confirmed_at INTEGER")
        if "audio_seconds" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN audio_seconds INTEGER NOT NULL DEFAULT 0")
        if "quota_reserved" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN quota_reserved INTEGER NOT NULL DEFAULT 0")
        if "source_path" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN source_path TEXT")
        if "processing_stage" not in job_columns:
            c.execute("ALTER TABLE ai_jobs ADD COLUMN processing_stage TEXT")
        c.execute("UPDATE users SET role='developer' WHERE lower(email)=?", (DEVELOPER_EMAIL,))
        # Preserve every existing account's original cloud save as slot 1.
        c.execute(
            "INSERT OR IGNORE INTO schedule_slots(user_id,slot,data,revision,updated_at) "
            "SELECT user_id,1,data,revision,updated_at FROM schedules"
        )
    AI_REQUESTS.init_schema()
    with db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS mindmap_requests(
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            request_id TEXT NOT NULL, note_id TEXT NOT NULL, source_hash TEXT NOT NULL,
            job_id TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT,
            updated_at INTEGER NOT NULL, PRIMARY KEY(user_id,request_id))""")
        c.execute("""CREATE TABLE IF NOT EXISTS note_insight_requests(
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            request_id TEXT NOT NULL, note_id TEXT NOT NULL, source_hash TEXT NOT NULL,
            job_id TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            PRIMARY KEY(user_id,request_id))""")
        c.execute("CREATE INDEX IF NOT EXISTS note_insight_expiry ON note_insight_requests(updated_at)")
        c.execute("CREATE TABLE IF NOT EXISTS summary_sources(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, transcript_hash TEXT NOT NULL, successful_revisions INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id,transcript_hash))")
        c.execute("CREATE TABLE IF NOT EXISTS summary_requests(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, request_id TEXT NOT NULL, source_hash TEXT NOT NULL, transcript_hash TEXT NOT NULL, job_id TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(user_id,request_id))")
        # Preserve only fingerprints before legacy transcript caches expire.
        # Existing counters must never be reset by a restart or migration.
        for job in c.execute("SELECT user_id,result_json FROM ai_jobs WHERE task_kind='audio' AND status='applied' AND result_json IS NOT NULL"):
            try:
                source=original_note_transcript(json.loads(job["result_json"])["noteLine"])
                if source:
                    c.execute("INSERT OR IGNORE INTO summary_sources(user_id,transcript_hash) VALUES(?,?)",(job["user_id"],hashlib.sha256(source.encode('utf-8')).hexdigest()))
            except (ValueError,KeyError,TypeError):
                continue



@app.on_event("startup")
def startup() -> None:
    init_db()


INSIGHTS_CACHE_SECONDS=7*86400
INSIGHTS_MARKER_SECONDS=30*86400
INSIGHTS_CLEANUP_TASK=None


def cleanup_note_insights():
    with db() as c:
        c.execute("UPDATE summary_requests SET result_json=NULL,state='expired' WHERE state='completed' AND updated_at<?",(now_ts()-INSIGHTS_CACHE_SECONDS,))
        c.execute("UPDATE note_insight_requests SET result_json=NULL,state='expired' WHERE state='completed' AND updated_at<?",(now_ts()-INSIGHTS_CACHE_SECONDS,))
        c.execute("DELETE FROM ai_requests WHERE job_id IN (SELECT job_id FROM note_insight_requests WHERE created_at<?)",(now_ts()-INSIGHTS_MARKER_SECONDS,))
        c.execute("DELETE FROM note_insight_requests WHERE created_at<?",(now_ts()-INSIGHTS_MARKER_SECONDS,))
        c.execute("DELETE FROM ai_jobs WHERE task_kind='insights' AND status NOT IN ('running','queued') AND created_at<?",(now_ts()-INSIGHTS_MARKER_SECONDS,))


@app.on_event("startup")
async def start_insights_cleanup():
    global INSIGHTS_CLEANUP_TASK
    cleanup_note_insights()
    async def sweep():
        while True:
            await asyncio.sleep(3600)
            with suppress(sqlite3.Error):cleanup_note_insights()
    INSIGHTS_CLEANUP_TASK=asyncio.create_task(sweep(),name="note-insights-cache-cleanup")


@app.on_event("shutdown")
async def stop_insights_cleanup():
    if INSIGHTS_CLEANUP_TASK:
        INSIGHTS_CLEANUP_TASK.cancel()
        with suppress(asyncio.CancelledError):await INSIGHTS_CLEANUP_TASK


def audio_source_signature(job_id: str) -> str:
    return hmac.new(APP_SECRET.encode(), f"audio-source:{job_id}".encode(), hashlib.sha256).hexdigest()


def audio_queue_position(job_id: str) -> int:
    with db() as c:
        row = c.execute("SELECT created_at,status FROM ai_jobs WHERE id=? AND task_kind='audio'", (job_id,)).fetchone()
        if not row or row["status"] != "queued":
            return 0
        return 1 + c.execute("SELECT COUNT(*) FROM ai_jobs WHERE task_kind='audio' AND status='queued' AND (created_at<? OR (created_at=? AND id<?))", (row["created_at"], row["created_at"], job_id)).fetchone()[0]


def audio_queue_estimate(position: int, duration: int) -> int:
    own = max(30, round(20 + duration * .09))
    return own + (max(0, position - 1) // AUDIO_CONCURRENCY) * max(60, own)


def delete_audio_file(value: str | Path | None) -> None:
    if not value:
        return
    path=Path(value)
    if path.is_file():
        path.unlink(missing_ok=True)


def claim_audio_job() -> sqlite3.Row | None:
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute("SELECT * FROM ai_jobs WHERE task_kind='audio' AND status='queued' ORDER BY created_at,id LIMIT 1").fetchone()
        if row:
            c.execute("UPDATE ai_jobs SET status='running' WHERE id=? AND status='queued'", (row["id"],))
        return row


def fail_queued_audio(job_id: str, user_id: str, duration: int, error: str) -> None:
    with db() as c:
        row = c.execute("SELECT quota_reserved FROM ai_jobs WHERE id=?", (job_id,)).fetchone()
        if row and row["quota_reserved"]:
            c.execute("UPDATE users SET audio_seconds_used=MAX(0,audio_seconds_used-?) WHERE id=?", (duration, user_id))
        c.execute("UPDATE ai_jobs SET status='failed',error=?,quota_reserved=0,source_path=NULL,finished_at=? WHERE id=?", (error[:500], now_ts(), job_id))


async def process_audio_job(row: sqlite3.Row) -> None:
    job_id=row["id"];user_id=row["user_id"];duration=int(row["audio_seconds"] or 0);path=Path(row["source_path"] or "")
    cancelled=False
    try:
        if not path.is_file():
            raise RuntimeError("排队音频已不存在，请重新上传")
        api_key=load_audio_key();summary_key=load_ai_key()
        if not api_key or not summary_key:
            raise RuntimeError("录音转写服务正在配置")
        signature=audio_source_signature(job_id)
        def on_stage(stage):
            with db() as c:
                c.execute("UPDATE ai_jobs SET processing_stage=? WHERE id=? AND status='running'", (stage, job_id))
        with db() as c:
            options=c.execute("SELECT summary_language,summary_requirements FROM audio_uploads WHERE user_id=? AND job_id=?", (user_id,job_id)).fetchone()
        summary_options={"language":options["summary_language"],"requirements":options["summary_requirements"]} if options else {}
        result,usage=await transcribe_and_summarize(f"{PUBLIC_ORIGIN}/api/v1/audio-source/{job_id}/{signature}.m4a",api_key,summary_key,on_stage=on_stage,**summary_options)
        note_id=str(uuid.uuid4());created_at=iso_time();summary=result["summary"]
        if result.get("uncertainties"):
            summary += ("\n\nNeeds review:\n" if result.get("language")=="en" else "\n\n待确认：\n") + "\n".join(f"• {item}" for item in result["uncertainties"])
        note_line="|".join(fixed_escape(x) for x in ["KJN1",note_id,None,None,result["title"],summary,json.dumps(result["keyPoints"],ensure_ascii=False,separators=(",",":")),json.dumps(result["actionItems"],ensure_ascii=False,separators=(",",":")),result["transcript"],duration,created_at,None])
        payload=json.dumps({"jobId":job_id,"noteLine":note_line,"usage":usage,"audioRetained":False},ensure_ascii=False,separators=(",",":"))
        with db() as c:
            c.execute("INSERT OR IGNORE INTO summary_sources(user_id,transcript_hash) VALUES(?,?)",(user_id,hashlib.sha256(result["transcript"].encode("utf-8")).hexdigest()))
            c.execute("UPDATE ai_jobs SET status='applied',result_json=?,course_count=1,input_tokens=?,output_tokens=?,latency_ms=?,quota_reserved=0,source_path=NULL,finished_at=? WHERE id=?",(payload,usage["inputTokens"],usage["outputTokens"],usage["latencyMs"],now_ts(),job_id))
    except asyncio.CancelledError:
        cancelled=True
        with db() as c:c.execute("UPDATE ai_jobs SET status='queued' WHERE id=? AND status='running'",(job_id,))
        raise
    except Exception as exc:
        fail_queued_audio(job_id,user_id,duration,str(exc))
    finally:
        if not cancelled:delete_audio_file(path)


async def audio_worker() -> None:
    global AUDIO_WAKE
    while True:
        row=claim_audio_job()
        if row:
            await process_audio_job(row)
            continue
        wake=AUDIO_WAKE
        if wake is None:
            await asyncio.sleep(1)
            continue
        wake.clear()
        with suppress(asyncio.TimeoutError):
            await asyncio.wait_for(wake.wait(),2)


@app.on_event("startup")
async def start_audio_queue() -> None:
    global AUDIO_WAKE,AUDIO_WORKERS
    AUDIO_QUEUE_DIR.mkdir(parents=True,exist_ok=True)
    with db() as c:
        # A provider request interrupted by a restart is safe to retry because
        # the original file and quota reservation remain durable.
        c.execute("UPDATE ai_jobs SET status='queued' WHERE task_kind='audio' AND status='running' AND source_path IS NOT NULL")
        stale=c.execute("SELECT id,user_id,audio_seconds,source_path FROM ai_jobs WHERE task_kind='audio' AND status IN ('queued','running') AND (source_path IS NULL OR created_at<?)",(now_ts()-24*3600,)).fetchall()
    for row in stale:
        delete_audio_file(row["source_path"])
        fail_queued_audio(row["id"],row["user_id"],int(row["audio_seconds"] or 0),"排队任务已过期，请重新上传")
    AUDIO_WAKE=asyncio.Event()
    AUDIO_WORKERS=[asyncio.create_task(audio_worker(),name=f"audio-worker-{index}") for index in range(AUDIO_CONCURRENCY)]


@app.on_event("shutdown")
async def stop_audio_queue() -> None:
    for task in AUDIO_WORKERS:
        task.cancel()
    for task in AUDIO_WORKERS:
        with suppress(asyncio.CancelledError):
            await task


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def rate_guard(key: str, limit: int, window: int) -> None:
    now = now_ts()
    with db() as c:
        c.execute("DELETE FROM rate_events WHERE at < ?", (now - 86400,))
        count = c.execute("SELECT COUNT(*) FROM rate_events WHERE rate_key=? AND at>=?", (key, now - window)).fetchone()[0]
        if count >= limit:
            raise HTTPException(429, "操作过于频繁，请稍后再试")
        c.execute("INSERT INTO rate_events(rate_key,at) VALUES(?,?)", (key, now))


def make_captcha(answer: str) -> bytes:
    width, height = 248, 92
    image = Image.new("RGB", (width, height), (241, 247, 242))
    draw = ImageDraw.Draw(image)
    font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    font = ImageFont.truetype(font_path, 43) if Path(font_path).exists() else ImageFont.load_default()
    rng = secrets.SystemRandom()
    for _ in range(12):
        x1, y1, x2, y2 = rng.randrange(width), rng.randrange(height), rng.randrange(width), rng.randrange(height)
        draw.line((x1, y1, x2, y2), fill=(rng.randrange(120, 205), rng.randrange(145, 215), rng.randrange(130, 205)), width=1)
    for _ in range(120):
        x, y = rng.randrange(width), rng.randrange(height)
        draw.point((x, y), fill=(rng.randrange(80, 190), rng.randrange(100, 200), rng.randrange(90, 190)))
    starts = [16, 60, 104, 148, 192]
    for index, ch in enumerate(answer):
        y = rng.randrange(14, 28)
        draw.text((starts[index] + rng.randrange(-3, 4), y), ch, font=font, fill=(20, 87 + rng.randrange(25), 72))
    output = io.BytesIO()
    image.save(output, "PNG", optimize=True)
    return output.getvalue()


def verify_captcha(challenge_id: str, answer: str, purpose: str) -> None:
    if not challenge_id or not answer:
        raise HTTPException(400, "请完成机器人验证")
    with db() as c:
        row = c.execute("SELECT * FROM captchas WHERE id=?", (challenge_id,)).fetchone()
        if not row or row["used"] or row["expires_at"] < now_ts() or row["purpose"] != purpose:
            raise HTTPException(400, "验证码已失效，请刷新后重试")
        c.execute("UPDATE captchas SET attempts=attempts+1,used=1 WHERE id=?", (challenge_id,))
        if row["attempts"] >= 4 or not hmac.compare_digest(row["answer_hash"], answer_hash(challenge_id, answer.strip())):
            raise HTTPException(400, "验证码不正确，请刷新后重试")


def issue_session(user_id: str, device_name: str) -> dict[str, Any]:
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(48)
    now = now_ts()
    session_id = str(uuid.uuid4())
    with db() as c:
        c.execute("UPDATE sessions SET revoked=1 WHERE user_id=? AND refresh_expires<?", (user_id, now))
        c.execute(
            "INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,0)",
            (session_id, user_id, token_hash(access), token_hash(refresh), now + ACCESS_TTL, now + REFRESH_TTL, now, now, device_name[:80]),
        )
        old = c.execute("SELECT id FROM sessions WHERE user_id=? AND revoked=0 ORDER BY last_seen DESC LIMIT -1 OFFSET 10", (user_id,)).fetchall()
        if old:
            c.executemany("UPDATE sessions SET revoked=1 WHERE id=?", [(r["id"],) for r in old])
    return {
        "accessToken": access,
        "refreshToken": refresh,
        "accessExpiresAt": iso_time(now + ACCESS_TTL),
        "refreshExpiresAt": iso_time(now + REFRESH_TTL),
    }


def auth_session(request: Request, authorization: str | None = Header(default=None)) -> sqlite3.Row:
    # A browser cookie grants admin endpoints only, never general account APIs.
    if not authorization and request.url.path.startswith('/api/v1/admin/'):
        cookie = request.cookies.get('__Host-kejian-admin')
        if cookie:
            if request.method not in {'GET','HEAD'}:
                require_admin_origin(request)
            with db() as c:
                row = c.execute('SELECT admin_devices.*,users.email FROM admin_devices JOIN users ON users.id=user_id WHERE token_hash=? AND expires>?',(token_hash(cookie),now_ts())).fetchone()
                if row and ADMIN_EMAIL and row['email'].lower()==ADMIN_EMAIL:
                    return row
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "登录已失效")
    hashed = token_hash(authorization[7:].strip())
    with db() as c:
        row = c.execute(
            "SELECT sessions.*,users.email FROM sessions JOIN users ON users.id=sessions.user_id WHERE access_hash=?",
            (hashed,),
        ).fetchone()
        if not row or row["revoked"] or row["access_expires"] < now_ts():
            raise HTTPException(401, "登录已失效")
        c.execute("UPDATE sessions SET last_seen=? WHERE id=?", (now_ts(), row["id"]))
        return row


def admin_session(session=Depends(auth_session)):
    if not ADMIN_EMAIL or session["email"].lower() != ADMIN_EMAIL:
        raise HTTPException(403, "没有管理员权限")
    return session


def account_profile(user_id: str) -> dict[str, Any]:
    with db() as c:
        user = c.execute("SELECT id,email,role,membership_expires,ai_points,ai_token_used,audio_seconds_used,audio_bonus_seconds,quota_reset_at FROM users WHERE id=?", (user_id,)).fetchone()
        if user and (user["quota_reset_at"] or 0) <= now_ts():
            c.execute("UPDATE users SET ai_token_used=0,audio_seconds_used=0,quota_reset_at=? WHERE id=?", (now_ts()+30*86400,user_id))
            user = c.execute("SELECT id,email,role,membership_expires,ai_points,ai_token_used,audio_seconds_used,audio_bonus_seconds,quota_reset_at FROM users WHERE id=?", (user_id,)).fetchone()
    if not user:
        raise HTTPException(401, "账号不存在")
    developer = user["role"] == "developer" or user["email"].lower() == DEVELOPER_EMAIL
    paid_role = "plus" if user["role"] == "supporter" else user["role"]
    paid_member = paid_role in {"plus","pro"} and (user["membership_expires"] or 0) > now_ts()
    role = "developer" if developer else paid_role if paid_member else "plus" if PROMOTIONAL_SUPPORTER else "default"
    member = role in {"developer","plus","pro"}
    ai_limit = None if developer else 4_000_000 if role=="pro" else 1_500_000 if role=="plus" else 0
    audio_limit = None if developer else 67_200 if role == "pro" else 7_200 if role == "plus" else 0
    return {
        "id": user["id"], "email": user["email"], "role": role,
        "membershipExpiresAt": iso_time(user["membership_expires"]) if paid_member and not developer else None,
        "aiPoints": None if developer else 0,
        "aiTokenLimit": ai_limit, "aiTokenUsed": user["ai_token_used"],
        "audioSecondsLimit": audio_limit, "audioSecondsUsed": user["audio_seconds_used"], "audioBonusSeconds": user["audio_bonus_seconds"],
        "entitlements": {"aiAssistant": member, "audioSummary": member, "widgetBackground": member, "multipleCloudSlots": member},
        "promotionActive": PROMOTIONAL_SUPPORTER,
    }


def require_ai_member(user_id: str) -> dict[str, Any]:
    profile = account_profile(user_id)
    if not profile["entitlements"]["aiAssistant"]:
        raise HTTPException(402, "AI 助手是 Plus / Pro 权益，请先开通会员")
    if profile["role"] != "developer" and profile["aiTokenUsed"] >= profile["aiTokenLimit"]:
        raise HTTPException(402, "本月 AI 额度已用完，请等待下个会员周期")
    return profile


def charge_ai_tokens(user_id: str, count: int) -> None:
    if count <= 0 or account_profile(user_id)["role"] == "developer": return
    with db() as c: c.execute("UPDATE users SET ai_token_used=ai_token_used+? WHERE id=?", (count, user_id))


def require_audio_quota(user_id: str, duration: int) -> dict[str, Any]:
    profile = account_profile(user_id)
    if not profile["entitlements"]["audioSummary"]:
        raise HTTPException(402, "AI 转写总结属于 Plus / Pro 权益")
    if profile["role"] != "developer" and profile["audioSecondsUsed"] + duration > profile["audioSecondsLimit"] + profile["audioBonusSeconds"]:
        raise HTTPException(402, "本月录音转写总结时长不足")
    return profile


def require_cloud_slot(user_id: str, slot: int) -> None:
    if slot not in (1, 2, 3):
        raise HTTPException(400, "云存档槽位无效")
    if slot > 1 and not account_profile(user_id)["entitlements"]["multipleCloudSlots"]:
        raise HTTPException(403, "额外云存档槽位是支持者会员权益")


def load_ai_key() -> str:
    try:
        value = AI_KEY_PATH.read_text(encoding="utf-8").strip()
    except OSError:
        value = os.environ.get("KEJIAN_AI_API_KEY", os.environ.get("KEJIAN_DEEPSEEK_API_KEY", "")).strip()
    return value


def load_audio_key() -> str:
    try: return AUDIO_KEY_PATH.read_text(encoding="utf-8").strip()
    except OSError: return os.environ.get("KEJIAN_AUDIO_API_KEY", "").strip()


def store_audio_key(value:str)->None:
    AUDIO_KEY_PATH.parent.mkdir(parents=True,exist_ok=True);temporary=AUDIO_KEY_PATH.with_name(f".{AUDIO_KEY_PATH.name}.{secrets.token_hex(8)}.tmp")
    try:
        temporary.write_text(value.strip()+"\n",encoding="utf-8");os.chmod(temporary,0o600);os.replace(temporary,AUDIO_KEY_PATH)
    finally: temporary.unlink(missing_ok=True)


def store_ai_key(value: str) -> None:
    AI_KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary = AI_KEY_PATH.with_name(f".{AI_KEY_PATH.name}.{secrets.token_hex(8)}.tmp")
    try:
        temporary.write_text(value.strip() + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, AI_KEY_PATH)
    finally:
        temporary.unlink(missing_ok=True)


def _validate_fixed_record(line: Any, prefix: str, field_count: int) -> None:
    limit = 2_000_000 if prefix == "KJN1" else 2000
    if not isinstance(line, str) or len(line) > limit or not line.startswith(prefix + "|"):
        raise HTTPException(400, f"{prefix} 记录无效")
    fields = 1
    escaped = False
    for char in line:
        if escaped:
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "|":
            fields += 1
    if escaped or fields != field_count:
        raise HTTPException(400, f"{prefix} 记录必须包含 {field_count} 个字段")


def validate_schedule(value: Any) -> str:
    if not isinstance(value, dict) or value.get("schemaVersion") not in (1, 2, 3, 4):
        raise HTTPException(400, "不支持的课表数据版本")
    schema = value["schemaVersion"]
    records = value.get("courses") if schema == 1 else value.get("courseLines")
    if not isinstance(value.get("settings"), dict) or not isinstance(records, list):
        raise HTTPException(400, "课表数据结构无效")
    if len(records) > 1000:
        raise HTTPException(400, "最多同步 1000 节课程")
    if schema >= 2:
        for line in records:
            _validate_fixed_record(line, "KJ1", 13)
    if schema >= 3:
        deadlines = value.get("deadlineLines")
        if not isinstance(deadlines, list):
            raise HTTPException(400, "截止日数据结构无效")
        if len(deadlines) > 1000:
            raise HTTPException(400, "最多同步 1000 个截止日")
        for line in deadlines:
            _validate_fixed_record(line, "KJD1", 9)
    if schema >= 4:
        notes = value.get("noteLines")
        if not isinstance(notes, list) or len(notes) > 2000:
            raise HTTPException(400, "课堂总结数据结构无效")
        for line in notes:
            _validate_fixed_record(line, "KJN1", 12)
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode()) > MAX_SCHEDULE_BYTES:
        raise HTTPException(413, "课表数据超过 8 MB")
    return encoded


class CaptchaRequest(BaseModel):
    purpose: str = Field(pattern="^(register|login|delete)$")


class LoginCredentials(BaseModel):
    email: str
    password: str
    deviceName: str = "Android"


class Credentials(LoginCredentials):
    captchaId: str
    captchaAnswer: str


class RefreshBody(BaseModel):
    refreshToken: str


class ScheduleBody(BaseModel):
    data: dict[str, Any]
    baseRevision: int | None = None
    force: bool = False


class DeleteBody(BaseModel):
    password: str
    captchaId: str
    captchaAnswer: str


class RecognitionBody(BaseModel):
    contentBase64: str = Field(max_length=33_554_432)
    mimeType: str = Field(pattern="^image/(jpeg|png|webp|gif)$")
    fileName: str | None = Field(default=None, max_length=180)
    colorOffset: int = Field(default=0, ge=0, le=11)
    clientRequestId: str | None = Field(default=None, pattern=r"^[a-zA-Z0-9_-]{16,80}$")


class AiTaskBody(BaseModel):
    command: str = Field(min_length=1, max_length=60_000)
    scheduleLines: list[str] = Field(default_factory=list, max_length=2000)
    clientRequestId: str | None = Field(default=None, pattern=r"^[a-zA-Z0-9_-]{16,80}$")


class ClientErrorBody(BaseModel):
    jobId: str | None = Field(default=None, max_length=80)
    category: str = Field(min_length=1, max_length=60)
    message: str = Field(min_length=1, max_length=1000)
    context: dict[str, Any] = Field(default_factory=dict)
    appVersion: str = Field(default="unknown", max_length=40)
    device: str = Field(default="Android", max_length=160)


class ReplaceAiKeyBody(BaseModel):
    apiKey: str = Field(min_length=20, max_length=240)


def public_ai_failure(exc: Exception, *, image: bool) -> tuple[int, str]:
    """Translate upstream/provider failures without hiding their real category.

    The detailed provider response remains in ai_jobs.error for the administrator;
    clients only receive an actionable, non-secret explanation.
    """
    detail = str(exc).casefold()
    unit = "识别" if image else "任务"
    if "insufficient balance" in detail or "api 402" in detail or "payment required" in detail:
        return 503, f"AI 服务余额不足，管理员补充后即可恢复；本次{unit}不扣积分"
    if "api 429" in detail or "too many requests" in detail or "rate limit" in detail or "resource exhausted" in detail:
        return 429, f"AI 服务当前繁忙，请稍后再试；本次{unit}不扣积分"
    if "timeout" in detail or "timed out" in detail:
        return 504, f"AI 服务响应超时，请稍后再试；本次{unit}不扣积分"
    if "api 404" in detail or "model not found" in detail:
        return 503, f"AI 模型暂时不可用，管理员正在恢复；本次{unit}不扣积分，请稍后重新提交"
    if "api 401" in detail or "api 403" in detail:
        return 503, f"AI 服务配置暂时失效，管理员更新后即可恢复；本次{unit}不扣积分"
    return 502, f"AI 暂时没有返回有效{'课表' if image else '操作'}，本次{unit}不扣积分"


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": str(exc.detail)})


@app.get("/api/v1/health")
def health():
    with db() as c:
        c.execute("SELECT 1").fetchone()
    return {"ok": True, "time": iso_time()}


@app.get("/api/v1/ai/status")
def ai_status():
    available = bool(load_ai_key())
    return {"available": available, "model": AI_MODEL if available else None, "protocols": ["KJ-OPS/1", AI_TASK_PROTOCOL]}


@app.post("/api/v1/ai/recognize")
async def ai_recognize(body: RecognitionBody, request: Request, session=Depends(auth_session)):
    profile = require_ai_member(session["user_id"])
    api_key = load_ai_key()
    if not api_key:
        raise HTTPException(503, "AI 识别服务正在配置")
    # Confirmed jobs are already bounded by monthly points. These short-window
    # guards stop automation without blocking two phones sharing one account.
    rate_guard(f"ai-image-user:{session['user_id']}", 20, 600)
    rate_guard(f"ai-image-ip:{client_ip(request)}", 40, 600)
    try:
        source = base64.b64decode(body.contentBase64, validate=True)
    except Exception:
        raise HTTPException(400, "图片编码无效")
    if not source or len(source) > MAX_SOURCE_BYTES:
        raise HTTPException(413, "图片为空或超过 24 MB")
    job_id = new_job_id();created = now_ts()
    AI_REQUESTS.begin(session["user_id"], body.clientRequestId, job_id)
    try:
        with db() as c:
            c.execute(
                "INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','image',?)",
                (job_id, session["user_id"], content_hash(source), body.mimeType, AI_MODEL, created),
            )
        result, usage = await AI_REQUESTS.run(request, recognize_timetable(source, api_key, body.colorOffset), session["user_id"], body.clientRequestId, keep_running_on_disconnect=request.headers.get("x-kejian-background") == "1")
        AI_REQUESTS.complete(session["user_id"], body.clientRequestId, job_id, result, usage, profile["role"] == "developer")
        return {"jobId": job_id, **result, "usage": usage, "clientRequestId": body.clientRequestId}
    except asyncio.CancelledError:
        AI_REQUESTS.stopped(session["user_id"], body.clientRequestId, job_id)
        raise HTTPException(409, "识别已停止，本次不扣额度")
    except HTTPException:
        raise
    except ValueError as exc:
        message = str(exc)[:300]
        with db() as c:
            c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?", (message, now_ts(), job_id))
        raise HTTPException(400, message)
    except Exception as exc:
        message = str(exc)[:500]
        with db() as c:
            c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?", (message, now_ts(), job_id))
        status, public_message = public_ai_failure(exc, image=True)
        raise HTTPException(status, public_message)
    finally:
        AI_REQUESTS.release(session["user_id"], body.clientRequestId)


@app.post("/api/v1/ai/task")
async def ai_task(body: AiTaskBody, request: Request, session=Depends(auth_session)):
    profile = require_ai_member(session["user_id"])
    api_key = load_ai_key()
    if not api_key:
        raise HTTPException(503, "AI 服务正在配置")
    rate_guard(f"ai-command-user:{session['user_id']}", 60, 600)
    rate_guard(f"ai-command-ip:{client_ip(request)}", 120, 600)
    for line in body.scheduleLines:
        expected = 13 if line.startswith("KJ1|") else 9 if line.startswith("KJD1|") else 0
        try:
            _validate_fixed_record(line, line.split("|", 1)[0], expected)
        except HTTPException:
            raise HTTPException(400, "当前课表格式无效")
    job_id = new_job_id(); created = now_ts()
    source_hash = hashlib.sha256((body.command + "\n" + "\n".join(body.scheduleLines)).encode()).hexdigest()
    AI_REQUESTS.begin(session["user_id"], body.clientRequestId, job_id)
    try:
        with db() as c:
            c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','command',?)",
                      (job_id, session["user_id"], source_hash, "text/plain", AI_MODEL, created))
        result, usage = await AI_REQUESTS.run(request, parse_schedule_command(body.command, body.scheduleLines, api_key), session["user_id"], body.clientRequestId, keep_running_on_disconnect=request.headers.get("x-kejian-background") == "1")
        if not result["operations"]:
            warning = "；".join(result.get("warnings") or []) or "任务不够明确，请补充课程、星期或时间"
            raise ValueError(warning)
        AI_REQUESTS.complete(session["user_id"], body.clientRequestId, job_id, result, usage, profile["role"] == "developer")
        return {"jobId": job_id, **result, "usage": usage, "clientRequestId": body.clientRequestId}
    except asyncio.CancelledError:
        AI_REQUESTS.stopped(session["user_id"], body.clientRequestId, job_id)
        raise HTTPException(409, "识别已停止，本次不扣额度")
    except HTTPException:
        raise
    except ValueError as exc:
        message = str(exc)[:500]
        with db() as c: c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?", ('INPUT_REQUIRED:'+message, now_ts(), job_id))
        raise HTTPException(400, message)
    except Exception as exc:
        with db() as c: c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?", (str(exc)[:500], now_ts(), job_id))
        status, public_message = public_ai_failure(exc, image=False)
        raise HTTPException(status, public_message)
    finally:
        AI_REQUESTS.release(session["user_id"], body.clientRequestId)


@app.get("/api/v1/ai/requests/{request_id}")
def recover_ai_request(request_id: str, session=Depends(auth_session)):
    rate_guard(f"ai-recovery-user:{session['user_id']}", 1200, 600)
    return JSONResponse(AI_REQUESTS.status(session["user_id"], request_id), headers={"Cache-Control": "no-store"})


@app.post("/api/v1/ai/requests/{request_id}/cancel")
async def cancel_ai_request(request_id: str, session=Depends(auth_session)):
    rate_guard(f"ai-cancel-user:{session['user_id']}", 120, 600)
    return AI_REQUESTS.cancel(session["user_id"], request_id)


def original_note_transcript(line: str) -> str | None:
    fields=[];current=[];escaped=False
    for char in line:
        if escaped:
            current.append("\n" if char=="n" else char);escaped=False
        elif char=="\\":escaped=True
        elif char=="|":fields.append("".join(current));current=[]
        else:current.append(char)
    fields.append("".join(current))
    return fields[8] if len(fields)==12 and fields[0]=="KJN1" and fields[8]!="~" else None


def summary_source(user_id: str, transcript: str) -> tuple[str, int]:
    fingerprint = hashlib.sha256(transcript.encode("utf-8")).hexdigest()
    with db() as c:
        row = c.execute("SELECT successful_revisions FROM summary_sources WHERE user_id=? AND transcript_hash=?", (user_id,fingerprint)).fetchone()
        if row:
            return fingerprint, row[0]
        # Legacy notes: verify the entire original transcript against an owned,
        # successful audio job. A client-controlled note id cannot reset freebies.
        for job in c.execute("SELECT result_json FROM ai_jobs WHERE user_id=? AND task_kind='audio' AND status='applied' AND result_json IS NOT NULL", (user_id,)):
            try:
                line = json.loads(job[0])["noteLine"]
                fields=[]; current=[]; escaped=False
                for char in line:
                    if escaped:
                        current.append("\n" if char=="n" else char); escaped=False
                    elif char=="\\": escaped=True
                    elif char=="|": fields.append("".join(current)); current=[]
                    else: current.append(char)
                fields.append("".join(current))
                if len(fields)==12 and fields[0]=="KJN1" and fields[8]==transcript:
                    c.execute("INSERT OR IGNORE INTO summary_sources(user_id,transcript_hash) VALUES(?,?)", (user_id,fingerprint))
                    return fingerprint, c.execute("SELECT successful_revisions FROM summary_sources WHERE user_id=? AND transcript_hash=?",(user_id,fingerprint)).fetchone()[0]
            except (ValueError, KeyError, TypeError):
                continue
    raise HTTPException(403, "找不到此账号的原始转写记录，不能重新总结 / Original transcript is not available for this account")


@app.post("/api/v1/ai/recording-summary/quote")
def summary_quote(body: ResummaryBody, session=Depends(auth_session)):
    _, used = summary_source(session["user_id"], body.transcript)
    profile=account_profile(session["user_id"])
    return {"freeRegenerationsRemaining":max(0,2-used), "usesTokens":used>=2 and profile["role"]!="developer"}


@app.post("/api/v1/ai/recording-summary")
async def recording_summary(body: ResummaryBody, request: Request, session=Depends(auth_session)):
    user_id=session["user_id"]
    source_hash=hashlib.sha256(json.dumps(body.model_dump(exclude={"requestId"}),sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    with db() as c:
        c.execute("UPDATE summary_requests SET state='failed' WHERE state='running' AND updated_at<?",(now_ts()-900,))
        c.execute("UPDATE summary_requests SET state='expired',result_json=NULL WHERE state='completed' AND updated_at<?",(now_ts()-7*86400,))
        previous=c.execute("SELECT * FROM summary_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
    if previous:
        if previous["source_hash"]!=source_hash:
            return JSONResponse(status_code=409,content={"code":"SUMMARY_FAILED","error":"任务内容已改变，请重新提交 / Request content changed"})
        if previous["state"]=="completed" and previous["result_json"]:
            return {**json.loads(previous["result_json"]),"profile":account_profile(user_id)}
        return JSONResponse(status_code=409,content={"code":"SUMMARY_RUNNING" if previous["state"]=="running" else "SUMMARY_FAILED","error":"正在处理或此前任务已结束，请稍后重试 / Processing or previous request has ended"})
    transcript_hash,used=summary_source(user_id,body.transcript)
    profile=account_profile(user_id)
    if used>=2 and profile["role"]!="developer":
        if not body.allowTokenCharge:raise HTTPException(409,"免费重新总结次数已用完，请确认使用 AI token / Confirm AI token usage")
        require_ai_member(user_id)
    api_key=load_ai_key()
    if not api_key:raise HTTPException(503,"总结服务尚未配置 / Summary service unavailable")
    rate_guard(f"resummary-user:{user_id}",12,600)
    job_id=new_job_id()
    try:AI_REQUESTS.begin(user_id,body.requestId,job_id)
    except HTTPException as exc:
        if exc.status_code!=409:raise
        with db() as c:marker=c.execute("SELECT state FROM ai_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
        return JSONResponse(status_code=409,content={"code":"SUMMARY_RUNNING" if marker and marker["state"]=="running" else "SUMMARY_FAILED","error":"任务状态正在确认或已停止 / Checking request state or stopped"})
    try:
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            if c.execute("SELECT 1 FROM summary_requests WHERE user_id=? AND state='running'",(user_id,)).fetchone():
                raise HTTPException(409,"另一份笔记正在重新总结 / Another summary is running")
            c.execute("INSERT INTO summary_requests(user_id,request_id,source_hash,transcript_hash,job_id,state,updated_at) VALUES(?,?,?,?,?,'running',?)",(user_id,body.requestId,source_hash,transcript_hash,job_id,now_ts()))
            c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','resummary',?)",(job_id,user_id,source_hash,"application/json",SUMMARY_MODEL,now_ts()))
        result,usage=await AI_REQUESTS.run(request,regenerate_summary(body,api_key),user_id,body.requestId,keep_running_on_disconnect=request.headers.get("x-kejian-background")=="1")
        record=validate_summary(result)
        if not summary_language_valid(record,body.language):raise ValueError("summary language mismatch")
        if any(type(usage.get(k)) is not int or usage[k]<0 for k in ("inputTokens","outputTokens")):raise ValueError("invalid usage")
        count=usage["inputTokens"]+usage["outputTokens"]
        if count<=0:raise ValueError("missing usage")
        profile=account_profile(user_id)
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            marker=c.execute("SELECT state,job_id FROM ai_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
            if not marker or marker["state"]=="cancelled":raise asyncio.CancelledError()
            if marker["state"]!="running" or marker["job_id"]!=job_id:raise HTTPException(409,"任务状态已改变 / Request state changed")
            used=c.execute("SELECT successful_revisions FROM summary_sources WHERE user_id=? AND transcript_hash=?",(user_id,transcript_hash)).fetchone()[0]
            # Compare trusted server results, not a client-supplied fingerprint.
            # An identical rerun must not consume another free revision or tokens.
            last=c.execute("SELECT result_json FROM summary_requests WHERE user_id=? AND transcript_hash=? AND state='completed' AND result_json IS NOT NULL ORDER BY updated_at DESC,rowid DESC LIMIT 1",(user_id,transcript_hash)).fetchone()
            same=False
            if last:
                prior=json.loads(last[0]).get("summary",{})
                same=all(prior.get(k)==getattr(record,k) for k in ("language","summary","keyPoints","actionItems","uncertainties"))
            charged=count if not same and used>=2 and profile["role"]!="developer" else 0
            if charged:
                if not body.allowTokenCharge or not profile["entitlements"]["aiAssistant"]:raise HTTPException(402,"请确认会员 AI token 用量 / Confirm member AI token usage")
                if c.execute("UPDATE users SET ai_token_used=ai_token_used+? WHERE id=? AND ai_token_used+?<=?",(charged,user_id,charged,profile["aiTokenLimit"])).rowcount!=1:
                    raise HTTPException(402,"剩余额度不足，本次不扣费 / Insufficient tokens; no charge")
            payload={"requestId":body.requestId,"jobId":job_id,"noteId":body.noteId,"contentDigest":body.contentDigest,"summary":record.model_dump(),"usage":usage,"chargedTokens":charged,"unchanged":same,"freeRegenerationsRemaining":max(0,2-used-(0 if same else 1))}
            encoded=json.dumps(payload,ensure_ascii=False,separators=(",",":"))
            if not same:c.execute("UPDATE summary_sources SET successful_revisions=successful_revisions+1 WHERE user_id=? AND transcript_hash=?",(user_id,transcript_hash))
            c.execute("UPDATE summary_requests SET state='completed',result_json=?,updated_at=? WHERE user_id=? AND request_id=?",(encoded,now_ts(),user_id,body.requestId))
            c.execute("UPDATE ai_jobs SET status='applied',input_tokens=?,output_tokens=?,latency_ms=?,finished_at=? WHERE id=?",(usage["inputTokens"],usage["outputTokens"],usage.get("latencyMs",0),now_ts(),job_id))
            c.execute("UPDATE ai_requests SET state='applied',charged_tokens=0,updated_at=? WHERE user_id=? AND request_id=?",(now_ts(),user_id,body.requestId))
        return {**payload,"profile":account_profile(user_id)}
    except asyncio.CancelledError:
        AI_REQUESTS.stopped(user_id,body.requestId,job_id)
        with db() as c:c.execute("UPDATE summary_requests SET state='cancelled',updated_at=? WHERE user_id=? AND request_id=? AND state='running'",(now_ts(),user_id,body.requestId))
        return JSONResponse(status_code=409,content={"code":"SUMMARY_FAILED","error":"已停止，不扣额度 / Stopped; no charge"})
    except Exception as exc:
        with db() as c:
            c.execute("UPDATE summary_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND state='running'",(now_ts(),user_id,body.requestId))
            c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=? AND status='running'",(str(exc) if isinstance(exc,NoteProviderError) else type(exc).__name__,now_ts(),job_id))
            c.execute("UPDATE ai_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND job_id=? AND state='running'",(now_ts(),user_id,body.requestId,job_id))
        return JSONResponse(status_code=exc.status_code if isinstance(exc,HTTPException) else 502,content={"code":exc.code if isinstance(exc,NoteProviderError) else "SUMMARY_FAILED","error":str(exc.detail) if isinstance(exc,HTTPException) else "重新总结未完成，原笔记保留，本次不扣额度 / Summary failed; original notes kept, no charge"})
    finally:AI_REQUESTS.release(user_id,body.requestId)


@app.post("/api/v1/ai/mindmap")
async def ai_mindmap(body: MindMapBody, request: Request, session=Depends(auth_session)):
    user_id = session["user_id"]; source_hash = body.source_hash()
    # Successful response replays remain available even if the current monthly
    # allowance was exhausted by the original request. Never charge a replay.
    with db() as c:
        c.execute("UPDATE mindmap_requests SET state='failed' WHERE user_id=? AND state='running' AND updated_at<?", (user_id, now_ts()-900))
        previous = c.execute("SELECT * FROM mindmap_requests WHERE user_id=? AND request_id=?", (user_id, body.requestId)).fetchone()
    if previous:
        if previous["source_hash"] != source_hash or previous["note_id"] != body.noteId:
            return JSONResponse(status_code=409, content={"error": "此任务编号已用于另一份笔记", "code": "MINDMAP_CONTENT_MISMATCH"})
        if previous["state"] == "completed" and previous["result_json"]:
            return {**json.loads(previous["result_json"]), "profile": account_profile(user_id)}
        code = "MINDMAP_RUNNING" if previous["state"] == "running" else "MINDMAP_FAILED"
        return JSONResponse(status_code=409, content={"error": "思维导图仍在生成" if code == "MINDMAP_RUNNING" else "上次任务已结束，请重新生成", "code": code})
    profile = require_ai_member(user_id); api_key = load_ai_key()
    if not api_key:
        raise HTTPException(503, "思维导图服务正在配置")
    rate_guard(f"mindmap-user:{user_id}", 12, 600)
    job_id = new_job_id()
    try:
        AI_REQUESTS.begin(user_id, body.requestId, job_id)
    except HTTPException as exc:
        if exc.status_code != 409:
            raise
        with db() as c:
            marker = c.execute("SELECT state FROM ai_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
        running = marker and marker["state"] == "running"
        return JSONResponse(status_code=409,content={"error":"任务仍在处理" if running else "任务已停止，请重新生成","code":"MINDMAP_RUNNING" if running else "MINDMAP_FAILED"})
    try:
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            # A process restart cannot leave an eternal 'running' id. Existing
            # requests age out after the provider timeout plus a safety margin.
            c.execute("UPDATE mindmap_requests SET state='failed' WHERE state='running' AND updated_at<?", (now_ts()-900,))
            if c.execute("SELECT COUNT(*) FROM mindmap_requests WHERE user_id=? AND state='running'", (user_id,)).fetchone()[0]:
                raise HTTPException(409, "另一份思维导图正在生成，请稍后再试")
            c.execute("INSERT INTO mindmap_requests(user_id,request_id,note_id,source_hash,job_id,state,updated_at) VALUES(?,?,?,?,?,'running',?)", (user_id, body.requestId, body.noteId, source_hash, job_id, now_ts()))
            c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','mindmap',?)", (job_id,user_id,source_hash,"application/json",SUMMARY_MODEL,now_ts()))
        result, usage = await AI_REQUESTS.run(request, generate_mindmap(body, api_key), user_id, body.requestId, keep_running_on_disconnect=request.headers.get("x-kejian-background") == "1")
        # Validate at the transaction boundary as well: injected/adapted providers
        # cannot charge for malformed data, cycles or oversized output.
        result = MindMap.model_validate(result).model_dump()
        count = max(0, int(usage.get("inputTokens", 0))) + max(0, int(usage.get("outputTokens", 0)))
        if count == 0:
            raise ValueError("模型未提供有效用量，请重试")
        profile = require_ai_member(user_id)
        payload = {"jobId":job_id,"requestId":body.requestId,"noteId":body.noteId,"contentDigest":body.contentDigest,"mindMap":result,"usage":usage}
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            marker = c.execute("SELECT state,job_id FROM ai_requests WHERE user_id=? AND request_id=?", (user_id,body.requestId)).fetchone()
            if not marker or marker["state"] == "cancelled":
                raise asyncio.CancelledError()
            if marker["state"] != "running" or marker["job_id"] != job_id:
                raise HTTPException(409, "任务状态已改变，请重新打开笔记")
            if profile["role"] != "developer":
                changed = c.execute("UPDATE users SET ai_token_used=ai_token_used+? WHERE id=? AND ai_token_used+?<=?", (count,user_id,count,profile["aiTokenLimit"])).rowcount
                if changed != 1:
                    raise HTTPException(402, "剩余 AI 额度不足以生成本次思维导图，本次不扣额度")
            c.execute("UPDATE ai_jobs SET status='applied',input_tokens=?,output_tokens=?,latency_ms=?,result_json=?,finished_at=? WHERE id=? AND user_id=? AND status='running'", (usage["inputTokens"],usage["outputTokens"],usage.get("latencyMs",0),encoded,now_ts(),job_id,user_id))
            c.execute("UPDATE ai_requests SET state='applied',charged_tokens=0,updated_at=? WHERE user_id=? AND request_id=?", (now_ts(),user_id,body.requestId))
            c.execute("UPDATE mindmap_requests SET state='completed',result_json=?,updated_at=? WHERE user_id=? AND request_id=?", (encoded,now_ts(),user_id,body.requestId))
        return {**payload, "profile":account_profile(user_id)}
    except asyncio.CancelledError:
        AI_REQUESTS.stopped(user_id, body.requestId, job_id)
        with db() as c:
            c.execute("UPDATE mindmap_requests SET state='cancelled',updated_at=? WHERE user_id=? AND request_id=? AND state='running'", (now_ts(),user_id,body.requestId))
        return JSONResponse(status_code=409, content={"error":"思维导图已停止，本次不扣额度","code":"MINDMAP_FAILED"})
    except Exception as exc:
        with db() as c:
            c.execute("UPDATE mindmap_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND state='running'", (now_ts(),user_id,body.requestId))
            c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=? AND user_id=? AND status='running'", (str(exc)[:500],now_ts(),job_id,user_id))
            c.execute("UPDATE ai_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND job_id=? AND state='running'",(now_ts(),user_id,body.requestId,job_id))
        if isinstance(exc, HTTPException):
            raise
        return JSONResponse(status_code=502,content={"code":exc.code if isinstance(exc,NoteProviderError) else "MINDMAP_FAILED","error":"思维导图未能完成，原笔记保留，本次不扣额度 / Mind map failed; notes kept, no charge"})
    finally:
        AI_REQUESTS.release(user_id, body.requestId)


@app.post("/api/v1/ai/note-insights")
async def note_insights(body: NoteInsightsBody, request: Request, session=Depends(auth_session)):
    user_id=session["user_id"];source_hash=body.source_hash()
    cleanup_note_insights()
    with db() as c:
        c.execute("UPDATE note_insight_requests SET state='failed',result_json=NULL WHERE user_id=? AND state='running' AND updated_at<?",(user_id,now_ts()-900))
        previous=c.execute("SELECT * FROM note_insight_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
    if previous:
        if previous["source_hash"]!=source_hash or previous["note_id"]!=body.noteId:
            return JSONResponse(status_code=409,content={"error":"任务编号已用于不同的笔记或问题","code":"INSIGHTS_CONTENT_MISMATCH"})
        if previous["state"]=="completed" and previous["result_json"]:
            return {**json.loads(previous["result_json"]),"profile":account_profile(user_id)}
        if previous["state"]=="expired":
            return JSONResponse(status_code=409,content={"error":"此前回答的服务器缓存已过期；重新提问会使用 AI token","code":"INSIGHTS_EXPIRED"})
        running=previous["state"]=="running"
        return JSONResponse(status_code=409,content={"error":"回答仍在生成" if running else "上次任务已结束或缓存已过期，请手动重新提问",
            "code":"INSIGHTS_RUNNING" if running else "INSIGHTS_FAILED"})
    require_ai_member(user_id);api_key=load_ai_key()
    if not api_key:raise HTTPException(503,"笔记问答服务正在配置")
    rate_guard(f"insights-user:{user_id}",20,600)
    job_id=new_job_id()
    try:AI_REQUESTS.begin(user_id,body.requestId,job_id)
    except HTTPException as exc:
        if exc.status_code!=409:raise
        with db() as c:marker=c.execute("SELECT state FROM ai_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
        running=marker and marker["state"]=="running"
        return JSONResponse(status_code=409,content={"error":"任务仍在处理" if running else "该任务已结束，请手动重新提问",
            "code":"INSIGHTS_RUNNING" if running else "INSIGHTS_FAILED"})
    try:
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            if c.execute("SELECT COUNT(*) FROM note_insight_requests WHERE user_id=? AND state='running'",(user_id,)).fetchone()[0]:
                raise HTTPException(409,"另一个问题仍在回答，请稍后再试")
            c.execute("INSERT INTO note_insight_requests(user_id,request_id,note_id,source_hash,job_id,state,created_at,updated_at) VALUES(?,?,?,?,?,'running',?,?)",
                (user_id,body.requestId,body.noteId,source_hash,job_id,now_ts(),now_ts()))
            c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','insights',?)",
                (job_id,user_id,source_hash,"application/json",SUMMARY_MODEL,now_ts()))
        answer,usage=await AI_REQUESTS.run(request,generate_insights(body,api_key),user_id,body.requestId, keep_running_on_disconnect=request.headers.get("x-kejian-background") == "1")
        # Revalidate at the charging boundary, even for an adapted provider.
        answer=validate_generated_answer(answer,body)
        if any(type(usage.get(key)) is not int or usage[key]<0 for key in ("inputTokens","outputTokens")):
            raise ValueError("invalid token usage")
        count=usage["inputTokens"]+usage["outputTokens"]
        if count<=0:raise ValueError("missing token usage")
        profile=require_ai_member(user_id)
        payload={"jobId":job_id,"requestId":body.requestId,"noteId":body.noteId,"contentDigest":body.contentDigest,
            "answer":answer,"usage":usage,"expiresAt":iso_time(now_ts()+INSIGHTS_CACHE_SECONDS)}
        encoded=json.dumps(payload,ensure_ascii=False,separators=(",",":"))
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            marker=c.execute("SELECT state,job_id FROM ai_requests WHERE user_id=? AND request_id=?",(user_id,body.requestId)).fetchone()
            if not marker or marker["state"]=="cancelled":raise asyncio.CancelledError()
            if marker["state"]!="running" or marker["job_id"]!=job_id:raise HTTPException(409,"任务状态已改变，请重新打开笔记")
            if profile["role"]!="developer":
                changed=c.execute("UPDATE users SET ai_token_used=ai_token_used+? WHERE id=? AND ai_token_used+?<=?",
                    (count,user_id,count,profile["aiTokenLimit"])).rowcount
                if changed!=1:raise HTTPException(402,"剩余 AI 额度不足，本次不扣额度")
            changed=c.execute("UPDATE ai_jobs SET status='applied',input_tokens=?,output_tokens=?,latency_ms=?,finished_at=? WHERE id=? AND user_id=? AND status='running'",
                (usage["inputTokens"],usage["outputTokens"],usage.get("latencyMs",0),now_ts(),job_id,user_id)).rowcount
            if changed!=1:raise HTTPException(409,"回答任务已结束，请重新打开笔记")
            c.execute("UPDATE ai_requests SET state='applied',charged_tokens=0,updated_at=? WHERE user_id=? AND request_id=?",(now_ts(),user_id,body.requestId))
            c.execute("UPDATE note_insight_requests SET state='completed',result_json=?,updated_at=? WHERE user_id=? AND request_id=?",
                (encoded,now_ts(),user_id,body.requestId))
        return {**payload,"profile":account_profile(user_id)}
    except asyncio.CancelledError:
        AI_REQUESTS.stopped(user_id,body.requestId,job_id)
        with db() as c:c.execute("UPDATE note_insight_requests SET state='cancelled',updated_at=? WHERE user_id=? AND request_id=? AND state='running'",(now_ts(),user_id,body.requestId))
        return JSONResponse(status_code=409,content={"error":"问答已停止，本次不扣额度","code":"INSIGHTS_FAILED"})
    except Exception as exc:
        with db() as c:
            c.execute("UPDATE note_insight_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND state='running'",(now_ts(),user_id,body.requestId))
            # Never log provider response text, note text, questions or history.
            c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=? AND user_id=? AND status='running'",
                (type(exc).__name__,now_ts(),job_id,user_id))
            c.execute("UPDATE ai_requests SET state='failed',updated_at=? WHERE user_id=? AND request_id=? AND job_id=? AND state='running'",(now_ts(),user_id,body.requestId,job_id))
        if isinstance(exc,HTTPException):
            if exc.status_code==409:return JSONResponse(status_code=409,content={"error":str(exc.detail),"code":"INSIGHTS_FAILED"})
            raise
        return JSONResponse(status_code=502,content={"code":"INSIGHTS_FAILED","error":"笔记问答未完成或引用未通过核对，本次不扣额度"})
    finally:AI_REQUESTS.release(user_id,body.requestId)


@app.post("/api/v1/ai/jobs/{job_id}/confirm")
def confirm_ai_job(job_id: str, session=Depends(auth_session)):
    profile = require_ai_member(session["user_id"])
    with db() as c:
        job = c.execute("SELECT status,confirmed_at FROM ai_jobs WHERE id=? AND user_id=?", (job_id, session["user_id"])).fetchone()
        if not job:
            raise HTTPException(404, "AI 任务不存在")
        if job["confirmed_at"]:
            return {"confirmed": True, "profile": account_profile(session["user_id"])}
        if job["status"] != "preview":
            raise HTTPException(409, "AI 任务尚未生成可确认的预览")
        locked = c.execute("UPDATE ai_jobs SET status='applying',confirmed_at=? WHERE id=? AND user_id=? AND status='preview' AND confirmed_at IS NULL",
                           (now_ts(), job_id, session["user_id"])).rowcount
        if locked != 1:
            raise HTTPException(409, "AI 任务正在确认，请勿重复提交")
        c.execute("UPDATE ai_jobs SET status='applied' WHERE id=?", (job_id,))
    return {"confirmed": True, "profile": account_profile(session["user_id"])}


def fixed_escape(value: Any) -> str:
    raw = "~" if value is None or value == "" else str(value)
    return raw.replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", "\\n")


@app.head("/api/v1/audio-temp/{token}.m4a")
@app.get("/api/v1/audio-temp/{token}.m4a")
def temporary_audio(token: str):
    item = TEMP_AUDIO.get(token)
    if not item or item[1] < now_ts() or not item[0].is_file():
        raise HTTPException(404, "audio expired")
    return FileResponse(item[0], media_type="audio/mp4", filename="recording.m4a")


@app.head("/api/v1/audio-source/{job_id}/{signature}.m4a")
@app.get("/api/v1/audio-source/{job_id}/{signature}.m4a")
def queued_audio_source(job_id: str, signature: str):
    if not hmac.compare_digest(signature,audio_source_signature(job_id)):
        raise HTTPException(404,"audio unavailable")
    with db() as c:
        row=c.execute("SELECT source_path,status FROM ai_jobs WHERE id=? AND task_kind='audio'",(job_id,)).fetchone()
    if not row or row["status"] != "running" or not row["source_path"]:
        raise HTTPException(404,"audio unavailable")
    path=Path(row["source_path"])
    if not path.is_file():
        raise HTTPException(404,"audio unavailable")
    return FileResponse(path,media_type="audio/mp4",filename="recording.m4a",headers={"Cache-Control":"private, no-store"})


@app.post("/api/v1/audio/jobs",status_code=202)
async def enqueue_audio_job(request:Request,x_audio_duration:int=Header(alias="X-Audio-Duration"),session=Depends(auth_session)):
    global AUDIO_WAKE
    if x_audio_duration < 10 or x_audio_duration > 5*3600:
        raise HTTPException(400,"单次录音时长应为 10 秒到 5 小时")
    require_audio_quota(session["user_id"],x_audio_duration)
    if not load_audio_key() or not load_ai_key():
        raise HTTPException(503,"录音转写服务正在配置，请管理员填写豆包语音 API Key")
    rate_guard(f"audio-user:{session['user_id']}",12,3600)
    with db() as c:
        queued=c.execute("SELECT COUNT(*) FROM ai_jobs WHERE task_kind='audio' AND status='queued'").fetchone()[0]
        own=c.execute("SELECT COUNT(*) FROM ai_jobs WHERE task_kind='audio' AND user_id=? AND status IN ('queued','running')",(session["user_id"],)).fetchone()[0]
        c.execute("UPDATE ai_jobs SET result_json=NULL WHERE task_kind='audio' AND finished_at IS NOT NULL AND finished_at<?",(now_ts()-7*86400,))
    if queued >= AUDIO_QUEUE_LIMIT:
        raise HTTPException(503,"当前转写队列已满，请稍后再试；本次不扣时长")
    if own >= 2:
        raise HTTPException(429,"每个账号最多同时保留 2 个转写任务，请等待现有任务完成")
    job_id=new_job_id();path=AUDIO_QUEUE_DIR/f"{job_id}.m4a";temporary=path.with_suffix(".upload");total=0;digest=hashlib.sha256();queued_job=False
    try:
        with temporary.open("wb") as out:
            async for chunk in request.stream():
                total+=len(chunk)
                if total>64*1024*1024:
                    raise HTTPException(413,"旧版上传方式仅支持 64 MB，请更新应用后使用自动分段上传（最高 512 MB）")
                digest.update(chunk);out.write(chunk)
        if total<512:
            raise HTTPException(400,"录音文件为空")
        temporary.replace(path)
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            reserve_audio_upload_job(c,session["user_id"],x_audio_duration,str(path),digest.hexdigest(),job_id)
        queued_job=True
        position=audio_queue_position(job_id)
        if AUDIO_WAKE is not None:AUDIO_WAKE.set()
        return {"jobId":job_id,"status":"queued","queuePosition":position,"estimatedSeconds":audio_queue_estimate(position,x_audio_duration),"maxConcurrent":AUDIO_CONCURRENCY}
    except BaseException:
        # Once committed, the queue owns the file and reservation even if the
        # client disconnects before receiving its acknowledgment.
        if not queued_job:
            delete_audio_file(temporary);delete_audio_file(path)
        raise


@app.get("/api/v1/audio/jobs/{job_id}")
def audio_job_status(job_id:str,session=Depends(auth_session)):
    with db() as c:
        row=c.execute("SELECT id,status,error,result_json,audio_seconds,created_at,processing_stage FROM ai_jobs WHERE id=? AND user_id=? AND task_kind='audio'",(job_id,session["user_id"])).fetchone()
    if not row:
        raise HTTPException(404,"转写任务不存在")
    status=row["status"]
    if status=="queued":
        position=audio_queue_position(job_id)
        return {"jobId":job_id,"status":"queued","queuePosition":position,"estimatedSeconds":audio_queue_estimate(position,int(row["audio_seconds"] or 0)),"maxConcurrent":AUDIO_CONCURRENCY}
    if status=="running":
        return {"jobId":job_id,"status":"running","stage":row["processing_stage"] or "transcribing","queuePosition":0,"estimatedSeconds":audio_queue_estimate(0,int(row["audio_seconds"] or 0)),"maxConcurrent":AUDIO_CONCURRENCY}
    if status=="applied" and row["result_json"]:
        return {**json.loads(row["result_json"]),"status":"completed","queuePosition":0}
    if status=="failed":
        return {"jobId":job_id,"status":"failed","queuePosition":0,"error":row["error"] or "转写失败，本次不扣时长"}
    return {"jobId":job_id,"status":status,"queuePosition":0}


@app.post("/api/v1/audio/transcribe")
async def audio_transcribe(request: Request, x_audio_duration: int = Header(alias="X-Audio-Duration"), session=Depends(auth_session)):
    if x_audio_duration < 10 or x_audio_duration > 5 * 3600:
        raise HTTPException(400, "单次录音时长应为 10 秒到 5 小时")
    profile = require_audio_quota(session["user_id"], x_audio_duration)
    api_key = load_audio_key()
    summary_key = load_ai_key()
    if not api_key or not summary_key:
        raise HTTPException(503, "录音转写服务正在配置，请管理员填写豆包语音 API Key")
    rate_guard(f"audio-user:{session['user_id']}", 12, 3600)
    token = secrets.token_urlsafe(32);path: Path | None = None;total = 0;job_id = new_job_id();started = now_ts();reserved = False;succeeded = False
    try:
        handle, name = tempfile.mkstemp(prefix="kejian-audio-", suffix=".m4a");os.close(handle);path = Path(name)
        with path.open("wb") as out:
            async for chunk in request.stream():
                total += len(chunk)
                if total > 64 * 1024 * 1024: raise HTTPException(413, "旧版上传方式仅支持 64 MB，请更新应用后使用自动分段上传（最高 512 MB）")
                out.write(chunk)
        if total < 512: raise HTTPException(400, "录音文件为空")
        # Reserve before invoking the paid providers so simultaneous requests from
        # one account cannot overrun its allowance. Any unsuccessful task is
        # refunded in finally; only a delivered result consumes time.
        if profile["role"] != "developer":
            with db() as c: c.execute("UPDATE users SET audio_seconds_used=audio_seconds_used+? WHERE id=?",(x_audio_duration,session["user_id"]))
            reserved = True
        TEMP_AUDIO[token] = (path, now_ts()+900)
        with db() as c: c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at) VALUES(?,?,?,?,?,'running','audio',?)",(job_id,session["user_id"],hashlib.sha256(path.read_bytes()).hexdigest(),"audio/mp4",f"{ASR_MODEL}+{SUMMARY_MODEL}",started))
        async with AUDIO_LEGACY_GATE:
            result, usage = await transcribe_and_summarize(f"{PUBLIC_ORIGIN}/api/v1/audio-temp/{token}.m4a", api_key, summary_key)
        note_id = str(uuid.uuid4());created_at = iso_time()
        summary = result["summary"]
        if result.get("uncertainties"):
            summary += ("\n\nNeeds review:\n" if result.get("language")=="en" else "\n\n待确认：\n") + "\n".join(f"• {item}" for item in result["uncertainties"])
        note_line = "|".join(fixed_escape(x) for x in ["KJN1",note_id,None,None,result["title"],summary,json.dumps(result["keyPoints"],ensure_ascii=False,separators=(",",":")),json.dumps(result["actionItems"],ensure_ascii=False,separators=(",",":")),result["transcript"],x_audio_duration,created_at,None])
        with db() as c:
            c.execute("UPDATE ai_jobs SET status='applied',course_count=1,input_tokens=?,output_tokens=?,latency_ms=?,finished_at=? WHERE id=?",(usage["inputTokens"],usage["outputTokens"],usage["latencyMs"],now_ts(),job_id))
        succeeded = True
        return {"jobId":job_id,"noteLine":note_line,"usage":usage,"audioRetained":False,"profile":account_profile(session["user_id"])}
    except HTTPException: raise
    except ValueError as exc:
        with db() as c: c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?",(str(exc)[:500],now_ts(),job_id))
        raise HTTPException(400,str(exc)[:300])
    except Exception as exc:
        with db() as c: c.execute("UPDATE ai_jobs SET status='failed',error=?,finished_at=? WHERE id=?",(str(exc)[:500],now_ts(),job_id))
        status,message=public_ai_failure(exc,image=False);raise HTTPException(status,message.replace("任务","转写"))
    finally:
        TEMP_AUDIO.pop(token,None)
        if path is not None: path.unlink(missing_ok=True)
        if reserved and not succeeded:
            with db() as c: c.execute("UPDATE users SET audio_seconds_used=MAX(0,audio_seconds_used-?) WHERE id=?",(x_audio_duration,session["user_id"]))


@app.post("/api/v1/client-errors", status_code=202)
def client_error(body: ClientErrorBody, request: Request, session=Depends(auth_session)):
    rate_guard(f"client-error:{session['user_id']}:{client_ip(request)}", 30, 3600)
    safe_context = json.dumps(body.context, ensure_ascii=False, separators=(",", ":"))
    if len(safe_context) > 8000:
        safe_context = safe_context[:8000]
    with db() as c:
        c.execute("INSERT INTO client_errors VALUES(?,?,?,?,?,?,?,?,?)", (str(uuid.uuid4()), session["user_id"], body.jobId,
                  body.category, body.message, safe_context, body.appVersion, body.device, now_ts()))
    return {"accepted": True}


def admin_health():
    # Aggregate operational data only; no environment, keys or process arguments.
    import shutil
    result={"cpuCount":os.cpu_count(),"load1":None,"memoryTotal":None,"memoryAvailable":None}
    try:result["load1"]=round(os.getloadavg()[0],2)
    except (AttributeError,OSError):pass
    try:
        memory={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines()}
        result.update(memoryTotal=memory.get('MemTotal'),memoryAvailable=memory.get('MemAvailable'))
    except (OSError,ValueError):pass
    disk=shutil.disk_usage(Path(DB_PATH).parent)
    result.update(diskTotal=disk.total,diskFree=disk.free,audioConcurrency=AUDIO_CONCURRENCY)
    return result


@app.get("/api/v1/admin/overview")
def admin_overview(days: int = 30, session=Depends(admin_session)):
    days = max(7, min(days, 120))
    since = datetime.fromtimestamp(now_ts() - (days - 1) * 86400, timezone.utc).date().isoformat()
    with db() as c:
        totals = {
            "users": c.execute("SELECT COUNT(*) FROM users").fetchone()[0],
            "supporters": c.execute("SELECT COUNT(*) FROM users").fetchone()[0] if PROMOTIONAL_SUPPORTER else c.execute("SELECT COUNT(*) FROM users WHERE role='supporter' AND membership_expires>?", (now_ts(),)).fetchone()[0],
            "cloudSlots": c.execute("SELECT COUNT(*) FROM schedule_slots").fetchone()[0],
            "downloads": c.execute("SELECT COALESCE(SUM(requests),0) FROM download_days").fetchone()[0],
            "uniqueDownloads": c.execute("SELECT COALESCE(SUM(unique_devices),0) FROM download_days").fetchone()[0],
            "aiJobs": c.execute("SELECT COUNT(*) FROM ai_jobs").fetchone()[0],
            "clientErrors": c.execute("SELECT COUNT(*) FROM client_errors").fetchone()[0],
        }
        download_rows = c.execute(
            "SELECT day,SUM(requests) requests,SUM(unique_devices) unique_devices FROM download_days WHERE day>=? GROUP BY day ORDER BY day",
            (since,),
        ).fetchall()
        ai = c.execute(
            "SELECT COUNT(*) jobs,SUM(CASE WHEN status IN ('preview','applied') THEN 1 ELSE 0 END) successes,"
            "COALESCE(SUM(course_count),0) courses,COALESCE(SUM(input_tokens),0) input_tokens,"
            "COALESCE(SUM(output_tokens),0) output_tokens,COALESCE(AVG(CASE WHEN status IN ('preview','applied') THEN latency_ms END),0) avg_latency "
            "FROM ai_jobs WHERE task_kind IN ('image','command') AND created_at>=?",
            (now_ts() - days * 86400,),
        ).fetchone()
        recent = c.execute(
            "SELECT status,course_count,input_tokens,output_tokens,image_count,latency_ms,created_at FROM ai_jobs ORDER BY created_at DESC LIMIT 30"
        ).fetchall()
        memberships={"default":0,"plus":0,"pro":0,"developer":0}
        for row in c.execute("SELECT role,membership_expires,COUNT(*) n FROM users GROUP BY role,membership_expires"):
            role=row["role"]
            if role!="developer":role=("plus" if role=="supporter" else role) if role in {"plus","pro","supporter"} and (row["membership_expires"] or 0)>now_ts() else ("plus" if PROMOTIONAL_SUPPORTER else "default")
            memberships[role if role in memberships else "default"]+=row["n"]
        active=c.execute("SELECT COUNT(DISTINCT user_id) FROM sessions WHERE last_seen>=?",(now_ts()-7*86400,)).fetchone()[0]
        tasks=[dict(row) for row in c.execute("SELECT task_kind kind,COUNT(*) total,SUM(CASE WHEN status IN ('preview','applied') THEN 1 ELSE 0 END) succeeded,SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END) failed,COALESCE(SUM(input_tokens+output_tokens),0) tokens,COALESCE(SUM(CASE WHEN status IN ('preview','applied') THEN audio_seconds ELSE 0 END),0) audioSeconds FROM ai_jobs WHERE created_at>=? GROUP BY task_kind",(now_ts()-days*86400,))]
        queue=[dict(row) for row in c.execute("SELECT task_kind kind,status,COUNT(*) count FROM ai_jobs WHERE status IN ('queued','running') GROUP BY task_kind,status")]
        recent_errors = c.execute("SELECT category,message,app_version,device,created_at FROM client_errors ORDER BY created_at DESC LIMIT 30").fetchall()
    return {
        "memberships":memberships,"activeUsers7Days":active,"tasks":tasks,"queue":queue,"system":admin_health(),"generatedAt":iso_time(now_ts()),"periodDays":days,
        "totals": totals,
        "downloads": [{"day": row["day"], "requests": row["requests"], "unique": row["unique_devices"]} for row in download_rows],
        "ai": {
            "jobs": ai["jobs"], "successes": ai["successes"] or 0, "courses": ai["courses"],
            "inputTokens": ai["input_tokens"], "outputTokens": ai["output_tokens"], "averageLatencyMs": round(ai["avg_latency"]),
        },
        "recentAiJobs": [
            {"status": row["status"], "courses": row["course_count"], "inputTokens": row["input_tokens"],
             "outputTokens": row["output_tokens"], "images": row["image_count"], "latencyMs": row["latency_ms"],
             "createdAt": iso_time(row["created_at"])} for row in recent
        ],
        "recentClientErrors": [{"category": row["category"], "message": row["message"], "appVersion": row["app_version"],
                                "device": row["device"], "createdAt": iso_time(row["created_at"])} for row in recent_errors],
        "configuration": {"aiConfigured": bool(load_ai_key()), "audioConfigured":bool(load_audio_key()), "model": AI_MODEL, "audioModel":f"{ASR_MODEL} + {SUMMARY_MODEL}", "release": RELEASE_VERSION},
    }


@app.post("/api/v1/admin/ai-key")
async def admin_replace_ai_key(body: ReplaceAiKeyBody, request: Request, session=Depends(admin_session)):
    rate_guard(f"admin-key:{session['user_id']}:{client_ip(request)}", 5, 3600)
    candidate = body.apiKey.strip()
    try:
        await verify_api_key(candidate)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    store_ai_key(candidate)
    with db() as c:
        c.execute("INSERT INTO admin_events VALUES(?,?,?,?)", (str(uuid.uuid4()), session["user_id"], "replace_ai_key", now_ts()))
    return {"ok": True, "message": "新 API 已验证并替换。出于安全原因不会显示当前密钥。"}


@app.post("/api/v1/admin/audio-key")
async def admin_replace_audio_key(body:ReplaceAiKeyBody,request:Request,session=Depends(admin_session)):
    rate_guard(f"admin-audio-key:{session['user_id']}:{client_ip(request)}",5,3600);candidate=body.apiKey.strip()
    try: await verify_audio_key(candidate)
    except ValueError as exc: raise HTTPException(400,str(exc))
    store_audio_key(candidate)
    with db() as c:c.execute("INSERT INTO admin_events VALUES(?,?,?,?)",(str(uuid.uuid4()),session["user_id"],"replace_audio_key",now_ts()))
    return {"ok":True,"message":"新的录音转写 API 已验证并替换；当前密钥仍不会显示。"}


@app.get("/api/v1/release")
def release():
    return {
        "version": RELEASE_VERSION,
        "versionCode": RELEASE_CODE,
        "downloadUrl": f"{PUBLIC_ORIGIN}/api/v1/download?version={RELEASE_VERSION}",
        "notes": RELEASE_NOTES,
        "sha256": RELEASE_SHA256,
    }


@app.get("/api/v1/download")
def download(request: Request, version: str):
    if version != RELEASE_VERSION:
        raise HTTPException(404, "这个版本已不再提供")
    now = now_ts();day = datetime.fromtimestamp(now, timezone.utc).date().isoformat()
    fingerprint = "|".join((day, client_ip(request), request.headers.get("user-agent", "")[:300]))
    visitor_hash = hmac.new(APP_SECRET.encode(), fingerprint.encode(), hashlib.sha256).hexdigest()
    with db() as c:
        c.execute("DELETE FROM download_visitors WHERE created_at < ?", (now - 120 * 86400,))
        inserted = c.execute(
            "INSERT OR IGNORE INTO download_visitors(day,version,visitor_hash,created_at) VALUES(?,?,?,?)",
            (day, RELEASE_VERSION, visitor_hash, now),
        ).rowcount
        c.execute(
            "INSERT INTO download_days(day,version,requests,unique_devices) VALUES(?,?,1,?) "
            "ON CONFLICT(day,version) DO UPDATE SET requests=requests+1,unique_devices=unique_devices+excluded.unique_devices",
            (day, RELEASE_VERSION, 1 if inserted else 0),
        )
    return RedirectResponse(f"{PUBLIC_ORIGIN}/downloads/{RELEASE_FILE}", status_code=302, headers={"Cache-Control": "no-store"})


@app.post("/api/v1/captcha")
def captcha(body: CaptchaRequest, request: Request):
    rate_guard(f"captcha:{client_ip(request)}", 20, 3600)
    answer = "".join(secrets.choice(CAPTCHA_ALPHABET) for _ in range(5))
    challenge_id = secrets.token_urlsafe(18)
    now = now_ts()
    with db() as c:
        c.execute("DELETE FROM captchas WHERE expires_at<?", (now,))
        c.execute(
            "INSERT INTO captchas(id,answer_hash,purpose,created_at,expires_at) VALUES(?,?,?,?,?)",
            (challenge_id, answer_hash(challenge_id, answer), body.purpose, now, now + CAPTCHA_TTL),
        )
    image = base64.b64encode(make_captcha(answer)).decode()
    return {"id": challenge_id, "imageBase64": image, "expiresIn": CAPTCHA_TTL}


@app.post("/api/v1/auth/register", status_code=201)
def register(body: Credentials, request: Request):
    email = normalize_email(body.email)
    validate_password(body.password)
    rate_guard(f"register-ip:{client_ip(request)}", 8, 3600)
    rate_guard(f"register-email:{email}", 5, 86400)
    verify_captcha(body.captchaId, body.captchaAnswer, "register")
    now = now_ts()
    user_id = str(uuid.uuid4())
    with db() as c:
        if c.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
            raise HTTPException(409, "该邮箱已注册，请直接登录")
        c.execute("INSERT INTO users(id,email,password_hash,created_at,updated_at,role) VALUES(?,?,?,?,?,?)", (user_id, email, ph.hash(body.password), now, now, "developer" if email == DEVELOPER_EMAIL else "default"))
    return {"user": account_profile(user_id), **issue_session(user_id, body.deviceName)}


@app.post("/api/v1/auth/login")
def login(body: LoginCredentials, request: Request):
    email = normalize_email(body.email)
    rate_guard(f"login-ip:{client_ip(request)}", 30, 3600)
    rate_guard(f"login-email:{email}", 10, 3600)
    with db() as c:
        user = c.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
    try:
        if not user:
            ph.hash(body.password)
            raise VerifyMismatchError
        ph.verify(user["password_hash"], body.password)
    except VerifyMismatchError:
        raise HTTPException(401, "邮箱或密码错误")
    return {"user": account_profile(user["id"]), **issue_session(user["id"], body.deviceName)}


@app.post("/api/v1/auth/refresh")
def refresh(body: RefreshBody, request: Request):
    rate_guard(f"refresh:{client_ip(request)}", 120, 3600)
    hashed = token_hash(body.refreshToken)
    with db() as c:
        row = c.execute("SELECT * FROM sessions WHERE refresh_hash=?", (hashed,)).fetchone()
        if not row or row["revoked"] or row["refresh_expires"] < now_ts():
            raise HTTPException(401, "请重新登录")
        c.execute("UPDATE sessions SET revoked=1 WHERE id=?", (row["id"],))
    return issue_session(row["user_id"], row["device_name"])


@app.get("/api/v1/me")
def me(session=Depends(auth_session)):
    return account_profile(session["user_id"])


@app.post("/api/v1/auth/logout", status_code=204)
def logout(session=Depends(auth_session)):
    with db() as c:
        c.execute("UPDATE sessions SET revoked=1 WHERE id=?", (session["id"],))
    return None


@app.get("/api/v1/schedule")
def get_schedule(slot: int = 1, session=Depends(auth_session)):
    require_cloud_slot(session["user_id"], slot)
    with db() as c:
        row = c.execute("SELECT * FROM schedule_slots WHERE user_id=? AND slot=?", (session["user_id"], slot)).fetchone()
    if not row:
        return {"schedule": None}
    return {"schedule": {"data": json.loads(row["data"]), "revision": row["revision"], "updatedAt": iso_time(row["updated_at"])}}


@app.put("/api/v1/schedule")
def put_schedule(body: ScheduleBody, slot: int = 1, session=Depends(auth_session)):
    require_cloud_slot(session["user_id"], slot)
    encoded = validate_schedule(body.data)
    now = now_ts()
    with db() as c:
        row = c.execute("SELECT revision FROM schedule_slots WHERE user_id=? AND slot=?", (session["user_id"], slot)).fetchone()
        current = row["revision"] if row else 0
        if row and not body.force and body.baseRevision != current:
            raise HTTPException(409, f"云端课表已更新（版本 {current}），请先选择保留本机或云端课表")
        revision = current + 1
        c.execute(
            "INSERT INTO schedule_slots(user_id,slot,data,revision,updated_at) VALUES(?,?,?,?,?) "
            "ON CONFLICT(user_id,slot) DO UPDATE SET data=excluded.data,revision=excluded.revision,updated_at=excluded.updated_at",
            (session["user_id"], slot, encoded, revision, now),
        )
    return {"revision": revision, "updatedAt": iso_time(now)}


@app.delete("/api/v1/account", status_code=204)
def delete_account(body: DeleteBody, request: Request, session=Depends(auth_session)):
    rate_guard(f"delete:{client_ip(request)}", 5, 3600)
    verify_captcha(body.captchaId, body.captchaAnswer, "delete")
    with db() as c:
        user = c.execute("SELECT password_hash FROM users WHERE id=?", (session["user_id"],)).fetchone()
        try:
            ph.verify(user["password_hash"], body.password)
        except VerifyMismatchError:
            raise HTTPException(401, "密码不正确")
        c.execute("DELETE FROM users WHERE id=?", (session["user_id"],))
    return None


def audio_upload_preflight(user_id: str, duration: int) -> None:
    require_audio_quota(user_id, duration)
    if not load_audio_key() or not load_ai_key():
        raise HTTPException(503, "录音转写服务正在配置，请稍后重试")


def reserve_audio_upload_job(c, user_id: str, duration: int, path: str,
                             sha256: str, job_id: str | None = None) -> str:
    """Caller holds BEGIN IMMEDIATE; quota and durable job must commit together."""
    now = now_ts()
    user = c.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
    if not user:
        raise HTTPException(401, "账号不存在")
    developer = user["role"] == "developer" or user["email"].lower() == DEVELOPER_EMAIL
    paid_role = "plus" if user["role"] == "supporter" else user["role"]
    member = paid_role in {"plus", "pro"} and (user["membership_expires"] or 0) > now
    role = "developer" if developer else paid_role if member else "plus" if PROMOTIONAL_SUPPORTER else "default"
    if role == "default":
        raise HTTPException(402, "AI 转写总结属于 Plus / Pro 权益")
    if (user["quota_reset_at"] or 0) <= now:
        c.execute("UPDATE users SET ai_token_used=0,audio_seconds_used=0,quota_reset_at=? WHERE id=?", (now + 30*86400, user_id))
        used = 0
    else:
        used = user["audio_seconds_used"]
    allowance = 67200 if role == "pro" else 7200
    if not developer and used + duration > allowance + user["audio_bonus_seconds"]:
        raise HTTPException(402, "本月录音转写总结时长不足")
    queued = c.execute("SELECT COUNT(*) FROM ai_jobs WHERE task_kind='audio' AND status='queued'").fetchone()[0]
    own = c.execute("SELECT COUNT(*) FROM ai_jobs WHERE task_kind='audio' AND user_id=? AND status IN ('queued','running')", (user_id,)).fetchone()[0]
    if queued >= AUDIO_QUEUE_LIMIT:
        raise HTTPException(503, "当前转写队列已满，请稍后再试；本次不扣时长")
    if own >= 2:
        raise HTTPException(429, "每个账号最多同时保留 2 个转写任务，请等待现有任务完成")
    if not developer:
        c.execute("UPDATE users SET audio_seconds_used=audio_seconds_used+? WHERE id=?", (duration, user_id))
    job_id = job_id or new_job_id()
    c.execute("INSERT INTO ai_jobs(id,user_id,source_hash,source_mime,model,status,task_kind,created_at,audio_seconds,quota_reserved,source_path) VALUES(?,?,?,?,?,'queued','audio',?,?,?,?)",
              (job_id, user_id, sha256, "audio/mp4", f"{ASR_MODEL}+{SUMMARY_MODEL}", now, duration, 0 if developer else 1, path))
    return job_id


def wake_audio_upload_job() -> None:
    if AUDIO_WAKE is not None:
        AUDIO_WAKE.set()


init_db()

from audio_uploads import install_audio_uploads

AUDIO_UPLOADS = install_audio_uploads(
    app, auth_session=auth_session, db=db, directory=AUDIO_QUEUE_DIR / "uploads",
    preflight=audio_upload_preflight, reserve_job=reserve_audio_upload_job,
    job_status=audio_job_status, wake=wake_audio_upload_job,
    ttl_seconds=int(os.getenv("KEJIAN_AUDIO_UPLOAD_TTL", "86280")),
    per_user_limit=2, global_limit=int(os.getenv("KEJIAN_AUDIO_UPLOAD_LIMIT", "32")),
)


# Browser admin credentials are HttpOnly, host-only and explicitly revocable.
def require_admin_origin(request: Request):
    if request.headers.get('origin') != PUBLIC_ORIGIN:
        raise HTTPException(403, "请从课间官网打开管理后台")

class AdminLoginBody(LoginCredentials):
    rememberDevice: bool = False

class AdminCodeBody(BaseModel):
    code: str = Field(min_length=8,max_length=64)
    rememberDevice: bool = False

def issue_admin_device(user_id: str, remember: bool, response: Response):
    lifetime = 30*86400 if remember else 12*3600
    token = secrets.token_urlsafe(40)
    with db() as c:
        c.execute('DELETE FROM admin_devices WHERE expires<?',(now_ts(),))
        c.execute('INSERT INTO admin_devices VALUES(?,?,?)',(token_hash(token),user_id,now_ts()+lifetime))
    response.set_cookie('__Host-kejian-admin',token,max_age=lifetime if remember else None,secure=True,httponly=True,samesite='strict',path='/')
    return {'email':ADMIN_EMAIL,'expiresAt':iso_time(now_ts()+lifetime),'remembered':remember}

@app.post('/api/v1/admin/web-login')
def admin_web_login(body: AdminLoginBody, request: Request, response: Response):
    require_admin_origin(request)
    # Shares account password verification and IP/email rate limits; no login captcha.
    result=login(body,request)
    user=result['user']
    with db() as c:
        c.execute('UPDATE sessions SET revoked=1 WHERE access_hash=?',(token_hash(result['accessToken']),))
    if not ADMIN_EMAIL or user['email'].lower()!=ADMIN_EMAIL:
        raise HTTPException(403,'没有管理员权限')
    return issue_admin_device(user['id'],body.rememberDevice,response)

@app.post('/api/v1/admin/login-code')
def admin_login_code(request: Request, session=Depends(admin_session)):
    if not request.headers.get('authorization','').startswith('Bearer '):
        raise HTTPException(403,'请从已登录管理员账号的课间 App 生成登录码')
    rate_guard('admin-code:'+session['user_id'],10,3600)
    code=secrets.token_hex(8).upper()
    with db() as c:
        c.execute('DELETE FROM admin_login_codes WHERE user_id=? OR expires<?',(session['user_id'],now_ts()))
        c.execute('INSERT INTO admin_login_codes VALUES(?,?,?)',(token_hash(code),session['user_id'],now_ts()+300))
    return {'code':code,'expiresIn':300}

@app.post('/api/v1/admin/code-login')
def admin_code_login(body: AdminCodeBody, request: Request, response: Response):
    require_admin_origin(request)
    rate_guard('admin-code-login:'+client_ip(request),10,3600)
    code=re.sub(r'[\s-]','',body.code).upper()
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        row=c.execute('SELECT admin_login_codes.*,users.email FROM admin_login_codes JOIN users ON users.id=user_id WHERE code_hash=? AND expires>?',(token_hash(code),now_ts())).fetchone()
        if not row or not ADMIN_EMAIL or row['email'].lower()!=ADMIN_EMAIL:
            raise HTTPException(401,'登录码无效或已过期，请在 App 中重新生成')
        c.execute('DELETE FROM admin_login_codes WHERE code_hash=?',(token_hash(code),))
    return issue_admin_device(row['user_id'],body.rememberDevice,response)

@app.get('/api/v1/admin/session')
def admin_web_session(session=Depends(admin_session)):
    return {'email':session['email']}

@app.post('/api/v1/admin/web-logout')
def admin_web_logout(request: Request,response: Response):
    require_admin_origin(request)
    with db() as c:
        c.execute('DELETE FROM admin_devices WHERE token_hash=?',(token_hash(request.cookies.get('__Host-kejian-admin','')),))
    response.delete_cookie('__Host-kejian-admin',secure=True,httponly=True,samesite='strict',path='/')
    return {'ok':True}
