from pathlib import Path

import pytest

from stt_service.config import DEFAULT_MAX_UPLOAD_BYTES, Device, Settings
from stt_service.errors import ConfigError


def test_defaults() -> None:
    s = Settings.from_env({})
    assert s.model == "large-v3-turbo"
    assert s.device is Device.CUDA
    assert s.compute_type == "float16"
    assert s.model_cache_dir is None
    assert s.max_upload_bytes == DEFAULT_MAX_UPLOAD_BYTES == 25 * 1024 * 1024
    assert s.default_language is None
    assert s.offline is False
    assert s.beam_size == 5
    assert s.host == "0.0.0.0"
    assert s.port == 8000
    assert s.log_level == "info"


def test_cpu_defaults_to_int8() -> None:
    s = Settings.from_env({"STT_DEVICE": "cpu"})
    assert s.device is Device.CPU
    assert s.compute_type == "int8"


def test_explicit_values() -> None:
    s = Settings.from_env(
        {
            "STT_MODEL": "tiny",
            "STT_DEVICE": "CPU",
            "STT_COMPUTE_TYPE": "float32",
            "STT_MODEL_CACHE_DIR": "/models/hub",
            "STT_MAX_UPLOAD_BYTES": "1024",
            "STT_DEFAULT_LANGUAGE": " EN ",
            "STT_OFFLINE": "true",
            "STT_BEAM_SIZE": "1",
            "STT_CPU_THREADS": "4",
            "STT_PORT": "9000",
            "STT_LOG_LEVEL": "DEBUG",
            "STT_LOAD_RETRY_INITIAL_SECONDS": "1.5",
            "STT_LOAD_RETRY_MAX_SECONDS": "60",
        }
    )
    assert s.model == "tiny"
    assert s.device is Device.CPU
    assert s.compute_type == "float32"
    assert s.model_cache_dir == Path("/models/hub")
    assert s.max_upload_bytes == 1024
    assert s.default_language == "en"
    assert s.offline is True
    assert s.beam_size == 1
    assert s.cpu_threads == 4
    assert s.port == 9000
    assert s.log_level == "debug"
    assert s.retry.initial_seconds == 1.5
    assert s.retry.max_seconds == 60


def test_empty_strings_mean_default() -> None:
    s = Settings.from_env({"STT_MODEL_CACHE_DIR": "", "STT_DEFAULT_LANGUAGE": "", "STT_COMPUTE_TYPE": ""})
    assert s.model_cache_dir is None
    assert s.default_language is None
    assert s.compute_type == "float16"


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("STT_DEVICE", "tpu"),
        ("STT_COMPUTE_TYPE", "float8"),
        ("STT_MAX_UPLOAD_BYTES", "lots"),
        ("STT_MAX_UPLOAD_BYTES", "0"),
        ("STT_OFFLINE", "maybe"),
        ("STT_BEAM_SIZE", "0"),
        ("STT_CPU_THREADS", "-1"),
        ("STT_PORT", "70000"),
        ("STT_LOG_LEVEL", "loud"),
        ("STT_MODEL", "  "),
        ("STT_DEFAULT_LANGUAGE", "english!"),
        ("STT_LOAD_RETRY_INITIAL_SECONDS", "0"),
        ("STT_LOAD_RETRY_MAX_SECONDS", "x"),
    ],
)
def test_invalid_values_raise_config_error(key: str, value: str) -> None:
    with pytest.raises(ConfigError) as info:
        Settings.from_env({key: value})
    assert info.value.key == key


def test_retry_max_must_not_be_below_initial() -> None:
    with pytest.raises(ConfigError) as info:
        Settings.from_env({"STT_LOAD_RETRY_INITIAL_SECONDS": "10", "STT_LOAD_RETRY_MAX_SECONDS": "5"})
    assert info.value.key == "STT_LOAD_RETRY_MAX_SECONDS"
