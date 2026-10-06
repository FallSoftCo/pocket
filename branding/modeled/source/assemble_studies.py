"""OZZZ-OS: verify and assemble separate rendered scenes for a review sheet.
This collection is explicitly not a native render-sequence receipt.
"""
import hashlib,json,socket,os
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();out=root/'render/studies';out.mkdir(parents=True,exist_ok=True)
collection={'verified':True,'verificationKind':'assembled verified source collection, not single native render sequence','frames':[1,9],'files':{},'sourceMap':{},'nativeSources':{}}
frame=0
for name in ['views','daylight','warm','midnight']:
 source=root/'render'/('crisp-'+name)
 receipt=json.loads((source/'sequence-complete.json').read_text());assert receipt['verified']
 collection['nativeSources'][name]=receipt
 for original in range(receipt['frames'][0],receipt['frames'][1]+1):
  frame+=1;p=source/('frame.%06d.exr'%original);record=receipt['files'][p.name]
  assert hashlib.sha256(p.read_bytes()).hexdigest()==record['sha256']
  target=out/('frame.%06d.exr'%frame)
  if not target.exists():os.link(p,target)
  assert hashlib.sha256(target.read_bytes()).hexdigest()==record['sha256']
  collection['files'][target.name]=record
  collection['sourceMap'][target.name]={'scene':name,'originalFrame':original,'sourceSceneSha256':receipt['scene_sha256']}
assert frame==9
(out/'source-collection.json').write_text(json.dumps(collection,indent=2))
labels=root/'build/studies.json';labels.write_text(labels.read_text().replace('high-quarter','low-quarter'))
print('Verified review source collection:',frame,'frames from four independent scenes',flush=True)
