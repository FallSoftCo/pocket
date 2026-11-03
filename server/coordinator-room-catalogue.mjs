/** On-demand metadata from paired human sources; never a work/model poll.
 * Preserve source order so latency cannot silently choose a different writer. */
export async function coordinatorRoomCatalogue({targets,call,deadlineMs=4000}){
 const signal=AbortSignal.timeout(deadlineMs),results=new Array(targets.length);let next=0;
 const run=async()=>{while(next<targets.length){
  const index=next++,target=targets[index],source={environmentId:target.environmentId,name:target.name};
  try{
   signal.throwIfAborted();
   const identity=await call(target.environmentId,'/api/hollywood/v3/identity',{signal});
   if(!/^[0-9a-f]{64}$/.test(identity?.bindingId)||typeof identity.authorityId!=='string')throw Error('Room binding unavailable');
   const result=await call(target.environmentId,'/api/hollywood/v3/rooms',{signal,hollywoodBinding:identity.bindingId});
   if(result.authorityId!==identity.authorityId||!Array.isArray(result.rooms))throw Error('Room source identity changed');
   results[index]={source:{...source,available:true,authorityId:identity.authorityId,roomCount:result.rooms.length},rooms:result.rooms};
  }catch{
   results[index]={source:{...source,available:false,problem:'Room metadata unavailable from this paired source; absence is not an empty room inventory.'},rooms:[]};
  }
 }};
 await Promise.all(Array.from({length:Math.min(3,targets.length)},run));
 const rooms=new Map();
 for(const {source,rooms:items} of results){
  for(const room of items){
   if(typeof room.id!=='string'||typeof room.name!=='string')continue;
   const key=source.authorityId+':'+room.id,route={environmentId:source.environmentId,environmentName:source.name};
   if(!rooms.has(key))rooms.set(key,{id:room.id,name:room.name,purpose:String(room.purpose||'').slice(0,600),owner:room.owner,authorityId:source.authorityId,...route,routes:[]});
   rooms.get(key).routes.push(route);
  }
 }
 const sources=results.map(result=>result.source);
 return {rooms:[...rooms.values()],sources,available:sources.some(s=>s.available),scope:'paired-human-room-bindings',complete:sources.every(s=>s.available),workSubmitted:false};
}
