"""Original direction/lighting studies, native geometry shared with production motion."""
import hou,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp' and hou.licenseCategory()==hou.licenseCategoryType.Indie
root=Path.cwd();out=root/'build';hou.hipFile.load(str(out/'objects.hiplc'),suppress_save_prompt=True)
studies=[('front',0,-9,'studio'),('left-quarter',-30,-9,'studio'),('right-quarter',30,-9,'studio'),('high-quarter',30,-24,'studio'),('side-limit',90,-9,'studio'),('back',180,-9,'studio'),('daylight',16,-9,'daylight'),('warm-studio',16,-9,'warm'),('midnight',16,-9,'midnight')]
base={n.name():tuple(n.parmTuple('base_color').eval()) for n in hou.node('/mat').children()}
for g in hou.node('/obj').children():
 if g.type().name()!='geo':continue
 for axis in ['rx','ry','rz']:g.parm(axis).deleteAllKeyframes()
 for frame,(_,yaw,pitch,theme) in enumerate(studies,1):
  for axis,val in [('rx',pitch),('ry',yaw),('rz',-2)]:
   k=hou.Keyframe();k.setFrame(frame);k.setValue(val);g.parm(axis).setKeyframe(k)
for frame,(_,yaw,pitch,theme) in enumerate(studies,1):
 colors=dict(base)
 if theme=='daylight':colors.update(porcelain=(.94,.90,.79),teal=(.025,.32,.37),silver=(.54,.58,.59))
 if theme=='warm':colors.update(porcelain=(.88,.81,.66),teal=(.018,.36,.40),silver=(.50,.47,.40))
 if theme=='midnight':colors.update(porcelain=(.34,.51,.63),teal=(.018,.34,.43),silver=(.28,.43,.53))
 for name,color in colors.items():
  n=hou.node('/mat/'+name)
  for p,val in zip(n.parmTuple('base_color'),color):
   k=hou.Keyframe();k.setFrame(frame);k.setValue(val);p.setKeyframe(k)
 key=hou.node('/obj/large_key');fill=hou.node('/obj/cool_fill')
 keycolor=(1,.82,.62) if theme=='warm' else (.70,.85,1) if theme=='midnight' else (1,1,1)
 for p,val in zip(key.parmTuple('light_color'),keycolor):
  k=hou.Keyframe();k.setFrame(frame);k.setValue(val);p.setKeyframe(k)
 for p,val in [(key.parm('light_intensity'),1.8 if theme=='midnight' else 1.5),(fill.parm('light_intensity'),.5 if theme=='daylight' else .22 if theme=='midnight' else .32)]:
  k=hou.Keyframe();k.setFrame(frame);k.setValue(val);p.setKeyframe(k)
hou.setFrame(1);hou.hipFile.save(str(out/'studies.hiplc'))
(out/'studies.json').write_text(json.dumps(studies,indent=2))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file() and p.name not in ['asset-manifest.json','spin-manifest.json','study-manifest.json']}
(out/'study-manifest.json').write_text(json.dumps({'scene':'studies.hiplc','files':files},indent=2))
