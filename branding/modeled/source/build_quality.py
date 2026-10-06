"""Crisp angle studies/full turns and separately authored, constant theme rigs.
Original v06 geometry is immutable; no tutorial scene or texture is imported.
"""
import hou,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp' and hou.licenseCategory()==hou.licenseCategoryType.Indie
root=Path.cwd();out=root/'build';source=Path('/aosp/houdini/nextcomp/modeled-v06/build');records={}
def load(name):hou.hipFile.load(str(source/(name+'.hiplc')),suppress_save_prompt=True)
def crisp():
 cams=[]
 for n in hou.node('/obj').children():
  if n.type().name()=='cam':
   p=n.parm('shutter');assert p is not None;n.parm('shutter').set(0);cams.append({'camera':n.path(),'shutter':p.eval()})
 return cams
def save(name,metadata):
 hou.setFrame(1);p=out/(name+'.hiplc');hou.hipFile.save(str(p));records[name]={'cameras':crisp(),'metadata':metadata,'sceneSha256':hashlib.sha256(p.read_bytes()).hexdigest()}
 files={f.name:{'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in list(out.glob('*.bgeo.sc'))+[p]}
 (out/(name+'-manifest.json')).write_text(json.dumps({'scene':p.name,'files':files},indent=2))
load('objects');crisp();views=[('front',0,-9,'studio'),('left-quarter',-30,-9,'studio'),('right-quarter',30,-9,'studio'),('low-quarter',30,-24,'studio'),('side-limit',90,-9,'studio'),('back',180,-9,'studio')]
for g in hou.node('/obj').children():
 if g.type().name()!='geo':continue
 for a in ['rx','ry','rz']:g.parm(a).deleteAllKeyframes()
 for frame,(_,yaw,pitch,_) in enumerate(views,1):
  for axis,val in [('rx',pitch),('ry',yaw),('rz',-2)]:
   k=hou.Keyframe();k.setFrame(frame);k.setValue(val);g.parm(axis).setKeyframe(k)
save('views',{'views':views,'motionBlur':'camera shutter explicitly zero'})
base={n.name():tuple(n.parmTuple('base_color').eval()) for n in hou.node('/mat').children()}
for theme in ['daylight','warm','midnight']:
 load('objects');crisp()
 for g in hou.node('/obj').children():
  if g.type().name()!='geo':continue
  for a in ['rx','ry','rz']:g.parm(a).deleteAllKeyframes()
  g.parmTuple('r').set((-9,16,-2))
 colors=dict(base)
 if theme=='daylight':colors.update(porcelain=(.12,.18,.21),teal=(.018,.24,.28),silver=(.20,.29,.33),gold=(.58,.28,.035),coral=(.70,.06,.03))
 if theme=='warm':colors.update(porcelain=(.75,.64,.45),teal=(.016,.28,.30),silver=(.40,.35,.27))
 if theme=='midnight':colors.update(porcelain=(.22,.38,.49),teal=(.018,.27,.35),silver=(.22,.34,.43))
 for name,color in colors.items():hou.node('/mat/'+name).parmTuple('base_color').set(color)
 key=hou.node('/obj/large_key');fill=hou.node('/obj/cool_fill')
 key.parmTuple('light_color').set((1,.75,.48) if theme=='warm' else (.70,.86,1) if theme=='midnight' else (.95,.98,1))
 key.parm('light_intensity').set(1.2 if theme!='midnight' else 1.0);fill.parm('light_intensity').set(.24 if theme!='midnight' else .18)
 if theme=='midnight':key.parmTuple('r').set((-25,-50,0))
 key.parm('vm_envangle').set(20 if theme=='warm' else 14)
 save(theme,{'colors':colors,'pose':[-9,16,-2],'keyIntensity':key.parm('light_intensity').eval(),'fillIntensity':fill.parm('light_intensity').eval(),'keyColor':list(key.parmTuple('light_color').eval())})
load('spins');crisp();save('spins',{'frames':160,'fps':60,'motionBlur':'zero shutter for small full-rotation cues'})
load('mark');crisp()
# Package just the original cubic N, removing unused historical extruded glyphs.
for n in hou.node('/obj').children():
 if n.type().name()=='geo' and n.name()!='glyph_Logo':n.destroy()
hou.node('/out/icons').destroy();hou.node('/out/logo').parm('excludeobjects').set('')
save('mark',{'frames':160,'fps':60,'geometry':'existing equal-XYZ cube N, only rendered glyph retained','originalMarkSourceSha256':hashlib.sha256((source/'mark.hiplc').read_bytes()).hexdigest(),'motionBlur':'zero shutter'})
(out/'quality.json').write_text(json.dumps(records,indent=2));(out/'studies.json').write_text(json.dumps(views+[('daylight',16,-9,'daylight'),('warm-studio',16,-9,'warm'),('midnight',16,-9,'midnight')],indent=2));print('CRISP RIGS BUILT',list(records),flush=True)
