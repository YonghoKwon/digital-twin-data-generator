import { errorText } from '../api.js';
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
export const empty = text => el('div', { class: 'empty' }, text);
export const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
export const code = value => el('pre', {}, pretty(value) ?? '');
export const badge = state => el('span', { class: `badge ${/^[A-Z_]+$/.test(state || '') ? state : ''}` }, state || 'UNKNOWN');
export function field(label, value = '', options = {}) {
  const input = el('input', { type: 'text', value, ...options });
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
  root.append(el('div', { class: 'page-head' }, el('div', {}, el('div', { class: 'eyebrow' }, 'SIMULATOR / CONTROL CENTER'), el('h1', {}, title), el('div', { class: 'muted' }, description)), action));
}
export function panel(title, ...children) { return el('section', { class: 'panel' }, el('h2', {}, title), ...children); }
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
    node.hidden = false; node.textContent = errorText(error); toast(node.textContent, true);
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
  const node = el('div', { class: 'editor' }, el('label', { htmlFor: id }, label), textarea, status);
  function validate() {
    try { JSON.parse(textarea.value); status.textContent = 'JSON 문법 정상'; node.classList.remove('invalid'); textarea.setAttribute('aria-invalid', 'false'); return true; }
    catch (error) { status.textContent = error.message; node.classList.add('invalid'); textarea.setAttribute('aria-invalid', 'true'); return false; }
  }
  textarea.addEventListener('input', validate); validate();
  return { node, textarea, get() { if (!validate()) throw new Error(`${label}: ${status.textContent}`); return JSON.parse(textarea.value); },
    set(value) { textarea.value = pretty(value); validate(); }, validate };
}
export function tokenHelp(editor) {
  const tokens = [['seq', '{{seq}}'], ['now', '{{now}}'], ['now 형식', "{{now:yyyy-MM-dd'T'HH:mm:ss}}"], ['uuid', '{{uuid}}'], ['random', '{{random:Integer:1..10}}'], ['walk', '{{walk:20:10..30:0.5}}'], ['cycle', '{{cycle:OK|WARN}}'], ['pick', '{{pick:A|B}}']];
  return el('details', {}, el('summary', {}, '토큰 도움말 · 예시 삽입'), help('문자열 전체가 토큰이면 숫자·불리언 타입을 유지합니다. 삽입 위치를 선택한 후 누르세요. 문자열 밖에서는 따옴표를 함께 삽입합니다.'), el('div', { class: 'token-grid' }, tokens.map(([name, token]) => button(name, () => {
    const area = editor.textarea;
    const prefix = area.value.slice(0, area.selectionStart);
    let inside = false, escaped = false;
    for (const char of prefix) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') inside = !inside; }
    area.setRangeText(inside ? token : JSON.stringify(token), area.selectionStart, area.selectionEnd, 'end');
    editor.validate(); area.focus();
  }, 'small'))));
}
