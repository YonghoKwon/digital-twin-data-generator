import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { readState, writeState, savePreset, loadPresets, deletePreset, searchCommands, SparkBuffer, CounterRate, filterMessages, collectSuggestions, jsonErrorPosition } from '../../main/resources/static/ui/components/state.js';
import { atMillis, timelineEvents } from '../../main/resources/static/ui/components/plots.js';

function storage() {
  const values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  return values;
}
test('presets and persisted state remove nested credentials including JSON strings and broker URLs', () => {
  const values = storage();
  const payload = { temperature: 22, pw: 'private', children: [{ password: 'private', Token: 'private', normal: 'OK' }], raw: '{"Authorization":"private","value":2}', activeMQIp: 'tcp://user:private@host' };
  savePreset('공정 A', payload);
  const preset = loadPresets().find(x => x.name === '공정 A');
  assert.deepEqual(preset.payload, { temperature: 22, children: [{ normal: 'OK' }], raw: '{"value":2}' });
  assert.equal(loadPresets().length, 4);
  savePreset('공정 A', { value: 4 }); assert.equal(loadPresets().length, 4);
  assert.throws(() => savePreset(' ', {})); assert.throws(() => savePreset('센서 값', {}));
  writeState('draft', { payload, headers: { api_key: 'private', ordinary: 'yes' } });
  assert.ok(![...values.values()].join('').includes('private'));
  assert.equal(readState('draft').headers.ordinary, 'yes');
  deletePreset('공정 A'); assert.equal(loadPresets().length, 3);
});
test('storage denied or corrupt remains usable with built-in presets', () => {
  globalThis.localStorage = { getItem: () => '{invalid', setItem: () => { throw Error('denied'); } };
  assert.equal(loadPresets().length, 3); assert.equal(readState('theme', 'system'), 'system');
  assert.doesNotThrow(() => savePreset('local', { temperature: 2 }));
});
test('command search matches Korean and case-insensitive multiword terms', () => {
  const commands = [{ label: '새 스트림' }, { label: '시나리오 실행 coil-demo.json' }, { label: '브로커 주소 복사' }];
  assert.equal(searchCommands(commands, '').length, 3);
  assert.deepEqual(searchCommands(commands, 'COIL 실행'), [commands[1]]);
  assert.deepEqual(searchCommands(commands, '스트림'), [commands[0]]);
  assert.deepEqual(searchCommands(commands, '없는 명령'), []);
});
test('sparkline holds a rolling 60 second bounded buffer and finite values', () => {
  const buffer = new SparkBuffer();
  for (let second = 0; second <= 120; second++) buffer.add(second, second * 1000);
  assert.equal(buffer.samples.length, 60); assert.equal(buffer.samples[0].at, 61000);
  buffer.add(NaN, 121000); buffer.add(Infinity, 121000); assert.equal(buffer.samples.length, 60);
  buffer.add(5, 120000); assert.equal(buffer.samples.length, 60); assert.equal(buffer.samples.at(-1).value, 5);
  for (let index = 0; index < 200; index++) buffer.add(index, 120001 + index);
  assert.ok(buffer.samples.length <= 120);
});
test('counter rates handle first observation, resets, separate subscriptions and elapsed time', () => {
  const rate = new CounterRate();
  assert.equal(rate.sample('a', 10, 1000), 0);
  assert.equal(rate.sample('a', 40, 4000), 10);
  assert.equal(rate.sample('b', 50, 4000), 0);
  assert.equal(rate.sample('a', 0, 5000), 0);
  assert.equal(rate.sample('a', 1, 5000), 0);
});
test('message filter combines topic substring with exact MESSAGE_ID without reordering records', () => {
  const messages = [{ seq: 1, topic: 'dt.COIL.temp', messageId: 'A' }, { seq: 2, topic: 'dt.coil.status', messageId: 'AB' }, { seq: 3, topic: 'other', messageId: 'A' }, { seq: 4 }];
  assert.deepEqual(filterMessages(messages, { topic: 'coil', messageId: 'A' }), [messages[0]]);
  assert.deepEqual(filterMessages(messages, { messageId: 'A' }), [messages[0], messages[2]]);
  assert.deepEqual(filterMessages(messages), messages);
});
test('autocomplete merges broker addresses, streams, subscriptions, scenario defaults, events and recent input', () => {
  const result = collectSuggestions({ broker: { addresses: [{ name: 'dt.broker' }] }, streams: [{ topic: 'dt.stream', messageId: 'SENSOR' }], subscriptions: [{ topic: 'dt.sub' }], scenarios: [{ defaults: { topic: 'dt.default' }, events: [{ topic: 'dt.event', messageId: 'ALARM' }] }], recent: [{ topic: 'dt.stream', messageId: 'PING' }] });
  assert.deepEqual(result.topics, ['dt.broker', 'dt.default', 'dt.event', 'dt.stream', 'dt.sub']);
  assert.deepEqual(result.messageIds, ['ALARM', 'PING', 'SENSOR']);
});
test('JSON validation reports line and column for syntax errors', () => {
  const source = '{\n "temperature": }';
  try { JSON.parse(source); assert.fail('must fail'); }
  catch (error) { assert.match(jsonErrorPosition(source, error), /^2:\d+$/); }
  assert.equal(jsonErrorPosition('{}', Error('unexpected end')), '1:3');
});
test('timeline converts clock timestamps, expands repeated ticks, preserves MARK and MOCK shapes and CSV tracks', () => {
  assert.equal(atMillis('00:00:01.500'), 1500);
  const events = timelineEvents({ defaults: { topic: 'dt.temp' }, events: [{ at: 0, repeat: { count: 3, everyMs: 500 } }, { at: 1500, type: 'MARK', label: '가열' }, { at: 2000, type: 'MOCK_ENABLE' }], tracks: [{ trackId: 'line1', startAt: 500 }] }, [{ atMs: 700, type: 'PUBLISH', topic: 'dt.csv' }]);
  assert.deepEqual(events.slice(0, 3).map(x => x.atMs), [0, 500, 1000]);
  assert.equal(events[3].label, '가열'); assert.equal(events[4].type, 'MOCK_ENABLE');
  assert.equal(events[5].type, 'TRACK'); assert.equal(events[6].topic, 'dt.csv');
});
test('HMI assets exclude decorative dashboard patterns and unsafe HTML rendering', async () => {
  const directory = new URL('../../main/resources/static/ui/', import.meta.url);
  const css = await readFile(new URL('styles.css', directory), 'utf8');
  assert.doesNotMatch(css, /box-shadow|gradient\(|letter-spacing|border-radius:\s*(?:50%|999)/i);
  assert.match(css, /width:132px/); assert.match(css, /prefers-reduced-motion/);
  async function inspect(path) {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const child = new URL(entry.name + (entry.isDirectory() ? '/' : ''), path);
      if (entry.isDirectory()) await inspect(child);
      else if (/\.(js|html)$/.test(entry.name)) {
        const source = await readFile(child, 'utf8');
        assert.doesNotMatch(source, /innerHTML|SIMULATOR \/ CONTROL CENTER|eyebrow|→|↗|▷|◫|≋|⇥|◇|⌘| · /, entry.name);
      }
    }
  }
  await inspect(directory);
});

