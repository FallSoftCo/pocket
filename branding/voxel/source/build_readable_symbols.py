"""NextComp v06: original continuous silhouettes with optical small-size clarity.
Codex shape remains derived from the official OpenAI circle-terminal provenance.
"""
import hou,math,json,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
assert hou.licenseCategory()==hou.licenseCategoryType.Indie
root=Path.cwd();out=root/'build';out.mkdir(exist_ok=True)
source=(root/'build_roles.py').read_text();scope={'math':math};exec(source[source.index('def line('):source.index("geo_code=")],scope);shape=scope['shape']
ns=json.loads((root/'original-glyphs.json').read_text())['names']+['Codex','User']
hou.hipFile.clear(suppress_save_prompt=True);obj=hou.node('/obj');mat=hou.node('/mat')
materials={}
for name,color,metal in [('ivory',(.86,.92,1),.12),('graphite',(.032,.06,.09),.10),('cyan',(.025,.49,.66),.20)]:
 n=mat.createNode('mtlxstandard_surface',name);n.parmTuple('base_color').set(color);n.parm('specular_roughness').set(.33);n.parm('metalness').set(metal);materials[name]=n.path()
code='''import hou
geo=hou.pwd().geometry();geo.addAttrib(hou.attribType.Prim,'shop_materialpath','')
points={}
def point(x,y,z):
 key=(x,y,z)
 if key not in points:
  p=geo.createPoint();p.setPosition(((x-N/2)/N,(N/2-y)/N,z));points[key]=p
 return points[key]
def face(coords,material):
 p=geo.createPolygon()
 for x,y,z in coords:p.addVertex(point(x,y,z))
 p.setAttribValue('shop_materialpath',material)
for x,y in CELLS:
 face([(x,y,D),(x,y+1,D),(x+1,y+1,D),(x+1,y,D)],FRONT)
 face([(x,y,-D),(x+1,y,-D),(x+1,y+1,-D),(x,y+1,-D)],SIDE)
 for dx,dy,coords in [(-1,0,[(x,y,-D),(x,y+1,-D),(x,y+1,D),(x,y,D)]),(1,0,[(x+1,y,D),(x+1,y+1,D),(x+1,y+1,-D),(x+1,y,-D)]),(0,-1,[(x,y,D),(x+1,y,D),(x+1,y,-D),(x,y,-D)]),(0,1,[(x,y+1,-D),(x+1,y+1,-D),(x+1,y+1,D),(x,y+1,D)])]:
  if (x+dx,y+dy) not in CELLS:face(coords,SIDE)
'''
paths=[]
for i,name in enumerate(ns):
 n=96;cells={(x,y) for x in range(n) for y in range(n) if shape(name,(x+.5)*24/n,(y+.5)*24/n)}
 g=obj.createNode('geo','glyph_'+name)
 for c in g.children():c.destroy()
 py=g.createNode('python','continuous_silhouette');py.parm('python').set('N=96\nD=.045\nCELLS='+repr(cells)+'\nFRONT='+repr(materials['ivory'])+'\nSIDE='+repr(materials['cyan' if name in ['Mic','Keyboard','Send','Codex'] else 'graphite'])+'\n'+code)
 bevel=g.createNode('polybevel::3.0','optical_edge');bevel.setInput(0,py);bevel.parm('offset').set(.0025);bevel.parm('divisions').set(2);bevel.setDisplayFlag(True);bevel.setRenderFlag(True)
 g.parmTuple('r').set((-7,12,-1));g.parmTuple('t').set(((i%8-3.5)*1.2,(3.5-i//8)*1.2,0));paths.append(g.path())
 assert bevel.geometry().intrinsicValue('primitivecount')>0,(name,bevel.errors())
cam=obj.createNode('cam','atlas_camera');cam.parmTuple('t').set((0,0,15));cam.parm('projection').set('ortho');cam.parm('orthowidth').set(9.6);cam.parmTuple('res').set((3840,3840))
key=obj.createNode('hlight','soft_key');key.parm('light_type').set('sun');key.parmTuple('r').set((-25,-35,0));key.parm('vm_envangle').set(12);key.parmTuple('light_color').set((.94,.97,1));key.parm('light_intensity').set(1.1)
fill=obj.createNode('envlight','soft_fill');fill.parmTuple('light_color').set((.80,.9,1));fill.parm('light_intensity').set(.22)
r=hou.node('/out').createNode('karma','icons');r.parm('engine').set('xpu');r.parm('camera').set(cam.path());r.parm('candobjects').set('');r.parm('objects').set(' '.join(paths));r.parm('override_camerares').set(1);r.parm('resolutionx').set(3840);r.parm('resolutiony').set(3840);r.parm('pathtracedsamples').set(64);r.parm('picture').set('$HIP/render/icons.$F4.exr')
hou.setFrame(1);hou.hipFile.save(str(out/'asset.hiplc'))
(out/'glyphs.json').write_text(json.dumps({'names':ns,'columns':8,'rows':8,'masterSize':3840,'cellSize':480,'source':'Original NextComp continuous-silhouette v06; official Codex provenance retained'},indent=2))
(out/'runtime-report.json').write_text(json.dumps({'license':str(hou.licenseCategory()),'houdini':hou.applicationVersionString(),'glyphCount':len(ns),'style':'Ivory front, cyan primary sides, graphite edges; shared watertight silhouette meshes, no cell seams'},indent=2))
files={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.iterdir() if p.is_file()}
(out/'asset-manifest.json').write_text(json.dumps({'scene':'asset.hiplc','files':files},indent=2));print('READABLE SYMBOL BUILD COMPLETE',flush=True)
