---
name: lyrics-canvas
description: Read and change the user's Lyrics Canvas account (their songwriting app of canvases, lyric snippets, tags and word bank) through the `lcx` CLI with a reviewed change plan. Use whenever the user asks you to look at, summarise, search or analyse their lyrics, snippets or canvases, or to change their lyrics-canvas data, for example propose or add tags for snippets, organise or group snippets by theme, move or also show snippets on other canvases, create or rename canvases, reorder a canvas's list, add words to the word bank or tag word-bank words, or fix a snippet's text. Every change is shown to the user as a plan diff and applied only after they explicitly approve it.
---

# Lyrics Canvas (lcx)

`lcx` reads the user's Lyrics Canvas account as JSON and changes it only through a **plan file** that
you build, the user reviews, and `lcx` applies. It lives next to this file: run it as
`~/.claude/skills/lyrics-canvas/lcx` (below just `lcx`). Python 3.11+, no packages needed.

## Rules

1. **Never print, echo, log or paste the API token**, and never read `agent.env` aloud (no `cat`).
   `lcx` reads it itself.
2. **Never apply without approval.** For any change: write a plan, run `lcx plan check PLAN.json`,
   show the user the diff output **verbatim**, and ask. Run `lcx plan apply PLAN.json --yes` only after
   the user explicitly says yes to *that* diff. If they ask for changes, edit the plan, re-check, show
   the new diff and ask again. "Looks good, go ahead" for a different or earlier plan is not approval.
3. Change data only through plans: no curl, no other HTTP calls with the token.
4. There are no deletes of snippets or canvases in plans. If the user wants something deleted, tell them
   to do it in the app. `remove_from` a snippet's only canvas is refused for the same reason.
5. On a `version_conflict` never force: re-read the snippet, rebuild the edit on the new text, and ask
   again.

## Setup (once, by the user)

If `lcx whoami` says `LYRICS_API_TOKEN is not set`, ask the user to:

1. open the web app -> account menu -> **API tokens** -> create one (name it e.g. "claude"), copy the
   secret (shown once);
2. put it in `~/.config/lyrics-canvas/agent.env` themselves:
   ```
   LYRICS_API_TOKEN=lct_...
   # LYRICS_API_URL=http://10.1.1.69:32443   (the default)
   ```
   then `chmod 600 ~/.config/lyrics-canvas/agent.env`.

Do not offer to write the token for them, and do not ask them to paste it into the chat. A revoked or
expired token gives `401` (exit 3): ask for a new one.

## Reading

All read commands print JSON to stdout (`--pretty` to indent):

| Command | What |
|---|---|
| `lcx whoami` | the account |
| `lcx canvases` | all canvases (`id`, `title`, `updated_at`, ...) |
| `lcx canvas <id or exact title>` | one canvas: `snippets` in the custom list order, each with `id`, `type`, `text`, `tags` (names), `version`, `list_rank`, `layout`, `canvases` (all canvases holding it). `--full` = the raw API detail |
| `lcx tags` | tags with `snippet_count`, `word_count` |
| `lcx presets` | saved tag filter expressions |
| `lcx bank` | word bank words with their tags |
| `lcx search "<words>" [--limit N]` | canvases by title and snippets by text, across canvases |
| `lcx dump` | the whole account in one read (`canvases`, `placements`, `snippets` with `tag_ids`, `tags`, `tag_presets`, `word_bank`) |

Prefer `lcx dump` when you need to look across everything (it is one request); pipe it through
`python3 -c` or `jq` to pick out what you need rather than reading it all into the conversation.
Snippet text uses `_` for a rest (a silent beat), keep it in edits.

## Plans

