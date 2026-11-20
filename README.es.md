<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-30363d?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-30363d?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-0891b2?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-30363d?style=for-the-badge)](README.fr.md)

<p align="center"><a href="https://developer.oryksa.com/es/sdks"><img src="https://developer.oryksa.com/assets/img/sdk/oryksa-sdks-es.gif" width="760" alt="SDKs de ORYKSA: el chat y la voz de la IA en iOS, Android, Flutter, React Native y la web"></a></p>

SDK oficial de Android de **ORYKSA AI Employees**. Pon dentro de tu app Android un empleado de IA que ya conoce tu negocio: un chat listo en Jetpack Compose (y una actividad lista para apps con Views) con el mismo aspecto que el chat de la web de ORYKSA, el nombre y la foto de tu IA desde tu cuenta ORYKSA, en inglés, portugués y español.

* Documentación: https://developer.oryksa.com/es/sdks
* API: `https://api.oryksa.com/v1`
* Android 6.0 o superior (minSdk 23).

## Capturas de pantalla

<p align="center"><img src="docs/screenshots/android_product_en.png" width="190" alt="Product page with the chat button"> <img src="docs/screenshots/android_chat_en.png" width="190" alt="Chat"> <img src="docs/screenshots/android_theme_en.png" width="190" alt="Chat with the README theme"> <img src="docs/screenshots/android_voice_en.png" width="190" alt="Voice"></p>

Pantallas reales de la app de ejemplo de este repositorio, capturadas por el CI en un emulador con una cuenta de prueba de ORYKSA. El chat verde usa el código de *Colores y tema*, más abajo.

## Instalar

Maven Central (no hace falta ningún repositorio adicional):

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.1.0")
}
```

Alternativa, JitPack (el mismo código, directamente desde GitHub):

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.1.0")
```

## Para qué sirve

El SDK pone el chat y la voz de ORYKSA dentro de tu app para atender a los **clientes de tu negocio**: preguntan, compran y reservan. La IA responde con tu Brain, tus productos y tus servicios, con el nombre, la foto y la voz que elegiste en *Tu IA*, y guarda leads, reservas y ventas en las Actividades de tu cuenta ORYKSA. **No** es una interfaz para el dueño: a través del SDK nadie cambia ajustes, lee las Actividades ni da órdenes a ORYKSA. Más información: https://help.oryksa.com/es/a/sdks-what-they-do

Las palabrotas que escribe el cliente aparecen con asteriscos en el chat (la misma lista de todos los chats de ORYKSA, cargada desde `https://app.oryksa.com/widget/profanity.json` y guardada 24 horas). Las respuestas de la IA ya vienen filtradas desde el servidor.

## Voz y contexto de la app (1.1)

El chat muestra un micrófono cuando tu plan incluye voz. Abre la pantalla de voz con el mismo comportamiento que la app de ORYKSA: el micrófono sigue abierto mientras ella habla y solo una voz humana la interrumpe (el tecleo, la televisión y su propio eco no); se detiene en la palabra y el texto queda en el chat; a un susurro responde con un susurro; si su voz falla, se muestra el texto (nunca una voz robótica).

Permiso de micrófono: el manifiesto del SDK declara `RECORD_AUDIO`; la pantalla de voz lo pide cuando el cliente la abre.

```kotlin
OryksaChat(client, lang = "pt",
    appContext = { OryksaAppContext(screen = "product", title = "Sky Beginner Snowboard, 489.95") })
```

## Cómo funciona (y por qué la clave está segura)

1. Tu **servidor** guarda la clave secreta de la API (`oryk_live_...`, creada en developer.oryksa.com) y crea un **token de sesión** de corta duración para cada usuario que ha iniciado sesión, con `POST /v1/sessions`.
2. Tu **app** recibe solo ese token de sesión (`oryk_cs_...`). La clave secreta nunca entra en la app.
3. Cada respuesta de la IA cuenta como una interacción de tu plan ORYKSA, igual que en WhatsApp.

## Jetpack Compose

```kotlin
val client = OryksaClient(getToken = { myBackend.fetchOryksaToken() }) // devuelve "oryk_cs_..."
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

O coloca el panel en cualquier lugar con `OryksaChat(client = client)`. Colores: `OryksaChatTheme(accent = Color(0xFF5B57E0))`.

## Apps con Views (Java o Kotlin)

```kotlin
OryksaChatActivity.client = OryksaClient(getToken = { myBackend.fetchOryksaToken() })
button.setOnClickListener { OryksaChatActivity.open(this, "en") }
```

Sin la interfaz (corrutinas): `client.agent()`, `client.sendAndWait("Are you open on Saturday?")`, `client.messages()`.

## Mantén la IA sincronizada con tu app

En tu servidor (por ejemplo con `@oryksa/sdk` en Node): `oryksa.learnApp({ name, description, screens, faq })`.

## Errores

Las llamadas que fallan lanzan `OryksaException` con `status`, `code` (por ejemplo `interaction_limit_reached`, `rate_limited`, `plan_required`) y `message`.

## Colores y tema

`OryksaChatTheme` es una data class con cinco colores: `accent` (botones, burbujas del visitante, enlaces), `ink` (texto principal), `soft` (burbujas de la IA), `background` (panel) y `muted` (texto secundario). Cada uno es un `Color` de Compose y tiene un valor por defecto, así que solo pasas los que quieres cambiar. Pasa el tema a `OryksaChat` y a `OryksaChatButton`.

```kotlin
val theme = OryksaChatTheme(accent = Color(0xFF0E8A6B))

OryksaChat(client = client, theme = theme)
OryksaChatButton(client = client, onClick = { /* open the chat */ }, theme = theme)
```

No hay tema oscuro automático: para el modo oscuro, pasa tus propios colores oscuros. `OryksaChatActivity` (apps con Views) usa los colores por defecto.

---

## Sobre el autor

**Weslley Harakawa** - Fundador de ORYKSA AI. En Lisboa, Portugal. Especialidades: inteligencia artificial, desarrollo web y móvil, tokenización en blockchain. Formación: University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSA)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
