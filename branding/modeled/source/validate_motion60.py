"""Read-only resource/ABI validation. Image inspection is separate from native UI qualification."""
from pathlib import Path
from PIL import Image,ImageChops
import json,hashlib,re,sys
root=Path(__file__).resolve().parents[3]
assets=root/'android/app/src/main/res/drawable-nodpi'
names=json.loads((root/'branding/modeled/qualification/objects-v06.json').read_text())['names']
assert len(names)==37 and len(set(names))==37
source=(root/'android/app/src/main/java/co/fallsoft/pocket/SymbolIcon.kt').read_text()
for name in names:assert f'"{name}" to R.drawable.symbol_{name.lower()}' in source,name
records={}
for name,count,cell,cols,perpage,stem in [(n,120,128,5,30,'symbol_motion_'+n.lower()) for n in names]+[(n,160,96,8,32,'symbol_spin_'+n.lower()) for n in ['Codex','Psychology']]+[('N',160,256,8,32,'nextcomp_motion'),('N_small',160,128,8,32,'nextcomp_motion_small')]:
 distinct=set();pages=[]
 for page in range((count+perpage-1)//perpage):
  path=assets/(stem+'_p'+str(page)+'.webp');im=Image.open(path);assert im.mode=='RGBA' and im.size==(cols*cell,perpage//cols*cell),(path,im.size,im.mode)
  transparent=im.getchannel('A').point([255]+[0]*255);clean=im.copy();clean.paste((0,0,0,0),mask=transparent);assert not ImageChops.difference(im,clean).convert('RGB').getbbox(), 'Undefined RGB under zero alpha'
  for index in range(perpage):
   if page*perpage+index>=count:break
   sprite=im.crop((index%cols*cell,index//cols*cell,(index%cols+1)*cell,(index//cols+1)*cell));a=sprite.getchannel('A');b=a.getbbox();assert a.getextrema()==(0,255) and b and b[0]>0 and b[1]>0 and b[2]<cell and b[3]<cell,(name,page,index,b)
   distinct.add(hashlib.sha256(sprite.tobytes()).hexdigest())
  pages.append({'file':path.name,'bytes':path.stat().st_size,'dimensions':im.size,'decodedBytes':im.width*im.height*4,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 assert len(distinct)==count,(name,len(distinct),count)
 records[name+('_spin' if stem.startswith('symbol_spin') else '')]={'frames':count,'distinctRasterFrames':len(distinct),'fps':60,'cell':cell,'pages':pages}
for name in names:
 im=Image.open(assets/('symbol_'+name.lower()+'.png'));assert im.size==(256,256) and im.mode=='RGBA' and im.getchannel('A').getextrema()==(0,255)
im=Image.open(assets/'nextcomp_motion.png');assert im.size==(256,256) and im.mode=='RGBA'
assert not list(assets.glob('symbol_motion_*.png')) and not list(assets.glob('symbol_spin_*.png')), 'Legacy long strips must not remain in the final package'
keep=(root/'android/app/src/main/res/raw/keep_symbol_motion.xml').read_text()
for pattern in ['symbol_motion_*','symbol_spin_*','nextcomp_motion_*']:assert pattern in keep
result={'verified':True,'symbols':37,'motionAssets':len(records),'atlasPages':sum(len(x['pages']) for x in records.values()),'atlasEncodedBytes':sum(p['bytes'] for x in records.values() for p in x['pages']),'cachedDecodedBudget':32*1024**2,'liveMemoryQualification':'Current/next pages are additionally held by each visible type; cache bound is not total live bitmap/GPU memory. Measure native process separately.','resources':records}
out=Path(sys.argv[1]) if len(sys.argv)>1 else root/'branding/modeled/qualification/motion60-resource-validation.json';out.write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='resources'},indent=2))
