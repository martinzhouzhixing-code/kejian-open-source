"""BYOK-only entry point. No registration, login, shared provider keys or admin API.

Run a single worker: credentials are deliberately held only in memory, not SQLite.
Each request and detached AI task carries its own context; credentials never cross users.
"""
import asyncio
from contextvars import ContextVar
import hashlib
import hmac
import os
import time

import httpx
from fastapi import Depends, HTTPException, Request
from fastapi.responses import JSONResponse

import app as core
from ai_recognition import API_URL, MODEL

app=core.app
app.title="Kejian Open Source BYOK Gateway"
app.version="2.4.0"

_keys=ContextVar("personal_provider_keys",default=("",""))
_vault={}
_ttl=24*3600

def credentials(authorization:str,asr_key:str=""):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401,"缺少 API Key，请在设置中填写")
    key=authorization[7:].strip()
    if not key or len(key)>512 or any(c.isspace() for c in key):
        raise HTTPException(401,"API Key 格式无效")
    if len(asr_key)>512 or any(c.isspace() for c in asr_key):
        raise HTTPException(400,"语音 API Key 格式无效")
    return key,asr_key

@app.middleware("http")
async def personal_context(request:Request,call_next):
    # Provider downloads authenticate with the scoped URL signature, not a user key.
    if request.url.path.startswith(("/api/v1/audio-source/","/api/v1/audio-temp/")):
        return await call_next(request)
    if request.url.path=="/health":
        return JSONResponse({"status":"ok","version":"2.4.0"})
    try:
        keys=credentials(request.headers.get("authorization",""),request.headers.get("x-kejian-asr-key",""))
    except HTTPException as error:
        return JSONResponse({"error":error.detail},status_code=error.status_code)
    if request.method=="POST" and request.url.path in ("/api/v1/audio/uploads","/api/v1/audio/jobs") and not keys[1]:
        return JSONResponse({"error":"缺少语音 API Key，请在设置中填写豆包语音密钥后再转写。"},status_code=401)
    marker=_keys.set(keys)
    try:
        return await call_next(request)
    finally:
        _keys.reset(marker)

async def personal_session():
    key,asr_key=_keys.get()
    if not key:raise HTTPException(401,"缺少 API Key")
    owner=hmac.new(core.APP_SECRET.encode(),key.encode(),hashlib.sha256).hexdigest()
    now=int(time.time())
    # Auth identity uses a salted HMAC, never the provider key itself.
    with core.db() as db:
        db.execute("INSERT OR IGNORE INTO users(id,email,password_hash,created_at,updated_at,role) VALUES(?,?,?,?,?,'developer')",(owner,owner+"@local.invalid","disabled",now,now))
    for user in list(_vault):
        if _vault[user][2]<now:_vault.pop(user,None)
    if owner not in _vault and len(_vault)>=256:
        raise HTTPException(503,"服务器任务较多，请稍后重试")
    _vault[owner]=(key,asr_key,now+_ttl)
    return {"user_id":owner,"email":owner+"@local.invalid","id":owner}

app.dependency_overrides[core.auth_session]=personal_session
core.load_ai_key=lambda:_keys.get()[0]
core.load_audio_key=lambda:_keys.get()[1]

_public_failure=core.public_ai_failure
def personal_failure(error, *, image):
    detail=str(error).casefold()
    if any(code in detail for code in ("api 401","api 403","http 401","http 403")):
        return 401,"API Key 无效、已过期或无权使用该模型，请在设置中检查。"
    if "balance" in detail or "402" in detail:
        return 429,"个人 API Key 额度不足，请在服务商控制台检查。"
    status,message=_public_failure(error,image=image)
    return status,message.replace("管理员", "服务器维护者").replace("，本次识别不扣积分", "").replace("；本次识别不扣积分", "").replace("；本次任务不扣积分", "")
core.public_ai_failure=personal_failure

# The account/admin/download routes are absent, even with a provider credential.
allowed=("/api/v1/ai/","/api/v1/audio/","/api/v1/audio-source/","/api/v1/audio-temp/")
app.router.routes[:]=[route for route in app.router.routes if getattr(route,"path","").startswith(allowed) or getattr(route,"path","") in ("/api/v1/me",)]

@app.get("/api/v1/byok/check")
async def check_key(session=Depends(personal_session)):
    # A model listing validates auth without sending coursework or paid inference.
    key=_keys.get()[0]
    models_url=API_URL.rsplit("/chat/completions",1)[0]+"/models"
    try:
        async with httpx.AsyncClient(timeout=20,follow_redirects=False) as client:
            response=await client.get(models_url,headers={"Authorization":"Bearer "+key})
        if response.status_code in (401,403):raise HTTPException(401,"API Key 无效或没有模型权限")
        if response.status_code==429:raise HTTPException(429,"服务商额度不足或限流")
        if response.status_code!=200:raise HTTPException(502,"无法验证服务器配置的 AI 服务")
        models=response.json().get("data",[])
        if MODEL not in {item.get("id") for item in models if isinstance(item,dict)}:
            raise HTTPException(403,"API Key 无权使用服务器配置的模型")
    except (httpx.HTTPError,ValueError):
        raise HTTPException(502,"暂时无法连接 AI 服务") from None
    return {"configured":True,"model":MODEL}

_process_audio=core.process_audio_job
async def personal_audio_job(row):
    # A restart drops credentials, but a polling client re-supplies them. Wait
    # without making a provider request with another user's/default credentials.
    for _ in range(120):
        entry=_vault.get(row["user_id"])
        if entry and entry[2]>=time.time():break
        await asyncio.sleep(2)
    else:
        core.fail_queued_audio(row["id"],row["user_id"],int(row["audio_seconds"] or 0),"服务器已重启，请重新打开录音并重试。")
        core.delete_audio_file(row["source_path"])
        return
    marker=_keys.set(entry[:2])
    try:await _process_audio(row)
    finally:_keys.reset(marker)

core.process_audio_job=personal_audio_job
