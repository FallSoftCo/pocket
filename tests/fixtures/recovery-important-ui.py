# Native synthetic taps. Requires explicit ownership of emulator5562 and fixture19988.
import subprocess,json,time,urllib.request,xml.etree.ElementTree as E,re
import os
serial=os.environ.get('NEXTCOMP_QA_SERIAL','emulator-5562')
assert serial.startswith('emulator-'), 'Synthetic QA is emulator-only'
b=['adb','-s',serial];base='http://127.0.0.1:19988'
def sh(*a):return subprocess.check_output(b+['shell',*a]).decode()
def post(path,data):return json.load(urllib.request.urlopen(urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json'})))
def tree():
 for _ in range(8):
  sh('rm','-f','/sdcard/context.xml');r=sh('uiautomator','dump','--compressed','/sdcard/context.xml')
  if 'ERROR' in r:time.sleep(.4);continue
  try:return E.fromstring(sh('cat','/sdcard/context.xml'))
  except Exception:time.sleep(.4)
 raise AssertionError('UI dump unavailable')
def capture(name):
 with open('/tmp/nextcomp-alpha23-'+name+'.png','wb')as f:subprocess.run(b+['exec-out','screencap','-p'],stdout=f,check=True)
def tap(n):
 q=list(map(int,re.findall(r'\d+',n.get('bounds'))));sh('input','tap',str((q[0]+q[2])//2),str((q[1]+q[3])//2));time.sleep(.3)
def find(r,desc):return next(n for n in r.iter()if n.get('content-desc')==desc)
def texts(r):return [(n.get('text'),n.get('content-desc'),n.get('bounds'))for n in r.iter()if n.get('text')or n.get('content-desc')]
def reopen():sh('am','force-stop','co.fallsoft.pocket');sh('am','start','-n','co.fallsoft.pocket/.MainActivity','--es','thread','alpha23-task');time.sleep(1.2)
def fixture(rows):post('/fixture/set',{'outgoing':rows,'failRead':False,'answerText':'Use 3840 × 2160 pixels for the final image.','notes':[{'id':'source-answer','text':'Use 3840 × 2160 pixels for the final image.','question':'What size should the final image be?','manual':0,'at':int(time.time()*1000)}]});time.sleep(.7)
def row(id,state,text):return {'id':id,'thread_id':'alpha23-task','state':state,'mode':'auto','text':text,'result':'Synthetic transport failure; no work executed.','created_at':int(time.time()*1000)}
def button(label):
 r=tree();return next(n for n in r.iter()if n.get('text')==label or n.get('content-desc')==label)
def state():return json.load(urllib.request.urlopen(base+'/fixture/state'))
def ime():return 'mInputShown=true' in sh('dumpsys','input_method') or 'mIsInputViewShown=true' in sh('dumpsys','input_method')
def dump():print(json.dumps(texts(tree())),flush=True)

def asserttext(label,present=True):assert any(n.get('text')==label for n in tree().iter())==present,label
results={}
def fresh(rows):fixture(rows);reopen();time.sleep(.7)
def action(label):tap(button(label));time.sleep(.8)
# Run selected scenario only after candidate APK installed.
import sys
case=sys.argv[1] if len(sys.argv)>1 else 'dump'
if case=='dump':dump()
if case=='failed':
 fresh([row('failed-retry','failed','Retry this synthetic turn once.')]);dump();capture('failed-before');action('Retry');time.sleep(1);s=state();assert len(s['requests'])==1,s;assert s['requests'][0].get('authenticated',True),s;assert s['requests'][0]['body']['action']=='retry',s;assert s['outgoing'][0]['state']=='accepted';capture('failed-accepted');print('PASS failed retry accepted once',s)
if case=='remove':
 fresh([row('failed-remove','failed','Remove this synthetic saved failure.')]);action('Remove');time.sleep(1);s=state();assert not s['outgoing'],s;assert len(s['requests'])==1,s;assert s['requests'][0].get('authenticated',True),s;asserttext('Remove this synthetic saved failure.',False);print('PASS remove gone',s)
if case=='unknown':
 fresh([row('unknown-retry','unknown','Unknown delivery: confirm only after checking.')]);action('Retry');dump();capture('unknown-confirm');action('Cancel');assert not state()['requests'];asserttext('Unknown delivery: confirm only after checking.');action('Retry');action('Retry anyway');time.sleep(1);s=state();assert len(s['requests'])==1,s;assert s['requests'][0].get('authenticated',True),s;assert s['requests'][0]['body']['confirmUnknown'] is True,s;assert s['outgoing'][0]['state']=='accepted';print('PASS unknown Cancel then confirmed retry exactly once',s)
if case=='edit':
 fresh([row('failed-edit','failed','Edit this synthetic turn.')]);action('Edit');dump();capture('edit');r=tree();field=next(n for n in r.iter()if n.get('class')=='android.widget.EditText');tap(field);sh('input','keyevent','KEYCODE_MOVE_END');sh('input','text','%supdated');action('Save');s=state();assert len(s['requests'])==1,s;assert s['requests'][0].get('authenticated',True),s;assert s['requests'][0]['body']['action']=='edit';assert s['outgoing'][0]['text'].endswith('updated');print('PASS edit original saved failure',s)
if case=='filter':
 fresh([]);action('Keyboard');sh('input','text','KeepSyntheticDraft');before=ime();action('Important');dump();capture('important-keyboard');assert sum(n.get('scrollable')=='true' and n.get('package')=='co.fallsoft.pocket' for n in tree().iter())<=1;asserttext('The lighting pass is ready for review.',False);asserttext('Use 3840 × 2160 pixels for the final image.');assert sum(n.get('text')=='Use 3840 × 2160 pixels for the final image.' for n in tree().iter())==1;asserttext('KeepSyntheticDraft');assert ime()==before;action('Important · Back to all');asserttext('The lighting pass is ready for review.');asserttext('KeepSyntheticDraft');assert ime()==before;capture('all-keyboard');print('PASS filter same draft and keyboard')

if case=='orphan':
 post('/fixture/set',{'outgoing':[],'notes':[{'id':'not-loaded-source','text':'An earlier saved answer remains useful.','question':'Earlier context','manual':1,'at':int(time.time()*1000)}]});reopen();action('Important');asserttext('An earlier saved answer remains useful.');capture('important-orphan');action('Unsave');asserttext('An earlier saved answer remains useful.',False);assert len(state()['requests'])==1;print('PASS orphan retained context removed without fake source')

if case=='remove-offline-history':
 fresh([row('failed-offline-remove','failed','Remove this saved failure even when history is unavailable.')]);post('/fixture/set',{'failRead':True});action('Remove');time.sleep(1);s=state();assert not s['outgoing'];assert len(s['requests'])==1,s;asserttext('Remove this saved failure even when history is unavailable.',False);capture('remove-ack-history-unavailable');print('PASS acknowledged removal immediately gone despite history503',s)

if case=='unsave-source':
 fresh([]);action('Important');asserttext('Saved answer');action('Unsave');asserttext('Saved answer',False);asserttext('Use 3840 × 2160 pixels for the final image.');asserttext('Unsave',False);capture('important-unsaved-source');print('PASS Unsave removes retention and leaves exact original source')
if case=='edit-offline-history':
 fresh([row('failed-offline-edit','failed','Edit this saved failure while history is unavailable.')]);action('Edit');r=tree();field=next(n for n in r.iter()if n.get('class')=='android.widget.EditText');tap(field);sh('input','keyevent','KEYCODE_MOVE_END');sh('input','text','%supdated');post('/fixture/set',{'failRead':True});action('Save');s=state();assert len(s['requests'])==1,s;assert s['requests'][0]['body']['action']=='edit';assert s['outgoing'][0]['text'].endswith('updated');asserttext(s['outgoing'][0]['text']);capture('edit-ack-history-unavailable');print('PASS acknowledged edit immediately visible despite history503',s)
if case=='source-go':
 fresh([]);action('Keyboard');r=tree();draft=next(n.get('text') for n in r.iter()if n.get('class')=='android.widget.EditText');before=ime();action('Important');action('Surrounding conversation');asserttext('Important');asserttext('Use 3840 × 2160 pixels for the final image.');asserttext(draft);assert ime()==before;capture('source-go-keyboard');print('PASS source navigation returns All with unchanged draft and IME')
if case=='speech-keyboard':
 text=('The saved answer stays in the original conversation while these native controls let you listen and stop without closing the keyboard. '*7).strip()
 post('/fixture/set',{'outgoing':[],'failRead':False,'answerText':text,'notes':[{'id':'source-answer','text':text,'manual':1,'at':int(time.time()*1000)}]});reopen();action('Keyboard');r=tree();draft=next(n.get('text') for n in r.iter()if n.get('class')=='android.widget.EditText');before=ime();action('Important');action('Speak visible conversation');asserttext(draft);assert ime()==before;capture('speech-keyboard');action('Stop speaking conversation');asserttext(draft);assert ime()==before;print('PASS single native Speak/Stop preserves draft and IME; no acoustic quality claim')
if case=='large-font':
 sh('settings','put','system','font_scale','2');fresh([]);action('Important');dump();capture('important-large-type');r=tree()
 for label in ['Reply','Surrounding conversation','Unsave']:
  n=next(n for n in r.iter()if n.get('text')==label);q=list(map(int,re.findall(r'\d+',n.get('bounds'))));assert 0<=q[0]<q[2]<=1080,(label,q)
 action('Unsave');asserttext('Saved answer',False);asserttext('Use 3840 × 2160 pixels for the final image.');sh('settings','put','system','font_scale','1');print('PASS 2x retained actions visible in width and actual Unsave tappable')
if case=='failed-important':
 fresh([row('failed-important-remove','failed','Recovery stays reachable inside Important.')]);action('Important');asserttext('Retry');asserttext('Edit');asserttext('Remove');action('Remove');asserttext('Recovery stays reachable inside Important.',False);asserttext('Important · Back to all');assert len(state()['requests'])==1;print('PASS recovery controls work inside Important')
