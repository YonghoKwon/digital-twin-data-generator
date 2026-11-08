// Credentials anywhere in a request (including payloads and header names) never persist.
const sensitive = /(?:^pw$|activeMQIp|password|passwd|passcode|token|secret|authorization|cookie|credential|api[-_]?key)/i;
export function sanitize(value) {
  if (Array.isArray(value)) return value.map(sanitize);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => !sensitive.test(key)).map(([key, child]) => [key, sanitize(child)]));
  if (typeof value === 'string') {
    try { const parsed = JSON.parse(value); if (parsed && typeof parsed === 'object') return JSON.stringify(sanitize(parsed)); }
    catch { /* A normal string, including generation tokens, stays a string. */ }
  }
  return value;
}
const key = 'dts.publish.history.v1';
const withoutBroker = request => Object.fromEntries(Object.entries(request).filter(([name]) => !['activeMQIp', 'id', 'pw'].includes(name)));
export function loadHistory() {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(value) ? value.filter(entry => entry && typeof entry === 'object' && entry.request && typeof entry.request === 'object' && !Array.isArray(entry.request))
      .slice(0, 10).map(entry => ({ at: entry.at, request: sanitize(withoutBroker(entry.request)) })) : [];
  }
  catch { return []; }
}
export function saveHistory(request) {
  // A broker URL can contain credentials as userinfo or query parameters.
  const stored = withoutBroker(request);
  const entries = [{ at: new Date().toISOString(), request: sanitize(stored) }, ...loadHistory()].slice(0, 10);
  try { localStorage.setItem(key, JSON.stringify(entries)); } catch { /* Storage may be disabled or full. */ }
  return entries;
}
export function clearHistory() { try { localStorage.removeItem(key); } catch { /* Optional storage. */ } }
