import pronouncing


def find_rhymes(word: str, include_near: bool = True) -> dict:
    """Find perfect and near rhymes from CMU dictionary."""
    word = word.lower().strip()
    result = {"perfect": [], "near": []}

    # Perfect rhymes
    perfect = pronouncing.rhymes(word)
    result["perfect"] = sorted(perfect)[:50]

    if include_near:
        # Near rhymes: words that share ending phonemes but not perfectly
        phones_list = pronouncing.phones_for_word(word)
        if phones_list:
            phones = phones_list[0].split()
            # Get last vowel + everything after (the rhyming part)
            vowel_idx = -1
            for i in range(len(phones) - 1, -1, -1):
                if any(c.isdigit() for c in phones[i]):
                    vowel_idx = i
                    break
            if vowel_idx >= 0 and vowel_idx > 0:
                # Try matching with one less phoneme for near rhymes
                partial = " ".join(phones[vowel_idx + 1:]) if vowel_idx < len(phones) - 1 else ""
                if partial:
                    search = pronouncing.search(partial + "$")
                    near = [w for w in search if w not in result["perfect"] and w != word]
                    result["near"] = sorted(near)[:30]

    return result


def find_rhymes_in_texts(word: str, texts: dict[str, str]) -> list[dict]:
    """Find words that rhyme with the given word within provided texts.
    
    texts: {snippet_id: raw_text}
    """
    word = word.lower().strip()
    rhyme_set = set(pronouncing.rhymes(word))
    results = []

    for snippet_id, text in texts.items():
        words_in_text = set(w.lower().strip(".,!?;:'\"") for w in text.split())
        matches = words_in_text & rhyme_set
        for match in matches:
            results.append({
                "word": match,
                "rhyme_type": "perfect",
                "source": "snippet",
                "snippet_id": snippet_id,
                "snippet_preview": text[:100],
            })

    return results
