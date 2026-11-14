from pathlib import Path
import os,json,time,subprocess,urllib.request,xml.etree.ElementTree as ET,uuid,hashlib,re,socket

# Package replacement can start Application from install broadcasts. Stop it
# AFTER every install before touching preferences or claiming a cold launch.
def install_stopped(adb,apk_path):
 adb('shell','am','force-stop','co.fallsoft.pocket')
 adb('install','-r',str(apk_path))
 adb('shell','am','force-stop','co.fallsoft.pocket')
def read_stopped_preferences(adb):
 adb('shell','am','force-stop','co.fallsoft.pocket')
 values={}
 for name in ('pocket.xml','pocket.xml.bak'):
  command="run-as co.fallsoft.pocket sh -c 'if [ -e shared_prefs/"+name+" ]; then printf \"present\\n\"; cat shared_prefs/"+name+"; else printf \"absent\\n\"; fi'"
  raw=adb('shell','-T',command)
  marker,separator,value=raw.partition(b'\n')
  if not separator or marker not in (b'present',b'absent'):raise RuntimeError('Cannot read retained QA preference '+name)
  values[name]=value if marker==b'present' else None
 return values
def write_stopped_preferences(adb,values):
 if set(values)!= {'pocket.xml','pocket.xml.bak'}:raise ValueError('Provide both main and AtomicFile backup preference states')
 adb('shell','am','force-stop','co.fallsoft.pocket')
 adb('shell','run-as','co.fallsoft.pocket','mkdir','-p','shared_prefs')
 # Android gives the AtomicFile backup precedence over the main file.
 adb('shell','run-as','co.fallsoft.pocket','rm','-f','shared_prefs/pocket.xml.bak')
 for name,value in values.items():
  if value is None:adb('shell','run-as','co.fallsoft.pocket','rm','-f','shared_prefs/'+name)
  else:adb('shell','-T',"run-as co.fallsoft.pocket sh -c 'cat > shared_prefs/"+name+"'",input=value)
 if read_stopped_preferences(adb)!=values:raise RuntimeError('QA main/AtomicFile backup preference readback differs')

def save_stopped_preferences(adb,q):
 retained=read_stopped_preferences(adb)
 for value in retained.values():
  if value is not None:assert ET.fromstring(value).tag=='map','Retained QA preferences must be XML maps'
 # Keep exact private recovery bytes even if a later ADB restore fails.
 backup=q/('retained-qa-preferences-'+uuid.uuid4().hex);backup.mkdir(mode=0o700)
 for name,value in retained.items():
  if value is not None:(backup/name).write_bytes(value);(backup/name).chmod(0o600)
 evidence={name:None if value is None else {'path':str((backup/name).relative_to(q)),'sha256':hashlib.sha256(value).hexdigest()} for name,value in retained.items()}
 return retained,evidence

def qualify_native_stock(*,root,q,data,adb,launch,avd_name="nextcomp_env_qa",signer_sha="22ecabe28e15be3b5ce9b718c8d85932d9d4855506f215a29d3828d2304c6f31",room_capture=None,room_evidence=None):
 assert re.fullmatch(r'(?:nextcomp_[a-zA-Z0-9_]+|harness_native_room)',avd_name)
 assert adb('shell','getprop','ro.boot.qemu.avd_name').decode().strip()==avd_name,'Owned NextComp emulator required'
 manifest=json.loads((data/'manifest.json').read_text());build=json.loads((data/'build.json').read_text())
 variant=next(v for v in manifest['variants'] if v['signerSha256']==signer_sha)
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
  install_stopped(adb,data/'qualification.apk')
  original,result['retainedQaPreferenceFiles']=save_stopped_preferences(adb,q);save()
  prefs=ET.Element('map')
  for key,value in {'server':'http://127.0.0.1:18880','token':'synthetic-system76-'+str(uuid.uuid4()),'deviceId':'synthetic-device-a','draft:same-thread':'Native optimized retained draft'}.items():ET.SubElement(prefs,'string',name=key).text=value
  write_stopped_preferences(adb,{'pocket.xml':ET.tostring(prefs,encoding='utf-8',xml_declaration=True),'pocket.xml.bak':None})
  result['isolatedQaPreferencesVerified']=True;save()
  adb('reverse','tcp:18880','tcp:'+str(port));install_stopped(adb,apk)
  package=adb('shell','pm','path','co.fallsoft.pocket').decode().strip().removeprefix('package:')
  assert adb('shell','sha256sum',package).decode().split()[0]==variant['sha256']
  start=time.monotonic();adb('shell','am','start','-n','co.fallsoft.pocket/.MainActivity','--es','thread','same-thread')
  xml,tree=wait('Native answer one');result['coldVisibleMs']=round((time.monotonic()-start)*1000);result['timingMeaning']='adb launch to first matching UI snapshot; not optical latency';save()
  assert len(texts(tree,'Native request one'))==1 and len(texts(tree,'Native answer one'))==1
  (data/'optimized-native-canonical-initial.xml').write_bytes(xml)
  (data/'optimized-native-initial.png').write_bytes(adb('exec-out','screencap','-p'))
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
  # Answering a question does not dismiss its review drawer. Back correctly
  # closes that control first; close it through its public action before testing
  # conversation-to-catalog Back. Do not weaken the subsequent source assertions.
  xml=dump();tree=ET.fromstring(xml)
  close=[n for n in tree.iter('node')if n.get('content-desc')=='Close questions']
  if close:
   assert len(close)==1,'Ambiguous questions close action'
   tap(close[0]);deadline=time.monotonic()+8
   while True:
    xml=dump();tree=ET.fromstring(xml)
    if not any(n.get('content-desc')=='Close questions'for n in tree.iter('node')):break
    if time.monotonic()>deadline:raise RuntimeError('Questions drawer did not close before Back navigation')
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
  (data/'optimized-native-catalog.png').write_bytes(adb('exec-out','screencap','-p'))
  result.update(state='complete',codexCatalogDrawn=True,optimizedPayloadVerified=True,canonicalPairOrder=True,reopenOriginalPairExactlyOnce=True,questionSkipOnce=True,legacyReads=0,positionRepair=0,workSubmissions=0,reads=len(state['reads']),finishedAt=time.time());save()
 except BaseException as error:
  result.update(state='failed',error=str(error));save()
  try:(q/'native-canonical-failure.png').write_bytes(adb('exec-out','screencap','-p'))
  except Exception:pass
  raise
 finally:
  try:
   try:
    if fixture is not None and fixture.poll()is None:qa('release')
   finally:
    if original is not None:
     result['retainedQaPreferencesRestored']=False;save()
     install_stopped(adb,data/'qualification.apk')
     write_stopped_preferences(adb,original)
     result['retainedQaPreferencesRestored']=True;save()
  except BaseException as error:
   result.update(state='failed',cleanupError=str(error));save();raise
  finally:
   if fixture is not None and fixture.poll()is None:
    fixture.terminate();fixture.wait(timeout=15)
   result['fixtureStopped']=fixture is None or fixture.poll()is not None;save()
 return result
