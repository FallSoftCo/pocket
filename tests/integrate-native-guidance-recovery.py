"""Actual Android Guide, stopped connector and original native receipt recovery."""
import hashlib,json,os,pwd,secrets,socket,subprocess,time,urllib.request,urllib.error
from pathlib import Path
from uuid import uuid4
from hollywood_backbone.bootstrap import initialize
from hollywood_backbone.store import Store,json_value
from lax_connector.host import enroll,resources
from lax_connector.linux_admin import provision
from lax_connector.journal import saved

PROXY="""
import sys,json
from pathlib import Path
from aiohttp import web,ClientSession
root=Path(sys.argv[1]);trace=[]
async def forward(req):
 if req.path=="/health":return web.json_response({"service":"owned-guidance-stop-fault"})
 body=await req.read();parsed=json.loads(body)if body else {}
 blocked=(req.method=="POST" and not(root/"allow-recovery").exists() and (
  "/connector/native-controls/"in req.path and parsed.get("state")!="dispatching"
  or req.path.endswith("/transition")and parsed.get("state")in("completed","failed","unknown","cancelled")))
 trace.append({"path":req.path,"method":req.method,"blocked":blocked,"state":parsed.get("state")});(root/"proxy-trace.json").write_text(json.dumps(trace))
 if blocked:return web.json_response({"error":"Owned qualification withholds receipt writes until connector stop"},status=503)
 async with ClientSession()as session:
  async with session.request(req.method,"http://127.0.0.1:18772"+req.path_qs,data=body,headers={k:v for k,v in req.headers.items()if k.lower()in("authorization","content-type")},allow_redirects=False)as response:
   return web.Response(body=await response.read(),status=response.status,content_type="application/json")
app=web.Application();app.router.add_route("*","/{tail:.*}",forward)
web.run_app(app,host="127.0.0.1",port=18773,print=None)
"""
REJECTED_INPUT="""
import asyncio,json,sys
from pathlib import Path
from aiohttp import ClientSession
from lax_connector.connector import Connector
from lax_connector.rpc import AppServer,RpcError
from lax_connector.journal import saved
async def main():
 v=json.load(sys.stdin);c=Connector(json.loads(Path(v["config"]).read_text()))
 async def event(*args):pass
 async def request(*args):raise RuntimeError("No native effects authorized beyond fresh fenced guidance")
 async with ClientSession()as c.session:
  row=await c.http("GET","native-controls/"+v["id"])
  c.active=await c.http("GET","deliveries/"+row["scope"]["deliveryId"]);c.thread=row["scope"]["nativeThread"]
  c.rpc=AppServer(c.config["binary"],Path(c.config["codexHome"]),c.state/"native",event,request)
  await c.rpc.start();original=c.rpc.call
  async def dropped(method,params,**kwargs):
   if method!="turn/steer":return await original(method,params,**kwargs)
   try:
    await original(method,params,**kwargs)
   except RpcError as error:
    saved(c.state/"qualification-native-rejection.json",{"code":error.code,"notSent":error.not_sent,"scope":row["scope"],"nativeReplyObserved":True,"freshControlId":v["id"]})
    raise RpcError("Owned qualification lost actual native rejection acknowledgement")
   raise RuntimeError("Fresh stale-turn negative unexpectedly admitted")
  c.rpc.call=dropped
  try:await c.controls.tick(row["scope"]["nativeTurn"])
  finally:await c.rpc.close()
  rejection=json.loads((c.state/"qualification-native-rejection.json").read_text());assert rejection["freshControlId"]==v["id"] and rejection["nativeReplyObserved"]
  observed=await c.http("GET","native-controls/"+v["id"]);assert observed["control"]["state"]=="unknown"
  print(json.dumps({"controlId":v["id"],"state":"unknown","freshNativeSteerAttempts":1,"newModelTurns":0}))
asyncio.run(main())
"""

