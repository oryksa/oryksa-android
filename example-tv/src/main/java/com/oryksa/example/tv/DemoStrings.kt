package com.oryksa.example.tv

import com.oryksa.sdk.tv.OryksaTvFakeBackend
import com.oryksa.sdk.tv.oryksaTvNormalizeLocale

/** The languages the demo cycles through ("Change the language" changes the whole screen and the voice). */
val DEMO_LOCALES = listOf("pt-BR", "pt-PT", "es-MX", "es-ES", "fr-FR", "de-DE", "en-US", "en-GB")

/** The first language: the device's when it is one of the 8, otherwise English of the USA. */
fun demoInitialLocale(deviceTag: String): String {
    val lang = oryksaTvNormalizeLocale(deviceTag).substringBefore('-')
    if (lang !in setOf("pt", "es", "fr", "de", "en")) return "en-US"
    val table = OryksaTvFakeBackend.resolveLocale(deviceTag)
    return if (table == "es-419") "es-MX" else table
}

/** Every text the demo shows, for [locale] (the same choice as the server: 8 tables, the rest English). */
fun demoStrings(locale: String): Map<String, String> = DEMO_TABLES.getValue(OryksaTvFakeBackend.resolveLocale(locale))

/** Words of one variant that must never show in the other (the owner's rule, 26/11/2026). */
val DEMO_FORBIDDEN_WORDS: Map<String, List<String>> = mapOf(
    "pt-BR" to listOf("telemóvel", "telemovel", "ecrã", "comando", "carrega", "utilizador", "tua", "teu"),
    "pt-PT" to listOf("celular", "tela", "controle", "você", "aperte", "usuário"),
    "es-419" to listOf("móvil", "mando", "pulsa", "vosotros"),
    "es-ES" to listOf("celular", "control remoto", "presiona"),
)

