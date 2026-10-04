"""Package original native RGBA renders on the registered CPU farm host only."""
import subprocess,sys,json,socket
from pathlib import Path
import OpenImageIO as oi
import numpy as np
assert socket.gethostname()=='ozzz-aosp'
root=Path(__file__).parent;out=root/'delivery';out.mkdir(exist_ok=True)
def png(exr,target):subprocess.run([sys.executable,str(root/'display_exr.py'),str(exr),str(target)],check=True)
def resize(src,dst,size):
 b=oi.ImageBuf(str(src));r=oi.ImageBufAlgo.resize(b,roi=oi.ROI(0,size,0,size,0,1,0,4));assert r.write(str(dst))
icons=root/'render/icons/frame.000001.exr';png(icons,out/'symbols-3840.png');resize(out/'symbols-3840.png',out/'symbols-1920.png',1920)
names=json.loads((root/'build/glyphs.json').read_text())['names'];atlas=oi.ImageBuf(str(out/'symbols-3840.png'));a=atlas.get_pixels(oi.FLOAT)
assets=out/'drawable-nodpi';assets.mkdir(exist_ok=True)
for i,name in enumerate(names):
 cell=a[(i//6)*640:(i//6+1)*640,(i%6)*640:(i%6+1)*640];b=oi.ImageBuf(oi.ImageSpec(640,640,4,oi.UINT8));b.set_pixels(oi.ROI(0,640,0,640,0,1,0,4),np.ascontiguousarray(cell));r=oi.ImageBufAlgo.resize(b,roi=oi.ROI(0,256,0,256,0,1,0,4));assert r.write(str(assets/('symbol_'+name.lower()+'.png')))
sheet=np.zeros((256,4096,4),dtype=np.float32)
for i in range(16):
 target=out/('logo-%02d.png'%i);png(root/('render/logo/frame.%06d.exr'%(i+1)),target)
 if i==0:
  target.rename(out/'logo-3840.png');target=out/'logo-3840.png';resize(target,out/'logo-1920.png',1920);resize(target,assets/'nextcomp_logo.png',512)
 b=oi.ImageBufAlgo.resize(oi.ImageBuf(str(target)),roi=oi.ROI(0,256,0,256,0,1,0,4));sheet[:,i*256:(i+1)*256]=b.get_pixels(oi.FLOAT)
 if i:target.unlink()
b=oi.ImageBuf(oi.ImageSpec(4096,256,4,oi.UINT8));b.set_pixels(oi.ROI(0,4096,0,256,0,1,0,4),np.ascontiguousarray(sheet));assert b.write(str(assets/'nextcomp_motion.png'))
(out/'delivery.json').write_text(json.dumps({'source':'Original Houdini Indie NextComp voxel v03','symbols':35,'frames':16,'fps':12,'nativeMaster':[3840,3840],'hd':[1920,1920],'alpha':'unassociated RGBA','host':socket.gethostname()},indent=2))
print('PACKAGED NEXTCOMP RGBA ASSETS')
