export function drawSparkline(canvas, buffer) {
  const ratio = globalThis.devicePixelRatio || 1;
  const width = canvas.clientWidth || 300, height = 56;
  canvas.width = width * ratio; canvas.height = height * ratio;
  const context = canvas.getContext('2d');
  context.scale(ratio, ratio);
  const styles = getComputedStyle(canvas);
  const now = Date.now(), samples = buffer.samples.filter(x => x.at >= now - 60000);
  const max = Math.max(1, ...samples.map(x => x.value));
  context.strokeStyle = styles.getPropertyValue('--rule'); context.beginPath(); context.moveTo(0, 49); context.lineTo(width, 49); context.stroke();
  context.strokeStyle = styles.getPropertyValue('--ink'); context.lineWidth = 1.5; context.beginPath();
  samples.forEach((sample, index) => {
    const x = Math.max(0, (sample.at - now + 60000) / 60000 * width), y = 48 - sample.value / max * 42;
    if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
  });
  context.stroke(); canvas.setAttribute('aria-label', `최근 60초 실제 TPS, 현재 ${samples.at(-1)?.value.toFixed(2) || 0}, 최대 ${max.toFixed(2)}`);
}

export function atMillis(at) {
  if (typeof at === 'number') return at;
  const parts = String(at || '0').split(':').map(Number);
  return parts.length === 3 ? (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000 : Number(at) || 0;
}
export function timelineEvents(definition = {}, logs = []) {
  const result = [];
  for (const event of definition.events || []) {
    const type = event.type || 'PUBLISH';
    const count = Math.min(2000, event.repeat?.count || 1);
    for (let index = 0; index < count; index++) result.push({ atMs: atMillis(event.at) + index * (event.repeat?.everyMs || 0), type, topic: event.topic || definition.defaults?.topic || type, label: event.label });
  }
  for (const track of definition.tracks || []) result.push({ atMs: atMillis(track.startAt), type: 'TRACK', topic: track.trackId || track.topic || 'CSV', label: track.dataFileName });
  // CSV row positions are exposed only after execution by the existing event log API.
  for (const log of logs) if (!result.some(x => x.atMs === log.atMs && x.topic === log.topic && x.type === log.type)) result.push(log);
  return result;
}
export function drawTimeline(canvas, definition, status, logs) {
  const events = timelineEvents(definition, logs);
  const rows = [...new Set(events.filter(x => x.type !== 'MARK').map(x => x.topic || x.type))];
  const width = canvas.clientWidth || 600, height = Math.max(150, rows.length * 26 + 65), ratio = globalThis.devicePixelRatio || 1;
  canvas.style.height = `${height}px`; canvas.width = width * ratio; canvas.height = height * ratio;
  const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
  const styles = getComputedStyle(canvas), ink = styles.getPropertyValue('--ink'), rule = styles.getPropertyValue('--rule');
  const left = Math.min(150, width * .3), duration = Math.max(1, status.durationMs);
  canvas.dataset.plotLeft = left;
  const x = at => left + Math.max(0, Math.min(1, at / duration)) * (width - left - 10);
  ctx.font = '12px Bahnschrift, Malgun Gothic, sans-serif';
  rows.forEach((topic, index) => {
    const y = 48 + index * 26;
    ctx.fillStyle = ink; ctx.fillText(String(topic).slice(0, 23), 6, y + 4);
    ctx.strokeStyle = rule; ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(width - 10, y); ctx.stroke();
  });
  for (const event of events) {
    ctx.fillStyle = ink; const position = x(event.atMs);
    if (event.type === 'MARK') { ctx.fillText(event.label || 'MARK', Math.min(width - 90, position), 16); continue; }
    const y = 48 + rows.indexOf(event.topic || event.type) * 26;
    if (String(event.type).startsWith('MOCK')) {
      ctx.beginPath(); ctx.moveTo(position, y - 5); ctx.lineTo(position + 5, y); ctx.lineTo(position, y + 5); ctx.lineTo(position - 5, y); ctx.closePath(); ctx.fill();
    } else if (event.type === 'TRACK') { ctx.strokeStyle = ink; ctx.strokeRect(position, y - 6, Math.max(8, width - 12 - position), 12); }
    else ctx.fillRect(position - 1, y - 5, 2, 10);
  }
  ctx.strokeStyle = styles.getPropertyValue('--act'); ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(x(status.positionMs), 23); ctx.lineTo(x(status.positionMs), height - 20); ctx.stroke();
  ctx.fillStyle = ink; ctx.fillText('0 s', left, height - 4); ctx.fillText(`${(duration / 1000).toFixed(1)} s`, Math.max(left, width - 60), height - 4);
}
