# Changelog

## 1.2.0

* **ORYKSA for TV apps: `com.oryksa.sdk.tv`** (Android TV, Google TV, Fire TV with Fire OS), in the same artifact.
  Only what the AI does for every TV app, driven only by the remote (arrows, OK, Back), in Jetpack Compose:
  * `OryksaTv.create(context, client)`: the TV pieces, with their state in `StateFlow`.
    `OryksaTvPlatform.detect(context)` finds Fire TV (`amazon.hardware.fire_tv`) or Android TV (leanback /
    television) for `platform`.
  * `OryksaPresenter` + `OryksaPresenterHost` / `OryksaPresenterView`: the AI speaks with a native voice for the
    locale (from the server, never in the code) and big subtitles. Every line is charged by the ORYKSA interaction
    cost table each time she says it or shows it (per piece of up to 600 characters), a repeated line too: a spoken
    line at the voice price, a subtitles-only line at the text price. The exact number comes in the
    `X-ORYKSA-Interactions-Spent` header, and the line appears only once the server has counted it; a line not
    counted (no interactions left, no network) is not shown. Lines already said are kept on the device and sent
    with `cached: true` and their `voice_rev`, so the server can charge them without making the voice again. With
    no voice for the locale she shows the subtitles only, never a robot voice. OK and Back on the remote stop her.
    Server route: `POST /v1/client/tts` with `locale` and `purpose: "presenter"`. `say(text, voice = false)` shows
    the subtitles only, at reading speed, with no sound, and is charged at the text price (`voice: false` in the
    request).
  * `OryksaTvAssistant` / `OryksaTvAssistantDialog`: a conversation with the AI for the remote. Questions, buttons,
    texts and rules come from `GET /v1/client/agent?locale=` (block `tv`). Typing uses the system keyboard, and only
    the Send button sends.
  * `OryksaPhoneVoice` + `OryksaPairingCard`: the phone as the microphone (QR and code; `POST /v1/client/tv/pair`,
    long-poll of `.../events`, `DELETE`).
  * `OryksaTvMic.isAvailable(context)`: whether the TV lists an audio input. It never asks for the permission.
  * `OryksaTvFakeBackend`: a simulated backend for tests and demos.
* **`OryksaClient(platform = ...)`**: the device sent as `platform` (`android` by default), now in every chat
  request, as in the Flutter SDK. `send` / `sendAndWaitReply` take `lang`. New `OryksaClient.public(...)` (a public
  session from the public chat token, with `platform` and `app_id`) and `OryksaTv.publicClient(context, ...)`.
* **The microphone is declared as not required** in the SDK's manifest. Its `RECORD_AUDIO` alone made the stores
  require a microphone and hide the app on TVs (and other devices) without one. Checked with aapt2 on both demos.
* **TV buttons click only on their own key down and key up** (Fire TV, 26/11/2026: the OK that opened the
  assistant panel was released on a panel button and clicked it). **`OryksaTvDialog(onDismiss, returnFocusTo)`**
  and `OryksaTvAssistantDialog(returnFocusTo = ...)` give the focus back to the button that opened them, which
  Compose does not do.
* **OK and Back stop the presenter inside the SDK's dialogs too.** A dialog gets its keys apart from your screen: in
  the assistant panel OK pressed the focused button while she read the reply. The panel and the typing dialog now
  stop her first, and `OryksaTvDialog(..., tv = tv)` does the same in your own dialogs. A key right after she starts
  already stops her, and the repeats of a key held down are not passed on.
* **The assistant panel shows where the phone stands** while the QR card is closed (connected, listening, the phone
  left), with the server's texts in the chosen language.
* **One focus ring on screen**: a button is lit only while its window has the focus (with a dialog open, the button
  behind it kept its ring).
* Tests: 31 new JVM tests, 5 of them for the TV screens on the JVM (Robolectric): the remote keys, typing, the phone
  text and the phone leaving, pt-BR and pt-PT with no word of the other variant and nothing technical on screen.
  Proven on the Fire TV against the real server: the presenter in pt-BR with the cache and the assistant panel with
  the pt-BR texts from the server; the rest is not proven yet. The charging of every line, a repeated one and a
  subtitles-only one too, is proven against the real server, not on the TV yet. A live test against the real server
  (`LiveTvPresenterTest`) runs only with `ORYKSA_LIVE=1` and the token of a test account from the environment
  (`ORYKSA_TV_TOKEN` or `ORYKSA_TV_TOKEN_FILE`); without it, it is skipped and `./gradlew test` never calls the
  server.

## 1.1.4

* **The AI doing what you want in your app: `client.ai(...)`.** An AI task of your own feature, from the app, with the
  session token (never the secret key): instructions, text, up to 4 photos and an optional JSON Schema; the answer
  comes back as text and, with a schema, as an object in `json`. Each answer counts as 1 ORYKSA interaction. It does
  not create a conversation in the business's inbox and never acts on the account. Server route: `POST /v1/client/ai`.

