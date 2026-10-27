// Shared by the seat map and the admin page: the access token, JSON calls to the API, the login
// box, small DOM helpers and a minimal STOMP client for the live seat updates.
'use strict';

const TOKEN_KEY = 'seatrush.token';

/** sessionStorage is per tab, so two tabs can be two different users side by side. */
const Session = {
  get token() {
    try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set token(value) {
    try {
      if (value) sessionStorage.setItem(TOKEN_KEY, value);
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch { /* storage blocked: the user simply has to log in again */ }
  },
  read(key) {
    try { return sessionStorage.getItem(key); } catch { return null; }
  },
  write(key, value) {
    try {
      if (value == null) sessionStorage.removeItem(key);
      else sessionStorage.setItem(key, value);
    } catch { /* ignore */ }
  },
};

class ApiError extends Error {
  constructor(status, problem) {
    super((problem && (problem.detail || problem.title)) || `Request failed (HTTP ${status})`);
    this.status = status;
    this.problem = problem || {};
  }
}

/** Calls the API with the token, and turns a ProblemDetail answer into an ApiError. */
async function api(method, path, body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = Session.token;
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }

  if (!response.ok) {
    if (response.status === 401 && token) {
      Session.token = null;
      document.dispatchEvent(new CustomEvent('seatrush:logged-out'));
      throw new ApiError(401, { detail: 'Your session has expired, please log in again' });
    }
    if (response.status === 403) {
      throw new ApiError(403, { detail: 'You are not allowed to do that' });
    }
    throw new ApiError(response.status, data);
  }
  return data;
}

/** The server keeps one hold per (user, key): a retried request with the same key gets the same hold. */
function newIdempotencyKey() {
  if (crypto.randomUUID) return crypto.randomUUID();
  // randomUUID needs a secure context; plain http on a LAN address does not have one
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** document.createElement with attributes, listeners and children. Text is never parsed as HTML. */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  node.append(...children.flat().filter((child) => child != null && child !== false));
  return node;
}

function toast(message, kind = 'info') {
  const item = el('div', { class: `toast toast-${kind}`, role: kind === 'error' ? 'alert' : 'status' }, message);
  document.getElementById('toasts').append(item);
  setTimeout(() => item.classList.add('leaving'), 4200);
  setTimeout(() => item.remove(), 4600);
}

/** Rows are letters while there are few enough of them, like in most venues. */
function rowName(row) {
  return row <= 26 ? String.fromCharCode(64 + row) : `R${row}`;
}

function seatName(seat) {
  return `${rowName(seat.row)}${seat.seat}`;
}

function formatDate(iso) {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function countStatuses(seats) {
  const counts = { AVAILABLE: 0, HELD: 0, SOLD: 0 };
  for (const seat of seats) counts[seat.status] += 1;
  return counts;
}

/**
 * The login box in the top bar. Calls onChange(me) after login and onChange(null) after logout,
 * where me is the answer of GET /api/me: { userId, username, roles }.
 */
class AuthBox {
  constructor(container, onChange) {
    this.container = container;
    this.onChange = onChange;
    this.me = null;
    document.addEventListener('seatrush:logged-out', () => this.setMe(null));
  }

  async start() {
    if (Session.token) {
      try {
        this.setMe(await api('GET', '/api/me'));
        return;
      } catch {
        Session.token = null;
      }
    }
    this.setMe(null);
  }

  setMe(me) {
    this.me = me;
    this.render();
    this.onChange(me);
  }

  isAdmin() {
    return Boolean(this.me && this.me.roles && this.me.roles.includes('ADMIN'));
  }

  render() {
    this.container.replaceChildren();
    if (this.me) {
      this.container.append(
        el('span', { class: 'who' }, 'Signed in as ', el('strong', {}, this.me.username)),
        el('button', { type: 'button', class: 'btn btn-quiet', onclick: () => this.logout() }, 'Log out'),
      );
      return;
    }
    const username = el('input', { name: 'username', placeholder: 'Username', autocomplete: 'username', required: true });
    const password = el('input', { name: 'password', type: 'password', placeholder: 'Password', autocomplete: 'current-password', required: true });
    const form = el('form', { class: 'login', onsubmit: (e) => { e.preventDefault(); this.login(username.value, password.value); } },
      username, password,
      el('button', { type: 'submit', class: 'btn btn-primary' }, 'Log in'),
      el('button', { type: 'button', class: 'btn btn-quiet', onclick: () => this.register(username.value, password.value) }, 'Create account'),
    );
    this.container.append(form);
  }

  async login(username, password) {
    try {
      const token = await api('POST', '/api/auth/login', { username, password });
      Session.token = token.accessToken;
      this.setMe(await api('GET', '/api/me'));
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async register(username, password) {
    if (!username || password.length < 8) {
      toast('Pick a username (letters, digits, _) and a password of at least 8 characters', 'error');
      return;
    }
    try {
      await api('POST', '/api/auth/register', { username, password });
      toast(`Account ${username} created`, 'success');
      await this.login(username, password);
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  logout() {
    Session.token = null;
    this.setMe(null);
  }
}

/**
 * Just enough STOMP 1.2 over a plain WebSocket to listen to seat topics: CONNECT, SUBSCRIBE,
 * UNSUBSCRIBE and MESSAGE. It reconnects by itself, and onConnected runs after every (re)connect
 * so the page can reload whatever it missed while the connection was down.
 */
class SeatFeed {
  constructor({ onChange, onConnected, onStatus }) {
    this.onChange = onChange;
    this.onConnected = onConnected || (() => {});
    this.onStatus = onStatus || (() => {});
    this.eventIds = new Set();
    this.socket = null;
    this.connected = false;
    this.retryDelay = 1000;
  }

  /** Listens to exactly these events from now on. */
  watch(eventIds) {
    const next = new Set(eventIds);
    if (this.connected) {
      for (const id of this.eventIds) if (!next.has(id)) this.send('UNSUBSCRIBE', { id: `seats-${id}` });
      for (const id of next) if (!this.eventIds.has(id)) this.subscribe(id);
    }
    this.eventIds = next;
    if (!this.socket) this.open();
  }

  open() {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${scheme}//${location.host}/ws`);
    this.socket = socket;
    socket.onopen = () => this.send('CONNECT', { 'accept-version': '1.2', host: location.hostname, 'heart-beat': '0,0' });
    socket.onmessage = (message) => this.receive(message.data);
    socket.onclose = () => {
      this.socket = null;
      this.connected = false;
      this.onStatus(false);
      setTimeout(() => this.open(), this.retryDelay);
      this.retryDelay = Math.min(this.retryDelay * 2, 15000);
    };
  }

  receive(data) {
    const headerEnd = data.indexOf('\n\n');
    if (headerEnd < 0) return; // heartbeat
    const lines = data.slice(0, headerEnd).split('\n');
    const command = lines[0].trim();
    const body = data.slice(headerEnd + 2).replace(/\0[\s\S]*$/, '');

    if (command === 'CONNECTED') {
      this.connected = true;
      this.retryDelay = 1000;
      for (const id of this.eventIds) this.subscribe(id);
      this.onStatus(true);
      this.onConnected();
    } else if (command === 'MESSAGE') {
      this.onChange(JSON.parse(body));
    } else if (command === 'ERROR') {
      console.warn('STOMP error from server', lines.slice(1).join(', '), body);
    }
  }

  subscribe(eventId) {
    this.send('SUBSCRIBE', { id: `seats-${eventId}`, destination: `/topic/events/${eventId}/seats` });
  }

  send(command, headers) {
    const frame = [command, ...Object.entries(headers).map(([key, value]) => `${key}:${value}`)].join('\n');
    this.socket.send(`${frame}\n\n\0`);
  }
}

/** Shows the connection state of a SeatFeed in the top bar. */
function showLive(element, connected) {
  element.dataset.state = connected ? 'on' : 'off';
  element.textContent = connected ? 'Live' : 'Reconnecting…';
}
