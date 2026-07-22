/**
 * Pomelo IM Client SDK v0.3
 *
 * 特性：
 * - WebSocket 二进制线协议（与服务端 ImMessage 兼容）
 * - 双 codec 支持：JSON（默认）+ Protobuf（connect 时选 codec: 'protobuf'）
 * - 发送队列 + 指数退避重试（5s→10s→20s，最多 3 次）
 * - 消息状态追踪（pending → sending → sent → delivered → seen → failed）
 * - ACK 自动机（收到消息自动回 RECEIVED，200ms 批量聚合）
 * - 断线重连 + 自动 Pull 离线消息
 * - 心跳保持（30s Ping/Pong）
 *
 * 用法：
 *   const sdk = new ImSDK({ url: 'ws://localhost:9001' });
 *   await sdk.connect({ userId: 'alice', userName: 'Alice', codec: 'protobuf' });
 *   sdk.onMessage((msg) => console.log(msg));
 */
import { im as proto } from './proto.js';

class ImSDK {

  constructor(options) {
    this.url = options.url || 'ws://localhost:9001';
    this.userId = null;
    this.userName = null;
    this.token = null;
    this.codec = options.codec || 'json'; // 'json' | 'protobuf'

    // --- Connection ---
    this.ws = null;
    this.connected = false;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = options.maxReconnectAttempts || 10;
    this.reconnectDelay = 1000;
    this.maxReconnectDelay = 30000;
    this.heartbeatInterval = options.heartbeatInterval || 30000;
    this.heartbeatTimer = null;
    this.pongTimer = null;

    // --- Send Queue ---
    this.queue = [];
    this.pending = new Map();       // messageId → { msg, timer, retryCount }
    this.maxRetries = 3;
    this.baseTimeout = 5000;        // 首次超时 5s
    this.msgIdCounter = 0;

    // --- ACK Automaton ---
    this.receivedMessages = new Set();
    this.batchTimer = null;
    this.batchWindow = 200;         // 200ms 批量聚合

    // --- Last seq (for Pull) ---
    this.lastSeq = 0;

    // --- Event listeners ---
    this._listeners = {
      message: [],
      connect: [],
      disconnect: [],
      error: [],
      statusChange: [],
      friendRequest: [],
      friendAccepted: [],
      friendDeleted: [],
      searchResult: [],
    };

    // --- Protocol constants ---
    this.MAGIC = 0x504D454C; // "PMEL"
    this.VERSION = 1;
    this.CODEC_JSON = 1;
    this.CODEC_PROTOBUF = 0;

    this.CMD = {
      AUTH_REQ:   0x0001, AUTH_RESP:   0x0002,
      LOGOUT_REQ: 0x0003, LOGOUT_RESP: 0x0004,
      C2C_REQ:    0x0010, C2C_RESP:    0x0011, C2C_NOTIFY: 0x0012,
      PULL_REQ:   0x0030, PULL_RESP:   0x0031,
      PING:       0x0050, PONG:        0x0051,
      ACK_REQ:    0x0052, ACK_RESP:    0x0053, ACK_NOTIFY: 0x0054,
      FRIEND_SEARCH_REQ: 0x0060, FRIEND_SEARCH_RESP: 0x0061,
      FRIEND_ADD_REQ:    0x0062, FRIEND_ADD_RESP: 0x0063, FRIEND_ADD_NOTIFY: 0x0064,
      FRIEND_ACCEPT_REQ: 0x0065, FRIEND_ACCEPT_RESP: 0x0066, FRIEND_ACCEPT_NOTIFY: 0x0067,
      FRIEND_DELETE_REQ: 0x0068, FRIEND_DELETE_RESP: 0x0069, FRIEND_DELETE_NOTIFY: 0x006A,
    };

    this.AckType = { RECEIVED: 0, SEEN: 1 };
  }

  // ================================================================
  // Public API
  // ================================================================

