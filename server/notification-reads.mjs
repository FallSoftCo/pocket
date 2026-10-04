const attentionKinds=new Set(['question','approval','error']);
export class NotificationReads {
  constructor(db){this.db=db;db.exec(`CREATE TABLE IF NOT EXISTS notification_reads(device_id TEXT NOT NULL,thread_id TEXT NOT NULL,through_id INTEGER NOT NULL,PRIMARY KEY(device_id,thread_id));`);}
  watermark(deviceId,threadId){return this.db.prepare('SELECT through_id FROM notification_reads WHERE device_id=? AND thread_id=?').get(deviceId,threadId)?.through_id||0;}
  needsAttention(n){if(!attentionKinds.has(n.kind))return false;return !!this.db.prepare('SELECT 1 FROM notification_attention WHERE notification_id=? AND resolved_at IS NULL').get(n.id);}
  decorate(rows,deviceId){return rows.map(n=>{const needs=this.needsAttention(n);return {...n,_needsAttention:needs,_read:!needs&&n.id<=this.watermark(deviceId,n.thread_id||'')};});}
  ack(deviceId,threadId,throughId){
    if(!deviceId||typeof threadId!=='string'||!threadId||!Number.isSafeInteger(throughId)||throughId<=0)throw Object.assign(new Error('Invalid read cursor.'),{status:400});
    // The cursor must refer to an actual fetched notification from this conversation.
    if(!this.db.prepare('SELECT 1 FROM notifications WHERE thread_id=? AND id=?').get(threadId,throughId))throw Object.assign(new Error('Notification cursor does not match this conversation.'),{status:400});
    this.db.prepare('INSERT INTO notification_reads VALUES(?,?,?) ON CONFLICT(device_id,thread_id) DO UPDATE SET through_id=MAX(through_id,excluded.through_id)').run(deviceId,threadId,throughId);
    const watermark=this.watermark(deviceId,threadId);
    const ids=this.db.prepare(`SELECT n.id FROM notifications n WHERE n.thread_id=? AND n.id<=? AND NOT EXISTS(SELECT 1 FROM notification_attention a WHERE a.notification_id=n.id AND a.resolved_at IS NULL)`).all(threadId,watermark).map(n=>n.id);
    return {ok:true,throughId:watermark,ids};
  }
}
