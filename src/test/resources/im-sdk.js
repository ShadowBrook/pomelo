/**
 * Pomelo IM Client SDK v0.1
 *
 * 特性：
 * - WebSocket 二进制线协议（与服务端 ImMessage 兼容）
 * - 自动重连（指数退避，最大 30s 间隔）
 * - 心跳保持（30s 间隔 Ping/Pong）
 * - 事件驱动 API（onMessage / onConnect / onDisconnect / onError）
 * - 多消息类型（文本、图片、视频、文件链接）
 *
 * 用法：
 *   const client = new ImClient({
 *     url: 'ws://localhost:9001',
 *     userId: 'user1',
 *     token: 'test-token',
 *   });
 *   client.on('message', (msg) => console.log(msg));
 *   await client.connect();
 *   await client.sendText('user2', 'Hello!');
 */
class ImClient {
  constructor(options) {
    this.url = options.url || 'ws://localhost:9001';
    this.userId = options.userId || 'anonymous';
    this.token = options.token || 'test-token';
    this.deviceId = options.deviceId || 'web';
    this.platform = options.platform || 'web';
    this.appVersion = options.appVersion || '1.0.0';

    // Internal
    this.ws = null;
    this.connected = false;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = options.maxReconnectAttempts || 10;
    this.reconnectDelay = 1000;
    this.heartbeatInterval = options.heartbeatInterval || 30000;
    this.heartbeatTimer = null;
    this.msgIdCounter = 0;

    // Event listeners
    this._listeners = { message: [], connect: [], disconnect: [], error: [] };

    // Protocol constants
    this.MAGIC = 0x504D454C;
    this.VERSION = 1;
    this.CODEC_JSON = 1;
    this.CODEC_PROTOBUF = 0;

    // Cmd
    this.CMD = {
      AUTH_REQ:  0x0001, AUTH_RESP:  0x0002,
      LOGOUT_REQ: 0x0003, LOGOUT_RESP: 0x0004,
      C2C_REQ:    0x0010, C2C_RESP:    0x0011, C2C_NOTIFY: 0x0012,
      C2G_REQ:    0x0020, C2G_RESP:    0x0021, C2G_NOTIFY: 0x0022,
      PULL_REQ:   0x0030, PULL_RESP:   0x0031,
      CTRL_REQ:   0x0040, CTRL_RESP:   0x0041, CTRL_NOTIFY:0x0042,
      PING:       0x0050, PONG:       0x0051,
      ACK_REQ:    0x0052, ACK_RESP:    0x0053, ACK_NOTIFY: 0x0054,
    };
  }

  // ---- Events ----
  on(event, fn) { this._listeners[event]?.push(fn); return this; }
  _emit(event, ...args) { this._listeners[event]?.forEach(f => f(...args)); }

