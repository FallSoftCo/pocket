import {createHash} from 'node:crypto';

// Observer only: durable pending attention is the source, never model polling turns.
export class TeamAttention {
  constructor({client,db,publish,resolve}){
    Object.assign(this,{client,db,publish,resolve});this.running=false;
    db.exec(`CREATE TABLE IF NOT EXISTS losangelex_notifications (
      environment TEXT NOT NULL,event INTEGER NOT NULL,team TEXT NOT NULL,task TEXT NOT NULL,
      agent TEXT NOT NULL,thread_id TEXT UNIQUE NOT NULL,notification_id INTEGER,
      PRIMARY KEY(environment,event))`);
  }
  async target(thread){
    const row=this.db.prepare('SELECT * FROM losangelex_notifications WHERE thread_id=? AND environment=?').get(thread,this.client.config?.environmentId||'');
    if(!row)throw Object.assign(Error('This Losangelex notification belongs to a different backend connection.'),{status:409});
    const detail=await this.client.call(`teams/${row.team}/messages/${row.event}`);
    return {...row,event:detail.event,attentionState:detail.attentionState,provider:'losangelex'};
  }
  async poll(){
    if(this.running||!this.client.config||this.client.config.invalid)return;this.running=true;
    try{
      const environment=this.client.config.environmentId,open=new Set(),seen=new Set();let cursor=null;
      do{
        const page=await this.client.call('attention',{query:{limit:100,...(cursor?{cursor}:{})}});
        for(const event of page.data||[]){
          const id=Number(event.id),team=event.team_id,task=event.task_id||event.task;
          if(!Number.isSafeInteger(id)||id<=0||!team||!task)continue;
          open.add(id);
          const old=this.db.prepare('SELECT notification_id FROM losangelex_notifications WHERE environment=? AND event=?').get(environment,id);
          if(old?.notification_id)continue;
          const thread='lx-'+createHash('sha256').update(environment+'\0'+team+'\0'+id).digest('hex').slice(0,24);
          this.publish(thread,n=>this.db.prepare('INSERT INTO losangelex_notifications VALUES(?,?,?,?,?,?,?)').run(environment,id,team,task,event.author,thread,n.id));
        }
        cursor=page.next_cursor??page.nextCursor??null;
        if(cursor&&seen.has(String(cursor)))throw Error('Repeated attention cursor');
        if(cursor)seen.add(String(cursor));
      }while(cursor);
      // Resolve only after a complete successful scan; an outage cannot dismiss work.
      for(const row of this.db.prepare('SELECT event,notification_id FROM losangelex_notifications WHERE environment=?').all(environment)){
        if(!open.has(row.event))this.resolve('notification_id=?',row.notification_id);
      }
    }finally{this.running=false;}
  }
  mount(app){
    app.get('/api/losangelex/notification-target/:id',async(req,res,next)=>{try{res.json(await this.target(req.params.id));}catch(e){next(e);}});
    app.post('/api/notifications/:id/skip',async(req,res,next)=>{
      const row=this.db.prepare('SELECT * FROM losangelex_notifications WHERE notification_id=?').get(req.params.id);if(!row)return next();
      try{const target=await this.target(row.thread_id);if(target.event.kind==='approval')throw Object.assign(Error('Open this Losangelex approval to review or decline it.'),{status:409});await this.client.call(`teams/${row.team}/attention/${row.event}/dismiss`,{body:{commandId:`nextcomp-dismiss-${row.notification_id}`}});this.resolve('notification_id=?',row.notification_id);res.json({ok:true});}catch(e){next(e);}
    });
    app.get('/api/notifications/:id/attention',async(req,res,next)=>{
      const row=this.db.prepare('SELECT * FROM losangelex_notifications WHERE notification_id=?').get(req.params.id);if(!row)return next();
      try{const target=await this.target(row.thread_id),needsAttention=target.attentionState==='open';if(!needsAttention)this.resolve('notification_id=?',row.notification_id);res.json({needsAttention});}catch(e){next(e);}
    });
  }
}
