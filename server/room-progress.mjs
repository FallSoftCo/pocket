import {createHash} from 'node:crypto';

// Event observation only. This never starts, resumes or prompts an agent.
export class RoomProgress {
 constructor({db,client,scopes,publish}){
  Object.assign(this,{db,client,scopes,publish});this.running=false;
  if(!Array.isArray(scopes)||scopes.length>64||scopes.some(s=>!s||Object.keys(s).some(k=>!['team','task'].includes(k))||![s.team,s.task].every(v=>typeof v==='string'&&/^[a-z0-9_-]{1,64}$/.test(v))))throw Error('Invalid owner-defined progress room scopes.');
  db.exec(`CREATE TABLE IF NOT EXISTS room_progress_cursor(environment TEXT,team TEXT,cursor INTEGER,PRIMARY KEY(environment,team));
  CREATE TABLE IF NOT EXISTS room_progress_notifications(environment TEXT,event INTEGER,team TEXT,task TEXT,agent TEXT,thread_id TEXT UNIQUE,notification_id INTEGER,PRIMARY KEY(environment,event));`);
 }
 async poll(){
  if(this.running||!this.client.config||this.client.config.invalid)return;this.running=true;
  try{
   const environment=this.client.config.environmentId;
   for(const team of new Set(this.scopes.map(s=>s.team))){
    let row=this.db.prepare('SELECT cursor FROM room_progress_cursor WHERE environment=? AND team=?').get(environment,team);
    if(!row){const overview=await this.client.call(`teams/${team}/overview`);if(!Number.isSafeInteger(overview.cursor)||overview.cursor<0)throw Error('Invalid room event cursor');this.db.prepare('INSERT INTO room_progress_cursor VALUES(?,?,?)').run(environment,team,overview.cursor);continue;}
    const page=await this.client.call(`teams/${team}/messages`,{query:{after:row.cursor,limit:100}});
    let cursor=row.cursor;
    for(const event of page.data||[]){
     if(!Number.isSafeInteger(event.id)||event.id<=cursor)throw Error('Room events must advance their cursor');
     if(event.kind==='message'&&event.author!=='you'&&event.visibility==='room'&&this.scopes.some(s=>s.team===team&&s.task===event.task)){
      const old=this.db.prepare('SELECT notification_id FROM room_progress_notifications WHERE environment=? AND event=?').get(environment,event.id);
      if(!old){
       const catalog=await this.client.call(`teams/${team}/rooms`,{query:{task:event.task,limit:1}});
       const room=catalog.rooms?.find(r=>r.task===event.task);if(!room)throw Error('Progress room identity unavailable');
       const thread='lx-'+createHash('sha256').update(environment+'\0progress\0'+team+'\0'+event.id).digest('hex').slice(0,24);
       this.publish(thread,room.title,event,n=>this.db.prepare('INSERT INTO room_progress_notifications VALUES(?,?,?,?,?,?,?)').run(environment,event.id,team,event.task,event.author,thread,n.id));
      }
     }
     cursor=event.id;
    }
    this.db.prepare('UPDATE room_progress_cursor SET cursor=? WHERE environment=? AND team=?').run(cursor,environment,team);
   }
  }finally{this.running=false;}
 }
 mount(app){
  app.get('/api/losangelex/notification-target/:id',async(req,res,next)=>{
   const row=this.db.prepare('SELECT * FROM room_progress_notifications WHERE thread_id=?').get(req.params.id);if(!row)return next();
   try{
    if(row.environment!==this.client.config?.environmentId)throw Object.assign(Error('This update belongs to its original room connection.'),{status:409});
    const value=await this.client.call(`teams/${row.team}/messages/${row.event}`);
    if(value.event?.task!==row.task)throw Object.assign(Error('Room update identity changed.'),{status:409});
    res.json({...row,event:value.event,provider:'losangelex',attentionState:null});
   }catch(e){next(e);}
  });
 }
}
