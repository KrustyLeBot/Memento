from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSettings


class Config:
    def __init__(self):
        self._settings = QSettings("Scriptorium", "ScriptoriumWindows")  # Windows registry

    @property
    def workdir(self) -> Path | None:
        value = self._settings.value("workdir", "")
        return Path(value) if value else None

    @workdir.setter
    def workdir(self, path: Path) -> None:
        self._settings.setValue("workdir", str(path))
        # A folder chosen while empty (a new start) is not asked about again at every launch while it stays so.
        try:
            empty = path.is_dir() and not any(path.iterdir())
        except OSError:
            empty = False
        self._settings.setValue("empty_workdir", str(path) if empty else "")
        self._settings.sync()

    @property
    def empty_workdir(self) -> str:
        """The folder the user chose although it was empty ("" when none)."""
        return str(self._settings.value("empty_workdir", ""))

    def forget_empty_workdir(self) -> None:
        self._settings.setValue("empty_workdir", "")
        self._settings.sync()

    @property
    def geometry(self):
        return self._settings.value("geometry")

    @geometry.setter
    def geometry(self, value) -> None:
        self._settings.setValue("geometry", value)
        self._settings.sync()

    @property
    def grid(self) -> bool:
        return str(self._settings.value("grid", "true")).lower() == "true"

    @grid.setter
    def grid(self, value: bool) -> None:
        self._settings.setValue("grid", "true" if value else "false")
        self._settings.sync()

    @property
    def map_mode(self) -> str:
        """Last 3D/2D choice of the activity map."""
        return "2d" if str(self._settings.value("map_mode", "3d")) == "2d" else "3d"

    @map_mode.setter
    def map_mode(self, value: str) -> None:
        self._settings.setValue("map_mode", value)
        self._settings.sync()

    @property
    def theme(self) -> str:
        return str(self._settings.value("theme", "system"))

    @theme.setter
    def theme(self, value: str) -> None:
        self._settings.setValue("theme", value)
        self._settings.sync()

    @property
    def last_module(self) -> str:
        return str(self._settings.value("last_module", "notes"))

    @last_module.setter
    def last_module(self, value: str) -> None:
        self._settings.setValue("last_module", value)
        self._settings.sync()

    @property
    def contacts_first_name_first(self) -> bool:
        """Contacts sorted and grouped by first name ("Prénom d'abord"); a setting of this PC."""
        return str(self._settings.value("contacts_first_name_first", "false")).lower() == "true"

    @contacts_first_name_first.setter
    def contacts_first_name_first(self, value: bool) -> None:
        self._settings.setValue("contacts_first_name_first", "true" if value else "false")
        self._settings.sync()

    @property
    def hidden_modules(self) -> set[str]:
        value = str(self._settings.value("hidden_modules", ""))
        return {k for k in value.split(",") if k}

    @hidden_modules.setter
    def hidden_modules(self, value: set[str]) -> None:
        self._settings.setValue("hidden_modules", ",".join(sorted(value)))
        self._settings.sync()

    # ---- agenda (settings of this PC; calendars and layers sync in agenda/calendars.json) ----

    @property
    def agenda_week_start(self) -> int:
        """First day of the week in the agenda: 0 = Monday, 6 = Sunday."""
        return 6 if str(self._settings.value("agenda_week_start", "0")) == "6" else 0

    @agenda_week_start.setter
    def agenda_week_start(self, value: int) -> None:
        self._settings.setValue("agenda_week_start", str(value))
        self._settings.sync()

    @property
    def agenda_event_zone(self) -> bool:
        """"Afficher les heures dans le fuseau de l'événement" instead of the device's."""
        return str(self._settings.value("agenda_event_zone", "false")).lower() == "true"

    @agenda_event_zone.setter
    def agenda_event_zone(self, value: bool) -> None:
        self._settings.setValue("agenda_event_zone", "true" if value else "false")
        self._settings.sync()

    @property
    def agenda_default_alerts(self) -> list[int]:
        """Reminders a new event starts with, in minutes relative to its start (negative = before)."""
        out = []
        for part in str(self._settings.value("agenda_default_alerts", "")).split(","):
            if part.strip().lstrip("-").isdigit():
                out.append(int(part))
        return out

    @agenda_default_alerts.setter
    def agenda_default_alerts(self, value: list[int]) -> None:
        self._settings.setValue("agenda_default_alerts", ",".join(str(v) for v in value))
        self._settings.sync()

    @property
    def agenda_view(self) -> str:
        value = str(self._settings.value("agenda_view", "agenda"))
        return value if value in ("agenda", "month", "week", "day") else "agenda"

    @agenda_view.setter
    def agenda_view(self, value: str) -> None:
        self._settings.setValue("agenda_view", value)
        self._settings.sync()
