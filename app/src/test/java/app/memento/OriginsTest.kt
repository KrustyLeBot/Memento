package app.memento

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginsTest {
    /** The view model's navigation, as MainViewModel.screen's setter and goBack() drive [Origins]. */
    private class Nav(var screen: Screen) {
        val origins = Origins()
        var now = 1_000_000L

        fun go(to: Screen) {
            origins.moved(screen, to, now)
            screen = to
        }

        fun back(settingsFallback: Screen = Screen.LIST) {
            val to = origins.pop(screen, screen.home ?: settingsFallback)
            screen = to
        }
    }

    @Test
    fun aDetailGoesBackToTheModuleItWasOpenedFrom() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.ACTIVITY)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        nav.go(Screen.EDITOR)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        nav.go(Screen.ACTIVITIES)
        nav.go(Screen.ACTIVITY)
        nav.back()
        assertEquals(Screen.ACTIVITIES, nav.screen)

        nav.go(Screen.LIST)
        nav.go(Screen.EDITOR)
        nav.back()
        assertEquals(Screen.LIST, nav.screen)

        nav.go(Screen.CONTACTS)
        nav.go(Screen.CONTACT)
        nav.back()
        assertEquals(Screen.CONTACTS, nav.screen)
    }

    @Test
    fun birthdayContactEditedFromTheAgendaStillGoesBackToTheAgenda() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.CONTACT)
        nav.go(Screen.CONTACT_EDIT)
        nav.go(Screen.CONTACT) // saved: the editor leaves into the detail
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)
    }

    @Test
    fun eventEditorAndDescriptionReturnThroughTheEvent() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.EVENT)
        nav.go(Screen.EVENT_EDIT)
        nav.go(Screen.EVENT) // saved
        nav.go(Screen.EDITOR) // its description
        nav.back()
        assertEquals(Screen.EVENT, nav.screen)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)
    }

    @Test
    fun aNewEventSavedShowsItsDetailAndBackSkipsTheEditor() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.EVENT_EDIT)
        nav.go(Screen.EVENT)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        // saved with "description": the detail, then the note editor on it
        nav.go(Screen.EVENT_EDIT)
        nav.go(Screen.EVENT)
        nav.go(Screen.EDITOR)
        nav.back()
        assertEquals(Screen.EVENT, nav.screen)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)
    }

    @Test
    fun cancellingAnEditorReturnsWhereItWasOpened() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.EVENT)
        nav.go(Screen.EVENT_EDIT)
        nav.back()
        assertEquals(Screen.EVENT, nav.screen)

        nav.go(Screen.EVENT_EDIT)
        nav.back()
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)
    }

    @Test
    fun drawingReturnsToItsNoteThenTheNoteToItsList() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.EDITOR)
        nav.go(Screen.DRAWING)
        nav.go(Screen.EDITOR) // closeDrawing
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        nav.go(Screen.EDITOR)
        nav.go(Screen.PDF)
        nav.back()
        assertEquals(Screen.EDITOR, nav.screen)
    }

    @Test
    fun aModuleRootStartsANewChain() {
        val nav = Nav(Screen.AGENDA)
        nav.go(Screen.EVENT)
        nav.go(Screen.CONTACTS) // the bottom bar
        nav.go(Screen.CONTACT)
        nav.back()
        assertEquals(Screen.CONTACTS, nav.screen)
        assertEquals(emptyList<Screen>(), nav.origins.chain)
    }

    @Test
    fun aDeepLinkGoesBackToThePreviousScreenWhenTheAppWasOpen() {
        val nav = Nav(Screen.CONTACTS)
        nav.go(Screen.EVENT) // a reminder tapped while the contacts were shown
        nav.back()
        assertEquals(Screen.CONTACTS, nav.screen)

        // from an open detail: back returns to it, then to its own origin
        nav.go(Screen.CONTACT)
        nav.go(Screen.ACTIVITY)
        nav.back()
        assertEquals(Screen.CONTACT, nav.screen)
        nav.back()
        assertEquals(Screen.CONTACTS, nav.screen)
    }

    @Test
    fun aDeepLinkAtColdStartGoesBackToTheModuleRootOfWhatItOpened() {
        val nav = Nav(Screen.LIST)
        nav.origins.forgetNextOrigin(nav.now)
        nav.go(Screen.EVENT)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        val record = Nav(Screen.CONTACTS)
        record.origins.forgetNextOrigin(record.now)
        record.go(Screen.RECORD)
        record.back()
        assertEquals(Screen.ACTIVITIES, record.screen)

        val activity = Nav(Screen.AGENDA)
        activity.origins.forgetNextOrigin(activity.now)
        activity.go(Screen.ACTIVITY)
        activity.back()
        assertEquals(Screen.ACTIVITIES, activity.screen)
    }

    @Test
    fun forgettingOnlyConcernsTheNextMoveSoonAfterTheIntent() {
        val nav = Nav(Screen.AGENDA)
        nav.origins.forgetNextOrigin(nav.now)
        nav.now += 60_000 // the entry was gone: nothing opened, the user taps a note later
        nav.go(Screen.EDITOR)
        nav.back()
        assertEquals(Screen.AGENDA, nav.screen)

        nav.origins.forgetNextOrigin(nav.now)
        nav.go(Screen.EVENT)
        nav.go(Screen.EDITOR) // only the first move forgot
        nav.back()
        assertEquals(Screen.EVENT, nav.screen)
    }

    @Test
    fun settingsAndRecordGoBackWhereTheyWereOpened() {
        val nav = Nav(Screen.CONTACTS)
        nav.go(Screen.SETTINGS)
        nav.go(Screen.AGENDA_CALENDARS)
        nav.back()
        assertEquals(Screen.SETTINGS, nav.screen)
        nav.back()
        assertEquals(Screen.CONTACTS, nav.screen)

        nav.go(Screen.LIST)
        nav.go(Screen.RECORD) // the notes' "recording" card
        nav.back()
        assertEquals(Screen.LIST, nav.screen)

        // nothing remembered: the module last used (what Settings was always left for)
        val fresh = Nav(Screen.SETTINGS)
        fresh.back(settingsFallback = Screen.AGENDA)
        assertEquals(Screen.AGENDA, fresh.screen)
    }

    @Test
    fun theChainIsBounded() {
        val o = Origins(max = 3)
        val chain = listOf(Screen.AGENDA, Screen.EVENT, Screen.EDITOR, Screen.PDF, Screen.CONTACT, Screen.ACTIVITY)
        chain.zipWithNext { a, b -> o.moved(a, b, 0) }
        assertEquals(listOf(Screen.EDITOR, Screen.PDF, Screen.CONTACT), o.chain)
        assertEquals(Screen.CONTACT, o.pop(Screen.ACTIVITY, Screen.ACTIVITIES))
    }
}
