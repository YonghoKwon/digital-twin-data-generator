import { createScope, segment } from '../api.js';
import { MessageBuffer } from '../components/message-buffer.js';
import { brokerFields } from '../components/message-form.js';
import { el, page, panel, help, empty, badge, field, numeric, button, errorBox, actionButton, toast, table, code, check, confirmAction, copyText, primaryAction } from '../components/ui.js';
import { readState, writeState, filterMessages, CounterRate, subscriptionSelection, localTimestamp } from '../components/state.js';
import { rememberMessage } from '../components/plant.js';

export default function subscriptions(root, scope) {
  page(root, '구독 모니터', 'Topic을 구독하고 UE와 시뮬레이터가 보내는 메시지를 실시간으로 확인하세요.');
  const errors = errorBox(), pollErrors = errorBox(), list = el('div'), viewer = el('div', {}, empty('메시지를 확인할 구독을 선택하세요.', '구독 추가', () => topic.input.focus()));
  const topic = field('Topic   wildcard 허용', 'dt.sensor.#'), id = field('Subscription ID   선택', '');
  const size = field('서버 bufferSize', 1000, { type: 'number', min: 1, max: 10000 });
  const bytes = field('본문 미리보기 maxBodyBytes', 65536, { type: 'number', min: 1 });
  const broker = brokerFields();
  const create = actionButton('구독 추가', errors, async () => {
    const value = await scope.request('/dt/subscriptions', { method: 'POST', body: { topic: topic.value, ...(id.value.trim() ? { subscriptionId: id.value } : {}), bufferSize: numeric(size, 'bufferSize', 1, 10000), maxBodyBytes: numeric(bytes, 'maxBodyBytes', 1), ...broker.read() } });
    if (!scope.alive) return; await refresh(); open(value.subscriptionId);
    rememberMessage({ topic: topic.value }); toast(`구독을 추가했습니다. ${value.subscriptionId}`);
  }, 'primary');
  const formPanel = panel('새 구독', el('div', { class: 'form-grid' }, topic.node, id.node, size.node, bytes.node), help('점 구분 wildcard 사용. * 한 단어, # 0개 이상 단어'), broker.node, create);
  primaryAction(formPanel, create);
  root.append(errors.node, pollErrors.node, el('div', { class: 'grid subscription-grid' }, el('div', { class: 'stack' }, panel('구독 목록', list), formPanel), panel('실시간 메시지', viewer)));
  let selected = null, connectionScope;
  const summaries = new Map();
  const rates = new CounterRate();
  scope.add(() => connectionScope?.dispose());
  async function refresh() {
    try {
      const values = await scope.request('/dt/subscriptions'); if (!scope.alive) return;
      pollErrors.clear(); summaries.clear(); values.forEach(x => summaries.set(x.subscriptionId, x)); list.replaceChildren();
      if (!values.length) list.append(empty('등록된 구독이 없습니다.', '구독 추가', () => topic.input.focus()));
      for (const value of values) {
        const select = button(value.subscriptionId, () => open(value.subscriptionId), `small ${selected === value.subscriptionId ? 'selected' : ''}`);
        list.append(el('div', { class: `card ${value.status === 'RECONNECTING' ? 'warn-row' : ''}` }, el('div', { class: 'panel-head' }, select, badge(value.status)), help(`${value.topic}\n수신 ${value.receivedCount}건   ${rates.sample(value.subscriptionId, value.receivedCount).toFixed(1)} msg/s   버퍼 ${value.bufferedCount}/${value.bufferSize}`),
          value.lastError ? el('div', { class: 'error' }, value.lastError) : null,
          actionButton('구독 삭제', errors, async () => { if (!await confirmAction(value.subscriptionId, '구독 삭제')) return; await scope.request(`/dt/subscriptions/${segment(value.subscriptionId)}`, { method: 'DELETE' }); if (selected === value.subscriptionId) closeViewer(); toast(`구독을 삭제했습니다. ${value.subscriptionId}`); await refresh(); }, 'small danger')));
      }
      const nextSelection = subscriptionSelection(values, selected, readState('selected-subscription'));
      if (selected && !summaries.has(selected)) closeViewer();
      if (nextSelection && nextSelection !== selected) open(nextSelection);
    } catch (error) { pollErrors.show(error); }
  }
  function closeViewer() { connectionScope?.dispose(); connectionScope = null; selected = null; writeState('selected-subscription', null); viewer.replaceChildren(empty('메시지를 확인할 구독을 선택하세요.', '구독 추가', () => topic.input.focus())); }
  function open(subscriptionId) {
    if (!scope.alive || selected === subscriptionId) return;
    connectionScope?.dispose(); connectionScope = createScope(); const child = connectionScope;
    selected = subscriptionId;
    writeState('selected-subscription', subscriptionId);
    const url = `/dt/subscriptions/${segment(subscriptionId)}`;
    const buffer = new MessageBuffer(500), localErrors = errorBox(), detail = el('div', { class: 'message-detail' });
    const status = el('span', { class: 'connection' }, 'SSE 연결 중…');
    const warning = el('div', { class: 'notice', hidden: true });
    const rows = el('div', { class: 'message-list' }), total = el('span', { class: 'help' });
    const filter = field('Message ID 필터   완전 일치', '', { placeholder: '예: SENSOR' });
    const topicFilter = field('Topic 부분 검색', '');
    const follow = check('최신 따라가기', true), topicCounts = el('div');
    let paused = false, stream, timer, recovery = false, retry = 1000, renderTimer, selectedMessage = null;
    const pause = button('화면 일시정지', () => { paused = !paused; pause.textContent = paused ? '화면 재개' : '화면 일시정지'; if (!paused) render(); });
    const snapshot = actionButton('필터 조회', localErrors, async () => {
      const query = new URLSearchParams({ limit: String(Math.min(500, summaries.get(subscriptionId)?.bufferSize || 500)) });
      if (filter.value) query.set('messageId', filter.value);
      const messages = await child.request(`${url}/messages?${query}`);
      if (!child.alive) return; buffer.add(messages); render();
    }, 'small');
    filter.input.addEventListener('input', () => { if (!paused) render(); });
    topicFilter.input.addEventListener('input', render);
    viewer.replaceChildren(el('div', { class: 'panel-head' }, el('strong', {}, subscriptionId), status), help('화면 보관 최대 500건   일시정지는 화면 갱신만 멈춥니다. 구독과 수신은 계속됩니다.'),
      el('div', { class: 'row' }, topicFilter.node, filter.node, snapshot), el('div', { class: 'actions' }, pause, follow.node, actionButton('서버 버퍼 비우기', localErrors, async () => { if (!await confirmAction(subscriptionId, '서버 버퍼 비우기')) return; await child.request(`${url}/messages`, { method: 'DELETE' }); if (!child.alive) return; buffer.clear(); selectedMessage = null; detail.replaceChildren(); render(); }, 'small'), total), warning, localErrors.node, topicCounts, rows, detail);
    primaryAction(viewer, snapshot);
    function showDetail(message) {
      const body = message.bodyJson ?? message.body ?? message.bodyBase64Head;
      const raw = code(body);
      const fold = el('details', { open: true }, el('summary', {}, '본문 JSON / 원문'), raw);
      detail.replaceChildren(el('div', { class: 'panel-head' }, el('h3', {}, `메시지 ${message.seq}`), actionButton('본문 복사', localErrors, () => copyText(typeof body === 'string' ? body : JSON.stringify(body, null, 2)))),
        table(['항목', '값'], [['시각', el('time', { dateTime: message.receivedAt, title: message.receivedAt }, localTimestamp(message.receivedAt))], ['Topic', message.topic], ['MESSAGE_ID', message.messageId || '없음'], ['형식', `${message.type} / ${message.encoding}`], ['크기', `${message.sizeBytes} bytes${message.truncated ? ' (잘림)' : ''}`]]),
        fold, el('h3', {}, 'Properties'), table(['이름', '값'], Object.entries(message.properties || {})), actionButton('전체 메시지 복사', localErrors, () => copyText(JSON.stringify(message, null, 2))));
    }
    function render() {
      if (!child.alive || paused) return;
      const messages = filterMessages(buffer.values(), { topic: topicFilter.value, messageId: filter.value });
      const scroll = rows.scrollTop, focusedSeq = rows.contains(document.activeElement) ? document.activeElement.dataset.seq : null;
      const counts = new Map(); for (const message of buffer.values()) counts.set(message.topic, (counts.get(message.topic) || 0) + 1);
      topicCounts.replaceChildren(el('details', {}, el('summary', {}, 'Topic별 수신 수 (화면 보관 기준)'), table(['Topic', '수신 수'], [...counts])));
      rows.replaceChildren(); total.textContent = `표시 ${messages.length}건 / 보관 ${buffer.records.size}건   seq ${buffer.cursor}`;
      const messageTable = table(['시각', 'Topic', 'MESSAGE_ID', '크기', '형식'], messages.map(message => [
        el('time', { dateTime: message.receivedAt, title: message.receivedAt }, localTimestamp(message.receivedAt, { timeOnly: true })), message.topic, message.messageId || '', message.sizeBytes, `${message.type} / ${message.encoding}`
      ]));
      messageTable.classList.add('message-table');
      const messageRows = [...messageTable.querySelectorAll('tbody tr')];
      const selectedIndex = messages.findIndex(message => message.seq === selectedMessage);
      function selectMessage(index, scrollIntoView = false) {
        selectedMessage = messages[index].seq;
        messageRows.forEach((row, at) => { row.setAttribute('aria-selected', String(at === index)); row.tabIndex = at === index ? 0 : -1; });
        showDetail(messages[index]);
        messageRows[index].focus({ preventScroll: true });
        if (scrollIntoView) messageRows[index].scrollIntoView({ block: 'nearest' });
      }
      messageRows.forEach((row, index) => {
        row.classList.add('selectable-row'); row.dataset.seq = messages[index].seq;
        row.setAttribute('aria-selected', String(messages[index].seq === selectedMessage));
        row.tabIndex = index === (selectedIndex < 0 ? 0 : selectedIndex) ? 0 : -1;
        row.addEventListener('click', () => selectMessage(index));
        row.addEventListener('keydown', event => {
          if (!['ArrowUp', 'ArrowDown', 'Enter', ' '].includes(event.key)) return;
          event.preventDefault();
          const target = Math.max(0, Math.min(messages.length - 1, index + (event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0)));
          selectMessage(target, true);
        });
      });
      rows.append(messageTable);
      if (!messages.length) rows.append(empty('수신 메시지가 없습니다. Topic과 필터를 확인하세요.', '구독 추가', () => topic.input.focus()));
      if (focusedSeq) rows.querySelector(`[data-seq="${focusedSeq}"]`)?.focus({ preventScroll: true });
      rows.scrollTop = follow.value ? rows.scrollHeight : scroll;
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
      status.textContent = '재연결 대기   afterSeq로 복구';
      timer = setTimeout(connect, retry); retry = Math.min(30000, retry * 2);
    }
    function connect() {
      if (!child.alive) return;
      stream = new EventSource(`${url}/stream`);
      stream.addEventListener('ready', () => { status.textContent = 'SSE 연결됨'; retry = 1000; void catchUp(); });
      stream.addEventListener('message', event => {
        try { buffer.add([JSON.parse(event.data)]); queueRender(); }
        catch (error) { localErrors.show(error); }
      });
      stream.addEventListener('overflow', event => {
        let data; try { data = JSON.parse(event.data); } catch { data = { reason: 'SLOW_CONSUMER', dropped: '?' }; }
        warn(`SSE overflow   ${data.reason}   dropped=${data.dropped}. afterSeq로 자동 이어받습니다.`); reconnect();
      });
      stream.onerror = () => reconnect();
    }
    child.add(() => { stream?.close(); clearTimeout(timer); clearTimeout(renderTimer); });
    render(); connect(); void refresh();
  }
  scope.poll(refresh, 3000);
}
