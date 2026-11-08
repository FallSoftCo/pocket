from pathlib import Path
import os,json,time,subprocess,urllib.request,xml.etree.ElementTree as ET,uuid,hashlib,re,socket,struct

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

def read_visible_hierarchy(adb,q):
 # Read this invocation's stdout, never a persistent device file: uiautomator
 # can return success with a null-root error while leaving its old file intact.
 for attempt in range(3):
  raw=adb('exec-out','uiautomator','dump','/proc/self/fd/1')
  match=re.search(rb'(?:<\?xml[^>]*\?>\s*)?<hierarchy\b[\s\S]*?</hierarchy>',raw)
  try:
   if match is None or raw.count(b'<hierarchy')!=1:raise ValueError('No unique current hierarchy')
   xml=match.group(0)
   if ET.fromstring(xml).tag!='hierarchy':raise ValueError('Unexpected UI root')
   (q/'last-visible-hierarchy.xml').write_bytes(xml)
   return xml
  except (ET.ParseError,ValueError):
   stem=q/('hierarchy-capture-'+str(time.time_ns()))
   stem.with_suffix('.raw').write_bytes(raw)
   if attempt<2:time.sleep(.25)
 raise RuntimeError('UIAutomation returned no valid current hierarchy; raw capture retained at '+str(stem))

def catalog_observation(xml):
 # One current snapshot must contain the catalog identity, preview and action.
 tree=ET.fromstring(xml)
 nodes=list(tree.iter('node'))
 titles=[n for n in nodes if n.get('text')=='Native canonical work']
 previews=[n for n in nodes if 'Native answer two' in n.get('text','')]
 chats=[n for n in nodes if n.get('content-desc')=='Open Chat for Native canonical work']
 if len(titles)!=1 or len(previews)!=1 or len(chats)!=1:return None
 if any(n.get('text') in ('Native request two','Native pending question') or n.get('content-desc')=='Close questions' for n in nodes):return None
 chat=chats[0]
 if chat.get('enabled')!='true' or chat.get('clickable')!='true':return None
 relevant=[titles[0],previews[0],chat]
 signature=[]
 for node in relevant:
  bounds=list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
  if len(bounds)!=4 or bounds[0]<0 or bounds[1]<0 or bounds[2]<=bounds[0] or bounds[3]<=bounds[1]:return None
  if node.get('package')!='co.fallsoft.pocket' or node.get('enabled')!='true':return None
  signature.append({'text':node.get('text',''),'description':node.get('content-desc',''),'bounds':bounds})
 return tree,chat,signature

