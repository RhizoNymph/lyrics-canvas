import uuid

from app.services.score_tools.export import export_filename, export_title

ID = uuid.UUID("0b7e5d3a-1f2c-4e6b-8a9d-3c4b5a6f7e81")


def test_title_is_the_first_non_blank_line():
    assert export_title("\n   \n  The  night is _ young \nso are we") == "The night is _ young"
    assert export_title("") == "Snippet"
    assert export_title(None) == "Snippet"
    assert len(export_title("a" * 500)) == 80


def test_filename_is_a_slug_of_the_title():
    assert export_filename("The Night, is young!\nnext", ID, "mid") == "the-night-is-young.mid"
    assert export_filename("Don't stop", ID, "musicxml") == "don-t-stop.musicxml"


def test_filename_falls_back_to_the_snippet_id():
    assert export_filename("", ID, "mid") == "snippet-0b7e5d3a.mid"
    assert export_filename("—— ♪ ——", ID, "musicxml") == "snippet-0b7e5d3a.musicxml"


def test_filename_is_bounded():
    name = export_filename("word " * 40, ID, "mid")
    assert len(name) <= 48 + 4 and not name.startswith("-") and "--" not in name
