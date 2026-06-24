package io.github.lycheeappf.tmm.ui.screen.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticsShareTest {

    // Nur die reine Authority-Logik wird unit-getestet. uriFor()/chooser() sind dünne
    // Wrapper über FileProvider/Intent (Framework); ihr Verhalten hängt am gemergten
    // Manifest + applicationId-Suffix und wird zur Laufzeit auf dem Gerät validiert.
    // Unter Robolectric trägt context.packageName das ".debug"-Suffix nicht, sodass
    // getUriForFile dort fälschlich die Authority verfehlt — kein echter Bug.

    @Test fun `authority appends fileprovider suffix`() {
        assertThat(DiagnosticsShare.authority("io.github.lycheeappf.tmm.debug"))
            .isEqualTo("io.github.lycheeappf.tmm.debug.fileprovider")
    }

    @Test fun `authority works for release id without suffix`() {
        assertThat(DiagnosticsShare.authority("io.github.lycheeappf.tmm"))
            .isEqualTo("io.github.lycheeappf.tmm.fileprovider")
    }
}
