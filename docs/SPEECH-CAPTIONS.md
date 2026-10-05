# Spoken update captions

Notification speech exposes the exact text of the currently synthesized chunk (or the exact translated passage submitted to native Codex audio), both in the app banner and the existing speech notification. Progress is labelled by chunk (Part X of Y): this audio path has no word timestamps, so it never guesses highlighted words or invents transcription timing.

The current conversation places spoken context above the composer, together with an equivalent retained answer when its conversation identity and complete normalized text match. Pause/Resume and Clear remain direct controls, and tapping the preview expands retained context or the full passage. Extra or different-conversation speech is not silently hidden. The alpha21 context surface and keyboard-preservation interactions are qualified in [conversation context](qualification/conversation-context-alpha21.json); earlier caption-sheet behavior belongs to earlier releases.

The latest caption remains after playback ends. Dismiss hides that message across further progress updates and app restarts; a different spoken message can appear normally. Dismiss does not stop playback. Pause/Resume remain separate playback actions.

Offline playback reuses notification ID 998. Native voice displays captions in its existing foreground notification (1120) while voice is active, then retains them under 998 when voice exits. Native captions start when actual playback starts and complete when playback finishes; no extra caption notification is posted during native voice. Retained updates are silent. Lock-screen visibility uses Android's notification permission, channel settings, and private visibility; the public version contains only “Unlock to read.” No overlay or lock-screen bypass permission is requested. Android may collapse or hide notification text according to the user's device settings.

Caption storage is app-private and includes only the latest spoken chunk. This does not expose raw model reasoning: captions show what the speech pipeline actually speaks.

`SpeechCaptionStateTest` checks persistent dismissal, new-message behavior, retained text, and truthful chunk progress. Device verification should exercise playback, pause/resume, completion, dismissal during playback, and locked-screen privacy.
