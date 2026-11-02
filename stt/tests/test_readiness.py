import asyncio

import pytest

from stt_service.errors import ModelNotReady
from stt_service.readiness import LoadFailed, Loading, ModelHolder, Ready, RetryPolicy

from .fakes import FakeTranscriber, GatedLoader


def test_retry_policy_backs_off_exponentially_and_caps() -> None:
    policy = RetryPolicy(initial_seconds=2, max_seconds=30)
    assert [policy.delay(n) for n in (1, 2, 3, 4, 5, 6)] == [2, 4, 8, 16, 30, 30]


async def test_starts_loading_and_becomes_ready() -> None:
    fake = FakeTranscriber()
    loader = GatedLoader([fake])
    holder = ModelHolder(loader, RetryPolicy(1, 1))
    assert holder.state == Loading(attempt=1)
    with pytest.raises(ModelNotReady):
        holder.require()
    task = asyncio.create_task(holder.run())
    await asyncio.sleep(0)
    assert isinstance(holder.state, Loading)
    loader.release.set()
    await asyncio.wait_for(task, 5)
    assert isinstance(holder.state, Ready)
    assert holder.require() is fake


async def test_failure_is_retried_with_backoff() -> None:
    fake = FakeTranscriber()
    loader = GatedLoader([RuntimeError("cuda missing"), RuntimeError("still missing"), fake])
    loader.release.set()
    sleeps: list[float] = []
    states: list[object] = []

    async def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)
        states.append(holder.state)

    holder = ModelHolder(loader, RetryPolicy(initial_seconds=3, max_seconds=100), sleep=fake_sleep)
    await asyncio.wait_for(holder.run(), 5)
    assert sleeps == [3, 6]
    assert states[0] == LoadFailed(attempt=1, error="RuntimeError: cuda missing", retry_in_seconds=3)
    assert states[1] == LoadFailed(attempt=2, error="RuntimeError: still missing", retry_in_seconds=6)
    assert loader.attempts == 3
    assert isinstance(holder.state, Ready)


async def test_not_ready_while_failed() -> None:
    loader = GatedLoader([RuntimeError("boom")])
    loader.release.set()
    gate = asyncio.Event()

    async def blocking_sleep(_seconds: float) -> None:
        await gate.wait()

    holder = ModelHolder(loader, RetryPolicy(1, 1), sleep=blocking_sleep)
    task = asyncio.create_task(holder.run())
    for _ in range(100):
        if isinstance(holder.state, LoadFailed):
            break
        await asyncio.sleep(0.01)
    assert isinstance(holder.state, LoadFailed)
    with pytest.raises(ModelNotReady):
        holder.require()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