test('HMI text tones maintain 4.5 contrast on panel, face and alert rows in both themes', () => {
  const rgb = hex => hex.match(/../g).map(value => parseInt(value, 16));
  const mix = (a, b, amount) => a.map((value, index) => value * amount + b[index] * (1 - amount));
  const luminance = values => values.map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
  const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + .05) / (Math.min(luminance(a), luminance(b)) + .05);
  for (const theme of [
    { panel: 'DADDE0', face: 'EEF0F1', ink: '1C2126', dim: '5E676F', alarm: 'C8102E', warn: 'D99A00', act: '1F4E79' },
    { panel: '2B3036', face: '343A41', ink: 'E3E6E8', dim: '9AA3AB', alarm: 'FF5A5F', warn: 'F2B630', act: '7FB0DD' }
  ]) {
    const colors = Object.fromEntries(Object.entries(theme).map(([key, value]) => [key, rgb(value)]));
    const dim = mix(colors.dim, colors.ink, .85), alarm = mix(colors.alarm, colors.ink, .5);
    for (const background of [colors.panel, colors.face]) {
      assert.ok(contrast(colors.ink, background) >= 4.5);
      assert.ok(contrast(dim, background) >= 4.5);
      assert.ok(contrast(colors.act, background) >= 4.5);
      assert.ok(contrast(alarm, background) >= 4.5);
    }
    assert.ok(contrast(alarm, mix(colors.alarm, colors.face, .1)) >= 4.5);
    assert.ok(contrast(colors.ink, mix(colors.warn, colors.face, .1)) >= 4.5);
  }
});
