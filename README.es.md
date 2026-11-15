<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-30363d?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-30363d?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-0891b2?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-30363d?style=for-the-badge)](README.fr.md)

SDK oficial de Android de **ORYKSA AI Employees**: pon en tu app un empleado de IA que ya conoce tu negocio, con un chat listo (Jetpack Compose o actividad lista) igual al chat de la web de ORYKSA, con el nombre y la foto de tu IA. Tu servidor crea el token de sesión (`POST /v1/sessions`) y la app usa `OryksaClient(getToken = ...)` con `OryksaChatActivity.open(context, "es")`. Cada respuesta de la IA cuenta como una interacción de tu plan.

## Instalar

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.1.0")
}
```

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.1.0")
```

**Documentación completa:** https://developer.oryksa.com/es/sdk-android

---

## Sobre el autor

**Weslley Harakawa** - Fundador de ORYKSA AI. En Lisboa, Portugal. Especialidades: inteligencia artificial, desarrollo web y móvil, tokenización en blockchain. Formación: University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSAAI)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
