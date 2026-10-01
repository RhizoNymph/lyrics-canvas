import os
from pathlib import Path

import pytest

# Point g2p_en's NLTK lookups at a private, pre-seeded directory when the test
# runner provides one (see resources.ensure_resources). Falls back to the default.
_nltk_dir = os.environ.get("NLTK_DATA")
if _nltk_dir:
    os.environ.setdefault("NLTK_DATA_DIR", _nltk_dir)


@pytest.fixture(scope="session")
def dialect():
    from app.services.phonetics import DEFAULT_DIALECT_MAP

    return DEFAULT_DIALECT_MAP


@pytest.fixture(scope="session")
def no_dialect():
    from app.services.phonetics.dialect import DialectMap

    return DialectMap(rules=())


@pytest.fixture(scope="session")
def nltk_data_dir(tmp_path_factory) -> Path:
    env = os.environ.get("NLTK_DATA_DIR") or os.environ.get("NLTK_DATA")
    if env:
        return Path(env)
    return tmp_path_factory.mktemp("nltk_data")