A plan is a JSON file (write it to a scratch/temp directory, not into the user's repos):

```json
{"version": 1, "description": "Tag night-themed verses and gather them on one canvas", "ops": [
  {"op": "create_tag", "name": "night"},
  {"op": "tag_snippet", "snippet_id": "<uuid>", "add": ["night"], "remove": ["draft"]},
  {"op": "create_canvas", "title": "Night songs", "ref": "$night"},
  {"op": "move", "snippet_id": "<uuid>", "from_canvas": "Drafts", "to_canvas": "$night"},
  {"op": "show_on", "snippet_id": "<uuid>", "canvas": "$night"},
  {"op": "reorder_list", "canvas": "$night", "snippet_ids": ["<top>", "<second>"]}
]}
```

| Op | Fields | Does |
|---|---|---|
| `create_tag` | `name`, `color`? (`#rrggbb`) | new user-wide tag (no change if the name exists, any case) |
| `tag_snippet` | `snippet_id`, `add`: [names], `remove`: [names] | link / unlink tags on a snippet |
| `tag_word` | `word_id`, `add`, `remove` | same for a word-bank word |
| `move` | `snippet_id`, `from_canvas`, `to_canvas` | take it off one canvas and put it on another (lands at the top of the list) |
| `show_on` | `snippet_id`, `canvas` | also show it on another canvas (same snippet, shared text and tags) |
| `remove_from` | `snippet_id`, `canvas` | take it off one canvas; refused if that is its only canvas |
| `set_layout` | `snippet_id`, `canvas`, any of `position_x`, `position_y`, `width`, `height`, `z_index`, `list_rank` | layout on that canvas |
| `reorder_list` | `canvas`, `snippet_ids` (top to bottom) | the listed snippets go to the top of the canvas's custom list in that order; the rest keep their order below |
| `create_canvas` | `title`, `ref` (`$name`) | new canvas, referable later as `$name`; an existing canvas with exactly that title is reused |
| `rename_canvas` | `canvas`, `title` | rename |
| `add_bank_word` | `text`, `tags`: [names] | add a word or short phrase (1-64 chars, up to 4 words) to the word bank |
| `edit_text` | `snippet_id`, `raw_text`, `base_version` | replace a snippet's whole text; `base_version` is the `version` you read |

- **Canvas** fields take a canvas id, its exact title (as of that point in the plan, so after a
  rename use the new title), or a `$ref` from an earlier `create_canvas`.
- **Tag names** match case-insensitively; a tag must exist or be created by an earlier op.
- Unknown ops or fields are rejected. Ops run in order; ops that are already true show as `=` (no change)
  and are skipped, so re-running a partly applied plan is safe.
- `reorder_list` sets the **Custom** sort; tell the user to pick "Custom" in the list view's sort menu.

### Check, show, approve, apply

```
lcx plan check /tmp/plan.json          # exit 0 = valid, 1 = problems (listed under "Problems")
```
Fix every problem first. Then show the user the full output and ask, e.g. "Apply these 6 changes?".
Lines with `*` change something, `=` are already true, `!` are problems. Only after a clear yes:

```
lcx plan apply /tmp/plan.json --yes
```

`apply` re-reads the account and re-checks first (refusing if anything no longer holds), then applies
step by step and **stops at the first error**, printing `Done`, `FAILED at op N` (with the server's
code and message) and `Not done`. Report that to the user; after fixing the cause the same plan can be
re-run (done steps become no-changes). Add `--json` to `check` or `apply` for machine-readable output.

Exit codes: 0 ok, 1 plan invalid or has problems, 2 usage/config, 3 the server refused a read
(401 = bad token), 4 apply stopped partway.

## Recipes

**Propose tags from content.** `lcx dump` (or `lcx canvas X`), read the snippet texts and existing
`tags`, prefer reusing existing tag names over inventing near-duplicates, and propose a small, consistent
set. First show the proposal as a short table (snippet first line -> tags) if the user asked to
"propose"; then build `create_tag` + `tag_snippet` ops, check, show the diff, wait for a yes.

**Group snippets by theme onto canvases.** Cluster by reading the texts; for each theme one
`create_canvas` (with a `$ref`) or an existing canvas, then `move` (if the user wants them out of the
source) or `show_on` (keep them where they are too; ask which if unclear). Optionally `reorder_list` each
target canvas. Never `remove_from` as a way to delete.

**Reorder a canvas's list.** `lcx canvas "<title>"` gives the current custom order. Build one
`reorder_list` with the snippet ids top to bottom (all of them, or just the ones that should lead). The
diff shows the new numbering with `(was N)`.

**Tag word-bank words.** `lcx bank` and `lcx tags`; build `tag_word` ops (and `create_tag` for new
tags), or `add_bank_word` with `tags` for new words (e.g. rhymes the user liked).

**Fix a snippet's text.** Use the `version` from `lcx canvas` as `base_version` and send the complete new
`raw_text` (all lines). The diff shows `-` old and `+` new lines.
