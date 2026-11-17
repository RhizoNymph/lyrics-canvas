"""A bounded least-recently-used cache of text -> normalized embedding row.

Touched only from the event loop (the meaning service looks up and inserts there, and encodes
the misses in a worker thread), so it needs no lock. Rows are stored read-only.

Memory: a 384-dim float32 row is 1536 bytes; with the numpy header, the key and the OrderedDict
entry an entry costs about 1.9 KB, so ``CACHE_ENTRIES`` (20 000) is about 38 MB at most.
"""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Iterable, Mapping

import numpy as np

CACHE_ENTRIES = 20_000


class VectorLRU:
    def __init__(self, max_entries: int = CACHE_ENTRIES) -> None:
        if max_entries < 1:
            raise ValueError(f"max_entries must be positive, got {max_entries}")
        self._max = max_entries
        self._rows: OrderedDict[str, np.ndarray] = OrderedDict()

    def __len__(self) -> int:
        return len(self._rows)

    def __contains__(self, text: object) -> bool:
        return text in self._rows

    @property
    def max_entries(self) -> int:
        return self._max

    def get_many(self, texts: Iterable[str]) -> dict[str, np.ndarray]:
        """The cached rows among ``texts`` (each hit becomes the most recently used)."""
        found: dict[str, np.ndarray] = {}
        for text in texts:
            row = self._rows.get(text)
            if row is not None:
                self._rows.move_to_end(text)
                found[text] = row
        return found

    def put_many(self, rows: Mapping[str, np.ndarray]) -> None:
        for text, row in rows.items():
            stored = np.array(row, dtype=np.float32, copy=True)
            stored.setflags(write=False)
            self._rows[text] = stored
            self._rows.move_to_end(text)
        while len(self._rows) > self._max:
            self._rows.popitem(last=False)
