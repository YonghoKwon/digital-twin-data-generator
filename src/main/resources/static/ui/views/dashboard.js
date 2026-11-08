import { segment, request } from '../api.js';
import { el, page, panel, badge, field, table, errorBox, actionButton, copyText, help, toast, primaryAction } from '../components/ui.js';
import { plant } from '../components/plant.js';

export default function dashboard(root, scope) {
  page(root, 'UE 연결 점검');
  const errors = errorBox(), broker = el('div'), consumers = el('div'), settings = el('div'), running = el('div');
  const topic = field('테스트 Topic', 'dt.ping');
  const pingResult = el('div', { role: 'status' });
  const ping = actionButton('테스트 메시지 보내기', errors, async () => {
    const name = topic.value.trim();
    if (!name) throw new Error('테스트 Topic을 입력하세요.');
    const subscriptionId = `ui-ping-${crypto.randomUUID()}`;
    const nonce = crypto.randomUUID();
    let created = false, failure;
    const start = performance.now();
    try {
      await request('/dt/subscriptions', { method: 'POST', body: { subscriptionId, topic: name, bufferSize: 10 } }); created = true;
      await request('/dt/publish', { method: 'POST', body: { topic: name, messageId: 'SIM_PING', payload: { nonce } } });
      const deadline = performance.now() + 5000;
      let received = false;
      while (performance.now() < deadline && scope.alive) {
        const messages = await scope.request(`/dt/subscriptions/${segment(subscriptionId)}/messages?limit=10`);
        received = messages.some(x => x.messageId === 'SIM_PING' && JSON.stringify(x.bodyJson ?? x.body).includes(nonce));
        if (received) break;
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      if (scope.alive && !received) throw new Error('5초 안에 SIM_PING 수신을 확인하지 못했습니다. 기본 브로커 대상과 Topic을 확인하세요.');
      if (scope.alive) { pingResult.textContent = `SIM_PING 왕복 ${Math.round(performance.now() - start)} ms`; toast('테스트 메시지를 보냈습니다. 왕복 수신을 확인했습니다.'); }
    } catch (error) { failure = error; }
    finally {
      if (created) {
        try { await request(`/dt/subscriptions/${segment(subscriptionId)}`, { method: 'DELETE' }); }
        catch (error) { failure = new Error(`${failure ? `${failure.message}\n` : ''}임시 구독 삭제 실패 ${subscriptionId}\n${error.message}`); }
      }
    }
    if (failure) throw failure;
  }, 'primary');
  root.append(errors.node, el('div', { class: 'grid' },
    panel('1 브로커 실행 확인', broker), panel('2 UE 구독자 수', consumers),
    panel('3 테스트 메시지 왕복', topic.node, ping, pingResult), panel('UE DTCoreSettings', settings)),
    panel('실행 중 항목', running));
  primaryAction(ping.closest('fieldset'), ping);
  function render() {
    if (!scope.alive) return;
    const b = plant.broker;
    broker.replaceChildren(b ? el('div', {}, badge(b.running ? 'RUNNING' : 'STOPPED'), el('p', {}, `기본 대상 ${b.defaultTarget === 'EMBEDDED' ? '내장' : '외부'} Artemis ${b.version}`), el('p', {}, b.websocketUrl), b.lastError ? el('div', { class: 'error' }, b.lastError) : null) : help('브로커 상태 확인 중'));
    consumers.replaceChildren(b?.running ? table(['Address', '구독자', '대기 메시지'], (b.addresses || []).map(x => [x.name, x.consumerCount, x.messageCount])) : help('외부 브로커의 구독자 수는 조회 API에서 제공하지 않습니다.'));
    consumers.append(help('구독자 수에 시뮬레이터 구독이 포함됩니다.'));
    const text = `WebSocketUrl=${b?.websocketUrl || '확인 중'}\nTestApiUrl=${location.origin}/mock`;
    settings.replaceChildren(el('div', { class: 'settings-block' }, text), actionButton('DTCoreSettings 복사', errors, () => copyText(text)), help('다른 PC에서는 호스트를 서버 PC의 IP로 입력하세요. WebSocketUrl은 내장 브로커 설정값입니다.'));
    const rows = [
      ...plant.streams.filter(x => ['RUNNING', 'PAUSED', 'RECONNECTING'].includes(x.state)).map(x => ['스트림', x.streamId, badge(x.state), x.topic, x.sent]),
      ...plant.subscriptions.map(x => ['구독', x.subscriptionId, badge(x.status), x.topic, x.receivedCount]),
      ...plant.runs.filter(x => ['RUNNING', 'PAUSED', 'RECONNECTING'].includes(x.state)).map(x => ['시나리오', x.runId, badge(x.state), `${Math.round(x.progress * 100)}%`, x.sent]),
      ...plant.tasks.map(x => ['기존 task', x.taskId, badge('RUNNING'), '', ''])
    ];
    running.replaceChildren(rows.length ? table(['종류', '이름', '상태', 'Topic / 진행률', '메시지 수'], rows) : el('div', { class: 'empty' }, '실행 중인 항목이 없습니다.', el('a', { href: '#/streams?action=new' }, '새 스트림')));
  }
  window.addEventListener('dts-status', render); scope.add(() => window.removeEventListener('dts-status', render)); render();
}
