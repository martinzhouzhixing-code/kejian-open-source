import asyncio
import httpx
import pytest
import audio_summary as audio

def test_confirmed_download_timeout_resubmits_but_submit_network_timeout_does_not(monkeypatch):
    calls=[]
    async def once(url,key):
        calls.append(url)
        if len(calls)==1: raise audio.AudioDownloadTimeout()
        return "transcript"
    async def sleep(_): pass
    monkeypatch.setattr(audio,"_transcribe_once",once)
    monkeypatch.setattr(audio.asyncio,"sleep",sleep)
    assert asyncio.run(audio._transcribe("https://example.test/audio","key"))=="transcript"
    assert len(calls)==2
    calls.clear()
    async def ambiguous(url,key):
        calls.append(url)
        raise httpx.ReadTimeout("submit may have succeeded")
    monkeypatch.setattr(audio,"_transcribe_once",ambiguous)
    with pytest.raises(httpx.ReadTimeout):asyncio.run(audio._transcribe("https://example.test/audio","key"))
    assert len(calls)==1

def test_download_retries_are_bounded_and_error_preserves_audio(monkeypatch):
    calls=[]
    async def once(url,key):
        calls.append(1)
        raise audio.AudioDownloadTimeout()
    async def sleep(_): pass
    monkeypatch.setattr(audio,"_transcribe_once",once)
    monkeypatch.setattr(audio.asyncio,"sleep",sleep)
    with pytest.raises(RuntimeError,match="本机原录音仍在"):
        asyncio.run(audio._transcribe("https://example.test/audio","key"))
    assert len(calls)==3
