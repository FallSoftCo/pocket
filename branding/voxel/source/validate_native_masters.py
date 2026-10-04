"""Verify full native pixels and checksums on an allowed rendering host."""
import hashlib,json,socket,sys
from pathlib import Path
import OpenImageIO as oi
oi.attribute("threads",2)
import numpy as np
assert socket.gethostname()=='ozzz-aosp'
paths=[('symbols','voxel-v03','icons'),('roles','roles-v01','icons'),('cubicN','voxel-v05','logo')]
if len(sys.argv)>1:paths=[('symbols',sys.argv[1],'icons')]
report={}
for name,scene,kind in paths:
 root=Path('/aosp/houdini/nextcomp')/scene/'render'/kind
 receipt=json.loads((root/'sequence-complete.json').read_text())
 assert receipt['verified'] and receipt['engine']=='xpu' and receipt['width']==3840 and receipt['height']==3840
 assert receipt['license_category']=='licenseCategoryType.Indie'
 telemetry=receipt['gpu_telemetry'];assert max(x['util'] for x in telemetry)>0
 assert max(x['used_mib']/x['total_mib'] for x in telemetry)<.8
 files=[]
 for relative,marker in receipt['files'].items():
  p=root/relative;assert hashlib.sha256(p.read_bytes()).hexdigest()==marker['sha256']
  b=oi.ImageBuf(str(p));spec=b.spec();assert (spec.width,spec.height)==(3840,3840)
  assert all(c in spec.channelnames for c in 'RGBA')
  a=b.get_pixels(oi.FLOAT);assert a is not None and np.isfinite(a).all()
  alpha=a[:,:,list(spec.channelnames).index('A')]
  assert float(alpha.min())==0 and float(alpha.max())>.99
  assert not np.any(alpha[0]) and not np.any(alpha[-1]) and not np.any(alpha[:,0]) and not np.any(alpha[:,-1]),'Object touches render edge'
  files.append({'name':relative,'sha256':marker['sha256'],'bytes':marker['bytes'],'size':[3840,3840],'channels':list(spec.channelnames),'alphaRange':[float(alpha.min()),float(alpha.max())]})
 report[name]={'verified':True,'engine':'Karma XPU','license':'Houdini Indie22.0.429','nativeSize':[3840,3840],'samples':receipt['samples'],'frames':receipt['frames'],'files':files,'gpuPeakUtilization':max(x['util'] for x in telemetry),'gpuPeakVRAMMiB':max(x['used_mib'] for x in telemetry),'cpuFallbackDisabled':True}
(Path('/aosp/houdini/nextcomp')/sys.argv[1]/'native-qualification.json' if len(sys.argv)>1 else Path('/aosp/houdini/nextcomp/native-master-qualification.json')).write_text(json.dumps(report,indent=2))
print('FULL NATIVE MASTERS VERIFIED', {k:len(v['files']) for k,v in report.items()})
