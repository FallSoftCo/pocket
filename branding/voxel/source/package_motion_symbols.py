"""Farm-only full-spin reviews and fixed-framing readable app sprite strips."""
import socket,sys,subprocess,json,hashlib,tarfile
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
assert socket.gethostname()=='ozzz-aosp'
mode=sys.argv[1];n=16 if mode=='spin' else 12;root=Path.cwd();d=root/'delivery';d.mkdir(exist_ok=True);full=d/'frames-3840';hd=d/'frames-1920';full.mkdir(exist_ok=True);hd.mkdir(exist_ok=True)
names=json.loads((root/'build/glyphs.json').read_text())['names'];frames=[];receipt=json.loads((root/'render/icons/sequence-complete.json').read_text());assert receipt['verified'] and receipt['frames']==[1,n]
for i in range(n):
 p=full/('frame-%02d.png'%i);src=root/'render/icons'/('frame.%06d.exr'%(i+1));assert hashlib.sha256(src.read_bytes()).hexdigest()==receipt['files'][src.name]['sha256']
 subprocess.run(['/opt/hfs22.0.429/bin/hython','display_exr.py',str(src),str(p)],check=True)
 im=Image.open(p);assert im.mode=='RGBA' and im.size==(3840,3840);frames.append(im);im.resize((1920,1920),Image.Resampling.LANCZOS).save(hd/p.name)
assets=d/'drawable-nodpi';assets.mkdir(exist_ok=True);reports={}
for index,name in enumerate(names):
 cells=[im.crop(((index%8)*480,(index//8)*480,(index%8+1)*480,(index//8+1)*480)) for im in frames]
 boxes=[c.getchannel('A').getbbox() for c in cells];assert all(boxes)
 box=(min(b[0] for b in boxes),min(b[1] for b in boxes),max(b[2] for b in boxes),max(b[3] for b in boxes));side=int(max(box[2]-box[0],box[3]-box[1])*1.16);strip=Image.new('RGBA',(160*n,160))
 for i,c in enumerate(cells):
  crop=c.crop(box);canvas=Image.new('RGBA',(side,side));canvas.alpha_composite(crop,((side-crop.width)//2,(side-crop.height)//2));strip.alpha_composite(canvas.resize((160,160),Image.Resampling.LANCZOS),(i*160,0))
 p=assets/('symbol_motion_'+name.lower()+'.png');strip.save(p)
 if mode=='spin' and name in ['Codex','Psychology']:strip.save(assets/('symbol_spin_'+name.lower()+'.png'))
 reports[name]={'size':[160*n,160],'frames':n,'fps':6,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'fixedBounds':box,'distinctFrames':len({hashlib.sha256(strip.crop((i*160,0,(i+1)*160,160)).tobytes()).hexdigest() for i in range(n)})}
# Four rendered phases of primary controls for actual visual qualification.
keys=Image.new('RGB',(640,3*190),(12,15,21));draw=ImageDraw.Draw(keys)
for row,name in enumerate(['Mic','Keyboard','Send']):
 strip=Image.open(assets/('symbol_motion_'+name.lower()+'.png'))
 for col,index in enumerate([0,n//4,n//2,3*n//4]):
  frame=strip.crop((index*160,0,(index+1)*160,160));keys.paste(frame,(col*160,row*190),frame);draw.text((col*160+10,row*190+164),name+' phase '+str(index),fill=(210,220,232))
keys.save(d/'keyframes-review.png')
# Native atlas review, cropped only for its website display; full square masters retained.
preview=d/'preview-frames';preview.mkdir(exist_ok=True)
for i,im in enumerate(frames):
 p=preview/('frame-%02d.png'%i);im.crop((0,0,3840,2400)).resize((1920,1200),Image.Resampling.LANCZOS).save(p)
subprocess.run(['ffmpeg','-y','-threads','2','-framerate','6','-i',str(preview/'frame-%02d.png'),'-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/(mode+'-review.mp4'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
Image.open(preview/'frame-00.png').save(d/'poster.png')
# Transparent review makes the motion available without video background compositing.
web=[Image.open(preview/('frame-%02d.png'%i)).resize((960,600),Image.Resampling.LANCZOS) for i in range(n)];web[0].save(d/(mode+'-review.webp'),save_all=True,append_images=web[1:],duration=166,loop=0,lossless=True)
(d/'motion-qualification.json').write_text(json.dumps({'mode':mode,'symbols':len(names),'frames':n,'frameSize':[160,160],'fps':6,'nativeMaster':[3840,3840],'hd':[1920,1920],'staticFallback':'v06 unchanged','rotationDegrees':360 if mode=='spin' else [-12,12],'files':reports},indent=2))
with tarfile.open(d/'native-motion-masters.tar','w') as t:
 for p in [root/'render/icons',full,hd,assets,root/'build/asset.hiplc',d/'motion-qualification.json']:t.add(p,arcname=str(p.relative_to(root)))
with tarfile.open(d/'app-sprite-strips.tar','w') as t:t.add(assets,arcname='drawable-nodpi')
print('MOTION PACKAGED',mode,n,len(names))
