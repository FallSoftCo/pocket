import test from 'node:test';
import assert from 'node:assert/strict';
import {flipFlopPlan,transgrammaticFlipPlan} from '../web/flip-flop.mjs';
test('every lexical unit changes assignment and survives both complementary phases',()=>{
 const units=[{target:'Il',meaning:'The'},{target:' ',meaning:' ',literal:true},{target:'lavoro',meaning:'work'},{target:' ',meaning:' ',literal:true},{target:'continua',meaning:'continues'},{target:'.',meaning:'.',literal:true}];
 const plan=flipFlopPlan(units);assert.equal(plan.eligible,3);
 assert.deepEqual(plan.assignment(1),plan.assignment(0).map(x=>x==='it'?'en':'it'));
 for(const unit of units.filter(x=>!x.literal)){assert.equal(plan.forms.filter(x=>x.includes(unit.target)).length,1);assert.equal(plan.forms.filter(x=>x.includes(unit.meaning)).length,1);}
 assert.deepEqual(plan.assignment(0),['en','en','en']);assert.deepEqual(plan.assignment(1),['it','it','it']);
 assert.deepEqual(plan.forms,['The work continues.','Il lavoro continua.']);
 assert.deepEqual(flipFlopPlan(units).forms,plan.forms);
});
test('missing lexical equivalents cannot masquerade as completed immersion',()=>{
 assert.equal(flipFlopPlan([{target:'lavoro',meaning:''}]),null);
});

test('unpaired prose outside translated spans is incomplete rather than permanently English',()=>{
 assert.equal(transgrammaticFlipPlan('Hello world',{original:'Hello world',planVersion:'transgrammatic-english-italian-v2',text:'Ciao world',spans:[{start:0,end:5,source:'Hello',target:'Ciao',targetSegments:[{start:0,end:4,target:'Ciao',meaning:'Hello'}]}]}),null);
});

test('grammatical phrases flip atomically with complete complementary coverage',()=>{
 const source='Can you open the latest message in this conversation?';
 const target='Puoi aprire l’ultimo messaggio in questa conversazione';
 const pairs=[['Puoi aprire','Can-you open'],['l’ultimo messaggio','the latest message'],['in questa conversazione','in this conversation']];
 let offset=0;
 const targetSegments=pairs.flatMap(([target,meaning],i)=>{
  const parts=[];
  if(i){parts.push({start:offset,end:offset+1,target:' ',meaning:''});offset++;}
  parts.push({start:offset,end:offset+target.length,target,meaning});offset+=target.length;return parts;
 });
 const plan=transgrammaticFlipPlan(source,{original:source,planVersion:'transgrammatic-english-italian-v3',text:target+'?',spans:[{start:0,end:source.length-1,source:source.slice(0,-1),target,targetSegments}]});
 assert.ok(plan);assert.equal(plan.eligible,3);
 assert.deepEqual(plan.assignment(1),plan.assignment(0).map(x=>x==='it'?'en':'it'));
 for(const [it,en] of pairs){
  assert.equal(plan.forms.filter(form=>form.includes(it)).length,1);
  assert.equal(plan.forms.filter(form=>form.includes(en)).length,1);
 }
 assert.ok(plan.forms.every(form=>form.endsWith('?')));
});
