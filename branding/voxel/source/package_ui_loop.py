"""CPU farm UI loop packaging. No local image processing."""
import socket,sys,subprocess,json,os
from pathlib import Path
import OpenImageIO as oi
import numpy as np
assert socket.gethostname()=='ozzz-aosp'
source=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(exist_ok=True,parents=True)
assert (source/'sequence-complete.json').is_file()
sheet=np.zeros((256,4096,4),dtype=np.float32)
for i in range(16):
 png=out/('frame-%02d.png'%i)
 subprocess.run([sys.executable,'display_exr.py',str(source/('frame.%06d.exr'%(i+1))),str(png)],check=True)
 b=oi.ImageBufAlgo.resize(oi.ImageBuf(str(png)),roi=oi.ROI(0,256,0,256,0,1,0,4));sheet[:,i*256:(i+1)*256]=b.get_pixels(oi.FLOAT)
b=oi.ImageBuf(oi.ImageSpec(4096,256,4,oi.UINT8));b.set_pixels(oi.ROI(0,4096,0,256,0,1,0,4),np.ascontiguousarray(sheet));assert b.write(str(out/'nextcomp_motion.png'))
subprocess.run(['/usr/bin/python3','-c',"from PIL import Image;from pathlib import Path;p=Path(__import__('sys').argv[1]);f=[Image.open(p/('frame-%02d.png'%i)) for i in range(16)];f[0].save(p/'native-loop.webp',save_all=True,append_images=f[1:],duration=166,loop=0,lossless=True)",str(out)],check=True,env={k:v for k,v in os.environ.items() if k not in ["PYTHONHOME","PYTHONPATH"]})
print('PACKAGED ACTUAL16FRAME LOOP')
