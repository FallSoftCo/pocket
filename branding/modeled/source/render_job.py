"""Resumable native Indie EXR sequences, from an immutable resolved queue job."""
import argparse, hashlib, json, os, shutil, socket, subprocess, threading, time
from pathlib import Path
import hou, OpenImageIO as oiio

p=argparse.ArgumentParser();p.add_argument('job',type=Path);a=p.parse_args()
j=json.loads(a.job.read_text())
assert not j.get('render_command'),'This family requires its explicit native render_command and finishing contract'
engine=j['engine'];gpu=engine=='xpu'
assert socket.gethostname()==('ozzz-gpu' if gpu else 'ozzz-aosp')
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
scene=Path(j['source_scene']);assert scene.suffix=='.hiplc'
assert hashlib.sha256(scene.read_bytes()).hexdigest()==j['scene_sha256']
if gpu:
 assert os.environ.get('KARMA_XPU_DISABLE_EMBREE_DEVICE')=='1'
 manifest=Path(j['asset_manifest']);sha=hashlib.sha256(manifest.read_bytes()).hexdigest()
 assert sha==j['asset_sha256']
 if not (j.get('review_stage')=='after_render' and j.get('runtime_preflight') is True):
  review=json.loads((manifest.parent/'GPU_LOOK_REVIEWED.json').read_text())
  assert review['asset_sha256']==sha and review['approved']
 for name,record in json.loads(manifest.read_text())['files'].items():
  if name=='asset-manifest.json':continue
  f=manifest.parent/name
  assert f.stat().st_size==record['bytes'] and hashlib.sha256(f.read_bytes()).hexdigest()==record['sha256'],name
hou.hipFile.load(str(scene),suppress_save_prompt=True)
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
hou.setFps(j['fps'])
from scene_validation import validate_render_objects
native_geometry=validate_render_objects(sorted(set([j['frames'][0],j.get('poster_frame',j['frames'][0]),j['frames'][1]])))
hou.setFps(j['fps']);start,end=j['frames'];out=Path(j['exr_dir']);out.mkdir(parents=True,exist_ok=True)
identity={k:j[k] for k in ['source_scene','scene_sha256','engine','frames','fps','width','height','samples']}
identity['passes']=j.get('passes',[dict(name='beauty',rop=j['rop'])])
assert all(set(p)<= {'name','rop'} for p in identity['passes']),'Per-pass scenes/ranges/sampling require the family native renderer'
identity['camera']=j.get('camera')
identity['render_contract_version']=2
identity['motion_blur']=j.get('motion_blur',True)
def atomic(path,data):
 t=path.with_suffix(path.suffix+'.next');t.write_text(json.dumps(data,indent=2)+'\n');t.replace(path)
identity_path=out/'render-identity.json'
if identity_path.exists():assert json.loads(identity_path.read_text())==identity,'Use a new delivery revision after scene/settings changes'
else:atomic(identity_path,identity)
telemetry=[];stop=threading.Event();previous_telemetry=[]
if gpu and (out/'gpu-evidence.json').exists():
 previous=json.loads((out/'gpu-evidence.json').read_text());assert previous['identity']==identity
 previous_telemetry=previous['telemetry']
def monitor():
 while not stop.is_set():
  try:
   row=subprocess.check_output(['nvidia-smi','--query-gpu=uuid,utilization.gpu,memory.used,memory.total','--format=csv,noheader,nounits'],text=True,timeout=5)
   uuid,util,used,total=[x.strip() for x in row.strip().split(',')]
   telemetry.append(dict(uuid=uuid,util=int(util),used_mib=int(used),total_mib=int(total)))
  except Exception:pass
  stop.wait(2)
