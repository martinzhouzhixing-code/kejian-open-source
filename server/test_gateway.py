import asyncio
import base64
import importlib
import os
from pathlib import Path
import tempfile
import uuid

import httpx
import pytest

_directory=tempfile.TemporaryDirectory()
os.environ["KEJIAN_APP_SECRET"]="test-only-secret-never-use-in-production-240"
os.environ["KEJIAN_DB_PATH"]=str(Path(_directory.name)/"test.db")
os.environ["KEJIAN_AUDIO_QUEUE_DIR"]=str(Path(_directory.name)/"audio")
os.environ["KEJIAN_PUBLIC_ORIGIN"]="https://gateway.example.test"
import gateway

def test_missing_key_and_account_routes_are_unavailable():
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway.app),base_url="https://gateway.example.test") as client:
            assert (await client.get("/api/v1/me")).status_code==401
            assert (await client.get("/health")).json()["version"]=="2.4.0"
            response=await client.post("/api/v1/auth/login",headers={"Authorization":"Bearer fake-key"})
            assert response.status_code==404
            assert (await client.get("/api/v1/admin/overview",headers={"Authorization":"Bearer fake-key"})).status_code==404
            missing_speech=await client.post("/api/v1/audio/uploads",headers={"Authorization":"Bearer fake-key"},json={})
            assert missing_speech.status_code==401 and "语音 API Key" in missing_speech.text
    asyncio.run(run())

def test_parallel_users_never_share_provider_keys_or_jobs(monkeypatch):
    calls=[]
    async def recognize(source,key,*args):
        await asyncio.sleep(.01)
        assert gateway.core.load_ai_key()==key
        calls.append(key)
        return {"protocol":"KJ-OPS/1","operations":[],"warnings":[],"strictKj1":""},{"inputTokens":1,"outputTokens":1,"imageCount":1,"latencyMs":1}
    monkeypatch.setattr(gateway.core,"recognize_timetable",recognize)
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway.app),base_url="https://gateway.example.test") as client:
            request_ids=[str(uuid.uuid4()),str(uuid.uuid4())]
            async def submit(index):
                return await client.post("/api/v1/ai/recognize",headers={"Authorization":f"Bearer fake-key-{index}"},json={"contentBase64":base64.b64encode(b"test").decode(),"mimeType":"image/png","clientRequestId":request_ids[index]})
            responses=await asyncio.gather(submit(0),submit(1))
            assert [r.status_code for r in responses]==[200,200], [r.text for r in responses]
            assert sorted(calls)==["fake-key-0","fake-key-1"]
            foreign=await client.get("/api/v1/ai/requests/"+request_ids[0],headers={"Authorization":"Bearer fake-key-1"})
            assert foreign.status_code==404
            with gateway.core.db() as db:
                users=db.execute("SELECT * FROM users").fetchall()
            assert all("fake-key" not in str(tuple(row)) for row in users)
    asyncio.run(run())

def test_provider_rejection_maps_to_key_error(monkeypatch):
    original=httpx.AsyncClient
    class Provider:
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def get(self,*args,**kwargs):return httpx.Response(401,json={"error":"invalid key"})
    monkeypatch.setattr(gateway.httpx,"AsyncClient",lambda **kwargs:Provider())
    async def run():
        async with original(transport=httpx.ASGITransport(app=gateway.app),base_url="https://gateway.example.test") as client:
            response=await client.get("/api/v1/byok/check",headers={"Authorization":"Bearer invalid-key"})
            assert response.status_code==401
            assert "API Key" in response.text
    asyncio.run(run())
