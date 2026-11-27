<p align="center"><img src="https://app.oryksa.com/static/oryksa_logo.png" width="300" alt="ORYKSA"></p>

# ORYKSA for Android (Kotlin)

[![English](https://img.shields.io/badge/English-30363d?style=for-the-badge)](README.md) [![Português](https://img.shields.io/badge/Portugu%C3%AAs-30363d?style=for-the-badge)](README.pt.md) [![Español](https://img.shields.io/badge/Espa%C3%B1ol-30363d?style=for-the-badge)](README.es.md) [![Français](https://img.shields.io/badge/Fran%C3%A7ais-0891b2?style=for-the-badge)](README.fr.md)

<p align="center"><a href="https://developer.oryksa.com/fr/sdks"><img src="https://developer.oryksa.com/assets/img/sdk/oryksa-sdks-fr.gif" width="760" alt="SDK ORYKSA : le chat et la voix de l'IA sur iOS, Android, Flutter, React Native et le web"></a></p>

SDK Android officiel d'**ORYKSA AI Employees**. Mettez dans votre app Android un employé IA qui connaît déjà votre entreprise : un chat Jetpack Compose prêt à l'emploi (et une activité prête pour les apps avec Views) identique au chat du site ORYKSA, le nom et la photo de votre IA issus de votre compte ORYKSA, en anglais, portugais et espagnol.

* Documentation : https://developer.oryksa.com/fr/sdks
* API : `https://api.oryksa.com/v1`
* Android 6.0+ (minSdk 23).

## Captures d'écran

<p align="center"><img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_product_en.png" width="190" alt="Product page with the chat button"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_chat_en.png" width="190" alt="Chat"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_theme_en.png" width="190" alt="Chat with the README theme"> <img src="https://raw.githubusercontent.com/oryksa/oryksa-android/main/docs/screenshots/android_voice_en.png" width="190" alt="Voice"></p>

Écrans réels de l'app d'exemple de ce dépôt, capturés par la CI sur un émulateur avec un compte de test ORYKSA. Le chat vert utilise le code de *Couleurs et thème*, plus bas.

## Installer

Maven Central (aucun dépôt supplémentaire nécessaire) :

```kotlin
dependencies {
    implementation("com.oryksa:sdk:1.2.0")
}
```

Autre option, JitPack (même code, directement depuis GitHub) :

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.oryksa:oryksa-android:1.2.0")
```

## À quoi il sert

Le SDK met le chat et la voix ORYKSA dans votre app pour servir les **clients de votre entreprise** : ils posent des questions, achètent et réservent. L'IA répond avec votre Brain, vos produits et vos services, avec le nom, la photo et la voix que vous avez choisis dans *Votre IA*, et enregistre les leads, réservations et ventes dans les Activités de votre compte ORYKSA. Ce n'est **pas** une interface pour le propriétaire : via le SDK, personne ne modifie les paramètres, ne lit les Activités ni ne donne d'ordres à ORYKSA. En savoir plus : https://help.oryksa.com/fr/a/sdks-what-they-do

Les gros mots que le client tape apparaissent sous forme d'astérisques dans le chat (la même liste que tous les chats ORYKSA, chargée depuis `https://app.oryksa.com/widget/profanity.json`, conservée 24 heures). Les réponses de l'IA arrivent déjà filtrées par le serveur.

## Voix et contexte de l'app (1.1)

Le chat affiche un micro quand votre forfait inclut la voix. Il ouvre l'écran vocal avec le même comportement que l'app ORYKSA : le micro reste ouvert pendant qu'elle parle et seule une voix humaine l'interrompt (la frappe au clavier, la télévision et son propre écho ne l'interrompent pas) ; elle s'arrête sur le mot et le texte reste dans le chat ; à un chuchotement, elle répond en chuchotant ; si sa voix échoue, le texte est affiché (jamais une voix robotique).

Autorisation du micro : le manifeste du SDK déclare `RECORD_AUDIO` ; l'écran vocal la demande quand le client l'ouvre.

```kotlin
OryksaChat(client, lang = "pt",
    appContext = { OryksaAppContext(screen = "product", title = "Sky Beginner Snowboard, 489.95") })
```

## Liens avec un bouton pour les ouvrir (1.1.3)

Les liens que donne l'IA se touchent dans le chat (adresses web et e-mails), et chaque lien web a une carte sous sa réponse avec la photo et le titre de la page et un bouton pour l'ouvrir (les pages de votre propre site, les domaines de votre chat web ; les autres sites montrent l'adresse). L'écran vocal montre ce qu'elle dit sans les adresses, avec les mêmes cartes ; toucher le bouton ferme l'écran vocal. Passez `onLink` pour ouvrir vos propres écrans, par exemple la page du produit : renvoyez true quand votre app l'a ouvert, false pour laisser le navigateur l'ouvrir.

```kotlin
OryksaChat(client, lang = "en", onLink = { uri ->
    val id = myProductIdOf(uri) ?: return@OryksaChat false // vos routes
    navController.navigate("product/$id")
    true
})

// Apps avec Views : le chat en plein écran
OryksaChatActivity.onLink = { uri -> openMyProduct(uri) }
```

`onLink` existe aussi sur les deux fonctions `OryksaVoiceScreen`.

## Joindre une photo ou un document (1.1.3)

Le chat a un bouton pour joindre (un trombone, activé par défaut) : une photo du sélecteur de photos, une photo de l'appareil photo ou un document (PDF, Word, texte, CSV, jusqu'à 8 Mo). L'IA regarde la photo (réduite à 1600 pixels, JPEG, jusqu'à 2 Mo) et lit le document ; la bulle du client montre la photo, ou l'icône du document et son nom. `attachments = false` masque le bouton (`OryksaChatActivity.attachments = false` pour le chat en plein écran).

Autorisations :

* Galerie : aucune. Le SDK utilise le sélecteur de photos d'Android (`PickVisualMedia`).
* Appareil photo : aucune dans la plupart des apps. Le SDK ouvre l'app de l'appareil photo (`TakePicture`), qui n'a pas besoin de l'autorisation `CAMERA`, et le SDK ne la déclare pas. Si votre app déclare `android.permission.CAMERA` dans son propre manifest, Android exige qu'elle soit accordée, donc le SDK la demande avant d'ouvrir l'appareil photo. Le manifest du SDK ajoute son propre FileProvider (`${applicationId}.oryksa.fileprovider`) pour la photo.
* Documents : aucune. Ils s'ouvrent dans le sélecteur de documents du système (`OpenDocument`).

```kotlin
OryksaChat(client, lang = "en", attachments = false) // sans bouton pour joindre
// Sans l'interface :
client.send("Voici le devis", file = OryksaFile(pdfBytes, "devis.pdf", "application/pdf"))
```

## Vos propres fonctions d'IA : `ai` (1.1.4)

Faites faire à l'IA ce dont votre app a besoin (lire une photo, extraire des données, classer, traduire, rédiger) et payez en interactions ORYKSA : 1 par réponse. Votre prompt et votre JSON Schema restent dans votre app ; le jeton de session suffit (n'embarquez jamais la clé secrète). Aucune conversation n'est créée dans votre boîte.

```kotlin
val r = client.ai(
    instructions = "Read the tyre on the photo: brand, model and size exactly as written on the sidewall. " +
        "If the size is not legible, readable=false and size=null. Never invent.",
    images = listOf(OryksaImage(jpegBytes)), // resized to 1600 px
    schema = JSONObject("""{"type":"object","properties":{"readable":{"type":"boolean"},"brand":{"type":["string","null"]},"size":{"type":["string","null"]}},"required":["readable","brand","size"]}"""),
)
if (r.json?.optBoolean("readable") == true) showProducts(r.json!!.getString("size"))
```

`r.text` contient la réponse en texte, `r.json` l'objet (avec un schema) et `r.interactionsSpent` les interactions utilisées. Quand les interactions du forfait sont épuisées, l'appel lève une `OryksaException` avec le status 402.

## Apps TV : `com.oryksa.sdk.tv` (Android TV, Google TV, Fire TV)

Ce que l'IA fait dans n'importe quelle app TV, uniquement avec la télécommande (flèches, OK et Retour), avec de gros caractères et un focus visible depuis le canapé. C'est du Jetpack Compose natif, sans web view. Les textes, les voix, les règles et les boutons viennent de votre compte ORYKSA.

* **Présentatrice** : l'IA parle à l'écran avec une voix native dans la langue du spectateur, avec de grands sous-titres. Les phrases déjà dites sont rejouées depuis l'appareil, sans nouvelle voix, et sont facturées à nouveau chaque fois qu'elle les dit.
* **Panneau d'assistant** : une conversation avec l'IA pensée pour la télécommande.
* **Voix par le téléphone** : beaucoup de TV ne donnent pas de micro aux apps (le Fire TV Stick n'en donne pas). La TV affiche un QR code, le téléphone ouvre la page ORYKSA et ce que dit la personne arrive dans votre app sous forme de texte.
* **`client.ai`** pour vos propres tâches, par exemple modérer ou classer.

```kotlin
// L'appareil dit s'il s'agit d'un Fire TV ou d'une Android TV (envoyé comme `platform`).
val platform = OryksaTvPlatform.detect(context)?.wire ?: "android"
val client = OryksaClient(getToken = { tokenDeVotreServeur() }, platform = platform)
val tv = OryksaTv.create(context, client, locale = "fr-FR")

setContent {
    // Votre écran dans le host : la présentatrice s'affiche par-dessus, et OK / Retour la font taire pendant qu'elle parle.
    OryksaPresenterHost(tv) { VotreEcran() }
    if (afficherAssistant) OryksaTvAssistantDialog(tv, onDismiss = { afficherAssistant = false })
}

scope.launch { tv.presenter.say("Bienvenue ! Je suis votre présentatrice ce soir.") }
scope.launch { tv.presenter.sayGenerated("Annonce la manche suivante en une phrase courte.", input = "Manche 2") }
// Sous-titres seuls, sans son (facturée au prix du texte ; une phrase avec voix est facturée au prix de la voix).
scope.launch { tv.presenter.say("Le thème de ce soir : les voyages.", voice = false) }

// Le téléphone comme micro : affichez OryksaPairingCard(tv) et lisez ce qui est dit.
scope.launch { tv.phoneVoice.texts.collect { texte -> quandLeSpectateurADit(texte) } }
```

* **Sans serveur à vous** : `OryksaTv.publicClient(context, chatToken, conversationId)` ouvre une session publique avec le token public du chat de votre entreprise, et envoie `platform` et votre package comme `app_id`. Quand votre entreprise limite son chat, elle doit mettre votre package dans la liste des apps autorisées. `app_id` est un filtre, pas une sécurité forte (il peut être imité) : la sécurité forte, c'est un token qui vient de votre propre serveur (`POST /v1/sessions`).
* **Langues** : le serveur ORYKSA choisit la voix et les textes pour la locale. Il y a des voix natives pour pt-BR, pt-PT, es-ES, es-419, fr-FR, de-DE, en-US et en-GB, et les autres langues utilisent la voix anglaise des États-Unis.
* **Interactions** : chaque phrase de la présentatrice est facturée selon la grille des coûts d'interaction ORYKSA chaque fois qu'elle la dit ou l'affiche (par morceau de 600 caractères au plus), la phrase répétée et la phrase en sous-titres seuls aussi : la phrase avec voix au prix de la voix, la phrase en sous-titres seuls au prix du texte. Le nombre exact arrive dans l'en-tête `X-ORYKSA-Interactions-Spent`, et une phrase que le serveur n'a pas comptée n'apparaît pas. Chaque texte entendu sur le téléphone, chaque réponse du chat et chaque réponse de `client.ai` sont aussi facturés.
* **Micro de la TV** : `OryksaTvMic.isAvailable(context)` indique si l'appareil a une entrée audio. Le SDK ne demande jamais l'autorisation sur une TV qui n'en a pas.
* **Dialogues** : Compose ne rend pas le focus au bouton qui a ouvert un dialogue, et avec une télécommande le focus est le seul moyen de savoir où l'on est. Passez `returnFocusTo` (le `FocusRequester` de ce bouton) à `OryksaTvAssistantDialog` et utilisez `OryksaTvDialog(onDismiss, returnFocusTo, tv = tv)` pour vos propres dialogues : avec `tv`, OK et Retour arrêtent la présentatrice pendant qu'elle parle (un dialogue reçoit les touches à part de votre écran).
* **AndroidManifest** : le SDK déclare le micro comme non obligatoire (avec seulement `RECORD_AUDIO`, les stores en exigeraient un et cacheraient votre app sur les TV sans micro). Déclarez aussi l'écran tactile et leanback comme non obligatoires, et ajoutez la catégorie du lanceur TV avec une bannière. Voir `example-tv/`.

```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.software.leanback" android:required="false" />
<!-- dans l'intent-filter de l'activité, et android:banner sur l'application -->
<category android:name="android.intent.category.LEANBACK_LAUNCHER" />
```

Pour les tests et les démos, `OryksaTv.create(context, OryksaTvFakeBackend())` fait tout tourner sans le serveur. C'est simulé, donc cela ne prouve rien sur votre compte ORYKSA.

## Comment ça marche (et pourquoi la clé reste en sécurité)

1. Votre **serveur** garde la clé API secrète (`oryk_live_...`, créée sur developer.oryksa.com) et crée un **jeton de session** de courte durée pour chaque utilisateur connecté avec `POST /v1/sessions`.
2. Votre **app** ne reçoit que ce jeton de session (`oryk_cs_...`). La clé secrète n'entre jamais dans l'app.
3. Chaque réponse de l'IA compte comme une interaction de votre forfait ORYKSA, comme sur WhatsApp.

## Jetpack Compose

```kotlin
val client = OryksaClient(getToken = { myBackend.fetchOryksaToken() }) // renvoie "oryk_cs_..."
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

Ou placez le panneau n'importe où avec `OryksaChat(client = client)`. Couleurs : `OryksaChatTheme(accent = Color(0xFF5B57E0))`.

## Apps avec Views (Java ou Kotlin)

```kotlin
OryksaChatActivity.client = OryksaClient(getToken = { myBackend.fetchOryksaToken() })
button.setOnClickListener { OryksaChatActivity.open(this, "en") }
```

Sans l'interface (coroutines) : `client.agent()`, `client.sendAndWait("Are you open on Saturday?")`, `client.messages()`.

## Garder l'IA synchronisée avec votre app

Sur votre serveur (par exemple avec `@oryksa/sdk` sur Node) : `oryksa.learnApp({ name, description, screens, faq })`.

## Erreurs

Les appels qui échouent lèvent `OryksaException` avec `status`, `code` (par exemple `interaction_limit_reached`, `rate_limited`, `plan_required`) et `message`.

## Couleurs et thème

`OryksaChatTheme` est une data class avec cinq couleurs : `accent` (boutons, bulles du visiteur, liens), `ink` (texte principal), `soft` (bulles de l'IA), `background` (panneau) et `muted` (texte secondaire). Chacune est une `Color` Compose avec une valeur par défaut : vous ne passez que celles que vous voulez changer. Passez le thème à `OryksaChat` et à `OryksaChatButton`.

```kotlin
val theme = OryksaChatTheme(accent = Color(0xFF0E8A6B))

OryksaChat(client = client, theme = theme)
OryksaChatButton(client = client, onClick = { /* open the chat */ }, theme = theme)
```

Il n'y a pas de thème sombre automatique : pour le mode sombre, passez vos propres couleurs sombres. `OryksaChatActivity` (apps avec Views) utilise les couleurs par défaut.

---

## À propos de l'auteur

**Weslley Harakawa** - Fondateur d'ORYKSA AI. À Lisbonne, Portugal. Spécialités : intelligence artificielle, développement web et mobile, tokenisation blockchain. Formation : University of the People.

- LinkedIn: https://www.linkedin.com/in/weslleyharakawa/
- ORYKSA AI Employees: https://oryksa.com (X: https://x.com/oryksa, Instagram: https://www.instagram.com/oryksaai, YouTube: https://www.youtube.com/@ORYKSA)

---

MIT License · ORYKSA AI Employees · W8 Atlantic Unipessoal Lda
