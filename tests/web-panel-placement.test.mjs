import test from 'node:test';
import assert from 'node:assert/strict';
import {panelFrame,anchoredPanelPlacement} from '../web/anchored-panel.mjs';
test('large narrow account pane stays within viewport instead of anchoring its Close above it',()=>{
 const viewport={width:360,height:900},frame=panelFrame(viewport);
 const p=anchoredPanelPlacement(viewport,{top:435,bottom:895,right:348},{width:320,height:876});
 assert.ok(p.top>=12);assert.ok(p.top+Math.min(876,p.maxHeight)<=888);assert.ok(p.left>=12);assert.ok(p.left+p.width<=348);
 assert.equal(frame.height,876);
});
test('available below placement is used when the control is near the top',()=>{
 const p=anchoredPanelPlacement({width:1280,height:900},{top:20,bottom:64,right:200},{width:320,height:400});
 assert.equal(p.top,72);assert.equal(p.left,12);
});
test('keyboard/zoom visual viewport offsets bound the complete pane and header',()=>{
 const v={left:70,top:120,width:320,height:360},p=anchoredPanelPlacement(v,{top:430,bottom:478,right:600},{width:500,height:700});
 assert.equal(p.top,132);assert.equal(p.left,82);assert.equal(p.width,296);assert.equal(p.maxHeight,336);
});
test('a clamped bottom-right panel does not overflow a small retained viewport',()=>{
 const p=anchoredPanelPlacement({width:320,height:500},{top:490,bottom:538,right:1000},{width:296,height:200});
 assert.equal(p.left,12);assert.ok(p.top>=12);assert.ok(p.top+200<=488);
});
