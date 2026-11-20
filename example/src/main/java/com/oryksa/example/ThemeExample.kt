package com.oryksa.example

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.oryksa.sdk.OryksaChat
import com.oryksa.sdk.OryksaChatButton
import com.oryksa.sdk.OryksaChatTheme
import com.oryksa.sdk.OryksaClient

/**
 * The "Colours and theme" code of the README, word for word. It is part of the example app, so CI compiles it
 * on every run, and the "theme" screen of the screenshots shows it.
 */
val readmeTheme = OryksaChatTheme(accent = Color(0xFF0E8A6B))

@Composable
fun ReadmeThemeExample(client: OryksaClient, showPanel: Boolean) {
    val theme = OryksaChatTheme(accent = Color(0xFF0E8A6B))

    if (showPanel) {
        OryksaChat(client = client, theme = theme)
    } else {
        OryksaChatButton(client = client, onClick = { /* open the chat */ }, theme = theme)
    }
}
