import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, build_unit, pronounce
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.syllables import parse_phones, syllabify_phones
from app.services.phonetics.types import Pronunciation, PronunciationSource, Provenance, WordPronunciation
from app.services.phonetics.units import text_unit_id, unit_from_text


def test_build_unit_derives_fields_under_dialect():
    u = build_unit(
        id="dict:singing",
        text="singing",
        pronunciation=pronounce("singing"),
        provenance=Provenance.dictionary,
        dialect=DEFAULT_DIALECT_MAP,
    )
    assert u.stress == "10"
    assert u.syllable_count == 2
    assert u.rhyme_key == ("IH", "NG", "IH", "N")
    assert u.rhyme_keys[1] == ("IH", "N")
    assert u.rhyme_keys[2] == ("IH", "NG", "IH", "N")
    assert set(u.rhyme_keys) == {1, 2}
    assert u.dialect_digest == DEFAULT_DIALECT_MAP.digest()
    assert u.effective_pronunciation == DEFAULT_DIALECT_MAP.apply(u.pronunciation)


def test_rhyme_key_starts_at_last_primary_stress():
    u = unit_from_text("telephone", DEFAULT_DIALECT_MAP)
    # T EH1 L AH0 F OW2 N: last primary stress is the first syllable
    assert u.stress == "102"
    assert u.rhyme_key == ("EH", "L", "AH", "F", "OW", "N")
    u2 = unit_from_text("attention", DEFAULT_DIALECT_MAP)
    assert u2.rhyme_key == ("EH", "N", "SH", "AH", "N")


def test_rhyme_key_falls_back_to_secondary_then_last_syllable():
    pron = Pronunciation(
        words=(
            WordPronunciation("x", syllabify_phones(parse_phones("B AH0 T AH2 L")), PronunciationSource.cmudict),
        )
    )
    u = build_unit(id="text:x", text="x", pronunciation=pron, provenance=Provenance.generated, dialect=DialectMap(rules=()))
    assert u.rhyme_key == ("AH", "L")
    pron0 = Pronunciation(
        words=(WordPronunciation("y", syllabify_phones(parse_phones("B AH0 T AH0 L")), PronunciationSource.cmudict),)
    )
    u0 = build_unit(id="text:y", text="y", pronunciation=pron0, provenance=Provenance.generated, dialect=DialectMap(rules=()))
    assert u0.rhyme_key == ("AH", "L")


def test_override_wins_over_lookup():
    override = Pronunciation(
        words=(WordPronunciation("cat", syllabify_phones(parse_phones("K AA1 T")), PronunciationSource.override),)
    )
    u = build_unit(
        id="dict:cat",
        text="cat",
        pronunciation=pronounce("cat"),
        provenance=Provenance.dictionary,
        dialect=DEFAULT_DIALECT_MAP,
        pronunciation_override=override,
    )
    assert u.rhyme_key == ("AA", "T")
    assert u.pronunciation_override is override


def test_text_unit_ids_are_deterministic():
    assert text_unit_id("door hinge") == text_unit_id("door hinge")
    assert text_unit_id("door hinge") != text_unit_id("orange")
    assert text_unit_id("door hinge").startswith("text:")
    u = unit_from_text("door hinge", DEFAULT_DIALECT_MAP)
    assert u.id == text_unit_id("door hinge")
    assert u.provenance == Provenance.generated and u.ephemeral is True


def test_rhyme_keys_truncate_to_syllable_count():
    u = unit_from_text("cat", DEFAULT_DIALECT_MAP)
    assert set(u.rhyme_keys) == {1}
    u4 = unit_from_text("supercalifragilistic", DEFAULT_DIALECT_MAP)
    assert set(u4.rhyme_keys) == {1, 2, 3, 4}
    assert len(u4.rhyme_keys[4]) > len(u4.rhyme_keys[3]) > len(u4.rhyme_keys[2]) > len(u4.rhyme_keys[1])


def test_derived_fields_change_with_dialect():
    plain = unit_from_text("singing", DialectMap(rules=()))
    assert plain.rhyme_keys[1] == ("IH", "NG")
    assert plain.dialect_digest != DEFAULT_DIALECT_MAP.digest()


def test_unit_with_no_syllables_is_rejected():
    from app.services.phonetics.errors import PronunciationError

    with pytest.raises(PronunciationError):
        unit_from_text("42", DEFAULT_DIALECT_MAP)
