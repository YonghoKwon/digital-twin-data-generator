import { el, button, field, table } from './ui.js';
import { searchCommands } from './state.js';
import { plant, stopAll, websocketAddress } from './plant.js';
import { copyText, toast } from './ui.js';
import { request } from '../api.js';

export const destinations = [ ['dashboard', '개요'], ['publish', '보내기'], ['streams', '스트림'], ['subscriptions', '수신'], ['mock', 'REST'], ['scenarios', '시나리오'], ['legacy', '기존'] ];
function go(route, intent) {
  location.hash = `#/${route}${intent ? `?action=${intent}` : ''}`;
  if (!intent) document.getElementById('main').focus();
}
export function shortcuts() {
  if (document.querySelector('dialog[open]')) return;
  const dialog = el('dialog', { 'aria-label': '단축키 목록' });
  dialog.append(el('h2', {}, '단축키'), table(['키', '동작'], [['1~7', '화면 이동'], ['Ctrl K 또는 /', '명령 검색'], ['Ctrl Enter', '현재 폼 주 동작'], ['Esc', '대화상자 닫기'], ['?', '단축키 목록']]), button('닫기', () => dialog.close()));
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog); dialog.showModal();
}
export function openPalette() {
  if (document.querySelector('dialog[open]')) return;
  const commands = [
    ...destinations.map(([route, label]) => ({ label: `${label} 화면`, run: () => go(route) })),
    { label: '새 스트림', run: () => go('streams', 'new') },
    { label: '구독 추가', run: () => go('subscriptions', 'new') },
    ...plant.library.map(entry => ({ label: `시나리오 실행 ${entry.file}`, run: async () => {
      const value = await request('/dt/scenario-runs', { method: 'POST', body: { file: entry.file } });
      toast(`시나리오를 실행했습니다. ${value.runId}`); go('scenarios');
    } })),
    { label: '전체 정지', run: stopAll },
    { label: '브로커 주소 복사', run: () => copyText(websocketAddress()) },
    { label: '단축키 목록', run: shortcuts }
  ];
  const dialog = el('dialog', { class: 'palette', 'aria-label': '명령 팔레트' });
  const search = field('명령 검색', '', { placeholder: '화면 또는 동작 이름', role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'command-results', autocomplete: 'off' });
  const list = el('div', { id: 'command-results', class: 'palette-list', role: 'listbox', 'aria-label': '명령 검색 결과' });
  let selected = 0, matches = [];
  async function execute(command) {
    if (!command) return;
    dialog.close();
    try { await command.run(); } catch (error) { toast(`${command.label} 실패\n${error.message}`, true); }
  }
  function render() {
    matches = searchCommands(commands, search.value);
    selected = Math.min(selected, Math.max(0, matches.length - 1));
    list.replaceChildren(...matches.map((command, index) => {
      const control = button(command.label, () => void execute(command));
      control.id = `command-${index}`; control.setAttribute('role', 'option'); control.setAttribute('aria-selected', String(index === selected));
      return control;
    }));
    if (!matches.length) list.append(el('p', {}, '일치하는 명령이 없습니다.'));
    search.input.setAttribute('aria-activedescendant', matches.length ? `command-${selected}` : '');
    list.children[selected]?.scrollIntoView({ block: 'nearest' });
  }
  search.input.addEventListener('input', () => { selected = 0; render(); });
  search.input.addEventListener('keydown', event => {
    if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault(); selected = (selected + (event.key === 'ArrowDown' ? 1 : -1) + matches.length) % (matches.length || 1); render();
    } else if (event.key === 'Enter') { event.preventDefault(); void execute(matches[selected]); }
  });
  dialog.append(search.node, list, button('닫기', () => dialog.close()));
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog); dialog.showModal(); render(); search.input.focus();
}
export function installShortcuts() {
  document.addEventListener('keydown', event => {
    const editing = event.target.closest('input,textarea,select,[contenteditable="true"]');
    const modal = document.querySelector('dialog[open]');
    if (event.ctrlKey && event.key === 'Enter' && !modal) {
      const group = event.target.closest('[data-primary-group]') || document.getElementById('main');
      const control = group.querySelector('[data-primary]');
      if (control && !control.disabled) { event.preventDefault(); control.click(); }
      return;
    }
    if ((event.ctrlKey && event.key.toLowerCase() === 'k') || (!editing && event.key === '/')) {
      event.preventDefault(); if (!modal) openPalette(); return;
    }
    if (editing || modal || event.ctrlKey || event.altKey || event.metaKey) return;
    if (/^[1-7]$/.test(event.key)) { event.preventDefault(); go(destinations[Number(event.key) - 1][0]); }
    if (event.key === '?') { event.preventDefault(); shortcuts(); }
  });
}
