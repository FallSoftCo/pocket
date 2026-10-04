"""Original NextComp voxel symbol alphabet; authored geometry, no stock icon paths."""
import hou,math,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
ROOT=Path.cwd();OUT=ROOT/'build';OUT.mkdir(exist_ok=True)
hou.hipFile.clear(suppress_save_prompt=True);hou.setFps(12);hou.playbar.setFrameRange(1,16)
obj=hou.node('/obj');mat=hou.node('/mat')
colors={'gold':(1,.56,.005),'green':(.002,.40,.12),'pink':(.82,.07,.3),'coral':(1,.17,.11),'ivory':(.88,.88,.84),'graphite':(.035,.04,.05)}
mats={}
for name,color in colors.items():
 n=mat.createNode('mtlxstandard_surface',name);n.parmTuple('base_color').set(color);n.parm('specular_roughness').set(.26);n.parm('metalness').set(.35);mats[name]=n
names=['ArrowBack','ArrowDownward','ArrowForward','ArrowUpward','AttachFile','Build','Check','Computer','Edit','EditNote','ErrorOutline','ExpandLess','ExpandMore','FolderOpen','Inbox','Keyboard','Layers','Mic','MoreHoriz','MoreVert','NotificationsActive','NotificationsNone','OpenInNew','Pause','PhoneAndroid','PlayArrow','Psychology','Search','Send','Stop','Terminal','TravelExplore','Tune','Unarchive','VolumeUp','Logo']
# Authored symbol language: orthogonal contours and 24-cell voxel construction.
def line(x,y,a,b,c,d,w=1.1):
 vx,vy=c-a,d-b;t=max(0,min(1,((x-a)*vx+(y-b)*vy)/(vx*vx+vy*vy or 1)))
 return (x-a-t*vx)**2+(y-b-t*vy)**2<=w*w

def rect(x,y,a,b,c,d):return a<=x<=c and b<=y<=d

def ring(x,y,cx,cy,r,w=1):return abs(math.hypot(x-cx,y-cy)-r)<=w

