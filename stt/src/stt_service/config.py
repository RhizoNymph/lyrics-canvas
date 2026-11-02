"""Service configuration, read once from the environment at startup."""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from .errors import ConfigError
from .readiness import RetryPolicy

DEFAULT_MAX_UPLOAD_BYTES = 25 * 1024 * 1024

# CTranslate2 compute types (https://opennmt.net/CTranslate2/quantization.html).
COMPUTE_TYPES = frozenset(
    {
        "default",
        "auto",
        "int8",
        "int8_float32",
        "int8_float16",
        "int8_bfloat16",
        "int16",
        "float16",
        "bfloat16",
        "float32",
    }
)
LOG_LEVELS = frozenset({"debug", "info", "warning", "error"})
_LANGUAGE = re.compile(r"^[a-z]{2,3}$")
_TRUE = frozenset({"1", "true", "yes", "on"})
_FALSE = frozenset({"0", "false", "no", "off"})


class Device(StrEnum):
    CUDA = "cuda"
    CPU = "cpu"

    @property
    def default_compute_type(self) -> str:
        match self:
            case Device.CUDA:
                return "float16"
            case Device.CPU:
                return "int8"


@dataclass(frozen=True, slots=True)
class Settings:
    model: str
    device: Device
    compute_type: str
    model_cache_dir: Path | None
    max_upload_bytes: int
    default_language: str | None
    offline: bool
    beam_size: int
    cpu_threads: int
    host: str
    port: int
    log_level: str
    retry: RetryPolicy

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Settings:
        r = _Reader(env)
        device = r.choice("STT_DEVICE", "cuda", {d.value for d in Device}, lower=True)
        dev = Device(device)
        compute_type = r.choice("STT_COMPUTE_TYPE", dev.default_compute_type, COMPUTE_TYPES, lower=True)
        initial = r.positive_float("STT_LOAD_RETRY_INITIAL_SECONDS", 5.0)
        maximum = r.positive_float("STT_LOAD_RETRY_MAX_SECONDS", 300.0)
        if maximum < initial:
            raise ConfigError(
                "STT_LOAD_RETRY_MAX_SECONDS", str(maximum), "must be >= STT_LOAD_RETRY_INITIAL_SECONDS"
            )
        language = r.optional("STT_DEFAULT_LANGUAGE")
        if language is not None:
            language = language.lower()
            if not _LANGUAGE.match(language):
                raise ConfigError("STT_DEFAULT_LANGUAGE", language, "must be a 2-3 letter language code")
        cache_dir = r.optional("STT_MODEL_CACHE_DIR")
        return cls(
            model=r.required("STT_MODEL", "large-v3-turbo"),
            device=dev,
            compute_type=compute_type,
            model_cache_dir=Path(cache_dir) if cache_dir else None,
            max_upload_bytes=r.integer("STT_MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES, minimum=1),
            default_language=language,
            offline=r.boolean("STT_OFFLINE", False),
            beam_size=r.integer("STT_BEAM_SIZE", 5, minimum=1),
            cpu_threads=r.integer("STT_CPU_THREADS", 0, minimum=0),
            host=r.required("STT_HOST", "0.0.0.0"),
            port=r.integer("STT_PORT", 8000, minimum=1, maximum=65535),
            log_level=r.choice("STT_LOG_LEVEL", "info", LOG_LEVELS, lower=True),
            retry=RetryPolicy(initial_seconds=initial, max_seconds=maximum),
        )


@dataclass(frozen=True, slots=True)
class _Reader:
    env: Mapping[str, str]

    def optional(self, key: str) -> str | None:
        value = self.env.get(key, "").strip()
        return value or None

    def required(self, key: str, default: str) -> str:
        if key not in self.env:
            return default
        value = self.env[key].strip()
        if not value:
            raise ConfigError(key, self.env[key], "must not be blank")
        return value

    def choice(self, key: str, default: str, allowed: frozenset[str] | set[str], *, lower: bool) -> str:
        value = self.optional(key)
        if value is None:
            return default
        if lower:
            value = value.lower()
        if value not in allowed:
            raise ConfigError(key, value, f"must be one of {', '.join(sorted(allowed))}")
        return value

    def integer(self, key: str, default: int, *, minimum: int, maximum: int | None = None) -> int:
        value = self.optional(key)
        if value is None:
            return default
        try:
            number = int(value)
        except ValueError as exc:
            raise ConfigError(key, value, "must be an integer") from exc
        if number < minimum or (maximum is not None and number > maximum):
            bound = f">= {minimum}" if maximum is None else f"in [{minimum}, {maximum}]"
            raise ConfigError(key, value, f"must be {bound}")
        return number

    def positive_float(self, key: str, default: float) -> float:
        value = self.optional(key)
        if value is None:
            return default
        try:
            number = float(value)
        except ValueError as exc:
            raise ConfigError(key, value, "must be a number") from exc
        if not number > 0:
            raise ConfigError(key, value, "must be > 0")
        return number

    def boolean(self, key: str, default: bool) -> bool:
        value = self.optional(key)
        if value is None:
            return default
        match value.lower():
            case v if v in _TRUE:
                return True
            case v if v in _FALSE:
                return False
            case _:
                raise ConfigError(key, value, "must be true or false")
