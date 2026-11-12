/**
 * A minimal Chrome DevTools Protocol client over one browser-level WebSocket.
 *
 * Page commands go through a flattened session (`Target.attachToTarget {flatten: true}`), so the
 * browser and the page share this one socket and are told apart by `sessionId`. Only what
 * `qeploy share` needs: request ids, results, errors, and events.
 */
import WebSocket from 'ws';

export class CdpError extends Error {
  constructor(method, error) {
    super(`${method}: ${error?.message ?? 'CDP 오류'}`);
    this.name = 'CdpError';
    this.code = error?.code;
  }
}

export class CdpConnection {
  /**
   * @param {string} url the `ws://127.0.0.1:<port>/devtools/browser/<id>` Chrome printed
   */
  static connect(url, { WebSocketImpl = WebSocket, timeoutMs = 15000 } = {}) {
    return new Promise((resolve, reject) => {
      // Screencast frames arrive base64-encoded inside JSON, so a large page is a large message.
      const socket = new WebSocketImpl(url, {
        perMessageDeflate: false,
        maxPayload: 256 * 1024 * 1024,
        handshakeTimeout: timeoutMs,
      });
      const onError = (e) => reject(new Error(`Chrome DevTools 에 연결하지 못했습니다: ${e.message}`));
      socket.once('error', onError);
      socket.once('open', () => {
        socket.off('error', onError);
        resolve(new CdpConnection(socket));
      });
    });
  }

  constructor(socket) {
    this.socket = socket;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.closeListeners = [];
    this.closed = false;
    socket.on('message', (data) => this.onMessage(data));
    socket.on('close', () => this.onClose());
    // Without a listener an 'error' would throw; the close that follows is what callers act on.
    socket.on('error', () => {});
  }

  /** @returns {Promise<object>} the command's `result` */
  send(method, params = {}, sessionId) {
    if (this.closed) return Promise.reject(new Error(`CDP 연결이 닫혀 ${method} 를 보낼 수 없습니다.`));
    const id = this.nextId++;
    const message = JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) });
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.socket.send(message, (e) => {
        if (!e) return;
        this.pending.delete(id);
        reject(e);
      });
    });
  }

  /**
   * @param {(params: object, sessionId?: string) => void} listener
   * @returns {() => void} unsubscribe
   */
  on(method, listener) {
    if (!this.listeners.has(method)) this.listeners.set(method, new Set());
    this.listeners.get(method).add(listener);
    return () => this.listeners.get(method)?.delete(listener);
  }

  onDisconnect(listener) {
    this.closeListeners.push(listener);
  }

  onMessage(data) {
    let message;
    try {
      message = JSON.parse(String(data));
    } catch {
      return;
    }
    if (message.id !== undefined) {
      const call = this.pending.get(message.id);
      if (!call) return;
      this.pending.delete(message.id);
      if (message.error) call.reject(new CdpError(call.method, message.error));
      else call.resolve(message.result ?? {});
      return;
    }
    if (typeof message.method !== 'string') return;
    for (const listener of this.listeners.get(message.method) ?? []) {
      try {
        listener(message.params ?? {}, message.sessionId);
      } catch {
        // One bad handler must not stop the others from seeing the event.
      }
    }
  }

  onClose() {
    if (this.closed) return;
    this.closed = true;
    for (const { reject, method } of this.pending.values()) {
      reject(new Error(`CDP 연결이 닫혀 ${method} 가 끝나지 못했습니다.`));
    }
    this.pending.clear();
    for (const listener of this.closeListeners) listener();
  }

  close() {
    try {
      this.socket.close();
    } catch {
      // already closing
    }
  }
}

/** Commands and events of the browser target itself (no session). */
export function browserScope(conn) {
  return {
    send: (method, params) => conn.send(method, params),
    on: (method, fn) => conn.on(method, (params, sessionId) => {
      if (!sessionId) fn(params);
    }),
  };
}

/** Commands and events of one attached page. */
export function sessionScope(conn, sessionId) {
  return {
    send: (method, params) => conn.send(method, params, sessionId),
    on: (method, fn) => conn.on(method, (params, sid) => {
      if (sid === sessionId) fn(params);
    }),
  };
}

/**
 * Attaches to the tab Chrome started with (a fresh profile opens exactly one), or opens one.
 *
 * @returns {Promise<{targetId: string, browser: object, page: object}>}
 */
export async function attachFirstPage(conn) {
  const { targetInfos = [] } = await conn.send('Target.getTargets');
  let targetId = targetInfos.find((t) => t.type === 'page')?.targetId;
  if (!targetId) {
    ({ targetId } = await conn.send('Target.createTarget', { url: 'about:blank' }));
  }
  const { sessionId } = await conn.send('Target.attachToTarget', { targetId, flatten: true });
  return { targetId, browser: browserScope(conn), page: sessionScope(conn, sessionId) };
}
