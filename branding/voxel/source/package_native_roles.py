"""Package the native Codex/User atlas on the allowed CPU host."""
import socket,subprocess,sys,json
from pathlib import Path
import OpenImageIO as oi
oi.attribute("threads",2)
import numpy as np
assert socket.gethostname()=='ozzz-aosp'
r=Path.cwd();out=r/'delivery';out.mkdir(exist_ok=True)
subprocess.run([sys.executable,'display_exr.py','render/icons/frame.000001.exr',str(out/'roles-3840.png')],check=True)
b=oi.ImageBuf(str(out/'roles-3840.png'));a=b.get_pixels(oi.FLOAT);hd=oi.ImageBufAlgo.resize(b,roi=oi.ROI(0,1920,0,1920,0,1,0,4));assert hd.write(str(out/'roles-1920.png'))
assets=out/'drawable-nodpi';assets.mkdir(exist_ok=True)
for i,name in enumerate(['codex','user']):
 c=a[960:2880,i*1920:(i+1)*1920];b=oi.ImageBuf(oi.ImageSpec(1920,1920,4,oi.UINT8));b.set_pixels(oi.ROI(0,1920,0,1920,0,1,0,4),np.ascontiguousarray(c));assert b.write(str(out/('symbol_'+name+'-1920.png')))
 b=oi.ImageBufAlgo.resize(b,roi=oi.ROI(0,256,0,256,0,1,0,4));assert b.write(str(assets/('symbol_'+name+'.png')))
(out/'delivery.json').write_text(json.dumps({'nativeAtlas':[3840,3840],'hdAtlas':[1920,1920],'individualNativeCrop':[1920,1920],'appGlyph':[256,256],'alpha':'RGBA','roles':['officialOpenAICodexVoxelInterpretation','originalNextCompUserSilhouette']},indent=2))
print('NATIVE ROLE ATLAS PACKAGED')
