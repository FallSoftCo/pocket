"""Shared media clock only; no credentials, task commands or network listeners."""
import asyncio,json,time
from fractions import Fraction
from aiortc import AudioStreamTrack
from av import AudioFrame

def emit(kind, **values):
    print(json.dumps({"type": kind, **values}, ensure_ascii=False), flush=True)


class RecordedAudio(AudioStreamTrack):
    def __init__(self, pcm):
        super().__init__()
        self.pcm = pcm
        self.position = None
        self.samples = 0
        self.clock = None
        self.drained = False

    async def recv(self):
        if self.clock is None:
            self.clock = time.monotonic()
        else:
            await asyncio.sleep(max(0, self.clock + self.samples / 16000 - time.monotonic()))
        data = bytes(640)
        if self.position is not None:
            if self.position < len(self.pcm):
                part = self.pcm[self.position:self.position + 640]
                data = part + bytes(640 - len(part))
            self.position += 640
            # Feed a real silence tail through the media encoder; do not stop producing RTP.
            if not self.drained and self.position >= len(self.pcm) + 32000:
                self.drained = True
                emit("drained", samples=len(self.pcm) // 2)
        frame = AudioFrame(format="s16", layout="mono", samples=320)
        frame.planes[0].update(data)
        frame.sample_rate = 16000
        frame.pts = self.samples
        frame.time_base = Fraction(1, 16000)
        self.samples += 320
        return frame

