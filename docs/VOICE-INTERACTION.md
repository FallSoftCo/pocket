# Foreground voice interaction

The capture microphone directly starts recording; pressing it again stops capture and submits the recorded turn. Its visible state distinguishes idle, recording, processing and a real capture error. Capture in an open conversation belongs to that conversation; global coordination belongs to the coordinator. Pending saved turns are reconciled explicitly rather than hidden behind a fresh recording action.

Recording while typing must keep the current conversation, keyboard focus and draft. Hardware volume keys use the same foreground capture path, including when the IME is open. Repeated key-down events must not start and immediately stop the same recording, and each consumed down event needs a matching consumed up event. Outside the app, ordinary system volume behavior remains; this design does not claim system-global interception.

Playback volume stays inline with existing speech controls. It adjusts Android media volume, the stream used by NextComp speech, and preserves explicit mute. Capture and playback are separate controls: opening a volume control is never a microphone action. Existing per-session speech queues, pause/clear controls and exact visible-language speech snapshots remain.

Blackout is still an explicit screen-on mode. Foreground capture keys should continue to work while black; exiting restores the existing conversation and window settings. This does not prevent Home, screen lock or operating-system backgrounding.

Qualification must distinguish native emulator key injection from real handset button presses, mock transcription from provider transcription, and release availability from actual in-place installation. See [alpha39 qualification](qualification/foreground-voice-alpha39.json) for measured outcomes and limitations.
