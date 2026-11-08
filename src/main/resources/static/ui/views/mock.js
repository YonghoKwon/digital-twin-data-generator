import { segment } from '../api.js';
import { el, page, panel, help, empty, badge, button, check, table, jsonEditor, errorBox, resultBox, actionButton } from '../components/ui.js';

const example = { id: 'sensor-status', method: 'GET', path: '/sensors/{id}', status: 200, headers: { 'Content-Type': 'application/json' }, body: '{"status":"OK"}', enabled: true };

export default function mock(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), routes = el('div'), status = el('div'), journal = el('div');
  const editor = jsonEditor('Route JSON', example, 14), editMode = el('span', { class: 'help' }, '새 route');
  const imported = resultBox(), importEditor = jsonEditor('DTCore DataTable · JSON 배열', [], 5), overwrite = check('같은 ID 덮어쓰기');
  const journalDetail = resultBox();
  let selected = null;
  page(root, 'REST Mock', 'UE가 호출할 API 응답을 정의하고 실제 호출 기록을 확인하세요.', actionButton('파일에서 reload', errors, async () => { await scope.request('/mock-admin/routes/reload', { method: 'POST' }); await refresh(); }));
  const save = actionButton('Route 저장', errors, async () => {
    const body = editor.get();
    await scope.request(selected ? `/mock-admin/routes/${segment(selected)}` : '/mock-admin/routes', { method: selected ? 'PUT' : 'POST', body }); await refresh();
  }, 'primary');
  const fresh = button('새로 작성', () => { selected = null; editMode.textContent = '새 route'; editor.set(example); });
  const remove = actionButton('선택 route 삭제', errors, async () => {
    if (!selected) throw new Error('삭제할 route를 목록에서 선택하세요.');
    await scope.request(`/mock-admin/routes/${segment(selected)}`, { method: 'DELETE' }); selected = null; editMode.textContent = '새 route'; editor.set(example); await refresh();
  }, 'danger');
  root.append(errors.node, pollErrors.node, status, el('div', { class: 'grid' },
    panel('Route 목록', help('서빙 경로는 /mock + path입니다. seq 실행 순번은 현재 조회 API에서 제공하지 않습니다.'), routes),
    panel('Route 편집', editMode, editor.node, el('div', { class: 'actions' }, fresh, save, remove), help('저장은 메모리에 적용됩니다. reload하면 서버 mock-routes 파일 내용으로 대체됩니다.')),
    panel('DTCore DataTable 가져오기', importEditor.node, overwrite.node, actionButton('가져오기', errors, async () => { imported.show(await scope.request(`/mock-admin/import/dtcore-api-table?overwrite=${overwrite.value}`, { method: 'POST', body: importEditor.get() })); await refresh(); }, 'primary'), imported.node),
    panel('호출 기록 · 3초 갱신', actionButton('기록 비우기', errors, async () => { await scope.request('/mock-admin/requests', { method: 'DELETE' }); journalDetail.clear(); await refresh(); }, 'small'), journal, journalDetail.node)));
  async function refresh() {
    const results = await Promise.allSettled(['/mock-admin/routes', '/mock-admin/status', '/mock-admin/requests?limit=100'].map(path => scope.request(path)));
    if (!scope.alive) return;
    pollErrors.clear();
    for (const result of results) if (result.status === 'rejected') pollErrors.show(result.reason);
    if (results[0].status === 'fulfilled') {
      const values = results[0].value;
      routes.replaceChildren(values.length ? table(['Method / Path', '상태', 'Enabled', 'seq', ''], values.map(route => [
        el('div', {}, el('strong', {}, route.method), el('div', { class: 'mono' }, route.path), help(route.id)), route.status ?? 200,
        actionButton(route.enabled === false ? 'OFF → ON' : 'ON → OFF', errors, async () => { await scope.request(`/mock-admin/routes/${segment(route.id)}/enabled`, { method: 'PATCH', body: { enabled: route.enabled === false } }); await refresh(); }, 'small'),
        '미제공', actionButton('편집', errors, async () => { const value = await scope.request(`/mock-admin/routes/${segment(route.id)}`); if (!scope.alive) return; selected = route.id; editMode.textContent = `수정: ${route.id}`; editor.set(value); editor.textarea.focus(); }, 'small')
      ])) : empty('등록된 route가 없습니다.'));
    }
    if (results[1].status === 'fulfilled') {
      const value = results[1].value;
      status.replaceChildren(el('div', { class: 'panel-head' }, help(`총 ${value.routeCount}개 route · 마지막 reload ${value.lastReloadAt || '없음'}`), badge(value.lastReloadSuccessful ? 'RUNNING' : 'FAILED')), value.lastReloadError ? el('div', { class: 'error' }, value.lastReloadError) : null);
    }
    if (results[2].status === 'fulfilled') {
      const values = results[2].value;
      journal.replaceChildren(values.length ? table(['시간 / 요청', '응답', ''], values.map(value => [el('div', {}, `${value.method} ${value.path}`, help(value.timestamp)), `${value.status} · ${value.elapsedMillis}ms`, button('상세', () => journalDetail.show(value), 'small')])) : empty('아직 Mock API 호출 기록이 없습니다.'));
    }
  }
  scope.poll(refresh, 3000);
}
