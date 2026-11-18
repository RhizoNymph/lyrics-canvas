import pytest

from app.services.score import check_score
from app.services.score_tools.draft import draft_grid
from app.services.score_tools.export import write_midi
from app.services.score_tools.export.midi import vlq
from tests.score.helpers import grid, score
from tests.score_tools.midi_reader import MidiParseError, parse_midi

G = grid("the ^hel-lo ^world", "^go")


def sample(pickup: int = 0, **kwargs):
    raw = score(
        [[(0, 0, 12, 0), (1, 12, 24, 1), (2, 36, 12, 0), (3, 48, 96, 2)], [(0, 200, 30, 1)]],
        pickup=pickup,
        **kwargs,
    )
    return check_score(raw, G)


def notes(midi):
    track = midi.tracks[0]
    ons = [e for e in track if e.kind == "note_on"]
    offs = [e for e in track if e.kind == "note_off"]
    return ons, offs


@pytest.mark.parametrize(("value", "encoded"), [(0, b"\x00"), (0x7F, b"\x7f"), (0x80, b"\x81\x00"), (0x3FFF, b"\xff\x7f"), (0x0FFFFFFF, b"\xff\xff\xff\x7f")])
def test_vlq(value, encoded):
    assert vlq(value) == encoded


def test_vlq_rejects_out_of_range():
    with pytest.raises(ValueError):
        vlq(0x10000000)


def test_header_format_one_track_division_48():
    midi = parse_midi(write_midi(sample(), G, "Song"))
    assert (midi.format, midi.division, len(midi.tracks)) == (1, 48, 1)


def test_notes_pitch_channel_velocity_and_timing():
    midi = parse_midi(write_midi(sample(), G, "Song"))
    ons, offs = notes(midi)
    assert [(e.tick, e.pitch, e.channel, e.velocity) for e in ons] == [
        (0, 60, 0, 64), (12, 60, 0, 96), (36, 60, 0, 64), (48, 60, 0, 120), (200, 60, 0, 96),
    ]
    assert [e.tick for e in offs] == [12, 36, 48, 144, 230]
    assert all(e.velocity == 0 for e in offs)


def test_lyrics_one_per_note_with_hyphen_inside_words():
    midi = parse_midi(write_midi(sample(), G, "Song"))
    lyrics = [(e.tick, e.data.decode()) for e in midi.metas(0x05)]
    assert lyrics == [(0, "the"), (12, "hel-"), (36, "lo"), (48, "world"), (200, "go")]


def test_lyric_precedes_its_note_on_and_note_off_precedes_the_next():
    track = parse_midi(write_midi(sample(), G, "Song")).tracks[0]
    at_12 = [e.kind if e.kind != "meta" else f"meta{e.meta_type:02x}" for e in track if e.tick == 12]
    assert at_12 == ["note_off", "meta05", "note_on"]


def test_track_name_meter_and_tempo_events():
    s = sample(meters=[(0, 4, 4), (1, 6, 8)], tempos=[(0, 90), (2, 120.5)])
    midi = parse_midi(write_midi(s, G, "Mein Lied ♪"))
    assert midi.metas(0x03)[0].data.decode() == "Mein Lied ♪"
    signatures = [(e.tick, tuple(e.data)) for e in midi.metas(0x58)]
    assert signatures == [(0, (4, 2, 24, 8)), (192, (6, 3, 36, 8))]
    tempos = [(e.tick, int.from_bytes(e.data, "big")) for e in midi.metas(0x51)]
    assert tempos == [(0, 666667), (192 + 144, round(60_000_000 / 120.5))]


def test_pickup_is_the_tail_of_a_full_first_bar():
    # pickup 24 in 4/4: everything moves right by 168 ticks
    raw = score([[(0, 0, 24, 0), (1, 24, 24, 1), (2, 60, 12, 0), (3, 80, 40, 0)], [(0, 200, 10, 0)]], pickup=24, tempos=[(0, 90), (1, 100)])
    midi = parse_midi(write_midi(check_score(raw, G), G, "x"))
    ons, _ = notes(midi)
    assert [e.tick for e in ons] == [168, 192, 228, 248, 368]
    # bar 0 of the score starts on MIDI bar 2 (tick 192); bar 1 of the score at 384
    assert [e.tick for e in midi.metas(0x51)] == [0, 384]


def test_end_of_track_at_the_end_of_the_last_bar():
    track = parse_midi(write_midi(sample(), G, "x")).tracks[0]
    assert track[-1].meta_type == 0x2F and track[-1].tick == 384  # last note ends at 230, bar 1 ends at 384


def test_meta_change_after_the_last_note_is_still_written():
    s = sample(tempos=[(0, 90), (9, 60)])
    midi = parse_midi(write_midi(s, G, "x"))
    assert [e.tick for e in midi.metas(0x51)] == [0, 9 * 192]
    assert midi.tracks[0][-1].tick == 9 * 192


def test_empty_lyric_is_not_written():
    g = {"lines": [{"cells": [{"stress": 1, "word_index": 0}, {"syllable": "b", "stress": 1, "word_index": 1}]}]}
    s = check_score(score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), g)
    midi = parse_midi(write_midi(s, g, "x"))
    assert [e.data for e in midi.metas(0x05)] == [b"b"]
    assert len(notes(midi)[0]) == 2


def test_score_without_notes_is_a_valid_file():
    g = grid("^a")
    midi = parse_midi(write_midi(check_score(score([[]]), g), g, "x"))
    assert notes(midi) == ([], [])
    assert midi.tracks[0][-1].tick == 192


def test_grid_draft_round_trips_note_count():
    g = grid("^I _ ^go ^now", "^and ^then")
    s = draft_grid(g, {"num": 3, "den": 4}, 120, "1/8").score
    ons, offs = notes(parse_midi(write_midi(s, g, "x")))
    assert len(ons) == len(offs) == 5
    assert [e.tick for e in ons] == [n.t for n in s.all_notes()]


def test_reader_rejects_truncation():
    data = write_midi(sample(), G, "x")
    with pytest.raises(MidiParseError):
        parse_midi(data[:-3])
