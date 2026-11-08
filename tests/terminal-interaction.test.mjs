import test from 'node:test';
import assert from 'node:assert/strict';
import {ActivityBoard} from '../server/activity-board.mjs';
test('retained terminal activity is current-turn scoped, bounded and never exposes stdin or mutates history',()=>{
 let now=1;const board=new ActivityBoard(()=>now++);
 const event={method:'item/commandExecution/terminalInteraction',params:{threadId:'a',turnId:'current',itemId:'old',stdin:'SECRET'}};
 board.observe(event);board.observe(event);
 assert.equal(board.snapshot().length,1);
 assert.equal(board.snapshot()[0].turnId,'current');
 assert.equal(JSON.stringify(board.snapshot()).includes('SECRET'),false);
 board.observe({method:'item/commandExecution/outputDelta',params:{threadId:'a',turnId:'old-turn',itemId:'old',delta:'OUTPUT'}});
 assert.equal(board.snapshot().length,1);
 board.observe({method:'turn/completed',params:{threadId:'b',turn:{id:'other'}}});
 assert.equal(board.snapshot()[0].state,'working');
 board.observe({method:'turn/completed',params:{threadId:'a',turn:{id:'current'}}});
 assert.equal(board.snapshot()[0].state,'finished');
 board.observe(event);board.disconnected();assert.equal(board.snapshot().length,0);
});
