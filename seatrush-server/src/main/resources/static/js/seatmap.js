// The seat map: pick an event, select up to four seats, hold them, then pay or cancel before the
// countdown runs out. Other people's holds and purchases appear live through the SeatFeed.
'use strict';

const MAX_SEATS = 4;

const state = {
  me: null,
  events: [],
  event: null,
  seats: new Map(),     // seat id -> { id, row, seat, status }
  buttons: new Map(),   // seat id -> its button in the map
  selected: new Set(),
  hold: null,           // my hold for this event, as GET /api/holds/{id} returns it
  holding: false,       // a hold request is on its way
  acting: false,        // a pay or cancel request is on its way
};

const view = {
  live: document.getElementById('live'),
  eventList: document.getElementById('event-list'),
  eventHeader: document.getElementById('event-header'),
  seatMap: document.getElementById('seat-map'),
  stats: document.getElementById('stats'),
  panel: document.getElementById('panel'),
};

const feed = new SeatFeed({
  onChange: applyChange,
  onConnected: () => { if (state.event) loadSeatMap(); },
  onStatus: (connected) => showLive(view.live, connected),
});

const auth = new AuthBox(document.getElementById('auth'), async (me) => {
  state.me = me;
  state.hold = null;
  state.selected.clear();
  if (state.event) {
    await restoreHold();
    renderSeats();
  }
  renderPanel();
});

let countdownTimer = null;

start();

async function start() {
  await auth.start();
  try {
    state.events = await api('GET', '/api/events');
  } catch (error) {
    toast(error.message, 'error');
    state.events = [];
  }
  renderEventList();
  const fromHash = Number(new URLSearchParams(location.hash.slice(1)).get('event'));
  const first = state.events.find((e) => e.id === fromHash) || state.events[0];
  if (first) selectEvent(first.id);
  else renderPanel();
}

// ---------- events ----------

function renderEventList() {
  view.eventList.replaceChildren();
  if (state.events.length === 0) {
    view.eventList.append(el('p', { class: 'muted' }, 'No events yet. An admin can create one on the ',
      el('a', { href: '/admin.html' }, 'admin page'), '.'));
    return;
  }
  for (const event of state.events) {
    view.eventList.append(el('button', {
      type: 'button',
      class: 'event-card',
      'aria-pressed': state.event && state.event.id === event.id ? 'true' : 'false',
      onclick: () => selectEvent(event.id),
    },
    el('span', { class: 'event-name' }, event.name),
    el('span', { class: 'event-meta' }, `${event.venue} · ${formatDate(event.startsAt)}`)));
  }
}

async function selectEvent(eventId) {
  state.event = state.events.find((e) => e.id === eventId);
  state.selected.clear();
  state.hold = null;
  history.replaceState(null, '', `#event=${eventId}`);
  renderEventList();
  view.eventHeader.replaceChildren(
    el('h1', {}, state.event.name),
    el('p', { class: 'muted' }, `${state.event.venue} · ${formatDate(state.event.startsAt)} · ${state.event.totalSeats} seats`),
  );
  // Listen first, then load: a change that happens in between is not lost
  feed.watch([eventId]);
  await loadSeatMap();
  await restoreHold();
  renderSeats();
  renderPanel();
}

async function loadSeatMap() {
  const eventId = state.event.id;
  let map;
  try {
    map = await api('GET', `/api/events/${eventId}/seats`);
  } catch (error) {
    toast(error.message, 'error');
    return;
  }
  if (!state.event || state.event.id !== eventId) return; // the user picked another event meanwhile
  state.seats = new Map(map.seats.map((seat) => [seat.id, seat]));
  for (const id of state.selected) {
    if (state.seats.get(id)?.status !== 'AVAILABLE') state.selected.delete(id);
  }
  buildSeatMap(map.seatRows, map.seatsPerRow);
}

function buildSeatMap(rows, perRow) {
  state.buttons.clear();
  view.seatMap.style.setProperty('--per-row', perRow);
  view.seatMap.replaceChildren();
  const byPosition = new Map([...state.seats.values()].map((seat) => [`${seat.row}-${seat.seat}`, seat]));
  for (let row = 1; row <= rows; row++) {
    view.seatMap.append(el('span', { class: 'row-label', 'aria-hidden': 'true' }, rowName(row)));
    for (let number = 1; number <= perRow; number++) {
      const seat = byPosition.get(`${row}-${number}`);
      const button = el('button', { type: 'button', class: 'seat', onclick: () => toggleSeat(seat.id) },
        String(number));
      state.buttons.set(seat.id, button);
      view.seatMap.append(button);
    }
  }
  renderSeats();
}

// ---------- seats ----------

