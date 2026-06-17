package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert die Reservierungs-Invariante von [SettingsStore.nextMappingId]: die
 * reservierten Mapping-Ids (statische Grok-Identität id 0 + Sprach-Ansprech-Kontakt
 * id 1) dürfen NIE dynamisch vergeben werden, sonst bekäme ein Messenger-Mapping eine
 * reservierte Fake-Adresse. DataStore braucht einen echten Context → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SettingsStore(context)

    @Test
    fun `nextMappingId never returns a reserved id and stays strictly increasing`() = runTest {
        val ids = (1..6).map { store.nextMappingId() }

        // Keine reservierte Id (0 = Grok, 1 = Sprach-Ansprech-Kontakt).
        assertThat(ids.none { it in AssistantIdentity.RESERVED_MAPPING_IDS }).isTrue()
        // Strikt monoton steigend, keine Dopplungen → NOTIFICATION-Vergabe nicht gestört.
        assertThat(ids).isInStrictOrder()
        assertThat(ids.toSet()).hasSize(ids.size)
        // Auf frischem Store beginnt die Vergabe direkt nach den reservierten {0,1}.
        assertThat(ids.first()).isEqualTo(2L)
    }
}
