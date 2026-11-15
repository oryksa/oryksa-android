<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-30363d?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-30363d?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-30363d?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-0891b2?style=for-the-badge)](README.fr.md)

SDK Android officiel d'**ORYKSA AI Employees** : mettez dans votre app un employé IA qui connaît déjà votre entreprise, avec un chat prêt à l'emploi (Jetpack Compose ou activité prête) identique au chat du site ORYKSA, avec le nom et la photo de votre IA. Votre serveur crée le jeton de session (`POST /v1/sessions`) et l'app utilise `OryksaClient(getToken = ...)` avec `OryksaChatActivity.open(context, "en")`. Chaque réponse de l'IA compte comme une interaction de votre forfait.

## Installer

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.1.0")
}
```

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.1.0")
```

**Documentation complète:** https://developer.oryksa.com/fr/sdk-android

---

## À propos de l'auteur

**Weslley Harakawa** - Fondateur d'ORYKSA AI. À Lisbonne, Portugal. Spécialités : intelligence artificielle, développement web et mobile, tokenisation blockchain. Formation : University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSAAI)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
