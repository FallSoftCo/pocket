"""Original assembled NextComp objects; no vector paths or silhouette extrusion.
Run only on OZZZ-OS through the farm geometry plan, Houdini Indie 22.
Coordinates: X right, Y up, Z toward viewer. Part-level editable mesh receipts.
"""
import hou, math, json, hashlib, socket
from pathlib import Path
assert socket.gethostname() == 'ozzz-aosp'
assert hou.licenseCategory() == hou.licenseCategoryType.Indie
root=Path.cwd(); out=root/'build'; out.mkdir(exist_ok=True)
hou.hipFile.clear(suppress_save_prompt=True); hou.setFps(60)
obj=hou.node('/obj'); mat=hou.node('/mat')
materials={}
for name,color,metal,rough in [('porcelain',(.83,.91,.95),.12,.30),('teal',(.018,.42,.48),.25,.30),('ink',(.018,.037,.054),.18,.32),('silver',(.42,.58,.64),.7,.24),('coral',(.94,.16,.10),.12,.33),('gold',(.92,.52,.075),.35,.29)]:
 n=mat.createNode('mtlxstandard_surface',name); n.parmTuple('base_color').set(color); n.parm('metalness').set(metal); n.parm('specular_roughness').set(rough); materials[name]=n.path()
names=['ArrowBack','ArrowDownward','ArrowForward','ArrowUpward','AttachFile','Build','Check','Computer','Edit','EditNote','ErrorOutline','ExpandLess','ExpandMore','FolderOpen','Inbox','Keyboard','Layers','Mic','MoreHoriz','MoreVert','NotificationsActive','NotificationsNone','OpenInNew','Pause','PhoneAndroid','PlayArrow','Psychology','Search','Send','Stop','Terminal','TravelExplore','Tune','Unarchive','VolumeUp','Codex','User']
receipts=[]; paths=[]
class Model:
 def __init__(self,name):
  self.name=name; self.geo=hou.Geometry(); self.geo.addAttrib(hou.attribType.Prim,'shop_materialpath',''); self.geo.addAttrib(hou.attribType.Prim,'name',''); self.parts=[]
 def mesh(self,label,verts,faces,material='porcelain'):
  pts=[]
  for v in verts:
   p=self.geo.createPoint(); p.setPosition(v); pts.append(p)
  for ids in faces:
   f=self.geo.createPolygon()
   for i in ids:f.addVertex(pts[i])
   f.setAttribValue('shop_materialpath',materials[material]); f.setAttribValue('name',label)
  self.parts.append({'part':label,'material':material,'vertices':len(verts),'faces':len(faces)})
 def sphere(self,label,c,r,material='porcelain',u=32,v=16):
  if isinstance(r,(int,float)):r=(r,r,r)
  verts=[(c[0]+r[0]*math.sin(math.pi*j/v)*math.cos(math.tau*i/u),c[1]+r[1]*math.cos(math.pi*j/v),c[2]+r[2]*math.sin(math.pi*j/v)*math.sin(math.tau*i/u)) for j in range(v+1) for i in range(u)]
  faces=[(j*u+i,j*u+(i+1)%u,(j+1)*u+(i+1)%u,(j+1)*u+i) for j in range(v) for i in range(u)]
  self.mesh(label,verts,faces,material)
 def lathe(self,label,profile,c=(0,0,0),material='porcelain',u=48):
  # Profile follows the complete cross-section, including hollow interior if present.
  verts=[(c[0]+r*math.cos(math.tau*i/u),c[1]+y,c[2]+r*math.sin(math.tau*i/u)) for r,y in profile for i in range(u)]
  faces=[(j*u+i,j*u+(i+1)%u,(j+1)*u+(i+1)%u,(j+1)*u+i) for j in range(len(profile)-1) for i in range(u)]
  self.mesh(label,verts,faces,material)
 def tube(self,label,points,r,material='porcelain',sides=12):
  verts=[]
  for k,pt in enumerate(points):
   t=hou.Vector3(points[min(k+1,len(points)-1)])-hou.Vector3(points[max(0,k-1)]);t=t.normalized()
   ref=hou.Vector3((0,0,1)) if abs(t[2])<.9 else hou.Vector3((0,1,0)); a=t.cross(ref).normalized(); b=t.cross(a).normalized()
   for i in range(sides):verts.append(tuple(hou.Vector3(pt)+r*(a*math.cos(math.tau*i/sides)+b*math.sin(math.tau*i/sides))))
  faces=[tuple(reversed(range(sides))),tuple((len(points)-1)*sides+i for i in range(sides))]
  faces += [(j*sides+i,j*sides+(i+1)%sides,(j+1)*sides+(i+1)%sides,(j+1)*sides+i) for j in range(len(points)-1) for i in range(sides)]
  self.mesh(label,verts,faces,material)
 def rod(self,label,a,b,r=.08,material='porcelain'):
  self.tube(label,[a,b],r,material);self.sphere(label+'_a',a,r,material,16,8);self.sphere(label+'_b',b,r,material,16,8)
 def box(self,label,c,size,material='porcelain',radius=.06):
  # Beveled physical housing, generated as a native box+bevel then merged.
  g=obj.createNode('geo','temporary_part');[n.destroy() for n in g.children()]
  b=g.createNode('box');b.parmTuple('size').set(size);b.parmTuple('t').set(c)
  e=g.createNode('polybevel::3.0');e.setInput(0,b);e.parm('offset').set(min(radius,min(size)*.30));e.parm('divisions').set(3)
  geo=e.geometry().freeze();assert not e.errors(),e.errors()
  for at,val in [('shop_materialpath',materials[material]),('name',label)]:
   geo.addAttrib(hou.attribType.Prim,at,'')
   for p in geo.prims():p.setAttribValue(at,val)
  self.geo.merge(geo); self.parts.append({'part':label,'material':material,'construction':'native beveled box','size':size});g.destroy()
 def arc(self,label,c,r,start,end,wire=.055,material='porcelain',z=0):
  self.tube(label,[(c[0]+r*math.cos(start+(end-start)*i/36),c[1]+r*math.sin(start+(end-start)*i/36),z) for i in range(37)],wire,material)
 def arrow(self,angle=0,center=(0,0),scale=1,label='arrow'):
  def p(x,y,z=0):return (center[0]+scale*(x*math.cos(angle)-y*math.sin(angle)),center[1]+scale*(x*math.sin(angle)+y*math.cos(angle)),z)
  self.rod(label+'_shaft',p(-.58,0),p(.47,0),.09*scale,'silver')
  self.rod(label+'_upper',p(.02,.42),p(.48,0),.11*scale)
  self.rod(label+'_lower',p(.48,0),p(.02,-.42),.11*scale)
 def pencil(self,offset=0):
  self.tube('hexagonal_barrel',[(-.36,-.38+offset,0),(.38,.40+offset,0)],.13,'gold',sides=6)
  self.rod('silver_ferrule',(.26,.28+offset,0),(.38,.40+offset,0),.14,'silver')
  self.sphere('eraser',(.43,.45+offset,0),(.13,.13,.13),'coral')
  # A tapered six-sided wood cone with a separate graphite lead.
  a=hou.Vector3((-.51,-.54+offset,0));b=hou.Vector3((-.36,-.38+offset,0));t=(b-a).normalized();u=t.cross(hou.Vector3((0,0,1))).normalized();v=t.cross(u)
  verts=[tuple(a)]+[tuple(b+.13*(u*math.cos(i*math.tau/6)+v*math.sin(i*math.tau/6))) for i in range(6)]
  self.mesh('sharpened_wood',verts,[(0,1+i,1+(i+1)%6) for i in range(6)]+[tuple(range(1,7))],'porcelain')
  self.tube('graphite_lead',[tuple(a),tuple(a+.055*t)],.028,'ink',sides=6)
 def tray(self):
  self.box('tray_floor',(0,-.45,0),(1.25,.16,.65),'teal')
  self.box('left_wall',(-.57,-.18,0),(.13,.48,.65))
  self.box('right_wall',(.57,-.18,0),(.13,.48,.65))
  self.box('back_wall',(0,-.18,-.28),(1.16,.48,.12))
  self.box('front_left',(-.42,-.26,.28),(.31,.31,.13))
  self.box('front_right',(.42,-.26,.28),(.31,.31,.13))
 def bell(self):
  self.lathe('hollow_spun_bell',[(0,.55),(.12,.54),(.26,.44),(.32,.23),(.35,-.12),(.48,-.35),(.50,-.42),(.44,-.45),(.39,-.35),(.29,-.14),(.27,.22),(.20,.37),(0,.43)],material='porcelain')
  self.sphere('clapper',(0,-.43,0),.14,'gold');self.sphere('crown',(0,.59,0),.105,'gold')
 def monitor(self,terminal=False):
  self.box('monitor_shell',(0,.12,0),(1.30,.93,.28))
  self.box('inset_screen',(0,.15,.156),(1.08,.68,.045),'ink',.04)
  if terminal:
   self.rod('prompt_a',(-.35,.32,.19),(-.15,.15,.19),.05,'teal');self.rod('prompt_b',(-.15,.15,.19),(-.35,-.02,.19),.05,'teal');self.rod('cursor',(.02,-.04,.19),(.32,-.04,.19),.045)
  else:
   self.box('screen_glow',(0,.15,.182),(.84,.48,.02),'teal',.01)
  self.rod('stand',(0,-.28,-.05),(0,-.52,-.05),.095,'silver');self.box('foot',(0,-.58,0),(.62,.12,.44),'silver')
 def globe(self):
  self.sphere('globe',(0,.08,-.06),.48,'teal')
  for y,r in [(-.18,.40),(.08,.49),(.34,.40)]:
   self.tube('latitude',[(r*math.cos(i*math.tau/48),y,-.06+r*math.sin(i*math.tau/48)) for i in range(49)],.025,'porcelain')
  for angle in [0,math.pi/2]:
   self.tube('meridian',[(.49*math.sin(i*math.tau/48)*math.cos(angle),.08+.49*math.cos(i*math.tau/48),-.06+.49*math.sin(i*math.tau/48)*math.sin(angle)) for i in range(49)],.028)
