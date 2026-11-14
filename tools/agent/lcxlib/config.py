"""Configuration: environment first, then ~/.config/lyrics-canvas/agent.env."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

from lcxlib.errors import ConfigError
from lcxlib.logs import kv, log

DEFAULT_API_URL = "http://10.1.1.69:32443"
DEFAULT_ENV_FILE = Path("~/.config/lyrics-canvas/agent.env")


@dataclass(frozen=True)
class Config:
    api_url: str
    token: str = field(repr=False)
    ca_file: str | None = None

    def __repr__(self) -> str:  # never show the token
        return f"Config(api_url={self.api_url!r}, token=<hidden>, ca_file={self.ca_file!r})"


def read_env_file(path: Path) -> dict[str, str]:
    """``KEY=VALUE`` lines; blank lines and ``#`` comments ignored; optional ``export`` and quotes."""
    values: dict[str, str] = {}
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return values
    except OSError as exc:
        raise ConfigError(f"cannot read {path}: {exc.strerror}") from exc
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.removeprefix("export ").partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def load_config(env: Mapping[str, str]) -> Config:
    """Environment first, then the env file (``LCX_ENV_FILE`` or ``~/.config/lyrics-canvas/agent.env``)."""
    path = Path(env.get("LCX_ENV_FILE") or DEFAULT_ENV_FILE).expanduser()
    from_file = read_env_file(path)
    if from_file.get("LYRICS_API_TOKEN") and path.stat().st_mode & 0o077:
        log.warning(kv("config.env_file_readable_by_others", path=str(path), fix=f"chmod 600 {path}"))
    merged = {**from_file, **{k: v for k, v in env.items() if v}}
    token = merged.get("LYRICS_API_TOKEN", "").strip()
    if not token:
        raise ConfigError(
            f"LYRICS_API_TOKEN is not set: create a token in the web app (Account -> API tokens) and put "
            f"LYRICS_API_TOKEN=... in {path}"
        )
    url = merged.get("LYRICS_API_URL", "").strip().rstrip("/") or DEFAULT_API_URL
    if not url.startswith(("http://", "https://")):
        raise ConfigError(f"LYRICS_API_URL must start with http:// or https:// (got {url!r})")
    return Config(api_url=url, token=token, ca_file=merged.get("LYRICS_CA_FILE") or None)
