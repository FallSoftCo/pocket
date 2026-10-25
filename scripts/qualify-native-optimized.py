from pathlib import Path
import os,json,time,subprocess,urllib.request,xml.etree.ElementTree as ET,uuid,hashlib,re,socket

def qualify_native_stock(*,root,q,data,adb,launch):
 assert adb('shell','getprop','ro.boot.qemu.avd_name').strip()==b'nextcomp_env_qa','Owned NextComp emulator required'
 manifest=json.loads((data/'manifest.json').read_text());build=json.loads((data/'build.json').read_text())
 variant=next(v for v in manifest['variants'] if v['signerSha256']=='22ecabe28e15be3b5ce9b718c8d85932d9d4855506f215a29d3828d2304c6f31')
 apk=data/variant['path'];assert hashlib.sha256(apk.read_bytes()).hexdigest()==variant['sha256']
 result={'state':'running','kind':'exact-optimized-native-canonical','versionCode':manifest['versionCode'],'apkSha256':variant['sha256'],'productionSourceSha':build['productionSourceSha'],'phoneTouched':False}
 path=q/'optimized-native-canonical.json'
 def save():path.write_text(json.dumps(result,indent=2)+'\n')
 save();port=20143;fixture=None;original=None
 def qa(name):
  req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/_qa/'+name,headers={'Authorization':'Bearer synthetic-system76'},method='POST')
  with urllib.request.urlopen(req,timeout=4)as response:return json.load(response)
 def dump():
  # A completed framework instrumentation can leave UIAutomation briefly busy.
  # Retain every failed read; retry only this observational capture, never taps,
  # commands, fixture transitions or app launches.
  for attempt in range(3):
   command=adb('shell','uiautomator','dump','/sdcard/owned-native150.xml')
   raw=adb('exec-out','cat','/sdcard/owned-native150.xml')
   try:
    assert ET.fromstring(raw).tag=='hierarchy'
    (q/'last-visible-hierarchy.xml').write_bytes(raw)
    return raw
   except (ET.ParseError,AssertionError):
    stem=q/('hierarchy-capture-'+str(time.time_ns()))
    stem.with_suffix('.stdout').write_bytes(command)
    stem.with_suffix('.raw').write_bytes(raw)
    if attempt<2:time.sleep(.25)
  raise RuntimeError('UIAutomation returned no valid hierarchy; raw capture retained at '+str(stem))
 def texts(tree,text):return[n for n in tree.iter('node')if n.get('text')==text]
 def wait(text,timeout=25,contains=False):
  deadline=time.monotonic()+timeout
  while time.monotonic()<deadline:
   xml=dump();tree=ET.fromstring(xml)
   if any((text in n.get('text','') if contains else n.get('text')==text) for n in tree.iter('node')):return xml,tree
   time.sleep(.15)
  raise RuntimeError('Exact optimized native UI did not show '+text)
 def tap(node):
  a=list(map(int,re.findall(r'-?\d+',node.get('bounds',''))));assert len(a)==4
  adb('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2))
 try:
  s=socket.socket();s.bind(('127.0.0.1',port));s.close()
  fixture=launch(['node','tests/fixtures/native-canonical-transcript.mjs'],'native-canonical-fixture',{**os.environ,'PORT':str(port)})
  deadline=time.monotonic()+10
  while True:
   try:qa('state');break
   except Exception:
    if time.monotonic()>deadline:raise
    time.sleep(.1)
  adb('shell','am','force-stop','co.fallsoft.pocket');adb('install','-r',str(data/'qualification.apk'))
  original=adb('exec-out','run-as','co.fallsoft.pocket','cat','shared_prefs/pocket.xml');assert ET.fromstring(original).tag=='map'
  # The framework probe captures the live canonical room before restoring its
  # profile. Harvest that exact image before a cold AVD cleanup can discard it.
  room_capture=adb('exec-out','run-as','co.fallsoft.pocket','cat','files/optimized-native-room.png')
  room_stamp=float(adb('shell','run-as','co.fallsoft.pocket','stat','-c','%Y','files/optimized-native-room.png'))
  assert room_capture.startswith(bytes([137,80,78,71])) and room_stamp>=build['finishedAt']/1000-2,'Fresh optimized room capture required'
  (data/'optimized-native-room.png').write_bytes(room_capture)

  prefs=ET.Element('map')
  for key,value in {'server':'http://127.0.0.1:18880','token':'synthetic-system76-'+str(uuid.uuid4()),'deviceId':'synthetic-device-a','draft:same-thread':'Native optimized retained draft'}.items():ET.SubElement(prefs,'string',name=key).text=value
  adb('shell','-T',"run-as co.fallsoft.pocket sh -c 'cat > shared_prefs/pocket.xml'",input=ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
  adb('reverse','tcp:18880','tcp:'+str(port));adb('install','-r',str(apk))
  package=adb('shell','pm','path','co.fallsoft.pocket').decode().strip().removeprefix('package:')
  assert adb('shell','sha256sum',package).decode().split()[0]==variant['sha256']
  start=time.monotonic();adb('shell','am','start','-n','co.fallsoft.pocket/.MainActivity','--es','thread','same-thread')
  xml,tree=wait('Native answer one');result['coldVisibleMs']=round((time.monotonic()-start)*1000);result['timingMeaning']='adb launch to first matching UI snapshot; not optical latency';save()
  assert len(texts(tree,'Native request one'))==1 and len(texts(tree,'Native answer one'))==1
  (data/'optimized-native-canonical-initial.xml').write_bytes(xml)
  qa('advance-held');time.sleep(.5);xml=dump();tree=ET.fromstring(xml)
  assert not texts(tree,'Native answer two') and not texts(tree,'Native request two'),'Unpositioned pair painted in exact release'
  qa('release');xml,tree=wait('Native answer two');users=texts(tree,'Native request two');answers=texts(tree,'Native answer two')
  assert len(users)==len(answers)==1
  u=list(map(int,re.findall(r'-?\d+',users[0].get('bounds'))));a=list(map(int,re.findall(r'-?\d+',answers[0].get('bounds'))));assert u[3]<=a[1],'Exact optimized pair is misordered'
  qa('question');xml,tree=wait('Native pending question');skip=texts(tree,'Skip')or texts(tree,'Salta');assert len(skip)==1;tap(skip[0])
  deadline=time.monotonic()+10
  while qa('state')['answers']!=1:
   if time.monotonic()>deadline:raise RuntimeError('Exact optimized question answer did not route')
   time.sleep(.1)
  qa('disconnect');adb('shell','input','keyevent','KEYCODE_BACK');qa('recover')
  xml,tree=wait('Native canonical work');xml,tree=wait('Native answer two',contains=True)
  # Idle status and preview share one text node. Open the actual Chat region,
  # not the editable title or an assumed standalone preview node.
  openChat=[n for n in tree.iter('node')if n.get('content-desc')=='Open Chat for Native canonical work']
  assert len(openChat)==1,'Native catalog lacks its explicit Chat action'
  beforeReopen=len(qa('state')['reads']);tap(openChat[0]);xml,tree=wait('Native request two')
  deadline=time.monotonic()+15
  while len(qa('state')['reads'])<=beforeReopen or texts(tree,'Native pending question'):
   if time.monotonic()>deadline:raise RuntimeError('Exact optimized reopen did not reconcile current state')
   time.sleep(.1);xml=dump();tree=ET.fromstring(xml)
  assert len(texts(tree,'Native request two'))==len(texts(tree,'Native answer two'))==1
  (data/'optimized-native-canonical.xml').write_bytes(xml);(data/'optimized-native-canonical.png').write_bytes(adb('exec-out','screencap','-p'))
  state=qa('state');assert state['legacyReads']==state['positionReads']==state['workSubmissions']==0 and state['answers']==1
  result.update(state='complete',canonicalPairOrder=True,reopenOriginalPairExactlyOnce=True,questionSkipOnce=True,legacyReads=0,positionRepair=0,workSubmissions=0,reads=len(state['reads']),finishedAt=time.time());save()
 except BaseException as error:
  result.update(state='failed',error=str(error));save()
  try:(q/'native-canonical-failure.png').write_bytes(adb('exec-out','screencap','-p'))
  except Exception:pass
  raise
 finally:
  try:
   if fixture is not None and fixture.poll()is None:qa('release')
   if original is not None:
    adb('shell','am','force-stop','co.fallsoft.pocket');adb('install','-r',str(data/'qualification.apk'))
    adb('shell','-T',"run-as co.fallsoft.pocket sh -c 'cat > shared_prefs/pocket.xml'",input=original)
    result['retainedQaPreferencesRestored']=adb('exec-out','run-as','co.fallsoft.pocket','cat','shared_prefs/pocket.xml')==original
    if not result['retainedQaPreferencesRestored']:result['state']='failed';save();raise RuntimeError('Native optimized probe did not restore retained QA preferences')
  finally:
   if fixture is not None and fixture.poll()is None:
    fixture.terminate();fixture.wait(timeout=15)
   result['fixtureStopped']=fixture is None or fixture.poll()is not None;save()
 return result
