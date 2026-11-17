"""A small JSON client for the Lyrics Canvas API (urllib)."""

from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from typing import Any

from lcxlib.config import Config
from lcxlib.errors import ApiError
from lcxlib.logs import kv, log

HTTP_TIMEOUT_S = 60.0


class Api:
    def __init__(self, config: Config):
        self._config = config
        self._ssl = ssl.create_default_context(cafile=config.ca_file) if config.ca_file else None

    def request(self, method: str, path: str, *, query: Mapping[str, object] | None = None, body: object = None) -> Any:
        url = self._config.api_url + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self._config.token}")
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S, context=self._ssl) as resp:
                raw = resp.read()
                log.debug(kv("http.response", method=method, path=path, status=resp.status))
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            log.debug(kv("http.response", method=method, path=path, status=exc.code))
            raise _api_error(method, path, exc.code, raw) from None
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            reason = getattr(exc, "reason", exc)
            raise ApiError(method, path, 0, "unreachable", f"cannot reach {self._config.api_url}: {reason}") from None

    def get(self, path: str, **query: object) -> Any:
        return self.request("GET", path, query=query)


def _api_error(method: str, path: str, status: int, raw: bytes) -> ApiError:
    """FastAPI bodies: ``{"detail": "text"}``, ``{"detail": {"error", "message", ...}}`` or pydantic's list."""
    try:
        body = json.loads(raw) if raw else None
    except ValueError:
        return ApiError(method, path, status, f"http_{status}", raw.decode("utf-8", "replace")[:300])
    detail = body.get("detail") if isinstance(body, dict) else None
    match detail:
        case {"error": str(code), **rest}:
            return ApiError(method, path, status, code, str(rest.get("message", code)), detail)
        case str(text):
            return ApiError(method, path, status, f"http_{status}", text, detail)
        case list(items):
            parts = [f"{'.'.join(str(p) for p in i.get('loc', []))}: {i.get('msg', '')}" for i in items if isinstance(i, dict)]
            return ApiError(method, path, status, "validation_error", "; ".join(parts), detail)
        case _:
            return ApiError(method, path, status, f"http_{status}", json.dumps(body)[:300], body)
