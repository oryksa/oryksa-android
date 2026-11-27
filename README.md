<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-0891b2?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-30363d?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-30363d?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-30363d?style=for-the-badge)](README.fr.md)

<p align="center"><a href="https://developer.oryksa.com/en/sdks"><img src="https://developer.oryksa.com/assets/img/sdk/oryksa-sdks-en.gif" width="760" alt="ORYKSA SDKs: the AI chat and voice on iOS, Android, Flutter, React Native and the web"></a></p>

Official Android SDK for **ORYKSA AI Employees**. Put an AI employee that already knows your business inside your Android app: a ready Jetpack Compose chat (and a ready activity for apps with Views) with the same look as the ORYKSA website chat, the name and photo of your AI from your ORYKSA account, in English, Portuguese and Spanish.

* Docs: https://developer.oryksa.com/en/sdks
* API: `https://api.oryksa.com/v1`
* Android 6.0+ (minSdk 23).

## Screenshots

<p align="center"><img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_product_en.png" width="190" alt="Product page with the chat button"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_chat_en.png" width="190" alt="Chat"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_theme_en.png" width="190" alt="Chat with the README theme"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_voice_en.png" width="190" alt="Voice"></p>

Real screens of the example app in this repository, taken by CI on an emulator with an ORYKSA test account. The green chat uses the code of *Colours and theme* below.

## Install

Maven Central (no extra repository needed):

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.2.0")
}
```

Alternative, JitPack (same code, straight from GitHub):

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.2.0")
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

## Links with an Open button (1.1.3)

The links the AI gives can be tapped in the chat (web addresses and e-mails), and each web link gets a card under her reply with the picture and title of the page and an **Open** button (the pages of your own site, the domains of your website chat; other sites show the address). The voice screen shows what she says without the addresses, with the same cards; tapping Open closes the voice screen. Pass `onLink` to open your own screens, for example your product page: return true when your app opened it, false to let the browser open it.

```kotlin
OryksaChat(client, lang = "en", onLink = { uri ->
    val id = myProductIdOf(uri) ?: return@OryksaChat false // your routes
    navController.navigate("product/$id")
    true
})

// Apps with Views: the full screen chat
OryksaChatActivity.onLink = { uri -> openMyProduct(uri) }
```

`onLink` is also on both `OryksaVoiceScreen` functions.

## Attach a photo or a document (1.1.3)

The chat has an attach button (a paperclip, on by default): a photo from the photo picker, a photo from the camera, or a document (PDF, Word, text, CSV, up to 8 MB). The AI looks at the photo (made smaller to 1600 pixels, JPEG, up to 2 MB) and reads the document; the customer's bubble shows the photo, or the document icon and its name. `attachments = false` hides the button (`OryksaChatActivity.attachments = false` for the full screen chat).

Permissions:

* Photo library: none. The SDK uses the Android photo picker (`PickVisualMedia`).
* Camera: none in most apps. The SDK opens the camera app (`TakePicture`), which needs no `CAMERA` permission, and the SDK does not declare it. If your app declares `android.permission.CAMERA` in its own manifest, Android requires it to be granted, so the SDK asks for it before it opens the camera. The SDK manifest adds its own FileProvider (`${applicationId}.oryksa.fileprovider`) for the camera photo.
* Documents: none. They open in the system document picker (`OpenDocument`).

```kotlin
OryksaChat(client, lang = "en", attachments = false) // no attach button
// Without the UI:
client.send("Here is the quote", file = OryksaFile(pdfBytes, "quote.pdf", "application/pdf"))
```

## Your own AI features: `ai` (1.1.4)

Have the AI do what your app needs (read a photo, extract data, classify, translate, write) and pay with ORYKSA interactions: 1 per answer. Your prompt and your JSON Schema live in your app; the session token is enough (never ship the secret key). It does not create a conversation in your inbox.

```kotlin
val r = client.ai(
    instructions = "Read the tyre on the photo: brand, model and size exactly as written on the sidewall. " +
        "If the size is not legible, readable=false and size=null. Never invent.",
    images = listOf(OryksaImage(jpegBytes)), // resized to 1600 px
    schema = JSONObject("""{"type":"object","properties":{"readable":{"type":"boolean"},"brand":{"type":["string","null"]},"size":{"type":["string","null"]}},"required":["readable","brand","size"]}"""),
)
if (r.json?.optBoolean("readable") == true) showProducts(r.json!!.getString("size"))
```

`r.text` has the answer as text, `r.json` the object (with a schema), `r.interactionsSpent` the interactions used. When the plan's interactions are used up the call throws `OryksaException` with status 402.

## TV apps: `com.oryksa.sdk.tv` (Android TV, Google TV, Fire TV)

What the AI does for any TV app, driven only by the remote (arrows, OK, Back), with big letters and a focus you can see from the sofa. Native Jetpack Compose, no web view. The texts, voices, rules and buttons come from your ORYKSA account.

* **Presenter**: the AI speaks on screen with a native voice in the viewer's language and big subtitles. Lines she already said play again from the device, with no new voice made, and are charged again each time she says them.
* **Assistant panel**: a conversation with the AI for the remote.
* **Phone voice**: many TVs give no microphone to apps (the Fire TV Stick does not). The TV shows a QR code, the phone opens the ORYKSA page, and what the person says arrives in your app as text.
* **`client.ai`** for your own tasks, for example moderating or classifying.

```kotlin
// The device says if it is a Fire TV or an Android TV (sent as `platform`).
val platform = OryksaTvPlatform.detect(context)?.wire ?: "android"
val client = OryksaClient(getToken = { tokenFromYourServer() }, platform = platform)
val tv = OryksaTv.create(context, client, locale = "pt-BR")

