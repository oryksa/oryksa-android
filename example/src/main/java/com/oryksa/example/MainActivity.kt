package com.oryksa.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oryksa.sdk.OryksaAgent
import com.oryksa.sdk.OryksaAppContext
import com.oryksa.sdk.OryksaChat
import com.oryksa.sdk.OryksaChatButton
import com.oryksa.sdk.OryksaClient
import com.oryksa.sdk.OryksaVoiceScreen

/**
 * ORYKSA chat + voice in an Android app: a product page of your store. The chat knows the customer is
 * looking at this product (appContext). Your server creates the session token (POST /v1/sessions with
 * the secret key) and the app only gets `oryk_cs_...`.
 * Extra "screen" = product | chat | voice opens that screen directly (used by the CI screenshots).
 */
class MainActivity : ComponentActivity() {
    private val client by lazy {
        OryksaClient(getToken = { BuildConfig.ORYKSA_TEST_TOKEN.ifEmpty { error("Get the token from your server") } })
    }
    private val product = "Sky Beginner Snowboard"
    private fun appContext() = OryksaAppContext(screen = "product", title = "$product, 489.95", items = listOf(product))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("screen") ?: "product"
        val lang = intent.getStringExtra("lang") ?: "en"
        setContent {
            var screen by remember { mutableStateOf(start) }
            var agent by remember { mutableStateOf<OryksaAgent?>(null) }
            LaunchedEffect(Unit) { agent = runCatching { client.agent() }.getOrNull() }
            when (screen) {
                "chat" -> OryksaChat(client, lang = lang, onClose = { screen = "product" }, appContext = { appContext() })
                "voice" -> agent?.let { OryksaVoiceScreen(client, it, onClose = { screen = "chat" }, lang = lang, appContext = { appContext() }) }
                else -> Box(Modifier.fillMaxSize().background(Color(0xFFFDFAFF))) {
                    Column(Modifier.padding(20.dp)) {
                        Text("My store", fontSize = 22.sp)
                        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(200.dp).background(Color(0xFFEFF3F8)), contentAlignment = Alignment.Center) {
                            Text("🏂", fontSize = 80.sp)
                        }
                        Text(product, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                        Text("489.95", fontSize = 18.sp)
                    }
                    OryksaChatButton(client, onClick = { screen = "chat" }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp), lang = lang)
                }
            }
        }
    }
}
