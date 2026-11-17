"""Typed errors, exit codes and plan issues."""

from __future__ import annotations

import enum
from dataclasses import dataclass


class ExitCode(enum.IntEnum):
    OK = 0
    PLAN_INVALID = 1  # the plan does not parse or does not check against the current data
    USAGE = 2  # bad arguments or configuration
    API = 3  # the server refused a read (or the plan's snapshot)
    APPLY_STOPPED = 4  # apply stopped at a failing step; earlier steps are done


class LcxError(Exception):
    exit_code: ExitCode = ExitCode.USAGE


class ConfigError(LcxError):
    exit_code = ExitCode.USAGE


class UsageError(LcxError):
    exit_code = ExitCode.USAGE


class ApiError(LcxError):
    """A non-2xx answer or a transport failure (``status`` 0)."""

    exit_code = ExitCode.API

    def __init__(self, method: str, path: str, status: int, code: str, message: str, body: object = None):
        super().__init__(f"{method} {path} -> {status} {code}: {message}")
        self.method, self.path, self.status, self.code, self.message, self.body = method, path, status, code, message, body

    def as_json(self) -> dict[str, object]:
        return {"method": self.method, "path": self.path, "status": self.status, "code": self.code, "message": self.message}


class IssueCode(enum.StrEnum):
    INVALID_PLAN = "invalid_plan"
    UNKNOWN_OP = "unknown_op"
    INVALID_FIELD = "invalid_field"
    UNKNOWN_CANVAS = "unknown_canvas"
    AMBIGUOUS_CANVAS = "ambiguous_canvas"
    UNKNOWN_REF = "unknown_ref"
    DUPLICATE_REF = "duplicate_ref"
    UNKNOWN_SNIPPET = "unknown_snippet"
    UNKNOWN_WORD = "unknown_word"
    UNKNOWN_TAG = "unknown_tag"
    NOT_ON_CANVAS = "not_on_canvas"
    SAME_CANVAS = "same_canvas"
    LAST_CANVAS = "last_canvas"
    VERSION_CONFLICT = "version_conflict"
    DUPLICATE_SNIPPET = "duplicate_snippet"
    INVALID_RANK = "invalid_rank"


@dataclass(frozen=True)
class Issue:
    index: int  # 1-based op number; 0 for the plan itself
    code: IssueCode
    message: str

    def as_json(self) -> dict[str, object]:
        return {"index": self.index, "code": self.code.value, "message": self.message}


class PlanError(LcxError):
    exit_code = ExitCode.PLAN_INVALID

    def __init__(self, issues: list[Issue]):
        super().__init__("; ".join(f"op {i.index}: {i.message}" for i in issues))
        self.issues = issues
