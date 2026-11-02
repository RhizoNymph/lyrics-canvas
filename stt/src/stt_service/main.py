"""Process entry point: `stt-service` (configured entirely from STT_* env vars)."""

from __future__ import annotations

import logging
import os
import sys

import uvicorn

from .app import create_app
from .config import Settings
from .errors import ConfigError
from .logs import configure_logging, log_event
from .whisper import whisper_loader

logger = logging.getLogger("stt")


def main() -> None:
    try:
        settings = Settings.from_env(os.environ)
    except ConfigError as exc:
        configure_logging("info")
        log_event(logger, logging.ERROR, "config.invalid", key=exc.key, value=exc.value, reason=exc.reason)
        sys.exit(2)
    configure_logging(settings.log_level)
    app = create_app(settings, whisper_loader(settings))
    # Access logs off: probes would drown the transcribe.* events.
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_config=None,
        access_log=False,
        timeout_graceful_shutdown=30,
    )


if __name__ == "__main__":
    main()
