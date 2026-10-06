"""Read-only delivery contract check; safe on control host, no rendering."""
from pathlib import Path
import re,json,hashlib
from PIL import Image
r=Path(__file__).resolve().parents[3]
component=r/'android/app/src/main/java/co/fallsoft/pocket/SymbolIcon.kt';names=re.findall(r'"(\w+)" to R.drawable.symbol_',component.read_text());assert len(names)==37
assets=r/'android/app/src/main/res/drawable-nodpi';records={};old=r/'branding/modeled/qualification/baseline-resource-sizes.json'
for name in names:
 for prefix,frames in [('symbol_',1),('symbol_motion_',12)]+([('symbol_spin_',16)] if name in ['Codex','Psychology'] else []):
  p=assets/(prefix+name.lower()+'.png');im=Image.open(p);expected=(256,256) if frames==1 else (160*frames,160)
  assert im.size==expected and im.mode=='RGBA',(p.name,im.size,im.mode)
  assert im.getchannel('A').getextrema()==(0,255),p.name
  if frames>1:
   for k in range(frames):
    bounds=im.crop((k*160,0,(k+1)*160,160)).getchannel('A').getbbox();assert bounds and bounds[0]>0 and bounds[1]>0 and bounds[2]<160 and bounds[3]<160,(p.name,k,bounds)
   hashes={hashlib.sha256(im.crop((k*160,0,(k+1)*160,160)).tobytes()).hexdigest() for k in range(frames)};assert len(hashes)==frames,(p.name,len(hashes))
  records[p.name]={'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'dimensions':list(im.size)}
assert len(records)==76
result={'passed':True,'symbols':37,'resources':76,'files':records,'compressedBytes':sum(v['bytes'] for v in records.values()),'largestDecodedSheetBytes':160*160*16*4,'eightEntryCacheWorstBytes':8*160*160*16*4,'androidBehaviorChanged':False}
if old.exists():result['previousCompressedBytes']=sum(json.loads(old.read_text()).values())
print(json.dumps(result,indent=2))
