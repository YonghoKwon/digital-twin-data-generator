// No dependencies or frontend build. Run with Node 22:
// node --test src/test/ui/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { request, ApiError, errorText, createScope } from '../../main/resources/static/ui/api.js';
import { MessageBuffer } from '../../main/resources/static/ui/components/message-buffer.js';
import { sanitize, saveHistory, loadHistory } from '../../main/resources/static/ui/components/history.js';

test('all seven view entry points and their module imports resolve without external libraries', async () => {
  for (const view of ['dashboard', 'publish', 'streams', 'subscriptions', 'mock', 'scenarios', 'legacy']) {
    const module = await import(`../../main/resources/static/ui/views/${view}.js`);
    assert.equal(typeof module.default, 'function', view);
  }
});

test('fetch wrapper preserves object errors, legacy text, 204 and partial publish results', async () => {
  const original = globalThis.fetch;
  try {
    let options;
    globalThis.fetch = async (_, value) => { options = value; return new Response('{"sent":2,"failed":1,"stoppedReason":"BROKER_ERROR"}', { status: 207 }); };
    assert.equal((await request('/dt/publish', { method: 'POST', body: { count: 3 } })).sent, 2);
    assert.equal(options.method, 'POST'); assert.deepEqual(JSON.parse(options.body), { count: 3 });
    globalThis.fetch = async () => new Response(null, { status: 204 });
    assert.equal(await request('/dt/subscriptions/x', { method: 'DELETE' }), null);
    globalThis.fetch = async () => new Response('success : Started 1 tasks');
    assert.equal(await request('/legacy'), 'success : Started 1 tasks');
    globalThis.fetch = async () => new Response('{"error":"INVALID_REQUEST","message":"잘못된 토큰"}', { status: 400 });
    await assert.rejects(request('/dt/publish'), error => error instanceof ApiError && error.status === 400 && errorText(error).includes('INVALID_REQUEST') && errorText(error).includes('잘못된 토큰'));
    globalThis.fetch = async () => new Response('', { status: 404 });
    await assert.rejects(request('/dt/scenarios'), error => error.status === 404);
  } finally { globalThis.fetch = original; }
});

test('SSE recovery merges overlapping REST pages, caps at 500 and preserves cursor on clear', () => {
  const buffer = new MessageBuffer();
  buffer.add([{ seq: 503, messageId: 'B' }]);
  buffer.add(Array.from({ length: 502 }, (_, index) => ({ seq: index + 1, messageId: index % 2 ? 'B' : 'A' })));
  buffer.add([{ seq: 502, messageId: 'B', body: 'overlap' }]);
  assert.equal(buffer.values().length, 500); assert.equal(buffer.values()[0].seq, 4);
  assert.equal(buffer.cursor, 503); assert.equal(buffer.values().at(-1).seq, 503);
  assert.ok(buffer.values('A').every(row => row.messageId === 'A'));
  assert.equal(buffer.values().find(row => row.seq === 502).body, 'overlap');
  buffer.add([{ seq: 'invalid' }, { seq: -1 }]); assert.equal(buffer.values().length, 500);
  buffer.clear(); assert.equal(buffer.cursor, 503); assert.equal(buffer.values().length, 0);
});

test('recent requests remove nested credentials and keep at most ten entries', () => {
  const store = new Map();
  globalThis.localStorage = { getItem: key => store.get(key) ?? null, setItem: (key, value) => store.set(key, value) };
  const request = { pw: 'private', activeMQIp: 'tcp://user:private@localhost:61626', topic: 'sensor', headers: { Authorization: 'private', ordinary: 'OK' }, payload: { password: 'private', children: [{ api_key: 'private', value: 3 }] } };
  assert.ok(!JSON.stringify(sanitize(request.payload)).includes('private'));
  assert.equal(sanitize('{"password":"private","value":3}'), '{"value":3}');
  for (let index = 0; index < 12; index++) saveHistory({ ...request, count: index });
  assert.equal(loadHistory().length, 10); assert.equal(loadHistory()[0].request.count, 11);
  assert.ok(![...store.values()][0].includes('private'));
  globalThis.localStorage = { getItem() { throw new Error('denied'); }, setItem() { throw new Error('denied'); } };
  assert.deepEqual(loadHistory(), []); assert.doesNotThrow(() => saveHistory({ topic: 'test' }));
});

test('route disposal aborts requests, clears polling, and hidden tabs do not poll', async () => {
  const originalFetch = globalThis.fetch, originalDocument = globalThis.document;
  const document = new EventTarget(); document.hidden = true; globalThis.document = document;
  let capturedSignal, calls = 0, disposed = false;
  globalThis.fetch = async (_, options) => { capturedSignal = options.signal; return new Response('[]'); };
  const scope = createScope();
  try {
    await scope.request('/dt/streams'); scope.add(() => { disposed = true; });
    scope.poll(async () => { calls++; }, 10);
    await new Promise(resolve => setTimeout(resolve, 25)); assert.equal(calls, 0);
    document.hidden = false; document.dispatchEvent(new Event('visibilitychange'));
    await new Promise(resolve => setTimeout(resolve, 25)); assert.ok(calls > 0);
    scope.dispose(); const stoppedAt = calls;
    assert.ok(capturedSignal.aborted); assert.ok(disposed); assert.equal(scope.alive, false);
    await new Promise(resolve => setTimeout(resolve, 30)); assert.equal(calls, stoppedAt);
  } finally { scope.dispose(); globalThis.fetch = originalFetch; globalThis.document = originalDocument; }
});
