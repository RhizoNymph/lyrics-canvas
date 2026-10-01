from app.services.nlp.rhyme_service import find_rhymes, find_rhymes_in_texts


def test_find_rhymes_shape_and_content():
    result = find_rhymes("cat")
    assert set(result) == {"perfect", "near"}
    assert "bat" in result["perfect"] and "hat" in result["perfect"]
    assert "cat" not in result["perfect"]
    assert result["perfect"] == sorted(result["perfect"])
    assert len(result["perfect"]) <= 50 and len(result["near"]) <= 30
    assert result["near"]
    assert {"cap", "cash", "bad", "back", "mad"} & set(result["near"])
    assert not set(result["perfect"]) & set(result["near"])


def test_find_rhymes_without_near():
    result = find_rhymes("Cat!", include_near=False)
    assert result["near"] == []
    assert "bat" in result["perfect"]


def test_find_rhymes_unknown_input():
    assert find_rhymes("42") == {"perfect": [], "near": []}
    assert find_rhymes("") == {"perfect": [], "near": []}


def test_find_rhymes_in_texts():
    texts = {"s1": "Wearing my hat, sat down", "s2": "walking the dog", "s3": "the cat came back"}
    results = find_rhymes_in_texts("cat", texts)
    by_word = {(r["snippet_id"], r["word"]): r for r in results}
    assert ("s1", "hat") in by_word and by_word[("s1", "hat")]["rhyme_type"] == "perfect"
    assert ("s1", "sat") in by_word
    assert ("s3", "cat") not in by_word  # the word itself is not a rhyme
    assert all(r["source"] == "snippet" for r in results)
    assert all(set(r) == {"word", "rhyme_type", "source", "snippet_id", "snippet_preview"} for r in results)
    assert by_word[("s1", "hat")]["snippet_preview"] == "Wearing my hat, sat down"
    assert results == sorted(results, key=lambda r: (r["rhyme_type"] != "perfect", r["snippet_id"], r["word"]))
