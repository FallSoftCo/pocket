# Privacy and data flow

Pocket is self-hosted. FallSoft does not operate an application backend, receive pairing credentials, or collect Pocket analytics. Installing the APK alone does not connect to a FallSoft server.

## Workstation and phone

The workstation stores pairing/device records, hashed device bearer tokens, Firebase registration tokens, notification history, reply/task-creation outboxes, and copied attachments in its private data directory. Codex retains its own transcripts and account configuration separately. All paired devices belong to one owner and can access that owner's tasks.

The phone stores its bearer token, server address, Firebase public configuration, notification/reminder state, and queued reply drafts in app-private storage. Opened attachments may be cached on the phone and shared with another app when you choose to open them. Android backup is disabled. Uninstalling clears app-private data, but does not retract content shared with another app or erase server data.

## Google Firebase

FCM processes device registration and notification delivery. Notification title, bounded text preview, task/notification identifiers, event type, and timestamp pass through Google. Full transcripts and attachments are fetched from the owner's HTTPS backend. Firebase service-account credentials stay on the workstation. Pocket includes no Analytics SDK and disables Firebase analytics collection.

This is self-hosted application infrastructure, **not** independence from Google's push network. Read [Firebase privacy information](https://firebase.google.com/support/privacy) for that service. Work submitted to Codex is also subject to the owner's Codex/OpenAI configuration and terms.

## Audio, access, and retention

Speech labels are generic, prepared with an offline English Android TTS voice; Pocket does not send task text to a speech service. Notification previews can appear on your device according to Android's lock-screen settings.

History and copied attachments have no automatic expiry in this alpha. Stop the backend and delete its data directory to erase Pocket server state (and invalidate all devices); this does not erase Codex history. Revoke a lost device using `node scripts/devices.mjs revoke DEVICE_ID`. Revocation prevents subsequent backend access and future push dispatches, but cannot recall already delivered or in-flight notifications. Protect the workstation, phone, backups, and tailnet accordingly.

Do not post private transcripts, screenshots, tokens, or service-account files in public issues.
