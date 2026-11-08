export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || (typeof body === 'string' && body) || `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export async function request(path, { method = 'GET', body, signal } = {}) {
  const response = await fetch(path, {
    method, signal, cache: 'no-store', headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  const raw = await response.text();
  let data = raw;
  try { data = raw ? JSON.parse(raw) : null; } catch { /* Legacy endpoints return plain text. */ }
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

export const segment = value => encodeURIComponent(value);
export const active = state => ['RUNNING', 'PAUSED', 'RECONNECTING'].includes(state);
export const errorText = error => error instanceof ApiError
  ? `HTTP ${error.status}\n${typeof error.body === 'object' ? JSON.stringify(error.body, null, 2) : error.message}`
  : error.message || String(error);

// Each route owns its fetches, timers and event listeners, including pending mutations.
export function createScope() {
  const controller = new AbortController();
  const disposers = [];
  return {
    get alive() { return !controller.signal.aborted; },
    request(path, options = {}) { return request(path, { ...options, signal: controller.signal }); },
    add(dispose) { disposers.push(dispose); return dispose; },
    poll(callback, milliseconds) {
      let timer, busy = false;
      const tick = async () => {
        clearTimeout(timer);
        if (controller.signal.aborted || document.hidden || busy) return;
        busy = true;
        try { await callback(); } catch (error) { if (error.name !== 'AbortError') console.error('Polling failed', error.name); }
        finally {
          busy = false;
          if (!controller.signal.aborted && !document.hidden) timer = setTimeout(tick, milliseconds);
        }
      };
      const visibility = () => { clearTimeout(timer); if (!document.hidden) void tick(); };
      document.addEventListener('visibilitychange', visibility);
      disposers.push(() => { clearTimeout(timer); document.removeEventListener('visibilitychange', visibility); });
      void tick();
      return tick;
    },
    dispose() { controller.abort(); for (const dispose of disposers.reverse()) dispose(); }
  };
}