setContent {
    // Your screen inside the host: the presenter shows over it, and OK / Back stop her while she speaks.
    OryksaPresenterHost(tv) { YourScreen() }
    if (showAssistant) OryksaTvAssistantDialog(tv, onDismiss = { showAssistant = false })
}

scope.launch { tv.presenter.say("Welcome! I am your host tonight.") }
scope.launch { tv.presenter.sayGenerated("Announce the next round in one short sentence.", input = "Round 2") }
// Subtitles only, no sound (charged at the text price; a spoken line is charged at the voice price).
scope.launch { tv.presenter.say("The theme tonight: travel.", voice = false) }

// The phone as the microphone: show OryksaPairingCard(tv) and read what is said.
scope.launch { tv.phoneVoice.texts.collect { text -> onViewerSaid(text) } }
```

* **Without a server of your own**: `OryksaTv.publicClient(context, chatToken, conversationId)` opens a public session with your business's public chat token, and sends `platform` and your package as `app_id`. When your business limits its chat, it must list your package in its allowed apps. `app_id` is a filter, not strong protection (it can be imitated): the strong protection is a token from your own server (`POST /v1/sessions`).
* **Languages**: the ORYKSA server picks the voice and the texts for the locale. There are native voices for pt-BR, pt-PT, es-ES, es-419, fr-FR, de-DE, en-US and en-GB, and any other language uses the US English voice.
* **Interactions**: each presenter line is charged by the ORYKSA interaction cost table every time she says it or shows it (per piece of up to 600 characters), a repeated line and a subtitles-only line too: a spoken line at the voice price, a subtitles-only line at the text price. The exact number comes in the `X-ORYKSA-Interactions-Spent` header, and a line the server did not count is not shown. Each text heard on the phone, each chat reply and each `client.ai` answer are charged too.
* **TV microphone**: `OryksaTvMic.isAvailable(context)` tells whether the device lists an audio input. The SDK never asks for the permission on a TV without one.
* **Dialogs**: Compose does not give the focus back to the button that opened a dialog, and with a remote the focus is the only way to know where you are. Pass `returnFocusTo` (the `FocusRequester` of that button) to `OryksaTvAssistantDialog`, and use `OryksaTvDialog(onDismiss, returnFocusTo, tv = tv)` for your own dialogs: with `tv`, OK and Back stop the presenter while she speaks (a dialog gets its keys apart from your screen).
* **AndroidManifest**: the SDK declares the microphone as not required (its `RECORD_AUDIO` alone would make the stores require one and hide your app on TVs without it). Declare the touchscreen and leanback as not required too, and add the TV launcher category with a banner. See `example-tv/`.

```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.software.leanback" android:required="false" />
<!-- in the activity's intent-filter, and android:banner on the application -->
<category android:name="android.intent.category.LEANBACK_LAUNCHER" />
```

For tests and demos, `OryksaTv.create(context, OryksaTvFakeBackend())` runs everything without the server. It is simulated, so it proves nothing about your ORYKSA account.

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

## Colours and theme

`OryksaChatTheme` is a data class with five colors: `accent` (buttons, visitor bubbles, links), `ink` (main text), `soft` (AI bubbles), `background` (panel) and `muted` (secondary text). Every one is a Compose `Color` and has a default, so you only pass the ones you want to change. Pass the theme to `OryksaChat` and `OryksaChatButton`.

```kotlin
val theme = OryksaChatTheme(accent = Color(0xFF0E8A6B))

OryksaChat(client = client, theme = theme)
OryksaChatButton(client = client, onClick = { /* open the chat */ }, theme = theme)
```

There is no automatic dark theme: for dark mode, pass your own dark colors. `OryksaChatActivity` (apps with Views) uses the default colors.

---

## About the author

**Weslley Harakawa** - Founder of ORYKSA AI. Based in Lisbon, Portugal. Specialties: artificial intelligence, web and mobile development, blockchain tokenization. Education: University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSA)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
