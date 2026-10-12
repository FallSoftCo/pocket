import {randomUUID} from 'node:crypto';
/** Transport evidence only: no runtime reads, worker activation or read acknowledgement. */
export class HistoryHeads {
 constructor({epoch=randomUUID(),limit=4096}={}){this.epoch=epoch;this.limit=limit;this.heads=new Map();}
 observe(threadId,version){
  if(typeof threadId!=='string'||!Number.isSafeInteger(version)||version<0)return;
  const previous=this.heads.get(threadId);
  if(previous!==undefined&&version<=previous)return;
  this.heads.delete(threadId);this.heads.set(threadId,version);
  while(this.heads.size>this.limit)this.heads.delete(this.heads.keys().next().value);
 }
 snapshot(threadId){return {threadId,epoch:this.epoch,version:this.heads.get(threadId)??null};}
}
