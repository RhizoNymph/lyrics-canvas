"""Model lifecycle: load in the background, retry with backoff, expose readiness."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from .errors import ModelNotReady
from .logs import log_event
from .transcriber import ModelLoader, Transcriber

logger = logging.getLogger("stt.model")


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    initial_seconds: float
    max_seconds: float

    def delay(self, failures: int) -> float:
        """Seconds to wait after the `failures`-th consecutive failure (1-based)."""
        return min(self.max_seconds, self.initial_seconds * 2 ** (failures - 1))


@dataclass(frozen=True, slots=True)
class Loading:
    attempt: int


@dataclass(frozen=True, slots=True)
class Ready:
    transcriber: Transcriber
    load_seconds: float


@dataclass(frozen=True, slots=True)
class LoadFailed:
    attempt: int
    error: str
    retry_in_seconds: float


ModelState = Loading | Ready | LoadFailed

Sleep = Callable[[float], Awaitable[None]]


class ModelHolder:
    """Owns the model state. Only `run` (one task) transitions it."""

    def __init__(self, loader: ModelLoader, retry: RetryPolicy, sleep: Sleep = asyncio.sleep) -> None:
        self._loader = loader
        self._retry = retry
        self._sleep = sleep
        self._state: ModelState = Loading(attempt=1)

    @property
    def state(self) -> ModelState:
        return self._state

    def require(self) -> Transcriber:
        match self._state:
            case Ready(transcriber=transcriber):
                return transcriber
            case Loading():
                raise ModelNotReady("model is still loading")
            case LoadFailed(error=error):
                raise ModelNotReady(f"model failed to load and will be retried: {error}")

    async def run(self) -> None:
        """Load until it succeeds. Each attempt runs in a worker thread."""
        attempt = 1
        while True:
            self._state = Loading(attempt=attempt)
            log_event(logger, logging.INFO, "model.load_start", attempt=attempt)
            started = time.monotonic()
            try:
                transcriber = await asyncio.to_thread(self._loader)
            except Exception as exc:  # any loader failure is retried; cancellation propagates
                delay = self._retry.delay(attempt)
                error = f"{type(exc).__name__}: {exc}"
                self._state = LoadFailed(attempt=attempt, error=error, retry_in_seconds=delay)
                log_event(
                    logger, logging.ERROR, "model.load_failed", attempt=attempt, error=error, retry_in_s=delay
                )
                await self._sleep(delay)
                attempt += 1
                continue
            elapsed = time.monotonic() - started
            self._state = Ready(transcriber=transcriber, load_seconds=elapsed)
            log_event(logger, logging.INFO, "model.ready", attempt=attempt, load_s=round(elapsed, 2))
            return
