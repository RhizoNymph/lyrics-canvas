# tools/agent: `lcx` and the lyrics-canvas skill

`lcx` is a small stdlib-only Python 3.11+ CLI that reads a Lyrics Canvas account and changes it through
a reviewed plan file. `skill/lyrics-canvas/` is a Claude Code skill that teaches an agent to use it
(check the plan, show the diff, apply only after the user says yes). Design and data flow:
[docs/features/agent_skill.md](../../docs/features/agent_skill.md).

## Install

1. In the web app: account menu -> **API tokens** -> create a token and copy the secret.
2. Store it (never commit it):
   ```sh
   mkdir -p ~/.config/lyrics-canvas
   printf 'LYRICS_API_TOKEN=%s\n' 'lct_...' > ~/.config/lyrics-canvas/agent.env
   chmod 600 ~/.config/lyrics-canvas/agent.env
   ```
   Optional keys: `LYRICS_API_URL` (default `http://10.1.1.69:32443`), `LYRICS_CA_FILE` (a CA bundle
   for an `https://` URL with the homelab CA). Environment variables override the file;
   `LCX_ENV_FILE` points at another file.
3. Install the skill by symlinking it (run from the repo root):
   ```sh
   ln -s "$PWD/tools/agent/skill/lyrics-canvas" ~/.claude/skills/lyrics-canvas
   ```
   `~/.claude/skills/lyrics-canvas/lcx` then runs the CLI (a relative symlink to `tools/agent/lcx.py`).
   For a shell command: `ln -s "$PWD/tools/agent/lcx.py" ~/.local/bin/lcx`.
4. `lcx whoami` should print the account.

## Use

```sh
lcx canvases --pretty
lcx canvas "Drafts"
lcx dump > /tmp/account.json
lcx plan check plan.json
lcx plan apply plan.json          # prints the diff only
lcx plan apply plan.json --yes    # applies, stops at the first error
```

The plan format is documented in the skill and in the feature doc.

## Tests

```sh
cd tools/agent && python -m pytest -q tests
```

`lcxlib/score_ref/` is a generated copy of the backend's score reference (stdlib only); after changing
`backend/app/services/score/` run `python tools/agent/scripts/vendor_score_ref.py` (a test fails while
the copy is stale).

The tests run `lcx` against an in-memory fake of the API (`tests/fake_server.py`, stdlib
`http.server` in a thread); no database or network is used.