def shape(name,x,y):
 if name.startswith('Arrow'):
  if name=='ArrowForward':x=24-x
  elif name=='ArrowUpward':x,y=y,x
  elif name=='ArrowDownward':x,y=24-y,x
  return line(x,y,5,12,20,12,1.3) or line(x,y,5,12,12,5,1.3) or line(x,y,5,12,12,19,1.3)
 if name in ['ExpandLess','ExpandMore']:
  if name=='ExpandMore':y=24-y
  return line(x,y,5,15,12,8,1.4) or line(x,y,12,8,19,15,1.4)
 if name=='Mic':return rect(x,y,9,4,15,13) or ((x-12)**2+(y-4)**2<=9) or (ring(x,y,12,11,7,1.2) and y>=11) or line(x,y,12,18,12,21) or line(x,y,8,21,16,21)
 if name=='Keyboard':return (rect(x,y,2,5,22,19) and not rect(x,y,4,7,20,17)) or any(rect(x,y,a,b,a+1.7,b+1.7) for b in [9,13] for a in [6,10,14,18]) or rect(x,y,7,16,17,17)
 if name=='Stop':return rect(x,y,5,5,19,19)
 if name=='Pause':return rect(x,y,6,4,10,20) or rect(x,y,14,4,18,20)
 if name in ['PlayArrow','Send']:
  return 6<=x<=20 and abs(y-12)<=(20-x)*.6
 if name in ['MoreHoriz','MoreVert']:
  if name=='MoreVert':x,y=y,x
  return any((x-a)**2+(y-12)**2<=3 for a in [5,12,19])
 if name=='Check':return line(x,y,4,12,10,18,1.5) or line(x,y,10,18,21,5,1.5)
 if name in ['Search','TravelExplore']:return ring(x,y,10,10,6,1.4) or line(x,y,15,15,21,21,1.5) or (name=='TravelExplore' and (line(x,y,5,10,15,10,.6) or line(x,y,10,4,10,16,.6)))
 if name in ['Computer','PhoneAndroid']:
  a,b,c,d=(3,4,21,17) if name=='Computer' else (7,2,17,22)
  return (rect(x,y,a,b,c,d) and not rect(x,y,a+2,b+2,c-2,d-2)) or (name=='Computer' and (line(x,y,12,17,12,21) or line(x,y,7,21,17,21)))
 if name in ['NotificationsActive','NotificationsNone']:return (ring(x,y,12,11,7,1.2) and y<=11) or line(x,y,5,11,4,18) or line(x,y,19,11,20,18) or line(x,y,4,18,20,18) or rect(x,y,10,20,14,21) or (name=='NotificationsActive' and (line(x,y,1,7,2,4,.7) or line(x,y,22,4,23,7,.7)))
 if name in ['Edit','EditNote']:return line(x,y,6,18,18,6,2) or rect(x,y,3,18,7,21) or (name=='EditNote' and line(x,y,3,22,21,22))
 if name=='Tune':return any(line(x,y,3,b,21,b,.8) or rect(x,y,a,b-2,a+3,b+2) for a,b in [(6,5),(15,12),(9,19)])
 if name in ['Layers','FolderOpen','Inbox','Unarchive']:
  if name=='Layers':return any(line(x,y,3,b,12,b-4) or line(x,y,12,b-4,21,b) or line(x,y,3,b,12,b+4) or line(x,y,12,b+4,21,b) for b in [9,16])
  if name=='FolderOpen':return line(x,y,3,20,21,20) or line(x,y,3,20,3,6) or line(x,y,3,6,10,6) or line(x,y,10,6,13,9) or line(x,y,13,9,21,9) or line(x,y,21,9,21,20)
  return line(x,y,3,8,3,20) or line(x,y,21,8,21,20) or line(x,y,3,20,21,20) or line(x,y,3,8,21,8) or (name=='Unarchive' and (line(x,y,12,17,12,3) or line(x,y,12,3,7,7) or line(x,y,12,3,17,7)))
 if name in ['Build','AttachFile']:
  if name=='Build':return line(x,y,5,19,17,7,1.8) or (ring(x,y,18,6,4,1.5) and not rect(x,y,17,1,23,5))
  return line(x,y,6,17,6,8) or ring(x,y,11,7,5,1) and y<8 or line(x,y,16,7,16,17) or ring(x,y,11,17,5,1) and y>=17 or line(x,y,11,7,11,17)
 if name=='Terminal':return (rect(x,y,2,4,22,20) and not rect(x,y,4,6,20,18)) or line(x,y,6,8,10,12) or line(x,y,10,12,6,16) or line(x,y,13,16,18,16)
 if name=='OpenInNew':return line(x,y,4,8,4,20) or line(x,y,4,20,17,20) or line(x,y,17,20,17,14) or line(x,y,11,13,21,3) or line(x,y,14,3,21,3) or line(x,y,21,3,21,10)
 if name=='ErrorOutline':return ring(x,y,12,12,9,1.2) or rect(x,y,11,6,13,13) or rect(x,y,11,16,13,18)
 if name=='VolumeUp':return rect(x,y,3,9,7,15) or (7<=x<=13 and abs(y-12)<=x-5) or (x>=16 and (ring(x,y,12,12,6,.9) or ring(x,y,12,12,9,.9)))
 if name=='Psychology':return ring(x,y,11,10,7,1.2) and y<14 or line(x,y,4,10,4,19) or line(x,y,4,19,13,19) or line(x,y,13,19,13,22) or line(x,y,18,10,22,14) or line(x,y,22,14,18,14) or rect(x,y,8,8,13,12)
 if name=='Logo':return rect(x,y,4,3,8,21) or rect(x,y,16,3,20,21) or line(x,y,7,5,17,19,2)
 raise ValueError(name)

