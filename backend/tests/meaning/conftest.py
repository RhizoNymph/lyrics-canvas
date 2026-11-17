"""The meaning DB test reuses the placement fixtures (a migrated throwaway database,
TAG_TEST_DATABASE_URL; skipped without it)."""

from tests.placements.conftest import db, migrated  # noqa: F401  (fixtures)
