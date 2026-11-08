import { active, segment } from '../api.js';
import { el, page, panel, badge, help, empty, field, metrics, numeric, errorBox, actionButton, jsonEditor, tokenHelp } from '../components/ui.js';
import { messageForm } from '../components/message-form.js';

export default function streams(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), form = messageForm({ stream: true }), cards = new Map(), list = el('div');
  page(root, '스트림', '지속 전송을 만들고 실행 중에도 속도·Payload를 조정하세요.', actionButton('전체 스트림 중지', errors, async () => {
    await scope.request('/dt/streams', { method: 'DELETE' }); await refresh();
  }, 'danger'));
  const create = actionButton('스트림 시작 ▷', errors, async () => {
    await scope.request('/dt/streams', { method: 'POST', body: form.read() }); await refresh();
  }, 'primary');
  root.append(errors.node, pollErrors.node, el('div', { class: 'grid' }, panel('새 스트림', form.node, create), panel('스트림 모니터 · 1초 갱신', list)));
  function createCard(stream) {
    const state = el('span'), stats = el('div'), lastError = el('div', { class: 'error', hidden: true });
    const localErrors = errorBox();
    const rate = field('rateTps', stream.rateTps, { type: 'number', min: 0.001, max: 5000, step: 'any' });
    const slider = el('input', { type: 'range', min: 0.1, max: 5000, step: 0.1, value: stream.rateTps, 'aria-label': `${stream.streamId} 전송 속도` });
    let dirty = false;
    const url = `/dt/streams/${segment(stream.streamId)}`;
    const patch = async body => { await scope.request(url, { method: 'PATCH', body }); await refresh(); };
    const apply = actionButton('속도 적용', localErrors, async () => { await patch({ rateTps: numeric(rate, 'rateTps', Number.MIN_VALUE, 5000) }); dirty = false; }, 'small');
    rate.input.addEventListener('input', () => { dirty = true; slider.value = rate.value; });
    rate.input.addEventListener('change', () => apply.click());
    slider.addEventListener('input', () => { dirty = true; rate.value = slider.value; });
    slider.addEventListener('change', () => apply.click());
    const pause = actionButton('일시정지', localErrors, () => patch({ paused: current.state !== 'PAUSED' }), 'small');
    const stop = actionButton('중지', localErrors, async () => { await scope.request(url, { method: 'DELETE' }); await refresh(); }, 'small danger');
    const editor = jsonEditor('교체할 Payload', { temperature: '{{walk:20:10..30:0.5}}' }, 6);
    const replace = actionButton('Payload 교체', localErrors, () => patch({ payload: editor.get() }), 'small');
    const node = el('article', { class: 'card' }, el('div', { class: 'card-head' }, el('div', {}, el('h3', {}, stream.streamId), help(`${stream.topic} · ${stream.messageId || 'RAW'}`)), state), stats,
      el('div', { class: 'row' }, el('div', { class: 'grow' }, slider), rate.node, apply), el('div', { class: 'actions' }, pause, stop),
      el('details', {}, el('summary', {}, 'Payload 교체 · 다음 메시지부터 적용'), help('FILE_DATA 소스도 단일 Payload 반복으로 교체됩니다. walk 상태가 초기화됩니다.'), editor.node, tokenHelp(editor), replace), localErrors.node, lastError);
    let current = stream;
    return { node, update(value) {
      current = value; state.replaceChildren(badge(value.state));
      stats.replaceChildren(metrics({ sent: value.sent, failed: value.failed, actualTps: Number(value.actualTps).toFixed(2), lagResets: value.lagResets, skipped: value.skippedWhileDisconnected }));
      if (!dirty && document.activeElement !== rate.input && document.activeElement !== slider) { rate.value = value.rateTps; slider.value = value.rateTps; }
      pause.textContent = value.state === 'PAUSED' ? '재개' : '일시정지';
      for (const control of [rate.input, slider, apply, pause, stop, replace]) control.disabled = !active(value.state);
      lastError.hidden = !value.lastError; lastError.textContent = value.lastError || '';
    } };
  }
  let refreshing = false;
  async function refresh() {
    if (refreshing) return; refreshing = true;
    try {
      const values = await scope.request('/dt/streams'); if (!scope.alive) return; pollErrors.clear();
      const ids = new Set(values.map(x => x.streamId));
      for (const [id, card] of cards) if (!ids.has(id)) { card.node.remove(); cards.delete(id); }
      list.querySelector('.empty')?.remove();
      if (!values.length) list.append(empty('실행 중인 스트림이 없습니다. 왼쪽에서 첫 스트림을 시작하세요.'));
      for (const value of values) { if (!cards.has(value.streamId)) { const card = createCard(value); cards.set(value.streamId, card); list.append(card.node); } cards.get(value.streamId).update(value); }
    } catch (error) { pollErrors.show(error); } finally { refreshing = false; }
  }
  scope.poll(refresh, 1000);
}
