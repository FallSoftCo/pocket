/** Renames alter labels, never message content, read cursors, attention or spoken context. */
export class NotificationTitles {
  constructor(db){this.db=db;db.exec('CREATE TABLE IF NOT EXISTS notification_thread_titles(thread_id TEXT PRIMARY KEY,title TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0)');if(!db.prepare('PRAGMA table_info(notification_thread_titles)').all().some(c=>c.name==='revision'))db.exec('ALTER TABLE notification_thread_titles ADD COLUMN revision INTEGER NOT NULL DEFAULT 0');db.exec('CREATE INDEX IF NOT EXISTS notification_label_lookup ON notifications(thread_id,title)');}
  revision(threadId){return this.db.prepare('SELECT revision FROM notification_thread_titles WHERE thread_id=?').get(threadId)?.revision||0;}
  title(threadId,fallback){
    if(!threadId)return fallback;
    return this.db.prepare('SELECT title FROM notification_thread_titles WHERE thread_id=?').get(threadId)?.title
      ||this.db.prepare('SELECT name FROM watches WHERE thread_id=?').get(threadId)?.name||fallback;
  }
  rename(threadId,title,{transaction=true}={}){
    const previous=this.db.prepare('SELECT title,revision FROM notification_thread_titles WHERE thread_id=?').get(threadId);
    const revision=(previous?.revision||0)+(previous?.title!==title?1:0);
    if(transaction)this.db.exec('BEGIN IMMEDIATE');
    try{
      this.db.prepare('INSERT INTO notification_thread_titles(thread_id,title,revision) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET title=excluded.title,revision=excluded.revision').run(threadId,title,revision);
      this.db.prepare('UPDATE watches SET name=? WHERE thread_id=?').run(title,threadId);
      const changed=this.db.prepare('UPDATE notifications SET title=? WHERE thread_id=? AND title IS NOT ?').run(title,threadId,title).changes;
      const notificationIds=this.db.prepare('SELECT id FROM notifications WHERE thread_id=? ORDER BY id').all(threadId).map(row=>row.id);
      if(transaction)this.db.exec('COMMIT');return {threadId,name:title,notificationIds,revision,changed:previous?.title!==title||changed>0};
    }catch(error){if(transaction)this.db.exec('ROLLBACK');throw error;}
  }
  reconcile(threads){
    const changes=[];this.db.exec('BEGIN IMMEDIATE');try{
    for(const thread of threads){
      if(typeof thread.name!=='string'||!thread.name.trim())continue;
      const title=thread.name;
      const saved=this.db.prepare('SELECT title FROM notification_thread_titles WHERE thread_id=?').get(thread.id);
      const watch=this.db.prepare('SELECT name FROM watches WHERE thread_id=?').get(thread.id);
      const stale=this.db.prepare('SELECT 1 FROM notifications WHERE thread_id=? AND title IS NOT ? LIMIT 1').get(thread.id,title);
      if(saved?.title===title&&(!watch||watch.name===title)&&!stale)continue;
      const change=this.rename(thread.id,title,{transaction:false});
      // Initial canonical metadata without existing stale labels does not create a push/event.
      if(stale||(watch&&watch.name!==title)||(saved&&saved.title!==title))changes.push(change);
    }
    this.db.exec('COMMIT');return changes;
    }catch(error){this.db.exec('ROLLBACK');throw error;}
  }

}
