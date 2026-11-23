const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

/** Delivery cache only. Hollywood remains authoritative for audience and attention. */
export class HollywoodPush {
 constructor({db,gateway,push,publish=()=>{},clock=()=>Date.now()}){
  Object.assign(this,{db,gateway,push,publish,clock});this.busy=false;
  db.exec(`CREATE TABLE IF NOT EXISTS hollywood_inbox_cursors(
   device TEXT REFERENCES devices(id) ON DELETE CASCADE,binding TEXT,cursor INTEGER NOT NULL,pending_cursor INTEGER NOT NULL DEFAULT 0,
   PRIMARY KEY(device,binding));
  CREATE TABLE IF NOT EXISTS hollywood_inbox_node_cursors(
   device TEXT REFERENCES devices(id) ON DELETE CASCADE,binding TEXT,node TEXT,cursor INTEGER NOT NULL,pending_cursor INTEGER NOT NULL DEFAULT 0,
   PRIMARY KEY(device,binding,node));
  CREATE TABLE IF NOT EXISTS hollywood_push_deliveries(
   device TEXT REFERENCES devices(id) ON DELETE CASCADE,binding TEXT,event TEXT,revision INTEGER NOT NULL,hint TEXT NOT NULL,
   action TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'queued',attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0,
   message_id TEXT,error TEXT,checked_at INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(device,binding,event));`);
 }
 checked(client,value){
  if(value.authorityId!==client.config.authorityId||value.principalId!==client.config.principalId||!uuid(value.eventId)||!uuid(value.roomId)||
    !Number.isSafeInteger(value.sequence)||value.sequence<1||!Number.isSafeInteger(value.revision)||value.revision<1||typeof value.notify!=='boolean'||
    !['message','result','question','approval'].includes(value.kind)||value.attentionId!=null&&!uuid(value.attentionId))throw Error('Hollywood inbox identity is unconfirmed.');
  return value;
 }
 page(client,value,after){
  if(value.authorityId!==client.config.authorityId||value.principalId!==client.config.principalId||!Array.isArray(value.events)||value.events.length>100||
    !Number.isSafeInteger(value.nextCursor)||value.nextCursor<after||!Number.isSafeInteger(value.head)||value.nextCursor>value.head)throw Error('Hollywood inbox cursor is unconfirmed.');
  let last=after;
  for(const raw of value.events){const h=this.checked(client,raw);if(h.sequence<=last||h.sequence>value.nextCursor)throw Error('Hollywood inbox ordering is unconfirmed.');last=h.sequence;}
  return value;
 }
 stage(device,client,h){
  this.checked(client,h);const binding=client.config.bindingId;
  const old=this.db.prepare('SELECT * FROM hollywood_push_deliveries WHERE device=? AND binding=? AND event=?').get(device,binding,h.eventId);
  if(old&&old.revision>h.revision)return;
  if(!h.notify&&!old)return;
  const action=h.notify?'notify':'resolve';
  if(old&&old.revision===h.revision&&old.action===action)return;
  this.db.prepare(`INSERT INTO hollywood_push_deliveries(device,binding,event,revision,hint,action,updated_at) VALUES(?,?,?,?,?,?,?)
   ON CONFLICT(device,binding,event) DO UPDATE SET revision=excluded.revision,hint=excluded.hint,action=excluded.action,
    state='queued',attempts=0,next_attempt=0,message_id=NULL,error=NULL,updated_at=excluded.updated_at`)
    .run(device,binding,h.eventId,h.revision,JSON.stringify(h),action,this.clock());
  try{this.publish(device,{type:'hollywoodInbox',binding:client.envelope(),hint:h});}catch{}
 }
 async recoverPending(device,client){
  const row=this.db.prepare('SELECT * FROM hollywood_inbox_cursors WHERE device=? AND binding=?').get(device,client.config.bindingId);
  const after=row.pending_cursor;
  const page=this.page(client,await client.call('inbox/pending',{query:{after,limit:100}}),after);
  this.db.exec('BEGIN IMMEDIATE');
  try{
   for(const h of page.events)this.stage(device,client,h);
   this.db.prepare('UPDATE hollywood_inbox_cursors SET pending_cursor=? WHERE device=? AND binding=?')
    .run(page.events.length===100?page.nextCursor:0,device,client.config.bindingId);this.db.exec('COMMIT');
  }catch(error){this.db.exec('ROLLBACK');throw error;}
 }
 async observeLegacy(device){
  const client=this.gateway.client(device);await client.verify();const binding=client.config.bindingId;
  let cursor=this.db.prepare('SELECT cursor FROM hollywood_inbox_cursors WHERE device=? AND binding=?').get(device,binding)?.cursor;
  if(cursor===undefined){
   const head=this.page(client,await client.call('inbox'),0);
   this.db.prepare('INSERT OR IGNORE INTO hollywood_inbox_cursors(device,binding,cursor) VALUES(?,?,?)').run(device,binding,head.nextCursor);
   cursor=head.nextCursor;
  }
  await this.recoverPending(device,client);
  const page=this.page(client,await client.call('inbox',{query:{after:cursor,limit:100}}),cursor);
  this.db.exec('BEGIN IMMEDIATE');
  try{
   for(const h of page.events)this.stage(device,client,h);
   this.db.prepare('UPDATE hollywood_inbox_cursors SET cursor=? WHERE device=? AND binding=?').run(page.nextCursor,device,binding);
   this.db.exec('COMMIT');
  }catch(error){this.db.exec('ROLLBACK');throw error;}
 }
 async observeNode(device,client,node,localNode){
  const binding=client.config.bindingId,query={nodeId:node};
  let row=this.db.prepare('SELECT * FROM hollywood_inbox_node_cursors WHERE device=? AND binding=? AND node=?').get(device,binding,node);
  const checkedPage=(value,after)=>{
   if(value.nodeId!==node&&!(node===localNode&&value.nodeId===undefined))throw Error('Hollywood inbox source is unconfirmed.');
   return this.page(client,value,after);
  };
  if(!row){
   const old=node===localNode?this.db.prepare('SELECT * FROM hollywood_inbox_cursors WHERE device=? AND binding=?').get(device,binding):null;
   const cursor=old?.cursor??checkedPage(await client.call('inbox',{query}),0).nextCursor;
   this.db.prepare('INSERT OR IGNORE INTO hollywood_inbox_node_cursors(device,binding,node,cursor,pending_cursor) VALUES(?,?,?,?,?)')
    .run(device,binding,node,cursor,old?.pending_cursor??0);
   row=this.db.prepare('SELECT * FROM hollywood_inbox_node_cursors WHERE device=? AND binding=? AND node=?').get(device,binding,node);
  }
  const pending=checkedPage(await client.call('inbox/pending',{query:{...query,after:row.pending_cursor,limit:100}}),row.pending_cursor);
  this.db.exec('BEGIN IMMEDIATE');
  try{
   for(const h of pending.events)this.stage(device,client,{...h,nodeId:node});
   this.db.prepare('UPDATE hollywood_inbox_node_cursors SET pending_cursor=? WHERE device=? AND binding=? AND node=?')
    .run(pending.events.length===100?pending.nextCursor:0,device,binding,node);this.db.exec('COMMIT');
  }catch(error){this.db.exec('ROLLBACK');throw error;}
  const page=checkedPage(await client.call('inbox',{query:{...query,after:row.cursor,limit:100}}),row.cursor);
  this.db.exec('BEGIN IMMEDIATE');
  try{
   for(const h of page.events)this.stage(device,client,{...h,nodeId:node});
   this.db.prepare('UPDATE hollywood_inbox_node_cursors SET cursor=? WHERE device=? AND binding=? AND node=?')
    .run(page.nextCursor,device,binding,node);this.db.exec('COMMIT');
  }catch(error){this.db.exec('ROLLBACK');throw error;}
 }
 async observe(device){
  const client=this.gateway.client(device);await client.verify();
  const catalog=await client.call('rooms');
  if(catalog.nodeId===undefined&&catalog.sources===undefined){await this.observeLegacy(device);return [];}
  if(catalog.authorityId!==client.config.authorityId||!uuid(catalog.nodeId)||!Array.isArray(catalog.sources))throw Error('Hollywood inbox discovery is unconfirmed.');
  const nodes=new Map();
  for(const source of catalog.sources){
   if(source.authorityId!==client.config.authorityId)continue;
   if(!uuid(source.nodeId)||!['connected','not_authorized','unavailable'].includes(source.status)||nodes.has(source.nodeId))throw Error('Hollywood inbox source list is unconfirmed.');
   nodes.set(source.nodeId,source);
  }
  if(nodes.get(catalog.nodeId)?.status!=='connected')throw Error('Local Hollywood inbox source is unconfirmed.');
  this.sourceFailures??=new Map();
  const unavailable=new Set([...nodes.values()].filter(s=>s.status!=='connected').map(s=>s.nodeId));
  this.sourceFailures.set(client.config.bindingId,unavailable);
  const errors=[];
  // Separate source cursors; one missing peer cannot block local observation.
  const ready=[...nodes.values()].filter(s=>s.status==='connected');
  for(let offset=0;offset<ready.length;offset+=4){
   const batch=ready.slice(offset,offset+4);
   const results=await Promise.allSettled(batch.map(s=>this.observeNode(device,client,s.nodeId,catalog.nodeId)));
   results.forEach((result,i)=>{if(result.status==='rejected'){unavailable.add(batch[i].nodeId);errors.push({node:batch[i].nodeId,code:result.reason.code||'HOLLYWOOD_INBOX_UNAVAILABLE'});}});
  }
  return errors;
 }
 sourceUnavailable(client,row){
  return this.sourceFailures?.get(client.config.bindingId)?.has(JSON.parse(row.hint).nodeId)||false;
 }
 holdSource(client,row){
  const node=JSON.parse(row.hint).nodeId;
  if(!uuid(node))return false;
  this.sourceFailures??=new Map();
  if(!this.sourceFailures.has(client.config.bindingId))this.sourceFailures.set(client.config.bindingId,new Set());
  this.sourceFailures.get(client.config.bindingId).add(node);return true;
 }
 payload(device,client,h,action){
  // No room text, question, approval command, account credential or native control enters FCM.
  return {hollywood_inbox:'1',device_id:device,binding_id:client.config.bindingId,authority_id:h.authorityId,
   principal_id:h.principalId,event_id:h.eventId,room_id:h.roomId,revision:String(h.revision),action};
 }
 async refreshRetained(device,client){
  const rows=this.db.prepare("SELECT * FROM hollywood_push_deliveries WHERE device=? AND binding=? AND action='notify' AND json_extract(hint,'$.attentionId') IS NOT NULL AND state NOT IN ('revoked','expired') ORDER BY checked_at,event LIMIT 100")
   .all(device,client.config.bindingId);
  for(const row of rows){
   if(this.sourceUnavailable(client,row))continue;
   try{const value=await client.call('inbox/events/'+row.event);this.stage(device,client,this.checked(client,value.hint));
    this.db.prepare('UPDATE hollywood_push_deliveries SET checked_at=? WHERE device=? AND binding=? AND event=?').run(this.clock(),device,row.binding,row.event);}
   catch(error){
    if(![403,404].includes(error.status)){if(this.holdSource(client,row))continue;throw error;}
    this.db.prepare("UPDATE hollywood_push_deliveries SET state='revoked',error='Audience no longer authorized',updated_at=? WHERE device=? AND binding=? AND event=?")
     .run(this.clock(),device,row.binding,row.event);
   }
  }
 }
 async flush(device,client){
  if(!this.push.enabled)return;
  const rows=this.db.prepare("SELECT * FROM hollywood_push_deliveries WHERE device=? AND binding=? AND state IN ('queued','retry') AND next_attempt<=? ORDER BY updated_at LIMIT 100")
   .all(device,client.config.bindingId,this.clock());
  for(const row of rows){
   if(this.sourceUnavailable(client,row))continue;
   // Pairing and current installation binding are checked again for each delivery attempt.
   const token=this.db.prepare('SELECT p.token FROM push_tokens p JOIN devices d ON d.id=p.device_id WHERE p.device_id=? AND p.project_id=?')
    .get(device,this.push.config.projectId);if(!token)continue;
   if(this.gateway.client(device).config.bindingId!==row.binding)continue;
   let h;
   try{h=this.checked(client,(await client.call('inbox/events/'+row.event)).hint);}
   catch(error){
    if(![403,404].includes(error.status)){if(this.holdSource(client,row))continue;throw error;}
    this.db.prepare("UPDATE hollywood_push_deliveries SET state='revoked',error='Audience no longer authorized',updated_at=? WHERE device=? AND binding=? AND event=?")
     .run(this.clock(),device,row.binding,row.event);continue;
   }
   this.stage(device,client,h);
   const current=this.db.prepare('SELECT * FROM hollywood_push_deliveries WHERE device=? AND binding=? AND event=?').get(device,row.binding,row.event);
   if(current.revision!==row.revision||current.action!==row.action)continue;
   if(!this.db.prepare('SELECT 1 FROM devices d JOIN push_tokens p ON p.device_id=d.id WHERE d.id=? AND p.token=?').get(device,token.token))continue;
   const attempts=row.attempts+1;
   try{
    const id=await this.push.send({token:token.token,data:this.payload(device,client,h,row.action),android:{priority:'high',ttl:86400000,restrictedPackageName:'co.fallsoft.pocket'}});
    this.db.prepare("UPDATE hollywood_push_deliveries SET state='accepted_by_fcm',message_id=?,attempts=?,error=NULL,updated_at=? WHERE device=? AND binding=? AND event=? AND revision=? AND action=?")
     .run(id,attempts,this.clock(),device,row.binding,row.event,row.revision,row.action);
   }catch(error){
    const code=error.code||'push/outcome-unconfirmed';
    const invalid=['messaging/registration-token-not-registered','messaging/invalid-registration-token','messaging/mismatched-credential'].includes(code);
    const permanent=invalid||['messaging/invalid-argument','messaging/authentication-error','messaging/invalid-package-name'].includes(code);
    this.db.prepare('UPDATE hollywood_push_deliveries SET state=?,attempts=?,next_attempt=?,error=?,updated_at=? WHERE device=? AND binding=? AND event=? AND revision=? AND action=?')
     .run(permanent||attempts>=10?'failed':'retry',attempts,this.clock()+Math.min(3600000,30000*2**(attempts-1)),code,this.clock(),device,row.binding,row.event,row.revision,row.action);
    if(invalid)this.db.prepare('DELETE FROM push_tokens WHERE device_id=? AND token=?').run(device,token.token);
   }
  }
 }
 async tick(){
  if(this.busy)return;this.busy=true;const errors=[];
  try{
   for(const {id} of this.db.prepare('SELECT id FROM devices ORDER BY id').all()){
    try{for(const error of await this.observe(id))errors.push({device:id,...error});const client=this.gateway.client(id);await this.refreshRetained(id,client);await this.flush(id,client);}
    catch(error){errors.push({device:id,code:error.code||'HOLLYWOOD_INBOX_UNAVAILABLE'});}
   }
   return {errors};
  }finally{this.busy=false;}
 }
}
