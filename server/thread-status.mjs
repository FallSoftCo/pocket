import { DatabaseSync } from 'node:sqlite';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const RESOLVED = new Set(['idle', 'active', 'systemError']);

// A second app-server can read persisted threads without owning their runtime.
// In that case Codex reports `notLoaded`, which is a loading state rather than a
// task state. Project the latest durable turn status so clients see whether the
// conversation is working or ready for input.
export class ThreadStatusProjection {
  constructor({ codexHome = process.env.CODEX_HOME, known = () => null } = {}) {
    this.codexHome = codexHome;
    this.known = known;
    this.db = null;
    this.dbPath = null;
  }

  historyDb() {
    if (this.db) return this.db;
    if (!this.codexHome || !existsSync(this.codexHome)) return null;
    const candidates = readdirSync(this.codexHome)
      .filter((name) => /^thread_history_\d+\.sqlite$/.test(name))
      .sort((a, b) => Number(b.match(/\d+/)?.[0]) - Number(a.match(/\d+/)?.[0]));
    if (!candidates.length) return null;
    this.dbPath = join(this.codexHome, candidates[0]);
    this.db = new DatabaseSync(this.dbPath, { readOnly: true });
    this.db.exec('PRAGMA busy_timeout=1000');
    return this.db;
  }

  latestTurnStatuses(ids) {
    if (!ids.length) return new Map();
    try {
      const db = this.historyDb();
      if (!db) return new Map();
      const rows = db.prepare(`SELECT thread_id,status FROM (
        SELECT thread_id,status,ROW_NUMBER() OVER (
          PARTITION BY thread_id ORDER BY rollout_ordinal DESC
        ) AS rank
        FROM thread_turns
        WHERE thread_id IN (SELECT value FROM json_each(?))
      ) WHERE rank=1`).all(JSON.stringify(ids));
      return new Map(rows.map((row) => [row.thread_id, row.status]));
    } catch (error) {
      console.error('Thread status projection unavailable:', error.code || error.name);
      return new Map();
    }
  }

  emptyDurableThreadIds(ids) {
    if (!ids.length) return new Set();
    try {
      const db = this.historyDb();
      if (!db) return new Set();
      const rows = db.prepare(`SELECT requested.value AS thread_id FROM json_each(?) AS requested
        WHERE NOT EXISTS (
          SELECT 1 FROM thread_turns WHERE thread_turns.thread_id=requested.value
        ) AND NOT EXISTS (
          SELECT 1 FROM thread_items WHERE thread_items.thread_id=requested.value
        )`).all(JSON.stringify(ids));
      return new Set(rows.map((row) => row.thread_id));
    } catch (error) {
      console.error('Empty thread projection unavailable:', error.code || error.name);
      return new Set();
    }
  }

  // A registered, non-ephemeral thread with no durable turns or items is an
  // empty session, not unknown task state. Require both catalog identity and
  // durable-history evidence so an unavailable non-empty history stays visible.
  isPersistedEmpty(thread) {
    const metadata = this.known(thread.id);
    if (metadata?.id !== thread.id || metadata.ephemeral !== false) return false;
    return this.emptyDurableThreadIds([thread.id]).has(thread.id);
  }

  project(threads) {
    const unresolved = threads.filter((thread) => !RESOLVED.has(thread.status?.type));
    if (!unresolved.length) return threads;
    const latest = this.latestTurnStatuses(unresolved.map((thread) => thread.id));
    const registered = unresolved.filter((thread) => {
      const metadata = this.known(thread.id);
      return metadata?.id === thread.id && metadata.ephemeral === false;
    });
    const empty = this.emptyDurableThreadIds(registered.map((thread) => thread.id));
    if (!latest.size && !empty.size) return threads;
    return threads.map((thread) => {
      if (RESOLVED.has(thread.status?.type)) return thread;
      if (latest.has(thread.id)) {
        const active = latest.get(thread.id) === 'inProgress';
        return {
          ...thread,
          status: active ? { type: 'active', activeFlags: [] } : { type: 'idle' },
          statusSource: 'persisted-turn',
        };
      }
      if (empty.has(thread.id)) {
        return {
          ...thread,
          status: { type: 'idle' },
          statusSource: 'persisted-empty',
        };
      }
      return thread;
    });
  }
}
