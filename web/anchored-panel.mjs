const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
/** Geometry only. Use the visual viewport so an open keyboard/zoom cannot put
 * a panel's heading and Close outside the area the person can reach. */
export function panelFrame(viewport){
 const width=Math.max(1,viewport.width),height=Math.max(1,viewport.height),margin=Math.min(12,width/8,height/8);
 return {left:(viewport.left||0)+margin,top:(viewport.top||0)+margin,width:Math.min(320,width-2*margin),height:height-2*margin};
}
export function anchoredPanelPlacement(viewport,anchor,size){
 const frame=panelFrame(viewport),width=Math.min(size.width,frame.width),height=Math.min(size.height,frame.height);
 let top=anchor.top-height-8;
 if(top<frame.top&&anchor.bottom+8+height<=frame.top+frame.height)top=anchor.bottom+8;
 return {left:clamp(anchor.right-width,frame.left,frame.left+frame.width-width),top:clamp(top,frame.top,frame.top+frame.height-height),width:frame.width,maxHeight:frame.height};
}
