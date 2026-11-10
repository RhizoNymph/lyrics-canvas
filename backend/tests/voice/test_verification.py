"""Checked-transcript normalization (contract section 3, PUT /api/recordings/{id}/verification)."""

from __future__ import annotations

import pytest

from app.services.voice.errors import InvalidVerificationError
from app.services.voice.verification import MAX_VERIFIED_CHARS, normalize_verification


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("hello world", "hello world"),
        ("a\r\nb", "a\nb"),
        ("a\rb", "a\nb"),
        ("a\r\n\r\nb", "a\n\nb"),
        ("a  \nb\t", "a\nb"),
        ("  indented\nkept", "  indented\nkept"),
        ("\n\n  \na\nb\n\n \n", "a\nb"),
        ("a\n\n\n\nb", "a\n\nb"),
        ("a\n \t \n\t\nb", "a\n\nb"),
        ("a\n\nb\n\n\nc", "a\n\nb\n\nc"),
        ("café ", "café"),
    ],
    ids=["plain", "crlf", "cr", "crlf-blank", "trailing", "leading-kept", "edges", "runs", "whitespace-runs",
         "two-runs", "nbsp"],
)
def test_normalization(raw, expected):
    assert normalize_verification(raw) == expected


@pytest.mark.parametrize("raw", ["", "   ", "\n\n", "\r\n \t\r\n"])
def test_empty_after_normalizing_is_invalid(raw):
    with pytest.raises(InvalidVerificationError) as info:
        normalize_verification(raw)
    assert info.value.status == 422
    assert info.value.code.value == "invalid_verification"


def test_length_limit_is_in_code_points_after_normalizing():
    assert MAX_VERIFIED_CHARS == 10_000
    assert normalize_verification("é" * 10_000) == "é" * 10_000
    # trailing whitespace is dropped before the length check
    assert normalize_verification("a" * 10_000 + "    \n\n") == "a" * 10_000
    with pytest.raises(InvalidVerificationError):
        normalize_verification("a" * 10_001)


def test_normalization_is_idempotent():
    raw = "\r\n x \r\n\r\n\r\ny  \n"
    once = normalize_verification(raw)
    assert normalize_verification(once) == once


def test_error_message_does_not_echo_the_text():
    with pytest.raises(InvalidVerificationError) as info:
        normalize_verification("secret" * 2000)
    assert "secret" not in info.value.message
