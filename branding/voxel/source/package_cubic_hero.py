import socket,subprocess,sys
from pathlib import Path
import OpenImageIO as oi
assert socket.gethostname()=='ozzz-aosp'
r=Path('/dev/shm/nextcomp-ui/cube-hero');subprocess.run([sys.executable,'display_exr.py',str(r/'frame.000001.exr'),str(r/'hero.png')],check=True)
b=oi.ImageBufAlgo.resize(oi.ImageBuf(str(r/'hero.png')),roi=oi.ROI(0,512,0,512,0,1,0,4));assert b.write(str(r/'nextcomp_logo.png'))
