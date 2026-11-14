"""Write spec/list_rank_vectors.json from the Python port (app/services/placements/list_rank.py).

Run from backend/:  uv run python -m scripts.export_list_rank_vectors
The file is the shared contract the web (frontend/src/lib/listMode/rank.ts) and Android
(core/logic/.../list/ListRank.kt) ports must pass; tests/placements/test_list_rank_vectors.py fails
whenever it drifts from the port.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from app.services.placements.list_rank import key_between, n_keys_between

VECTORS_PATH = Path(__file__).resolve().parents[2] / "spec" / "list_rank_vectors.json"

_PAIRS: list[tuple[str | None, str | None]] = [
    (None, None),
    (None, "a0"),
    ("a0", None),
    ("a0", "a1"),
    ("a1", "a2"),
    ("a0", "a0V"),
    ("a0", "a0G"),
    ("a0V", "a1"),
    ("a0V", "a0l"),
    ("Zz", "a0"),
    ("Zz", "a1"),
    ("Zz", "a0V"),
    ("Zy", None),
    ("Zz", None),
    (None, "Zz"),
    (None, "a1"),
    (None, "a0V"),
    (None, "Zz5"),
    ("az", None),
    ("azz", None),
    ("azz", "b00"),
    ("a0", "b00"),
    (None, "b00"),
    ("b00", None),
    ("bzz", None),
    (None, "Y00"),
    (None, "Xzzz"),
    ("Y00", "Y01"),
    ("Yzz", "Z0"),
    ("Yzz", None),
    ("a0zzz", "a1"),
    ("a0", "a01"),
    ("a0", "a001"),
    ("a01", "a02"),
    ("a0z", "a1"),
    ("a0zV", "a1"),
    ("a5", "a5V"),
    ("a5V", "a5W"),
    ("a5Vz", "a5W"),
    ("zzzzzzzzzzzzzzzzzzzzzzzzzzz", None),
    ("zzzzzzzzzzzzzzzzzzzzzzzzzzy", "zzzzzzzzzzzzzzzzzzzzzzzzzzz"),
    (None, "A000000000000000000000000001"),
    (None, "A0000000000000000000000000V"),
]

_N_CASES: list[tuple[str | None, str | None, int]] = [
    (None, None, 0),
    (None, None, 1),
    (None, None, 2),
    (None, None, 5),
    (None, None, 70),
    ("a0", "a1", 2),
    ("a0", "a1", 5),
    ("a0", "a1", 10),
    (None, "a0", 5),
    ("a0", None, 5),
    ("Zz", "a0", 3),
    ("a0V", "a0l", 7),
    (None, "Y00", 3),
    ("bzz", None, 3),
    ("Zx", "a2", 4),
    (None, "a0", 70),
]


def _chains() -> list[tuple[str | None, str | None]]:
    """Long chains: appending past ``az``, prepending past ``Z0``, bisecting toward both ends."""
    pairs: list[tuple[str | None, str | None]] = []
    key = "a0"
    for _ in range(70):
        pairs.append((key, None))
        key = key_between(key, None)
    key = "a0"
    for _ in range(70):
        pairs.append((None, key))
        key = key_between(None, key)
    key = "a1"
    for _ in range(30):
        pairs.append(("a0", key))
        key = key_between("a0", key)
    key = "a0"
    for _ in range(30):
        pairs.append((key, "a1"))
        key = key_between(key, "a1")
    return pairs


INVALID: list[str] = [
    "",
    "a",
    "b0",
    "Z",
    "Y0",
    "a00",
    "a0V0",
    "b000",
    "0",
    "0a",
    "!a0",
    " a0",
    "a0 ",
    "a0!",
    "a0-",
    "a0é",
    "A00000000000000000000000000",
    "A0000000000000000000000000",
    "a0" + "V" * 63,
]


def build_vectors() -> dict:
    between = [{"a": a, "b": b, "key": key_between(a, b)} for a, b in [*_PAIRS, *_chains()]]
    n_between = [{"a": a, "b": b, "n": n, "keys": n_keys_between(a, b, n)} for a, b, n in _N_CASES]
    return {"between": between, "n_between": n_between, "invalid": INVALID}


def render_vectors() -> str:
    return json.dumps(build_vectors(), indent=1, ensure_ascii=True) + "\n"


def main() -> int:
    VECTORS_PATH.write_text(render_vectors(), encoding="utf-8")
    print(f"wrote {VECTORS_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
