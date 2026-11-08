import { el, page, panel, help, empty, button, errorBox, resultBox, actionButton, badge } from '../components/ui.js';
import { messageForm } from '../components/message-form.js';
import { loadHistory, saveHistory, clearHistory } from '../components/history.js';

export default function publish(root, scope) {
  page(root, '단발 전송', 'Payload를 확인하고 원하는 횟수만큼 Topic에 전송하세요.');
  const form = messageForm(), errors = errorBox(), result = resultBox();
  const history = el('div');
  const summary = el('div');
  const submit = preview => actionButton(preview ? '미리보기' : '메시지 보내기 ↗', errors, async () => {
    const body = form.read();
    if (preview && body.count > 10) throw new Error('미리보기 count는 1~10입니다. 전송 수를 10 이하로 줄이세요.');
    const response = await scope.request(preview ? '/dt/publish/preview' : '/dt/publish', { method: 'POST', body });
    if (!scope.alive) return;
    result.show(response);
    summary.replaceChildren(badge(response.failed ? 'FAILED' : 'COMPLETED'), help(preview ? `${response.count}건 생성 미리보기 · 브로커 연결 없음` : `${response.sent}건 전송 · 실패 ${response.failed}건 · ${response.elapsedMillis}ms${response.stoppedReason ? ` · ${response.stoppedReason} (HTTP 207, 일부 전송)` : ''}`));
    saveHistory(body); renderHistory();
  }, preview ? '' : 'primary');
  const formPanel = panel('메시지 작성', form.node, errors.node, el('div', { class: 'actions' }, submit(true), submit(false)));
  const resultPanel = panel('생성 · 전송 결과', summary, help('미리보기는 실제 전송하지 않습니다. 전송 결과의 firstMessage에서 최종 envelope를 확인할 수 있습니다.'), result.node);
  root.append(el('div', { class: 'grid' }, formPanel, el('div', { class: 'stack' }, resultPanel, panel('최근 요청 · 최대 10개', help('이 브라우저에 저장됩니다. 브로커 연결 정보와 민감한 이름의 필드는 제외합니다. 복원 후 해당 값을 다시 입력하세요.'), history, button('기록 삭제', () => { clearHistory(); renderHistory(); }, 'small')))));
  function renderHistory() {
    const entries = loadHistory(); history.replaceChildren();
    if (!entries.length) history.append(empty('미리보기나 전송을 실행하면 요청이 기록됩니다.'));
    for (const entry of entries) history.append(button(`${entry.request?.topic || '기본 Topic'} · ${entry.request?.count || 1}건 · ${new Date(entry.at).toLocaleString('ko-KR')}`, () => { form.load(entry.request || {}); }, 'message-row'));
  }
  renderHistory();
}
