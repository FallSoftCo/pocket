import {Codex} from './codex.mjs';
const methods=new Set(['thread/read','thread/timeline/list','thread/turns/list','thread/items/list']);
// History RPCs have no connection-owned lifecycle state. A fresh transport for
// each read prevents a stalled legacy request from blocking another session.
// Control, subscriptions and turn admission stay on their original connection.
export function createTimelineConnection({client=null,createClient=()=>new Codex()}={}){
 return {
  connect:async()=>{},
  async call(method,params){
   if(!methods.has(method))throw Error('Timeline connection accepts history reads only.');
   const connection=client||createClient();
   try{
    await connection.connect();
    return await connection.call(method,params);
   }finally{if(!client)connection.ws?.close();}
  }
 };
}
