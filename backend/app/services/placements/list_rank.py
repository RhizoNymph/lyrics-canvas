"""Custom list order keys: an exact port of ``fractional-indexing`` 3.2.0 (rocicorp).

A rank is a fractional index key over the base-62 digits ``0-9A-Za-z``: a variable-length integer
part (its head letter gives the length: ``a``..``z`` are 2..27 chars, ``Z``..``A`` are 2..27 chars and
sort before every ``a`` key) followed by an optional fraction that never ends in ``0``. Keys compare by
plain code point (= byte, they are ASCII) order, never by locale; the column is ``COLLATE "C"``.

``key_between(a, b)`` and ``n_keys_between(a, b, n)`` match ``generateKeyBetween`` and
``generateNKeysBetween`` of the published package output for output (pinned by
spec/list_rank_vectors.json, which the web and Android ports must also pass). ``validate_rank``
accepts what ``validateOrderKey`` accepts, restricted to base-62 characters and at most 64 chars
(docs/features/snippet_placements.md#list-order).
"""

from __future__ import annotations

from app.services.placements.errors import InvalidListRankError

DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
MAX_RANK_LENGTH = 64

_ZERO = DIGITS[0]
_LAST = DIGITS[-1]
_SMALLEST_INTEGER = "A" + _ZERO * 26
_DIGIT_SET = frozenset(DIGITS)


class RankGenerationError(InvalidListRankError):
    """The inputs are valid keys but no key fits (``a >= b``, or the key space is exhausted)."""


