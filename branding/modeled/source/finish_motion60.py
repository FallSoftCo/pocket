"""OZZZ-OS: mixed-revision final resources, native masters and review delivery.
Geometry/swivels are v06; crisp full turns and separate theme/view rigs are v07.
"""
import json,hashlib,socket,tarfile,shutil,subprocess
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();prior=Path('/aosp/houdini/nextcomp/modeled-v06');d=root/'delivery';assets=d/'drawable-nodpi'
subprocess.run(['/usr/bin/python3','normalize_atlases.py'],check=True)
q={}
for kind,base in [('swivel',prior),('study',root),('spin',root),('mark',root)]:
 p=base/'delivery'/(kind+'-qualification.json');q[kind]=json.loads(p.read_text());assert q[kind]['verified']
for p in (prior/'delivery/drawable-nodpi').iterdir():shutil.copy2(p,assets/p.name)
assert len(list(assets.iterdir()))==206
for name in ['swivel-60fps.mp4','swivel-poster.png','readability-16-20-24-32-44.png']:
 shutil.copy2(prior/'delivery'/name,d/name)
for stem in ['readability-16-20-24-32-44','perspective-lighting','themed-readability']:
 if (d/(stem+'.mp4')).exists():continue
 subprocess.run(['ffmpeg','-y','-threads','2','-loop','1','-i',str(d/(stem+'.png')),'-t','3','-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/(stem+'.mp4'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
for src,name in [(prior/'delivery/icons-masters/frame.000001.png','atlas-3840.png'),(prior/'delivery/icons-masters/frame.000001.hd.png','atlas-1920.png')]:shutil.copy2(src,d/name)
with tarfile.open(d/'native-modeled-masters.tar','w') as t:
 def delivered_pngs(info):
  # HDR EXRs already contain every native UHD master. Include all HD PNG frames
  # and a first UHD PNG per cohort; avoid duplicating entire UHD PNG sequences.
  if info.isfile() and info.name.endswith('.png') and not info.name.endswith('.hd.png') and not info.name.endswith('/frame.000001.png'):return None
  return info
 for revision,base,folders in [('v06',prior,['build','render/icons','delivery/icons-masters']),('v07',root,['build','render/crisp-views','render/crisp-daylight','render/crisp-warm','render/crisp-midnight','render/crisp-spins','render/crisp-mark','render/studies/source-collection.json','delivery/studies-masters','delivery/spins-masters','delivery/mark-masters'])]:
  for folder in folders:
   if revision=='v06' and folder=='build':
    for p in list((base/'build').glob('*.bgeo.sc'))+[(base/'build'/n) for n in ['objects.hiplc','objects.json','asset-manifest.json']]:t.add(p,arcname=revision+'/build/'+p.name)
   else:t.add(base/folder,arcname=revision+'/'+folder,filter=delivered_pngs if folder.endswith('-masters') else None)
 for p in root.glob('*.py'):t.add(p,arcname='source/'+p.name)
with tarfile.open(d/'app-resources.tar','w') as t:t.add(assets,arcname='drawable-nodpi')
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
native=d/'native-modeled-masters.tar';native_sha=digest(native);compressed=native.with_suffix('.tar.zst')
subprocess.run(['zstd','-T2','-6','-f',str(native),'-o',str(compressed)],check=True)
subprocess.run(['zstd','-t',str(compressed)],check=True)
decoder=subprocess.Popen(['zstd','-d','-c',str(compressed)],stdout=subprocess.PIPE);decoded=hashlib.sha256()
for block in iter(lambda:decoder.stdout.read(1024*1024),b''):decoded.update(block)
assert decoder.wait()==0 and decoded.hexdigest()==native_sha
native.unlink()  # Only redundant archive removed, after exact round-trip verification; native sources/frames remain.
summary={'verified':True,'geometryRevision':'v06','swivelRevision':'v06, 120 real frames at 60fps','crispFullTurnsAndStudiesRevision':'v07, explicitly disabled motion cache and zero camera shutter','sources':q,'resourceCount':206,'resourcesBytes':sum(p.stat().st_size for p in assets.iterdir()),'archives':{}}
summary['nativeArchiveRoundTripSha256']=native_sha
summary['nativeArchiveScope']='Every native3840 RGBA HDR master, all1920 RGBA HD PNG frames, first3840 PNG per cohort, editable Indie scenes/geometry and receipts. Entire tonemapped3840 PNG sequences remain on the farm.'
for name in ['native-modeled-masters.tar.zst','app-resources.tar']:
 p=d/name;summary['archives'][name]={'bytes':p.stat().st_size,'sha256':digest(p)}
(d/'qualification.json').write_text(json.dumps(summary,indent=2));print('FINAL VERIFIED MIXED-REVISION DELIVERY',summary['resourceCount'],summary['resourcesBytes'],flush=True)
