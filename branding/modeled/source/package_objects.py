"""OZZZ-OS packaging: verify native masters; fixed crop across motion; delivery-size reviews."""
import json,hashlib,socket,subprocess
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();d=root/'delivery';d.mkdir(exist_ok=True);assets=d/'drawable-nodpi';assets.mkdir(exist_ok=True)
meta=json.loads((root/'build/objects.json').read_text());names=meta['names'];receipt=json.loads((root/'render/icons/sequence-complete.json').read_text());assert receipt['verified']
frames=[]
for n in range(receipt['frames'][0],receipt['frames'][1]+1):
 src=root/'render/icons'/('frame.%06d.exr'%n);assert hashlib.sha256(src.read_bytes()).hexdigest()==receipt['files'][src.name]['sha256']
 target=d/('atlas-%02d-3840.png'%n)
 subprocess.run(['/opt/hfs22.0.429/bin/hython','display_exr.py',str(src),str(target),'--exposure','.85'],check=True)
 im=Image.open(target);assert im.size==(3840,3840) and im.mode=='RGBA';frames.append(im.copy());im.resize((1920,1920),Image.Resampling.LANCZOS).save(d/('atlas-%02d-1920.png'%n))
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13)
sheet=Image.new('RGB',(1440,5*142),(14,22,30));draw=ImageDraw.Draw(sheet);report={};bounds={}
for i,name in enumerate(names):
 cells=[im.crop(((i%8)*480,(i//8)*480,(i%8+1)*480,(i//8+1)*480)) for im in frames];boxes=[im.getchannel('A').getbbox() for im in cells];assert all(boxes),name
 x0=min(b[0] for b in boxes);y0=min(b[1] for b in boxes);x1=max(b[2] for b in boxes);y1=max(b[3] for b in boxes)
 side=int(max(x1-x0,y1-y0)*1.10);center=((x0+x1)//2,(y0+y1)//2);box=(center[0]-side//2,center[1]-side//2,center[0]-side//2+side,center[1]-side//2+side);bounds[name]=box
 sprites=[im.crop(box).resize((256,256),Image.Resampling.LANCZOS) for im in cells]
 p=assets/('symbol_'+name.lower()+'.png');sprites[0].save(p)
 if len(sprites)==12:
  strip=Image.new('RGBA',(160*12,160))
  for k,im in enumerate(sprites):strip.paste(im.resize((160,160),Image.Resampling.LANCZOS),(160*k,0))
  strip.save(assets/('symbol_motion_'+name.lower()+'.png'))
 x=(i%8)*180;y=(i//8)*142;draw.text((x+5,y+5),name,fill=(221,232,241),font=font)
 dx=5
 for size in [16,20,24,32,44]:
  small=sprites[0].resize((size,size),Image.Resampling.LANCZOS);sheet.paste(small,(x+dx,y+32+(44-size)//2),small);draw.text((x+dx,y+87),str(size),fill=(135,162,178),font=font);dx+=size+8
 a=sprites[0].getchannel('A');assert a.getextrema()==(0,255)
 report[name]={'crop':box,'alphaBounds':a.getbbox(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'size':[256,256]}
sheet.save(d/'readability-16-20-24-32-44.png')
# Synthetic context compositions using the actual resources and actual dp sizes.
context=Image.new('RGB',(720,600),(14,22,30));dr=ImageDraw.Draw(context);large=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',22)
def icon(name,xy,size):
 im=Image.open(assets/('symbol_'+name.lower()+'.png')).resize((size,size),Image.Resampling.LANCZOS);context.paste(im,xy,im)
def text(s,xy):dr.text(xy,s,font=large,fill=(227,237,242))
dr.rounded_rectangle((20,20,700,222),radius=18,fill=(25,39,50));icon('Psychology',(36,40),24);text('Working · Modeled symbols',(76,40));icon('NotificationsActive',(646,42),24);icon('Codex',(40,106),18);text('Building the microphone and keyboard',(78,101));text('Synthetic session card · display sizes preserved',(40,158))
dr.rounded_rectangle((20,244,700,408),radius=18,fill=(25,39,50));icon('ArrowBack',(40,260),32);text('Conversation',(92,263));icon('Tune',(646,260),24);icon('User',(40,323),20);text('Make the forms readable at mobile size.',(82,320));icon('VolumeUp',(646,357),30)
for name,x in [('Mic',190),('Keyboard',338),('ArrowUpward',486)]:dr.rounded_rectangle((x-16,443,x+60,520),radius=20,fill=(31,55,64));icon(name,(x,459),44)
icon('Computer',(40,558),20);text('68%   ·   Reset 10:30',(90,549));icon('Layers',(650,555),24)
context.save(d/'synthetic-contexts.png')
(d/'qualification.json').write_text(json.dumps({'verified':True,'symbols':37,'motionFrames':len(frames),'nativeAtlas':[3840,3840],'hdAtlas':[1920,1920],'reviewSizesPx':[16,20,24,32,44],'contextEvidence':'synthetic raster composition, not native UI capture','files':report,'nativeRenderReceipt':receipt},indent=2))
(d/'crop-bounds.json').write_text(json.dumps(bounds,indent=2))
print('MODELED OBJECTS PACKAGED',len(names),len(frames),flush=True)
