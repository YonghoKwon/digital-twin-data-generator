import { errorText } from '../api.js';
import { jsonErrorPosition } from './state.js';
let sequence = 0;

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key in node && !key.startsWith('aria')) node[key] = value;
    else node.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child !== undefined && child !== null) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
export const button = (label, onclick, kind = '') => el('button', { type: 'button', class: kind, onclick }, label);
export const help = text => el('p', { class: 'help' }, text);
export const empty = (text, label, callback) => el('div', { class: 'empty' }, text, label ? button(label, callback) : null);
export const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
export const code = value => el('pre', {}, pretty(value) ?? '');
// ISO 시각(나노초 포함 가능)을 'YYYY. M. D. 오전 hh:mm:ss' 형식으로. 값이 없거나 읽을 수 없으면 대체 문자열.
export const time = (value, fallback = '없음') => {
  if (!value) return fallback;
  const date = new Date(String(value).replace(/(\.\d{3})\d+/, '$1'));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('ko-KR');
};
export const badge = state => {
  const names = { RUNNING: '실행 중', PAUSED: '일시정지', RECONNECTING: '재연결 중', CONNECTED: '연결됨', STOPPED: '중지', FAILED: '실패', COMPLETED: '완료', VALID: '유효', INVALID: '오류', LOADED: '로드됨' };
  const kind = ['FAILED', 'INVALID'].includes(state) ? 'alarm' : state === 'RECONNECTING' ? 'warn' : ['RUNNING', 'CONNECTED'].includes(state) ? 'run' : 'idle';
  return el('span', { class: `state ${kind}` }, el('span', { class: 'lamp', 'aria-hidden': 'true' }), names[state] || state || '확인 중');
};
export function field(label, value = '', options = {}) {
  const input = el('input', { type: 'text', value, ...options });
  if (/topic/i.test(label)) input.setAttribute('list', 'known-topics');
  if (/message\s*id/i.test(label)) input.setAttribute('list', 'known-message-ids');
  const node = el('label', { class: 'field' }, el('span', {}, label), input);
  return { node, input, get value() { return input.value; }, set value(value) { input.value = value ?? ''; } };
}
export function select(label, choices, initial) {
  const input = el('select', {}, choices.map(choice => {
    const [value, name] = Array.isArray(choice) ? choice : [choice, choice];
    return el('option', { value, selected: value === initial }, name);
  }));
  return { node: el('label', { class: 'field' }, el('span', {}, label), input), input, get value() { return input.value; } };
}
export function check(label, initial = false) {
  const input = el('input', { type: 'checkbox', checked: initial });
  return { node: el('label', { class: 'check' }, input, label), input, get value() { return input.checked; } };
}
export function page(root, title, description, action) {
  root.append(el('div', { class: 'page-head' }, el('h1', {}, title), action));
}
export function panel(title, ...children) { return el('fieldset', { class: 'panel' }, el('legend', {}, title), ...children); }
export function resultBox() {
  const node = el('div', { class: 'result', 'aria-live': 'polite' });
  return { node, show(value) { node.replaceChildren(code(value)); }, clear() { node.replaceChildren(); } };
}
let lastToast = '', lastToastAt = 0;
export function toast(message, error = false) {
  if (message === lastToast && Date.now() - lastToastAt < 10000) return;
  lastToast = message; lastToastAt = Date.now();
  const holder = document.getElementById('toasts');
  const item = el('div', { class: `toast ${error ? 'error-toast' : ''}` }, message);
  holder.append(item);
  while (holder.children.length > 4) holder.firstChild.remove();
  setTimeout(() => item.remove(), 7000);
}
export function errorBox() {
  const node = el('div', { class: 'error', role: 'alert', hidden: true });
  return { node, clear() { node.hidden = true; node.textContent = ''; }, show(error) {
    if (error.name === 'AbortError') return;
    const guidance = error.status === 502 ? '브로커 주소와 연결 상태를 확인하세요.' : error.status === 409 ? '실행 ID와 현재 상태를 확인하세요.' : error.status === 404 ? '대상과 서버 기능을 확인하세요.' : '입력 값과 JSON 오류 위치를 확인하세요.';
    node.hidden = false; node.textContent = `${errorText(error)}\n${guidance}`; toast(node.textContent, true);
  } };
}
export async function action(control, errors, callback) {
  if (control.disabled) return;
  control.disabled = true; errors.clear();
  try { await callback(); } catch (error) { errors.show(error); }
  finally { control.disabled = false; }
}
export function actionButton(label, errors, callback, kind = '') {
  const control = button(label, () => action(control, errors, callback), kind);
  return control;
}
export function metrics(values) {
  return el('div', { class: 'metrics' }, Object.entries(values).map(([label, value]) => el('div', {}, label, el('strong', {}, value ?? '—'))));
}
export function primaryAction(node, control) { node.dataset.primaryGroup = 'true'; control.dataset.primary = 'true'; }
export async function confirmAction(name, operation = '삭제') {
  const dialog = el('dialog', { class: 'confirm-dialog', 'aria-label': `${operation} 확인` });
  return new Promise(resolve => {
    const finish = value => { dialog.close(); dialog.remove(); resolve(value); };
    dialog.append(el('h2', {}, `${operation} 확인`), el('p', {}, name), el('div', { class: 'actions' }, button('취소', () => finish(false)), button(operation, () => finish(true), 'danger')));
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(false); });
    document.body.append(dialog); dialog.showModal(); dialog.querySelector('button').focus();
  });
}
export async function copyText(value) {
  try { await navigator.clipboard.writeText(value); toast('복사했습니다.'); }
  catch { throw new Error(`복사할 내용을 선택해 직접 복사하세요.\n${value}`); }
}
export function table(headers, rows) {
  return el('div', { class: 'table-wrap' }, el('table', {}, el('thead', {}, el('tr', {}, headers.map(x => el('th', { scope: 'col' }, x)))), el('tbody', {}, rows.map(row => el('tr', {}, row.map(cell => el('td', {}, cell)))))));
}
export function numeric(input, label, min = 0, max = Infinity) {
  const raw = input.value;
  const value = Number(raw);
  if (raw.trim() === '' || !Number.isFinite(value) || value < min || value > max) throw new Error(`${label}: ${min}~${max === Infinity ? '무제한' : max} 범위의 숫자를 입력하세요.`);
  return value;
}
export function jsonEditor(label, initial = {}, rows = 12) {
  const id = `editor-${++sequence}`;
  const textarea = el('textarea', { id, rows, value: typeof initial === 'string' ? initial : pretty(initial), spellcheck: false, 'aria-describedby': `${id}-status` });
  const status = el('div', { class: 'editor-status', id: `${id}-status`, 'aria-live': 'polite' });
  const format = button('정렬', () => { if (validate()) { textarea.value = pretty(JSON.parse(textarea.value)); textarea.dispatchEvent(new Event('input', { bubbles: true })); } }, 'small');
  const node = el('div', { class: 'editor' }, el('div', { class: 'panel-head' }, el('label', { htmlFor: id }, label), format), textarea, status);
  function validate() {
    try { JSON.parse(textarea.value); status.textContent = 'JSON 문법 정상'; node.classList.remove('invalid'); textarea.setAttribute('aria-invalid', 'false'); return true; }
    catch (error) { status.textContent = `${jsonErrorPosition(textarea.value, error)} ${error.message}`; node.classList.add('invalid'); textarea.setAttribute('aria-invalid', 'true'); return false; }
  }
  textarea.addEventListener('input', validate); validate();
  return { node, textarea, get() { if (!validate()) throw new Error(`${label}: ${status.textContent}`); return JSON.parse(textarea.value); },
    set(value) { textarea.value = pretty(value); textarea.dispatchEvent(new Event('input', { bubbles: true })); }, validate };
}
export function tokenHelp(editor) {
  const tokens = [['seq', '{{seq}}'], ['now', '{{now}}'], ['now 형식', "{{now:yyyy-MM-dd'T'HH:mm:ss}}"], ['uuid', '{{uuid}}'], ['random', '{{random:Integer:1..10}}'], ['walk', '{{walk:20:10..30:0.5}}'], ['cycle', '{{cycle:OK|WARN}}'], ['pick', '{{pick:A|B}}']];
  return el('details', {}, el('summary', {}, '토큰 도움말 · 예시 삽입'), help('문자열 전체가 토큰이면 숫자·불리언 타입을 유지합니다. 삽입 위치를 선택한 후 누르세요. 문자열 밖에서는 따옴표를 함께 삽입합니다.'), el('div', { class: 'token-grid' }, tokens.map(([name, token]) => button(name, () => {
    const area = editor.textarea;
    const prefix = area.value.slice(0, area.selectionStart);
    let inside = false, escaped = false;
    for (const char of prefix) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') inside = !inside; }
    area.setRangeText(inside ? token : JSON.stringify(token), area.selectionStart, area.selectionEnd, 'end');
    area.dispatchEvent(new Event('input', { bubbles: true })); area.focus();
  }, 'small'))));
}
