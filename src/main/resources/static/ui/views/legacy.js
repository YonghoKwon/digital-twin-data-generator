import { segment } from '../api.js';
import { brokerFields } from '../components/message-form.js';
import { el, page, panel, help, empty, button, field, select, check, numeric, table, jsonEditor, errorBox, resultBox, actionButton, confirmAction, primaryAction, toast } from '../components/ui.js';

const random = { format: [{ dataId: 'temperature,status', dataType: 'Double,String', randomBoolean: '1,1', randomCondition: '20..30,8' }], value: [] };

export default function legacy(root, scope) {
  const errors = errorBox(), pollErrors = errorBox(), tasks = el('div'), preview = resultBox(), sent = resultBox(), loadResult = resultBox();
  page(root, '기존 기능', '기존 format·파일 생성, task 제어와 JmsTemplate 부하 테스트를 실행하세요.', el('a', { href: '/swagger', target: '_blank', rel: 'noopener' }, 'Swagger 열기'));
  const mode = select('생성 방식', [['NORMAL', '일반 format / value'], ['FILE', '파일 반복'], ['FILE_DATA', '파일 + CSV 데이터']], 'NORMAL');
  const tc = field('tcName', 'UI-TEST'), count = field('messageCount', 1, { type: 'number', min: 0 });
  const repeat = check('repeatBoolean', true), repeatTime = field('repeatTime (ms)', 0, { type: 'number', min: 0 });
  const delay = field('delayTime (ms)', 0, { type: 'number', min: 0 }), tps = field('targetTps   0=제한 없음', 0, { type: 'number', min: 0, max: 1000000 });
  const concurrent = field('concurrentTasks', 1, { type: 'number', min: 1, max: 2000 }), typed = check('typedValues   숫자/불리언 타입 유지');
  const topic = field('Topic   비우면 서버 기본값', ''), taskId = field('전송 Task ID', 'ui-send');
  const filePath = field('서버 filePath', '.'), fileName = field('fileName', 'format.txt'), formatName = field('formatFileName', 'format.txt'), dataName = field('dataFileName', 'data.csv');
  const limit = field('미리보기 limit', 5, { type: 'number', min: 1, max: 100 });
  const format = jsonEditor('format / value   JSON', random, 8), broker = brokerFields();
  const formatNode = el('div', {}, format.node, typed.node);
  const files = el('div', { class: 'form-grid', hidden: true }, filePath.node, fileName.node, formatName.node, dataName.node);
  function switchMode() { formatNode.hidden = mode.value !== 'NORMAL'; files.hidden = mode.value === 'NORMAL'; fileName.node.hidden = mode.value !== 'FILE'; formatName.node.hidden = dataName.node.hidden = mode.value !== 'FILE_DATA'; }
  mode.input.addEventListener('change', switchMode); switchMode();
  function body() {
    const value = { tcName: tc.value, messageCount: numeric(count, 'messageCount'), repeatBoolean: repeat.value, repeatTime: numeric(repeatTime, 'repeatTime'), delayTime: numeric(delay, 'delayTime'), targetTps: numeric(tps, 'targetTps', 0, 1000000), concurrentTasks: numeric(concurrent, 'concurrentTasks', 1, 2000), ...(topic.value.trim() ? { topic: topic.value } : {}), ...broker.read() };
    if (mode.value === 'NORMAL') Object.assign(value, format.get(), { typedValues: typed.value });
    else Object.assign(value, { filePath: filePath.value }, mode.value === 'FILE' ? { fileName: fileName.value } : { formatFileName: formatName.value, dataFileName: dataName.value });
    return value;
  }
  const endpoint = () => '/activemq/request' + (mode.value === 'NORMAL' ? '' : mode.value === 'FILE' ? '/file' : '/file-data');
  const dryRun = actionButton('Dry-run 미리보기', errors, async () => { preview.show(await scope.request(`${endpoint()}/dry-run?limit=${numeric(limit, 'limit', 1, 100)}`, { method: 'POST', body: body() })); });
  const publish = actionButton('기존 API로 전송', errors, async () => { if (!taskId.value.trim()) throw new Error('Task ID를 입력하세요.'); sent.show(await scope.request(`${endpoint()}/${segment(taskId.value)}`, { method: 'POST', body: body() })); await refresh(); }, 'primary');
  const loadTc = field('tcName', 'UI-LOAD'), loadId = field('Task ID', 'ui-jms-load'), loadCount = field('messageCount', 100, { type: 'number', min: 1, max: 1000000 });
  const loadWorkers = field('workerCount', 1, { type: 'number', min: 1, max: 2000 }), loadTps = field('targetTps', 100, { type: 'number', min: 0, max: 1000000 });
  const loadDelay = field('delayTime (ms)', 0, { type: 'number', min: 0 }), loadTopic = field('Topic   선택', ''), loadBroker = brokerFields(), payload = jsonEditor('전송 Payload   JSON', { temperature: 25.1 }, 5);
  const runLoad = actionButton('부하 테스트 시작', errors, async () => {
    if (!loadId.value.trim()) throw new Error('부하 테스트 Task ID를 입력하세요.');
    const value = { tcName: loadTc.value, messageCount: numeric(loadCount, 'messageCount', 1, 1000000), workerCount: numeric(loadWorkers, 'workerCount', 1, 2000), targetTps: numeric(loadTps, 'targetTps', 0, 1000000), delayTime: numeric(loadDelay, 'delayTime'), payload: JSON.stringify(payload.get()), ...(loadTopic.value.trim() ? { topic: loadTopic.value } : {}), ...loadBroker.read() };
    loadResult.show('실행 중입니다. 실행 task 목록에서 취소할 수 있습니다.');
    try { loadResult.show(await scope.request(`/activemq/request/jms-template-load/${segment(loadId.value)}`, { method: 'POST', body: value })); await refresh(); }
    catch (error) { loadResult.clear(); throw error; }
  }, 'primary');
  root.append(errors.node, pollErrors.node, el('div', { class: 'grid' },
    panel('생성 미리보기   기존 전송', mode.node, el('div', { class: 'form-grid' }, tc.node, topic.node, count.node, repeatTime.node, delay.node, tps.node, concurrent.node, taskId.node), repeat.node, formatNode, files, help('파일 경로는 서버 base-dir 기준입니다. delayTime과 targetTps를 함께 주면 둘 다 적용됩니다.'), broker.node, limit.node, el('div', { class: 'actions' }, dryRun, publish), preview.node, sent.node),
    el('div', { class: 'stack' }, panel('실행 중인 Legacy task   3초 갱신', actionButton('전체 task 취소', errors, async () => { if (!await confirmAction('모든 기존 task', '전체 task 취소')) return; sent.show(await scope.request('/activemq/task/cancel-tasks', { method: 'POST' })); await refresh(); toast('전체 task를 취소했습니다.'); }, 'small danger'), tasks),
      panel('JmsTemplate 부하 테스트', el('div', { class: 'form-grid' }, loadId.node, loadTc.node, loadCount.node, loadWorkers.node, loadTps.node, loadDelay.node, loadTopic.node), payload.node, loadBroker.node,
        help('완료까지 HTTP 응답을 기다립니다. 다른 탭으로 이동해도 서버 작업은 계속되므로 실행 task 목록에서 취소하세요.'), runLoad, loadResult.node))));
  async function refresh() {
    try {
      const values = await scope.request('/activemq/task/running-tasks'); if (!scope.alive) return; pollErrors.clear();
      tasks.replaceChildren(values.length ? table(['Task ID', ''], values.map(value => [value.taskId, actionButton('취소', errors, async () => { if (!await confirmAction(value.taskId, 'Task 취소')) return; await scope.request(`/activemq/task/cancel-task/${segment(value.taskId)}`, { method: 'POST' }); await refresh(); toast(`Task를 취소했습니다. ${value.taskId}`); }, 'small danger')])) : empty('실행 중인 task가 없습니다.', '전송 작성', () => tc.input.focus()));
    } catch (error) { pollErrors.show(error); }
  }
  primaryAction(publish.closest('fieldset'), publish); primaryAction(runLoad.closest('fieldset'), runLoad);
  scope.poll(refresh, 3000);
}
