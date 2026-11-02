"""The editor's recurrence model (core/agenda/rules.py) and the French wording (frdates.py), without Qt."""
from datetime import date, datetime

import pytest

from scriptorium_win.core.agenda import rules
from scriptorium_win.core.agenda.frdates import alert_label, day_span, duration, long_date, week_title

ZONE = "Europe/Paris"
STARTS = [datetime(2026, 11, 12, 14, 30), datetime(2026, 11, 30, 9, 0), datetime(2027, 1, 1, 8, 0),
          date(2026, 11, 26), date(2027, 2, 28)]


@pytest.mark.parametrize("start", STARTS)
def test_every_preset_comes_back_as_itself(start):
    for key, label in rules.presets(start):
        rule = rules.preset_rule(key, start, ZONE if isinstance(start, datetime) else None, ZONE)
        assert rules.preset_of(rule, start, ZONE, ZONE) == key, (key, rule)
        assert label


def test_preset_labels_follow_the_start():
    labels = dict(rules.presets(datetime(2026, 11, 26, 9, 0)))
    assert labels["weekly"] == "Toutes les semaines le jeudi"
    assert labels["monthly_nth"] == "Tous les mois le 4e jeudi"
    assert labels["monthly_last"] == "Tous les mois le dernier jeudi"
    assert labels["yearly"] == "Tous les ans le 26 novembre"
    assert "monthly_last" not in dict(rules.presets(date(2026, 11, 12)))
    assert dict(rules.presets(date(2026, 11, 1)))["monthly_day"] == "Tous les mois le 1er"


@pytest.mark.parametrize("rule,expected", [
    ("FREQ=WEEKLY", "weekly"),                       # BYDAY implied by the start
    ("FREQ=MONTHLY", "monthly_day"),
    ("FREQ=MONTHLY;BYDAY=TH;BYSETPOS=2", "monthly_nth"),
    ("RRULE:FREQ=DAILY;INTERVAL=1", "daily"),
    ("FREQ=WEEKLY;BYDAY=TH;WKST=SU", "weekly"),      # WKST only matters every other week
    ("FREQ=WEEKLY;BYDAY=TH;COUNT=4", "custom"),
    ("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH;WKST=SU", "imported"),
    ("FREQ=WEEKLY;BYDAY=1TH", "imported"),
    ("FREQ=MONTHLY;BYMONTHDAY=1,15", "imported"),
    ("FREQ=YEARLY;BYMONTH=3", "imported"),
    ("FREQ=HOURLY", "imported"),
    ("FREQ=DAILY;COUNT=2;UNTIL=20270101", "imported"),
])
def test_rule_kinds(rule, expected):
    assert rules.preset_of(rule, datetime(2026, 11, 12, 14, 30), ZONE, ZONE) == expected


def test_until_is_written_in_the_form_of_the_start():
    spec = rules.RuleSpec(freq="DAILY", until=date(2026, 12, 31))
    assert rules.to_rrule(spec, date(2026, 11, 2), None, ZONE) == "FREQ=DAILY;UNTIL=20261231"
    assert rules.to_rrule(spec, datetime(2026, 11, 2, 9), ZONE, ZONE) == "FREQ=DAILY;UNTIL=20261231T225959Z"
    assert rules.to_rrule(spec, datetime(2026, 11, 2, 9), None, ZONE) == "FREQ=DAILY;UNTIL=20261231T235959"
    back = rules.parse_spec("FREQ=DAILY;UNTIL=20261231T225959Z", datetime(2026, 11, 2, 9), ZONE, ZONE)
    assert back.until == date(2026, 12, 31)


def test_describe_and_next_occurrences():
    start = datetime(2026, 11, 12, 14, 30)
    spec = rules.parse_spec("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,TH;COUNT=10", start, ZONE, ZONE)
    assert rules.describe(spec, start) == "Toutes les 2 semaines le lundi, le mercredi et le jeudi, 10 fois"
    assert rules.describe_rule("FREQ=HOURLY", start, ZONE, ZONE) == "Règle importée : FREQ=HOURLY"
    nxt = rules.next_starts("FREQ=WEEKLY;BYDAY=TH", start, ZONE, ZONE, date(2026, 11, 13),
                            exdate=[datetime(2026, 11, 19, 14, 30)])
    assert nxt == [datetime(2026, 11, 26, 14, 30), datetime(2026, 12, 3, 14, 30), datetime(2026, 12, 10, 14, 30)]
    assert rules.next_starts("FREQ=DAILY;COUNT=2", start, ZONE, ZONE, date(2027, 1, 1)) == []
    with pytest.raises(ValueError):
        rules.next_starts("FREQ=NEVER", start, ZONE, ZONE, date(2026, 11, 1))


def test_french_wording():
    assert long_date(date(2026, 11, 1)) == "dimanche 1er novembre 2026"
    assert week_title(date(2026, 12, 28), date(2027, 1, 3)) == "28 déc. 2026 – 3 janv. 2027"
    assert week_title(date(2026, 11, 30), date(2026, 12, 6)) == "30 nov. – 6 déc. 2026"
    assert day_span(date(2026, 12, 1), date(2026, 12, 3), date(2026, 11, 2)) == "Du 1 au 3 déc."
    assert [alert_label(m) for m in (0, -15, -90, -1440, -10080, 30)] == \
        ["Au début", "15 min avant", "1 h 30 avant", "1 jour avant", "1 semaine avant", "30 min après"]
    assert duration(2880) == "2 jours"
