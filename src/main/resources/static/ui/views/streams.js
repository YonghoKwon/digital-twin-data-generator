import { active, segment } from '../api.js';
import { el, page, panel, badge, help, empty, field, metrics, numeric, errorBox, actionButton, jsonEditor, tokenHelp, button, table, primaryAction, confirmAction, toast } from '../components/ui.js';
import { messageForm } from '../components/message-form.js';
import { readState, writeState, SparkBuffer } from '../components/state.js';
import { rememberMessage } from '../components/plant.js';
import { drawSparkline } from '../components/plots.js';

export default function streams(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), form = messageForm({ stream: true }), cards = new Map(), list = el('div');
  page(root, '스트림', '지속 전송을 만들고 실행 중에도 속도·Payload를 조정하세요.', actionButton('전체 스트림 중지', errors, async () => {
    if (!await confirmAction('모든 스트림', '전체 스트림 중지')) return;
    await scope.request('/dt/streams', { method: 'DELETE' }); await refresh();
  }, 'danger'));
  const create = actionButton('스트림 시작', errors, async () => {
    const body = form.read();
    const value = await scope.request('/dt/streams', { method: 'POST', body });
    writeState(`stream-config.${value.streamId}`, body); writeState('selected-stream', value.streamId); rememberMessage(body);
    toast(`스트림을 시작했습니다. ${value.streamId}`); await refresh();
  }, 'primary');
  const formPanel = panel('새 스트림', form.node, create);
  primaryAction(formPanel, create);
  let mode = readState('stream-view', 'face'), values = [];
  const tableList = el('div');
  const toggle = button(mode === 'face' ? '표 보기' : '면 보기', () => { mode = mode === 'face' ? 'table' : 'face'; writeState('stream-view', mode); renderTable(); });
  const monitor = panel('스트림 모니터', toggle, tableList, list);
  root.append(errors.node, pollErrors.node, el('div', { class: 'grid' }, formPanel, monitor));
  function duplicate(value) {
    const config = readState(`stream-config.${value.streamId}`);
    if (!config) throw new Error('이 브라우저에 원본 설정이 없습니다. Topic과 속도를 참고해 새 스트림을 작성하세요.');
    form.load({ ...config, streamId: `${value.streamId}-copy`, rateTps: value.rateTps });
    formPanel.scrollIntoView({ block: 'start' }); form.node.querySelector('input').focus();
    toast(`스트림을 복제했습니다. ${value.streamId}-copy 설정을 확인하고 시작하세요.`);
  }
  function renderTable() {
    list.hidden = mode !== 'face'; tableList.hidden = mode !== 'table'; toggle.textContent = mode === 'face' ? '표 보기' : '면 보기';
    tableList.replaceChildren(values.length ? table(['이름', '상태', '실제 TPS', '전송', '실패', ''], values.map(value => [
      button(value.streamId, () => { writeState('selected-stream', value.streamId); mode = 'face'; renderTable(); cards.get(value.streamId)?.node.scrollIntoView({ block: 'nearest' }); }), badge(value.state), Number(value.actualTps).toFixed(2), value.sent, value.failed,
      actionButton('복제', errors, async () => duplicate(value), 'small')
    ])) : empty('실행한 스트림이 없습니다.', '새 스트림', () => form.node.querySelector('input').focus()));
  }
  function createCard(stream) {
    const state = el('span'), stats = el('div'), lastError = el('div', { class: 'error', hidden: true });
    const actual = el('div', { class: 'number' });
    const spark = el('canvas', { class: 'sparkline', role: 'img', 'aria-label': '최근 60초 TPS' }), samples = new SparkBuffer();
    const localErrors = errorBox();
    const rate = field('rateTps', stream.rateTps, { type: 'number', min: 0.001, max: 5000, step: 'any' });
    const slider = el('input', { type: 'range', min: 0.1, max: 5000, step: 0.1, value: stream.rateTps, 'aria-label': `${stream.streamId} 전송 속도` });
    let dirty = false;
    const url = `/dt/streams/${segment(stream.streamId)}`;
    const patch = async body => {
      await scope.request(url, { method: 'PATCH', body });
      const messages = { paused: body.paused ? '스트림을 일시정지했습니다.' : '스트림을 재개했습니다.', payload: 'Payload를 교체했습니다.', rateTps: '속도를 적용했습니다.' };
      toast(`${messages[Object.keys(body)[0]]} ${stream.streamId}`);
      await refresh();
    };
    const apply = actionButton('속도 적용', localErrors, async () => { await patch({ rateTps: numeric(rate, 'rateTps', Number.MIN_VALUE, 5000) }); dirty = false; }, 'small');
    rate.input.addEventListener('input', () => { dirty = true; slider.value = rate.value; });
    rate.input.addEventListener('change', () => apply.click());
    slider.addEventListener('input', () => { dirty = true; rate.value = slider.value; });
    slider.addEventListener('change', () => apply.click());
    const pause = actionButton('일시정지', localErrors, () => patch({ paused: current.state !== 'PAUSED' }), 'small');
    const stop = actionButton('중지', localErrors, async () => { if (!await confirmAction(stream.streamId, '스트림 중지')) return; await scope.request(url, { method: 'DELETE' }); toast(`스트림을 중지했습니다. ${stream.streamId}`); await refresh(); }, 'small danger');
    const editor = jsonEditor('교체할 Payload', { temperature: '{{walk:20:10..30:0.5}}' }, 6);
    const replace = actionButton('Payload 교체', localErrors, async () => {
      const payload = editor.get(); await patch({ payload });
      const config = readState(`stream-config.${stream.streamId}`);
      if (config) { delete config.source; writeState(`stream-config.${stream.streamId}`, { ...config, payload }); }
    }, 'small');
    const speedButtons = [.5, 2].map(factor => actionButton(factor === .5 ? '×½' : '×2', localErrors, async () => {
      rate.value = Math.max(.001, Math.min(5000, current.rateTps * factor));
      await patch({ rateTps: Number(rate.value) });
    }, 'small'));
    const node = el('article', { class: 'faceplate' }, el('div', { class: 'card-head' }, el('div', {}, button(stream.streamId, () => { writeState('selected-stream', stream.streamId); for (const [id, item] of cards) item.node.classList.toggle('selected', id === stream.streamId); }, 'tag-name'), help(`${stream.topic}   ${stream.messageId || 'RAW'}`)), state), actual, spark, stats,
      el('div', { class: 'row' }, el('div', { class: 'grow' }, slider), rate.node, apply, speedButtons), el('div', { class: 'actions' }, pause, stop, actionButton('복제', localErrors, async () => duplicate(current), 'small')),
      el('details', {}, el('summary', {}, 'Payload 교체   다음 메시지부터 적용'), help('FILE_DATA 소스도 단일 Payload 반복으로 교체됩니다. walk 상태가 초기화됩니다.'), editor.node, tokenHelp(editor), replace), localErrors.node, lastError);
    let current = stream;
    primaryAction(node, apply); primaryAction(editor.node.parentElement, replace);
    return { node, update(value) {
      current = value; state.replaceChildren(badge(value.state));
      node.classList.toggle('selected', readState('selected-stream') === value.streamId);
      node.classList.toggle('alarm-row', value.state === 'FAILED'); node.classList.toggle('warn-row', value.state === 'RECONNECTING');
      actual.textContent = `${Number(value.actualTps).toFixed(2)} TPS`; samples.add(Number(value.actualTps)); if (!list.hidden) drawSparkline(spark, samples);
      stats.replaceChildren(metrics({ sent: value.sent, failed: value.failed, actualTps: Number(value.actualTps).toFixed(2), lagResets: value.lagResets, skipped: value.skippedWhileDisconnected }));
      if (!dirty && document.activeElement !== rate.input && document.activeElement !== slider) { rate.value = value.rateTps; slider.value = value.rateTps; }
      pause.textContent = value.state === 'PAUSED' ? '재개' : '일시정지';
      for (const control of [rate.input, slider, apply, pause, stop, replace, ...speedButtons]) control.disabled = !active(value.state);
      lastError.hidden = !value.lastError; lastError.textContent = value.lastError || '';
    } };
  }
  let refreshing = false;
  async function refresh() {
    if (refreshing) return; refreshing = true;
    try {
      values = await scope.request('/dt/streams'); if (!scope.alive) return; pollErrors.clear();
      const ids = new Set(values.map(x => x.streamId));
      for (const [id, card] of cards) if (!ids.has(id)) { card.node.remove(); cards.delete(id); }
      list.querySelector('.empty')?.remove();
      if (!values.length) list.append(empty('실행한 스트림이 없습니다.', '새 스트림', () => form.node.querySelector('input').focus()));
      for (const value of values) { if (!cards.has(value.streamId)) { const card = createCard(value); cards.set(value.streamId, card); list.append(card.node); } cards.get(value.streamId).update(value); }
      renderTable();
    } catch (error) { pollErrors.show(error); } finally { refreshing = false; }
  }
  scope.poll(refresh, 1000);
}
