package app.memento.agenda

import android.content.Context
import java.time.DayOfWeek

enum class AgendaView(val label: String) { AGENDA("Agenda"), MONTH("Mois"), WEEK("Semaine"), DAY("Jour") }

/** Agenda settings of this phone (not synced): display choices and what was already asked. */
class AgendaPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agenda", Context.MODE_PRIVATE)

    var weekStart: DayOfWeek
        get() = runCatching { DayOfWeek.valueOf(prefs.getString("week_start", "MONDAY")!!) }.getOrDefault(DayOfWeek.MONDAY)
        set(v) = prefs.edit().putString("week_start", v.name).apply()

    /** "Afficher les heures dans le fuseau de l'événement". */
    var eventZoneTimes: Boolean
        get() = prefs.getBoolean("event_zone_times", false)
        set(v) = prefs.edit().putBoolean("event_zone_times", v).apply()

    var lastView: AgendaView
        get() = runCatching { AgendaView.valueOf(prefs.getString("view", "AGENDA")!!) }.getOrDefault(AgendaView.AGENDA)
        set(v) = prefs.edit().putString("view", v.name).apply()

    /** The exact-alarm explanation was shown once. */
    var exactAlarmAsked: Boolean
        get() = prefs.getBoolean("exact_alarm_asked", false)
        set(v) = prefs.edit().putBoolean("exact_alarm_asked", v).apply()

    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notifications_asked", false)
        set(v) = prefs.edit().putBoolean("notifications_asked", v).apply()

    var exportWithLayers: Boolean
        get() = prefs.getBoolean("export_layers", false)
        set(v) = prefs.edit().putBoolean("export_layers", v).apply()
}
