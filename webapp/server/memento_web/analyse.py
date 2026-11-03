"""`python -m memento_web.analyse <operation> <arguments>`: one of trackwork.py's operations on a long track, in a
short-lived process that gives all its memory back when it exits. stdout: a JSON header line, then the body; on
failure the last stderr line is the message for the user, and the exit status is 3 (unreadable) or 4 (missing).
"""
from __future__ import annotations

import sys

if __name__ == "__main__":
    from memento_web.trackwork import main
    sys.exit(main(sys.argv[1:]))
