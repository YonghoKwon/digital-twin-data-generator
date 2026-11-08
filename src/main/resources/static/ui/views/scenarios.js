import { active, segment } from '../api.js';
import { el, page, panel, help, empty, badge, button, field, select, check, numeric, metrics, table, code, jsonEditor, tokenHelp, errorBox, resultBox, actionButton, confirmAction, primaryAction, toast } from '../components/ui.js';
import { readState, writeState } from '../components/state.js';
import { drawTimeline } from '../components/plots.js';
import { plant } from '../components/plant.js';

const sample = {
  scenarioId: 'sensor-demo', name: '센서 상태 데모', description: '온도 변화와 상태 전환', speed: 1, loop: false, onDisconnect: 'SKIP',
  defaults: { topic: 'dt.sensor.temp', messageId: 'SENSOR', envelope: 'DTCORE', standardHeaders: false, headers: {} },
  events: [
    { at: 0, type: 'PUBLISH', payload: { temperature: '{{walk:20:10..30:0.5}}', seq: '{{seq}}' }, repeat: { count: 10, everyMs: 1000 } },
    { at: 5000, type: 'MARK', label: '상태 전환 구간' },
    { at: 10000, type: 'PUBLISH', payload: { status: 'COMPLETE' } }
  ]
};

export default function scenarios(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), library = el('div'), runs = el('div'), cards = new Map();
  const missing = el('div', { class: 'notice', hidden: true }, '시나리오 기능이 아직 없는 서버입니다. S5 백엔드 적용 후 다시 확인하세요. 다른 메뉴는 계속 사용할 수 있습니다.');
  const file = field('라이브러리 파일 이름', 'sensor-demo.json');
  const editor = jsonEditor('시나리오 정의   JSON', sample, 17), validation = resultBox();
  const speed = field('실행 배속 speed', 1, { type: 'number', min: 0.1, max: 100, step: 0.1 });
  const runId = field('Run ID   선택', ''), startAt = field('시작 위치 startAtMs', 0, { type: 'number', min: 0 });
  const loop = check('반복 재생'), paused = check('일시정지 상태로 실행');
  const mode = select('실행 소스', [['FILE', '라이브러리 파일'], ['INLINE', '현재 JSON 편집기']], 'FILE');
  const events = el('div', {}, empty('run에서 이벤트 보기를 선택하세요.'));
  let available = true, selectedRun = null;
  const definitions = new Map(), runLogs = new Map();
  const requireApi = async (path, options) => {
    try { const value = await scope.request(path, options); return value; }
    catch (error) { if (error.status === 404) { await probe(); } throw error; }
  };
  page(root, '시나리오', '이벤트 타임라인을 저장하고 배속·탐색으로 반복 테스트하세요.', actionButton('서버 기능 다시 확인', errors, async () => { await probe(); if (available) { await refreshLibrary(); await refreshRuns(); } }));
  const validate = actionButton('검증', errors, async () => {
    const value = await requireApi('/dt/scenarios/validate', { method: 'POST', body: editor.get() });
    validation.node.replaceChildren(badge(value.valid ? 'VALID' : 'INVALID'), help(`이벤트 ${value.eventCount}건   길이 ${value.durationMs} ms`));
    for (const message of value.errors || []) {
      const key = message.match(/(?:events\[(\d+)\]|events\.(\d+))/);
      const lines = editor.textarea.value.split('\n');
      const starts = lines.map((line, index) => /"at"\s*:/.test(line) ? index : -1).filter(index => index >= 0);
      const line = key ? starts[Number(key[1] ?? key[2])] : lines.findIndex(text => text.includes(`"${message.split(':')[0]}"`));
      validation.node.append(button(`${line >= 0 ? `${line + 1}행 ` : ''}${message}`, () => {
        editor.textarea.focus(); if (line >= 0) { const offset = lines.slice(0, line).join('\n').length + (line ? 1 : 0); editor.textarea.setSelectionRange(offset, offset + lines[line].length); }
      }, 'validation-error'));
    }
  });
  const save = actionButton('파일 저장', errors, async () => { validation.show(await requireApi(`/dt/scenarios/${segment(file.value)}`, { method: 'PUT', body: editor.get() })); await refreshLibrary(); toast(`파일을 저장했습니다. ${file.value}`); }, 'primary');
  async function startRun(body) {
    const definition = body.scenario || await requireApi(`/dt/scenarios/${segment(body.file)}`);
    const run = await requireApi('/dt/scenario-runs', { method: 'POST', body });
    definitions.set(run.runId, definition); writeState(`run-definition.${run.runId}`, definition);
    toast(`시나리오를 실행했습니다. ${run.runId}`); await refreshRuns();
  }
  const start = actionButton('실행', errors, async () => {
    const body = { ...(runId.value.trim() ? { runId: runId.value } : {}), ...(mode.value === 'FILE' ? { file: file.value } : { scenario: editor.get() }), speed: numeric(speed, 'speed', 0.1, 100), loop: loop.value, startAtMs: numeric(startAt, 'startAtMs'), startPaused: paused.value };
    await startRun(body);
  }, 'primary');
  const stopAll = actionButton('모든 run 중지', errors, async () => { if (!await confirmAction('모든 시나리오 실행', '모든 run 중지')) return; await requireApi('/dt/scenario-runs', { method: 'DELETE' }); await refreshRuns(); }, 'danger small');
  const remove = actionButton('파일 삭제', errors, async () => { if (!await confirmAction(file.value, '시나리오 파일 삭제')) return; await requireApi(`/dt/scenarios/${segment(file.value)}`, { method: 'DELETE' }); await refreshLibrary(); toast(`파일을 삭제했습니다. ${file.value}`); }, 'danger');
  const controls = [validate, save, start, stopAll, remove];
  function availability(value) { available = value; missing.hidden = value; for (const control of controls) control.disabled = !value; }
  root.append(missing, errors.node, pollErrors.node, el('div', { class: 'grid' },
    panel('시나리오 라이브러리', actionButton('목록 새로고침', errors, refreshLibrary, 'small'), library),
    panel('시나리오 편집', file.node, editor.node, tokenHelp(editor), help('events: PUBLISH   MOCK_UPDATE   MOCK_ENABLE   MOCK_DISABLE   MARK\ntracks: CSV_TIMELINE   파일은 서버 base-dir 내부 경로를 사용합니다.'), el('div', { class: 'actions' }, button('새 예시', () => { file.value = 'sensor-demo.json'; editor.set(sample); }), validate, save, remove), validation.node),
    panel('실행 설정', mode.node, el('div', { class: 'form-grid' }, runId.node, speed.node, startAt.node), loop.node, paused.node, start),
    panel('최근 이벤트 로그', events)),
    el('div', { class: 'separator' }), el('div', { class: 'panel-head' }, el('h2', {}, 'Run 모니터   1초 갱신'), stopAll), runs);
  async function probe() {
    try { await scope.request('/dt/scenarios'); if (scope.alive) availability(true); }
    catch (error) { if (error.status === 404) { if (scope.alive) availability(false); } else throw error; }
  }
  async function refreshLibrary() {
    try {
      const values = await scope.request('/dt/scenarios'); if (!scope.alive) return; availability(true);
      library.replaceChildren(values.length ? table(['파일 / 이름', '길이', '유효성', ''], values.map(value => [
        el('div', {}, el('strong', {}, value.file), help(value.name || value.scenarioId), value.error ? el('div', { class: 'error' }, value.error) : null), `${value.durationMs ?? '—'} ms`, badge(value.valid ? 'VALID' : 'INVALID'),
        el('div', { class: 'actions' }, actionButton('열기', errors, async () => { const body = await requireApi(`/dt/scenarios/${segment(value.file)}`); if (!scope.alive) return; file.value = value.file; editor.set(body); }, 'small'), actionButton('실행', errors, () => startRun({ file: value.file }), 'small'))
      ])) : empty('저장된 시나리오가 없습니다. 예시 JSON을 검증하고 저장하세요.', '예시 검증', () => validate.click()));
    } catch (error) { if (error.status === 404) { availability(false); library.replaceChildren(empty('시나리오 기능이 아직 없는 서버')); } else errors.show(error); }
  }
  function runCard(value) {
    let current = value, dirty = false, seekDirty = false;
    const state = el('span'), stats = el('div'), byTopicContent = code({}), byTopic = el('details', {}, el('summary', {}, 'byTopic   토픽별 전송'), byTopicContent), mark = el('div'), lastError = el('div', { class: 'error', hidden: true });
    const localErrors = errorBox(), progressText = el('div', { class: 'help' });
    const speedInput = field('배속', value.speed, { type: 'number', min: 0.1, max: 100, step: 0.1 });
    speedInput.input.addEventListener('input', () => { dirty = true; });
    const url = `/dt/scenario-runs/${segment(value.runId)}`;
    const timeline = el('canvas', { class: 'timeline', role: 'img', tabindex: 0, 'aria-label': `${value.runId} 이벤트 타임라인. 재생 위치는 아래 슬라이더로 조작할 수 있습니다.` });
    const patch = async body => {
      await requireApi(url, { method: 'PATCH', body });
      const messages = { paused: body.paused ? '시나리오를 일시정지했습니다.' : '시나리오를 재개했습니다.', seekMs: '위치를 적용했습니다.', speed: '배속을 적용했습니다.' };
      toast(`${messages[Object.keys(body)[0]]} ${value.runId}`);
      await refreshRuns();
    };
    timeline.addEventListener('click', event => {
      if (!active(current.state)) return;
      const box = timeline.getBoundingClientRect(), left = Number(timeline.dataset.plotLeft || 150);
      const seekMs = Math.round(Math.max(0, Math.min(1, (event.clientX - box.left - left) / (box.width - left - 10))) * current.durationMs);
      void patch({ seekMs }).catch(error => localErrors.show(error));
    });
    const seek = el('input', { type: 'range', min: 0, max: Math.max(1, value.durationMs), step: 1, value: value.positionMs, 'aria-label': `${value.runId} 재생 위치 탐색` });
    seek.addEventListener('input', () => { seekDirty = true; progressText.textContent = `탐색 위치 ${seek.value} / ${current.durationMs}ms`; });
    const applySeek = actionButton('위치 적용', localErrors, async () => { await patch({ seekMs: Number(seek.value) }); seekDirty = false; }, 'small');
    seek.addEventListener('change', () => applySeek.click());
    const applySpeed = actionButton('배속 적용', localErrors, async () => { await patch({ speed: numeric(speedInput, 'speed', 0.1, 100) }); dirty = false; }, 'small');
    const pause = actionButton('일시정지', localErrors, () => patch({ paused: current.state !== 'PAUSED' }), 'small');
    const speedButtons = [.5, 1, 2, 5].map(speed => actionButton(`×${speed}`, localErrors, () => patch({ speed }), 'small'));
    const stop = actionButton('중지', localErrors, async () => { if (!await confirmAction(value.runId, '시나리오 중지')) return; await requireApi(url, { method: 'DELETE' }); await refreshRuns(); toast(`시나리오를 중지했습니다. ${value.runId}`); }, 'small danger');
    const node = el('article', { class: 'card' }, el('div', { class: 'card-head' }, el('div', {}, el('h3', {}, value.name || value.scenarioId || value.runId), help(value.runId)), state),
      progressText, timeline, seek, help('타임라인 클릭 또는 슬라이더 방향키로 탐색합니다. CSV 트랙은 구간과 처리된 이벤트를 표시합니다. 원본 정의가 없는 run은 처리 로그를 표시합니다. 반복 이벤트는 정의별 최대 2000개 눈금입니다.'), stats, byTopic, mark,
      el('div', { class: 'actions' }, speedInput.node, applySpeed, speedButtons, applySeek, pause, stop, button('이벤트 보기', () => { selectedRun = value.runId; void refreshEvents(); }, 'small')), localErrors.node, lastError);
    primaryAction(node, applySpeed);
    return { node, update(next) {
      current = next; state.replaceChildren(badge(next.state));
      node.classList.toggle('alarm-row', next.state === 'FAILED'); node.classList.toggle('warn-row', next.state === 'RECONNECTING');
      const definition = definitions.get(next.runId) || readState(`run-definition.${next.runId}`) || [...plant.definitions.values()].find(x => x.scenarioId === next.scenarioId) || {};
      drawTimeline(timeline, definition, next, runLogs.get(next.runId) || []);
      if (!seekDirty && document.activeElement !== seek) { seek.value = next.positionMs; progressText.textContent = `${(Math.min(1, Math.max(0, next.progress || 0)) * 100).toFixed(1)}%   ${next.positionMs} / ${next.durationMs}ms   반복 ${next.loopCount}`; }
      seek.max = Math.max(1, next.durationMs);
      if (!dirty && document.activeElement !== speedInput.input) speedInput.value = next.speed;
      stats.replaceChildren(metrics({ sent: next.sent, failed: next.failed, skipped: next.skipped, events: next.loopCount > 0 ? `${next.eventsDone} (누적)` : `${next.eventsDone}/${next.eventsTotal}`, mockOps: next.mockOps }));
      byTopicContent.textContent = JSON.stringify(next.byTopic || {}, null, 2);
      mark.textContent = `마지막 MARK: ${next.lastMark || '없음'}`; mark.className = 'help';
      lastError.hidden = !next.lastError; lastError.textContent = next.lastError || '';
      pause.textContent = next.state === 'PAUSED' ? '재개' : '일시정지';
      for (const control of [speedInput.input, applySpeed, applySeek, seek, pause, stop, ...speedButtons]) control.disabled = !active(next.state);
      if (!next.durationMs) { seek.disabled = true; applySeek.disabled = true; }
    } };
  }
  async function refreshEvents() {
    if (!selectedRun || !available) return;
    try {
      const id = selectedRun;
      const values = await scope.request(`/dt/scenario-runs/${segment(id)}/events?limit=50`);
      if (!scope.alive || id !== selectedRun) return;
      runLogs.set(id, values);
      events.replaceChildren(help(id), values.length ? table(['seq / atMs', '이벤트', '결과'], values.map(value => [`${value.seq} / ${value.atMs}`, `${value.type}   ${value.topic || value.messageId || ''}`, el('div', {}, value.result, value.error ? el('div', { class: 'error' }, value.error) : null)])) : empty('처리된 이벤트가 없습니다.'));
    } catch (error) { pollErrors.show(error); }
  }
  async function refreshRuns() {
    if (!available) return;
    try {
      const values = await scope.request('/dt/scenario-runs'); if (!scope.alive) return; pollErrors.clear();
      const ids = new Set(values.map(x => x.runId));
      for (const [id, card] of cards) if (!ids.has(id)) { card.node.remove(); cards.delete(id); }
      runs.querySelector('.empty')?.remove();
      if (!values.length) runs.append(empty('실행한 시나리오가 없습니다.', '시나리오 실행', () => start.focus()));
      for (const value of values) {
        if (!cards.has(value.runId)) { const card = runCard(value); cards.set(value.runId, card); runs.append(card.node); }
        cards.get(value.runId).update(value);
      }
      if (!selectedRun && values.length) selectedRun = values[0].runId;
      await refreshEvents();
    } catch (error) { if (error.status === 404) availability(false); else pollErrors.show(error); }
  }
  primaryAction(editor.node.parentElement, save);
  primaryAction(start.parentElement, start);
  void refreshLibrary(); scope.poll(refreshRuns, 1000);
}
