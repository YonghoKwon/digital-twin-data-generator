import { createScope, segment } from '../api.js';
import { MessageBuffer } from '../components/message-buffer.js';
import { brokerFields } from '../components/message-form.js';
import { el, page, panel, help, empty, badge, field, numeric, button, errorBox, resultBox, actionButton, toast } from '../components/ui.js';

export default function subscriptions(root, scope) {
  page(root, '구독 모니터', 'Topic을 구독하고 UE와 시뮬레이터가 보내는 메시지를 실시간으로 확인하세요.');
  const errors = errorBox(), pollErrors = errorBox(), list = el('div'), viewer = el('div', {}, empty('메시지를 확인할 구독을 선택하세요.'));
  const topic = field('Topic · wildcard 허용', 'dt.sensor.#'), id = field('Subscription ID · 선택', '');
  const size = field('서버 bufferSize', 1000, { type: 'number', min: 1, max: 10000 });
  const bytes = field('본문 미리보기 maxBodyBytes', 65536, { type: 'number', min: 1 });
  const broker = brokerFields();
  const create = actionButton('구독 만들기', errors, async () => {
    const value = await scope.request('/dt/subscriptions', { method: 'POST', body: { topic: topic.value, ...(id.value.trim() ? { subscriptionId: id.value } : {}), bufferSize: numeric(size, 'bufferSize', 1, 10000), maxBodyBytes: numeric(bytes, 'maxBodyBytes', 1), ...broker.read() } });
    if (!scope.alive) return; await refresh(); open(value.subscriptionId);
  }, 'primary');
  root.append(errors.node, pollErrors.node, el('div', { class: 'grid' }, el('div', { class: 'stack' }, panel('새 구독', el('div', { class: 'form-grid' }, topic.node, id.node, size.node, bytes.node), help('점으로 단어를 구분합니다. * = 한 단어, # = 0개 이상 단어\n예: dt.sensor.# → dt.sensor.temp, dt.sensor.line1.temp'), broker.node, create), panel('구독 목록', list)), panel('실시간 메시지', viewer)));
  let selected = null, connectionScope;
  const summaries = new Map();
  scope.add(() => connectionScope?.dispose());
  async function refresh() {
    try {
      const values = await scope.request('/dt/subscriptions'); if (!scope.alive) return;
      pollErrors.clear(); summaries.clear(); values.forEach(x => summaries.set(x.subscriptionId, x)); list.replaceChildren();
      if (!values.length) list.append(empty('등록된 구독이 없습니다.'));
      for (const value of values) {
        const select = button(value.subscriptionId, () => open(value.subscriptionId), `small ${selected === value.subscriptionId ? 'selected' : ''}`);
        list.append(el('div', { class: 'card' }, el('div', { class: 'panel-head' }, select, badge(value.status)), help(`${value.topic}\n수신 ${value.receivedCount}건 · 버퍼 ${value.bufferedCount}/${value.bufferSize}`),
          value.lastError ? el('div', { class: 'error' }, value.lastError) : null,
          actionButton('구독 삭제', errors, async () => { await scope.request(`/dt/subscriptions/${segment(value.subscriptionId)}`, { method: 'DELETE' }); if (selected === value.subscriptionId) closeViewer(); await refresh(); }, 'small danger')));
      }
      if (selected && !summaries.has(selected)) closeViewer();
    } catch (error) { pollErrors.show(error); }
  }
  function closeViewer() { connectionScope?.dispose(); connectionScope = null; selected = null; viewer.replaceChildren(empty('메시지를 확인할 구독을 선택하세요.')); }
  function open(subscriptionId) {
    if (!scope.alive || selected === subscriptionId) return;
    connectionScope?.dispose(); connectionScope = createScope(); const child = connectionScope;
    selected = subscriptionId;
    const url = `/dt/subscriptions/${segment(subscriptionId)}`;
    const buffer = new MessageBuffer(500), localErrors = errorBox(), detail = resultBox();
    const status = el('span', { class: 'connection' }, 'SSE 연결 중…');
    const warning = el('div', { class: 'notice', hidden: true });
    const rows = el('div', { class: 'message-list' }), total = el('span', { class: 'help' });
    const filter = field('Message ID 필터 · 완전 일치', '', { placeholder: '예: SENSOR' });
    let paused = false, stream, timer, recovery = false, retry = 1000, renderTimer;
    const pause = button('화면 일시정지', () => { paused = !paused; pause.textContent = paused ? '화면 재개' : '화면 일시정지'; if (!paused) render(); });
    const snapshot = actionButton('필터 조회', localErrors, async () => {
      const query = new URLSearchParams({ limit: String(Math.min(500, summaries.get(subscriptionId)?.bufferSize || 500)) });
      if (filter.value) query.set('messageId', filter.value);
      const messages = await child.request(`${url}/messages?${query}`);
      if (!child.alive) return; buffer.add(messages); render();
    }, 'small');
    filter.input.addEventListener('input', () => { if (!paused) render(); });
    viewer.replaceChildren(el('div', { class: 'panel-head' }, el('strong', {}, subscriptionId), status), help('화면 보관 최대 500건 · 일시정지는 화면 갱신만 멈춥니다. 구독과 수신은 계속됩니다.'),
      el('div', { class: 'row' }, filter.node, snapshot), el('div', { class: 'actions' }, pause, actionButton('서버 버퍼 비우기', localErrors, async () => { await child.request(`${url}/messages`, { method: 'DELETE' }); if (!child.alive) return; buffer.clear(); detail.clear(); render(); }, 'small'), total), warning, localErrors.node, rows, el('h3', {}, '메시지 상세 · 본문과 properties'), detail.node);
    function render() {
      if (!child.alive || paused) return;
      const messages = buffer.values(filter.value);
      rows.replaceChildren(); total.textContent = `표시 ${messages.length}건 / 보관 ${buffer.records.size}건 · seq ${buffer.cursor}`;
      if (!messages.length) rows.append(empty('아직 수신한 메시지가 없거나 필터에 맞는 기록이 없습니다.'));
      for (const message of messages.toReversed()) {
        const summary = `${message.body ?? message.bodyBase64Head ?? ''}`.slice(0, 120);
        rows.append(button(`#${message.seq} · ${message.type}/${message.encoding} · ${message.messageId || 'ID 없음'}\n${message.receivedAt} · ${message.sizeBytes} bytes${message.truncated ? ' · 잘림' : ''}\n${summary}`, () => detail.show({ ...message, body: message.bodyJson ?? message.body, ...(message.encoding === 'BINARY' ? { binaryNote: 'BINARY 본문 · bodyBase64Head는 앞 256바이트의 Base64입니다.' } : {}) }), 'message-row'));
      }
    }
    function queueRender() { if (!renderTimer) renderTimer = setTimeout(() => { renderTimer = null; render(); }, 150); }
    function warn(text) { warning.hidden = false; warning.textContent = text; toast(text, true); }
    async function catchUp() {
      if (recovery) return; recovery = true;
      let cursor = buffer.cursor;
      try {
        const limit = Math.min(500, summaries.get(subscriptionId)?.bufferSize || 500);
        for (;;) {
          const query = new URLSearchParams({ limit: String(limit) });
          if (cursor) query.set('afterSeq', String(cursor));
          const messages = await child.request(`${url}/messages?${query}`);
          if (!child.alive) return;
          if (cursor && messages.length && messages[0].seq > cursor + 1) warn('서버 버퍼가 오래된 메시지를 제거했습니다. 보관 중인 seq부터 이어받습니다.');
          buffer.add(messages); queueRender();
          if (!messages.length) break;
          const next = Math.max(...messages.map(x => x.seq));
          if (next <= cursor || messages.length < limit) break;
          cursor = next;
        }
        localErrors.clear();
      } catch (error) { localErrors.show(error); if (child.alive) reconnect(); }
      finally { recovery = false; }
    }
    function reconnect() {
      stream?.close(); clearTimeout(timer);
      if (!child.alive) return;
      status.textContent = '재연결 대기 · afterSeq로 복구';
      timer = setTimeout(connect, retry); retry = Math.min(30000, retry * 2);
    }
    function connect() {
      if (!child.alive) return;
      stream = new EventSource(`${url}/stream`);
      stream.addEventListener('ready', () => { status.textContent = '● SSE 연결됨'; retry = 1000; void catchUp(); });
      stream.addEventListener('message', event => {
        try { buffer.add([JSON.parse(event.data)]); queueRender(); }
        catch (error) { localErrors.show(error); }
      });
      stream.addEventListener('overflow', event => {
        let data; try { data = JSON.parse(event.data); } catch { data = { reason: 'SLOW_CONSUMER', dropped: '?' }; }
        warn(`SSE overflow · ${data.reason} · dropped=${data.dropped}. afterSeq로 자동 이어받습니다.`); reconnect();
      });
      stream.onerror = () => reconnect();
    }
    child.add(() => { stream?.close(); clearTimeout(timer); clearTimeout(renderTimer); });
    render(); connect(); void refresh();
  }
  scope.poll(refresh, 3000);
}
