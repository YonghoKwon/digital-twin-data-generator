/** Merge SSE and REST catch-up without duplicating records or exceeding the display budget. */
export class MessageBuffer {
  constructor(limit = 500) { this.limit = limit; this.records = new Map(); this.cursor = 0; }
  add(messages) {
    for (const message of messages) {
      if (!Number.isSafeInteger(message.seq) || message.seq < 1) continue;
      this.records.set(message.seq, message); this.cursor = Math.max(this.cursor, message.seq);
    }
    const sorted = [...this.records.keys()].sort((a, b) => a - b);
    for (const seq of sorted.slice(0, Math.max(0, sorted.length - this.limit))) this.records.delete(seq);
  }
  values(filter = '') { return [...this.records.values()].sort((a, b) => a.seq - b.seq).filter(message => !filter || message.messageId === filter); }
  clear() { this.records.clear(); } // Server seq survives clearing as well.
}
