<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-30363d?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-0891b2?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-30363d?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-30363d?style=for-the-badge)](README.fr.md)

SDK oficial Android da **ORYKSA AI Employees**: põe na tua app um colaborador de IA que já conhece o teu negócio, com um chat pronto (Jetpack Compose ou atividade pronta) igual ao chat do site da ORYKSA, com o nome e a foto da tua IA. O teu servidor cria o token de sessão (`POST /v1/sessions`) e a app usa `OryksaClient(getToken = ...)` com `OryksaChatActivity.open(context, "pt")`. Cada resposta da IA conta como uma interação do teu plano.

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

**Documentação completa:** https://developer.oryksa.com/pt/sdk-android

---

## Sobre o autor

**Weslley Harakawa** - Fundador da ORYKSA AI. Em Lisboa, Portugal. Especialidades: inteligência artificial, desenvolvimento web e mobile, tokenização em blockchain. Formação: University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSAAI)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