## 1.1.3

- **Links you can tap, with an Open button.** The links of her replies were plain text. They can now be tapped in the chat (web and e-mail, underlined, in the accent colour on her bubbles and white on the customer's), and each web link (3 at most) gets a card under the reply with the picture and the title of the page and an **Open** button. Titles and pictures come from `OryksaClient.linkPreview` (new `GET /v1/client/link-preview`, kept in memory, never throws), only for the pages of the business's own site (its website chat domains); other links show the address.
- `onLink: (Uri) -> Boolean` on `OryksaChat`, `OryksaChatActivity.onLink` and both `OryksaVoiceScreen` functions lets your app open its own screens (return true) instead of the browser (`Intent.ACTION_VIEW`).
- **The voice screen shows what she says**, the short spoken version, without the addresses (7 lines at most), with the same cards under it; tapping Open closes the voice screen, then opens the link. `OryksaVoiceController.lastSpoken`; `say()` now also sets `lastReply` and `lastSpoken`.
- New public helpers: `oryksaLinksIn`, `oryksaWithoutLinks`, `oryksaRichText`, `oryksaOpenLink`, `OryksaLinkCard`, `OryksaLinkPreview`.
- **Attach a photo, take one or send a document.** The chat input has an attach button (on by default, `attachments = false` on `OryksaChat` or `OryksaChatActivity.attachments = false` hides it): the photo picker (`PickVisualMedia`), the camera app (`TakePicture`, through the SDK's own FileProvider `${applicationId}.oryksa.fileprovider`) or a document (`OpenDocument`: PDF, Word, text, CSV and pictures, up to 8 MB). Photos are made smaller to 1600 pixels, JPEG 80%, turned the right way up, up to 2 MB, and go as `image_base64` / `image_mime`; documents go as `file_base64` / `file_name` / `file_mime` on `POST /v1/client/chat`. The customer's bubble shows the photo, or the document icon and its name; a file that is too large or cannot be read shows a short note. No new permission: the SDK does not declare `CAMERA`; when your app declares it, the SDK asks for it before the camera opens. `OryksaImage`, `OryksaFile`, `send(image, file)`, `sendAndWait(image, file)`, `oryksaDocumentAttachment`, `ORYKSA_MAX_PHOTO_BYTES`, `ORYKSA_MAX_FILE_BYTES`.

## 1.1.2

- Documentation only, no change in the SDK behaviour: README in English, Portuguese, Spanish and French with real screenshots of the example app (CI, ORYKSA test account), an animated overview of the ORYKSA SDKs and a *Colours and theme* section; the example app compiles the README theme code.
- Install instructions point to this version. The YouTube link of the author section is now https://www.youtube.com/@ORYKSA.

## 1.1.1

- Voice screen: the words the person says are never shown on screen. While the AI thinks it shows "I'm listening" / "One moment..." in the interface language; the AI's answer still shows as text. Same behaviour as the ORYKSA apps, web and extensions.

## 1.1.0

* Copy button under each reply of the AI (copies the text, shows a check for a moment), as in the ORYKSA apps and extension.
* Voice: a voice screen with the photo of the AI ("I'm listening" / her answer, Mute, Close) opened from the microphone in the chat. The same behaviour as the ORYKSA app: the microphone stays open while she speaks and only a human voice cuts her off (typing, TV, birds and her own echo do not); she stops on the word and the text stays in the chat; the second before the cut is kept; 700 ms of silence closes a sentence (15 s at most); a whisper gets a whispered, shorter answer; her voice is the one chosen in ORYKSA and, if it fails, the text is shown without a robot voice.
* The voice engine is the ORYKSA app engine (`Vad`), with the same audio tests on real recordings.
* `appContext`: tell the AI which screen of your app the customer is on (product, cart, booking) so it answers about it.
* The client gets `tts`, `transcribe` and `voiceStats`; `send` accepts `voice`, `whisper`, `appContext` and returns `speech` (the short spoken version).
* The agent includes `photo` (the AI photo from Your AI), `voice` and `language`.
* Bold text (`**`) in the chat bubbles.
* The SDK serves the customers of the business only: it is never an interface for the owner.
* The SDK manifest declares `RECORD_AUDIO`; the voice screen asks for it when the customer opens it.

## 1.0.0

* First release.
* `OryksaClient`: in-app client with short-lived session tokens (agent, send, sendAndWait, messages), refreshes the token when it expires.
* `OryksaChatButton` and `OryksaChat` (Jetpack Compose) and `OryksaChatActivity` (any app): ready chat with the look of the ORYKSA website chat, name and photo of the AI from the ORYKSA account, in English, Portuguese (Portugal and Brazil) and Spanish.
