"""Backfill ``snippets.phonetics`` for every snippet that has none.

Usage (from backend/):
    uv run python -m scripts.backfill_phonetics --report out.csv [--dialect rules.json] [--force]

Existing ``grid_data`` stress wins over the engine; each disagreement becomes a pronunciation
override and a row in the report (snippet_id, line, word, grid_stress, engine_stress).
Idempotent: snippets with phonetics already set are skipped unless ``--force``.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import logging
import sys
from dataclasses import astuple, dataclass, fields
from pathlib import Path
from typing import IO, Protocol

from sqlalchemy import select

from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.dialect import DialectMap, DialectRule, RuleKind
from app.services.phonetics_service import compute_snippet_phonetics_with_report

logger = logging.getLogger("backfill_phonetics")


@dataclass(frozen=True, slots=True)
class ReportRow:
    snippet_id: str
    line: int
    word: int
    grid_stress: str
    engine_stress: str


class SnippetLike(Protocol):
    id: object
    raw_text: str
    grid_data: dict | None
    phonetics: dict | None


def load_dialect(path: Path | None) -> DialectMap:
    if path is None:
        return DEFAULT_DIALECT_MAP
    data = json.loads(path.read_text())
    return DialectMap(
        rules=tuple(
            DialectRule(kind=RuleKind(rule["kind"]), source=tuple(rule["source"]), target=tuple(rule.get("target", [])))
            for rule in data["rules"]
        )
    )


def backfill_snippet(snippet: SnippetLike, dialect: DialectMap, *, force: bool) -> list[ReportRow] | None:
    """Compute and assign phonetics; None when skipped because it was already set."""
    if snippet.phonetics is not None and not force:
        return None
    phonetics, disagreements = compute_snippet_phonetics_with_report(
        snippet.raw_text or "", snippet.grid_data, None, dialect
    )
    snippet.phonetics = phonetics.model_dump(mode="json")
    return [
        ReportRow(
            snippet_id=str(snippet.id),
            line=d.line_index,
            word=d.word_index,
            grid_stress=d.grid_stress,
            engine_stress=d.engine_stress,
        )
        for d in disagreements
    ]


def write_report(stream: IO[str], rows: list[ReportRow]) -> None:
    writer = csv.writer(stream)
    writer.writerow([f.name for f in fields(ReportRow)])
    for row in rows:
        writer.writerow(astuple(row))


@dataclass(slots=True)
class Summary:
    processed: int = 0
    skipped: int = 0
    disagreements: int = 0


async def run(report_path: Path | None, dialect: DialectMap, *, force: bool, batch_size: int = 200) -> Summary:
    from app.db.database import async_session
    from app.models.snippet import Snippet

    summary = Summary()
    rows: list[ReportRow] = []
    async with async_session() as session:
        query = select(Snippet)
        if not force:
            query = query.where(Snippet.phonetics.is_(None))
        result = await session.execute(query.order_by(Snippet.created_at))
        snippets = list(result.scalars().all())
        for start in range(0, len(snippets), batch_size):
            for snippet in snippets[start : start + batch_size]:
                outcome = backfill_snippet(snippet, dialect, force=force)
                if outcome is None:
                    summary.skipped += 1
                    continue
                summary.processed += 1
                rows.extend(outcome)
            await session.commit()
            logger.info("backfill.progress processed=%d skipped=%d", summary.processed, summary.skipped)
    summary.disagreements = len(rows)
    if report_path is not None:
        with report_path.open("w", newline="") as stream:
            write_report(stream, rows)
    else:
        write_report(sys.stdout, rows)
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", type=Path, default=None, help="CSV path for disagreements (stdout if omitted)")
    parser.add_argument("--dialect", type=Path, default=None, help='JSON file {"rules": [...]}')
    parser.add_argument("--force", action="store_true", help="recompute snippets that already have phonetics")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    summary = asyncio.run(run(args.report, load_dialect(args.dialect), force=args.force))
    logger.info(
        "backfill.done processed=%d skipped=%d disagreements=%d", summary.processed, summary.skipped, summary.disagreements
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
