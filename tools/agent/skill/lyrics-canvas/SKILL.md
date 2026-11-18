---
name: lyrics-canvas
description: Read and change the user's Lyrics Canvas account (their songwriting app of canvases, lyric snippets, tags and word bank) through the `lcx` CLI with a reviewed change plan. Use whenever the user asks you to look at, summarise, search or analyse their lyrics, snippets or canvases (including a snippet's vocal cadence / rhythm score in musical mode), or to change their lyrics-canvas data, for example propose or add tags for snippets, organise or group snippets by theme, move or also show snippets on other canvases, create or rename canvases, reorder a canvas's list, add words to the word bank or tag word-bank words, fix a snippet's text, or set, adjust or remove a snippet's rhythm score. Every change is shown to the user as a plan diff and applied only after they explicitly approve it.
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
| `lcx canvas <id or exact title>` | one canvas: `snippets` in the custom list order, each with `id`, `type`, `text`, `tags` (names), `version`, `list_rank`, `layout`, `canvases` (all canvases holding it), `cadence` (the score, compact; null without one) and `score_view`. `--full` = the raw API detail |
| `lcx tags` | tags with `snippet_count`, `word_count` |
| `lcx presets` | saved tag filter expressions |
| `lcx bank` | word bank words with their tags |
| `lcx search "<words>" [--limit N]` | canvases by title and snippets by text, across canvases |
| `lcx dump` | the whole account in one read (`canvases`, `placements`, `snippets` with `tag_ids`, raw `score` and compact `cadence`, `tags`, `tag_presets`, `word_bank`) |
| `lcx score <snippet id>` | one snippet's score for editing: `version`, `text`, `score_view`, raw `score`, `cadence`, and `lines` with each grid line's `syllables` in ordinal order |

Prefer `lcx dump` when you need to look across everything (it is one request); pipe it through
`python3 -c` or `jq` to pick out what you need rather than reading it all into the conversation.
Snippet text uses `_` for a rest (a silent beat), keep it in edits.

### Reading a cadence (musical mode)

A snippet may have a **score**: its syllables placed on a one-line rhythm staff (no pitch). `cadence`
shows it compactly:

```
4/4, 90 bpm, pickup 0.5 beat, snap 1/16; bar 5: 3/4; bar 9: 120 bpm
1. "I _ go now": pickup: i@4.5~0.5> | bar 1: go@1~1/3 now@1+1/3~1>>
2. "hello world": bar 2: hel@1~0.5 world@2~2  (unplaced: lo)
```

- Header: the first meter and tempo, the pickup (beats before bar 1), the editor's snap, then meter
  and tempo changes at bars.
- One line per text line: `syllable@beat~length`, grouped by bar (`bar 1` is the first full bar,
  `pickup` the upbeat, counted as the end of a bar: `@4.5` is the "and" of 4). Beats and lengths are
  in beats of the meter in force (a quarter in x/4, an eighth in x/8): `~0.5` an eighth note in 4/4,
  `~1/3` an eighth triplet, `@1+1/3` a third of a beat after beat 1. `>` accent, `>>` strong accent.
- `(unplaced: ...)`: syllables without a note. `score_view: true` means the app shows the staff.

Use it to describe the flow: where the downbeats fall, which syllables are stressed, syncopation
(notes off the beat), held syllables, gaps.

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
| `set_score` | `snippet_id`, `score` (a score object, or `null` to remove it), `score_view`? (bool) | replace or remove the snippet's rhythm score; `score_view` true shows the staff in the app |

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
`raw_text` (all lines). The diff shows `-` old and `+` new lines. If the snippet has a score, the server
keeps the notes of unchanged words (changed or new words become unplaced) and may put back `_` rests the
score needs; re-read it afterwards.

**Edit a score.** `lcx score <id>` gives the raw `score` and the grid's syllables. A score is JSON:

```json
{"v": 1, "ppq": 48, "pickup": 0,
 "meters": [{"bar": 0, "num": 4, "den": 4}], "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
 "lines": [{"notes": [{"s": 0, "t": 0, "d": 24, "a": 1}, {"s": 1, "t": 72, "d": 24, "a": 0}]}]}
```

- Time is in ticks: 48 per quarter note (eighth 24, sixteenth 12, eighth triplet 16, sixteenth
  triplet 8, whole 192). A bar of `num/den` is `num * 192 / den` ticks. Tick 0 is the start of the
  pickup; bar 0 (shown as "bar 1") starts at `pickup` ticks. `meters` / `tempos` entries start at a
  bar index (first entry `bar: 0`, strictly increasing); `bpm` 20..400 (quarter notes per minute).
- `lines`: exactly one entry per grid line (same order as the text lines, rest-only lines included).
  A note is `s` (the syllable's ordinal in the line, rests not counted, from `lcx score`'s
  `syllables`), `t` start tick, `d` duration ticks (>= 1), `a` accent 0 / 1 / 2. Keep any other keys
  of an existing note. Each syllable has at most one note, `s` increasing; omit a syllable to leave it
  unplaced.
- One voice: across the whole score (lines in order) every note starts at or after the previous
  note's end (`t >= previous t + d`).
- **Gaps become rests**: between two consecutive placed syllables, a gap of `g` ticks puts
  `floor(g / beat)` rest cells (`_`) there in the grid and text (beat = 192 / den, a quarter in 4/4).
  The diff shows that text change; do not also edit the text for it.
- Change only what the user asked for (start from the current score), build one `set_score` op, check
  it (problems name the reason, line and note, e.g. `notes_overlap, line 0, note 3`), show the diff
  (cadence before `-` and after `+`, view and text changes) and wait for a yes. One `edit_text` or
  `set_score` per snippet per plan: to change text and score, apply the text edit first, re-read,
  then plan the score. Removing a score is `"score": null` (confirm first: it is not recoverable).
