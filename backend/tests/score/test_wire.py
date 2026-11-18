"""The pydantic wire model (ScoreModel)."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.services.score import ScoreInvalid, ScoreModel, check_score, score_to_json
from tests.score.helpers import grid, score


def test_round_trip_with_extras():
    raw = score([[(0, 0, 12, 1), (1, 12, 12, 0)]], tempos=[(0, 90.5)], pickup=6)
    raw["lines"][0]["notes"][0]["p"] = 60
    model = ScoreModel.model_validate(raw)
    assert model.to_json() == raw
    assert score_to_json(model.to_score(grid("a b"))) == raw
    assert ScoreModel.from_score(check_score(raw, grid("a b"))).to_json() == raw


def test_rejects_unknown_top_level_keys_and_bad_shapes():
    raw = score([[]])
    with pytest.raises(ValidationError):
        ScoreModel.model_validate({**raw, "x": 1})
    with pytest.raises(ValidationError):
        ScoreModel.model_validate({**raw, "snap": "1/4"})


def test_to_score_reports_reasons():
    model = ScoreModel.model_validate(score([[(0, 0, 12, 0)]]))
    with pytest.raises(ScoreInvalid):
        model.to_score(grid("a", "b"))


def test_json_schema_builds():
    schema = ScoreModel.model_json_schema()
    assert set(schema["properties"]) == {"v", "ppq", "pickup", "meters", "tempos", "snap", "lines"}
