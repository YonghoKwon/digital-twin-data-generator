import { createScope } from './api.js';
import { el, errorBox } from './components/ui.js';
import { readState, writeState, persistForm } from './components/state.js';
import { startStatus, stopAll } from './components/plant.js';
import { installShortcuts, openPalette, shortcuts } from './components/commands.js';
import dashboard from './views/dashboard.js';
import publish from './views/publish.js';
import streams from './views/streams.js';
import subscriptions from './views/subscriptions.js';
import mock from './views/mock.js';
import scenarios from './views/scenarios.js';
import legacy from './views/legacy.js';

const routes = { dashboard, publish, streams, subscriptions, mock, scenarios, legacy };
const root = document.getElementById('main');
const globalScope = createScope();
let scope;
function navigate() {
  scope?.dispose(); scope = createScope(); root.replaceChildren();
  const route = location.hash.slice(2).split('?')[0] || readState('route', 'dashboard');
  const selected = Object.hasOwn(routes, route) ? route : 'dashboard';
  writeState('route', selected);
  for (const link of document.querySelectorAll('nav a')) {
    if (link.hash === `#/${selected}`) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
  try { routes[selected](root, scope); persistForm(root, `draft.${selected}`); }
  catch (error) { const errors = errorBox(); root.append(el('h1', {}, '화면을 열지 못했습니다'), errors.node); errors.show(error); }
  root.focus({ preventScroll: true }); window.scrollTo(0, 0);
  if (new URLSearchParams(location.hash.split('?')[1]).get('action') === 'new') root.querySelector('input')?.focus();
}
window.addEventListener('hashchange', navigate);
window.addEventListener('pagehide', () => { scope?.dispose(); globalScope.dispose(); });
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
const themes = ['system', 'light', 'dark'];
const names = { system: '시스템', light: '주간', dark: '야간' };
let theme = readState('theme', 'system');
if (!themes.includes(theme)) theme = 'system';
const themeButton = document.getElementById('theme');
function applyTheme() { document.documentElement.dataset.theme = theme; themeButton.textContent = names[theme]; }
themeButton.addEventListener('click', () => { theme = themes[(themes.indexOf(theme) + 1) % themes.length]; applyTheme(); writeState('theme', theme); });
document.getElementById('commands').addEventListener('click', openPalette);
document.getElementById('shortcuts').addEventListener('click', shortcuts);
document.getElementById('stop-all').addEventListener('click', () => void stopAll());
applyTheme(); installShortcuts(); navigate(); startStatus(globalScope);
