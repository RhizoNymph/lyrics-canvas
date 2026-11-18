# Agent skill and `lcx` CLI

An agent (Claude Code) can read the user's Lyrics Canvas account and change it, but only through a
**plan** that the user sees as a diff and approves. `tools/agent/lcx.py` is the CLI (Python 3.11+,
stdlib only); `tools/agent/skill/lyrics-canvas/SKILL.md` is the skill that tells the agent how to use
it. Authentication is a personal API token (`lct_...`, see the API tokens feature, contract section C).

## Scope

- Configuration from the environment or `~/.config/lyrics-canvas/agent.env`; the token is never
  printed, logged or shown in `repr`.
- Read commands that print the API's JSON: `whoami`, `canvases`, `canvas <id|title>` (compact, list
  order, with each snippet's compact `cadence` and `score_view`), `tags`, `presets`, `bank`,
  `search <q>`, `dump` (`GET /api/sync/snapshot` plus a `cadence` per snippet), `score <snippet>`.
- A typed plan format (version 1, thirteen ops, `set_score` checked locally with the vendored score
  reference, see [score_backend.md](./score_backend.md#agent-cli)), a strict parser, a checker that resolves names and
  simulates the plan over the account to produce a human-readable diff and typed problems, and an
  applier that re-checks, runs the steps in order and stops at the first error.
- A Python port of fractional-indexing 3.2.0 for `reorder_list` ranks and `set_layout.list_rank`
  validation (same algorithm and vectors as `backend/app/services/placements/list_rank.py`).
- The Claude Code skill (frontmatter `name`, `description`), installed by symlinking.
- Tests against an in-memory fake API over HTTP.

## Non-scope

- Deleting snippets, canvases, tags or words (not in plan v1; `remove_from` a snippet's last canvas is
  refused because the server would delete the snippet).
- Token management (`/api/tokens` refuses API-token auth with 403 `token_management_forbidden`; tokens
  are created in the web app).
- Rhyme, similarity, voice, export, merge, groups, presets edits, snippet creation.
- Forcing past a `version_conflict`, retries of failed steps, transactions across steps.

## Files

| File | Role | Key exports |
|---|---|---|
| `tools/agent/lcx.py` | Entry point: checks the Python version, puts its own (symlink-resolved) directory on `sys.path`, calls `lcxlib.cli.main` | - |
| `tools/agent/lcxlib/cli.py` | argparse, read commands, `plan check` / `plan apply` driver, exit codes | `main(argv, *, env, stdout, stderr) -> int`, `build_parser`, `read_command`, `run_plan`, `resolve_canvas_id` |
| `tools/agent/lcxlib/config.py` | env + env file, defaults, permission warning | `Config`, `load_config(env)`, `read_env_file(path)`, `DEFAULT_API_URL`, `DEFAULT_ENV_FILE` |
| `tools/agent/lcxlib/client.py` | urllib JSON client with Bearer auth; maps FastAPI error bodies to `ApiError` | `Api.request(method, path, *, query, body)`, `Api.get` |
| `tools/agent/lcxlib/errors.py` | Typed errors and plan issues | `ExitCode`, `LcxError`, `ConfigError`, `UsageError`, `ApiError`, `PlanError`, `IssueCode`, `Issue` |
| `tools/agent/lcxlib/logs.py` | `key=value` log lines on stderr (`-v` = debug) | `log`, `kv(event, **fields)` |
| `tools/agent/lcxlib/rank.py` | fractional-indexing port | `key_between(a, b)`, `n_keys_between(a, b, n)`, `validate_rank(s)`, `RankError`, `MAX_RANK_LENGTH` |
| `tools/agent/lcxlib/plan.py` | Plan dataclasses (one per op), `Op` union, strict parser | `Plan`, `CreateTag` ... `EditText`, `parse_plan(raw)`, `load_plan(path)`, `LAYOUT_FIELDS`, `PLAN_VERSION` |
| `tools/agent/lcxlib/world.py` | The snapshot as lookup tables; list order | `World.from_snapshot`, `list_order`, `first_line` |
| `tools/agent/lcxlib/checker.py` | Resolution + simulation -> `Step`s (summary, details, no-op reason, runner) and `Issue`s; text diff | `Checker`, `Step`, `ApplyContext`, `CheckResult`, `check_plan`, `render_check` |
| `tools/agent/lcxlib/apply.py` | Ordered execution, stop on first error | `apply_steps`, `ApplyResult`, `render_apply` |
| `tools/agent/lcxlib/cadence.py` | Compact score rendering for reads and diffs | `cadence`, `header`, `line_cadence` |
| `tools/agent/lcxlib/score_ref/` | Generated verbatim copy of the backend score reference (validate, timing, rest link, text splice, rest_marks) | `check_score`, `validate`, `apply_rest_link`, `splice_line_texts`, `changed_line_texts`, `bar_at_tick`, `beat_unit`, `score_to_json` |
| `tools/agent/scripts/vendor_score_ref.py` | Regenerates `score_ref/` from `backend/app/services/` | `render`, `MODULES` |
| `tools/agent/skill/lyrics-canvas/SKILL.md` | The skill: rules, setup, reading, plan format, check-show-approve-apply, recipes | - |
| `tools/agent/skill/lyrics-canvas/lcx` | Relative symlink to `../../lcx.py`, so the installed skill carries the CLI | - |
| `tools/agent/README.md` | Install and use | - |
| `tools/agent/tests/fake_server.py` | In-memory fake of the routes lcx uses (`ThreadingHTTPServer` in a thread), request log, one-shot failures | `FakeBackend`, `FakeServer`, `TOKEN`, `uid` |
| `tools/agent/tests/conftest.py` | Seeded account, `lc` fixture, `Env.run(...)`, `Env.plan_file(...)` | - |
| `tools/agent/tests/test_*.py` | rank port (+ `spec/list_rank_vectors.json` when present), config and reads, parser, check, apply; `test_score.py`: vendored copy current + score vectors, cadence, score reads, `set_score` | - |

## Configuration

`load_config(env)`: reads the env file (`LCX_ENV_FILE`, default `~/.config/lyrics-canvas/agent.env`;
`KEY=VALUE` lines, `#` comments, optional `export` and quotes), then non-empty environment variables
override it. Keys: `LYRICS_API_TOKEN` (required, else `ConfigError` exit 2 explaining where to create
one), `LYRICS_API_URL` (default `http://10.1.1.69:32443`, the production NodePort; must be http(s);
trailing `/` dropped), `LYRICS_CA_FILE` (optional CA bundle for https). If the file holds the token and
is group/world readable, a `config.env_file_readable_by_others` warning is logged.

## Data and control flow

### Reads

```
lcx <cmd> -> cli.main -> load_config -> Api -> GET route -> JSON -> stdout (indent with --pretty)
```

| Command | Route |
|---|---|
| `whoami` | `GET /api/auth/me` |
| `canvases` | `GET /api/canvases` |
| `canvas X` | `GET /api/canvases` (resolve id or exact title; 0 or 2+ matches -> exit 2) then `GET /api/canvases/{id}`; compact: `{id, title, version, updated_at, snippets: [{id, placement_id, type, text, tags: [names], version, cadence, score_view, content_updated_at, list_rank, layout{...}, canvases}]}` in list order; `--full` = raw |
| `tags` / `presets` / `bank` | `GET /api/tags` / `GET /api/tag-presets` / `GET /api/word-bank` |
| `search q` | `GET /api/search?q=&limit=` (default 50) |
| `dump` | `GET /api/sync/snapshot`, each snippet gains `cadence` (compact score, null without one) |
| `score ID` | `GET /api/sync/snapshot`; `{id, version, text, score_view, score, cadence, lines: [{text, syllables}]}`; unknown id -> exit 2 |

List order everywhere is the contract's custom order: `list_rank` ascending (byte order), then
`placement_id`.

### Plans

```
plan check FILE   : load_plan -> parse_plan (PlanError -> exit 1, no request)
                    -> GET /api/sync/snapshot -> World -> Checker.check -> render_check / --json
                    exit 0 if no issues else 1; never writes
plan apply FILE   : same check (fresh snapshot); issues -> exit 1, nothing sent
                    without --yes: print the diff + "Not applied ..." (exit 0)
                    with --yes: apply_steps -> render_check + render_apply; exit 0 or 4
```

**Parsing** (`plan.py`): top level `{version: 1, description?: str, ops: [..non-empty..]}`, unknown
top-level fields rejected. Each op is looked up in `_OP_FIELDS` (dataclass, field kinds, required);
unknown op names (`unknown_op`), unknown or missing fields and wrong types (`invalid_field`; bools are
not numbers) are all collected and reported together with their 1-based op index. Extra rules:
`set_layout` needs at least one layout field, `edit_text.base_version >= 1`, `create_canvas.ref`
matches `$[A-Za-z0-9_-]{1,64}`; `set_score.score` must be present (an object or null) and
`score_view` a bool.

**Checking** (`checker.py`): `Checker` walks the ops in order over a mutable copy of the account:
canvas titles by key (ids and `$refs`), each canvas's list order, snippet tags and word tags by lower
name, the bank's lower texts, defined refs, edited snippets. Each op either becomes a `Step` (with a
summary, detail lines, an optional no-op reason, and a runner closure) or an `Issue` (the op raised
`_Skip`), and later ops see the simulated effect of earlier ones.

- Canvas fields: `$ref` -> must be defined by an earlier `create_canvas` (`unknown_ref`); a uuid of an
  existing canvas; else an exact title as of that point (after renames) -> `unknown_canvas` /
  `ambiguous_canvas`.
- Tag names: `strip().lower()` must be an existing tag or one created earlier (`unknown_tag`).
- Snippet ids must be in the snapshot (`unknown_snippet`); word ids likewise (`unknown_word`).

| Op | Check | Runner (apply) |
|---|---|---|
| `create_tag` | name 1-100 chars, no control chars; color `#rrggbb`; exists (any case) -> no-op | `POST /api/tags {name, color?}`; records the id for later ops |
| `tag_snippet` | add/remove resolved; same tag in both or neither list -> `invalid_field`; only real changes kept, none -> no-op | `POST /api/snippets/{id}/tags?tag_id=` per add, `DELETE /api/snippets/{id}/tags/{tag}` per remove; keeps the returned `version` |
| `tag_word` | same over the word's tags | `POST /api/word-bank/tags {word_ids:[id], tag_id, op: link|unlink}` per tag |
| `move` | from != to (`same_canvas`); not on from: on to -> no-op, else `not_on_canvas`; already on to -> detail "only removed from" | `POST /api/canvases/{from}/snippets/transfer {snippet_ids:[id], target_canvas_id, mode: move}` |
| `show_on` | already on the canvas -> no-op; source = first canvas holding it | same route, `mode: show`, from that source |
| `remove_from` | not on it -> no-op; its only canvas -> `last_canvas` | `DELETE /api/canvases/{cid}/snippets/{id}`; a `snippet_deleted: true` answer is reported as an error |
| `set_layout` | on the canvas (`not_on_canvas`); `list_rank` via `validate_rank` (`invalid_rank`); all given fields equal the snapshot -> no-op | `PUT /api/snippets/{id}?canvas_id= {given layout fields}` |
| `reorder_list` | duplicates (`duplicate_snippet`), all on the canvas (`not_on_canvas`); new order = listed + the rest in current order; equal -> no-op; details number the new order with `(was N)` | `GET /api/canvases/{cid}` (live order, so snippets moved in by earlier steps are included), same composition, `n_keys_between(None, None, n)`, one `PATCH /api/snippets/batch-position {canvas_id, updates:[{id, list_rank}]}`; `updated != n` -> `partial_update` error |
| `create_canvas` | ref unused (`duplicate_ref`); title 1-255; exactly one existing canvas with that title -> no-op and the ref binds to it; two or more -> `ambiguous_canvas` | `POST /api/canvases {title}`; binds `$ref` to the new id |
| `rename_canvas` | title 1-255; same -> no-op | `PUT /api/canvases/{cid} {title}` |
| `add_bank_word` | text NFC + whitespace-collapsed, 1-64 chars; tags resolved; already in the bank (lower text) with all those tags -> no-op; in the bank without them -> "only links the tags" | `POST /api/word-bank {words:[text], tag_ids}` |
| `edit_text` | one `edit_text` or `set_score` per snippet per plan; `base_version` must equal the snapshot version (`version_conflict`); on some canvas at that point; same text -> no-op; details `-`/`+` lines (plus a note when the snippet has a score: the server reconciles it) | `PUT /api/snippets/{id}?canvas_id= {raw_text, base_version}` where base_version is the latest version this apply has seen (own tag changes bump it); 409 stops the apply |
| `set_score` | one `edit_text` or `set_score` per snippet per plan; on some canvas; `score` (object or null, required) validated with the vendored `check_score` against the snapshot grid (`invalid_score` with reason, line, note); canonical score, rest-linked grid and spliced text computed locally; same score and view -> no-op; details: cadence `-` / `+`, `view: grid -> staff`, text change | `PUT /api/snippets/{id}?canvas_id= {score, score_view?, base_version}` (latest version seen) |

**Diff text** (`render_check`): `Plan: <description>`, a count line (`N ops: C changes, D already
done[, P problems]`), then one line per step: `  3. * <summary>` with indented details, or
`  3. = <summary>  (no change: <reason>)`; then `Problems (nothing will be applied until they are
fixed):` with `  5. ! <code>: <message>`. Snippets are labelled `<first 8 id chars> [<type>]
"<first non-empty line, 40 chars>"`; canvases `"<title>"` plus ` ($ref)` for plan-created ones.

**Applying** (`apply.py`): `ApplyContext` starts from the fresh snapshot (tag ids by lower name,
snippet versions) and gains `$ref` -> id and new tag ids as steps run. No-op steps are skipped (never
sent). The first `ApiError` (any non-2xx, transport failure, or a runner's own consistency error)
stops the run: `ApplyResult{done, skipped, failed, not_done}` -> `Done (n):`, `FAILED at op N:
<summary>` with `<status> <code>: <message>` (plus a re-read hint for `version_conflict`), and
`Not done (n):`. Re-running the same plan is safe: finished steps check as no-ops.

### Errors from the server

`client._api_error` maps FastAPI bodies: `{"detail": {"error", "message", ...}}` -> that code;
`{"detail": "text"}` -> `http_<status>`; pydantic lists -> `validation_error` with `loc: msg` parts;
anything else -> `http_<status>`. Transport failures are `unreachable` with status 0.

### Exit codes

0 ok; 1 plan does not parse or has problems; 2 usage or configuration; 3 a read (or the plan's
snapshot) failed, e.g. 401 for a revoked token; 4 apply stopped at a failing step.

## Skill

`SKILL.md`'s description triggers on requests to look at or change the user's lyrics-canvas data
(propose tags, organise or move snippets across canvases, reorder lists, tag word-bank words, fix
text). Its rules: never print the token or read the env file aloud; never `apply --yes` before the user
explicitly approved the exact `plan check` output; changes only through plans; no deletes; never force
past a conflict. It covers setup (the user creates a token in Account -> API tokens and writes the env
file themselves), the read commands, the op table, the check -> show -> approve -> apply loop, exit
codes, and recipes (propose tags, group by theme onto canvases, reorder a list, tag word-bank words,
fix text). Install: `ln -s "$PWD/tools/agent/skill/lyrics-canvas" ~/.claude/skills/lyrics-canvas`
from the repo root; the skill directory's `lcx` symlink resolves to the CLI.

## Invariants

- The token only ever goes into the `Authorization: Bearer` header; it is not in any output, log line,
  error message or `repr` (tested with `-v` and with a rejected token).
- `plan check` and `plan apply` without `--yes` send only `GET /api/sync/snapshot`.
- `plan apply --yes` sends nothing unless a fresh check has no problems; steps run in plan order and
  nothing runs after a failed step; nothing is retried, and `edit_text` never omits `base_version`.
- No op deletes a snippet: `remove_from` of a last canvas is refused at check time.
- `reorder_list` always writes a full, strictly ascending set of valid ranks for every placement on the
  canvas in one call.
- The rank port matches fractional-indexing 3.2.0 and passes `spec/list_rank_vectors.json`.
- Stdlib only; Python 3.11 syntax (tests pass on 3.11 and 3.13).

## Decisions

- **Not one file.** The contract asks for a one-file `lcx.py`; the implementation grew to ~1300 lines,
  over the repo's ~1000-line split rule, so `lcx.py` is a thin entry point and the code is in
  `tools/agent/lcxlib/` (still stdlib only, still runnable as `python3 tools/agent/lcx.py` or through
  the skill's symlink).
- **Already-true ops are no-ops, not errors** (an existing tag, a snippet already on the target,
  `create_canvas` with an existing unique title binding the `$ref` to it), so a plan that stopped
  partway can be re-run unchanged.
- **`reorder_list` with a subset** puts the listed snippets on top and keeps the rest in their current
  custom order below, then re-ranks the whole canvas with fresh `n_keys_between(None, None, n)` keys
  in one batch-position call (the same "adopt" write the clients use), reading the live canvas at apply
  time.
- **`show_on` picks its source** as the first canvas (snapshot order) holding the snippet, since the
  transfer route needs one and the result is the same.
- **`set_layout` also accepts `width`, `height`, `z_index`** (the PUT layout fields), beyond the
  contract's example.
- **One `edit_text` per snippet per plan**; its `base_version` is checked against the snapshot and
  the apply sends the latest version it observed, so the plan's own tag changes don't conflict with it.
- **`set_score`** (musical mode, [score_backend.md](./score_backend.md#agent-cli)): no
  `base_version` field (contract shape), the apply sends the latest version seen; it shares the
  one-content-edit-per-snippet rule with `edit_text` since a score cannot be checked against a text
  edit's grid locally. The score reference is vendored (generated copy + currency test), not imported
  (the backend package needs pydantic) nor hand-ported.
- `LYRICS_CA_FILE` (optional) for an https URL signed by the homelab CA; `LCX_ENV_FILE` to point at
  another env file (used by the tests).
