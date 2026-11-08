import { active, request } from '../api.js';
import { el, badge, button, confirmAction, copyText, toast } from './ui.js';
import { collectSuggestions, CounterRate, writeState } from './state.js';
import { loadHistory } from './history.js';

export const plant = { streams: [], subscriptions: [], runs: [], tasks: [], library: [], definitions: new Map() };
export async function stopAll() {
  if (!await confirmAction('모든 스트림, 시나리오 실행, 기존 task', '전체 정지')) return;
  const results = [];
  for (const [label, path, method] of [
    ['스트림', '/dt/streams', 'DELETE'], ['시나리오', '/dt/scenario-runs', 'DELETE'], ['기존 task', '/activemq/task/cancel-tasks', 'POST']
  ]) {
    try { await request(path, { method }); results.push(`${label} 정지 완료`); }
    catch (error) { results.push(`${label} 정지 실패 ${error.message}`); }
  }
  toast(`전체 정지 결과\n${results.join('\n')}`, results.some(x => x.includes('실패')));
  window.dispatchEvent(new Event('dts-refresh'));
}
export function websocketAddress() { return plant.broker?.websocketUrl || ''; }
export function rememberMessage(request) {
  const recent = loadHistory().map(x => x.request);
  recent.unshift({ topic: request.topic, messageId: request.messageId });
  writeState('recent-inputs', recent.slice(0, 30));
  updateSuggestions(recent);
}
function updateSuggestions(recent = loadHistory().map(x => x.request)) {
  const values = collectSuggestions({ ...plant, scenarios: [...plant.definitions.values()], recent });
  for (const [id, entries] of [['known-topics', values.topics], ['known-message-ids', values.messageIds]]) {
    document.getElementById(id)?.replaceChildren(...entries.map(value => el('option', { value })));
  }
}
export function startStatus(scope) {
  const rate = new CounterRate();
  const holder = document.getElementById('plant-status');
  let count = 0;
  const paths = { broker: '/dt/broker', streams: '/dt/streams', subscriptions: '/dt/subscriptions', runs: '/dt/scenario-runs', tasks: '/activemq/task/running-tasks', mock: '/mock-admin/status' };
  async function refresh() {
    const entries = Object.entries(paths);
    const results = await Promise.allSettled(entries.map(([, path]) => scope.request(path)));
    if (!scope.alive) return;
    const failed = [];
    results.forEach((result, index) => {
      const key = entries[index][0];
      if (result.status === 'fulfilled') plant[key] = result.value;
      else if (result.reason.name !== 'AbortError' && !(key === 'runs' && result.reason.status === 404)) failed.push(key);
    });
    const b = plant.broker;
    const alarms = [
      ...(failed.length ? [{ label: '상태 조회 실패', route: 'dashboard' }] : []),
      ...(b?.lastError || (b?.enabled && !b.running) ? [{ label: '브로커 오류', route: 'dashboard' }] : []),
      ...plant.streams.filter(x => ['FAILED', 'RECONNECTING'].includes(x.state)).map(x => ({ label: `${x.streamId} ${x.state}`, route: 'streams' })),
      ...plant.runs.filter(x => ['FAILED', 'RECONNECTING'].includes(x.state)).map(x => ({ label: `${x.runId} ${x.state}`, route: 'scenarios' })),
      ...plant.subscriptions.filter(x => x.status === 'RECONNECTING').map(x => ({ label: `${x.subscriptionId} 재연결`, route: 'subscriptions' }))
    ];
    const msgRate = plant.subscriptions.reduce((sum, x) => sum + rate.sample(x.subscriptionId, x.receivedCount), 0);
    const runs = plant.runs.filter(x => active(x.state));
    const brokerButton = button(b ? `${b.defaultTarget === 'EMBEDDED' ? '내장' : '외부'} ${b.version || ''} ${b.websocketUrl || ''}` : '브로커 확인 중', () => void copyText(websocketAddress()).catch(error => toast(error.message, true)));
    brokerButton.setAttribute('aria-label', `브로커 주소 복사 ${b?.websocketUrl || ''}`);
    holder.replaceChildren(
      ...alarms.slice(0, 1).map(alarm => button(`${alarm.label}${alarms.length > 1 ? ` 외 ${alarms.length - 1}` : ''}`, () => { location.hash = `#/${alarm.route}`; }, 'alarm-cell')),
      el('span', { class: 'actions' }, badge(b?.running ? 'CONNECTED' : b?.defaultTarget === 'EXTERNAL' ? 'EXTERNAL' : 'STOPPED'), brokerButton),
      el('a', { href: '#/streams' }, `스트림 ${plant.streams.filter(x => active(x.state)).length}`),
      el('a', { href: '#/subscriptions' }, `구독 ${plant.subscriptions.length} (${msgRate.toFixed(1)} msg/s)`),
      el('a', { href: '#/scenarios' }, `시나리오 ${runs.length}${runs.length ? ` (${Math.round((runs[0].progress || 0) * 100)}%)` : ''}`),
      el('a', { href: '#/mock' }, `Mock ${plant.mock?.routeCount ?? '확인 중'}`)
    );
    updateSuggestions();
    window.dispatchEvent(new Event('dts-status'));
    if (count++ % 10 === 0) {
      try {
        plant.library = await scope.request('/dt/scenarios');
        const definitions = await Promise.allSettled(plant.library.map(async entry => [entry.file, await scope.request(`/dt/scenarios/${encodeURIComponent(entry.file)}`)]));
        if (!scope.alive) return;
        plant.definitions.clear();
        for (const result of definitions) if (result.status === 'fulfilled') plant.definitions.set(...result.value);
        updateSuggestions();
      } catch { /* Older servers may not provide the scenario library. */ }
    }
  }
  const tick = scope.poll(refresh, 3000);
  window.addEventListener('dts-refresh', tick);
  scope.add(() => window.removeEventListener('dts-refresh', tick));
}
