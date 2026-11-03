"""Isolated canonical HTTP/PG fixture. No native runtime or execution starts."""
import asyncio,json,os,secrets,signal
from pathlib import Path
from uuid import uuid4
from aiohttp.test_utils import TestServer
from aiohttp import ClientSession
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
    human,human_token=uid(),secrets.token_urlsafe(40)
    store.principal(owner,human,'ISOLATED ordinary human','human',human_token)
    agent_token,peer_token=secrets.token_urlsafe(40),secrets.token_urlsafe(40)
    store.principal(owner,agent,'ISOLATED fixture representative','agent',agent_token)
    store.principal(owner,peer,'ISOLATED private-audience observer','agent',peer_token)
    store.create_room(owner,source,'ISOLATED existing project','Synthetic room-intent path; no models',agent)
    store.membership(owner,source,human,{'can_read':True,'can_post':True,'can_request':True},0)
    store.membership(owner,source,peer,{'can_read':True,'can_post':True,'can_request':True},0)
    for n in range(24):
        store.post(human,source,uid(),{'kind':'message','body':f'Canonical useful finding {n:02}','audience':'room'})
    original=store.post(human,source,uid(),{'kind':'message','body':'PRIVATE_ROOM_INTENT_CANARY','audience':'direct','recipientId':agent})['event']
    server=TestServer(application(store),host='127.0.0.1');await server.start_server()
    role_before=store.role_read(owner,agent)
    readonly,readonly_token=uid(),secrets.token_urlsafe(40)
    store.principal(owner,readonly,'ISOLATED read-only human','human',readonly_token)
    store.membership(owner,source,readonly,{'can_read':True,'can_post':True,'can_request':False},0)
    outsider,inactive=uid(),uid()
    for label,identity in (('outsider',outsider),('inactive',inactive)):
        store.principal(owner,identity,'ISOLATED non-owner '+label,'agent',secrets.token_urlsafe(40))
    with store.pool.connection() as c:
        c.execute('UPDATE hb_principal SET active=false WHERE id=%s',(inactive,))
    negative=[]
    async with ClientSession() as session:
        cases=[('missing-source',human_token,{},403),('unavailable-source',human_token,{'sourceRoomId':uid()},403),
               ('owner-not-member',human_token,{'sourceRoomId':source,'ownerId':outsider},403),
               ('owner-inactive',human_token,{'sourceRoomId':source,'ownerId':inactive},403),
               ('source-not-requestable',readonly_token,{'sourceRoomId':source},403),
               ('admin-field-injection',human_token,{'sourceRoomId':source,'administrator':True},400)]
        for label,token,delta,status in cases:
            payload={'id':uid(),'operationId':uid(),'name':'ISOLATED denied '+label,'purpose':'Negative policy fixture','ownerId':agent,**delta}
            async with session.post(server.make_url('/hollywood/v3/rooms'),json=payload,headers={'Authorization':'Bearer '+token}) as response:
                value=await response.json();assert response.status==status,(label,response.status,value)
                negative.append({'case':label,'status':response.status})
        async with session.put(server.make_url('/hollywood/v3/rooms/'+source+'/membership'),json={'operationId':uid(),'principalId':agent,'can_read':False,'can_post':False,'can_request':False,'revision':1},headers={'Authorization':'Bearer '+human_token}) as response:
            assert response.status==403;negative.append({'case':'unrelated-administration-still-denied','status':response.status})
    assert store.catalog(readonly)['roomControl']['createRoom']['allowed'] is False
    (root/'canonical-policy-negatives.json').write_text(json.dumps({'passed':True,'cases':negative,'readOnlyHumanAdvertisesNoCreation':True}))
    (root/'canonical.private.json').write_text(json.dumps({'url':str(server.make_url('/')).rstrip('/'),'authority':authority,
       'principal':human,'token':human_token,'room':source,'agent':agent,'peerToken':peer_token,'privateSource':original['id']}))
    stop=asyncio.Event();asyncio.get_running_loop().add_signal_handler(signal.SIGTERM,stop.set)
    try:await stop.wait()
    finally:
        with store.pool.connection() as c:
            events=c.execute("SELECT id,author,room,kind,audience,body FROM hb_event WHERE authority=%s AND kind='request'",(authority,)).fetchall()
            rooms=c.execute('SELECT id,name FROM hb_room WHERE authority=%s',(authority,)).fetchall()
        self_principal=store.principal_read(human,human)
        assert store.role_read(owner,agent)==role_before
        assert self_principal['administrator'] is False
        (root/'canonical-result.private.json').write_text(json.dumps({'requests':events,'rooms':rooms,'humanAdministrator':self_principal['administrator'],'existingOwnerRoleUnchanged':True,'canonicalPolicyNegatives':negative,'nativeOrModelStarted':False},default=str,indent=2))
        await server.close();store.close()

if __name__=='__main__':asyncio.run(main())