function mySeatIds() {
  return new Set(state.hold && (state.hold.status === 'ACTIVE' || state.hold.status === 'CONFIRMED')
    ? state.hold.seatIds : []);
}

function renderSeats() {
  const mine = mySeatIds();
  for (const [id, button] of state.buttons) {
    const seat = state.seats.get(id);
    let look = seat.status.toLowerCase();
    let label = seat.status === 'AVAILABLE' ? 'available' : seat.status === 'HELD' ? 'held by someone' : 'sold';
    if (mine.has(id)) {
      look = seat.status === 'SOLD' ? 'mine sold' : 'mine';
      label = seat.status === 'SOLD' ? 'yours, paid' : 'held by you';
    } else if (state.selected.has(id)) {
      look = 'selected';
      label = 'selected';
    }
    button.className = `seat ${look}`;
    button.disabled = seat.status !== 'AVAILABLE' && !mine.has(id);
    button.setAttribute('aria-pressed', state.selected.has(id) ? 'true' : 'false');
    button.setAttribute('aria-label', `Seat ${seatName(seat)}, ${label}`);
    button.title = `${seatName(seat)} · ${label}`;
  }
  const counts = countStatuses(state.seats.values());
  view.stats.replaceChildren(
    el('span', {}, el('b', {}, counts.AVAILABLE), ' available'),
    el('span', {}, el('b', {}, counts.HELD), ' held'),
    el('span', {}, el('b', {}, counts.SOLD), ' sold'),
  );
}

function toggleSeat(seatId) {
  if (!state.me) {
    toast('Log in to pick seats', 'info');
    document.querySelector('#auth input')?.focus();
    return;
  }
  if (state.hold && state.hold.status === 'ACTIVE') {
    toast('Pay for or cancel your current hold first', 'info');
    return;
  }
  if (state.seats.get(seatId).status !== 'AVAILABLE') return;
  if (state.selected.has(seatId)) {
    state.selected.delete(seatId);
  } else if (state.selected.size >= MAX_SEATS) {
    toast(`You can hold at most ${MAX_SEATS} seats at once`, 'info');
    return;
  } else {
    state.selected.add(seatId);
  }
  renderSeats();
  renderPanel();
}

/** A pushed change: { eventId, seatIds, status }, sent after the change was committed. */
function applyChange(change) {
  if (!state.event || change.eventId !== state.event.id) return;
  const mine = mySeatIds();
  const lost = [];
  let touchesMyHold = false;
  for (const id of change.seatIds) {
    const seat = state.seats.get(id);
    if (!seat) continue;
    seat.status = change.status;
    if (mine.has(id)) touchesMyHold = true;
    if (state.selected.has(id) && change.status !== 'AVAILABLE') {
      state.selected.delete(id);
      lost.push(seatName(seat));
    }
  }
  if (lost.length > 0 && !state.holding) {
    toast(`Someone just took ${lost.join(', ')}`, 'warning');
  }
  renderSeats();
  renderPanel();
  // My own seats changed: expired, or paid or cancelled in another tab. When this tab is paying or
  // cancelling itself, the push can arrive before the answer; that answer is enough.
  if (touchesMyHold && change.status !== 'HELD' && !state.acting) refreshHold();
}

// ---------- my hold ----------

function holdKey() {
  return `seatrush.hold.${state.me.userId}.${state.event.id}`;
}

async function restoreHold() {
  state.hold = null;
  if (!state.me || !state.event) return;
  const holdId = Session.read(holdKey());
  if (!holdId) return;
  try {
    const hold = await api('GET', `/api/holds/${holdId}`);
    if (hold.status === 'ACTIVE' || hold.status === 'CONFIRMED') state.hold = hold;
    else Session.write(holdKey(), null);
  } catch {
    Session.write(holdKey(), null);
  }
}

async function refreshHold() {
  if (!state.hold) return;
  try {
    state.hold = await api('GET', `/api/holds/${state.hold.id}`);
  } catch (error) {
    toast(error.message, 'error');
    return;
  }
  if (state.hold.status === 'EXPIRED' || state.hold.status === 'CANCELLED') {
    toast(state.hold.status === 'EXPIRED'
      ? 'Your hold expired, the seats are back on sale'
      : 'Your hold was cancelled', 'warning');
    Session.write(holdKey(), null);
    state.hold = null;
  }
  renderSeats();
  renderPanel();
}

