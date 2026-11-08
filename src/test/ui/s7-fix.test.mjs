import test from 'node:test';
import assert from 'node:assert/strict';
import { sparklineScale, layoutMarkLabels, timelineEvents } from '../../main/resources/static/ui/components/plots.js';
import { classifyAddresses, subscriptionSelection, localTimestamp } from '../../main/resources/static/ui/components/state.js';

test('sparkline reserves the full 60 seconds and scales against target or observed peak with 20% headroom', () => {
  const samples = [{ at: 39000, value: 999 }, { at: 99000, value: 10 }, { at: 100000, value: 20 }, { at: 101000, value: 999 }];
  const scale = sparklineScale(samples, 100, { now: 100000, width: 600, height: 36 });
  assert.equal(scale.max, 120);
  assert.deepEqual(scale.points.map(point => point.x), [590, 600]);
  assert.equal(scale.points[1].y, 34 - 20 / 120 * 32);
  assert.equal(scale.targetY, 34 - 100 / 120 * 32);
  assert.equal(sparklineScale([{ at: 100000, value: 200 }], 100, { now: 100000 }).max, 240);
  const fullMinute = sparklineScale([{ at: 40000, value: 10 }, { at: 100000, value: 10 }], 10, { now: 100000, width: 600 });
  assert.deepEqual(fullMinute.points.map(point => point.x), [0, 600]);
});

test('sparkline leaves unknown history empty and draws paused zero samples at the bottom', () => {
  const paused = sparklineScale([{ at: 99000, value: 10 }, { at: 100000, value: 0 }], 10, { now: 100000 });
  assert.equal(paused.points.length, 2);
  assert.equal(paused.points.at(-1).y, 34);
  const empty = sparklineScale([], 0, { now: 100000 });
  assert.deepEqual(empty.points, []); assert.ok(Number.isFinite(empty.targetY));
});

const measure = text => [...text].length * 8;
function assertSeparated(labels, left, right) {
  for (const label of labels) {
    assert.ok(label.start >= left && label.end <= right);
    assert.ok(measure(label.display) <= label.width);
  }
  for (const lane of [0, 1]) {
    const items = labels.filter(label => label.lane === lane && label.display);
    for (let index = 1; index < items.length; index++) assert.ok(items[index - 1].end <= items[index].start);
  }
}
test('MARK labels alternate lanes to avoid collisions and right-align the end label inside the plot', () => {
  const labels = layoutMarkLabels([
    { atMs: 0, label: 'running' }, { atMs: 50, label: '상태 전환' }, { atMs: 1000, label: 'complete' }
  ], { left: 100, right: 500, durationMs: 1000, measure });
  assert.equal(labels[0].lane, 0); assert.equal(labels[1].lane, 1);
  assert.equal(labels[2].align, 'right'); assert.equal(labels[2].end, 500);
  assert.equal(labels[2].display, 'complete');
  assertSeparated(labels, 100, 500);
});

test('crowded, long and simultaneous MARKs never collide or escape the plot and preserve full tooltip text', () => {
  const marks = [0, 1, 2, 50, 51, 900, 999, 1000, 1000, 1000].map((atMs, index) => ({ atMs, label: `긴 라벨 ${index} 원문은 title에서 확인합니다` }));
  for (const width of [80, 300, 600]) {
    const labels = layoutMarkLabels(marks, { left: 100, right: 100 + width, durationMs: 1000, measure });
    assertSeparated(labels, 100, 100 + width);
    assert.ok(labels.some(label => label.display.endsWith('…')));
    assert.ok(labels.every(label => label.text.includes('원문')));
  }
});

test('timeline merges logged MARK ticks without printing duplicate generic MARK labels', () => {
  const events = timelineEvents({ defaults: { topic: 'dt.sensor' }, events: [{ at: 10, type: 'MARK', label: 'running' }] }, [{ atMs: 10, type: 'MARK', topic: null, result: 'MARK' }]);
  assert.equal(events.length, 1); assert.equal(events[0].label, 'running');
});

test('broker addresses hide internal namespaces before separating wildcard simulator subscriptions', () => {
  const addresses = [
    { name: 'activemq.notifications', consumerCount: 1 }, { name: 'ActiveMQ.management.#' }, { name: '$sys.broker' }, { name: '$management' },
    { name: 'dt.sensor.#', consumerCount: 1 }, { name: 'dt.*.temp', consumerCount: 2 },
    { name: 'dt.sensor.temp', consumerCount: 3 }, { name: 'dt.idle', consumerCount: 0 }
  ];
  const result = classifyAddresses(addresses);
  assert.deepEqual(result.topics, addresses.slice(6));
  assert.deepEqual(result.simulator, addresses.slice(4, 6));
  assert.equal(result.topics[1].consumerCount, 0);
  assert.deepEqual(classifyAddresses(), { topics: [], simulator: [] });
});

test('subscription selection restores a valid last choice or the only subscription without switching an active selection', () => {
  const values = [{ subscriptionId: 'one' }, { subscriptionId: 'two' }];
  assert.equal(subscriptionSelection(values, null, 'two'), 'two');
  assert.equal(subscriptionSelection(values, 'one', 'two'), 'one');
  assert.equal(subscriptionSelection(values, null, 'deleted'), null);
  assert.equal(subscriptionSelection(values.slice(0, 1), null, 'deleted'), 'one');
  assert.equal(subscriptionSelection([], 'deleted', 'deleted'), null);
});

test('message timestamps use local date and millisecond time including nano ISO input and invalid fallback', () => {
  const local = new Date(2026, 10, 8, 4, 5, 6, 7);
  assert.equal(localTimestamp(local.toISOString()), '2026-11-08 04:05:06.007');
  assert.equal(localTimestamp(local.toISOString().replace('.007', '.007123456'), { timeOnly: true }), '04:05:06.007');
  assert.equal(localTimestamp('bad-date'), 'bad-date'); assert.equal(localTimestamp(null), '없음');
});
