import pytest

from app.services.score import ScoreInvalid, apply_rest_link, draft_from_grid, score_to_json
from app.services.score_tools.draft import draft_grid
from app.services.score_tools.errors import DraftEmptyError
from tests.score.helpers import grid, line_from_cells


def test_grid_draft_is_the_reference_draft_with_counts():
    g = grid("^I _ ^go", "^now")
    result = draft_grid(g, {"num": 4, "den": 4}, 90, "1/8")
    assert score_to_json(result.score) == score_to_json(draft_from_grid(g, {"num": 4, "den": 4}, 90, "1/8"))
    assert (result.bpm_estimated, result.unplaced, result.unmatched_words) == (None, 0, 0)
    assert apply_rest_link(result.score, g) == g


@pytest.mark.parametrize("g", [None, {"lines": []}, {"lines": [line_from_cells([{"rest": True}])]}])
def test_grid_without_syllables_is_draft_empty(g):
    with pytest.raises(DraftEmptyError):
        draft_grid(g, {"num": 4, "den": 4}, 90, "1/16")


@pytest.mark.parametrize(
    ("meter", "bpm", "note", "reason"),
    [
        ({"num": 4, "den": 5}, 90, "1/8", "meter_invalid"),
        ({"num": 4, "den": 4}, 10, "1/8", "tempo_invalid"),
        ({"num": 4, "den": 4}, 90, "1/12", "snap_invalid"),
    ],
)
def test_grid_draft_argument_errors(meter, bpm, note, reason):
    with pytest.raises(ScoreInvalid) as info:
        draft_grid(grid("^a"), meter, bpm, note)
    assert info.value.reason.value == reason
