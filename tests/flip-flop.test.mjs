import test from 'node:test';
import assert from 'node:assert/strict';
import {flipFlopPlan} from '../web/flip-flop.mjs';
test('every lexical unit changes assignment and survives both complementary phases',()=>{
 const units=[{target:'Il',meaning:'The'},{target:' ',meaning:' ',literal:true},{target:'lavoro',meaning:'work'},{target:' ',meaning:' ',literal:true},{target:'continua',meaning:'continues'},{target:'.',meaning:'.',literal:true}];
 const plan=flipFlopPlan(units);assert.equal(plan.eligible,3);
 assert.deepEqual(plan.assignment(1),plan.assignment(0).map(x=>x==='it'?'en':'it'));
 for(const unit of units.filter(x=>!x.literal)){assert.equal(plan.forms.filter(x=>x.includes(unit.target)).length,1);assert.equal(plan.forms.filter(x=>x.includes(unit.meaning)).length,1);}
 assert.ok(Math.abs(plan.assignment(0).filter(x=>x==='it').length-plan.assignment(0).filter(x=>x==='en').length)<=1);
 assert.deepEqual(flipFlopPlan(units).forms,plan.forms);
});
test('missing lexical equivalents cannot masquerade as completed immersion',()=>{
 assert.equal(flipFlopPlan([{target:'lavoro',meaning:''}]),null);
});