/** The demo's own texts. The SDK pieces take theirs from the server (tv.texts). */val DEMO_TABLES: Map<String, Map<String, String>> = mapOf(
    "pt-BR" to mapOf(
        "title" to "Demonstração da ORYKSA para TV",
        "language" to "Idioma",
        "language_name" to "Português (Brasil)",
        "mic" to "Microfone da TV",
        "mic_yes" to "sim",
        "mic_no" to "não, use o celular pelo QR",
        "phone_last" to "Último texto do celular",
        "presenter" to "Ouvir a apresentadora",
        "assistant" to "Abrir o assistente",
        "phone" to "Falar pelo celular",
        "change_language" to "Mudar o idioma",
        "simulated" to "Demonstração simulada: não usa o servidor da ORYKSA",
        "welcome" to "Olá! Eu sou a ORYKSA, sua apresentadora. Use as setas do controle e aperte OK.",
    ),
    "pt-PT" to mapOf(
        "title" to "Demonstração da ORYKSA para TV",
        "language" to "Língua",
        "language_name" to "Português (Portugal)",
        "mic" to "Microfone da TV",
        "mic_yes" to "sim",
        "mic_no" to "não, usa o telemóvel pelo QR",
        "phone_last" to "Último texto do telemóvel",
        "presenter" to "Ouvir a apresentadora",
        "assistant" to "Abrir o assistente",
        "phone" to "Falar pelo telemóvel",
        "change_language" to "Mudar a língua",
        "simulated" to "Demonstração simulada: não usa o servidor da ORYKSA",
        "welcome" to "Olá! Sou a ORYKSA, a tua apresentadora. Usa as setas do comando e carrega em OK.",
    ),
    "es-419" to mapOf(
        "title" to "Demostración de ORYKSA para TV",
        "language" to "Idioma",
        "language_name" to "Español (Latinoamérica)",
        "mic" to "Micrófono de la TV",
        "mic_yes" to "sí",
        "mic_no" to "no, usa el celular con el QR",
        "phone_last" to "Último texto del celular",
        "presenter" to "Escuchar a la presentadora",
        "assistant" to "Abrir el asistente",
        "phone" to "Hablar por el celular",
        "change_language" to "Cambiar el idioma",
        "simulated" to "Demostración simulada: no usa el servidor de ORYKSA",
        "welcome" to "¡Hola! Soy ORYKSA, tu presentadora. Usa las flechas del control remoto y presiona OK.",
    ),
    "es-ES" to mapOf(
        "title" to "Demostración de ORYKSA para TV",
        "language" to "Idioma",
        "language_name" to "Español (España)",
        "mic" to "Micrófono de la TV",
        "mic_yes" to "sí",
        "mic_no" to "no, usa el móvil con el QR",
        "phone_last" to "Último texto del móvil",
        "presenter" to "Escuchar a la presentadora",
        "assistant" to "Abrir el asistente",
        "phone" to "Hablar por el móvil",
        "change_language" to "Cambiar el idioma",
        "simulated" to "Demostración simulada: no usa el servidor de ORYKSA",
        "welcome" to "¡Hola! Soy ORYKSA, tu presentadora. Usa las flechas del mando y pulsa OK.",
    ),
    "fr-FR" to mapOf(
        "title" to "Démo ORYKSA pour la TV",
        "language" to "Langue",
        "language_name" to "Français",
        "mic" to "Micro de la TV",
        "mic_yes" to "oui",
        "mic_no" to "non, utilisez votre téléphone avec le QR code",
        "phone_last" to "Dernier texte du téléphone",
        "presenter" to "Écouter la présentatrice",
        "assistant" to "Ouvrir l'assistant",
        "phone" to "Parler avec le téléphone",
        "change_language" to "Changer de langue",
        "simulated" to "Démo simulée : elle n'utilise pas le serveur ORYKSA",
        "welcome" to "Bonjour ! Je suis ORYKSA, votre présentatrice. Utilisez les flèches de la télécommande et appuyez sur OK.",
    ),
    "de-DE" to mapOf(
        "title" to "ORYKSA-Demo für TV",
        "language" to "Sprache",
        "language_name" to "Deutsch",
        "mic" to "TV-Mikrofon",
        "mic_yes" to "ja",
        "mic_no" to "nein, nutze dein Handy mit dem QR-Code",
        "phone_last" to "Letzter Text vom Handy",
        "presenter" to "Moderatorin anhören",
        "assistant" to "Assistent öffnen",
        "phone" to "Mit dem Handy sprechen",
        "change_language" to "Sprache ändern",
        "simulated" to "Simulierte Demo: sie nutzt nicht den ORYKSA-Server",
        "welcome" to "Hallo! Ich bin ORYKSA, deine Moderatorin. Nutze die Pfeiltasten der Fernbedienung und drücke OK.",
    ),
    "en-US" to mapOf(
        "title" to "ORYKSA for TV demo",
        "language" to "Language",
        "language_name" to "English (US)",
        "mic" to "TV microphone",
        "mic_yes" to "yes",
        "mic_no" to "no, use your phone with the QR code",
        "phone_last" to "Last text from the phone",
        "presenter" to "Hear the host",
        "assistant" to "Open the assistant",
        "phone" to "Talk on your phone",
        "change_language" to "Change the language",
        "simulated" to "Simulated demo: it does not use the ORYKSA server",
        "welcome" to "Hi! I am ORYKSA, your host. Use the arrows on the remote and press OK.",
    ),
    "en-GB" to mapOf(
        "title" to "ORYKSA for TV demo",
        "language" to "Language",
        "language_name" to "English (UK)",
        "mic" to "TV microphone",
        "mic_yes" to "yes",
        "mic_no" to "no, use your phone with the QR code",
        "phone_last" to "Last text from the phone",
        "presenter" to "Hear the host",
        "assistant" to "Open the assistant",
        "phone" to "Talk on your phone",
        "change_language" to "Change the language",
        "simulated" to "Simulated demo: it does not use the ORYKSA server",
        "welcome" to "Hello! I am ORYKSA, your host. Use the arrows on the remote and press OK.",
    ),
)
