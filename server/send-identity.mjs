/** Durable identity evidence, not text deduplication. One backend database owns
 * one environment. Only provider-issued item/client pairs are admitted here. */
export class SendIdentity {
  constructor(db) {
    db.exec(`CREATE TABLE IF NOT EXISTS user_send_identity (
      thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, item_id TEXT NOT NULL,
      client_id TEXT NOT NULL, PRIMARY KEY(thread_id,turn_id,item_id,client_id)
    ) WITHOUT ROWID`);
    this.insert=db.prepare('INSERT OR IGNORE INTO user_send_identity VALUES(?,?,?,?)');
    this.claims=db.prepare('SELECT client_id FROM user_send_identity WHERE thread_id=? AND turn_id=? AND item_id=? LIMIT 2');
  }
  item(threadId,turnId,item) {
    if(item?.type!=='userMessage'||!valid(threadId)||!valid(turnId)||!valid(item.id))return item;
    const supplied=item._pocketRow?.clientId||item.clientId||item.clientUserMessageId;
    if(valid(supplied))this.insert.run(threadId,turnId,item.id,supplied);
    const claims=this.claims.all(threadId,turnId,item.id);
    // Conflicting provider claims are not permission to collapse distinct sends.
    if(claims.length!==1)return item;
    const clientId=claims[0].client_id;
    return item._pocketRow
      ? {...item,_pocketRow:{...item._pocketRow,clientId}}
      : {...item,clientId};
  }
  snapshot(thread) {
    if(!thread?.id)return thread;
    return {...thread,turns:(thread.turns||[]).map(turn=>({...turn,
      items:(turn.items||[]).map(item=>this.item(thread.id,turn.id,item))}))};
  }
  event(event) {
    const p=event.params,threadId=p?.threadId||p?.thread?.id;
    if(!p||!threadId)return event;
    if(p.item)return {...event,params:{...p,item:this.item(threadId,p.turnId,p.item)}};
    if(p.turn?.items)return {...event,params:{...p,turn:{...p.turn,
      items:p.turn.items.map(item=>this.item(threadId,p.turn.id,item))}}};
    return event;
  }
}
const valid=value=>typeof value==='string'&&value.length>0&&value.length<=512;
