import socket,subprocess,sys
from pathlib import Path
import OpenImageIO as o
import numpy as np
assert socket.gethostname()=='ozzz-aosp'
r=Path('/dev/shm/nextcomp-ui');subprocess.run([sys.executable,'display_exr.py',str(r/'roles-proof/frame.000001.exr'),str(r/'roles-proof.png')],check=True)
a=o.ImageBuf(str(r/'roles-proof.png')).get_pixels(o.FLOAT)
for i,name in enumerate(['codex','user']):
 c=a[240:720,i*480:(i+1)*480];b=o.ImageBuf(o.ImageSpec(480,480,4,o.UINT8));b.set_pixels(o.ROI(0,480,0,480,0,1,0,4),np.ascontiguousarray(c));b=o.ImageBufAlgo.resize(b,roi=o.ROI(0,256,0,256,0,1,0,4));assert b.write(str(r/('symbol_'+name+'.png')))
