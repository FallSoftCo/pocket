"""Native render-object validation on the host that will actually render."""
import hou

def validate_render_objects(frames):
 reports=[]
 for frame in frames:
  hou.setFrame(frame);objects=[]
  for node in hou.node('/obj').children():
   if node.type().name()!='geo' or not node.isDisplayFlagSet():continue
   sop=node.renderNode()
   if sop is None:continue
   geometry=sop.geometry()
   errors=[]
   for upstream in (sop,)+tuple(sop.inputAncestors()):
    errors.extend(upstream.path()+': '+str(error) for error in upstream.errors())
   assert not errors,'Native render-object cook failed: '+'; '.join(errors)
   objects.append(dict(path=node.path(),points=geometry.intrinsicValue('pointcount'),primitives=geometry.intrinsicValue('primitivecount')))
  reports.append(dict(frame=frame,objects=objects))
 assert any(sum(x['primitives'] for x in report['objects'])>0 for report in reports),'No visible native geometry in any representative frame'
 return reports
