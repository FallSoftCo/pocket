import {Codex} from './codex.mjs';
const methods=new Set(['thread/read','thread/timeline/list','thread/turns/list','thread/items/list']);
// A separate transport keeps slow background lifecycle calls from serializing
// the phone's history reads. It never resumes a thread or starts a turn.
export function createTimelineConnection({client=new Codex()}={}){
 return {
  connect:()=>client.connect(),
  async call(method,params){
   if(!methods.has(method))throw Error('Timeline connection accepts history reads only.');
   await client.connect();
   return client.call(method,params);
  }
 };
}
