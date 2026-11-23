#!/usr/bin/env python3
"""Private native speech media renderer. Account/control authority stays in the parent."""
import argparse,asyncio,array,hashlib,json,sys,unicodedata,wave
from pathlib import Path
from aiortc import RTCConfiguration,RTCPeerConnection,RTCSessionDescription
from aiortc.mediastreams import MediaStreamError
from av import AudioResampler
from native_audio_media import RecordedAudio,emit

def words(text):
    return ' '.join(''.join(c.lower() if c.isalnum() else ' ' for c in unicodedata.normalize('NFKC',text)).split())

async def run(output):
    header=json.loads(await asyncio.to_thread(sys.stdin.readline))
    text=header.get('text')
    if header.get('type')!='passage' or not isinstance(text,str) or not text.strip() or len(text)>2000:
        raise ValueError('Invalid bounded speech passage')
    peer=RTCPeerConnection(RTCConfiguration(iceServers=[]))
    audio=RecordedAudio(b'');peer.addTrack(audio)
    channel=peer.createDataChannel('oai-events',ordered=True)
    closed=asyncio.Event();first_audio=False;final=None;response=None;finishing=None;pcm=bytearray();remote=set();failure=None
    @peer.on('track')
    def track_received(track):
        async def capture():
            nonlocal failure,first_audio
            convert=AudioResampler(format='s16',layout='mono',rate=24000)
            try:
                while True:
                    frame=await track.recv()
                    for part in convert.resample(frame):
                        content=bytes(part.planes[0])[:part.samples*2]
                        if not first_audio and any(content):first_audio=True;emit('firstAudio')
                        pcm.extend(content)
                        if len(pcm)>16*1024*1024:raise ValueError('Speech audio exceeded its bounded size')
            except MediaStreamError:pass
            except Exception as error:
                failure=str(error);emit('error',message=failure)
        task=asyncio.create_task(capture());remote.add(task);task.add_done_callback(remote.discard)
    async def finish(turn):
        if turn.get('role')!='assistant' or turn.get('error') or turn.get('status') not in (None,'completed') or words(turn.get('transcript',''))!=words(text):
            raise ValueError('Native speech did not confirm the complete original passage')
        await asyncio.sleep(.5)  # Drain media jitter after the explicit completed-turn event, not a silence completion guess.
        emit('finalizing')
        channel.send(json.dumps({'type':'session.close'}))
        await asyncio.wait_for(closed.wait(),15)
        if failure or not final or final.get('reason') not in ('client_request','close_requested'):
            raise ValueError('Native speech session did not finalize cleanly')
        samples=array.array('h');samples.frombytes(pcm)
        first=next((i for i,v in enumerate(samples) if v!=0),None)
        if first is None:raise ValueError('Provider confirmed text but returned no audio')
        last=len(samples)-1-next(i for i,v in enumerate(reversed(samples)) if v!=0)
        # Trim exact digital silence only, with 200ms safety padding. This never authorizes completion.
        begin=max(0,first-4800);end=min(len(samples),last+4801)
        content=samples[begin:end].tobytes()
        with wave.open(str(output),'wb') as target:
            target.setnchannels(1);target.setsampwidth(2);target.setframerate(24000);target.writeframes(content)
        Path(output).chmod(0o600)
        raw=Path(output).read_bytes()
        emit('rendered',confirmed=True,sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),durationMs=len(content)//48,
             proof={'version':1,'boundary':'turn.done+session.closed','transcriptMatches':True,
                    'textSha256':hashlib.sha256(text.encode()).hexdigest(),'reason':final.get('reason'),'usage':final.get('usage')})
    @channel.on('message')
    def message(raw):
        nonlocal final,response,finishing,failure
        try:
            event=json.loads(raw);kind=event.get('type')
            if kind=='session.started':emit('providerStarted')
            if kind=='error':raise ValueError('Native provider rejected speech rendering')
            if kind=='session.closed':final={k:event.get(k) for k in ('reason','usage')};closed.set()
            if kind=='turn.created' and event.get('turn',{}).get('role')=='assistant' and response is None:response=event['turn'].get('id')
            if kind=='turn.done' and response is not None and event.get('turn',{}).get('id')==response and finishing is None:
                finishing=asyncio.create_task(finish(event['turn']))
                def done(task):
                    if not task.cancelled() and task.exception():emit('error',message=str(task.exception()))
                finishing.add_done_callback(done)
        except Exception as error:failure=str(error);emit('error',message=failure)
    try:
        await peer.setLocalDescription(await peer.createOffer());emit('offer',sdp=peer.localDescription.sdp)
        while True:
            line=await asyncio.to_thread(sys.stdin.readline)
            if not line:break
            command=json.loads(line)
            if command.get('type')=='answer':await peer.setRemoteDescription(RTCSessionDescription(sdp=command['sdp'],type='answer'))
            elif command.get('type')=='shutdown':break
            else:raise ValueError('Invalid media control command')
    finally:
        audio.stop()
        if finishing and not finishing.done():finishing.cancel()
        if finishing:await asyncio.gather(finishing,return_exceptions=True)
        for task in list(remote):task.cancel()
        await asyncio.gather(*list(remote),return_exceptions=True)
        await peer.close();emit('peerClosed',state=peer.connectionState)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    try:asyncio.run(run(Path(args.output)))
    except Exception as error:emit('error',message=str(error));sys.exit(1)