for index,name in enumerate(names):
 m=Model(name)
 if name.startswith('Arrow'):
  m.arrow({'ArrowBack':math.pi,'ArrowForward':0,'ArrowUpward':math.pi/2,'ArrowDownward':-math.pi/2}[name])
 elif name in ['ExpandLess','ExpandMore','Check']:
  pts=[(-.47,-.19,0),(0,.27,0),(.47,-.19,0)] if name=='ExpandLess' else [(-.47,.19,0),(0,-.27,0),(.47,.19,0)] if name=='ExpandMore' else [(-.48,0,0),(-.13,-.35,.02),(.48,.43,0)]
  for i in range(2):m.rod('rounded_arm'+str(i),pts[i],pts[i+1],.105)
 elif name=='Mic':
  m.lathe('capsule_lower_housing',[(0,-.14),(.16,-.14),(.235,-.07),(.24,.18),(.23,.21),(0,.21)],material='teal')
  m.lathe('rounded_grille_shell',[(0,.18),(.236,.18),(.24,.32),(.23,.43),(.19,.53),(.11,.59),(0,.62)],material='silver')
  for y,r in [(.25,.242),(.35,.239),(.45,.223)]:
   m.lathe('circumferential_grille_vent',[(r-.005,y-.010),(r+.002,y-.010),(r+.002,y+.010),(r-.005,y+.010),(r-.005,y-.010)],material='ink')
  for x in [-.26,.26]:m.sphere('yoke_pivot',(x,.02,0),(.055,.08,.09),'gold')
  m.arc('suspension_yoke',(0,.02),.38,math.pi,math.tau,.065,'porcelain');m.rod('stem',(0,-.35,0),(0,-.52,0),.07,'silver');m.box('weighted_base',(0,-.57,0),(.57,.12,.38),'porcelain')
 elif name=='Keyboard':
  m.box('keyboard_case',(0,0,0),(1.43,.81,.26),'teal',.10)
  m.box('recessed_keybed',(0,0,.135),(1.28,.68,.045),'ink',.05)
  for y in [.22,0]:
   for x in [-.48,-.24,0,.24,.48]:m.box('raised_key',(x,y,.18),(.18,.16,.10),radius=.035)
  m.box('spacebar',(0,-.24,.18),(.78,.13,.10),radius=.035)
 elif name in ['NotificationsActive','NotificationsNone']:
  m.bell()
  if name=='NotificationsActive':
   for sign in [-1,1]:m.arc('ringing_arc',(0,.05),.67, .20 if sign==1 else math.pi-.8,.8 if sign==1 else math.pi-.2,.055,'teal')
 elif name=='Computer':m.monitor()
 elif name=='Terminal':m.monitor(True)
 elif name=='PhoneAndroid':
  m.box('phone_body',(0,0,0),(.72,1.27,.25),'porcelain',.12);m.box('screen',(0,.03,.145),(.56,.95,.04),'teal',.06);m.rod('earpiece',(-.09,.54,.16),(.09,.54,.16),.025,'ink');m.sphere('home_button',(0,-.53,.15),.045,'ink')
 elif name in ['Edit','EditNote']:
  if name=='EditNote':
   m.box('note_pad',(0,-.09,-.13),(1.02,.99,.12),'porcelain')
   for y in [-.30,-.11,.08]:m.rod('ruled_line',(-.36,y,-.05),(.25,y,-.05),.025,'teal')
  m.pencil(.07 if name=='EditNote' else 0)
 elif name=='Search':
  m.arc('lens_rim',(-.13,.14),.39,0,math.tau,.085,'porcelain',.05)
  m.sphere('convex_lens',(-.13,.14,.00),(.33,.33,.10),'teal');m.rod('handle',(.15,-.14,0),(.52,-.52,0),.11,'silver')
 elif name=='TravelExplore':
  m.globe();m.arc('search_rim',(.22,-.12),.26,0,math.tau,.052,'porcelain',.49);m.rod('search_handle',(.40,-.31,.49),(.62,-.55,.49),.072,'silver')
 elif name=='Build':
  m.rod('forged_handle',(-.45,-.48,0),(.26,.28,0),.125,'silver');m.sphere('handle_end',(-.45,-.48,0),.16,'silver')
  m.arc('forged_open_head',(.32,.32),.235,math.pi/2,math.tau,.105,'porcelain')
 elif name=='AttachFile':
  pts=[]
  for cx,cy,r,a,b in [(-.01,.27,.26,0,math.pi),(-.01,-.28,.26,math.pi,math.tau),(.065,.20,.18,0,math.pi)]:
   pts.extend([(cx+r*math.cos(a+(b-a)*i/24),cy+r*math.sin(a+(b-a)*i/24),.035*len(pts)/100) for i in range(25)])
  pts.append((-.115,-.23,.08));m.tube('bent_wire_paperclip',pts,.065,'silver')
 elif name=='FolderOpen':
  m.box('folder_back',(0,.12,-.19),(1.26,.90,.10),'teal');m.box('folder_tab',(-.35,.58,-.19),(.47,.20,.10),'teal')
  m.box('paper',(0,.04,-.07),(1.02,.65,.07))
  m.mesh('sloped_front',[(-.64,-.48,-.18),(.64,-.48,-.18),(.72,.26,.30),(-.72,.26,.30),(-.64,-.48,-.10),(.64,-.48,-.10),(.72,.26,.38),(-.72,.26,.38)],[(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)],'porcelain')
 elif name in ['Inbox','Unarchive']:
  m.tray()
  if name=='Unarchive':m.arrow(math.pi/2,(0,.23),.65,'restore_arrow')
  else:
   m.box('incoming_sheet',(0,.05,-.03),(.72,.45,.07),'silver')
 elif name=='Layers':
  for y,z in [(-.36,.19),(0,0),(.36,-.19)]:m.box('stacked_tile',(0,y,z),(1.05,.13,.72),'porcelain')
 elif name in ['MoreHoriz','MoreVert']:
  for p in [-.43,0,.43]:m.sphere('spherical_button',(p,0,0) if name=='MoreHoriz' else (0,p,0),.145)
 elif name=='Tune':
  for y,x in [(.40,-.30),(0,.26),(-.40,-.06)]:
   m.rod('slider_rail',(-.59,y,-.04),(.59,y,-.04),.048,'silver');m.box('slider_cap',(x,y,.055),(.23,.27,.22),'porcelain')
 elif name in ['Stop','Pause','PlayArrow']:
  if name=='Stop':m.box('stop_key',(0,0,0),(.94,.94,.47),'porcelain',.13)
  elif name=='Pause':
   for x in [-.25,.25]:m.box('pause_key',(x,0,0),(.27,1.06,.42),'porcelain',.09)
  else:
   # A closed biconvex ceramic play pebble; volume varies continuously over its face.
   corners=[(-.40,-.57),(-.40,.57),(.59,0)];outline=[]
   for i,b in enumerate(corners):
    a=corners[(i-1)%3];c=corners[(i+1)%3]
    first=(.82*b[0]+.18*a[0],.82*b[1]+.18*a[1]);last=(.82*b[0]+.18*c[0],.82*b[1]+.18*c[1])
    for k in range(8):
     t=k/7;outline.append(((1-t)**2*first[0]+2*t*(1-t)*b[0]+t*t*last[0],(1-t)**2*first[1]+2*t*(1-t)*b[1]+t*t*last[1]))
   u=len(outline);v=20
   verts=[(x*math.sin(math.pi*j/v),y*math.sin(math.pi*j/v),.30*math.cos(math.pi*j/v)) for j in range(v+1) for x,y in outline]
   faces=[(j*u+i,(j+1)*u+i,(j+1)*u+(i+1)%u,j*u+(i+1)%u) for j in range(v) for i in range(u)]
   m.mesh('biconvex_triangular_key',verts,faces,'porcelain')
 elif name=='Send':
  # Folded paper plane: intersecting wing panels, raised center ridge, belly and tail.
  m.mesh('folded_wings',[(-.63,.46,.04),(.66,0,.10),(-.63,-.46,.04),(-.28,0,.34),(-.63,.46,-.02),(.66,0,.04),(-.63,-.46,-.02),(-.28,0,.28)],[(0,1,3),(3,1,2),(4,7,5),(7,6,5),(0,4,5,1),(1,5,6,2),(0,3,7,4),(3,2,6,7)],'porcelain')
  m.rod('center_keel',(-.28,0,.30),(.59,0,.10),.035,'teal')
 elif name=='OpenInNew':
  m.rod('frame_left',(-.49,.25,-.13),(-.49,-.49,-.13),.065,'silver');m.rod('frame_bottom',(-.49,-.49,-.13),(.29,-.49,-.13),.065,'silver');m.rod('frame_right',(.29,-.49,-.13),(.29,-.18,-.13),.065,'silver');m.arrow(math.pi/4,(.15,.18),.70)
 elif name=='ErrorOutline':
  m.arc('round_warning_rim',(0,0),.58,0,math.tau,.08,'coral')
  m.rod('exclamation_stem',(0,.30,.04),(0,-.08,.04),.072);m.sphere('exclamation_dot',(0,-.30,.04),.09)
 elif name=='VolumeUp':
  m.box('speaker_driver',(-.42,0,0),(.28,.43,.37),'teal')
  m.mesh('flared_horn',[(-.30,-.20,-.16),(-.30,.20,-.16),(-.30,.20,.16),(-.30,-.20,.16),(.05,-.48,-.30),(.05,.48,-.30),(.05,.48,.30),(.05,-.48,.30)],[(0,1,2,3),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0),(4,7,6,5)],'porcelain')
  for r in [.47,.70]:m.arc('sound_wave',(-.06,0),r,-.70,.70,.048,'silver')
 elif name=='Psychology':
  m.sphere('cranium',(-.09,.17,-.08),(.44,.47,.34));m.box('neck',(-.08,-.40,-.10),(.39,.36,.37),'porcelain')
  m.sphere('nose',(.36,.07,.02),(.17,.13,.16));m.box('jaw',(.10,-.21,0),(.50,.25,.42),'porcelain')
  # Broad cerebral hemispheres with raised gyri, rather than a cluster of marbles.
  for x in [-.23,.08]:
   m.sphere('cerebral_hemisphere',(x,.36,.12),(.21,.23,.27),'teal')
   for y in [.26,.39,.49]:
    fold=[]
    for i in range(21):
     dx=-.14+.28*i/20;yy=y+.03*math.sin(i*math.pi/10)
     zz=.12+.27*math.sqrt(max(0,1-(dx/.21)**2-((yy-.36)/.23)**2))+.012
     fold.append((x+dx,yy,zz))
    m.tube('cortical_fold',fold,.025,'teal')
  m.tube('hemisphere_fissure',[(-.075,.22,.22),(-.075,.36,.302),(-.075,.50,.22)],.018,'ink')
 elif name=='User':
  m.sphere('head',(0,.33,0),(.26,.30,.26));m.lathe('neck',[(.12,-.14),(.13,.12)],c=(0,0,-.02),material='porcelain');m.sphere('shoulder_bust',(0,-.31,-.02),(.55,.32,.32),'teal')
 elif name=='Codex':
  # Official circle-terminal meaning retained as a solid machined torus and rods.
  m.arc('terminal_ring',(0,0),.59,0,math.tau,.105,'porcelain')
  m.rod('prompt_upper',(-.25,.20,.10),(-.05,0,.10),.065,'teal');m.rod('prompt_lower',(-.05,0,.10),(-.25,-.20,.10),.065,'teal');m.rod('cursor',(.10,-.18,.10),(.30,-.18,.10),.058,'teal')
 else:raise ValueError(name)
 file=out/(name+'.bgeo.sc');m.geo.saveToFile(str(file))
 g=obj.createNode('geo','symbol_'+name);[n.destroy() for n in g.children()]
 f=g.createNode('file','assembled_parts');f.parm('file').set('$HIP/'+name+'.bgeo.sc')
 n=g.createNode('normal','surface_normals');n.setInput(0,f);n.parm('cuspangle').set(50);n.setDisplayFlag(True);n.setRenderFlag(True)
 for frame in range(1,121):
  phase=math.tau*(frame-1)/120
  for axis,value in [('rx',-9+3*(math.cos(phase)-1)),('ry',16+9*math.sin(phase)),('rz',-2)]:
   k=hou.Keyframe();k.setFrame(frame);k.setValue(value);g.parm(axis).setKeyframe(k)
 g.parmTuple('t').set(((index%8-3.5)*1.85,(3.5-index//8)*1.85,0));paths.append(g.path())
 bounds=m.geo.boundingBox();receipts.append({'name':name,'parts':m.parts,'primitives':len(m.geo.prims()),'bounds':list(bounds.sizevec()),'construction':'assembled volumetric surfaces; no filled-outline extrusion'})
cam=obj.createNode('cam','atlas_camera');cam.parmTuple('t').set((0,0,20));cam.parm('projection').set('ortho');cam.parm('orthowidth').set(14.8);cam.parmTuple('res').set((3840,3840))
key=obj.createNode('hlight','large_key');key.parm('light_type').set('sun');key.parmTuple('r').set((-30,-35,0));key.parm('vm_envangle').set(14);key.parm('light_intensity').set(1.5)
fill=obj.createNode('envlight','cool_fill');fill.parmTuple('light_color').set((.66,.81,1));fill.parm('light_intensity').set(.32)
r=hou.node('/out').createNode('karma','icons');r.parm('engine').set('xpu');r.parm('camera').set(cam.path());r.parm('candobjects').set('');r.parm('objects').set(' '.join(paths));r.parm('override_camerares').set(1);r.parm('resolutionx').set(3840);r.parm('resolutiony').set(3840);r.parm('pathtracedsamples').set(48);r.parm('picture').set('$HIP/../render/icons/frame.$F6.exr')
hou.setFrame(1);hou.hipFile.save(str(out/'objects.hiplc'))
(out/'objects.json').write_text(json.dumps({'names':names,'columns':8,'cellSize':480,'masterSize':3840,'fps':60,'swivelFrames':120,'geometry':receipts,'palette':list(materials),'host':socket.gethostname(),'license':str(hou.licenseCategory()),'version':hou.applicationVersionString()},indent=2))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file()}
(out/'asset-manifest.json').write_text(json.dumps({'scene':'objects.hiplc','files':files},indent=2))
print('MODELED OBJECTS BUILT',len(names),flush=True)
