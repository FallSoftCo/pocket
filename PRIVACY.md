# Privacy and data flow

Pocket is self-hosted. FallSoft does not operate an application backend, receive pairing credentials, or collect Pocket analytics. Installing the APK alone does not connect to a FallSoft server.

## Workstation and phone

The optional Losangelex backend keeps its owner-registered endpoint and token-file reference in the private NextComp data directory. NextComp sends scoped team commands to that service using the server-held token. Team execution and detailed history stay with the registered service. NextComp stores notification-origin mappings; the phone stores backend selection, scoped drafts and uncertain command payloads privately. Team push previews are generic, and opening them fetches the originating request through the authenticated NextComp connection.

The workstation stores pairing/device records, hashed device bearer tokens, Firebase registration tokens, notification history, reply/task-creation outboxes, and copied attachments in its private data directory. Codex retains its own transcripts and account configuration separately. All paired devices belong to one owner and can access that owner's tasks.

The phone stores its bearer token, server address, Firebase public configuration, notification/reminder state, and queued reply drafts in app-private storage. Opened attachments may be cached on the phone and shared with another app when you choose to open them. Android backup is disabled. Uninstalling clears app-private data, but does not retract content shared with another app or erase server data.

Linked-image previews are fetched by the phone and may be cached privately. An external image host receives its ordinary image request and network metadata, including the requested URL. Pairing credentials are attached only to the exact paired-backend `/api/files/` origin; public image requests carry no pairing token. Authenticated attachment redirects are blocked. Native previews and zoom do not send images to a FallSoft service.

Phone-local mode also stores Pocket bridge state in Termux private storage and a random automation secret separately in Pocket and Termux. It uses loopback connections and does not send local notifications through Firebase. Codex prompts, transcripts, tool inputs, screen structure, and requested screenshots still go to the owner's signed-in Codex service according to that account's OpenAI settings. Pocket sends screen data only when a phone tool is called; password text is omitted. Accessibility data and screenshots are not sent to FallSoft.

## Google Firebase

FCM processes device registration and notification delivery. Notification title, bounded text preview, task/notification identifiers, event type, and timestamp pass through Google. Full transcripts and attachments are fetched from the owner's HTTPS backend. Firebase service-account credentials stay on the workstation. Pocket includes no Analytics SDK and disables Firebase analytics collection.

This is self-hosted application infrastructure, **not** independence from Google's push network. Read [Firebase privacy information](https://firebase.google.com/support/privacy) for that service. Work submitted to Codex is also subject to the owner's Codex/OpenAI configuration and terms.

## Audio, access, and retention

Speech is optional. Labels are generic; Speak messages reads the notification's spoken response. Short previews travel with the Firebase payload; complete longer spoken text is fetched from the authenticated backend. Notification speech and explicit Speak send the selected text to OpenAI through the paired host's ChatGPT-authenticated native Codex audio transport. These speech-only connections receive a synthetic silent input track, do not access the microphone, and cannot submit tasks. The APK receives no account credentials or separate audio API key. There is no silent offline TTS or paid API fallback. Fresh audio requires the paired host and compatible native account transport; unavailable audio leaves the item saved for retry or Clear.

Saved speech text, cursor and bounded rendered audio cache remain in app-private storage. Cached audio is played locally with MediaPlayer and Android media controls. Explicit Speak parks any existing notification queue and restores it paused on stop or completion. Excerpt cleanup does not guarantee that sensitive task content is removed; enable content speech only where reading notifications aloud is appropriate. Notification previews follow Android's lock-screen settings. Native speech and task-model usage are distinct; exact account/grant debits have not been measured.

Voice mode records only manually started turns. Once the user sends a completed recording, the phone delivers it over WebRTC to OpenAI through a native Codex voice connection authenticated by the paired host. Account credentials remain on the host; the APK and bundled audio page receive neither account credentials nor an audio API key. The host collects the transcript before submitting it to a dedicated Codex controller; delayed-final-transcript completeness remains a runtime qualification gap. Incoming audio cannot execute work through the audio-only thread. Controller responses and task notifications can be read aloud through the same connection. Native voice uses account usage rather than a separate API audio key.

A pending recording remains in app-private storage until confirmed delivery and response playback begins. The backend retains transcripts, results, command IDs and tool outcomes. Response audio is buffered privately in the voice transport for pause/replay and released when voice ends. Generic prerecorded error prompts contain no task data. Volume keys are captured only during voice mode; background key access uses the optional Accessibility service. Turning voice off ends recording and foreground audio services; an expired client lease closes abandoned native connections. Voice can read task content aloud while the screen is locked. Session list previews expose recent task text within the authenticated Pocket app.
