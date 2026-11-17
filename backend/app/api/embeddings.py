"""Process-wide embedding services and their route dependencies (overridden in tests).

- ``related_words``: the word bank related-word index (docs/features/word_bank.md#related-words).
- ``meaning_service``: meaning similarity for the "Rhyme, then meaning" order of rhyme-suggest,
  rhyme-pattern suggest and word bank rhymes (docs/features/rhyme_view.md#rhyme-then-meaning).
  It reads dictionary words' rows from the related-word index once that is built.

Both use the one sentence-transformers model of app/services/nlp/embedding_service.py.
"""

from __future__ import annotations

from app.config import settings
from app.services.meaning.embedder import sentence_transformer_embedder
from app.services.meaning.service import MeaningService
from app.services.word_bank.related import RelatedWords, default_builder

related_words = RelatedWords(default_builder(settings.related_words_cache_dir))
meaning_service = MeaningService(sentence_transformer_embedder, vocabulary=related_words)


def get_related_words() -> RelatedWords:
    return related_words


def get_meaning_service() -> MeaningService:
    return meaning_service
