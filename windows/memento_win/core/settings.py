"""Settings of the Activités folder, kept in activities/settings.json so they sync with the phone."""
from __future__ import annotations

import json
from dataclasses import dataclass, field

from .track import Sport

PATH = "activities/settings.json"


@dataclass
class ActivitySettings:
    refine_altitude: bool = False  # on the phone, when saving a recording: correct the barometer with the terrain model
    totals: set[Sport] = field(default_factory=lambda: set(Sport))  # sports counted in the totals

    def to_json(self) -> str:
        return json.dumps({"refine_altitude": self.refine_altitude,
                           "totals": [s.key for s in Sport if s in self.totals]}) + "\n"

    @staticmethod
    def parse(text: str) -> "ActivitySettings":
        try:
            data = json.loads(text)
        except ValueError:
            return ActivitySettings()
        if not isinstance(data, dict):
            return ActivitySettings()
        totals = data.get("totals")
        sports = set(Sport) if not isinstance(totals, list) else {s for s in Sport if s.key in totals}
        return ActivitySettings(data.get("refine_altitude") is True, sports)
