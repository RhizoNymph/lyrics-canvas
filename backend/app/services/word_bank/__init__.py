"""Word bank: a per-user list of tagged words and phrases (docs/features/word_bank.md).

- ``text``: word text normalization and validation (pure)
- ``errors``: the typed failures (codes shared with the tag error body)
- ``service``: DB operations scoped to one user
- ``rhymes`` / ``families``: phonetic ranking and clustering over bank words (pure)
- ``related``: the related-word embedding index
"""
