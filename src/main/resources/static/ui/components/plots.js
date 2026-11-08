export function sparklineScale(samples, targetTps = 0, { now = Date.now(), width = 300, height = 36 } = {}) {
  const windowMs = 60000, padding = 2;
  const recent = samples.filter(sample => sample.at >= now - windowMs && sample.at <= now && Number.isFinite(sample.value));
  const target = Math.max(0, Number(targetTps) || 0);
  const max = Math.max(target, ...recent.map(sample => Math.max(0, sample.value))) * 1.2 || 1;
  const y = value => height - padding - Math.max(0, value) / max * (height - padding * 2);
  return { max, targetY: y(target), points: recent.map(sample => ({
    x: (sample.at - now + windowMs) / windowMs * width, y: y(sample.value)
  })) };
}
export function drawSparkline(canvas, buffer, targetTps = 0) {
  const ratio = globalThis.devicePixelRatio || 1;
  const width = canvas.clientWidth || 300, height = 36;
  canvas.width = width * ratio; canvas.height = height * ratio;
  const context = canvas.getContext('2d');
  context.scale(ratio, ratio);
  const styles = getComputedStyle(canvas);
  const { max, targetY, points } = sparklineScale(buffer.samples, targetTps, { width, height });
  context.strokeStyle = styles.getPropertyValue('--dim'); context.lineWidth = 1; context.setLineDash([3, 3]);
  context.beginPath(); context.moveTo(0, targetY); context.lineTo(width, targetY); context.stroke(); context.setLineDash([]);
  context.strokeStyle = styles.getPropertyValue('--ink'); context.lineWidth = 1.5; context.beginPath();
  points.forEach(({ x, y }, index) => {
    if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
  });
  context.stroke();
  if (points.length === 1) { context.fillStyle = context.strokeStyle; context.fillRect(points[0].x - 1, points[0].y - 1, 2, 2); }
  canvas.setAttribute('aria-label', `최근 60초 실제 TPS, 목표 ${targetTps}, 세로축 0~${max.toFixed(2)}`);
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
  for (const log of logs) if (!result.some(x => x.atMs === log.atMs && x.type === log.type && (x.type === 'MARK' || x.topic === log.topic))) result.push(log);
  return result;
}
// Fit labels into two lanes. Crowded lanes truncate at the next label rather than overlap.
export function layoutMarkLabels(marks, { left = 0, right, durationMs, measure, gap = 6, maxWidth = 180 }) {
  const width = Math.max(1, right - left), duration = Math.max(1, durationMs);
  const lanes = [[], []];
  const labels = marks.map((mark, index) => {
    const text = String(mark.label || '');
    const position = left + Math.max(0, Math.min(1, mark.atMs / duration)) * width;
    const labelWidth = Math.min(width, maxWidth, Math.max(1, measure(text)));
    return { text, position, width: labelWidth, start: Math.min(right - labelWidth, position), index };
  }).sort((a, b) => a.position - b.position || a.index - b.index);
  for (const label of labels) {
    let lane = lanes.findIndex(items => !items.length || items.at(-1).end + gap <= label.start);
    if (lane < 0) lane = lanes[0].at(-1).end <= lanes[1].at(-1).end ? 0 : 1;
    label.end = Math.min(right, label.start + label.width);
    label.lane = lane;
    lanes[lane].push(label);
  }
  for (const items of lanes) for (let index = 0; index < items.length; index++) {
    const label = items[index], previous = items[index - 1], next = items[index + 1];
    const start = previous ? Math.min(right, (previous.position + label.position + gap) / 2) : left;
    const end = next ? Math.max(start, (label.position + next.position - gap) / 2) : right;
    const available = Math.min(label.width, Math.max(0, end - start));
    label.start = Math.max(start, Math.min(label.start, end - available));
    label.end = label.start + available;
    let text = label.text;
    if (measure(text) > available) {
      while (text && measure(`${text}…`) > available) text = text.slice(0, -1);
      text = measure('…') <= available ? `${text}…` : '';
    }
    label.display = text; label.width = available;
    label.align = label.position + measure(label.text) > right ? 'right' : 'left';
    label.x = label.align === 'right' ? label.end : label.start;
  }
  return labels;
}
export function drawTimeline(canvas, definition, status, logs) {
  const events = timelineEvents(definition, logs);
  const rows = [...new Set(events.filter(x => x.type !== 'MARK').map(x => x.topic || x.type))];
  const width = canvas.clientWidth || 600, height = Math.max(150, rows.length * 26 + 87), ratio = globalThis.devicePixelRatio || 1;
  canvas.style.height = `${height}px`; canvas.width = width * ratio; canvas.height = height * ratio;
  const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
  const styles = getComputedStyle(canvas), ink = styles.getPropertyValue('--ink'), rule = styles.getPropertyValue('--rule');
  const left = Math.min(150, width * .3), duration = Math.max(1, status.durationMs);
  canvas.dataset.plotLeft = left;
  const x = at => left + Math.max(0, Math.min(1, at / duration)) * (width - left - 10);
  ctx.font = '12px Bahnschrift, Malgun Gothic, sans-serif';
  rows.forEach((topic, index) => {
    const y = 70 + index * 26;
    ctx.fillStyle = ink; ctx.fillText(String(topic).slice(0, 23), 6, y + 4);
    ctx.strokeStyle = rule; ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(width - 10, y); ctx.stroke();
  });
  for (const event of events) {
    ctx.fillStyle = ink; const position = x(event.atMs);
    if (event.type === 'MARK') {
      ctx.strokeStyle = ink; ctx.beginPath(); ctx.moveTo(position, 43); ctx.lineTo(position, 53); ctx.stroke(); continue;
    }
    const y = 70 + rows.indexOf(event.topic || event.type) * 26;
    if (String(event.type).startsWith('MOCK')) {
      ctx.beginPath(); ctx.moveTo(position, y - 5); ctx.lineTo(position + 5, y); ctx.lineTo(position, y + 5); ctx.lineTo(position - 5, y); ctx.closePath(); ctx.fill();
    } else if (event.type === 'TRACK') { ctx.strokeStyle = ink; ctx.strokeRect(position, y - 6, Math.max(8, width - 12 - position), 12); }
    else ctx.fillRect(position - 1, y - 5, 2, 10);
  }
  const marks = layoutMarkLabels(events.filter(event => event.type === 'MARK'), { left, right: width - 10, durationMs: duration, measure: text => ctx.measureText(text).width });
  for (const mark of marks) { ctx.fillStyle = ink; ctx.textAlign = mark.align; ctx.fillText(mark.display, mark.x, 16 + mark.lane * 18); }
  ctx.textAlign = 'left';
  canvas.title = marks.map(mark => mark.text).filter(Boolean).join('\n');
  // Canvas labels have no DOM title; expose the exact hovered label as its native tooltip.
  canvas.onmousemove = event => {
    const box = canvas.getBoundingClientRect(), px = event.clientX - box.left, py = event.clientY - box.top;
    canvas.title = marks.filter(mark => py >= mark.lane * 18 && py <= 20 + mark.lane * 18 && px >= mark.start && px <= mark.end).map(mark => mark.text).join('\n');
  };
  ctx.strokeStyle = styles.getPropertyValue('--ink'); ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(x(status.positionMs), 43); ctx.lineTo(x(status.positionMs), height - 20); ctx.stroke();
  ctx.fillStyle = ink; ctx.fillText('0 s', left, height - 4); ctx.fillText(`${(duration / 1000).toFixed(1)} s`, Math.max(left, width - 60), height - 4);
}
