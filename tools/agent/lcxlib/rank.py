"""List ranks: a port of fractional-indexing 3.2.0 (base-62), as in the backend's list_rank.py."""

from __future__ import annotations

DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
MAX_RANK_LENGTH = 64
_SMALLEST_INTEGER = "A" + DIGITS[0] * 26


class RankError(ValueError):
    pass


def _integer_length(head: str) -> int:
    if "a" <= head <= "z":
        return ord(head) - ord("a") + 2
    if "A" <= head <= "Z":
        return ord("Z") - ord(head) + 2
    raise RankError(f"invalid order key head: {head!r}")


def _integer_part(key: str) -> str:
    length = _integer_length(key[0])
    if length > len(key):
        raise RankError(f"invalid order key: {key!r}")
    return key[:length]


def _validate_key(key: str) -> None:
    if not key:
        raise RankError("empty order key")
    if key == _SMALLEST_INTEGER:
        raise RankError(f"invalid order key: {key!r}")
    if any(c not in DIGITS for c in key):
        raise RankError(f"invalid order key: {key!r}")
    integer = _integer_part(key)
    if key[len(integer):].endswith(DIGITS[0]):
        raise RankError(f"invalid order key: {key!r}")


def validate_rank(key: str) -> str:
    """The key if fractional-indexing accepts it and it has at most 64 chars, else ``RankError``."""
    _validate_key(key)
    if len(key) > MAX_RANK_LENGTH:
        raise RankError(f"order key longer than {MAX_RANK_LENGTH} chars")
    return key


def _midpoint(a: str, b: str | None) -> str:
    zero = DIGITS[0]
    if b is not None and a >= b:
        raise RankError(f"{a!r} >= {b!r}")
    if a.endswith(zero) or (b and b.endswith(zero)):
        raise RankError("trailing zero")
    if b:
        n = 0
        while (a[n] if n < len(a) else zero) == b[n]:
            n += 1
        if n > 0:
            return b[:n] + _midpoint(a[n:], b[n:])
    digit_a = DIGITS.index(a[0]) if a else 0
    digit_b = DIGITS.index(b[0]) if b is not None else len(DIGITS)
    if digit_b - digit_a > 1:
        return DIGITS[(digit_a + digit_b + 1) // 2]  # Math.round(0.5 * (a + b))
    if b and len(b) > 1:
        return b[:1]
    return DIGITS[digit_a] + _midpoint(a[1:], None)


def _increment_integer(x: str) -> str | None:
    head, digs = x[0], list(x[1:])
    carry = True
    for i in range(len(digs) - 1, -1, -1):
        d = DIGITS.index(digs[i]) + 1
        if d == len(DIGITS):
            digs[i] = DIGITS[0]
        else:
            digs[i], carry = DIGITS[d], False
            break
    if not carry:
        return head + "".join(digs)
    if head == "Z":
        return "a" + DIGITS[0]
    if head == "z":
        return None
    h = chr(ord(head) + 1)
    if h > "a":
        digs.append(DIGITS[0])
    else:
        digs.pop()
    return h + "".join(digs)


def _decrement_integer(x: str) -> str | None:
    head, digs = x[0], list(x[1:])
    borrow = True
    for i in range(len(digs) - 1, -1, -1):
        d = DIGITS.index(digs[i]) - 1
        if d == -1:
            digs[i] = DIGITS[-1]
        else:
            digs[i], borrow = DIGITS[d], False
            break
    if not borrow:
        return head + "".join(digs)
    if head == "a":
        return "Z" + DIGITS[-1]
    if head == "A":
        return None
    h = chr(ord(head) - 1)
    if h < "Z":
        digs.append(DIGITS[-1])
    else:
        digs.pop()
    return h + "".join(digs)


def key_between(a: str | None, b: str | None) -> str:
    """A key strictly between ``a`` and ``b`` (``None`` = open end), compared by byte order."""
    if a is not None:
        _validate_key(a)
    if b is not None:
        _validate_key(b)
    if a is not None and b is not None and a >= b:
        raise RankError(f"{a!r} >= {b!r}")
    if a is None:
        if b is None:
            return "a" + DIGITS[0]
        ib = _integer_part(b)
        fb = b[len(ib):]
        if ib == _SMALLEST_INTEGER:
            return ib + _midpoint("", fb)
        if ib < b:
            return ib
        res = _decrement_integer(ib)
        if res is None:
            raise RankError("cannot decrement any more")
        return res
    if b is None:
        ia = _integer_part(a)
        i = _increment_integer(ia)
        return ia + _midpoint(a[len(ia):], None) if i is None else i
    ia, ib = _integer_part(a), _integer_part(b)
    fa, fb = a[len(ia):], b[len(ib):]
    if ia == ib:
        return ia + _midpoint(fa, fb)
    i = _increment_integer(ia)
    if i is None:
        raise RankError("cannot increment any more")
    return i if i < b else ia + _midpoint(fa, None)


def n_keys_between(a: str | None, b: str | None, n: int) -> list[str]:
    """``n`` ascending keys strictly between ``a`` and ``b`` (``generateNKeysBetween``)."""
    if n == 0:
        return []
    if n == 1:
        return [key_between(a, b)]
    if b is None:
        keys = [key_between(a, None)]
        for _ in range(n - 1):
            keys.append(key_between(keys[-1], None))
        return keys
    if a is None:
        keys = [key_between(None, b)]
        for _ in range(n - 1):
            keys.append(key_between(None, keys[-1]))
        return keys[::-1]
    mid = n // 2
    c = key_between(a, b)
    return [*n_keys_between(a, c, mid), c, *n_keys_between(c, b, n - mid - 1)]
