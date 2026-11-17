#!/usr/bin/env python3
"""lcx: read and change a Lyrics Canvas account from the command line (docs/features/agent_skill.md).

Stdlib only (Python 3.11+). Reads print the API's JSON. Changes go through a typed plan file:
``lcx plan check PLAN.json`` prints what would change, ``lcx plan apply PLAN.json --yes`` applies it.
The API token is read from the environment or ``~/.config/lyrics-canvas/agent.env`` and never printed.
The code lives in ``lcxlib/`` next to this file (found through symlinks).
"""

import sys
from pathlib import Path

if sys.version_info < (3, 11):
    sys.exit("lcx needs Python 3.11 or newer")

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lcxlib.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
