import limits from './audio-limits.json' with {type:'json'};
export const MAX_RECORDING_SECONDS=limits.maxSeconds;
export const MAX_RECORDING_PCM_BYTES=limits.maxSeconds*limits.sampleRate*limits.channels*limits.bytesPerSample;
export const MAX_RECORDING_WAV_BYTES=MAX_RECORDING_PCM_BYTES+44;
// Native media is paced at real time. Leave room for negotiation and finalization.
export const NATIVE_AUDIO_DEADLINE_MS=(limits.maxSeconds+90)*1000;
export const REMOTE_AUDIO_DEADLINE_MS=NATIVE_AUDIO_DEADLINE_MS+30000;
