"""Isolated canonical HTTP/PG fixture. No native runtime or execution starts."""
import asyncio,json,os,secrets,signal
from pathlib import Path
from uuid import uuid4
from aiohttp.test_utils import TestServer
from hollywood_backbone.store import Store
from hollywood_backbone.server import application

async def main():
    os.umask(0o077)
    root=Path(os.environ['ROOM_INTENT_QA'])
    store=Store(json.loads((root/'database.json').read_text()))
    store.migrate()
    uid=lambda:str(uuid4())
    authority,source,agent,peer=[uid() for _ in range(4)]
    credentials={k:{'principalId':uid(),'token':secrets.token_urlsafe(40)} for k in ('Owner','Global')}
    store.bootstrap(authority,'ISOLATED room-intent QA',{'globalRoomId':uid(),'credentials':credentials})
    owner=credentials['Owner']['principalId']
    agent_token,peer_token=secrets.token_urlsafe(40),secrets.token_urlsafe(40)
    store.principal(owner,agent,'ISOLATED fixture representative','agent',agent_token)
    store.principal(owner,peer,'ISOLATED private-audience observer','agent',peer_token)
    store.create_room(owner,source,'ISOLATED existing project','Synthetic room-intent path; no models',agent)
    store.membership(owner,source,peer,{'can_read':True,'can_post':True,'can_request':True},0)
    original=store.post(owner,source,uid(),{'kind':'message','body':'PRIVATE_ROOM_INTENT_CANARY','audience':'direct','recipientId':agent})['event']
    server=TestServer(application(store),host='127.0.0.1');await server.start_server()
    (root/'canonical.private.json').write_text(json.dumps({'url':str(server.make_url('/')).rstrip('/'),'authority':authority,
       'principal':owner,'token':credentials['Owner']['token'],'room':source,'agent':agent,'peerToken':peer_token,'privateSource':original['id']}))
    stop=asyncio.Event();asyncio.get_running_loop().add_signal_handler(signal.SIGTERM,stop.set)
    try:await stop.wait()
    finally:
        with store.pool.connection() as c:
            events=c.execute("SELECT id,author,room,kind,audience,body FROM hb_event WHERE authority=%s AND kind='request'",(authority,)).fetchall()
            rooms=c.execute('SELECT id,name FROM hb_room WHERE authority=%s',(authority,)).fetchall()
        (root/'canonical-result.private.json').write_text(json.dumps({'requests':events,'rooms':rooms,'nativeOrModelStarted':False},default=str,indent=2))
        await server.close();store.close()

if __name__=='__main__':asyncio.run(main())