def capture_stable_catalog(adb,q,data,timeout=15):
 # Observe twice before capture and once after. A changing layout retries only
 # observation; it never repeats navigation or an external action. Ignore clock
 # text by comparing only this exact catalog card's identity/action geometry.
 deadline=time.monotonic()+timeout;attempt=0
 while time.monotonic()<deadline:
  attempt+=1;stem=q/('catalog-observation-'+str(attempt))
  first=read_visible_hierarchy(adb,q);stem.with_suffix('.first.xml').write_bytes(first)
  a=catalog_observation(first)
  if a is None:time.sleep(.15);continue
  time.sleep(.15)
  before=read_visible_hierarchy(adb,q);stem.with_suffix('.before.xml').write_bytes(before)
  b=catalog_observation(before)
  if b is None or a[2]!=b[2]:continue
  capturedAt=time.time();png=adb('exec-out','screencap','-p')
  stem.with_suffix('.png').write_bytes(png)
  after=read_visible_hierarchy(adb,q);stem.with_suffix('.after.xml').write_bytes(after)
  c=catalog_observation(after)
  if c is None or b[2]!=c[2]:continue
  if len(png)<24 or png[:8]!=b'\x89PNG\r\n\x1a\n' or png[12:16]!=b'IHDR':raise RuntimeError('Catalog capture is not a PNG')
  width,height=struct.unpack('>II',png[16:24])
  if any(v['bounds'][2]>width or v['bounds'][3]>height for v in c[2]):continue
  files={'optimized-native-catalog-before.xml':before,'optimized-native-catalog-after.xml':after,'optimized-native-catalog.png':png}
  for name,value in files.items():(data/name).write_bytes(value)
  proof={'capturedAt':capturedAt,'attempt':attempt,'dimensions':[width,height],'signature':c[2],
         'artifacts':[{ 'path':name,'sha256':hashlib.sha256(value).hexdigest()} for name,value in files.items()],
         'limit':'Stable catalog semantics and bounds bracket the screenshot; optical review of the retained PNG remains required.'}
  (data/'optimized-native-catalog-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
  return c[0],c[1],proof
 raise RuntimeError('Catalog did not remain stable around its screenshot; observation attempts retained in '+str(q))

def qualify_native_stock(*,root,q,data,adb,launch,avd_name="nextcomp_env_qa",signer_sha="22ecabe28e15be3b5ce9b718c8d85932d9d4855506f215a29d3828d2304c6f31",room_capture=None,room_evidence=None):
 assert re.fullmatch(r'(?:nextcomp_[a-zA-Z0-9_]+|harness_native_room)',avd_name)
 manifest=json.loads((data/'manifest.json').read_text());build=json.loads((data/'build.json').read_text())
 # The method itself belongs to qualificationInputSha. Reject an old build
 # receipt before any device access, even when the production APK is unchanged.
 identities=json.loads(subprocess.check_output(['node','--input-type=module','-e',
  "import {qualificationInputSha,productionSourceSha} from './scripts/release-qualification.mjs'; console.log(JSON.stringify({qualificationInputSha:qualificationInputSha(process.cwd()),productionSourceSha:productionSourceSha(process.cwd())}));"],cwd=root))
 if any(build.get(key)!=value for key,value in identities.items()):raise RuntimeError('Current source/qualification inputs differ from build receipt; fresh matching qualification required')
 assert adb('shell','getprop','ro.boot.qemu.avd_name').decode().strip()==avd_name,'Owned NextComp emulator required'
 variant=next(v for v in manifest['variants'] if v['signerSha256']==signer_sha)
 apk=data/variant['path'];assert hashlib.sha256(apk.read_bytes()).hexdigest()==variant['sha256']
 result={'state':'running','kind':'exact-optimized-native-canonical','versionCode':manifest['versionCode'],'apkSha256':variant['sha256'],'productionSourceSha':build['productionSourceSha'],'qualificationInputSha':build['qualificationInputSha'],'phoneTouched':False}
 path=q/'optimized-native-canonical.json'
 def save():path.write_text(json.dumps(result,indent=2)+'\n')
 save();port=20143;fixture=None;original=None
 def qa(name):
  req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/_qa/'+name,headers={'Authorization':'Bearer synthetic-system76'},method='POST')
  with urllib.request.urlopen(req,timeout=4)as response:return json.load(response)
 def dump():
  return read_visible_hierarchy(adb,q)
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
  tree,openChat,catalogProof=capture_stable_catalog(adb,q,data)
  result['catalogSnapshotProof']=catalogProof;result['catalogSnapshotStable']=True;save()
  beforeReopen=len(qa('state')['reads']);tap(openChat);xml,tree=wait('Native request two')
  deadline=time.monotonic()+15
  while len(qa('state')['reads'])<=beforeReopen or texts(tree,'Native pending question'):
   if time.monotonic()>deadline:raise RuntimeError('Exact optimized reopen did not reconcile current state')
   time.sleep(.1);xml=dump();tree=ET.fromstring(xml)
  assert len(texts(tree,'Native request two'))==len(texts(tree,'Native answer two'))==1
  (data/'optimized-native-canonical.xml').write_bytes(xml);(data/'optimized-native-canonical.png').write_bytes(adb('exec-out','screencap','-p'))
  state=qa('state');assert state['legacyReads']==state['positionReads']==state['workSubmissions']==0 and state['answers']==1
  # Private review assembly is manual; provide the two actual visual artifacts
  # without claiming that the automated semantic check performed optical review.
  result['optimizedReviewArtifactCandidates']=[{'path':name,'sha256':hashlib.sha256((data/name).read_bytes()).hexdigest()} for name in ('optimized-native-catalog.png','optimized-native-canonical.png')]
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
