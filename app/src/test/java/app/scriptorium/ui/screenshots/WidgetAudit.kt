package app.scriptorium.ui.screenshots

import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import app.scriptorium.R
import app.scriptorium.activity.Sport
import app.scriptorium.agenda.AgendaData
import app.scriptorium.agenda.AgendaIndex
import app.scriptorium.agenda.WidgetOptions
import app.scriptorium.agenda.WidgetRows
import app.scriptorium.agenda.Zones
import app.scriptorium.ui.sportEmoji
import app.scriptorium.widget.AgendaWidgets
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The two home-screen widgets (RemoteViews applied to real views), light and dark, on a grey "wallpaper". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "fr-rFR-w412dp-h915dp-xhdpi")
class WidgetAudit {
    private val out = File(System.getProperty("uiAudit") ?: "build/ui-audit")
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        out.mkdirs()
        shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        AuditData.seed()
    }

    private fun render(view: View, wDp: Int, hDp: Int, wallpaper: Int, name: String) {
        val d = app.resources.displayMetrics.density
        val frame = FrameLayout(app)
        frame.setBackgroundColor(wallpaper)
        val pad = (16 * d).toInt()
        frame.setPadding(pad, pad, pad, pad)
        frame.addView(view, FrameLayout.LayoutParams((wDp * d).toInt(), (hDp * d).toInt()))
        val w = ((wDp + 32) * d).toInt()
        val h = ((hDp + 32) * d).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun widgets() {
        for (dark in listOf(true, false)) {
            RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
            val ctx = app
            val suffix = if (dark) "_dark" else "_light"
            val wallpaper = if (dark) 0xFF202830.toInt() else 0xFF8FA3B8.toInt()
            val manager = AppWidgetManager.getInstance(ctx)

            // agenda widget: its frame, with the rows the list adapter would show put in place of the list
            val frame = AgendaWidgets.agendaViews(ctx, 1, manager).apply(ctx, FrameLayout(ctx))
            val list = frame.findViewById<View>(R.id.list)
            val parent = list.parent as ViewGroup
            val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val sources = AgendaData.load(ctx)!!
            val entries = AgendaIndex.entries(sources, Zones.dayStart(today, zone), Zones.dayStart(today.plusDays(14), zone), zone, respectVisibility = false).entries
            WidgetRows.rows(entries, Instant.now(), zone, WidgetOptions(), true) { sportEmoji(Sport.of(it)) }.take(9).forEach { r ->
                rows.addView(AgendaWidgets.rowViews(ctx, r).apply(ctx, rows))
            }
            val index = parent.indexOfChild(list)
            parent.removeView(list)
            frame.findViewById<View>(R.id.empty)?.visibility = View.GONE
            parent.addView(rows, index, list.layoutParams)
            render(frame, 300, 380, wallpaper, "widget_agenda$suffix")

            val todayView = AgendaWidgets.todayViews(ctx, 2).apply(ctx, FrameLayout(ctx))
            render(todayView, 300, 90, wallpaper, "widget_today$suffix")
        }
    }
}
