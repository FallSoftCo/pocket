"""Package reviewed modeled controls, actual rendered rotations, sources and native masters on OZZZ-OS."""
import socket,json,hashlib,subprocess,tarfile
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();d=root/'delivery';assets=d/'drawable-nodpi';q=json.loads((d/'qualification.json').read_text());assert q['motionFrames']==12
receipt=json.loads((root/'render/spins/sequence-complete.json').read_text());assert receipt['verified']
spins=[]
for n in range(1,17):
 src=root/'render/spins'/('frame.%06d.exr'%n);assert hashlib.sha256(src.read_bytes()).hexdigest()==receipt['files'][src.name]['sha256']
 dst=d/('spins-%02d-3840.png'%n)
 if not dst.exists():subprocess.run(['/opt/hfs22.0.429/bin/hython','display_exr.py',str(src),str(dst),'--exposure','.85'],check=True)
 im=Image.open(dst);assert im.size==(3840,3840);spins.append(im.copy());im.resize((1920,1920),Image.Resampling.LANCZOS).save(d/('spins-%02d-1920.png'%n))
for i,name in enumerate(['Codex','Psychology']):
 cells=[im.crop((i*1920,960,(i+1)*1920,2880)) for im in spins];boxes=[im.getchannel('A').getbbox() for im in cells];assert all(boxes)
 side=int(max(max(b[2]-b[0],b[3]-b[1]) for b in boxes)*1.1)
 # Rotation remains centered on the model pivot, never recentered per frame.
 box=(960-side//2,960-side//2,960-side//2+side,960-side//2+side)
 strip=Image.new('RGBA',(2560,160))
 for k,im in enumerate(cells):strip.paste(im.crop(box).resize((160,160),Image.Resampling.LANCZOS),(k*160,0))
 strip.save(assets/('symbol_spin_'+name.lower()+'.png'))
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',19)
reviews=[]
for n in range(1,13):
 im=Image.open(d/('atlas-%02d-1920.png'%n)).crop((0,0,1920,1200));canvas=Image.new('RGB',(1920,1200),(14,22,30));canvas.paste(im,(0,0),im);reviews.append(canvas)
reviews[0].save(d/'poster.png');reviews[0].save(d/'swivel-review.webp',save_all=True,append_images=reviews[1:],duration=166,loop=0,quality=90)
keyframes=Image.new('RGB',(1200,660),(14,22,30));dr=ImageDraw.Draw(keyframes)
for col,n in enumerate([1,4,7,10]):
 for row,name in enumerate(['Mic','Keyboard','Send']):
  im=Image.open(assets/('symbol_motion_'+name.lower()+'.png')).crop(((n-1)*160,0,n*160,160));im=im.resize((190,190));keyframes.paste(im,(col*300+70,row*220+20),im);dr.text((col*300+15,row*220+190),name+' · phase '+str(n),font=font,fill=(221,232,241))
keyframes.save(d/'primary-keyframes.png')
spinreviews=[]
for im in spins:
 im=im.crop((0,960,3840,2880)).resize((960,480));canvas=Image.new('RGB',im.size,(14,22,30));canvas.paste(im,(0,0),im);spinreviews.append(canvas)
spinreviews[0].save(d/'spin-review.webp',save_all=True,append_images=spinreviews[1:],duration=166,loop=0,quality=90)
for name,frames in [('swivel-review',reviews),('spin-review',spinreviews)]:
 frame_dir=d/(name+'-frames');frame_dir.mkdir(exist_ok=True)
 for i,im in enumerate(frames,1):im.save(frame_dir/('frame-%02d.png'%i))
 subprocess.run(['ffmpeg','-y','-threads','2','-framerate','6','-i',str(frame_dir/'frame-%02d.png'),'-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/(name+'.mp4'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
for name in ['readability-16-20-24-32-44','synthetic-contexts']:
 subprocess.run(['ffmpeg','-y','-threads','2','-loop','1','-i',str(d/(name+'.png')),'-t','3','-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/(name+'.mp4'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
with tarfile.open(d/'native-modeled-masters.tar','w') as t:
 for p in [root/'build',root/'render',root/'spin-native',root/'build_objects.py',root/'build_spins.py',root/'package_objects.py',root/'finish_delivery.py',root/'display_exr.py',d/'qualification.json']:
  t.add(p,arcname=str(p.relative_to(root)))
with tarfile.open(d/'app-resources.tar','w') as t:t.add(assets,arcname='drawable-nodpi')
q['spinRenderReceipt']=receipt;q['spinNativeRevision']='v02 retained as independent native source, packaged under spin-native';q['resourceCount']=len(list(assets.glob('*.png')));assert q['resourceCount']==76
q['resourceBytes']=sum(p.stat().st_size for p in assets.glob('*.png'))
q['decodedCacheUpperBytes']=8*16*160*160*4
q['assetFiles']={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'dimensions':Image.open(p).size} for p in assets.glob('*.png')}
(d/'qualification.json').write_text(json.dumps(q,indent=2))
print('MODELED DELIVERY COMPLETE',q['resourceCount'],q['resourceBytes'],flush=True)
