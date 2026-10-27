// The admin page: switch the seat lock strategy, create events, and watch every event's seat
// counts change live (for example while the load test runs).
'use strict';

const STRATEGY_INFO = {
  pessimistic: 'MySQL row locks (SELECT … FOR UPDATE). A waiting request keeps its database connection.',
  optimistic: 'No locks. A @Version check at commit catches conflicts, and the transaction is retried.',
  'in-memory': 'Fair ReentrantLocks inside this JVM, taken around the transaction. Single server only.',
  redis: 'A Lua script takes every seat key at once, with a lease. Works across several servers.',
};

const view = {
  live: document.getElementById('live'),
  gate: document.getElementById('gate'),
  console: document.getElementById('console'),
  strategies: document.getElementById('strategies'),
  form: document.getElementById('event-form'),
  rows: document.getElementById('event-rows'),
};

const events = new Map();      // event id -> event
const seatStatus = new Map();  // event id -> Map(seat id -> status)
let renderQueued = false;

const feed = new SeatFeed({
  onChange: applyChange,
  onConnected: () => { if (events.size > 0) loadAllSeats(); },
  onStatus: (connected) => showLive(view.live, connected),
});

const auth = new AuthBox(document.getElementById('auth'), (me) => {
  if (!me) {
    showGate('Log in with an admin account to use this page.');
  } else if (!auth.isAdmin()) {
    showGate(`You are signed in as ${me.username}, which is not an admin account.`);
  } else {
    view.gate.hidden = true;
    view.console.hidden = false;
    loadStrategy();
    loadEvents();
  }
});

view.form.addEventListener('submit', createEvent);
view.form.elements.startsAt.value = tomorrowEvening();
auth.start();

function showGate(message) {
  view.console.hidden = true;
  view.gate.hidden = false;
  view.gate.replaceChildren(el('h2', {}, 'Admins only'), el('p', { class: 'muted' }, message));
}

// ---------- lock strategy ----------

async function loadStrategy() {
  try {
    renderStrategy(await api('GET', '/api/admin/lock-strategy'));
  } catch (error) {
    toast(error.message, 'error');
  }
}

function renderStrategy({ current, available }) {
  view.strategies.replaceChildren(...available.map((name) => el('button', {
    type: 'button',
    class: 'strategy',
    'aria-pressed': name === current ? 'true' : 'false',
    onclick: () => switchStrategy(name),
  },
  el('span', { class: 'strategy-name' }, name, name === current ? el('span', { class: 'badge' }, 'in use') : null),
  el('span', { class: 'strategy-text' }, STRATEGY_INFO[name] || ''))));
}

async function switchStrategy(name) {
  try {
    renderStrategy(await api('PUT', '/api/admin/lock-strategy', { strategy: name }));
    toast(`New holds now use the ${name} strategy`, 'success');
  } catch (error) {
    toast(error.message, 'error');
  }
}

// ---------- events ----------

async function loadEvents() {
  try {
    const list = await api('GET', '/api/events');
    events.clear();
    for (const event of list) events.set(event.id, event);
  } catch (error) {
    toast(error.message, 'error');
    return;
  }
  // Listen first, then load the seats, so no change in between is lost
  feed.watch([...events.keys()]);
  await loadAllSeats();
}

async function loadAllSeats() {
  await Promise.all([...events.keys()].map(async (eventId) => {
    try {
      const map = await api('GET', `/api/events/${eventId}/seats`);
      seatStatus.set(eventId, new Map(map.seats.map((seat) => [seat.id, seat.status])));
    } catch (error) {
      toast(error.message, 'error');
    }
  }));
  renderEvents();
}

function applyChange(change) {
  const statuses = seatStatus.get(change.eventId);
  if (!statuses) return;
  for (const id of change.seatIds) statuses.set(id, change.status);
  // Under a load test changes arrive by the hundred; draw at most once per frame
  if (!renderQueued) {
    renderQueued = true;
    requestAnimationFrame(() => {
      renderQueued = false;
      renderEvents();
    });
  }
}

function renderEvents() {
  if (events.size === 0) {
    view.rows.replaceChildren(el('tr', {}, el('td', { colspan: 7, class: 'muted' }, 'No events yet. Create one above.')));
    return;
  }
  view.rows.replaceChildren(...[...events.values()].map((event) => {
    const counts = countStatuses([...(seatStatus.get(event.id) || new Map()).values()].map((status) => ({ status })));
    const share = (n) => `${(n / event.totalSeats) * 100}%`;
    return el('tr', {},
      el('td', {}, el('a', { href: `/#event=${event.id}` }, event.name), el('div', { class: 'muted small' }, event.venue)),
      el('td', {}, formatDate(event.startsAt)),
      el('td', { class: 'num' }, `${event.seatRows} × ${event.seatsPerRow}`),
      el('td', {}, el('div', { class: 'bar', title: `${counts.SOLD} sold, ${counts.HELD} held` },
        el('span', { class: 'sold', style: `width:${share(counts.SOLD)}` }),
        el('span', { class: 'held', style: `width:${share(counts.HELD)}` }),
        el('span', { class: 'available', style: `width:${share(counts.AVAILABLE)}` }))),
      el('td', { class: 'num' }, counts.AVAILABLE),
      el('td', { class: 'num' }, counts.HELD),
      el('td', { class: 'num' }, counts.SOLD));
  }));
}

async function createEvent(submit) {
  submit.preventDefault();
  const form = view.form.elements;
  const body = {
    name: form.name.value.trim(),
    venue: form.venue.value.trim(),
    startsAt: new Date(form.startsAt.value).toISOString(),
    seatRows: Number(form.seatRows.value),
    seatsPerRow: Number(form.seatsPerRow.value),
  };
  try {
    const event = await api('POST', '/api/admin/events', body);
    toast(`Created ${event.name} with ${event.totalSeats} seats`, 'success');
    form.name.value = '';
    form.venue.value = '';
    await loadEvents();
  } catch (error) {
    toast(error.message, 'error');
  }
}

/** The value a datetime-local input expects, for tomorrow at 19:30 local time. */
function tomorrowEvening() {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T19:30`;
}