  /** 连接 */
  async connect(auth) {
    this.userId = auth.userId;
    this.userName = auth.userName || auth.userId;
    this.token = auth.token || 'test-token';
    if (auth.codec === 'protobuf') this.codec = 'protobuf';

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
            this._pullOfflineMessages();
            this._resendPending();
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
        this.ws.onerror = (e) => {
          this._emit('error', e);
          reject(e);
        };
      } catch (e) { reject(e); }
    });
  }

  /** 断开连接 */
  disconnect() {
    this.maxReconnectAttempts = 0;
    this._stopHeartbeat();
    // 将 pending 中的消息标记为 failed
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      this.pending.delete(id);
      p.msg.status = 'failed';
      this._emit('statusChange', p.msg);
    }
    if (this.ws) { this.ws.close(); this.ws = null; }
    this.connected = false;
  }

  /** 发送消息，返回 messageId */
  sendMessage({ recipientId, msgType, content }) {
    if (!this.connected) throw new Error('Not connected');

    const msg = {
      id: this._snowflakeId(),
      recipientId,
      msgType: msgType || 1,
      content,
      status: 'pending',
      createdAt: Date.now(),
      retryCount: 0,
    };

    this.queue.push(msg);
    this._flush();
    return msg.id;
  }

  /** 标记已读 */
  markSeen(messageIds) {
    if (!this.connected) return;
    if (!Array.isArray(messageIds)) messageIds = [messageIds];
    this._sendAck(messageIds, this.AckType.SEEN);
  }

  /** 搜索用户 */
  searchUsers(keyword) {
    if (!this.connected) throw new Error('Not connected');
    const buf = this._encodeMessage(this.CMD.FRIEND_SEARCH_REQ, 'search-' + Date.now(), { keyword });
    this.ws.send(buf);
  }

  /** 添加好友 */
  addFriend(friendId) {
    if (!this.connected) throw new Error('Not connected');
    const body = { userId: this.userId, friendId };
    const buf = this._encodeMessage(this.CMD.FRIEND_ADD_REQ, 'add-' + Date.now(), body);
    this.ws.send(buf);
  }

  /** 接受好友申请 */
  acceptFriend(friendId) {
    if (!this.connected) throw new Error('Not connected');
    const body = { userId: this.userId, friendId };
    const buf = this._encodeMessage(this.CMD.FRIEND_ACCEPT_REQ, 'accept-' + Date.now(), body);
    this.ws.send(buf);
  }

  /** 删除好友 */
  deleteFriend(friendId) {
    if (!this.connected) throw new Error('Not connected');
    const body = { userId: this.userId, friendId };
    const buf = this._encodeMessage(this.CMD.FRIEND_DELETE_REQ, 'delete-' + Date.now(), body);
    this.ws.send(buf);
  }

  /** 注册事件监听 */
  on(event, fn) {
    if (this._listeners[event]) this._listeners[event].push(fn);
    return this;
  }

  /** 新消息回调（SDK 已自动回 RECEIVED） */
  onMessage(fn) { return this.on('message', fn); }

  /** 消息状态变化回调 */
  onStatusChange(fn) { return this.on('statusChange', fn); }

  /** 收到好友申请 */
  onFriendRequest(fn) { return this.on('friendRequest', fn); }

  /** 好友申请被接受 */
  onFriendAccepted(fn) { return this.on('friendAccepted', fn); }

  /** 被好友删除 */
  onFriendDeleted(fn) { return this.on('friendDeleted', fn); }

  /** 搜索结果回调 */
  onSearchResult(fn) { return this.on('searchResult', fn); }

  // ================================================================
  // Send Queue (client-side)
  // ================================================================

  _flush() {
    while (this.queue.length > 0) {
      const msg = this.queue.shift();
      this._send(msg);
    }
  }

  _send(msg) {
    msg.status = 'sending';
    this.pending.set(String(msg.id), msg);

    const body = {
      messageId: msg.id,
      senderId: this.userId,
      recipientId: msg.recipientId,
      message: { msgType: msg.msgType, content: msg.content }
    };

    const buf = this._encodeMessage(this.CMD.C2C_REQ, String(msg.id), body);
    this.ws.send(buf);

    this._startSendTimer(msg);
    this._emit('statusChange', msg);
  }

  _startSendTimer(msg) {
    clearTimeout(msg.timer);
    const delay = this.baseTimeout * Math.pow(2, msg.retryCount);
    msg.timer = setTimeout(() => {
      if (msg.retryCount < this.maxRetries) {
        msg.retryCount++;
        console.log(`[ImSDK] 重试发送 msgId=${msg.id} (第 ${msg.retryCount} 次)`);
        this._send(msg);
      } else {
        this.pending.delete(msg.id);
        msg.status = 'failed';
        console.warn(`[ImSDK] 发送失败 msgId=${msg.id}，已重试 ${this.maxRetries} 次`);
        this._emit('statusChange', msg);
      }
    }, delay);
  }

  /** 收到 C2CResp → 标记 sent */
  _onC2CResp(messageId) {
    const msg = this.pending.get(messageId);
    if (msg) {
      clearTimeout(msg.timer);
      this.pending.delete(messageId);
      msg.status = 'sent';
      this._emit('statusChange', msg);
    }
  }

  /** 收到 AckNotify → 更新 delivered/seen */
  _onAckNotify(messageIds, ackType) {
    for (const id of messageIds) {
      const msg = this.pending.get(String(id));
      if (!msg) continue;
      if (ackType === this.AckType.SEEN) {
        msg.status = 'seen';
      } else if (msg.status === 'sent') {
        msg.status = 'delivered';
      }
      this._emit('statusChange', msg);
    }
  }

  /** 重连后重发 pending */
  _resendPending() {
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      p.msg.retryCount = 0;
      this._send(p.msg);
    }
  }

  // ================================================================
  // ACK Automaton
  // ================================================================

  /** 收到 C2CNotify → 归一化消息格式 → 排队回 RECEIVED */
  _onMessageReceived(raw) {
    // 归一化：服务端 JSON 格式为 {senderId, recipientId, seq, message:{msgType,content}, id, createdAt}
    // 统一转为 {id, senderId, recipientId, msgType, content, seq, createdAt}
    const msg = {
      id: raw.id,
      senderId: raw.senderId,
      recipientId: raw.recipientId,
      conversationId: raw.conversationId,
      msgType: raw.message ? raw.message.msgType : raw.msgType,
      content: raw.message ? raw.message.content : raw.content,
      seq: raw.seq,
      createdAt: raw.createdAt,
    };

    this.receivedMessages.add(msg.id);
    if (msg.seq > this.lastSeq) this.lastSeq = msg.seq;
    this._scheduleBatchAck();
    this._emit('message', msg);
  }

  _scheduleBatchAck() {
    if (this.batchTimer) return;
    this.batchTimer = setTimeout(() => {
      const ids = Array.from(this.receivedMessages);
      this.receivedMessages.clear();
      this.batchTimer = null;
      if (ids.length > 0) {
        this._sendAck(ids, this.AckType.RECEIVED);
      }
    }, this.batchWindow);
  }

  _sendAck(messageIds, ackType) {
    const body = {
      messageIds: messageIds.map(Number),
      ackType: ackType
    };
    const buf = this._encodeMessage(this.CMD.ACK_REQ, 'ack-' + Date.now(), body);
    this.ws.send(buf);
    console.log(`[ImSDK] ACK: ${messageIds.length} msgs, type=${ackType === 0 ? 'RECEIVED' : 'SEEN'}`);
  }

  // ================================================================
  // Pull Offline Messages
  // ================================================================

  _pullOfflineMessages() {
    if (!this.connected) return;
    const body = {
      userId: this.userId,
      lastMsgId: this.lastSeq,
      limit: 50
    };
    const buf = this._encodeMessage(this.CMD.PULL_REQ, 'pull-' + Date.now(), body);
    this.ws.send(buf);
    console.log(`[ImSDK] Pull 离线消息 sinceSeq=${this.lastSeq}`);
  }

  _onPullResp(body) {
    // body.messages might be a list of MessageRecord or similar
    // Update lastSeq if needed
    console.log(`[ImSDK] Pull 响应: hasMore=${body.hasMore}, code=${body.code}`);
  }

  // ================================================================
  // Connection & Heartbeat
  // ================================================================

  async _authenticate() {
    const body = {
      token: this.token,
      userId: this.userId,
      userName: this.userName,
      deviceId: this.deviceId || 'web',
      platform: this.platform || 'web',
      appVersion: this.appVersion || '1.0.0'
    };
    const buf = this._encodeMessage(this.CMD.AUTH_REQ, 'auth-' + Date.now(), body);
    this.ws.send(buf);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.connected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        const buf = this._encodeMessage(this.CMD.PING, 'ping-' + Date.now(), null);
        this.ws.send(buf);
      }
    }, this.heartbeatInterval);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) { clearInterval(this.heartbeatTimer); this.heartbeatTimer = null; }
    if (this.pongTimer) { clearTimeout(this.pongTimer); this.pongTimer = null; }
  }

  async _tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.warn('[ImSDK] 重连次数已达上限');
      return;
    }
    const delay = Math.min(this.reconnectDelay * Math.pow(2, this.reconnectAttempts), this.maxReconnectDelay);
    this.reconnectAttempts++;
    console.log(`[ImSDK] 重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts}) 等待 ${delay}ms...`);
    await new Promise(r => setTimeout(r, delay));
    try {
      await this.connect({ userId: this.userId, token: this.token });
    } catch (e) {
      // onclose triggers retry
    }
  }

  // ================================================================
  // Wire Protocol Encode/Decode
  // ================================================================

  _encodeMessage(cmd, messageId, body) {
    const isPb = this.codec === 'protobuf';
    const te = new TextEncoder();

    // Encode body
    let bodyBytes;
    if (body == null) {
      bodyBytes = new Uint8Array(0);
    } else if (isPb) {
      const ProtoType = this._getProtoType(cmd);
      if (ProtoType) {
        const err = ProtoType.verify(body);
        if (err) throw new Error('Proto verify failed for cmd 0x' + cmd.toString(16) + ': ' + err);
        bodyBytes = ProtoType.encode(ProtoType.create(body)).finish();
      } else {
        bodyBytes = te.encode(JSON.stringify(body)); // fallback
      }
    } else {
      bodyBytes = te.encode(JSON.stringify(body));
    }

    const msgIdBytes = te.encode(messageId);
    const hdrEntries = this.userId ? { userId: this.userId } : {};

    let hdrSize = 4;
    for (const [k, v] of Object.entries(hdrEntries)) {
      hdrSize += 4 + te.encode(k).length + 4 + te.encode(v).length;
    }

    const size = 4 + 1 + 1 + 4 + 4 + msgIdBytes.length + hdrSize + 4 + bodyBytes.length;
    const buf = new ArrayBuffer(size + 4);
    const v = new DataView(buf);
    const bytes = new Uint8Array(buf);
    let p = 0;

    v.setInt32(p, size, false); p += 4;
    v.setInt32(p, this.MAGIC, false); p += 4;
    v.setUint8(p, this.VERSION); p += 1;
    v.setUint8(p, isPb ? this.CODEC_PROTOBUF : this.CODEC_JSON); p += 1;
    v.setInt32(p, cmd, false); p += 4;

    v.setInt32(p, msgIdBytes.length, false); p += 4;
    bytes.set(msgIdBytes, p); p += msgIdBytes.length;

    const hdrKeys = Object.entries(hdrEntries);
    v.setInt32(p, hdrKeys.length, false); p += 4;
    for (const [k, val] of hdrKeys) {
      const kb = te.encode(k), vb = te.encode(val);
      v.setInt32(p, kb.length, false); p += 4; bytes.set(kb, p); p += kb.length;
      v.setInt32(p, vb.length, false); p += 4; bytes.set(vb, p); p += vb.length;
    }

    v.setInt32(p, bodyBytes.length, false); p += 4;
    bytes.set(bodyBytes, p);

    return buf;
  }

  _decodeMessage(buf) {
    const v = new DataView(buf);
    const td = new TextDecoder();
    let p = 4;

    const magic = v.getInt32(p, false); p += 4;
    if (magic !== this.MAGIC) throw new Error('Invalid magic: ' + magic.toString(16));
    const version = v.getUint8(p); p += 1;
    const codecId = v.getUint8(p); p += 1;
    const cmd = v.getInt32(p, false); p += 4;

    const msgIdLen = v.getInt32(p, false); p += 4;
    let messageId = '';
    if (msgIdLen > 0) {
      messageId = td.decode(new Uint8Array(buf, p, msgIdLen));
      p += msgIdLen;
    }

    // skip varHeaders
    const hdrCnt = v.getInt32(p, false); p += 4;
    for (let i = 0; i < hdrCnt; i++) {
      const kl = v.getInt32(p, false); p += 4 + kl;
      const vl = v.getInt32(p, false); p += 4 + vl;
    }

    const bodyLen = v.getInt32(p, false); p += 4;
    let body = null;
    if (bodyLen > 0) {
      const raw = new Uint8Array(buf, p, bodyLen);
      if (codecId === this.CODEC_PROTOBUF) {
        const ProtoType = this._getProtoType(cmd);
        if (ProtoType) {
          try { body = ProtoType.toObject(ProtoType.decode(raw), { longs: Number, enums: String }); } catch (e) { body = null; }
        }
      } else {
        try { body = JSON.parse(td.decode(raw)); } catch (e) { body = td.decode(raw); }
      }
    }

    return { cmd, messageId, body };
  }

  _onMessage(event) {
    try {
      const msg = this._decodeMessage(event.data);
      switch (msg.cmd) {
        case this.CMD.C2C_NOTIFY:
          this._onMessageReceived(msg.body || { id: msg.messageId });
          break;

        case this.CMD.C2C_RESP:
          this._onC2CResp(msg.messageId);
          break;

        case this.CMD.ACK_NOTIFY:
          if (msg.body && msg.body.messageIds) {
            this._onAckNotify(msg.body.messageIds, msg.body.ackType);
          }
          break;

        case this.CMD.PULL_RESP:
          this._onPullResp(msg.body || {});
          break;

        case this.CMD.AUTH_RESP:
          if (msg.body && msg.body.userId) this.userId = msg.body.userId;
          if (msg.body && msg.body.userName) this.userName = msg.body.userName;
          console.log('[ImSDK] 认证成功 userId=' + this.userId);
          break;

        case this.CMD.ACK_RESP:
          // ACK 已处理
          break;

        case this.CMD.PONG:
          break;

        case this.CMD.FRIEND_SEARCH_RESP:
          this._emit('searchResult', msg.body);
          break;

        case this.CMD.FRIEND_ADD_RESP:
          console.log('[ImSDK] 好友申请已发送:', msg.body);
          break;

        case this.CMD.FRIEND_ADD_NOTIFY:
          this._emit('friendRequest', msg.body);
          break;

        case this.CMD.FRIEND_ACCEPT_RESP:
          console.log('[ImSDK] 好友已添加:', msg.body);
          break;

        case this.CMD.FRIEND_ACCEPT_NOTIFY:
          this._emit('friendAccepted', msg.body);
          break;

        case this.CMD.FRIEND_DELETE_RESP:
          console.log('[ImSDK] 好友已删除:', msg.body);
          break;

        case this.CMD.FRIEND_DELETE_NOTIFY:
          this._emit('friendDeleted', msg.body);
          break;

        default:
          console.log('[ImSDK] Unhandled cmd:', '0x' + msg.cmd.toString(16), msg.body);
      }
    } catch (e) {
      console.error('[ImSDK] Decode error:', e);
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  _snowflakeId() {
    const now = Date.now();
    const seq = ++this.msgIdCounter;
    return now * 1000 + (seq % 1000);
  }

  _emit(event, ...args) {
    if (this._listeners[event]) {
      this._listeners[event].forEach(fn => {
        try { fn(...args); } catch (e) { console.error('[ImSDK] Event handler error:', e); }
      });
    }
  }

  /** cmd → Protobuf type mapping */
  _getProtoType(cmd) {
    const P = proto;
    switch (cmd) {
      case this.CMD.AUTH_REQ:          return P.auth.AuthReq;
      case this.CMD.AUTH_RESP:         return P.auth.AuthResp;
      case this.CMD.LOGOUT_REQ:        return P.auth.LogoutReq;
      case this.CMD.LOGOUT_RESP:       return P.auth.LogoutResp;
      case this.CMD.C2C_REQ:           return P.chat.C2CReq;
      case this.CMD.C2C_RESP:          return P.chat.C2CResp;
      case this.CMD.C2C_NOTIFY:        return P.chat.C2CNotify;
      case this.CMD.PULL_REQ:          return P.pull.PullReq;
      case this.CMD.PULL_RESP:         return P.pull.PullResp;
      case this.CMD.ACK_REQ:           return P.ack.AckReq;
      case this.CMD.ACK_RESP:          return P.ack.AckResp;
      case this.CMD.ACK_NOTIFY:        return P.ack.AckNotify;
      case this.CMD.PING:              return P.heartbeat.Ping;
      case this.CMD.PONG:              return P.heartbeat.Pong;
      case this.CMD.FRIEND_SEARCH_REQ: return P.relation.SearchUserReq;
      case this.CMD.FRIEND_SEARCH_RESP:return P.relation.SearchUserResp;
      case this.CMD.FRIEND_ADD_REQ:    return P.relation.FriendAddReq;
      case this.CMD.FRIEND_ADD_RESP:   return P.relation.FriendAddResp;
      case this.CMD.FRIEND_ADD_NOTIFY: return P.relation.FriendAddNotify;
      case this.CMD.FRIEND_ACCEPT_REQ: return P.relation.FriendAcceptReq;
      case this.CMD.FRIEND_ACCEPT_RESP:return P.relation.FriendAcceptResp;
      case this.CMD.FRIEND_ACCEPT_NOTIFY:return P.relation.FriendAcceptNotify;
      case this.CMD.FRIEND_DELETE_REQ: return P.relation.FriendDeleteReq;
      case this.CMD.FRIEND_DELETE_RESP:return P.relation.FriendDeleteResp;
      case this.CMD.FRIEND_DELETE_NOTIFY:return P.relation.FriendDeleteNotify;
      default: return null;
    }
  }
}

// Export
export { ImSDK };
