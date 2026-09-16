"""Bounded, cancellation-safe retries for inference; never applies or charges jobs."""
import asyncio
import os
import random
import time

import httpx

_cooldown = {}
_slots = asyncio.Semaphore(4)


async def post_inference(client, url, *, headers, payload, vision=False):
    # Only explicitly configured, capability-tested models on the SAME endpoint.
    setting = "KEJIAN_AI_VISION_FALLBACK_MODELS" if vision else "KEJIAN_AI_FALLBACK_MODELS"
    models = list(dict.fromkeys([payload["model"], *filter(None, (x.strip() for x in os.getenv(setting, "").split(",")))]))[:3]
    now = time.monotonic()
    ready = [m for m in models if _cooldown.get((url, m), 0) <= now]
    if not ready:
        raise RuntimeError("AI provider API 503: models temporarily unavailable")
    # A bounded queue protects the process; cancellation propagates through both waits.
    try:
        await asyncio.wait_for(_slots.acquire(), timeout=30)
    except TimeoutError as exc:
        raise RuntimeError("AI provider API 429: inference queue busy") from exc
    try:
        async with asyncio.timeout(150):
            last = None
            for attempt in range(3):
                model = ready[0]
                try:
                    response = await client.post(url, headers=headers, json={**payload, "model": model})
                except (httpx.TimeoutException, httpx.NetworkError) as exc:
                    last = exc
                    response = None
                if response is not None:
                    if response.status_code < 400:
                        _cooldown.pop((url, model), None)
                        return response
                    status = response.status_code
                    # Authentication, billing and input errors must never be retried.
                    if status not in (404, 408, 429, 500, 502, 503, 504):
                        return response
                    last = RuntimeError(f"AI provider API {status}: {response.text[:300]}")
                    if status == 404:
                        _cooldown[(url, model)] = time.monotonic() + 60
                        ready = [m for m in ready if m != model]
                        if not ready:
                            raise last
                        # Keep the remaining model first for the next attempt.
                    elif status >= 500:
                        _cooldown[(url, model)] = time.monotonic() + 10
                    if status != 404:
                        ready = ready[1:] + ready[:1]
                else:
                    ready = ready[1:] + ready[:1]
                if attempt < 2:
                    await asyncio.sleep(min(4, 0.5 * 2**attempt) + random.uniform(0, .2))
            raise last or RuntimeError("AI provider API 503: inference unavailable")
    except TimeoutError as exc:
        raise RuntimeError("AI provider timeout: inference deadline exceeded") from exc
    finally:
        _slots.release()
