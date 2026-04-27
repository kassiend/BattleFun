package org.forfunnypubg.app

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import org.forfunnypubg.app.map.MapScreen
import org.forfunnypubg.app.theme.AppTheme

@Preview
@Composable
fun App(
    onThemeChanged: @Composable (isDark: Boolean) -> Unit = {}
) = AppTheme(onThemeChanged) {
    MapScreen()
}
