import { createScope } from './api.js';
import { el, errorBox } from './components/ui.js';
import dashboard from './views/dashboard.js';
import publish from './views/publish.js';
import streams from './views/streams.js';
import subscriptions from './views/subscriptions.js';
import mock from './views/mock.js';
import scenarios from './views/scenarios.js';
import legacy from './views/legacy.js';

const routes = { dashboard, publish, streams, subscriptions, mock, scenarios, legacy };
const root = document.getElementById('main');
let scope;
function navigate() {
  scope?.dispose(); scope = createScope(); root.replaceChildren();
  const route = location.hash.slice(2).split('?')[0] || 'dashboard';
  const selected = Object.hasOwn(routes, route) ? route : 'dashboard';
  for (const link of document.querySelectorAll('nav a')) {
    if (link.hash === `#/${selected}`) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
  try { routes[selected](root, scope); }
  catch (error) { const errors = errorBox(); root.append(el('h1', {}, '화면을 열지 못했습니다'), errors.node); errors.show(error); }
  root.focus({ preventScroll: true }); window.scrollTo(0, 0);
}
window.addEventListener('hashchange', navigate);
window.addEventListener('pagehide', () => scope?.dispose());
window.addEventListener('pageshow', event => { if (event.persisted) navigate(); });
const themes = ['system', 'light', 'dark'];
const names = { system: '시스템', light: '라이트', dark: '다크' };
let theme = 'system';
try { const stored = localStorage.getItem('dts.theme'); if (themes.includes(stored)) theme = stored; } catch { /* Optional preference. */ }
const themeButton = document.getElementById('theme');
function applyTheme() { document.documentElement.dataset.theme = theme; themeButton.textContent = `테마: ${names[theme]}`; }
themeButton.addEventListener('click', () => { theme = themes[(themes.indexOf(theme) + 1) % themes.length]; applyTheme(); try { localStorage.setItem('dts.theme', theme); } catch { /* Optional preference. */ } });
function clock() { document.getElementById('clock').textContent = new Date().toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }); }
applyTheme(); clock(); setInterval(clock, 30000); navigate();
