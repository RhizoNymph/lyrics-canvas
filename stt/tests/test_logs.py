import logging

from stt_service.logs import KeyValueFormatter, log_event


def format_record(event: str, level: int = logging.INFO, **fields: object) -> str:
    logger = logging.getLogger("stt.test")
    captured: list[logging.LogRecord] = []

    class Capture(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured.append(record)

    handler = Capture()
    logger.addHandler(handler)
    logger.setLevel(logging.DEBUG)
    logger.propagate = False
    try:
        log_event(logger, level, event, **fields)
    finally:
        logger.removeHandler(handler)
    return KeyValueFormatter().format(captured[0])


def test_key_value_line() -> None:
    line = format_record("transcribe.done", words=12, duration_ms=3400, language="en")
    assert line.startswith("ts=")
    assert " level=info logger=stt.test event=transcribe.done " in line
    assert line.endswith("words=12 duration_ms=3400 language=en")


def test_values_with_spaces_and_quotes_are_quoted() -> None:
    line = format_record("model.load_failed", logging.ERROR, error='RuntimeError: "cuda" missing')
    assert ' error="RuntimeError: \\"cuda\\" missing"' in line
    assert " level=error " in line


def test_empty_and_none_values() -> None:
    line = format_record("x", language=None, prompt="")
    assert line.endswith('language=none prompt=""')


def test_plain_library_messages_become_msg_field() -> None:
    record = logging.LogRecord(
        "uvicorn.error", logging.INFO, __file__, 1, "Started server process [%d]", (7,), None
    )
    line = KeyValueFormatter().format(record)
    assert ' event=log msg="Started server process [7]"' in line


def test_exceptions_are_single_line() -> None:
    logger = logging.getLogger("stt.exc")
    try:
        raise ValueError("bad\nthing")
    except ValueError:
        record = logger.makeRecord("stt.exc", logging.ERROR, __file__, 1, "boom", (), None)
        import sys

        record.exc_info = sys.exc_info()
    line = KeyValueFormatter().format(record)
    assert "\n" not in line
    assert "exc=" in line
