import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { recoverReply } from '../server/reply-recovery.mjs';
import { readThreadProjection } from '../server/thread-projection.mjs';
import { ThreadStatusProjection } from '../server/thread-status.mjs';
import { workAttention } from '../server/work-attention.mjs';

function historyFixture() {
  const root = mkdtempSync(join(tmpdir(), 'nextcomp-empty-thread-'));
  const db = new DatabaseSync(join(root, 'thread_history_1.sqlite'));
  db.exec(`
    CREATE TABLE thread_turns(thread_id TEXT NOT NULL, status TEXT NOT NULL, rollout_ordinal INTEGER NOT NULL);
    CREATE TABLE thread_items(thread_id TEXT NOT NULL, item_id TEXT NOT NULL);
  `);
  return { root, db, close: () => { db.close(); rmSync(root, { recursive: true, force: true }); } };
}

function metadata(id, { ephemeral = false } = {}) {
  return { id, ephemeral, historyMode: 'paginated', status: { type: 'notLoaded' }, turns: [] };
}

test('empty durable sessions project ready while non-empty history keeps turn state', () => {
  const fixture = historyFixture();
  const empty = metadata('empty');
  const working = metadata('working');
  const unknown = metadata('unknown');
  const known = new Map([empty, working, unknown].map((thread) => [thread.id, thread]));
  fixture.db.prepare('INSERT INTO thread_turns VALUES (?,?,?)').run('working', 'inProgress', 1);
  fixture.db.prepare('INSERT INTO thread_items VALUES (?,?)').run('working', 'item');
  try {
    const projection = new ThreadStatusProjection({
      codexHome: fixture.root,
      known: (id) => known.get(id) || null,
    });
    const projected = projection.project([empty, working, metadata('unregistered')]);
    assert.deepEqual(projected[0].status, { type: 'idle' });
    assert.equal(projected[0].statusSource, 'persisted-empty');
    assert.deepEqual(projected[1].status, { type: 'active', activeFlags: [] });
    assert.equal(projected[1].statusSource, 'persisted-turn');
    assert.deepEqual(projected[2].status, { type: 'notLoaded' });
    assert.equal(projected[2].statusSource, undefined);
  } finally {
    fixture.close();
  }
});

test('empty ready status verifies attention without inventing work items', () => {
  const fixture = historyFixture();
  const empty = metadata('empty');
  const known = new Map([[empty.id, empty]]);
  const attentionDb = new DatabaseSync(':memory:');
  attentionDb.exec(`
    CREATE TABLE outgoing(id TEXT, thread_id TEXT, state TEXT, created_at INTEGER);
    CREATE TABLE pocket_catalog_context(thread_id TEXT, needs_input INTEGER, at INTEGER);
  `);
  try {
    const projection = new ThreadStatusProjection({
      codexHome: fixture.root,
      known: (id) => known.get(id) || null,
    });
    const [thread] = projection.project([empty]);
    const attention = workAttention({
      db: attentionDb,
      pending: new Map(),
      codex: { ready: true },
      threads: [thread],
    });
    assert.deepEqual(attention.get('empty'), { version: 1, items: [], verified: true });
  } finally {
    attentionDb.close();
    fixture.close();
  }
});

test('head returns a truthful empty projection for a registered empty session', async () => {
  const threadId = 'empty';
  const thread = metadata(threadId);
  const codex = {
    connect: async () => {},
    call: async () => ({ thread }),
  };
  const history = {
    read: async () => {
      throw Object.assign(new Error(`thread not loaded: ${threadId}`), {
        rpc: { code: -32600, message: `thread not loaded: ${threadId}` },
      });
    },
  };
  const timeline = { version: 7, seed: () => {} };
  const historyHeads = { snapshot: () => ({ threadId, version: 1 }) };
  const result = await readThreadProjection(threadId, {
    codex,
    history,
    timeline,
    historyHeads,
    known: () => thread,
    emptyThread: () => true,
  });
  assert.deepEqual(result.status, { type: 'idle' });
  assert.equal(result.statusSource, 'persisted-empty');
  assert.deepEqual(result.turns, []);
  assert.deepEqual(result._pocketPage, { hasEarlier: false, before: null });
});

test('head still reports unavailable history for a non-empty session', async () => {
  const threadId = 'nonempty';
  const thread = metadata(threadId);
  const codex = {
    connect: async () => {},
    call: async () => ({ thread }),
  };
  const history = {
    read: async () => {
      throw Object.assign(new Error(`thread not loaded: ${threadId}`), {
        rpc: { code: -32600, message: `thread not loaded: ${threadId}` },
      });
    },
  };
  await assert.rejects(
    readThreadProjection(threadId, {
      codex,
      history,
      timeline: { version: 1, seed: () => {} },
      historyHeads: { snapshot: () => null },
      known: () => thread,
      emptyThread: () => false,
    }),
    (error) => error.status === 409 && error.code === 'THREAD_HISTORY_UNAVAILABLE',
  );
});

test('head projects a registered empty session when metadata is no longer loaded', async () => {
  const threadId = 'empty';
  const thread = metadata(threadId);
  const codex = {
    connect: async () => {},
    call: async () => {
      throw Object.assign(new Error(`thread not loaded: ${threadId}`), {
        rpc: { code: -32600, message: `thread not loaded: ${threadId}` },
      });
    },
  };
  const result = await readThreadProjection(threadId, {
    codex,
    history: { read: async () => { throw new Error('must not read history'); } },
    timeline: { version: 3, seed: () => {} },
    historyHeads: { snapshot: () => null },
    known: () => thread,
    emptyThread: () => true,
  });
  assert.deepEqual(result.status, { type: 'idle' });
  assert.equal(result.statusSource, 'persisted-empty');
  assert.equal(result._pocketSnapshotRevision, 3);
});

test('a held steer message becomes a dispatchable queued steer', () => {
  const db = new DatabaseSync(':memory:');
  db.exec(`
    CREATE TABLE outgoing(
      id TEXT PRIMARY KEY, thread_id TEXT, text TEXT, state TEXT, result TEXT,
      created_at INTEGER, updated_at INTEGER, mode TEXT
    );
  `);
  db.prepare('INSERT INTO outgoing VALUES (?,?,?,?,?,?,?,?)').run(
    'reply', 'thread', 'Follow up', 'held', 'Waiting for Codex before sending.', 1, 1, 'steer');
  const result = recoverReply(db, 'thread', 'reply', { action: 'send' }, 2);
  assert.deepEqual(result, { status: 200, id: 'reply', state: 'queued', mode: 'steer', dispatch: true });
  assert.deepEqual(
    { ...db.prepare('SELECT state,mode,text,updated_at FROM outgoing WHERE id=?').get('reply') },
    { state: 'queued', mode: 'steer', text: 'Follow up', updated_at: 2 },
  );
  db.close();
});
