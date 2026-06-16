package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * Einheitliches App-Gerüst: TopAppBar (Titel + optionaler Zurück-Pfeil + Actions),
 * optionaler Bottom-Bar-Slot (geteilte NavigationBar bei Haupt-Screens, leer bei
 * Detail-/Dev-Screens) und Snackbar-Slot. Ersetzt das pro-Screen handgebaute
 * Header-`Text` + `Column(padding/scroll)`-Boilerplate.
 *
 * `content` bekommt das `innerPadding` der Scaffold — der Screen entscheidet selbst
 * über Scroll-Column vs. LazyColumn.
 */
@Composable
fun MfsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = actions
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content
    )
}
