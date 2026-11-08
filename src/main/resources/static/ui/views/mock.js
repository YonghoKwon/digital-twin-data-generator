import { segment } from '../api.js';
import { el, time, page, panel, help, empty, badge, button, check, field, table, jsonEditor, errorBox, resultBox, actionButton, confirmAction, primaryAction, toast, code } from '../components/ui.js';

const example = { id: 'sensor-status', method: 'GET', path: '/sensors/{id}', status: 200, headers: { 'Content-Type': 'application/json' }, body: '{"status":"OK"}', enabled: true };

export default function mock(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), routes = el('div'), status = el('div'), journal = el('div');
  const editor = jsonEditor('Route JSON', example, 14), editMode = el('span', { class: 'help' }, '새 route');
  const imported = resultBox(), importEditor = jsonEditor('DTCore DataTable JSON 배열', [], 5), overwrite = check('같은 ID 덮어쓰기');
  const journalDetail = resultBox(), auto = check('호출 기록 자동 새로고침', true);
  const variables = el('div', { class: 'form-grid' }), tryResult = el('div'), requestBody = jsonEditor('호출 요청 본문 JSON', {}, 4);
  let selected = null, pathFields = [], pattern = '';
  const controller = new AbortController(); scope.add(() => controller.abort());
  page(root, 'REST Mock', '', actionButton('파일에서 reload', errors, async () => { await scope.request('/mock-admin/routes/reload', { method: 'POST' }); await refresh(); toast('파일에서 reload했습니다.'); }));
  function prepareTry() {
    let route;
    try { route = JSON.parse(editor.textarea.value); } catch { return; }
    const nextPattern = route.path || '';
    if (nextPattern === pattern) return;
    pattern = nextPattern;
    pathFields = [...new Set([...pattern.matchAll(/\{([^}]+)\}|\*\*?/g)].map(match => match[1] || match[0]))].map(name => ({ name, input: field(`경로 변수 ${name}`, '') }));
    variables.replaceChildren(...pathFields.map(x => x.input.node));
  }
  editor.textarea.addEventListener('input', prepareTry); prepareTry();
  const save = actionButton('Route 저장', errors, async () => {
    const body = editor.get();
    await scope.request(selected ? `/mock-admin/routes/${segment(selected)}` : '/mock-admin/routes', { method: selected ? 'PUT' : 'POST', body });
    selected = body.id; editMode.textContent = `수정 ${selected}`; await refresh(); toast(`Route를 저장했습니다. ${selected}`);
  }, 'primary');
  const fresh = button('새 route', () => { selected = null; editMode.textContent = '새 route'; editor.set(example); editor.textarea.focus(); });
  const remove = actionButton('Route 삭제', errors, async () => {
    if (!selected) throw new Error('삭제할 route를 목록에서 선택하세요.');
    if (!await confirmAction(selected, 'Route 삭제')) return;
    const id = selected;
    await scope.request(`/mock-admin/routes/${segment(id)}`, { method: 'DELETE' }); selected = null; editMode.textContent = '새 route'; editor.set(example); await refresh(); toast(`Route를 삭제했습니다. ${id}`);
  }, 'danger');
  const tryCall = actionButton('호출해 보기', errors, async () => {
    const route = editor.get(); let path = route.path;
    for (const { name, input } of pathFields) {
      if (!input.value.trim()) throw new Error(`경로 변수 ${name} 값을 입력하세요.`);
      path = path.replaceAll(name.startsWith('*') ? name : `{${name}}`, encodeURIComponent(input.value));
    }
    if (!path.startsWith('/') || path.includes('..') || path.includes('\\')) throw new Error('호출 경로는 /로 시작하는 Mock 경로를 입력하세요.');
    const method = route.method === 'ANY' || route.method === '*' ? 'GET' : route.method;
    const start = performance.now();
    const response = await fetch(`/mock${path}`, { method, signal: controller.signal, cache: 'no-store', redirect: 'error',
      ...(!['GET', 'HEAD'].includes(method) ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(requestBody.get()) } : {}) });
    const raw = await response.text(); let body = raw; try { body = JSON.parse(raw); } catch { /* Plain response body. */ }
    if (!scope.alive) return;
    tryResult.replaceChildren(el('div', { class: response.status >= 500 ? 'error' : response.status >= 400 ? 'notice' : '' }, `${method} /mock${path}   HTTP ${response.status}   ${Math.round(performance.now() - start)} ms`), code(body));
    await refresh();
  });
  const editPanel = panel('Route 편집', editMode, editor.node, el('div', { class: 'actions' }, fresh, save, remove), help('저장은 메모리에 적용됩니다. reload하면 서버 파일 내용으로 대체됩니다.'));
  primaryAction(editPanel, save);
  const tryPanel = panel('호출해 보기', variables, requestBody.node, tryCall, tryResult);
  primaryAction(tryPanel, tryCall);
  const importButton = actionButton('가져오기', errors, async () => { imported.show(await scope.request(`/mock-admin/import/dtcore-api-table?overwrite=${overwrite.value}`, { method: 'POST', body: importEditor.get() })); await refresh(); }, 'primary');
  const importPanel = panel('DTCore DataTable 가져오기', importEditor.node, overwrite.node, importButton, imported.node);
  primaryAction(importPanel, importButton);
  root.append(errors.node, pollErrors.node, status, el('div', { class: 'grid' },
    panel('Route 목록', routes), editPanel,
    tryPanel,
    panel('호출 기록', auto.node, el('div', { class: 'actions' }, actionButton('새로고침', errors, refresh), actionButton('기록 비우기', errors, async () => { if (!await confirmAction('Mock 호출 기록', '기록 비우기')) return; await scope.request('/mock-admin/requests', { method: 'DELETE' }); journalDetail.clear(); await refresh(); }, 'small')), journal, journalDetail.node)),
    importPanel);
  async function selectRoute(route) {
    const value = await scope.request(`/mock-admin/routes/${segment(route.id)}`);
    if (!scope.alive) return; selected = route.id; editMode.textContent = `수정 ${route.id}`; editor.set(value); editor.textarea.focus();
  }
  async function refresh() {
    const results = await Promise.allSettled(['/mock-admin/routes', '/mock-admin/status', '/mock-admin/requests?limit=100'].map(path => scope.request(path)));
    if (!scope.alive) return;
    pollErrors.clear();
    for (const result of results) if (result.status === 'rejected') pollErrors.show(result.reason);
    if (results[0].status === 'fulfilled') {
      const values = results[0].value;
      routes.replaceChildren(values.length ? table(['메서드', '경로', '응답', '사용'], values.map(route => [
        route.method, actionButton(route.path, errors, () => selectRoute(route), 'small'), route.status ?? 200,
        actionButton(route.enabled === false ? '사용 안 함' : '사용 중', errors, async () => { await scope.request(`/mock-admin/routes/${segment(route.id)}/enabled`, { method: 'PATCH', body: { enabled: route.enabled === false } }); await refresh(); }, 'small')
      ]), index => void selectRoute(values[index]).catch(error => errors.show(error))) : empty('등록된 route가 없습니다.', '새 route', () => fresh.click()));
    }
    if (results[1].status === 'fulfilled') {
      const value = results[1].value;
      status.replaceChildren(el('div', { class: 'panel-head' }, help(`Route ${value.routeCount}개   마지막 reload ${time(value.lastReloadAt)}`), badge(value.lastReloadSuccessful ? 'LOADED' : 'FAILED')));
      if (value.lastReloadError) status.append(el('div', { class: 'error' }, value.lastReloadError));
    }
    if (results[2].status === 'fulfilled') {
      const values = results[2].value;
      journal.replaceChildren(values.length ? table(['시각', '메서드', '경로', '응답', '지연', ''], values.map(value => [time(value.timestamp), value.method, value.path, value.status, `${value.elapsedMillis} ms`, button('상세', () => journalDetail.show(value), 'small')])) : empty('Mock API를 호출하면 기록이 표시됩니다.', '호출해 보기', () => tryCall.click()));
      const rows = journal.querySelectorAll('tbody tr');
      values.forEach((value, index) => { if (value.status >= 400 && rows[index]) rows[index].className = value.status >= 500 ? 'alarm-row' : 'warn-row'; });
    }
  }
  scope.poll(async () => { if (auto.value) await refresh(); }, 3000);
}
