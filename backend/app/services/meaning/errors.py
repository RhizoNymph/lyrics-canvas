"""Typed failures of the meaning (embedding similarity) service. None of them reaches a client:
the rhyme endpoints answer in today's order with ``order_applied: "rhyme"`` instead."""

from __future__ import annotations


class MeaningError(Exception):
    """Base of the meaning-service failures."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class EmbedderUnavailableError(MeaningError):
    """The embedding model cannot be loaded (for the rest of the process)."""


class EmbeddingFailedError(MeaningError):
    """The loaded model failed on one batch, or returned the wrong shape (this request only)."""
