import { sanitize } from './history.js';

export function readState(key, fallback = null) {
  try { return JSON.parse(localStorage.getItem(`dts.s7.${key}`)) ?? fallback; }
  catch { return fallback; }
}
export function writeState(key, value) {
  try { localStorage.setItem(`dts.s7.${key}`, JSON.stringify(sanitize(value))); }
  catch { /* Browser storage is optional. */ }
}
export function persistForm(node, key) {
  const controls = [...node.querySelectorAll('input,select,textarea')].filter(input =>
    input.type !== 'password' && !input.closest('[data-private]') && input.type !== 'range');
  const saved = readState(key, {});
  const counts = new Map();
  for (const input of controls) {
    const label = input.closest('label')?.querySelector('span')?.textContent || input.getAttribute('aria-label') || input.id || input.type;
    const count = counts.get(label) || 0;
    counts.set(label, count + 1);
    input.dataset.draftKey = `${label}-${count}`;
    if (Object.hasOwn(saved, input.dataset.draftKey)) {
      if (input.type === 'checkbox') input.checked = saved[input.dataset.draftKey];
      else input.value = saved[input.dataset.draftKey];
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }
  const save = () => writeState(key, Object.fromEntries(controls.map(input => [input.dataset.draftKey,
    input.type === 'checkbox' ? input.checked : input.value])));
  node.addEventListener('input', save);
  node.addEventListener('change', save);
}

export const builtInPresets = [
  { name: '센서 값', payload: { seq: '{{seq}}', temperature: '{{walk:20:10..30:0.5}}' } },
  { name: '설비 상태', payload: { equipment: 'COIL-01', status: '{{cycle:IDLE|RUN|RUN|WARN}}' } },
  { name: '알람 이벤트', payload: { code: 'TEMP_HIGH', severity: 'WARN', occurredAt: '{{now}}' } }
];
export function loadPresets() {
  const stored = readState('presets', []);
  return [...builtInPresets, ...(Array.isArray(stored) ? stored.filter(x => x && typeof x.name === 'string' && Object.hasOwn(x, 'payload')).map(sanitize) : [])];
}
export function savePreset(name, payload) {
  const cleanName = name.trim();
  if (!cleanName) throw new Error('프리셋 이름을 입력하세요.');
  if (builtInPresets.some(x => x.name === cleanName)) throw new Error('기본 프리셋과 다른 이름을 입력하세요.');
  const custom = loadPresets().slice(builtInPresets.length).filter(x => x.name !== cleanName);
  writeState('presets', [...custom, { name: cleanName, payload: sanitize(payload) }]);
}
export function deletePreset(name) {
  writeState('presets', loadPresets().slice(builtInPresets.length).filter(x => x.name !== name));
}
export function searchCommands(commands, query) {
  const words = query.toLocaleLowerCase().trim().split(/\s+/);
  return commands.filter(command => words.every(word => `${command.label} ${command.keywords || ''}`.toLocaleLowerCase().includes(word)));
}
export class SparkBuffer {
  constructor(seconds = 60) { this.seconds = seconds; this.samples = []; }
  add(value, at = Date.now()) {
    if (!Number.isFinite(value)) return;
    this.samples = this.samples.filter(sample => sample.at > at - this.seconds * 1000 && sample.at !== at);
    this.samples.push({ at, value });
    this.samples.sort((a, b) => a.at - b.at);
    this.samples = this.samples.slice(-this.seconds * 2);
  }
}
export class CounterRate {
  constructor() { this.previous = new Map(); }
  sample(id, count, at = Date.now()) {
    const previous = this.previous.get(id);
    this.previous.set(id, { count, at });
    return previous && at > previous.at ? Math.max(0, (count - previous.count) * 1000 / (at - previous.at)) : 0;
  }
}
export function filterMessages(messages, { topic = '', messageId = '' } = {}) {
  return messages.filter(message => (!topic || String(message.topic || '').toLowerCase().includes(topic.toLowerCase())) &&
    (!messageId || message.messageId === messageId));
}
export function collectSuggestions({ broker, streams = [], subscriptions = [], scenarios = [], recent = [] } = {}) {
  const topics = new Set(), messageIds = new Set();
  function add(value) {
    if (!value || typeof value !== 'object') return;
    if (typeof value.topic === 'string' && value.topic) topics.add(value.topic);
    if (typeof value.messageId === 'string' && value.messageId) messageIds.add(value.messageId);
    for (const child of Object.values(value)) if (child && typeof child === 'object') {
      if (Array.isArray(child)) child.forEach(add); else add(child);
    }
  }
  for (const address of broker?.addresses || []) topics.add(address.address || address.name);
  [...streams, ...subscriptions, ...scenarios, ...recent].forEach(add);
  return { topics: [...topics].filter(Boolean).sort(), messageIds: [...messageIds].sort() };
}
export function jsonErrorPosition(source, error) {
  const explicit = error.message.match(/line (\d+) column (\d+)/i);
  if (explicit) return `${explicit[1]}:${explicit[2]}`;
  const match = error.message.match(/position (\d+)/i);
  const offset = match ? Number(match[1]) : source.length;
  const lines = source.slice(0, offset).split('\n');
  return `${lines.length}:${lines.at(-1).length + 1}`;
}
