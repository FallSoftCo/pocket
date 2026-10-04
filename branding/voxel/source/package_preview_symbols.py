import socket,json
from pathlib import Path
import numpy as np
import OpenImageIO as o
assert socket.gethostname()=='ozzz-aosp'
r=Path.cwd();out=r/'proof/app';out.mkdir(exist_ok=True)
a=o.ImageBuf(str(r/'proof/icons.png')).get_pixels(o.FLOAT);names=json.loads((r/'build/glyphs.json').read_text())['names']
for i,n in enumerate(names):
 c=a[(i//6)*160:(i//6+1)*160,(i%6)*160:(i%6+1)*160];b=o.ImageBuf(o.ImageSpec(160,160,4,o.UINT8));b.set_pixels(o.ROI(0,160,0,160,0,1,0,4),np.ascontiguousarray(c));b=o.ImageBufAlgo.resize(b,roi=o.ROI(0,256,0,256,0,1,0,4));assert b.write(str(out/('symbol_'+n.lower()+'.png')))
b=o.ImageBufAlgo.resize(o.ImageBuf(str(r/'proof/logo.png')),roi=o.ROI(0,512,0,512,0,1,0,4));assert b.write(str(out/'nextcomp_logo.png'));b=o.ImageBufAlgo.resize(b,roi=o.ROI(0,256,0,256,0,1,0,4));c=b.get_pixels(o.FLOAT);s=np.tile(c,(1,16,1));b=o.ImageBuf(o.ImageSpec(4096,256,4,o.UINT8));b.set_pixels(o.ROI(0,4096,0,256,0,1,0,4),np.ascontiguousarray(s));assert b.write(str(out/'nextcomp_motion.png'))
