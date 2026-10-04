import socket,json,hashlib,tarfile
from pathlib import Path
from PIL import Image
assert socket.gethostname()=='ozzz-aosp'
b=Path('/aosp/houdini/nextcomp');d=b/'voxel-v03/delivery';r=b/'roles-v01/delivery';report={}
for p in [d/'symbols-3840.png',d/'symbols-1920.png',d/'logo-3840.png',d/'logo-1920.png',r/'roles-3840.png',r/'roles-1920.png',*sorted((d/'frames-3840').glob('*.png')),*sorted((d/'frames-1920').glob('*.png'))]:
 im=Image.open(p);assert im.mode=='RGBA';expected=3840 if '3840' in str(p) else 1920;assert im.size==(expected,expected);im.load();assert im.getchannel('A').getextrema()==(0,255);report[str(p.relative_to(b))]={'size':im.size,'mode':im.mode,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
assert len(list((d/'frames-3840').glob('*.png')))==16
assert len({v['sha256'] for k,v in report.items() if 'frames-3840' in k})==16
assert len(list((d/'drawable-nodpi').glob('symbol_*.png')))==35
assert len(list((r/'drawable-nodpi').glob('symbol_*.png')))==2
(b/'native-export-qualification.json').write_text(json.dumps({'verified':True,'files':report,'symbols':35,'roles':2,'distinctRotationFrames':16,'pngTransfer':'tone mapped sRGB unassociated RGBA; original HDR EXR preserved'},indent=2))
with tarfile.open(b/'nextcomp-native-masters.tar','w') as t:
 for path in [b/'voxel-v03/render/icons',b/'roles-v01/render/icons',b/'voxel-v05/render/logo',d/'frames-3840',d/'frames-1920',b/'native-master-qualification.json',b/'native-export-qualification.json']:
  t.add(path,arcname=str(path.relative_to(b)))
print('EXPORTS VERIFIED AND ARCHIVED',len(report))
