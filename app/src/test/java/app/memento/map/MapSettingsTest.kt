package app.memento.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.memento.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The map settings survive a restart; the tile server's secrets sit in their own private preferences file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MapSettingsTest {
    @Test
    fun tuileriesAndPhotoZoomArePersisted() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val s = Settings(app)
        assertFalse(s.tuileries.configured)
        assertEquals(17, s.photoMaxZoom)
        s.tuileries = Tuileries(" https://tuileries.example.org/ ", "randonneur", "mot-de-passe-de-test", "cid", "csec")
        s.photoMaxZoom = 25
        val again = Settings(app)
        assertEquals(Tuileries("https://tuileries.example.org/", "randonneur", "mot-de-passe-de-test", "cid", "csec"), again.tuileries)
        assertEquals(19, again.photoMaxZoom)
        val general = app.getSharedPreferences("settings", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("secrets kept apart from the general settings", general.contains("mot-de-passe-de-test") || general.contains("csec"))
    }
}
