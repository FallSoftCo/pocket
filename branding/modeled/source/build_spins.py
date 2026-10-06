"""Reuse assembled native objects for the two established full rotation indicators."""
import hou,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
root=Path.cwd();source=root/'build/objects.hiplc';hou.hipFile.load(str(source),suppress_save_prompt=True)
paths=[]
for g in hou.node('/obj').children():
 if g.type().name()!='geo':continue
 name=g.name().removeprefix('symbol_')
 if name not in ['Codex','Psychology']:g.setDisplayFlag(False);continue
 g.parmTuple('t').set((-.90 if name=='Codex' else .90,0,0))
 for axis in ['rx','ry','rz']:g.parm(axis).deleteAllKeyframes()
 g.parm('rx').set(-9);g.parm('rz').set(-2)
 for frame in range(1,161):
  k=hou.Keyframe();k.setFrame(frame);k.setValue(16+360*(frame-1)/160);g.parm('ry').setKeyframe(k)
 paths.append(g.path())
cam=hou.node('/obj/atlas_camera');cam.parm('orthowidth').set(3.6)
rop=hou.node('/out/icons');rop.parm('objects').set(' '.join(paths));rop.parm('excludeobjects').set(' '.join(g.path() for g in hou.node('/obj').children() if g.type().name()=='geo' and g.path() not in paths))
hou.setFrame(1);out=root/'build';hou.hipFile.save(str(out/'spins.hiplc'))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file() and p.name not in ['asset-manifest.json','spin-manifest.json']}
(out/'spin-manifest.json').write_text(json.dumps({'scene':'spins.hiplc','files':files},indent=2))
print('MODELED SPINS BUILT')