thread=threading.Thread(target=monitor,daemon=True) if gpu else None
if thread:thread.start()
begun=time.monotonic();files={}
def validate(path):
 b=oiio.ImageInput.open(str(path));assert b,path
 s=b.spec();assert (s.width,s.height)==(j['width'],j['height']) and all(x in s.channelnames for x in 'RGBA'),path
 # Read the last scanline too: a header alone can describe an incomplete EXR.
 pixels=b.read_scanlines(s.y+s.height-1,s.y+s.height,0,0,s.nchannels);assert pixels is not None,path
 b.close();return dict(bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
try:
 for renderpass in identity['passes']:
  directory=out if len(identity['passes'])==1 else out/renderpass['name'];directory.mkdir(exist_ok=True)
  rop=hou.node(renderpass['rop']);assert rop is not None
  if not j.get('motion_blur',True):
   rop.parm('enablemblur').set(0);rop.parm('mblur').set(0);rop.parm('enableimageblur').set(0);rop.parm('xformsamples').set(1);rop.parm('geosamples').set(1)
  rop.parm('engine').set(engine);rop.parm('threads').set(4 if gpu else 2)
  if j.get('camera'):rop.parm('camera').set(j['camera'])
  if rop.parm('override_camerares'):rop.parm('override_camerares').set(1)
  rop.parm('resolutionx').set(j['width']);rop.parm('resolutiony').set(j['height']);rop.parm('pathtracedsamples').set(j['samples'])
  rop.parm('picture').set(str(directory/'frame.$F6.exr'))
  for first in range(start,end+1,j.get('chunk_frames',24)):
   last=min(end,first+j.get('chunk_frames',24)-1)
   missing=[]
   for frame in range(first,last+1):
    f=directory/f'frame.{frame:06d}.exr';marker=f.with_suffix('.verified.json')
    if f.exists() and marker.exists():
     record=json.loads(marker.read_text())
     assert f.stat().st_size==record['bytes'] and hashlib.sha256(f.read_bytes()).hexdigest()==record['sha256']
     files[str(f.relative_to(out))]=record
    else:missing.append(frame)
   if not missing:continue
   # Keep the GPU worker within disk capacity; finished frames remain resumable.
   assert shutil.disk_usage(out).free>35*1024**3,'Queue paused: less than35GiB free'
   # One native husk session for each contiguous missing range, reusing scene data.
   runs=[]
   for frame in missing:
    if runs and runs[-1][1]+1==frame:runs[-1][1]=frame
    else:runs.append([frame,frame])
   for lo,hi in runs:
    try:rop.render(frame_range=(lo,hi))
    except Exception:
     for node in rop.allSubChildren():
      if node.errors():print(node.path(),node.errors(),flush=True)
     raise
   assert hou.licenseCategory()==hou.licenseCategoryType.Indie
   if gpu:
    measured=previous_telemetry+telemetry
    assert measured and max(x['util'] for x in measured)>0,'No GPU activity in native chunk'
    atomic(out/'gpu-evidence.json',dict(identity=identity,telemetry=measured))
   for frame in missing:
    f=directory/f'frame.{frame:06d}.exr';record=validate(f)
    atomic(f.with_suffix('.verified.json'),record);files[str(f.relative_to(out))]=record
   atomic(out/'progress.json',dict(complete_files=len(files),expected_files=(end-start+1)*len(identity['passes']),last_frame=last,host=socket.gethostname(),identity=identity))
finally:
 stop.set()
 if thread:thread.join(timeout=6)
if gpu:
 telemetry=previous_telemetry+telemetry
 assert telemetry and max(x['util'] for x in telemetry)>0,'No measured GPU rendering'
 assert max(x['used_mib']/x['total_mib'] for x in telemetry)<.8,'Scene exceeded planned VRAM headroom'
assert len(files)==(end-start+1)*len(identity['passes'])
atomic(out/'sequence-complete.json',dict(verified=True,native_geometry=native_geometry,**identity,license_category=str(hou.licenseCategory()),host=socket.gethostname(),files=files,seconds=time.monotonic()-begun,gpu_telemetry=telemetry))
print('Native sequence complete',j['id'],flush=True)