def _midpoint(a: str, b: str | None) -> str:
    """A fraction strictly between fractions ``a`` and ``b`` (``b`` None = 1). Mirrors JS truthiness:
    ``if (b)`` is false for both None and ""."""
    if b is not None and a >= b:
        raise RankGenerationError(f"{a!r} >= {b!r}")
    if a[-1:] == _ZERO or (b and b[-1:] == _ZERO):
        raise RankGenerationError("trailing zero")
    if b:
        # Longest common prefix, padding ``a`` with zeros.
        n = 0
        while (a[n] if n < len(a) else _ZERO) == (b[n] if n < len(b) else None):
            n += 1
        if n > 0:
            return b[:n] + _midpoint(a[n:], b[n:])
    digit_a = DIGITS.index(a[0]) if a else 0
    match b:
        case None:
            digit_b = len(DIGITS)
        case "":
            digit_b = -1  # JS: digits.indexOf(undefined)
        case _:
            digit_b = DIGITS.index(b[0])
    if digit_b - digit_a > 1:
        # Math.round(0.5 * (a + b)) on integers rounds halves up.
        return DIGITS[(digit_a + digit_b + 1) // 2]
    if b and len(b) > 1:
        return b[:1]
    return DIGITS[digit_a] + _midpoint(a[1:], None)


def _integer_length(head: str) -> int:
    if "a" <= head <= "z":
        return ord(head) - ord("a") + 2
    if "A" <= head <= "Z":
        return ord("Z") - ord(head) + 2
    raise InvalidListRankError(f"invalid order key head: {head!r}")


def _integer_part(key: str) -> str:
    if not key:
        raise InvalidListRankError("empty order key")
    length = _integer_length(key[0])
    if length > len(key):
        raise InvalidListRankError(f"invalid order key: {key!r}")
    return key[:length]


def _validate_integer(integer: str) -> None:
    if len(integer) != _integer_length(integer[0]):
        raise InvalidListRankError(f"invalid integer part of order key: {integer!r}")


def _increment_integer(x: str) -> str | None:
    _validate_integer(x)
    head, digs = x[0], list(x[1:])
    carry = True
    i = len(digs) - 1
    while carry and i >= 0:
        d = DIGITS.index(digs[i]) + 1
        if d == len(DIGITS):
            digs[i] = _ZERO
        else:
            digs[i] = DIGITS[d]
            carry = False
        i -= 1
    if not carry:
        return head + "".join(digs)
    if head == "Z":
        return "a" + _ZERO
    if head == "z":
        return None
    h = chr(ord(head) + 1)
    if h > "a":
        digs.append(_ZERO)
    else:
        digs.pop()
    return h + "".join(digs)


def _decrement_integer(x: str) -> str | None:
    _validate_integer(x)
    head, digs = x[0], list(x[1:])
    borrow = True
    i = len(digs) - 1
    while borrow and i >= 0:
        d = DIGITS.index(digs[i]) - 1
        if d == -1:
            digs[i] = _LAST
        else:
            digs[i] = DIGITS[d]
            borrow = False
        i -= 1
    if not borrow:
        return head + "".join(digs)
    if head == "a":
        return "Z" + _LAST
    if head == "A":
        return None
    h = chr(ord(head) - 1)
    if h < "Z":
        digs.append(_LAST)
    else:
        digs.pop()
    return h + "".join(digs)


def _validate_order_key(key: str) -> None:
    """``validateOrderKey`` of 3.2.0, plus: only base-62 characters (the JS version checks only the
    head and the trailing zero; other characters would make ``indexOf`` return -1 later)."""
    if not isinstance(key, str):
        raise InvalidListRankError("order key must be a string")
    if key == _SMALLEST_INTEGER:
        raise InvalidListRankError(f"invalid order key: {key!r}")
    integer = _integer_part(key)
    fraction = key[len(integer) :]
    if fraction[-1:] == _ZERO:
        raise InvalidListRankError(f"invalid order key: {key!r}")
    if not _DIGIT_SET.issuperset(key):
        raise InvalidListRankError(f"order key has characters outside 0-9A-Za-z: {key!r}")


def validate_rank(rank: str) -> str:
    """The rank unchanged when it is a valid key of at most ``MAX_RANK_LENGTH`` chars; else
    ``InvalidListRankError``."""
    if not isinstance(rank, str):
        raise InvalidListRankError("list_rank must be a string")
    if len(rank) > MAX_RANK_LENGTH:
        raise InvalidListRankError(f"list_rank is longer than {MAX_RANK_LENGTH} characters")
    _validate_order_key(rank)
    return rank


def key_between(a: str | None, b: str | None) -> str:
    """``generateKeyBetween(a, b)``: a key strictly between ``a`` and ``b`` (None = unbounded)."""
    if a is not None:
        _validate_order_key(a)
    if b is not None:
        _validate_order_key(b)
    if a is not None and b is not None and a >= b:
        raise RankGenerationError(f"{a!r} >= {b!r}")
    if a is None:
        if b is None:
            return "a" + _ZERO
        ib = _integer_part(b)
        fb = b[len(ib) :]
        if ib == _SMALLEST_INTEGER:
            return ib + _midpoint("", fb)
        if ib < b:
            return ib
        res = _decrement_integer(ib)
        if res is None:
            raise RankGenerationError("cannot decrement any more")
        return res
    if b is None:
        ia = _integer_part(a)
        fa = a[len(ia) :]
        i = _increment_integer(ia)
        return ia + _midpoint(fa, None) if i is None else i
    ia = _integer_part(a)
    fa = a[len(ia) :]
    ib = _integer_part(b)
    fb = b[len(ib) :]
    if ia == ib:
        return ia + _midpoint(fa, fb)
    i = _increment_integer(ia)
    if i is None:
        raise RankGenerationError("cannot increment any more")
    if i < b:
        return i
    return ia + _midpoint(fa, None)


def n_keys_between(a: str | None, b: str | None, n: int) -> list[str]:
    """``generateNKeysBetween(a, b, n)``: ``n`` ascending keys strictly between ``a`` and ``b``."""
    if n < 0:
        raise ValueError("n must not be negative")
    if n == 0:
        return []
    if n == 1:
        return [key_between(a, b)]
    if b is None:
        c = key_between(a, b)
        result = [c]
        for _ in range(n - 1):
            c = key_between(c, b)
            result.append(c)
        return result
    if a is None:
        c = key_between(a, b)
        result = [c]
        for _ in range(n - 1):
            c = key_between(a, c)
            result.append(c)
        result.reverse()
        return result
    mid = n // 2
    c = key_between(a, b)
    return [*n_keys_between(a, c, mid), c, *n_keys_between(c, b, n - mid - 1)]
