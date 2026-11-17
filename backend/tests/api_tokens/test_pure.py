"""Pure rules of personal API tokens: secret format and hashing, names, expiry, the last-used
throttle, and the auth split between API tokens and JWTs."""

from __future__ import annotations

import hashlib
import string
from datetime import datetime, timedelta, timezone

import pytest

from app.services.api_tokens.errors import InvalidTokenNameError, TokenErrorCode
from app.services.api_tokens.secrets import TOKEN_LENGTH, display_prefix, hash_token, is_api_token, new_secret
from app.services.api_tokens.values import expires_at, is_live, needs_touch, token_name

NOW = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)
URLSAFE = set(string.ascii_letters + string.digits + "-_")


def test_new_secret_format():
    made = new_secret()
    assert made.token.startswith("lct_") and len(made.token) == TOKEN_LENGTH == 47
    assert set(made.token[4:]) <= URLSAFE
    assert made.prefix == made.token[:12]
    assert made.token_hash == hashlib.sha256(made.token.encode()).hexdigest()
    assert len(made.token_hash) == 64


def test_new_secrets_differ():
    assert len({new_secret().token for _ in range(50)}) == 50


def test_hash_and_prefix():
    assert hash_token("lct_abc") == hashlib.sha256(b"lct_abc").hexdigest()
    assert display_prefix("lct_0123456789abcdef") == "lct_01234567"


@pytest.mark.parametrize(("bearer", "expected"), [("lct_x", True), ("eyJhbGciOi.x.y", False), ("LCT_x", False), ("", False)])
def test_is_api_token(bearer, expected):
    assert is_api_token(bearer) is expected


@pytest.mark.parametrize(("raw", "name"), [("cli", "cli"), ("  laptop agent  ", "laptop agent"), ("x" * 64, "x" * 64), ("é" * 64, "é" * 64)])
def test_valid_names(raw, name):
    assert token_name(raw) == name


@pytest.mark.parametrize("raw", ["", "   ", "\n\t", "x" * 65])
def test_invalid_names(raw):
    with pytest.raises(InvalidTokenNameError) as raised:
        token_name(raw)
    assert raised.value.code is TokenErrorCode.invalid_token_name and raised.value.status == 422


def test_expiry():
    assert expires_at(NOW, None) is None
    assert expires_at(NOW, 90) == NOW + timedelta(days=90)
    assert expires_at(NOW, 1) == NOW + timedelta(days=1)


def test_is_live():
    assert is_live(None, None, NOW)
    assert is_live(None, NOW + timedelta(seconds=1), NOW)
    assert not is_live(None, NOW, NOW)
    assert not is_live(NOW - timedelta(days=1), None, NOW)


def test_needs_touch_at_most_once_a_minute():
    assert needs_touch(None, NOW)
    assert not needs_touch(NOW - timedelta(seconds=59), NOW)
    assert needs_touch(NOW - timedelta(minutes=1), NOW)
    assert needs_touch(NOW - timedelta(hours=3), NOW)
