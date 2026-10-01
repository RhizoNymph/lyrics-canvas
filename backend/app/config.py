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

    model_config = {"env_file": ".env"}


settings = Settings()