  // ---- Connection ----
  async connect() {
    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(this.url);
        this.ws.binaryType = 'arraybuffer';

        this.ws.onopen = async () => {
          try {
            await this._authenticate();
            this.connected = true;
            this.reconnectAttempts = 0;
            this._startHeartbeat();
            this._emit('connect');
            resolve();
          } catch (e) { reject(e); }
        };

        this.ws.onmessage = (e) => this._onMessage(e);
        this.ws.onclose = (e) => {
          this.connected = false;
          this._stopHeartbeat();
          this._emit('disconnect', { code: e.code, reason: e.reason });
          this._tryReconnect();
        };
        this.ws.onerror = (e) => { this._emit('error', e); reject(e); };
      } catch (e) { reject(e); }
    });
  }

  disconnect() {
    this.maxReconnectAttempts = 0;
    this._stopHeartbeat();
    if (this.ws) { this.ws.close(); this.ws = null; }
    this.connected = false;
  }

  // ---- Send Messages ----
  sendText(recipientId, content) {
    return this._sendC2C(recipientId, 1, content);
  }

  sendImage(recipientId, url) {
    return this._sendC2C(recipientId, 2, url);
  }

  sendVoice(recipientId, url) {
    return this._sendC2C(recipientId, 3, url);
  }

  sendVideo(recipientId, url) {
    return this._sendC2C(recipientId, 4, url);
  }

  sendFile(recipientId, url) {
    return this._sendC2C(recipientId, 5, url);
  }

  _sendC2C(recipientId, msgType, content) {
    if (!this.connected) throw new Error('Not connected');
    const msgId = this._nextMsgId();
    const body = { senderId: this.userId, recipientId, msgType, content };
    const buf = this._encodeMessage(this.CMD.C2C_REQ, msgId, body);
    this.ws.send(buf);
    return msgId;
  }

  logout() {
    if (!this.connected) return;
    const body = { userId: this.userId };
    const buf = this._encodeMessage(this.CMD.LOGOUT_REQ, this._nextMsgId(), body);
    this.ws.send(buf);
    this.disconnect();
  }

  // ---- Internal ----
  async _authenticate() {
    const body = {
      token: this.token,
      userId: this.userId,
      deviceId: this.deviceId,
      platform: this.platform,
      appVersion: this.appVersion
    };
    const buf = this._encodeMessage(this.CMD.AUTH_REQ, 'auth-' + Date.now(), body);
    this.ws.send(buf);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.connected) {
        const buf = this._encodeMessage(this.CMD.PING, 'ping-' + Date.now(), null);
        this.ws.send(buf);
      }
    }, this.heartbeatInterval);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) { clearInterval(this.heartbeatTimer); this.heartbeatTimer = null; }
  }

  async _tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) return;
    const delay = Math.min(this.reconnectDelay * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectAttempts++;
    console.log(`[ImSDK] 重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts}) 等待 ${delay}ms...`);
    await new Promise(r => setTimeout(r, delay));
    try { await this.connect(); } catch (e) { /* onclose triggers retry */ }
  }

  _nextMsgId() { return Date.now().toString() + '-' + (++this.msgIdCounter); }

  // ---- Wire Protocol Encode ----
  _encodeMessage(cmd, messageId, body) {
    const te = new TextEncoder();
    const bodyJson = body ? te.encode(JSON.stringify(body)) : new Uint8Array(0);
    const msgIdBytes = te.encode(messageId);

    const size = 4 + 1 + 1 + 4 + 4 + msgIdBytes.length + 4 + 4 + bodyJson.length;
    const buf = new ArrayBuffer(size + 4);
    const v = new DataView(buf);
    let p = 0;

    v.setInt32(p, size, false); p += 4;          // total length
    v.setInt32(p, this.MAGIC, false); p += 4;     // magic "PMEL"
    v.setUint8(p, this.VERSION); p += 1;          // version
    v.setUint8(p, this.CODEC_JSON); p += 1;       // codecId=JSON
    v.setInt32(p, cmd, false); p += 4;            // cmd
    v.setInt32(p, msgIdBytes.length, false); p += 4; // msgId length
    new Uint8Array(buf).set(msgIdBytes, p); p += msgIdBytes.length;
    v.setInt32(p, 0, false); p += 4;              // headers count
    v.setInt32(p, bodyJson.length, false); p += 4;// body length
    new Uint8Array(buf).set(bodyJson, p);

    return buf;
  }

  // ---- Wire Protocol Decode ----
  _decodeMessage(buf) {
    const v = new DataView(buf);
    let p = 4; // skip total length

    const magic = v.getInt32(p, false); p += 4;
    if (magic !== this.MAGIC) throw new Error('Invalid magic: ' + magic.toString(16));
    const version = v.getUint8(p); p += 1;
    const codecId = v.getUint8(p); p += 1;
    const cmd = v.getInt32(p, false); p += 4;

    const msgIdLen = v.getInt32(p, false); p += 4;
    let messageId = '';
    if (msgIdLen > 0) {
      messageId = new TextDecoder().decode(new Uint8Array(buf, p, msgIdLen));
      p += msgIdLen;
    }

    const hdrCnt = v.getInt32(p, false); p += 4;
    for (let i = 0; i < hdrCnt; i++) {
      const kl = v.getInt32(p, false); p += 4 + kl;
      const vl = v.getInt32(p, false); p += 4 + vl;
    }

    const bodyLen = v.getInt32(p, false); p += 4;
    let body = null;
    if (bodyLen > 0) {
      body = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, p, bodyLen)));
    }

    return { cmd, messageId, body };
  }

  _onMessage(event) {
    try {
      const msg = this._decodeMessage(event.data);
      switch (msg.cmd) {
        case this.CMD.C2C_NOTIFY:
          this._emit('message', msg.body);
          break;
        case this.CMD.C2C_RESP:
          // Ack from server — logged silently
          break;
        case this.CMD.AUTH_RESP:
          // Auth response — logged silently
          break;
        case this.CMD.PONG:
          // Heartbeat response
          break;
        default:
          console.log('[ImSDK] Unhandled cmd:', msg.cmd.toString(16), msg.body);
      }
    } catch (e) {
      console.error('[ImSDK] Decode error:', e);
    }
  }
}

// Export for module usage
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { ImClient };
}
