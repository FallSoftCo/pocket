"""Package and inspect v06 on the authorized CPU farm host."""
import socket,subprocess,json,hashlib,os
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();d=root/'delivery';d.mkdir(exist_ok=True)
subprocess.run(['/opt/hfs22.0.429/bin/hython','display_exr.py','render/icons/frame.000001.exr',str(d/'symbols-3840.png')],check=True)
from PIL import Image,ImageDraw,ImageFont
im=Image.open(d/'symbols-3840.png');assert im.size==(3840,3840) and im.mode=='RGBA';im.resize((1920,1920),Image.Resampling.LANCZOS).save(d/'symbols-1920.png')
names=json.loads((root/'build/glyphs.json').read_text())['names'];assets=d/'drawable-nodpi';assets.mkdir(exist_ok=True);report={}
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13)
sheet=Image.new('RGB',(1200,5*126),(12,15,21));draw=ImageDraw.Draw(sheet)
for i,name in enumerate(names):
 cell=im.crop(((i%8)*480,(i//8)*480,(i%8+1)*480,(i//8+1)*480));box=cell.getchannel('A').getbbox();assert box
 crop=cell.crop(box);side=max(crop.size);canvas=Image.new('RGBA',(int(side*1.16),int(side*1.16)));canvas.alpha_composite(crop,((canvas.width-crop.width)//2,(canvas.height-crop.height)//2));canvas=canvas.resize((256,256),Image.Resampling.LANCZOS);p=assets/('symbol_'+name.lower()+'.png');canvas.save(p)
 x=(i%8)*150;y=(i//8)*126;draw.text((x+6,y+7),name,fill=(176,189,207),font=font)
 for size,dx in [(24,8),(32,45),(44,91)]:
  small=canvas.resize((size,size),Image.Resampling.LANCZOS);sheet.paste(small,(x+dx,y+34+(44-size)//2),small);draw.text((x+dx,y+88),str(size),fill=(96,124,143),font=font)
 a=canvas.getchannel('A');assert a.getextrema()==(0,255);report[name]={'size':[256,256],'nativeCrop':box,'alphaBounds':a.getbbox(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
sheet.save(d/'readability-24-32-44.png')
(d/'qualification.json').write_text(json.dumps({'verified':True,'symbols':len(names),'palette':'Ivory faces, cyan primary sidewalls, graphite secondary sidewalls','contactSheetSizes':[24,32,44],'nativeAtlas':[3840,3840],'hdAtlas':[1920,1920],'files':report},indent=2))
subprocess.run(['ffmpeg','-y','-threads','2','-loop','1','-i',str(d/'readability-24-32-44.png'),'-t','3','-vf','pad=1200:630:0:0,format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/'readability-review.mp4')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('READABLE SYMBOLS PACKAGED')
