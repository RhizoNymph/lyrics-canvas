"""Rhyme pair fixtures for the phonetic engine.

Each entry is (a, b, k, low, high, label). ``score(a, b, k).score`` must fall inside
``[low, high]`` under ``DEFAULT_DIALECT_MAP``. Perfect rhymes pin both bounds to 1.0;
non-rhymes must land below 0.5.
"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Pair:
    a: str
    b: str
    k: int
    low: float
    high: float
    label: str


PERFECT = 1.0
NEAR_LOW = 0.5
NEAR_HIGH = 0.999
NON_HIGH = 0.4999

PAIRS: tuple[Pair, ...] = (
    # --- perfect monosyllabic rhymes ---
    Pair("cat", "bat", 1, PERFECT, PERFECT, "perfect"),
    Pair("cat", "hat", 1, PERFECT, PERFECT, "perfect"),
    Pair("light", "night", 1, PERFECT, PERFECT, "perfect"),
    Pair("moon", "june", 1, PERFECT, PERFECT, "perfect"),
    Pair("love", "dove", 1, PERFECT, PERFECT, "perfect"),
    Pair("time", "rhyme", 1, PERFECT, PERFECT, "perfect"),
    Pair("day", "way", 1, PERFECT, PERFECT, "perfect"),
    Pair("stone", "alone", 1, PERFECT, PERFECT, "perfect"),
    Pair("sky", "fly", 1, PERFECT, PERFECT, "perfect"),
    Pair("heart", "start", 1, PERFECT, PERFECT, "perfect"),
    Pair("gold", "cold", 1, PERFECT, PERFECT, "perfect"),
    Pair("dream", "seem", 1, PERFECT, PERFECT, "perfect"),
    Pair("mind", "find", 1, PERFECT, PERFECT, "perfect"),
    Pair("pain", "rain", 1, PERFECT, PERFECT, "perfect"),
    Pair("street", "beat", 1, PERFECT, PERFECT, "perfect"),
    Pair("name", "game", 1, PERFECT, PERFECT, "perfect"),
    Pair("in the dark", "park", 1, PERFECT, PERFECT, "perfect-phrase"),
    Pair("bird", "word", 1, PERFECT, PERFECT, "perfect"),
    # --- perfect multisyllabic rhymes (k=2) ---
    Pair("station", "nation", 2, PERFECT, PERFECT, "multi"),
    Pair("motion", "ocean", 2, PERFECT, PERFECT, "multi"),
    Pair("banana", "cabana", 2, PERFECT, PERFECT, "multi"),
    Pair("dancing", "prancing", 2, PERFECT, PERFECT, "multi"),
    Pair("letter", "better", 2, PERFECT, PERFECT, "multi"),
    Pair("money", "honey", 2, PERFECT, PERFECT, "multi"),
    Pair("fighter", "writer", 2, PERFECT, PERFECT, "multi"),
    Pair("thunder", "wonder", 2, PERFECT, PERFECT, "multi"),
    Pair("tomorrow", "sorrow", 2, PERFECT, PERFECT, "multi"),
    Pair("attention", "dimension", 2, PERFECT, PERFECT, "multi"),
    Pair("telephone", "saxophone", 2, PERFECT, PERFECT, "multi"),
    Pair("forever", "whenever", 2, PERFECT, PERFECT, "multi"),
    Pair("fire", "higher", 2, PERFECT, PERFECT, "multi"),
    # --- near rhymes ---
    Pair("cat", "cap", 1, 0.7, NEAR_HIGH, "near"),
    Pair("cat", "cash", 1, 0.7, NEAR_HIGH, "near"),
    Pair("time", "fine", 1, 0.7, NEAR_HIGH, "near"),
    Pair("love", "rough", 1, 0.7, NEAR_HIGH, "near"),
    Pair("moon", "room", 1, 0.7, NEAR_HIGH, "near"),
    Pair("heart", "hard", 1, 0.8, NEAR_HIGH, "near"),
    Pair("light", "like", 1, 0.7, NEAR_HIGH, "near"),
    Pair("dream", "green", 1, 0.7, NEAR_HIGH, "near"),
    Pair("stone", "home", 1, 0.7, NEAR_HIGH, "near"),
    Pair("day", "date", 1, NEAR_LOW, 0.8, "near-open-vs-closed"),
    Pair("bottle", "model", 2, 0.8, NEAR_HIGH, "near-multi"),
    Pair("silver", "deliver", 2, NEAR_LOW, NEAR_HIGH, "near-multi"),
    Pair("laughter", "daughter", 2, NEAR_LOW, 0.9, "near-multi"),
    Pair("orange", "door hinge", 2, NEAR_LOW, NEAR_HIGH, "near-dialect"),
    Pair("singing", "sinning", 2, 0.7, NEAR_HIGH, "near-dialect"),
    Pair("bit", "bet", 1, 0.7, NEAR_HIGH, "near-vowel"),
    Pair("bet", "bat", 1, 0.7, NEAR_HIGH, "near-vowel"),
    # --- dialect-map cases that become perfect under the default map ---
    Pair("ring", "thin", 1, PERFECT, PERFECT, "dialect-ing-in"),
    Pair("cot", "caught", 1, PERFECT, PERFECT, "dialect-cot-caught"),
    Pair("test", "mess", 1, 0.85, NEAR_HIGH, "dialect-optional-t"),
    Pair("hand", "man", 1, 0.85, NEAR_HIGH, "dialect-optional-d"),
    Pair("soul", "sold", 1, 0.85, NEAR_HIGH, "dialect-optional-d"),
    # --- non-rhymes ---
    Pair("cat", "dog", 1, 0.0, NON_HIGH, "non"),
    Pair("love", "tree", 1, 0.0, NON_HIGH, "non"),
    Pair("house", "window", 1, 0.0, NON_HIGH, "non"),
    Pair("music", "table", 2, 0.0, NON_HIGH, "non"),
    Pair("cough", "though", 1, 0.0, NON_HIGH, "non"),
    Pair("orange", "purple", 2, 0.0, NON_HIGH, "non"),
    Pair("yellow", "green", 1, 0.0, NON_HIGH, "non"),
    Pair("apple", "stone", 1, 0.0, NON_HIGH, "non"),
    Pair("seven", "night", 1, 0.0, NON_HIGH, "non"),
    Pair("water", "beach", 1, 0.0, NON_HIGH, "non"),
    Pair("moon", "sun", 1, 0.0, NON_HIGH, "non-consonance-only"),
    Pair("sky", "blue", 1, 0.0, NON_HIGH, "non"),
)


assert len(PAIRS) >= 50
