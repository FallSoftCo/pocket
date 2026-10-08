# Voice latency and evidence

The coordinator keeps a persistent task conversation. Its account-native audio connection is a separate transport, not a new work conversation for every utterance. Model choice and permissions are preserved.

The historical latency question took 60.055 seconds from transcript submission to an error clip. Native startup/context took 3.506 seconds and the model turn 17.455 seconds. Another 18.053 seconds elapsed before speech preparation, followed by a 21.009-second no-audio timeout. The clip was an error notification, not successful response speech. The greeting model took 2.850 seconds after 1.030-second startup; its original capture and first-audio logs were evicted. Those numbers cannot locate every delay in the greeting.

The confirmed client bottlenecks were full-recording replay after stop, an unconditional audio reconnection for each recording, blocking immersion preparation, and whole-response speech buffering. Foreground input now feeds bounded 16kHz PCM chunks while visibly recording; stop drains that same stream and commits once. A complete private WAV remains available if the stream failed before commit. A rejected/silent recording closes its uncommitted native input. Partial transcripts never submit coordinator work. The captured conversation remains the delivery target.

Foreground output plays the first audio while caching the response. The existing saved-notification renderer retains its buffered completion checks. Pause preserves unheard content: the provider can finish caching silently, and Resume seeks the cached audio to the remembered position rather than skipping ahead. Cancellation and stale generations cannot replay or replace newer speech. Uncached immersion planning continues asynchronously; the available rendition is spoken without waiting for translation. Error clips retain the error state.

Startup context contains bounded, explicitly partial metadata. Greetings do not fetch unrelated histories. Semantic routing still uses live discovery and reads the intended target before changes; creating new work requires discovery. Durable cross-turn references remain available.

A real account-provider test through an isolated headless transport measured:

| Stage after stop | Duration |
| --- | ---: |
| Already-streamed audio drains |4ms|
| Transcript settlement and commit |2,046ms|
| Coordinator and text delivery |2,534ms|
| TTS first audio |912ms|
| Stop to first audio |5,496ms|

This is not a handset audibility test or a universal latency guarantee. A nonmutating routing test identified the intended existing session from its history in 14.211 seconds. Historical ten-minute system samples showed 83.81–88.67% CPU idle and 0.20–0.93 ms SSD await, without sustained saturation; brief spikes remain possible. A current 29 ms phone-to-workstation Tailscale RTT does not measure the historical phone-to-provider path. An expired native heartbeat was observed separately after a failed speech attempt.

Persistent per-turn timing records cover transcription, coordinator readiness, context size/preparation, native startup, first response, completion, first fetch and speech submission. Native boundary logs cover capture, stream drain, first audio and safe WebRTC RTT/jitter/loss counters, without transcripts or candidate addresses. See [the versioned qualification](qualification/voice-latency-alpha42.json) for exact delivery and remaining limits.
