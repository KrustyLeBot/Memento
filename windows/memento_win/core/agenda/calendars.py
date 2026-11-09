"""`agenda/calendars.json`: the calendars (name, colour, visible) and the layers (activities, notes, birthdays).

Synced with the notes like `activities/settings.json`. Unknown keys are kept. An unreadable file gives the
defaults plus a `problem` (French) for the UI to show; it is never overwritten silently: saving is the UI's choice.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

from .. import note as fmt
from .model import format_alert, parse_alert

PATH = "agenda/calendars.json"
# Reminders of a new event ("Rappels par défaut"), synced with the phone: `["-15m", "-1d"]`.
KEY_ALERTS = "default_alerts"
KEY_ALERTS_ALL_DAY = "default_alerts_all_day"

# Fixed palette: every colour has a contrast of at least 3:1 against white and against #121212 (bars and
# dots read on the light and the dark theme). Checked by tests on both platforms.
PALETTE: list[tuple[str, str]] = [
    ("#4F8BF5", "Bleu"), ("#2E9E5B", "Vert"), ("#D9534F", "Rouge"), ("#E0663A", "Orange"),
    ("#B0851A", "Ocre"), ("#1E9A8A", "Sarcelle"), ("#2B8FC4", "Ciel"), ("#5C6BC0", "Indigo"),
    ("#8E63CE", "Violet"), ("#C2549C", "Prune"), ("#D64D6F", "Framboise"), ("#78858F", "Ardoise"),
]

LAYER_ACTIVITIES = "activities"
LAYER_NOTES = "notes"
LAYER_BIRTHDAYS = "birthdays"
DEFAULT_LAYER_COLORS = {LAYER_ACTIVITIES: "#2E9E5B", LAYER_NOTES: "#8E63CE", LAYER_BIRTHDAYS: "#D64D6F"}


@dataclass
class Calendar:
    id: str
    name: str
    color: str
    visible: bool = True
    import_id: str | None = None  # set when an ICS import created it (undo removes it)
    extra: dict = field(default_factory=dict)


@dataclass
class Layer:
    visible: bool = True
    color: str = "#78858F"
    sports: list[str] | None = None  # activities only: sport keys shown; None = all
    extra: dict = field(default_factory=dict)


def _default_layers() -> dict[str, Layer]:
    return {k: Layer(True, c) for k, c in DEFAULT_LAYER_COLORS.items()}


@dataclass
class CalendarSettings:
    calendars: list[Calendar] = field(default_factory=lambda: [Calendar("perso", "Perso", PALETTE[0][0])])
    default: str = "perso"
    layers: dict[str, Layer] = field(default_factory=_default_layers)
    extra: dict = field(default_factory=dict)
    problem: str | None = None  # why the file could not be read (the defaults are used)

    def calendar(self, calendar_id: str | None) -> Calendar:
        """The calendar of an event; an unknown or missing id is the default calendar (a sync may bring
        the event before the settings file)."""
        for c in self.calendars:
            if c.id == calendar_id:
                return c
        return self.default_calendar()

    def default_calendar(self) -> Calendar:
        for c in self.calendars:
            if c.id == self.default:
                return c
        return self.calendars[0] if self.calendars else Calendar(self.default or "perso", "Perso", PALETTE[0][0])

    def layer(self, key: str) -> Layer:
        return self.layers.get(key) or Layer(True, DEFAULT_LAYER_COLORS.get(key, "#78858F"))

    def default_alerts(self, all_day: bool) -> list[int]:
        """Reminders of a new event (minutes, negative = before), as the phone's `CalendarSettings.defaultAlerts`:
        unset -> 15 min before for a timed event, none for an all-day one."""
        raw = self.extra.get(KEY_ALERTS_ALL_DAY if all_day else KEY_ALERTS)
        if raw is None:
            return [] if all_day else [-15]
        out = []
        for v in raw if isinstance(raw, list) else []:
            try:
                out.append(parse_alert(str(v)))
            except ValueError:
                pass
        return out

    def set_default_alerts(self, all_day: bool, alerts: list[int]) -> None:
        self.extra[KEY_ALERTS_ALL_DAY if all_day else KEY_ALERTS] = [format_alert(m) for m in sorted(set(alerts))]

    def to_json(self) -> str:
        cals = []
        for c in self.calendars:
            d = {"id": c.id, "name": c.name, "color": c.color, "visible": c.visible}
            if c.import_id:
                d["import"] = c.import_id
            cals.append({**d, **{k: v for k, v in c.extra.items() if k not in d}})
        layers = {}
        for key, layer in self.layers.items():
            d = {"visible": layer.visible, "color": layer.color}
            if layer.sports is not None:
                d["sports"] = list(layer.sports)
            layers[key] = {**d, **{k: v for k, v in layer.extra.items() if k not in d}}
        data = {"calendars": cals, "default": self.default, "layers": layers}
        data.update({k: v for k, v in self.extra.items() if k not in data})
        return json.dumps(data, ensure_ascii=False, indent=2) + "\n"

    @staticmethod
    def parse(text: str) -> "CalendarSettings":
        try:
            data = json.loads(text)
            if not isinstance(data, dict):
                raise ValueError("pas un objet JSON")
            cals = []
            for raw in data.get("calendars") or []:
                if not isinstance(raw, dict) or not isinstance(raw.get("id"), str) or not raw["id"]:
                    raise ValueError(f"agenda sans id : {raw!r}")
                cals.append(Calendar(
                    id=raw["id"], name=str(raw.get("name") or raw["id"]),
                    color=str(raw.get("color") or PALETTE[len(cals) % len(PALETTE)][0]),
                    visible=raw.get("visible") is not False,
                    import_id=raw.get("import") if isinstance(raw.get("import"), str) else None,
                    extra={k: v for k, v in raw.items() if k not in ("id", "name", "color", "visible", "import")},
                ))
            layers = _default_layers()
            for key, raw in (data.get("layers") or {}).items():
                if not isinstance(raw, dict):
                    continue
                sports = raw.get("sports")
                layers[key] = Layer(
                    visible=raw.get("visible") is not False,
                    color=str(raw.get("color") or DEFAULT_LAYER_COLORS.get(key, "#78858F")),
                    sports=[str(s) for s in sports] if isinstance(sports, list) else None,
                    extra={k: v for k, v in raw.items() if k not in ("visible", "color", "sports")},
                )
        except (ValueError, TypeError, AttributeError) as error:
            return CalendarSettings(problem=f"{PATH} illisible : {error}")
        if not cals:
            cals = [Calendar("perso", "Perso", PALETTE[0][0])]
        default = data.get("default") if isinstance(data.get("default"), str) else cals[0].id
        extra = {k: v for k, v in data.items() if k not in ("calendars", "default", "layers")}
        return CalendarSettings(cals, default, layers, extra)


def next_color(settings: CalendarSettings) -> str:
    """The first palette colour no calendar uses yet (cycling when all are taken)."""
    used = [c.color.upper() for c in settings.calendars]
    for color, _ in PALETTE:
        if color.upper() not in used:
            return color
    return PALETTE[len(settings.calendars) % len(PALETTE)][0]


def unique_calendar_id(name: str, settings: CalendarSettings) -> str:
    base = fmt.slug(name)
    taken = {c.id for c in settings.calendars}
    candidate, n = base, 2
    while candidate in taken:
        candidate, n = f"{base}-{n}", n + 1
    return candidate
