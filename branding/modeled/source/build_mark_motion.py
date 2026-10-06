"""Retiming the existing equal-cube N; its construction and look remain the authored baseline."""
import hou,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp' and hou.licenseCategory()==hou.licenseCategoryType.Indie
root=Path.cwd();out=root/'build';source=Path('/aosp/houdini/nextcomp/voxel-v05/build/asset.hiplc')
hou.hipFile.load(str(source),suppress_save_prompt=True);hou.setFps(60)
g=hou.node('/obj/glyph_Logo');assert g is not None
for axis in ['rx','ry','rz']:g.parm(axis).deleteAllKeyframes()
g.parm('rx').set(-16);g.parm('rz').set(-4)
for frame in range(1,161):
 k=hou.Keyframe();k.setFrame(frame);k.setValue(26+360*(frame-1)/160);g.parm('ry').setKeyframe(k)
hou.setFrame(1);hou.hipFile.save(str(out/'mark.hiplc'))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file() and p.name not in ['asset-manifest.json','spin-manifest.json','study-manifest.json','mark-manifest.json']}
(out/'mark-manifest.json').write_text(json.dumps({'scene':'mark.hiplc','original_scene_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'frames':160,'fps':60,'files':files},indent=2))
