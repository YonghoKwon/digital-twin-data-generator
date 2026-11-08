import { active } from '../api.js';
import { time, el, page, panel, badge, help, empty, code, errorBox, actionButton, toast } from '../components/ui.js';

export default function dashboard(root, scope) {
  const updated = el('span', { class: 'help' }, '상태를 확인하고 있습니다…');
  page(root, '대시보드', '전송부터 수신까지, 테스트 환경의 현재 상태를 확인하세요.', updated);
  const broker = el('div'), counts = el('div', { class: 'stats' });
  const lists = el('div', { class: 'grid' });
  const errors = errorBox();
  root.append(errors.node, panel('브로커 연결', broker), counts, lists);
  const endpoints = ['/dt/broker', '/dt/streams', '/dt/subscriptions', '/dt/scenario-runs', '/activemq/task/running-tasks', '/mock-admin/status'];
  scope.poll(async () => {
    const responses = await Promise.allSettled(endpoints.map(path => scope.request(path)));
    if (!scope.alive) return;
    errors.clear(); const problems = [];
    const values = responses.map((result, index) => {
      if (result.status === 'fulfilled') return result.value;
      if (index === 3 && result.reason.status === 404) return null;
      if (result.reason.name !== 'AbortError') problems.push(`${endpoints[index]}\n${result.reason.message}`);
      return undefined;
    });
    for (const response of responses) {
      if (response.status === 'rejected' && !(response.reason.status === 404 && responses.indexOf(response) === 3)) { errors.show(response.reason); break; }
    }
    const [b, streams, subscriptions, runs, tasks, mock] = values;
    broker.replaceChildren();
    if (b) {
      const copyErrors = errorBox();
      const copy = actionButton('WebSocketUrl 복사', copyErrors, async () => {
        try { await navigator.clipboard.writeText(b.websocketUrl); toast('WebSocketUrl을 복사했습니다.'); }
        catch { throw new Error(`클립보드를 사용할 수 없습니다. 주소를 직접 복사하세요: ${b.websocketUrl}`); }
      }, 'small');
      broker.append(el('div', { class: 'panel-head' }, el('div', { class: 'actions' }, badge(b.running ? 'RUNNING' : 'STOPPED'), el('strong', {}, `Artemis ${b.version}`), el('span', { class: 'help' }, `기본 대상: ${b.defaultTarget}`)), copy),
        el('div', { class: 'grid' }, el('div', {}, el('div', { class: 'help' }, 'STOMP WebSocket'), code(b.websocketUrl), help(`Core: ${b.coreUrl}`)), el('div', {}, el('div', { class: 'help' }, 'UE DTCoreSettings'), code(b.dtcoreSettingsHint))),
        b.running ? help('다른 PC의 UE에서는 localhost 대신 이 서버 PC의 IP를 사용하세요. 방화벽에서 STOMP 포트를 허용해야 합니다.') : el('div', { class: 'notice' }, '내장 브로커가 중지되어 있습니다. 기본 대상이 EXTERNAL이면 외부 브로커 설정을 사용합니다.'), copyErrors.node);
      if (b.lastError) broker.append(el('div', { class: 'error' }, b.lastError));
    } else broker.append(empty('브로커 상태를 읽지 못했습니다.'));
    counts.replaceChildren(); lists.replaceChildren();
    const items = [
      ['실행 중 스트림', streams?.filter(x => active(x.state)), 'streams', x => [x.streamId, x.state, `${x.topic} · ${x.sent}건`]],
      ['구독', subscriptions, 'subscriptions', x => [x.subscriptionId, x.status, `${x.topic} · ${x.receivedCount}건`]],
      ['실행 중 시나리오', runs?.filter(x => active(x.state)), 'scenarios', x => [x.runId, x.state, `${Math.round((x.progress || 0) * 100)}% · ${x.sent}건`]],
      ['Legacy task', tasks, 'legacy', x => [x.taskId, 'RUNNING', '취소 가능']]
    ];
    for (const [title, list, route, summarize] of items) {
      counts.append(el('div', { class: 'stat' }, el('span', { class: 'muted' }, title), el('strong', {}, list?.length ?? '—'), el('a', { href: `#/${route}` }, '관리 화면 열기 →')));
      const content = el('div');
      if (!list?.length) content.append(empty(list ? '현재 실행 중인 항목이 없습니다.' : route === 'scenarios' && runs === null ? '시나리오 기능이 아직 없는 서버' : '상태를 읽지 못했습니다.'));
      else for (const item of list.slice(0, 5)) { const [id, state, detail] = summarize(item); content.append(el('div', { class: 'list-summary' }, el('div', {}, el('strong', {}, id), el('div', { class: 'help' }, detail)), badge(state))); }
      if (list?.length > 5) content.append(help(`외 ${list.length - 5}개 · 관리 화면에서 전체 확인`));
      lists.append(panel(title, content));
    }
    const mockStatus = mock ? el('div', {}, el('div', { class: 'actions' }, badge(mock.lastReloadSuccessful ? 'LOADED' : 'FAILED'), `${mock.routeCount}개 route`), help(`마지막 로드: ${time(mock.lastReloadAt)}`), mock.lastReloadError ? el('div', { class: 'error' }, mock.lastReloadError) : help('마지막 로드 오류 없음'), el('a', { href: '#/mock' }, 'REST Mock 관리 →')) : empty('REST Mock 상태를 읽지 못했습니다.');
    lists.append(panel('REST Mock', mockStatus));
    updated.textContent = `${new Date().toLocaleTimeString('ko-KR')} 갱신 · 3초 주기${problems.length ? ' · 일부 조회 실패' : ''}`;
  }, 3000);
}