async function holdSelected() {
  const seatIds = [...state.selected].sort((a, b) => a - b);
  state.holding = true;
  renderPanel();
  try {
    const hold = await api('POST', `/api/events/${state.event.id}/holds`, {
      seatIds,
      idempotencyKey: newIdempotencyKey(),
    });
    state.hold = hold;
    for (const id of hold.seatIds) state.seats.get(id).status = 'HELD';
    Session.write(holdKey(), String(hold.id));
    state.selected.clear();
    toast('Seats held. Pay before the timer runs out.', 'success');
  } catch (error) {
    const taken = error.problem.unavailableSeatIds || [];
    for (const id of taken) {
      state.selected.delete(id);
      const seat = state.seats.get(id);
      if (seat && seat.status === 'AVAILABLE') seat.status = 'HELD';
    }
    toast(error.message, 'error');
  } finally {
    state.holding = false;
    renderSeats();
    renderPanel();
  }
}

async function actOnHold(action) {
  state.acting = true;
  try {
    state.hold = await api('POST', `/api/holds/${state.hold.id}/${action}`);
  } catch (error) {
    toast(error.message, 'error');
    await refreshHold();
    return;
  } finally {
    state.acting = false;
  }
  if (action === 'confirm') {
    for (const id of state.hold.seatIds) state.seats.get(id).status = 'SOLD';
    toast('Payment done, enjoy the show!', 'success');
  } else {
    toast('Hold cancelled, the seats are back on sale', 'info');
    Session.write(holdKey(), null);
    state.hold = null;
  }
  renderSeats();
  renderPanel();
}

function finishHold() {
  Session.write(holdKey(), null);
  state.hold = null;
  renderSeats();
  renderPanel();
}

// ---------- side panel ----------

function renderPanel() {
  clearInterval(countdownTimer);
  view.panel.replaceChildren();
  if (!state.event) {
    view.panel.append(el('p', { class: 'muted' }, 'Pick an event to see its seats.'));
    return;
  }
  if (!state.me) {
    view.panel.append(el('h2', {}, 'Your seats'), el('p', { class: 'muted' }, 'Log in at the top to pick seats.'));
    return;
  }
  if (state.hold && state.hold.status === 'ACTIVE') {
    renderActiveHold();
  } else if (state.hold && state.hold.status === 'CONFIRMED') {
    view.panel.append(
      el('h2', {}, 'Paid'),
      el('p', {}, 'These seats are yours:'),
      seatChips(state.hold.seatIds),
      el('button', { type: 'button', class: 'btn btn-quiet wide', onclick: finishHold }, 'Done'),
    );
  } else {
    renderSelection();
  }
}

function renderSelection() {
  const chosen = [...state.selected].sort((a, b) => a - b);
  view.panel.append(el('h2', {}, 'Your seats'));
  if (chosen.length === 0) {
    view.panel.append(el('p', { class: 'muted' }, `Click up to ${MAX_SEATS} green seats on the map.`));
    return;
  }
  view.panel.append(
    seatChips(chosen),
    el('button', {
      type: 'button',
      class: 'btn btn-primary wide',
      disabled: state.holding,
      onclick: holdSelected,
    }, state.holding ? 'Holding…' : `Hold ${chosen.length} seat${chosen.length > 1 ? 's' : ''}`),
    el('p', { class: 'muted small' }, 'A hold keeps the seats for you for a few minutes while you pay.'),
  );
}

function renderActiveHold() {
  const remaining = el('span', { class: 'countdown-time' });
  const bar = el('span', { class: 'countdown-bar' });
  view.panel.append(
    el('h2', {}, 'Held for you'),
    seatChips(state.hold.seatIds),
    el('div', { class: 'countdown' }, el('span', { class: 'muted' }, 'Expires in '), remaining,
      el('span', { class: 'countdown-track' }, bar)),
    el('button', { type: 'button', class: 'btn btn-primary wide', onclick: () => actOnHold('confirm') }, 'Pay now'),
    el('button', { type: 'button', class: 'btn btn-quiet wide', onclick: () => actOnHold('cancel') }, 'Cancel hold'),
  );

  const expiresAt = new Date(state.hold.expiresAt).getTime();
  const total = Math.max(expiresAt - Date.now(), 1);
  const tick = () => {
    const left = Math.max(expiresAt - Date.now(), 0);
    const seconds = Math.ceil(left / 1000);
    remaining.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    bar.style.width = `${(left / total) * 100}%`;
    bar.classList.toggle('urgent', left < 30000);
    if (left === 0) {
      clearInterval(countdownTimer);
      // The server releases the seats right at the deadline; ask it what happened
      setTimeout(refreshHold, 1000);
    }
  };
  tick();
  countdownTimer = setInterval(tick, 250);
}

function seatChips(seatIds) {
  return el('ul', { class: 'chips' }, seatIds.map((id) => {
    const seat = state.seats.get(id);
    return el('li', { class: 'chip' }, seat ? seatName(seat) : `#${id}`);
  }));
}
