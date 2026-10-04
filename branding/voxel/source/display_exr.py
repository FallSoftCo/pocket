import argparse
import OpenImageIO as o
o.attribute("threads",2)
import numpy as np
p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('target');p.add_argument('--exposure',type=float,default=1);a=p.parse_args()
b=o.ImageBuf(a.source);v=b.get_pixels(o.FLOAT);assert v is not None and not b.has_error
s=b.spec();c=list(s.channelnames);alpha=np.clip(v[:,:,c.index('A')],0,1);rgb=np.maximum(v[:,:,[c.index(x) for x in 'RGB']],0)
rgb=np.divide(rgb,np.maximum(alpha[:,:,None],1e-8),out=np.zeros_like(rgb),where=alpha[:,:,None]>1e-8)*a.exposure
rgb=np.clip(rgb*(2.51*rgb+.03)/(rgb*(2.43*rgb+.59)+.14),0,1);rgb=np.where(rgb<=.0031308,12.92*rgb,1.055*rgb**(1/2.4)-.055)
spec=o.ImageSpec(s.width,s.height,4,o.UINT8);spec.attribute('oiio:UnassociatedAlpha',1);spec.attribute('oiio:ColorSpace','sRGB')
b=o.ImageBuf(spec);assert b.set_pixels(o.ROI(0,s.width,0,s.height,0,1,0,4),np.ascontiguousarray(np.dstack([rgb,alpha]),dtype=np.float32));assert b.write(a.target)
