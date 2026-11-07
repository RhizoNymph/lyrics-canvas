from pathlib import Path

from pydantic_settings import BaseSettings

BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://lyrics:lyrics_dev@localhost:5435/lyrics_canvas"
    secret_key: str = "dev-secret-key-change-in-production"
    access_token_expire_minutes: int = 60 * 24 * 7  # 7 days
    cors_origins: list[str] = ["http://localhost:5173"]
    google_client_id: str = ""
    nltk_data_dir: str = str(BACKEND_DIR / ".nltk_data")
    # Voice capture (docs/features/voice_capture.md). Base URL of the STT service, e.g.
    # http://<release>-stt:8000; empty disables voice takes (503 stt_disabled).
    stt_url: str = ""
    stt_timeout_seconds: float = 120.0
    voice_max_upload_bytes: int = 25 * 1024 * 1024
    # Vocabulary hints and the dataset export (docs/features/voice_dataset.md). Hints send the
    # user's word bank to STT as Whisper's initial_prompt (a take can opt out with
    # meta.vocabulary_hints=false); the prompt is at most this many code points.
    voice_vocab_hints: bool = True
    voice_vocab_hint_max_chars: int = 600
    # GET /api/recordings/export refuses (413 export_too_large) more audio than this.
    voice_export_max_bytes: int = 1024 * 1024 * 1024
    # Word bank related words (docs/features/word_bank.md#related-words). Warmup builds the
    # vocabulary embedding index at startup instead of on the first request; the cache dir holds
    # the matrix (empty = $HF_HOME/lyrics-canvas, else ~/.cache/lyrics-canvas).
    related_words_warmup: bool = False
    related_words_cache_dir: str = ""

    model_config = {"env_file": ".env"}


settings = Settings()
