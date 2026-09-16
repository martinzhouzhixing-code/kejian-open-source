"""Bounded, durable audio uploads. No provider calls or audio retention live here.

Confirmed offsets advance only after a whole chunk is fsynced. A database lease
serializes each upload across processes; no SQL transaction stays open while
waiting for the client. Job creation and the quota reservation share one commit.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import shutil
import sqlite3
import time
import uuid
from contextlib import suppress
from pathlib import Path
from typing import Callable

from fastapi import APIRouter, Depends, HTTPException, Request

# A negotiated ceiling, not a minimum: older clients keep sending 1 MiB chunks.
# Larger stable transfers reduce cross-region round trips without buffering a file.
CHUNK_BYTES = 4 * 1024 * 1024
MAX_BYTES = 512 * 1024 * 1024
MIN_BYTES = 512
MAX_SECONDS = 5 * 3600
CHUNK_TIMEOUT = 60
LEASE_SECONDS = CHUNK_TIMEOUT + 30


class AudioUploads:
    def __init__(self, *, db, directory: Path, preflight: Callable,
                 reserve_job: Callable, job_status: Callable, wake: Callable,
                 clock: Callable = time.time, ttl_seconds: int = 86400 - 120,
                 per_user_limit: int = 2, global_limit: int = 32,
                 disk_budget: int = 2 * 1024**3, min_free: int = 256 * 1024**2):
        self.db = db
        self.directory = Path(directory).resolve()
        self.preflight = preflight
        self.reserve_job = reserve_job
        self.job_status = job_status
        self.wake = wake
        self.clock = clock
        # Leave room for the 60-second sweeper interval: no abandoned audio >24h.
        self.ttl = max(60, min(ttl_seconds, 86400 - 120))
        self.per_user_limit = max(1, min(per_user_limit, 4))
        self.global_limit = max(1, min(global_limit, 100))
        self.disk_budget = disk_budget
        self.min_free = min_free
        self.cleanup_task = None

    def init_db(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        with self.db() as c:
            c.executescript("""
                CREATE TABLE IF NOT EXISTS audio_uploads(
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    request_id TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    uploaded_bytes INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'uploading',
                    job_id TEXT,
                    error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    lease_token TEXT,
                    lease_expires INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(user_id,request_id)
                );
                CREATE INDEX IF NOT EXISTS audio_uploads_expiry_idx
                    ON audio_uploads(status,created_at);
            """)

        with self.db() as c:
            columns = {r["name"] for r in c.execute("PRAGMA table_info(audio_uploads)")}
            if "summary_language" not in columns: c.execute("ALTER TABLE audio_uploads ADD COLUMN summary_language TEXT NOT NULL DEFAULT 'zh-CN'")
            if "summary_requirements" not in columns: c.execute("ALTER TABLE audio_uploads ADD COLUMN summary_requirements TEXT NOT NULL DEFAULT ''")

    def now(self):
        return int(self.clock())

    @staticmethod
    def request_id(value):
        try:
            return str(uuid.UUID(str(value)))
        except (ValueError, TypeError, AttributeError):
            raise HTTPException(400, "录音上传标识无效")

    def path(self, owner: str, request_id: str):
        name = hashlib.sha256(f"{owner}:{request_id}".encode()).hexdigest() + ".part"
        result = (self.directory / name).resolve()
        if result.parent != self.directory:
            raise HTTPException(500, "录音临时目录无效")
        return result

    def row(self, owner, request_id):
        with self.db() as c:
            row = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?",
                            (owner, request_id)).fetchone()
        if not row:
            raise HTTPException(404, "录音上传不存在或已过期，请重新上传")
        if row["job_id"] is None and row["created_at"] <= self.now() - self.ttl:
            self.cleanup()
            raise HTTPException(404, "录音上传已过期，请重新上传")
        return row

    def state(self, row):
        result = {"requestId": row["request_id"], "uploadedBytes": row["uploaded_bytes"],
                  "sizeBytes": row["size_bytes"], "chunkBytes": CHUNK_BYTES,
                  "status": row["status"]}
        if row["job_id"]:
            result.update(self.job_status(row["job_id"], {"user_id": row["user_id"]}))
        elif row["error"]:
            result["error"] = row["error"]
        return result

    def create(self, owner, body):
        if not isinstance(body, dict) or not {"requestId", "durationSeconds", "sizeBytes", "sha256"} <= set(body) or set(body) - {"requestId", "durationSeconds", "sizeBytes", "sha256", "summaryLanguage", "summaryRequirements"}:
            raise HTTPException(400, "录音上传信息无效")
        language, requirements = body.get("summaryLanguage", "zh-CN"), body.get("summaryRequirements", "")
        if language not in ("zh-CN", "en") or not isinstance(requirements, str) or len(requirements) > 1000:
            raise HTTPException(400, "总结语言或额外要求无效")
        request_id = self.request_id(body["requestId"])
        duration, size, sha = body["durationSeconds"], body["sizeBytes"], body["sha256"]
        if type(duration) is not int or not 10 <= duration <= MAX_SECONDS:
            raise HTTPException(400, "单次录音时长应为 10 秒到 5 小时")
        if type(size) is not int or not MIN_BYTES <= size <= MAX_BYTES:
            raise HTTPException(413, "录音文件应为 512 字节到 512 MB，大文件将自动分段上传")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
            raise HTTPException(400, "录音校验值无效")
        sha = sha.lower()
        self.cleanup()
        # Replays already owned by a job must work even if its reservation used
        # the last seconds of the user's allowance.
        with self.db() as c:
            existing = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?",
                                 (owner, request_id)).fetchone()
        if existing:
            if (existing["duration_seconds"], existing["size_bytes"], existing["sha256"], existing["summary_language"], existing["summary_requirements"]) != (duration, size, sha, language, requirements):
                raise HTTPException(409, "此上传标识已用于其他录音，请创建新的上传")
            return self.state(existing)
        self.preflight(owner, duration)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            existing = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?",
                                 (owner, request_id)).fetchone()
            if existing:
                if (existing["duration_seconds"], existing["size_bytes"], existing["sha256"], existing["summary_language"], existing["summary_requirements"]) != (duration, size, sha, language, requirements):
                    raise HTTPException(409, "此上传标识已用于其他录音")
            else:
                own = c.execute("SELECT COUNT(*) FROM audio_uploads WHERE user_id=? AND status='uploading'", (owner,)).fetchone()[0]
                jobs = c.execute("SELECT COUNT(*) FROM ai_jobs WHERE user_id=? AND task_kind='audio' AND status IN ('queued','running')", (owner,)).fetchone()[0]
                count, reserved, remaining = c.execute("""SELECT COUNT(*),COALESCE(SUM(u.size_bytes),0),
                    COALESCE(SUM(CASE WHEN u.status='uploading' THEN u.size_bytes-u.uploaded_bytes ELSE 0 END),0)
                    FROM audio_uploads u LEFT JOIN ai_jobs j ON j.id=u.job_id
                    WHERE u.status='uploading' OR j.status IN ('queued','running')""").fetchone()
                if own + jobs >= self.per_user_limit:
                    raise HTTPException(429, "每个账号最多同时上传或处理 2 段录音，请等待现有任务完成")
                if count >= self.global_limit or reserved + size > self.disk_budget:
                    raise HTTPException(503, "录音上传队列已满，请稍后重试")
                if shutil.disk_usage(self.directory).free - remaining - size < self.min_free:
                    raise HTTPException(503, "录音临时空间不足，请稍后重试")
                now = self.now()
                c.execute("INSERT INTO audio_uploads(user_id,request_id,duration_seconds,size_bytes,sha256,created_at,updated_at,summary_language,summary_requirements) VALUES(?,?,?,?,?,?,?,?,?)",
                          (owner, request_id, duration, size, sha, now, now, language, requirements))
                existing = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?", (owner, request_id)).fetchone()
        return self.state(existing)

    def acquire(self, owner, request_id):
        row = self.row(owner, request_id)
        if row["job_id"]:
            return row, None
        if row["status"] != "uploading":
            raise HTTPException(409, row["error"] or "此上传已结束，请创建新的上传")
        token = uuid.uuid4().hex
        with self.db() as c:
            changed = c.execute("UPDATE audio_uploads SET lease_token=?,lease_expires=? WHERE user_id=? AND request_id=? AND status='uploading' AND job_id IS NULL AND (lease_token IS NULL OR lease_expires<=?)",
                                (token, self.now() + LEASE_SECONDS, owner, request_id, self.now())).rowcount
            if changed != 1:
                raise HTTPException(409, "这段录音正在上传，请稍后查询上传进度")
            row = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?", (owner, request_id)).fetchone()
        return row, token

    def release(self, owner, request_id, token):
        if token:
            with self.db() as c:
                c.execute("UPDATE audio_uploads SET lease_token=NULL,lease_expires=0 WHERE user_id=? AND request_id=? AND lease_token=?", (owner, request_id, token))

    def fail(self, owner, request_id, message):
        with self.db() as c:
            c.execute("UPDATE audio_uploads SET status='failed',error=?,lease_token=NULL,lease_expires=0 WHERE user_id=? AND request_id=? AND job_id IS NULL", (message, owner, request_id))
        self.path(owner, request_id).unlink(missing_ok=True)

    def repair(self, row):
        path = self.path(row["user_id"], row["request_id"])
        offset = row["uploaded_bytes"]
        if not path.exists() and offset == 0:
            path.touch(mode=0o600)
        if not path.is_file() or path.stat().st_size < offset:
            self.fail(row["user_id"], row["request_id"], "录音临时文件不完整，请从本机重新上传")
            raise HTTPException(409, "录音临时文件不完整，请从本机重新上传")
        with path.open("r+b") as out:
            out.truncate(offset)  # Discard an uncommitted tail left by a crash.
        return path

    async def put(self, owner, request_id, offset, request):
        request_id = self.request_id(request_id)
        row, token = self.acquire(owner, request_id)
        if token is None:
            return self.state(row)
        path = None
        committed = False
        try:
            if offset != row["uploaded_bytes"]:
                raise HTTPException(409, "上传偏移不一致，请先查询已上传字节数")
            self.preflight(owner, row["duration_seconds"])
            length = request.headers.get("content-length")
            if length is not None:
                try:
                    size = int(length)
                except ValueError:
                    raise HTTPException(400, "录音分块长度无效")
                if not 1 <= size <= CHUNK_BYTES or offset + size > row["size_bytes"]:
                    raise HTTPException(413, "录音分块超出允许大小")
            path = self.repair(row)
            received = 0
            with path.open("r+b") as out:
                out.seek(offset)
                try:
                    async with asyncio.timeout(CHUNK_TIMEOUT):
                        async for chunk in request.stream():
                            received += len(chunk)
                            if received > CHUNK_BYTES or offset + received > row["size_bytes"]:
                                raise HTTPException(413, "录音分块超出允许大小")
                            out.write(chunk)
                    if received == 0 or (length is not None and received != int(length)):
                        raise HTTPException(400, "录音分块不完整，请重试")
                    out.flush()
                    os.fsync(out.fileno())
                    with self.db() as c:
                        changed = c.execute("UPDATE audio_uploads SET uploaded_bytes=?,updated_at=? WHERE user_id=? AND request_id=? AND lease_token=? AND uploaded_bytes=? AND status='uploading'",
                                            (offset + received, self.now(), owner, request_id, token, offset)).rowcount
                        if changed != 1:
                            raise HTTPException(409, "上传状态已变化，请重新查询进度")
                    committed = True
                finally:
                    if not committed:
                        out.seek(offset)
                        out.truncate()
                        out.flush()
                        os.fsync(out.fileno())
            return self.state(self.row(owner, request_id))
        except TimeoutError:
            raise HTTPException(408, "录音分块上传超时，可以继续上传")
        finally:
            self.release(owner, request_id, token)

    @staticmethod
    def digest(path):
        value = hashlib.sha256()
        with path.open("rb") as source:
            while chunk := source.read(CHUNK_BYTES):
                value.update(chunk)
        return value.hexdigest()

    async def complete(self, owner, request_id):
        request_id = self.request_id(request_id)
        row, token = self.acquire(owner, request_id)
        if token is None:
            return self.state(row)
        try:
            if row["uploaded_bytes"] != row["size_bytes"]:
                raise HTTPException(409, "录音尚未上传完整，请继续上传")
            self.preflight(owner, row["duration_seconds"])
            path = self.repair(row)
            if await asyncio.to_thread(self.digest, path) != row["sha256"]:
                self.fail(owner, request_id, "录音校验失败，请从本机重新上传")
                raise HTTPException(422, "录音校验失败，请从本机重新上传")
            with self.db() as c:
                c.execute("BEGIN IMMEDIATE")
                fresh = c.execute("SELECT * FROM audio_uploads WHERE user_id=? AND request_id=?", (owner, request_id)).fetchone()
                if not fresh or fresh["lease_token"] != token or fresh["status"] != "uploading":
                    raise HTTPException(409, "上传状态已变化，请重新查询进度")
                # Callback rechecks membership, quota and queue capacity under
                # this same write lock, then inserts and reserves exactly once.
                job_id = self.reserve_job(c, owner, row["duration_seconds"], str(path), row["sha256"])
                c.execute("UPDATE audio_uploads SET job_id=?,status='queued',updated_at=?,lease_token=NULL,lease_expires=0 WHERE user_id=? AND request_id=? AND lease_token=?",
                          (job_id, self.now(), owner, request_id, token))
            self.wake()
            return self.state(self.row(owner, request_id))
        finally:
            self.release(owner, request_id, token)

    def cleanup(self):
        now = self.now()
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            rows = c.execute("SELECT user_id,request_id FROM audio_uploads WHERE job_id IS NULL AND created_at<=? AND (lease_token IS NULL OR lease_expires<=?)", (now - self.ttl, now)).fetchall()
            for row in rows:
                self.path(row["user_id"], row["request_id"]).unlink(missing_ok=True)
                c.execute("DELETE FROM audio_uploads WHERE user_id=? AND request_id=? AND job_id IS NULL", (row["user_id"], row["request_id"]))
            # Jobs own accepted audio. The existing worker deletes it in finally;
            # additionally remove leftovers if a process died after settling.
            finished = c.execute("SELECT u.user_id,u.request_id FROM audio_uploads u JOIN ai_jobs j ON j.id=u.job_id WHERE j.status IN ('applied','failed')").fetchall()
            for row in finished:
                self.path(row["user_id"], row["request_id"]).unlink(missing_ok=True)
            owned = {self.path(row["user_id"], row["request_id"]).name for row in c.execute("SELECT user_id,request_id FROM audio_uploads")}
        for path in self.directory.glob("*.part"):
            if path.name not in owned and path.is_file() and path.stat().st_mtime <= now - self.ttl:
                path.unlink(missing_ok=True)

    async def start(self):
        self.init_db()
        self.cleanup()
        async def sweep():
            while True:
                await asyncio.sleep(60)
                with suppress(OSError, sqlite3.Error):
                    self.cleanup()
        self.cleanup_task = asyncio.create_task(sweep(), name="audio-upload-cleanup")

    async def stop(self):
        if self.cleanup_task:
            self.cleanup_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.cleanup_task


def install_audio_uploads(app, *, auth_session, **kwargs):
    uploads = AudioUploads(**kwargs)
    uploads.init_db()
    router = APIRouter(prefix="/api/v1/audio/uploads")

    @router.post("", status_code=201)
    async def create_upload(request: Request, session=Depends(auth_session)):
        # Auth runs before reading even this small metadata body. Audio bytes
        # are only accepted by PUT after quota/config/size preflight.
        payload = bytearray()
        try:
            async with asyncio.timeout(15):
                async for chunk in request.stream():
                    payload.extend(chunk)
                    if len(payload) > 4096:
                        raise HTTPException(413, "录音上传信息过大")
        except TimeoutError:
            raise HTTPException(408, "录音上传信息发送超时，请重试")
        try:
            body = json.loads(payload)
        except (ValueError, UnicodeError):
            raise HTTPException(400, "录音上传信息无效")
        return uploads.create(session["user_id"], body)

    @router.get("/{request_id}")
    async def get_upload(request_id: str, session=Depends(auth_session)):
        return uploads.state(uploads.row(session["user_id"], uploads.request_id(request_id)))

    @router.put("/{request_id}")
    async def put_upload(request_id: str, request: Request, offset: int, session=Depends(auth_session)):
        return await uploads.put(session["user_id"], request_id, offset, request)

    @router.post("/{request_id}/complete", status_code=202)
    async def complete_upload(request_id: str, session=Depends(auth_session)):
        return await uploads.complete(session["user_id"], request_id)

    app.include_router(router)
    app.add_event_handler("startup", uploads.start)
    app.add_event_handler("shutdown", uploads.stop)
    return uploads
