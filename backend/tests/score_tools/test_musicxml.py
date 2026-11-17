"""MusicXML: parsed back with xml.etree; measures sum to their bar lengths, ties at barlines,
tuplets for triplets, lyrics with syllabic."""

import xml.etree.ElementTree as ET

import pytest

from app.services.score import bar_length, check_score
from app.services.score_tools.draft import draft_from_recording, draft_grid
from app.services.score_tools.export import write_musicxml
from tests.score.helpers import grid, score
from tests.score_tools.takes import steady, timings

G = grid("the ^hel-lo ^world", "^go")


def parse(data: bytes) -> ET.Element:
    assert data.startswith(b'<?xml version="1.0" encoding="UTF-8"')
    assert b"<!DOCTYPE score-partwise PUBLIC" in data
    return ET.fromstring(data)


def measures(root: ET.Element) -> list[ET.Element]:
    parts = root.findall("part")
    assert len(parts) == 1 and parts[0].get("id") == "P1"
    return parts[0].findall("measure")


def total(measure: ET.Element) -> int:
    return sum(int(n.findtext("duration")) for n in measure.findall("note"))


def sounding(measure: ET.Element) -> list[ET.Element]:
    return [n for n in measure.findall("note") if n.find("rest") is None]


def check_lengths(root: ET.Element, s) -> None:
    for measure in measures(root):
        number = int(measure.get("number"))
        expected = s.pickup if number == 0 else bar_length(s, number - 1)
        assert total(measure) == expected, f"measure {number}"


def test_document_header():
    s = check_score(score([[(0, 0, 12, 0)], []]), G)
    root = parse(write_musicxml(s, G, "Night & Day"))
    assert root.tag == "score-partwise" and root.get("version") == "4.0"
    assert root.findtext("work/work-title") == "Night & Day"
    assert root.findtext("part-list/score-part/part-name") == "Voice"
    attributes = measures(root)[0].find("attributes")
    assert attributes.findtext("divisions") == "48"
    assert attributes.findtext("clef/sign") == "percussion"
    assert attributes.findtext("staff-details/staff-lines") == "1"


def test_measures_sum_to_bar_lengths_with_pickup_and_meter_changes():
    raw = score(
        [[(0, 0, 12, 0), (1, 12, 16, 1), (2, 28, 8, 0), (3, 40, 300, 2)], [(0, 420, 30, 1)]],
        pickup=12, meters=[(0, 4, 4), (1, 3, 4), (2, 6, 8)], tempos=[(0, 90), (2, 120)],
    )
    s = check_score(raw, G)
    root = parse(write_musicxml(s, G, "x"))
    bars = measures(root)
    assert [(m.get("number"), m.get("implicit")) for m in bars][:2] == [("0", "yes"), ("1", None)]
    check_lengths(root, s)
    times = [(m.get("number"), m.findtext("attributes/time/beats"), m.findtext("attributes/time/beat-type")) for m in bars]
    assert [t for t in times if t[1]] == [("0", "4", "4"), ("2", "3", "4"), ("3", "6", "8")]
    tempos = [(m.get("number"), m.find("direction/sound").get("tempo")) for m in bars if m.find("direction/sound") is not None]
    assert tempos == [("0", "90"), ("3", "120")]


def test_ties_at_barlines():
    g = grid("^a ^b")
    s = check_score(score([[(0, 0, 48, 0), (1, 144, 120, 0)]]), g)  # b: 144..264 crosses 192
    bars = measures(parse(write_musicxml(s, g, "x")))
    last_first = sounding(bars[0])[-1]
    first_second = sounding(bars[1])[0]
    assert [t.get("type") for t in last_first.findall("tie")] == ["start"]
    assert [t.get("type") for t in last_first.findall("notations/tied")] == ["start"]
    assert [t.get("type") for t in first_second.findall("tie")] == ["stop"]
    assert [t.get("type") for t in first_second.findall("notations/tied")] == ["stop"]
    # the lyric is only on the first piece
    assert last_first.find("lyric") is not None and first_second.find("lyric") is None
    assert int(last_first.findtext("duration")) + int(first_second.findtext("duration")) == 120


def test_long_note_inside_a_bar_is_tied_pieces():
    g = grid("^a")
    s = check_score(score([[(0, 0, 60, 0)]]), g)  # quarter + 16th
    notes_ = sounding(measures(parse(write_musicxml(s, g, "x")))[0])
    assert [n.findtext("type") for n in notes_] == ["quarter", "16th"]
    assert [[t.get("type") for t in n.findall("tie")] for n in notes_] == [["start"], ["stop"]]


