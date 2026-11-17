"""Applying checked steps in order, stopping at the first error."""

from __future__ import annotations

from dataclasses import dataclass

from lcxlib.checker import ApplyContext, CheckResult, Step
from lcxlib.errors import ApiError
from lcxlib.client import Api
from lcxlib.logs import kv, log


@dataclass
class ApplyResult:
    done: list[tuple[Step, object]]
    skipped: list[Step]
    failed: tuple[Step, ApiError] | None
    not_done: list[Step]

    def as_json(self) -> dict[str, object]:
        return {
            "ok": self.failed is None,
            "done": [{"index": s.index, "summary": s.summary, "result": r} for s, r in self.done],
            "skipped": [{"index": s.index, "summary": s.summary, "no_change": s.noop} for s in self.skipped],
            "failed": None if self.failed is None else {"index": self.failed[0].index, "summary": self.failed[0].summary,
                                                       "error": self.failed[1].as_json()},
            "not_done": [{"index": s.index, "summary": s.summary} for s in self.not_done],
        }


def apply_steps(api: Api, result: CheckResult) -> ApplyResult:
    """Run the changing steps in plan order; stop at the first error (never retried or forced)."""
    w = result.world
    ctx = ApplyContext({}, dict(w.tags), {sid: s["version"] for sid, s in w.snippets.items()})
    out = ApplyResult([], [], None, [])
    for n, step in enumerate(result.steps):
        if step.noop is not None or step.run is None:
            out.skipped.append(step)
            continue
        try:
            value = step.run(api, ctx)
        except ApiError as exc:
            log.debug(kv("plan.step_failed", index=step.index, op=step.op, status=exc.status, code=exc.code))
            out.failed = (step, exc)
            out.not_done = [s for s in result.steps[n + 1:] if s.noop is None]
            return out
        log.debug(kv("plan.step_done", index=step.index, op=step.op))
        out.done.append((step, value))
    return out


def render_apply(out: ApplyResult) -> str:
    lines = [f"Done ({len(out.done)}):", *(f"{s.index:>3}. {s.summary}" for s, _ in out.done)]
    if out.failed:
        step, exc = out.failed
        hint = " (changed on the server since the plan was checked; re-read and rebuild this edit)" if exc.code == "version_conflict" else ""
        lines += ["", f"FAILED at op {step.index}: {step.summary}", f"     {exc.status} {exc.code}: {exc.message}{hint}"]
        lines += ["", f"Not done ({len(out.not_done)}):", *(f"{s.index:>3}. {s.summary}" for s in out.not_done)]
    return "\n".join(lines)
