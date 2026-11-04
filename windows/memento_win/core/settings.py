"""Settings of the Activités folder, kept in activities/settings.json so they sync with the phone."""
from __future__ import annotations

import json
from dataclasses import dataclass, field

from .track import Sport

PATH = "activities/settings.json"
# AWS Terrarium (256 px PNG to z13, about 30 m data in France) or Mapterhorn (512 px WebP: IGN RGE ALTI 5 m, LiDAR HD
# 0.5-1 m where flown), with the label the settings show
RELIEFS = {"aws": "AWS (standard)", "mapterhorn": "Mapterhorn (précis)"}


@dataclass
class ActivitySettings:
    refine_altitude: bool = False  # on the phone, when saving a recording: correct the barometer with the terrain model
    totals: set[Sport] = field(default_factory=lambda: set(Sport))  # sports counted in the totals
    relief: str = "aws"  # the map's relief on every platform: one of RELIEFS
    buildings: bool = True  # the buildings drawn in 3D when online (offline packs never hold them)

    def to_json(self) -> str:
        return json.dumps({"refine_altitude": self.refine_altitude, "totals": [s.key for s in Sport if s in self.totals],
                           "relief": self.relief, "buildings": self.buildings}) + "\n"

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
        relief = data.get("relief")
        return ActivitySettings(data.get("refine_altitude") is True, sports, relief if relief in RELIEFS else "aws",
                                data.get("buildings") is not False)