def test_tuplets_for_triplets():
    g = grid("^la ^la ^la ^go")
    s = check_score(score([[(0, 0, 16, 0), (1, 16, 16, 0), (2, 32, 16, 0), (3, 48, 48, 0)]]), g)
    notes_ = sounding(measures(parse(write_musicxml(s, g, "x")))[0])
    triplets = notes_[:3]
    for n in triplets:
        assert n.findtext("type") == "eighth"
        assert n.findtext("time-modification/actual-notes") == "3"
        assert n.findtext("time-modification/normal-notes") == "2"
    assert [[t.get("type") for t in n.findall("notations/tuplet")] for n in triplets] == [["start"], [], ["stop"]]
    assert notes_[3].find("time-modification") is None and notes_[3].find("notations/tuplet") is None


def test_single_triplet_value_opens_and_closes_its_bracket():
    g = grid("^a ^b")
    s = check_score(score([[(0, 0, 8, 0), (1, 48, 48, 0)]]), g)
    elements = measures(parse(write_musicxml(s, g, "x")))[0].findall("note")
    # note 16th triplet (8), rest 40 = eighth + eighth triplet, note quarter, rest half
    assert [(n.findtext("type"), n.find("time-modification") is not None) for n in elements] == [
        ("16th", True), ("eighth", False), ("eighth", True), ("quarter", False), ("half", False),
    ]
    marks = [[t.get("type") for t in n.findall("notations/tuplet")] for n in elements]
    assert marks == [["start", "stop"], [], ["start", "stop"], [], []]


def test_lyrics_syllabic():
    s = check_score(score([[(0, 0, 12, 0), (1, 12, 12, 1), (2, 24, 12, 0), (3, 36, 12, 2)], [(0, 48, 12, 0)]]), G)
    notes_ = sounding(measures(parse(write_musicxml(s, G, "x")))[0])
    assert [(n.findtext("lyric/syllabic"), n.findtext("lyric/text")) for n in notes_] == [
        ("single", "the"), ("begin", "hel"), ("end", "lo"), ("single", "world"), ("single", "go"),
    ]
    assert all(n.find("lyric").get("number") == "1" for n in notes_)


def test_middle_syllabic():
    g = grid("^beau-ti-ful")
    s = check_score(score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0)]]), g)
    notes_ = sounding(measures(parse(write_musicxml(s, g, "x")))[0])
    assert [n.findtext("lyric/syllabic") for n in notes_] == ["begin", "middle", "end"]


def test_accents():
    s = check_score(score([[(0, 0, 12, 0), (1, 12, 12, 1), (2, 24, 12, 0), (3, 36, 12, 2)], []]), G)
    notes_ = sounding(measures(parse(write_musicxml(s, G, "x")))[0])
    assert [n.find("notations/articulations/accent") is not None for n in notes_] == [False, True, False, False]
    assert notes_[3].find("notations/articulations/strong-accent") is not None


def test_rests_fill_gaps_and_empty_measures_are_whole_rests():
    g = grid("^a ^b")
    s = check_score(score([[(0, 12, 12, 0), (1, 600, 12, 0)]]), g)
    root = parse(write_musicxml(s, g, "x"))
    bars = measures(root)
    check_lengths(root, s)
    assert bars[0].findall("note")[0].find("rest") is not None  # leading 12-tick rest
    assert bars[1].find("note/rest").get("measure") == "yes"
    assert len(bars[1].findall("note")) == 1


def test_unpitched_on_the_single_line():
    s = check_score(score([[(0, 0, 12, 0)], []]), G)
    note = sounding(measures(parse(write_musicxml(s, G, "x")))[0])[0]
    assert note.findtext("unpitched/display-step") == "B" and note.findtext("unpitched/display-octave") == "4"
    assert note.find("pitch") is None


def test_escaping_in_lyrics_and_title():
    g = grid("^<a&b>")
    s = check_score(score([[(0, 0, 12, 0)]]), g)
    root = parse(write_musicxml(s, g, 'say "<hi>" & go'))
    assert root.findtext("work/work-title") == 'say "<hi>" & go'
    assert sounding(measures(root)[0])[0].findtext("lyric/text") == "<a&b>"


@pytest.mark.parametrize("meter", [{"num": 4, "den": 4}, {"num": 3, "den": 4}, {"num": 6, "den": 8}, {"num": 7, "den": 8}, {"num": 5, "den": 16}])
def test_drafts_export_with_consistent_measures(meter):
    g = grid("the ^night is ^young and ^we are ^free", "so ^la ^la ^la")
    words = "the night is young and we are free so la la la".split()
    take = timings(steady(words, [0, 1, 3, 4, 8, 9, 11, 12, 20, 21, 22, 23], [1, 2, 1, 3, 1, 2, 1, 5, 1, 1, 1, 6]))
    for s in (draft_from_recording(g, take, meter, 90).score, draft_grid(g, meter, 90, "1/8t").score):
        root = parse(write_musicxml(s, g, "x"))
        check_lengths(root, s)
        assert sum(1 for m in measures(root) for n in sounding(m) if n.find("lyric") is not None) == len(s.all_notes())
