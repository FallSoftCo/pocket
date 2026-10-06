"""OZZZ-OS: remove undefined RGB beneath fully transparent atlas pixels.
Lossless WebP's default invisible-color optimization can confuse RGB-only
inspection. Exact mode preserves clean transparent payloads. Visible pixels and
alpha must remain byte-identical; this is encoding hygiene, not a new render.
"""
import socket,json,hashlib
from pathlib import Path
from PIL import Image,ImageChops
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();records={}
for directory in [Path('/aosp/houdini/nextcomp/modeled-v06/delivery'),root/'delivery']:
 for p in (directory/'drawable-nodpi').glob('*.webp'):
  before=Image.open(p).convert('RGBA');transparent=before.getchannel('A').point([255]+[0]*255)
  clean=before.copy();clean.paste((0,0,0,0),mask=transparent)
  if not ImageChops.difference(before,clean).convert('RGB').getbbox():
   records[str(p)]={'visiblePixelsAndAlphaUnchanged':True,'alreadyClean':True,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size};continue
  tmp=p.with_suffix('.normalizing.webp');clean.save(tmp,lossless=True,exact=True,method=3)
  after=Image.open(tmp).convert('RGBA');assert before.getchannel('A').tobytes()==after.getchannel('A').tobytes()
  visible_difference=ImageChops.difference(before,after).convert('RGB');visible_difference.paste((0,0,0),mask=transparent);assert not visible_difference.getbbox()
  assert clean.tobytes()==after.tobytes()
  tmp.replace(p);records[str(p)]={'visiblePixelsAndAlphaUnchanged':True,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
 for p in directory.glob('*-qualification.json'):
  q=json.loads(p.read_text())
  for name,record in q.get('resources',{}).items():
   asset=directory/'drawable-nodpi'/name
   if asset.exists():record.update(sha256=hashlib.sha256(asset.read_bytes()).hexdigest(),bytes=asset.stat().st_size)
  q['codecNormalization']='Exact lossless WebP; RGB cleared only where alpha is zero. Visible RGBA and all alpha byte-identical.';p.write_text(json.dumps(q,indent=2))
(root/'delivery/atlas-normalization.json').write_text(json.dumps(records,indent=2));print('Exact transparent atlas normalization:',len(records),'pages',flush=True)
