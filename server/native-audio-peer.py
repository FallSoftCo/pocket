#!/usr/bin/env python3
"""Private media peer. No account credentials, task tools, HTTP listener or task submission."""
import argparse
import asyncio
import hashlib
import io
import json
import sys
import time
import wave
from fractions import Fraction
from pathlib import Path

from aiortc import AudioStreamTrack, RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from av import AudioFrame
from aiortc.mediastreams import MediaStreamError


from native_audio_media import RecordedAudio, emit


async def run(args):
    raw = Path(args.audio).read_bytes()
    if len(raw) > 3840044 or hashlib.sha256(raw).hexdigest() != args.sha256:
        raise ValueError("Recording identity or size does not match admission")
    with wave.open(io.BytesIO(raw)) as recording:
        if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate()) != (1, 2, 16000):
            raise ValueError("Expected mono 16 kHz PCM16")
        if not 1600 <= recording.getnframes() <= 1920000:
            raise ValueError("Recording duration outside bounds")
        pcm = recording.readframes(recording.getnframes())

    peer = RTCPeerConnection(RTCConfiguration(iceServers=[]))
    audio = RecordedAudio(pcm)
    sender = peer.addTrack(audio)
    channel = peer.createDataChannel("oai-events", ordered=True)
    opened, started, closed = asyncio.Event(), asyncio.Event(), asyncio.Event()
    transcript, timeline = [], []
    final_event = None
    remote_tasks = set()
    failed = None

    @channel.on("open")
    def on_open():
        opened.set()
        emit("connected")

    @channel.on("message")
    def on_message(data):
        nonlocal final_event, failed
        if not isinstance(data, str) or len(data) > 262144:
            return
        try:
            event = json.loads(data)
        except (ValueError, TypeError):
            failed = "Invalid provider event"
            return
        kind = event.get("type")
        if kind == "session.started":
            started.set()
            emit("providerStarted", fields=list(event.get("session",{}).keys()))
        if kind == "session.input_transcript.delta":
            delta = event.get("delta")
            if not isinstance(delta, str) or sum(len(x) for x in transcript) + len(delta) > 24000:
                failed = "Invalid or oversized transcript"
                return
            transcript.append(delta)
            timeline.append({key: event.get(key) for key in ("start_ms", "end_ms")})
        if kind == "error":
            failed = str(event.get("error", {}).get("message", "Audio provider error"))[:300]
        if kind == "session.closed":
            final_event = {key: event.get(key) for key in ("reason", "usage")}
            closed.set()
        # Event names only: session configuration and credentials never leave the peer.
        emit("providerEvent", name=kind)

    @peer.on("track")
    def on_track(track):
        async def discard():
            try:
                while True:
                    await track.recv()
            except MediaStreamError:
                pass
        task = asyncio.create_task(discard())
        remote_tasks.add(task)
        task.add_done_callback(remote_tasks.discard)

    @peer.on("connectionstatechange")
    def on_connection_state():
        emit("connectionState", state=peer.connectionState)

    try:
        await peer.setLocalDescription(await peer.createOffer())
        emit("offer", sdp=peer.localDescription.sdp)
        while True:
            line = await asyncio.to_thread(sys.stdin.readline)
            if not line:
                break
            if len(line) > 131072:
                raise ValueError("Control message too large")
            command = json.loads(line)
            kind = command.get("type")
            if kind == "answer":
                await peer.setRemoteDescription(RTCSessionDescription(sdp=command["sdp"], type="answer"))
            elif kind == "play":
                await asyncio.wait_for(opened.wait(), 30)
                await asyncio.wait_for(started.wait(), 15)
                emit("playStarted", sessionSamples=audio.samples)
                if audio.position is not None:
                    raise ValueError("A recording can only be played once in a peer")
                audio.position = 0
            elif kind == "finalize":
                if not audio.drained or final_event is not None:
                    raise ValueError("Invalid finalization boundary")
                # Listener is installed above before close is sent. Keep media and data alive.
                stats=await sender.getStats()
                emit("mediaStats", samples=audio.samples, played=audio.position,
                     outbound=[{"packets":r.packetsSent,"bytes":r.bytesSent} for r in stats.values() if r.type=="outbound-rtp"])
                channel.send(json.dumps({"type": "session.close"}))
                await asyncio.wait_for(closed.wait(), 15)
                emit("finalized", confirmed=failed is None and final_event.get("reason") in ("close_requested","client_request"),
                     text="".join(transcript), timeline=timeline, final=final_event, error=failed)
            elif kind == "shutdown":
                break
            else:
                raise ValueError("Unsupported peer command")
    finally:
        audio.stop()
        for task in list(remote_tasks):
            task.cancel()
        await asyncio.gather(*remote_tasks, return_exceptions=True)
        await peer.close()
        emit("peerClosed", state=peer.connectionState)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", required=True)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except Exception as error:
        emit("error", message=str(error)[:300])
        sys.exit(1)