geo_code='''import hou
geo=hou.pwd().geometry()
geo.addAttrib(hou.attribType.Prim, 'shop_materialpath', '')
for cx,cy,cz,dx,dy,dz,material,side_material in CELLS:
 ps=[geo.createPoint() for _ in range(8)]
 for p,(a,b,c) in zip(ps,[(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]):p.setPosition((cx+a*dx/2,cy+b*dy/2,cz+c*dz/2))
 for face_index,ids in enumerate([(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]):
  poly=geo.createPolygon()
  for i in ids:poly.addVertex(ps[i])
  poly.setAttribValue("shop_materialpath", material if face_index==1 else side_material)
'''
objects=[]
for index,name in enumerate(names):
 g=obj.createNode('geo','glyph_'+name)
 for child in g.children():child.destroy()
 cells=[]
 for yi in range(24):
  for xi in range(24):
   if shape(name,xi+.5,yi+.5):
    material='gold';side_material='gold'
    if name=='Logo':
     material='ivory';side_material='green' if xi<8 else 'gold' if xi>=16 else 'coral' if yi<12 else 'pink'
    cells.append(((xi-11.5)/24,(11.5-yi)/24,0,.041*.98,.041*.98,.30 if name=='Logo' else .22,mats[material].path(),mats[side_material].path()))
 py=g.createNode('python','authored_voxels');py.parm('python').set('CELLS='+repr(cells)+'\n'+geo_code)
 bevel=g.createNode('polybevel::3.0','soft_machined_edges');bevel.setInput(0,py)
 for key,value in [('offset',.005),('divisions',2)]:
  assert bevel.parm(key),key
  bevel.parm(key).set(value)
 bevel.setDisplayFlag(True);bevel.setRenderFlag(True)
 g.parm('shop_materialpath').set(mats['gold' if name!='Logo' else 'ivory'].path());g.parmTuple('r').set((-16,26,-4))
 if name!='Logo':g.parmTuple('t').set(((index%6-2.5)*1.4,(2.5-index//6)*1.4,0));objects.append(g.path())
 else:
  for component in ['ry','rx']:
   for frame in range(1,18):
    key=hou.Keyframe();key.setFrame(frame);key.setValue((26 if component=='ry' else -16)+6*math.sin((frame-1)*math.tau/16+(0 if component=='ry' else math.pi/2)));g.parm(component).setKeyframe(key)
  logo=g.path()
 assert bevel.geometry().intrinsicValue('primitivecount')>0,(name,bevel.errors())

def camera(name,eye,width):
 c=obj.createNode('cam',name);e=hou.Vector3(eye);c.parmTuple('t').set(eye);c.parmTuple('r').set(tuple(hou.Vector3((0,0,-1)).matrixToRotateTo(-e).extractRotates()));c.parm('projection').set('ortho');c.parm('orthowidth').set(width);c.parmTuple('res').set((3840,3840));return c
cam=camera('atlas_camera' ,(0,0,15),8.4);hero=camera('logo_camera',(0,0,6),1.4)
key=obj.createNode('hlight','raking_key');key.parm('light_type').set('sun');key.parmTuple('r').set((-30,-40,0));key.parm('vm_envangle').set(8);key.parmTuple('light_color').set((1,.94,.82));key.parm('light_intensity').set(.8)
fill=obj.createNode('envlight','cool_fill');fill.parmTuple('light_color').set((.58,.72,1));fill.parm('light_intensity').set(.10)
for name,c,paths in [('icons',cam,objects),('logo',hero,[logo])]:
 r=hou.node('/out').createNode('karma',name);r.parm('engine').set('xpu');r.parm('camera').set(c.path());r.parm('candobjects').set('');r.parm('objects').set(' '.join(paths));r.parm('excludeobjects').set(' '.join([g.path() for g in obj.children() if g.type().name()=='geo' and g.path() not in paths]));r.parm('override_camerares').set(1);r.parm('resolutionx').set(3840);r.parm('resolutiony').set(3840);r.parm('pathtracedsamples').set(32);r.parm('picture').set('$HIP/render/'+name+'.$F4.exr')
hou.setFrame(1);hou.hipFile.save(str(OUT/'asset.hiplc'))
(OUT/'glyphs.json').write_text(json.dumps({'names':names[:-1],'columns':6,'rows':6,'masterSize':3840,'cellSize':640,'logoFrames':16,'fps':12,'source':'Original procedural NextComp voxel construction'},indent=2))
(OUT/'runtime-report.json').write_text(json.dumps({'license':str(hou.licenseCategory()),'hostname':socket.gethostname(),'houdini':hou.applicationVersionString(),'glyphCount':len(objects),'primitives':sum(g.renderNode().geometry().intrinsicValue('primitivecount') for g in obj.children() if g.type().name()=='geo'),'alphaRequired':True},indent=2))
files={}
for p in OUT.iterdir():
 if p.is_file():files[p.name]={'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
(OUT/'asset-manifest.json').write_text(json.dumps({'scene':'asset.hiplc','files':files},indent=2))
print('NEXTCOMP NATIVE SYMBOL BUILD COMPLETE',flush=True)
