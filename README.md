<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

Official Android SDK for **ORYKSA AI Employees**. Put an AI employee that already knows your business inside your Android app: a ready Jetpack Compose chat (and a ready activity for apps with Views) with the same look as the ORYKSA website chat, the name and photo of your AI from your ORYKSA account, in English, Portuguese (Portugal and Brazil) and Spanish.

* Docs: https://developer.oryksa.com/en/sdks
* API: `https://api.oryksa.com/v1`
* Android 6.0+ (minSdk 23).

## Install

Maven Central (no extra repository needed):

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.1.0")
}
```

Alternative, JitPack (same code, straight from GitHub):

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.1.0")
```

## What it is for

The SDK puts the ORYKSA chat and voice inside your app to serve the **customers of your business**: they ask, buy and book. The AI answers with your Brain, products and services, with the name, photo and voice you chose in *Your AI*, and saves leads, bookings and sales in the Activities of your ORYKSA account. It is **not** an interface for the owner: nobody changes settings, reads Activities or gives orders to ORYKSA through the SDK. More: https://help.oryksa.com/en/a/sdks-what-they-do

Swear words the customer types show as asterisks in the chat (the same list as every ORYKSA chat, loaded from `https://app.oryksa.com/widget/profanity.json`, kept 24 hours). The AI's replies already come filtered from the server.

## Voice and app context (1.1)

The chat shows a microphone when your plan has voice. It opens the voice screen with the same behaviour as the ORYKSA app: the microphone stays open while she speaks and only a human voice cuts her off (typing, TV and her own echo do not); she stops on the word and the text stays in the chat; a whisper gets a whispered answer; if her voice fails, the text is shown (never a robot voice).

Microphone permission: the SDK manifest declares `RECORD_AUDIO`; the voice screen asks for it when the customer opens it.

```kotlin
OryksaChat(client, lang = "pt",
    appContext = { OryksaAppContext(screen = "product", title = "Sky Beginner Snowboard, 489.95") })
```

## How it works (and why the key stays safe)

1. Your **server** keeps the secret API key (`oryk_live_...`, created at developer.oryksa.com) and creates a short-lived **session token** for each signed-in user with `POST /v1/sessions`.
2. Your **app** receives only that session token (`oryk_cs_...`). The secret key never goes into the app.
3. Every AI reply counts as one interaction of your ORYKSA plan, the same as on WhatsApp.

## Jetpack Compose

```kotlin
val client = OryksaClient(getToken = { myBackend.fetchOryksaToken() }) // returns "oryk_cs_..."
OryksaChatActivity.client = client

Box(Modifier.fillMaxSize()) {
    MyScreens()
    OryksaChatButton(
        client = client,
        onClick = { OryksaChatActivity.open(context, lang = "en") }, // en, pt, br, es
        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
    )
}
```

Or put the panel anywhere with `OryksaChat(client = client)`. Colors: `OryksaChatTheme(accent = Color(0xFF5B57E0))`.

## Apps with Views (Java or Kotlin)

```kotlin
OryksaChatActivity.client = OryksaClient(getToken = { myBackend.fetchOryksaToken() })
button.setOnClickListener { OryksaChatActivity.open(this, "en") }
```

Without the UI (coroutines): `client.agent()`, `client.sendAndWait("Are you open on Saturday?")`, `client.messages()`.

## Keep the AI in sync with your app

On your server (for example with `@oryksa/sdk` on Node): `oryksa.learnApp({ name, description, screens, faq })`.

## Errors

Failed calls throw `OryksaException` with `status`, `code` (for example `interaction_limit_reached`, `rate_limited`, `plan_required`) and `message`.

---

## Português (Portugal)

SDK oficial Android da **ORYKSA AI Employees**: põe na tua app um colaborador de IA que já conhece o teu negócio, com um chat pronto (Jetpack Compose ou atividade pronta) igual ao chat do site da ORYKSA, com o nome e a foto da tua IA. O teu servidor cria o token de sessão (`POST /v1/sessions`) e a app usa `OryksaClient(getToken = ...)` com `OryksaChatActivity.open(context, "pt")`. Cada resposta da IA conta como uma interação do teu plano.

## Português (Brasil)

SDK oficial Android da **ORYKSA AI Employees**: coloque no seu app um colaborador de IA que já conhece o seu negócio, com um chat pronto (Jetpack Compose ou atividade pronta) igual ao chat do site da ORYKSA, com o nome e a foto da sua IA. Seu servidor cria o token de sessão (`POST /v1/sessions`) e o app usa `OryksaClient(getToken = ...)` com `OryksaChatActivity.open(context, "br")`. Cada resposta da IA conta como uma interação do seu plano.

## Español

SDK oficial de Android de **ORYKSA AI Employees**: pon en tu app un empleado de IA que ya conoce tu negocio, con un chat listo (Jetpack Compose o actividad lista) igual al chat de la web de ORYKSA, con el nombre y la foto de tu IA. Tu servidor crea el token de sesión (`POST /v1/sessions`) y la app usa `OryksaClient(getToken = ...)` con `OryksaChatActivity.open(context, "es")`. Cada respuesta de la IA cuenta como una interacción de tu plan.

## About the author

**Weslley Harakawa** - Founder of ORYKSA AI. Based in Lisbon, Portugal. Specialties: artificial intelligence, web and mobile development, blockchain tokenization. Education: University of the People.

- Website: https://harakawa.tech
- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- Instagram: https://www.instagram.com/weslley.harakawa
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSAAI)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
