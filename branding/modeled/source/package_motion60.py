"""OZZZ-OS: true rendered 60fps, fixed crops, portable paged lossless WebP atlases."""
import socket,json,hashlib,subprocess,sys,tarfile
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();kind=sys.argv[1];d=root/'delivery';d.mkdir(exist_ok=True);assets=d/'drawable-nodpi';assets.mkdir(exist_ok=True)
folder={'swivel':'icons','spin':'spins','study':'studies','mark':'mark'}[kind]
source=root/'render'/folder
receipt_path=source/('source-collection.json' if (source/'source-collection.json').exists() else 'sequence-complete.json')
receipt=json.loads(receipt_path.read_text());assert receipt['verified']
master=d/(folder+'-masters');master.mkdir(exist_ok=True);small=d/(folder+'-review-frames');small.mkdir(exist_ok=True)
meta=json.loads((root/'build/objects.json').read_text());names=meta['names'] if kind in ['swivel','study'] else ['Codex','Psychology'] if kind=='spin' else ['N']
# A single Hython process converts the sequence, avoiding startup per frame.
subprocess.run(['/opt/hfs22.0.429/bin/hython','convert_sequence.py',folder,str(master)],check=True)
series={name:[] for name in names};boxes={name:[] for name in names}
for frame in range(receipt['frames'][0],receipt['frames'][1]+1):
 p=master/('frame.%06d.png'%frame);im=Image.open(p);assert im.size==(3840,3840) and im.mode=='RGBA'
 if kind in ['swivel','study']:
  cells=[im.crop((i%8*480,i//8*480,(i%8+1)*480,(i//8+1)*480)) for i in range(len(names))]
 elif kind=='spin':cells=[im.crop((i*1920,960,(i+1)*1920,2880)).resize((480,480),Image.Resampling.LANCZOS) for i in range(2)]
 else:cells=[im.resize((480,480),Image.Resampling.LANCZOS)]
 for name,cell in zip(names,cells):
  b=cell.getchannel('A').getbbox();assert b,name;boxes[name].append(b);series[name].append(cell)
 if kind in ['swivel','study']:review=im.crop((0,0,3840,2400)).resize((1920,1200),Image.Resampling.LANCZOS)
 elif kind=='spin':review=im.crop((0,960,3840,2880)).resize((960,480),Image.Resampling.LANCZOS)
 else:review=im.resize((512,512),Image.Resampling.LANCZOS)
 bg=Image.new('RGB',review.size,(14,22,30));bg.paste(review,(0,0),review);bg.save(small/('frame.%06d.png'%frame))
 # Native square UHD and HD deliveries, never upscaled.
 im.resize((1920,1920),Image.Resampling.LANCZOS).save(master/('frame.%06d.hd.png'%frame))
report={};sprites={};font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13)
for name,cells in series.items():
 bs=boxes[name]
 if kind in ['swivel','study']:
  x0=min(b[0] for b in bs);y0=min(b[1] for b in bs);x1=max(b[2] for b in bs);y1=max(b[3] for b in bs)
  side=int(max(x1-x0,y1-y0)*1.12);cx=(x0+x1)//2;cy=(y0+y1)//2
 elif kind=='spin':side=int(max(max(b[2]-b[0],b[3]-b[1]) for b in bs)*1.12);cx=cy=240
 else:side=480;cx=cy=240
 box=(cx-side//2,cy-side//2,cx-side//2+side,cy-side//2+side)
 sprites[name]=[im.crop(box).resize((256,256),Image.Resampling.LANCZOS) for im in cells]
 if kind=='study':continue
 if kind=='swivel':sprites[name][0].save(assets/('symbol_'+name.lower()+'.png'))
 cellsize=128 if kind=='swivel' else 96 if kind=='spin' else 256
 perpage=30 if kind=='swivel' else 32;columns=5 if kind=='swivel' else 8
 stem='symbol_motion_'+name.lower() if kind=='swivel' else 'symbol_spin_'+name.lower() if kind=='spin' else 'nextcomp_motion'
 rows=perpage//columns;distinct=set();padding=[]
 for page in range((len(cells)+perpage-1)//perpage):
  sheet=Image.new('RGBA',(columns*cellsize,rows*cellsize))
  for local in range(perpage):
   index=page*perpage+local
   if index>=len(cells):break
   sprite=sprites[name][index].resize((cellsize,cellsize),Image.Resampling.LANCZOS)
   alpha=sprite.getchannel('A');bound=alpha.getbbox();assert bound and bound[0]>0 and bound[1]>0 and bound[2]<cellsize and bound[3]<cellsize,(name,index,bound)
   distinct.add(hashlib.sha256(sprite.tobytes()).hexdigest());padding.append(bound)
   sheet.paste(sprite,(local%columns*cellsize,local//columns*cellsize))
  sheet.save(assets/(stem+'_p'+str(page)+'.webp'),lossless=True,exact=True,method=3)
  if kind=='mark':sheet.resize((columns*128,rows*128),Image.Resampling.LANCZOS).save(assets/('nextcomp_motion_small_p'+str(page)+'.webp'),lossless=True,exact=True,method=3)
 report[name]={'frames':len(cells),'distinctFrames':len(distinct),'fps':60,'cell':cellsize,'columns':columns,'pageFrames':perpage,'pages':(len(cells)+perpage-1)//perpage,'decodedPageBytes':columns*rows*cellsize*cellsize*4,'fixedCrop':box,'alphaPaddingVerified':True}
 if kind=='mark':sprites[name][0].save(assets/'nextcomp_motion.png')
if kind=='swivel':
 sheet=Image.new('RGB',(1440,710),(14,22,30));draw=ImageDraw.Draw(sheet)
 for i,name in enumerate(names):
  x=i%8*180;y=i//8*142;draw.text((x+5,y+5),name,font=font,fill=(221,232,241));dx=5
  for size in [16,20,24,32,44]:
   im=sprites[name][0].resize((size,size),Image.Resampling.LANCZOS);sheet.paste(im,(x+dx,y+32+(44-size)//2),im);draw.text((x+dx,y+87),str(size),font=font,fill=(135,162,178));dx+=size+8
 sheet.save(d/'readability-16-20-24-32-44.png')
if kind=='study':
 labels=json.loads((root/'build/studies.json').read_text());study=Image.new('RGB',(1620,9*196),(14,22,30));draw=ImageDraw.Draw(study)
 for row,label in enumerate(labels):
  theme=label[3];background=(236,232,219) if theme=='daylight' else (37,27,23) if theme=='warm' else (9,17,28) if theme=='midnight' else (14,22,30)
  foreground=(38,54,58) if theme=='daylight' else (221,232,241)
  draw.rectangle((0,row*196,1620,(row+1)*196),fill=background)
  draw.text((10,row*196+4),' / '.join(map(str,label)),font=font,fill=foreground)
  for col,name in enumerate(['Mic','Keyboard','Edit','NotificationsNone','Psychology','User','Build','PlayArrow','Send']):
   im=sprites[name][row].resize((140,140),Image.Resampling.LANCZOS);study.paste(im,(col*180+20,row*196+26),im);draw.text((col*180+15,row*196+170),name,font=font,fill=foreground)
 study.save(d/'perspective-lighting.png')
 themed=Image.new('RGB',(1440,3*710));td=ImageDraw.Draw(themed)
 for band,(frame,background,foreground) in enumerate([(6,(236,232,219),(38,54,58)),(7,(37,27,23),(221,232,241)),(8,(9,17,28),(221,232,241))]):
  td.rectangle((0,band*710,1440,(band+1)*710),fill=background)
  for i,name in enumerate(names):
   x=i%8*180;y=band*710+i//8*142;td.text((x+5,y+5),name,font=font,fill=foreground);dx=5
   for size in [16,20,24,32,44]:
    im=sprites[name][frame].resize((size,size),Image.Resampling.LANCZOS);themed.paste(im,(x+dx,y+32+(44-size)//2),im);td.text((x+dx,y+87),str(size),font=font,fill=foreground);dx+=size+8
 td.text((1020,band*710+580),labels[frame][3]+' / materials and lighting',font=font,fill=foreground)
 themed.save(d/'themed-readability.png')
 report={'studies':labels,'realSizeThemeSheet':'37 objects in each of three separate theme rigs at16/20/24/32/44pixels','interpretation':'side and rear are limits, not universal semantic recognition; UI range +/-30 yaw and moderate pitch'}
else:
 subprocess.run(['ffmpeg','-y','-threads','2','-framerate','60','-i',str(small/'frame.%06d.png'),'-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/(kind+'-60fps.mp4'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
(small/'frame.000001.png').replace(d/(kind+'-poster.png'))
(d/(kind+'-qualification.json')).write_text(json.dumps({'verified':True,'kind':kind,'fps':60 if kind!='study' else None,'objects':report,'renderReceipt':receipt,'resources':{p.name:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'size':Image.open(p).size} for p in assets.iterdir() if p.is_file()}},indent=2))
print('PACKAGED',kind,len(names),'real frames',receipt['frames'],flush=True)
