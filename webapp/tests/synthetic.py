"""A made-up Mémento folder for manual runs, screenshots and memory measurements (never real data: the repository
is public).

    python webapp/tests/synthetic.py <folder> [--notes 500 --activities 300 --contacts 300 --events 1000]

Activities reuse windows/tests/data/synthetic.fit (a synthetic track)."""
from __future__ import annotations

import argparse
import random
import sys
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "windows"))

from memento_win.core import atomic  # noqa: E402

WORDS = ("courses pain lait idée projet vacances réunion budget jardin vélo livre recette tarte voyage montagne "
         "rapport facture garage école musique film cadeau anniversaire plan travaux peinture").split()
COLORS = ["default"] * 6 + ["red", "orange", "yellow", "green", "teal", "blue", "darkblue", "purple", "pink", "brown", "gray"]
FIRST = "Marie Jean Lucie Paul Chloé Hugo Emma Louis Léa Gabriel Inès Arthur Jade Noé Alice".split()
LAST = "Martin Bernard Dubois Thomas Robert Richard Petit Durand Leroy Moreau Simon Laurent Lefebvre Michel".split()
STAMP = "2026-10-{day:02d}T{hour:02d}:{minute:02d}:00+02:00"


def words(rng: random.Random, n: int) -> str:
    return " ".join(rng.choice(WORDS) for _ in range(n))


def note_text(rng: random.Random, i: int) -> tuple[str, str]:
    note_id = f"n{i:05d}"
    title = words(rng, rng.randint(1, 4)).capitalize()
    body_kind = rng.random()
    if body_kind < 0.3:
        body = "\n".join(f"- [{'x' if rng.random() < 0.3 else ' '}] {words(rng, rng.randint(1, 3))}" for _ in range(rng.randint(2, 8)))
    elif body_kind < 0.5:
        body = f"# {words(rng, 2).capitalize()}\n\n**{words(rng, 2)}** {words(rng, 12)}\n- {words(rng, 3)}\n- {words(rng, 3)}"
    else:
        body = "\n\n".join(words(rng, rng.randint(5, 40)).capitalize() + "." for _ in range(rng.randint(1, 3)))
    labels = rng.sample(["maison", "travail", "à faire", "idées", "famille"], rng.randint(0, 2))
    day, hour, minute = rng.randint(1, 31), rng.randint(7, 22), rng.randint(0, 59)
    lines = ["---", f"id: {note_id}", f"title: {title}"]
    color = rng.choice(COLORS)
    if color != "default":
        lines.append(f"color: {color}")
    if rng.random() < 0.06:
        lines.append("pinned: true")
    if labels:
        lines.append("labels: [" + ", ".join(f'"{x}"' for x in labels) + "]")
    lines += [f"created: {STAMP.format(day=day, hour=hour, minute=minute)}",
              f"updated: {STAMP.format(day=day, hour=hour, minute=minute)}", "---", "", body, ""]
    return f"{title.split()[0].lower()}-{note_id}.md", "\n".join(lines)


def build(folder: Path, notes: int, activities: int, contacts: int, events: int, seed: int = 7) -> None:
    rng = random.Random(seed)
    folder.mkdir(parents=True, exist_ok=True)
    for i in range(notes):
        name, text = note_text(rng, i)
        atomic.write_text(folder / name, text)
    fit = (ROOT / "windows" / "tests" / "data" / "synthetic.fit").read_bytes()
    (folder / "attachments").mkdir(exist_ok=True)
    for i in range(activities):
        aid = f"a{i:05d}"
        track = f"attachments/sortie-{aid}.fit"
        atomic.write_bytes(folder / track, fit)
        start = 1_790_000_000_000 + i * 86_400_000
        atomic.write_text(folder / "activities" / f"sortie-{aid}.md", (
            f"---\nid: {aid}\ntitle: Sortie {i}\nattachments: [{track}]\n"
            f'activity: {{"sport": "walk", "start": {start}, "elapsed": 3600, "moving": 3300, "distance": 5200.0, '
            f'"ascent": 120.0, "descent": 118.0, "file": "{track}"}}\n'
            f"created: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n---\n"))
    for i in range(contacts):
        cid = f"c{i:05d}"
        first, last = rng.choice(FIRST), rng.choice(LAST)
        atomic.write_text(folder / "contacts" / f"{last.lower()}-{first.lower()}-{cid}.md", (
            f"---\nid: {cid}\ntitle: {first} {last}\nfirst_name: {first}\nlast_name: {last}\n"
            f"phones:\n  - +33 6 {rng.randint(10, 99)} {rng.randint(10, 99)} {rng.randint(10, 99)} {rng.randint(10, 99)} (mobile)\n"
            f"emails:\n  - {first.lower()}.{last.lower()}{i}@example.org\n"
            + (f"birthday: {rng.randint(1950, 2010)}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}\n" if rng.random() < 0.5 else "")
            + "created: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n---\n"))
    first_day = date(2026, 1, 1)
    for i in range(events):
        eid = f"e{i:05d}"
        day = first_day + timedelta(days=rng.randint(0, 700))
        hour = rng.randint(8, 19)
        rrule = "rrule: FREQ=WEEKLY\n" if rng.random() < 0.05 else ""
        atomic.write_text(folder / "agenda" / str(day.year) / f"rdv-{eid}.md", (
            f"---\nid: {eid}\ntitle: {words(rng, 2).capitalize()}\n"
            f"created: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n"
            f"start: {day.isoformat()}T{hour:02d}:00\nend: {day.isoformat()}T{hour + 1:02d}:00\ntz: Europe/Paris\n{rrule}"
            f"uid: {eid}@memento\n---\n\n{words(rng, 8)}\n"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    parser.add_argument("--notes", type=int, default=40)
    parser.add_argument("--activities", type=int, default=5)
    parser.add_argument("--contacts", type=int, default=20)
    parser.add_argument("--events", type=int, default=30)
    a = parser.parse_args()
    build(a.folder, a.notes, a.activities, a.contacts, a.events)