def main():
 os.umask(0o077)
 assert os.geteuid()==0 and socket.gethostname()=="ozzz-aosp"
 root=Path(os.environ["GUIDANCE_EVIDENCE"]);root.mkdir(mode=0o711);root.chmod(0o711)
 runtime=Path(os.environ["GUIDANCE_RUNTIME"]);python=str(runtime/"venv/bin/python");repo=Path(os.environ["GUIDANCE_NEXTCOMP"]);dbpath=Path(os.environ["GUIDANCE_DATABASE_CONFIG"])
 adbpath="/home/ai/Android/Sdk/platform-tools/adb";a=pwd.getpwnam("aosp");children=[];unit=None;global_receipt=None;instrument=None
 for port in (18772,18773,18888):
  with socket.socket()as s:s.bind(("127.0.0.1",port))
 initialize(json.loads(dbpath.read_text()),root/"enterprise","Actual Android native guidance recovery")
 setup=json.loads((root/"enterprise/installation-intent.json").read_text());authority=setup["authorityId"];owner=setup["credentials"]["Owner"];glob=setup["credentials"]["Global"];room=setup["globalRoomId"];host=str(uuid4());prefix="guidance_"+authority.replace("-","")[:10]
 store=Store(json.loads(dbpath.read_text()))
 saved(root/"intent.json",{"scope":"One fresh actual Global turn and Android Guide; controlled connector stop; no original input replay","runtime":str(runtime),"productionChanged":False,"physicalPhone":False})
 saved(Path("/opt/hetzner-blue-team/coordination/announcements")/("native-guidance-recovery-"+authority+".json"),{"owner":"01a12c58-d21a-79b0-a512-0c3bc6dc0ee4","host":"ozzz-aosp","privateListeners":["127.0.0.1:18772","127.0.0.1:18773","127.0.0.1:18888"],"scope":"Fresh finite native/Android recovery only; other owners and physical phone untouched","runtime":str(runtime),"credentialIntent":str(root/"enterprise/installation-intent.json"),"rollback":"Stop owned API/proxy/gateway/controller/Global/instrumentation only; retain original state"})
 def launch(name,args,user=None,env=None,cwd=None):
  with(root/(name+".log")).open("ab")as f:p=subprocess.Popen((["runuser","-u",user,"--"]if user else [])+args,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,env=env,cwd=cwd)
  children.append(p);saved(root/(name+"-process.json"),{"pid":p.pid});return p
 def stop(p):
  if p and p.poll()is None:p.terminate();p.wait(timeout=20)
 def adb(args,data=None,check=True):
  p=subprocess.run(["runuser","-u","aosp","--",adbpath,"-s","emulator-5660",*args],input=data,capture_output=True,timeout=45)
  if check and p.returncode:raise RuntimeError("Owned emulator command failed")
  return p
 def phone_read(name):
  p=adb(["exec-out","run-as","co.fallsoft.pocket","cat","files/"+prefix+"-"+name+".json"],check=False)
  try:return json.loads(p.stdout)if not p.returncode else None
  except ValueError:return None
 def phone_write(name,value):
  adb(["shell","run-as","co.fallsoft.pocket","tee","files/"+prefix+"-"+name+".json"],json.dumps(value).encode())
 def ready(base,child):
  for _ in range(100):
   assert child.poll()is None,"Owned service stopped before health"
   try:
    with urllib.request.urlopen(base+"/health",timeout=2)as res:
     if res.status==200:return
   except OSError:time.sleep(.25)
  raise RuntimeError("Owned service health timed out")
 def call(base,path,body=None,token=None,name=None,method=None,status=200):
  if body is not None:
   assert name;saved(root/(name+"-intent.json"),{"path":path,"body":body})
  request=urllib.request.Request(base+path,method=method or("POST"if body is not None else"GET"),data=None if body is None else json.dumps(body).encode(),headers={"Content-Type":"application/json",**({"Authorization":"Bearer "+token}if token else{})})
  try:
   with urllib.request.urlopen(request,timeout=15)as response:code=response.status;value=json.load(response)
  except urllib.error.HTTPError as e:code=e.code;value=json.load(e)
  assert code==status,(path,code,value,status)
  if name:saved(root/(name+"-receipt.json"),value)
  return value
 def api(path,body=None,name=None,method=None,status=200):
  return call("http://127.0.0.1:18772","/hollywood/v3/"+path,body,owner["token"],name,method,status)
 def wait(test,seconds=180):
  end=time.monotonic()+seconds
  while time.monotonic()<end:
   value=test()
   if value:return value
   if instrument and instrument.poll()is not None and not phone_read("result"):
    raise RuntimeError("Android journey ended without acceptance; inspect original instrumentation output")
   time.sleep(.4)
  raise RuntimeError("Owned guidance condition timed out")
 def cli(name,action):
  p=subprocess.run(["runuser","-u",global_receipt["linuxUser"],"--",python,"-m","lax_connector.connector","--config",global_receipt["connectorConfig"],*action],capture_output=True,text=True,cwd=global_receipt["home"],timeout=90)
  (root/(name+".stderr")).write_text(p.stderr);(root/(name+".stdout")).write_text(p.stdout);assert p.returncode==0,(name,p.stderr[-500:])
  return json.loads(p.stdout)
 try:
  assert adb(["shell","getprop","ro.boot.qemu.avd_name"]).stdout.decode().strip()=="harness_native_room"
  assert adb(["shell","getprop","sys.boot_completed"]).stdout.decode().strip()=="1"
  before=adb(["exec-out","run-as","co.fallsoft.pocket","cat","shared_prefs/pocket.xml"],check=False);(root/"private-android-before.xml").write_bytes(before.stdout)
  for apk in [repo/"android/app/build/outputs/apk/qualification/app-qualification.apk",repo/"android/app/build/outputs/apk/androidTest/qualification/app-qualification-androidTest.apk"]:
   saved(root/(apk.name+"-install-intent.json"),{"path":str(apk),"sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"serial":"emulator-5660","replace":True})
   p=adb(["install","-r",str(apk)]);(root/(apk.name+"-install-receipt.txt")).write_bytes(p.stdout+p.stderr)
  api_process=launch("api",[python,"-m","hollywood_backbone.server","--database-config",str(dbpath),"--port","18772"]);ready("http://127.0.0.1:18772",api_process)
  proxy=root/"proxy.py";proxy.write_text(PROXY);proxy_process=launch("proxy",[python,str(proxy),str(root)]);ready("http://127.0.0.1:18773",proxy_process)
  viewer={"principalId":str(uuid4()),"token":secrets.token_urlsafe(40)}
  api("principals",{"id":viewer["principalId"],"name":"Guidance viewer","kind":"human","enrollmentCredential":viewer["token"]},"viewer")
  api("rooms/"+room+"/membership",{"principalId":viewer["principalId"],"can_read":True,"can_post":False,"can_request":False,"revision":0},"viewer-grant","PUT")
  data=root/"gateway-data";data.mkdir(mode=0o700);os.chown(data,a.pw_uid,a.pw_gid)
  def binding(label):return {"url":"http://127.0.0.1:18773","tokenFile":str(data/(label+".key")),"authorityId":authority,"principalId":owner["principalId"]if label=="owner"else viewer["principalId"],"defaultRoomId":room}
  config={"format":"hollywood-nextcomp-binding/v1","installationId":str(uuid4()),"devices":{"owner":binding("owner")}}
  seed="import{readFileSync,writeFileSync}from'node:fs';import{openStore}from'"+(repo/"server/store.mjs").as_uri()+"';const s=JSON.parse(readFileSync(0,'utf8'));const{db,secrets}=openStore(s.data);db.close();for(const[k,v]of Object.entries(s.keys))writeFileSync(s.data+'/'+k+'.key',v,{mode:0o600});writeFileSync(s.data+'/hollywood.json',JSON.stringify(s.config),{mode:0o600});console.log(JSON.stringify({adminToken:secrets.adminToken}));"
  seeded=subprocess.run(["runuser","-u","aosp","--","node","--input-type=module","-e",seed],input=json.dumps({"data":str(data),"keys":{"owner":owner["token"],"viewer":viewer["token"]},"config":config}),text=True,capture_output=True,cwd=repo);(root/"gateway-bootstrap.stderr").write_text(seeded.stderr);seeded.check_returncode();admin=json.loads(seeded.stdout)["adminToken"]
  env={**os.environ,"PORT":"18888","POCKET_DATA":str(data),"POCKET_EXECUTION_BACKEND":"hollywood","CODEX_SOCKET":str(root/"no-stock-runtime.sock"),"POCKET_CODEX_COMMAND":""}
  gateway=launch("gateway-bootstrap",["node",str(repo/"server/index.mjs")],user="aosp",env=env,cwd=repo);ready("http://127.0.0.1:18888",gateway)
  pairs={}
  for label in("owner","viewer"):
   code=call("http://127.0.0.1:18888","/api/pairing",{},admin,label+"-pairing-code")
   pairs[label]=call("http://127.0.0.1:18888","/api/pair",{"code":code["code"],"name":prefix+"-"+label},name=label+"-pairing")
  stop(gateway)
  config["devices"].update({pairs[label]["id"]:binding(label)for label in("owner","viewer")});(data/"hollywood.json").write_text(json.dumps(config));os.chown(data/"hollywood.json",a.pw_uid,a.pw_gid)
  gateway=launch("gateway",["node",str(repo/"server/index.mjs")],user="aosp",env=env,cwd=repo);ready("http://127.0.0.1:18888",gateway)
  global_receipt=provision({"installationIntent":str(root/"enterprise/installation-intent.json"),"approvedModelAuthFile":os.environ["GUIDANCE_APPROVED_AUTH"],"binary":str(runtime/"native/bin/codex"),"interpreter":python,"hollywoodUrl":"http://127.0.0.1:18773","hostId":host,"model":"gpt-6.1-sol","effort":"xhigh"});saved(root/"global-provision-receipt.json",global_receipt)
  api("placements",{"hostId":host,"name":"Android guidance OZZZ","identityFingerprint":hashlib.sha256(Path("/etc/machine-id").read_bytes()).hexdigest(),"capabilities":{"localProvisioning":True},"agentId":glob["principalId"]},"placement")
  enrolled=enroll({"authorityId":authority,"hostId":host,"hollywoodUrl":"http://127.0.0.1:18773","administratorCredential":owner,"policy":{"maxConcurrent":resources()["cpuSlots"],"reservedInteractive":1 if resources()["cpuSlots"]>1 else 0,"controlAgentIds":[glob["principalId"]],"memoryPerTurnBytes":2*1024**3,"reserveMemoryBytes":4*1024**3,"sampleMaxAgeSeconds":20,"connectorMaxAgeSeconds":20}});saved(root/"host-enrollment.json",enrolled)
  subprocess.run([python,"-m","lax_connector.host","--once",enrolled["configPath"]],capture_output=True,check=True)
  launch("host-controller",[python,"-m","lax_connector.host","--serve",enrolled["configPath"]])
  unit="lax-guidance-global-"+authority.replace("-","")[:12];saved(root/"global-service-intent.json",{"unit":unit})
  subprocess.run(["systemd-run","--unit="+unit,"--uid="+global_receipt["linuxUser"],"--property=UMask=0077","--property=MemoryMax=3G","--property=RuntimeMaxSec=900","--property=WorkingDirectory="+global_receipt["home"]+"/work",python,"-m","lax_connector.connector","--config",global_receipt["connectorConfig"],"--serve"],capture_output=True,check=True)
  marker="ANDROID_GUIDANCE_"+secrets.token_hex(12)
  request="Perform one finite native guidance deliverable. Read hollywood_briefing first. Run one shell command that writes ready followed by newline to guidance-ready.txt in your workspace and sleeps 60 seconds. This gives the human a bounded opportunity to guide this original turn. After that command, apply the human guidance and write exactly its supplied marker plus newline to guidance-proof.txt, verify the actual bytes/hash and report the artifact. Keep your ongoing native role active. Do not invent the marker, ask a question, delegate, self-chain, schedule or touch historical work."
  guidance="The marker for this original task is "+marker+". Write exactly that marker and newline to guidance-proof.txt, verify it and complete this bounded request. Do not change the ongoing Global role."
  settings={"case":prefix,"base":"http://127.0.0.1:18880","ownerProfile":prefix+"_owner","viewerProfile":prefix+"_viewer","ownerToken":pairs["owner"]["token"],"viewerToken":pairs["viewer"]["token"],"ownerDevice":pairs["owner"]["id"],"viewerDevice":pairs["viewer"]["id"],"globalRoom":room,"globalAgent":glob["principalId"],"request":request,"guidance":guidance}
  saved(root/"android-settings.json",settings);saved(root/"expected-marker.json",{"marker":marker})
  adb(["reverse","tcp:18880","tcp:18888"]);adb(["shell","run-as","co.fallsoft.pocket","tee","files/native-guidance-settings.json"],json.dumps(settings).encode())
  instrument=launch("android-journey",["runuser","-u","aosp","--",adbpath,"-s","emulator-5660","shell","am","instrument","-w","-r","-e","class","co.fallsoft.pocket.HollywoodGuidanceRecoveryJourneyTest","co.fallsoft.pocket.test/androidx.test.runner.AndroidJUnitRunner"])
  wait(lambda:phone_read("admission"))
  wait(lambda:(Path(global_receipt["home"])/"work/guidance-ready.txt").exists(),180);phone_write("native-ready",{"ready":True})
  captured=wait(lambda:phone_read("guidance-intent"),120);saved(root/"android-guidance-intent.json",captured)
  scope=captured["body"]["scope"];control=captured["body"]["id"]
  def native_complete():
   for f in(Path(global_receipt["home"])/"codex-home/sessions").rglob("*.jsonl"):
    for line in f.read_text().splitlines():
     try:v=json.loads(line)
     except ValueError:continue
     if v.get("type")=="event_msg"and v.get("payload",{}).get("type")=="task_complete":return True
   return False
  wait(native_complete,180)
  artifact=Path(global_receipt["home"])/"work/guidance-proof.txt";assert artifact.read_text()==marker+"\n"
  subprocess.run(["systemctl","stop",unit],capture_output=True,check=True)
  state=Path(global_receipt["home"])/"state";journal=state/"execution.json";before_hash=hashlib.sha256(journal.read_bytes()).hexdigest()
  original=api("native-controls/"+control);assert original["control"]["state"]in("dispatching","unknown")
  saved(root/"before-recovery.json",original);(root/"allow-recovery").write_text("original metadata recovery authorized\n")
  first=cli("guidance-only-recovery",["--reconcile-guidance",control]);assert first["guidance"][0]["state"]=="native_recorded";assert hashlib.sha256(journal.read_bytes()).hexdigest()==before_hash
  # A separate fresh no-op input reaches a cold native runtime, whose actual rejection is hidden.
  # No active native turn is loaded or resumed; the connector must retain unknown instead of retrying.
  unknown=str(uuid4());api("deliveries/"+scope["deliveryId"]+"/guidance",{"id":unknown,"operationId":"fresh-negative:"+str(uuid4()),"scope":scope,"body":"Fresh no-op guidance for a stopped original turn; preserve earlier work."},"fresh-unknown")
  helper=runtime/"qualification-rejected-guidance.py";helper.write_text(REJECTED_INPUT);helper.chmod(0o644)
  negative=subprocess.run(["runuser","-u",global_receipt["linuxUser"],"--",python,str(helper)],input=json.dumps({"config":global_receipt["connectorConfig"],"id":unknown}),capture_output=True,text=True,cwd=global_receipt["home"],timeout=60)
  (root/"native-rejection.stderr").write_text(negative.stderr);(root/"native-rejection.stdout").write_text(negative.stdout);assert negative.returncode==0,negative.stderr[-500:]
  settled=cli("execution-recovery",["--reconcile"]);assert settled["state"]=="completed";assert any(x["controlId"]==unknown and x["held"]for x in settled["guidance"])
  repeated=cli("terminal-guidance-recovery",["--reconcile"]);assert repeated["state"]=="completed"and repeated["guidance"][0]["held"]
  final=api("native-controls/"+control);assert final["control"]["state"]=="native_recorded"
  held=api("native-controls/"+unknown);assert held["control"]["state"]=="unknown"and not held["nativeMessageRecorded"]
  saved(root/"recovery-result.json",{"guidanceOnly":first,"execution":settled,"repeatedTerminal":repeated,"unknownControl":unknown,"unknownStillHeld":True,"originalJournalUnchangedByGuidanceOnly":True})
  phone_write("reconciled",{"unknownControl":unknown,"originalControl":control})
  result=wait(lambda:phone_read("result"),150);saved(root/"android-result.json",result)
  instrument.wait(timeout=20);output=(root/"android-journey.log").read_text();assert "FAILURES"not in output and"OK (1 test)"in output
  with store.pool.connection()as c:
   deliveries=c.execute("SELECT id,state,native_thread,native_turn FROM hb_delivery WHERE authority=%s",(authority,)).fetchall()
  assert len(deliveries)==1 and deliveries[0]["state"]=="completed"and deliveries[0]["native_turn"]==scope["nativeTurn"]
  proof={"passed":True,"realNativeTurns":1,"androidGuideUi":True,"artifact":{"path":str(artifact),"sha256":hashlib.sha256(artifact.read_bytes()).hexdigest()},"controlRecoveredAfterConnectorStop":True,"originalThreadTurnRetained":True,"metadataRecoveryNewModelTurns":0,"unknownFreshNativeRejectionHeldWithoutRetry":True,"originalJournalUnchangedByGuidanceOnly":True,"androidResult":result,"deliveries":json_value(deliveries),"productionChanged":False,"physicalPhoneChanged":False}
  saved(root/"result.json",proof);print(json.dumps(proof),flush=True)
 finally:
  if unit:subprocess.run(["systemctl","stop",unit],capture_output=True)
  for child in reversed(children):stop(child)
  saved(root/"cleanup.json",{"ownedChildrenStopped":all(p.poll()is not None for p in children),"globalStopped":True,"productionChanged":False,"physicalPhoneChanged":False})
  store.close()

if __name__=="__main__":main()
