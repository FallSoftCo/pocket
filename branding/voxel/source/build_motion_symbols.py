"""Native motion derived from original v06 geometry; static sources unchanged."""
import hou,math,json,hashlib,socket,sys
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
mode=sys.argv[1];root=Path.cwd();out=root/'build';out.mkdir(exist_ok=True)
hou.hipFile.load('/aosp/houdini/nextcomp/symbols-v06/build/asset.hiplc',suppress_save_prompt=True)
hou.setFps(6);count=16 if mode=='spin' else 12;hou.playbar.setFrameRange(1,count)
for g in hou.node('/obj').children():
 if g.type().name()!='geo':continue
 for f in range(1,count+2):
  phase=(f-1)*math.tau/count
  for parm,value in [('ry',12+((f-1)*360/count if mode=='spin' else 12*math.sin(phase))),('rx',-7+(0 if mode=='spin' else 4*math.sin(phase+math.pi/2)))]:
   k=hou.Keyframe();k.setFrame(f);k.setValue(value);g.parm(parm).setKeyframe(k)
hou.setFrame(1);hou.hipFile.save(str(out/'asset.hiplc'))
source=Path('/aosp/houdini/nextcomp/symbols-v06/build/glyphs.json');(out/'glyphs.json').write_text(source.read_text())
(out/'motion.json').write_text(json.dumps({'mode':mode,'frames':count,'fps':6,'rotationDegrees':360 if mode=='spin' else [-12,12],'staticFallback':'v06','nativeMaster':[3840,3840]},indent=2))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file()}
(out/'asset-manifest.json').write_text(json.dumps({'scene':'asset.hiplc','files':files},indent=2));print('MOTION BUILT',mode,count)
