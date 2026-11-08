import { el, field, select, check, button, numeric, jsonEditor, tokenHelp, help } from './ui.js';

export function brokerFields() {
  const address = field('브로커 URL · 비우면 서버 기본값', '', { placeholder: 'tcp://localhost:61626', autocomplete: 'off' });
  const user = field('계정 · 선택', '', { autocomplete: 'off' });
  const password = field('비밀번호 · 선택', '', { type: 'password', autocomplete: 'new-password' });
  return { node: el('details', {}, el('summary', {}, '브로커 연결 override'), el('div', { class: 'form-grid' }, address.node, user.node, password.node)),
    read() { return Object.fromEntries([['activeMQIp', address.value], ['id', user.value], ['pw', password.value]].filter(([, value]) => value.trim())); },
    clear() { address.value = ''; user.value = ''; password.value = ''; } };
}
export function messageForm({ stream = false } = {}) {
  const topic = field('Topic', 'dt.sensor.temp');
  const messageId = field('Message ID', 'SENSOR');
  const envelope = select('Envelope', ['DTCORE', 'RAW'], 'DTCORE');
  const standard = check('표준 헤더 추가 (dx_*)');
  const headers = el('div');
  function header(key = '', value = '') {
    const name = field('헤더 키', key, { placeholder: 'dx_schema_version' });
    const content = field('헤더 값', value);
    const row = el('div', { class: 'row' }, name.node, content.node, button('삭제', () => row.remove(), 'small'));
    row.read = () => [name.value, content.value]; headers.append(row);
  }
  const payload = jsonEditor('Payload · JSON', { seq: '{{seq}}', temperature: '{{walk:20:10..30:0.5}}', status: '{{cycle:OK|WARN}}' });
  const broker = brokerFields();
  const count = field('전송 수 count', 1, { type: 'number', min: 1, max: 1000 });
  const interval = field('전송 간격 intervalMs', 0, { type: 'number', min: 0 });
  const streamId = field('Stream ID · 비우면 자동 생성', '', { placeholder: 'sensor-1' });
  const rate = field('초당 전송 rateTps', 1, { type: 'number', min: 0.001, max: 5000, step: 'any' });
  const duration = field('지속 시간 durationSec · 0=무한', 0, { type: 'number', min: 0, step: 'any' });
  const max = field('성공 전송 한도 maxMessages · 0=무한', 0, { type: 'number', min: 0 });
  const paused = check('일시정지 상태로 생성');
  const sourceType = select('데이터 소스', [['PAYLOAD', 'JSON Payload'], ['FILE_DATA', 'FILE_DATA · 서버 파일']], 'PAYLOAD');
  const filePath = field('서버 filePath', '.');
  const format = field('formatFileName', 'format.txt');
  const data = field('dataFileName', 'data.csv');
  const loop = check('파일 끝에서 반복', true);
  const fileNode = el('div', { hidden: true }, help('서버의 simulator.files.base-dir 내부 경로입니다. CSV header·quoted CSV를 지원하며 파일 행의 토큰은 확장하지 않습니다.'), el('div', { class: 'form-grid' }, filePath.node, format.node, data.node), loop.node);
  const payloadNode = el('div', {}, payload.node, tokenHelp(payload));
  sourceType.input.addEventListener('change', () => { fileNode.hidden = sourceType.value !== 'FILE_DATA'; payloadNode.hidden = !fileNode.hidden; });
  const node = el('div', {}, el('div', { class: 'form-grid' }, topic.node, messageId.node, envelope.node), standard.node,
    el('details', {}, el('summary', {}, '사용자 헤더'), headers, button('+ 헤더 추가', () => header(), 'small'), help('헤더 값은 문자열입니다. 세 가지 dx-* 이름만 dx_*로 변환됩니다.')),
    stream ? sourceType.node : null, payloadNode, stream ? fileNode : null,
    el('div', { class: 'form-grid' }, ...(stream ? [streamId.node, rate.node, duration.node, max.node] : [count.node, interval.node])), stream ? paused.node : null, broker.node);
  return { node, payload,
    read() {
      const values = Object.create(null);
      for (const row of headers.children) { const [key, value] = row.read(); if (!key.trim()) throw new Error('빈 헤더 키를 입력할 수 없습니다.'); if (Object.hasOwn(values, key)) throw new Error(`중복 헤더: ${key}`); values[key] = value; }
      const request = { topic: topic.value, messageId: messageId.value, envelope: envelope.value, standardHeaders: standard.value, headers: values, ...broker.read() };
      if (stream && sourceType.value === 'FILE_DATA') request.source = { type: 'FILE_DATA', filePath: filePath.value, formatFileName: format.value, dataFileName: data.value, loop: loop.value };
      else request.payload = payload.get();
      if (stream) Object.assign(request, { ...(streamId.value.trim() ? { streamId: streamId.value } : {}), rateTps: numeric(rate, 'rateTps', Number.MIN_VALUE, 5000), durationSec: numeric(duration, 'durationSec'), maxMessages: numeric(max, 'maxMessages'), startPaused: paused.value });
      else Object.assign(request, { count: numeric(count, 'count', 1, 1000), intervalMs: numeric(interval, 'intervalMs') });
      return request;
    },
    load(request) {
      topic.value = request.topic; messageId.value = request.messageId; envelope.input.value = request.envelope || 'DTCORE'; standard.input.checked = !!request.standardHeaders;
      count.value = request.count ?? 1; interval.value = request.intervalMs ?? 0; payload.set(request.payload ?? {}); headers.replaceChildren();
      Object.entries(request.headers || {}).forEach(([key, value]) => header(key, value)); broker.clear();
    }
  };
}
