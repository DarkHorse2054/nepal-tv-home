'use strict';

// Run the shipped script in memory. These tests never start a server, browser,
// ADB process, or network request; fetch and time are controlled below.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const dist = path.resolve(__dirname, '../dist');
const html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8');
const script = fs.readFileSync(path.join(dist, 'app.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

class EventTarget {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatch(type, details = {}) {
    const event = {
      type, target: this, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      ...details,
    };
    for (const listener of this.listeners.get(type) || []) listener(event);
    return event;
  }
}

class Element extends EventTarget {
  constructor(tagName, attributes, document) {
    super();
    this.tagName = tagName.toUpperCase();
    this.attributes = attributes;
    this.document = document;
    this.parentElement = null;
    this.childNodes = [];
    this.dataset = {};
    for (const [name, value] of Object.entries(attributes)) {
      if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    }
    this.id = attributes.id || '';
    this.value = attributes.value || '';
    this.disabled = Object.hasOwn(attributes, 'disabled');
    this.hidden = Object.hasOwn(attributes, 'hidden');
    this.open = Object.hasOwn(attributes, 'open');
    this.isContentEditable = false;
    const classes = new Set((attributes.class || '').split(/\s+/));
    this.classList = {
      add: name => classes.add(name),
      remove: name => classes.delete(name),
      contains: name => classes.has(name),
      toggle(name, force) {
        const add = force === undefined ? !classes.has(name) : force;
        if (add) classes.add(name); else classes.delete(name);
        return add;
      },
    };
  }
  get textContent() { return this.childNodes.map(node => node.textContent).join(''); }
  set textContent(value) { this.childNodes = [{ textContent: String(value) }]; }
  get firstChild() { return this.childNodes[0] || null; }
  get lastChild() { return this.childNodes.at(-1) || null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  getAttribute(name) { return this.attributes[name] ?? null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  matches(selector) {
    return selector.split(',').some(part => {
      const value = part.trim();
      if (value.startsWith('#')) return this.id === value.slice(1);
      if (value.startsWith('.')) return this.classList.contains(value.slice(1));
      const attribute = /^\[([^=\]]+)(?:="([^"]*)")?\]$/.exec(value);
      if (attribute) return this.hasAttribute(attribute[1]) && (attribute[2] === undefined || this.getAttribute(attribute[1]) === attribute[2]);
      return this.tagName.toLowerCase() === value.toLowerCase();
    });
  }
  closest(selector) {
    for (let element = this; element; element = element.parentElement) if (element.matches(selector)) return element;
    return null;
  }
  focus() { this.document.activeElement = this; }
  select() {}
  scrollIntoView() {}
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, right: 450, top: 0, bottom: 700 }; }
  showModal() { this.open = true; }
  close() {
    if (!this.open) return;
    this.open = false;
    // Native dialog close events are queued, rather than dispatched by close().
    queueMicrotask(() => this.dispatch('close'));
  }
}
class Button extends Element {}

class Document extends EventTarget {
  constructor() {
    super();
    this.hidden = false;
    this.elements = [];
    const stack = [];
    const voidElements = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
    for (const token of html.match(/<!--[^]*?-->|<![^>]*>|<[^>]+>|[^<]+/g)) {
      if (token.startsWith('<!')) continue;
      const closing = /^<\/([\w-]+)/.exec(token);
      if (closing) {
        while (stack.length && stack.pop().tagName.toLowerCase() !== closing[1]) {}
        continue;
      }
      const opening = /^<([\w-]+)/.exec(token);
      if (!opening) {
        if (stack.length) stack.at(-1).childNodes.push({ textContent: token });
        continue;
      }
      const attributes = {};
      for (const match of token.slice(opening[0].length).matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) attributes[match[1]] = match[2] ?? '';
      const Constructor = opening[1] === 'button' ? Button : Element;
      const element = new Constructor(opening[1], attributes, this);
      element.parentElement = stack.at(-1) || null;
      element.parentElement?.childNodes.push(element);
      this.elements.push(element);
      if (element.tagName === 'BODY') this.body = element;
      if (!voidElements.has(opening[1]) && !token.endsWith('/>')) stack.push(element);
    }
    this.activeElement = this.body;
  }
  querySelectorAll(selector) { return this.elements.filter(element => element.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

class Clock {
  constructor() { this.now = 0; this.nextId = 0; this.tasks = new Map(); }
  setTimeout(callback, delay = 0) {
    const id = ++this.nextId;
    this.tasks.set(id, { id, callback, at: this.now + delay, interval: 0 });
    return id;
  }
  setInterval(callback, interval) {
    const id = this.setTimeout(callback, interval);
    this.tasks.get(id).interval = interval;
    return id;
  }
  clear(id) { this.tasks.delete(id); }
  async advance(duration) {
    const end = this.now + duration;
    for (;;) {
      const next = [...this.tasks.values()].filter(task => task.at <= end).sort((a, b) => a.at - b.at || a.id - b.id)[0];
      if (!next) break;
      this.now = next.at;
      if (next.interval) next.at += next.interval; else this.tasks.delete(next.id);
      next.callback();
      await flush();
    }
    this.now = end;
    await flush();
  }
}

function response(data, status = 200) { return { ok: status >= 200 && status < 300, status, json: async () => data }; }
function deviceFixture(overrides = {}) {
  return {
    online: true, sampledAt: '2026-10-25T17:00:00+00:00', ageSeconds: 1, stale: false,
    memory: { totalBytes: 1610612736, availableBytes: 536870912, usedBytes: 1073741824, usedPercent: 66.7 },
    storage: { totalBytes: 6442450944, availableBytes: 4294967296, usedBytes: 2147483648, usedPercent: 33.3 },
    ...overrides,
  };
}
function weatherFixture(overrides = {}) {
  return {
    enabled: true, state: 'ready', location: 'San Mateo', localDate: '2026-10-25', temperatureUnit: 'F',
    sampledAt: '2026-10-25T17:00:00+00:00', ageSeconds: 60,
    current: { time: '2026-10-25T10:00', temperature: 64, weatherCode: 2, isDay: true },
    daily: Array.from({ length: 7 }, (_, index) => ({
      date: `2026-10-${25 + index}`, weatherCode: index === 0 ? 2 : 3,
      high: 70 + index, low: 50 + index, precipitationProbability: index * 5,
    })),
    attribution: { name: 'Open-Meteo', url: 'https://open-meteo.com/' },
    ...overrides,
  };
}
function harness(fixtures = {}) {
  const document = new Document();
  const window = new EventTarget();
  const clock = new Clock();
  const requests = [];
  const deferred = [];
  const status = { online: true, device: { name: 'Test Fire TV' }, date: {}, samsung: { enabled: true, paired: true } };
  const fetch = (url, options) => {
    const request = { url, options, body: options.body ? JSON.parse(options.body) : undefined };
    requests.push(request);
    const index = deferred.findIndex(item => item.url === url);
    if (index >= 0) {
      const pending = deferred.splice(index, 1)[0];
      pending.request = request;
      if (!pending.ignoreAbort) options.signal.addEventListener('abort', () => pending.reject(Object.assign(new Error('Timed out'), { name: 'AbortError' })), { once: true });
      return pending.promise;
    }
    const defaults = {
      '/api/status': status,
      '/api/device': fixtures.device || deviceFixture(),
      '/api/weather': fixtures.weather || weatherFixture(),
    };
    return Promise.resolve(response(defaults[url] || { ok: true, state: 'sent' }));
  };
  window.matchMedia = () => ({ matches: false });
  const session = new Map(fixtures.paired === false ? [] : [['nepal.remote.session', 'unit-test-token']]);
  const context = vm.createContext({
    document, window, HTMLElement: Element, HTMLButtonElement: Button, fetch,
    sessionStorage: { getItem: key => session.get(key), setItem: (key, value) => session.set(key, value), removeItem: key => session.delete(key) },
    navigator: {}, AbortController, performance: { now: () => clock.now },
    setTimeout: clock.setTimeout.bind(clock), clearTimeout: clock.clear.bind(clock),
    setInterval: clock.setInterval.bind(clock), clearInterval: clock.clear.bind(clock),
  });
  vm.runInContext(script, context, { filename: path.join(dist, 'app.js') });
  const get = selector => {
    const element = document.querySelector(selector);
    assert.ok(element, `Missing UI element: ${selector}`);
    return element;
  };
  function click(selector) {
    const element = typeof selector === 'string' ? get(selector) : selector;
    assert.equal(element.disabled, false, `${selector} should be enabled`);
    element.dispatch('click', { detail: 1 });
  }
  function pointer(type, element, pointerId = 1) { element.dispatch(type, { button: 0, pointerId }); }
  function tap(selector, pointerId = 1) {
    const element = get(selector);
    assert.equal(element.disabled, false);
    pointer('pointerdown', element, pointerId);
    pointer('pointerup', element, pointerId);
  }
  function key(keyValue, target) {
    const event = document.dispatch('keydown', { key: keyValue, target, repeat: false, ctrlKey: false, altKey: false, metaKey: false, isComposing: false });
    if (!event.defaultPrevented && keyValue === 'Escape') document.querySelectorAll('dialog').filter(sheet => sheet.open).at(-1)?.close();
    return event;
  }
  function enterText(value) {
    const input = get('#tv-text');
    assert.equal(input.disabled, false, 'Typing must be enabled before editing');
    input.value = value;
    input.dispatch('input');
  }
  function defer(url, options = {}) {
    const pending = { url, ...options };
    pending.promise = new Promise((resolve, reject) => { pending.resolve = (data = { ok: true }, code = 200) => resolve(response(data, code)); pending.reject = reject; });
    deferred.push(pending);
    return pending;
  }
  return { document, window, clock, requests, get, click, pointer, tap, key, enterText, defer,
    calls: url => requests.filter(request => request.url === url) };
}

test('two deliberate rapid arrow taps both send while the first request is pending', async () => {
  const ui = harness(); await flush();
  const first = ui.defer('/api/key');
  ui.tap('[data-key="up"]', 1); await flush();
  ui.tap('[data-key="up"]', 2); await flush();
  assert.equal(ui.calls('/api/key').length, 1);
  first.resolve(); await flush();
  assert.deepEqual(ui.calls('/api/key').map(request => request.body.key), ['up', 'up']);
});

test('held repeats stop on release, cancellation, capture loss, blur, or hiding', async t => {
  for (const reason of ['pointerup', 'pointercancel', 'lostpointercapture', 'blur', 'hidden']) await t.test(reason, async () => {
    const ui = harness(); await flush();
    const up = ui.get('[data-key="up"]');
    ui.pointer('pointerdown', up); await flush();
    await ui.clock.advance(420);
    assert.equal(ui.calls('/api/key').length, 2, 'The hold should have produced a repeat');
    if (reason === 'blur') ui.window.dispatch('blur');
    else if (reason === 'hidden') { ui.document.hidden = true; ui.document.dispatch('visibilitychange'); }
    else ui.pointer(reason, up);
    await ui.clock.advance(1500);
    assert.equal(ui.calls('/api/key').length, 2, 'No repeated input may survive cancellation');
    assert.equal(up.classList.contains('is-pressed'), false);
  });
});

test('unsent taps are discarded after blur or their queue deadline', async t => {
  for (const reason of ['blur', 'deadline']) await t.test(reason, async () => {
    const ui = harness(); await flush();
    const first = ui.defer('/api/key');
    ui.tap('[data-key="up"]'); await flush();
    ui.click('[data-key="home"]');
    if (reason === 'blur') ui.window.dispatch('blur');
    else await ui.clock.advance(1201);
    first.resolve(); await flush();
    assert.deepEqual(ui.calls('/api/key').map(request => request.body.key), ['up']);
  });
});

test('H and M work with button focus while Enter and summary activation stay native', async () => {
  const ui = harness(); await flush();
  const button = ui.get('[data-key="home"]');
  assert.equal(ui.key('h', button).defaultPrevented, true); await flush();
  assert.equal(ui.key('M', button).defaultPrevented, true); await flush();
  assert.deepEqual(ui.calls('/api/key').map(request => request.body.key), ['home', 'menu']);
  assert.equal(ui.key('Enter', button).defaultPrevented, false);
  const summary = ui.get('summary');
  for (const key of ['Enter', ' ', 'h']) assert.equal(ui.key(key, summary).defaultPrevented, false);
  await flush();
  assert.equal(ui.calls('/api/key').length, 2, 'Native control keys must not also issue TV commands');
});

test('Escape dismisses Sources natively without sending Samsung Back', async () => {
  const ui = harness(); await flush();
  ui.click('#sources-button');
  assert.equal(ui.get('#sources-dialog').open, true);
  const event = ui.key('Escape', ui.get('[data-samsung="up"]'));
  assert.equal(event.defaultPrevented, false);
  await flush();
  assert.equal(ui.get('#sources-dialog').open, false);
  assert.equal(ui.calls('/api/samsung/key').length, 0);
});

test('typing is locked during submission and sends the submitted value only once', async () => {
  const ui = harness(); await flush();
  ui.click('#type-button'); ui.enterText('test draft');
  const pending = ui.defer('/api/text');
  ui.get('#text-form').dispatch('submit'); await flush();
  assert.equal(ui.get('#tv-text').disabled, true);
  assert.equal(ui.get('#text-submit').disabled, true);
  assert.deepEqual(ui.calls('/api/text').map(request => request.body), [{ text: 'test draft' }]);
  pending.resolve(); await flush();
  assert.equal(ui.get('#tv-text').value, '');
  assert.equal(ui.get('#tv-text').disabled, false);
  assert.equal(ui.calls('/api/text').length, 1);
});

test('typing success is announced inside its native dialog and expires there', async () => {
  const ui = harness(); await flush();
  ui.click('#type-button'); ui.enterText('sample text');
  ui.get('#text-form').dispatch('submit'); await flush();
  const notice = ui.get('#type-dialog-notice');
  assert.equal(notice.closest('dialog').id, 'type-dialog');
  assert.equal(notice.hidden, false);
  assert.equal(notice.textContent, 'Text sent to TV.');
  assert.equal(ui.get('#toast').classList.contains('is-visible'), false);
  assert.equal(ui.get('#toast').textContent, '');
  await ui.clock.advance(4200);
  assert.equal(notice.hidden, true);
  assert.equal(notice.textContent, '');
});

test('Samsung failures remain visible inside Sources rather than behind its backdrop', async () => {
  const ui = harness(); await flush();
  ui.click('#sources-button');
  const pending = ui.defer('/api/samsung/key');
  ui.click('[data-samsung="source"]'); await flush();
  pending.resolve({ error: 'samsung_tv_unavailable' }, 503); await flush();
  const notice = ui.get('#sources-dialog-notice');
  assert.equal(notice.closest('dialog').id, 'sources-dialog');
  assert.equal(notice.hidden, false);
  assert.equal(notice.textContent, 'Samsung is unavailable. Check that the TV is on.');
  assert.equal(ui.get('#toast').classList.contains('is-visible'), false);
  assert.equal(ui.get('#toast').textContent, '');
  ui.get('#sources-dialog').close(); await flush();
  assert.equal(notice.hidden, true);
  assert.equal(notice.textContent, '');
  ui.click('#sources-button');
  assert.equal(notice.hidden, true, 'Reopening Sources must not show stale feedback');
});

test('main remote feedback keeps the global toast and clears when a sheet opens', async () => {
  const ui = harness(); await flush();
  const pending = ui.defer('/api/key');
  ui.click('[data-key="home"]'); await flush();
  pending.resolve({ error: 'command_failed' }, 503); await flush();
  const toast = ui.get('#toast');
  assert.equal(toast.classList.contains('is-visible'), true);
  assert.equal(toast.textContent, 'Fire TV could not complete that action.');
  ui.click('#sources-button');
  assert.equal(toast.classList.contains('is-visible'), false);
  assert.equal(toast.textContent, '');
  assert.equal(ui.get('#sources-dialog-notice').hidden, true);
});

test('a late typing result cannot erase or report an old error into a reopened draft', async t => {
  for (const outcome of ['success', 'uncertain']) await t.test(outcome, async () => {
    const ui = harness(); await flush();
    ui.click('#type-button'); ui.enterText('old draft');
    const pending = ui.defer('/api/text');
    ui.get('#text-form').dispatch('submit'); await flush();
    ui.get('#type-dialog').close();
    // Reopen before the queued close event, as can happen during rapid input.
    ui.click('#type-button'); ui.enterText('new draft');
    await flush();
    if (outcome === 'success') pending.resolve();
    else pending.resolve({ error: 'text_send_uncertain' }, 503);
    await flush();
    assert.equal(ui.get('#tv-text').value, 'new draft');
    assert.equal(ui.get('#text-error').textContent, '');
    assert.equal(ui.get('#tv-text').disabled, false);
    assert.equal(ui.calls('/api/text').length, 1, 'Text must never be replayed automatically');
  });
});

test('Samsung action timeout preserves Mac reachability established by status polls', async () => {
  const ui = harness(); await flush();
  await ui.clock.advance(1000);
  ui.defer('/api/samsung/key');
  ui.click('[data-samsung="volume_up"]'); await flush();
  await ui.clock.advance(12000); // Status succeeds at 6s and 12s; action times out at 13s.
  assert.ok(ui.calls('/api/status').length >= 3);
  assert.equal(ui.get('#mac-status').textContent, 'Available');
  assert.equal(ui.get('#connection-label').textContent, 'Connected');
  assert.equal(ui.get('[data-key="up"]').disabled, false);
  assert.equal(ui.calls('/api/samsung/key').length, 1);
});

test('a failed status probe does disable TV controls and identify the Mac as unavailable', async () => {
  const ui = harness(); await flush();
  const pending = ui.defer('/api/status');
  await ui.clock.advance(6000);
  pending.reject(new Error('Network unavailable')); await flush();
  assert.equal(ui.get('#mac-status').textContent, 'Unavailable');
  assert.equal(ui.get('#connection-label').textContent, 'Mac offline');
  assert.equal(ui.get('[data-key="up"]').disabled, true);
});

test('expired authentication opens pairing and invalidates queued controls', async () => {
  const ui = harness(); await flush();
  const first = ui.defer('/api/key');
  ui.tap('[data-key="up"]'); await flush();
  ui.click('[data-key="home"]');
  first.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.get('#pair-dialog').open, true);
  assert.equal(ui.get('[data-key="up"]').disabled, true);
  assert.equal(ui.calls('/api/key').length, 1);
});

test('device stats sample only while their sheet is open and the page is visible', async () => {
  const ui = harness(); await flush();
  assert.equal(ui.calls('/api/device').length, 0, 'The main remote does not need a device sample');
  ui.click('#device-button'); await flush();
  assert.equal(ui.get('#device-dialog').open, true);
  assert.equal(ui.calls('/api/device').length, 1);
  assert.equal(ui.get('#memory-percent').textContent, '67% used');
  assert.equal(ui.get('#storage-percent').textContent, '33% used');
  assert.equal(ui.get('#memory-progress').hidden, false);
  assert.equal(ui.get('#storage-progress').hidden, false);
  await ui.clock.advance(9999);
  assert.equal(ui.calls('/api/device').length, 1);
  await ui.clock.advance(1);
  assert.equal(ui.calls('/api/device').length, 2);
  ui.get('#device-dialog').close(); await flush();
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/device').length, 2, 'Closing the sheet must stop device work');
  ui.click('#device-button'); await flush();
  assert.equal(ui.calls('/api/device').length, 3);
  ui.document.hidden = true; ui.document.dispatch('visibilitychange');
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/device').length, 3, 'A hidden phone tab must not keep polling Fire TV');
  ui.document.hidden = false; ui.document.dispatch('visibilitychange'); await flush();
  assert.equal(ui.calls('/api/device').length, 4, 'The still-open sheet refreshes when visible again');
  await ui.clock.advance(10000);
  assert.equal(ui.calls('/api/device').length, 5, 'Visibility must resume one polling loop');
});

test('late device samples cannot replace a reopened sheet or render while hidden', async t => {
  for (const reason of ['close-and-reopen', 'hidden']) await t.test(reason, async () => {
    const ui = harness(); await flush();
    const old = ui.defer('/api/device', { ignoreAbort: true });
    ui.click('#device-button'); await flush();
    if (reason === 'close-and-reopen') {
      ui.get('#device-dialog').close();
      // A native close event can arrive after the next open.
      ui.click('#device-button'); await flush();
      assert.equal(ui.get('#memory-percent').textContent, '67% used');
    } else {
      ui.document.hidden = true; ui.document.dispatch('visibilitychange'); await flush();
    }
    const previous = ui.get('#memory-percent').textContent;
    old.resolve(deviceFixture({ memory: { totalBytes: 1000, availableBytes: 10, usedBytes: 990, usedPercent: 99 } }));
    await flush();
    assert.equal(ui.get('#memory-percent').textContent, previous, 'An obsolete request must not update visible stats');
    if (reason === 'close-and-reopen') {
      const count = ui.calls('/api/device').length;
      await ui.clock.advance(10000);
      assert.equal(ui.calls('/api/device').length, count + 1, 'An old close event must not stop the new polling loop');
    }
  });
});

test('device stale and missing readings are explicit instead of displaying zero usage', async () => {
  const ui = harness({ device: deviceFixture({ online: false, stale: true, ageSeconds: 120, memory: null, storage: null }) });
  await flush(); ui.click('#device-button'); await flush();
  assert.match(ui.get('#device-state').textContent, /offline|unavailable|stale/i);
  for (const kind of ['memory', 'storage']) {
    assert.equal(ui.get(`#${kind}-percent`).textContent, 'Unavailable');
    assert.equal(ui.get(`#${kind}-progress`).hidden, true);
    assert.doesNotMatch(ui.get(`#${kind}-used`).textContent, /^0/);
  }
});

test('weather renders current conditions and all seven forecast days without background polling', async () => {
  const ui = harness(); await flush();
  assert.equal(ui.calls('/api/weather').length, 0);
  ui.click('#weather-button'); await flush();
  assert.equal(ui.get('#weather-dialog').open, true);
  assert.equal(ui.get('#weather-location').textContent, 'San Mateo');
  assert.equal(ui.get('#weather-temperature').textContent, '64°F');
  assert.equal(ui.get('#weather-current').hidden, false);
  assert.equal(ui.get('#weather-daily').hidden, false);
  for (let index = 0; index < 7; index++) {
    assert.equal(ui.get(`#weather-day-${index}`).hidden, false, `Day ${index + 1} should be visible`);
    assert.ok(ui.get(`#weather-day-${index}-name`).textContent);
    assert.match(ui.get(`#weather-day-${index}-high`).textContent, new RegExp(String(70 + index)));
  }
  assert.doesNotMatch(ui.get('#weather-updated').textContent, /no forecast/i);
  await ui.clock.advance(60000);
  assert.equal(ui.calls('/api/weather').length, 1, 'Weather refresh is deliberate, not a second background poller');
  ui.click('#weather-refresh'); await flush();
  assert.equal(ui.calls('/api/weather').length, 2);
});

test('weather preserves cached forecasts with a stale warning and hides disabled or unavailable forecasts', async t => {
  for (const state of ['stale', 'disabled', 'unavailable']) await t.test(state, async () => {
    const cached = state === 'stale';
    const ui = harness({ weather: weatherFixture({
      enabled: state !== 'disabled', state, ageSeconds: cached ? 1800 : null,
      sampledAt: cached ? '2026-10-25T17:00:00+00:00' : null,
      current: cached ? weatherFixture().current : null,
      daily: cached ? weatherFixture().daily : [],
    }) });
    await flush(); ui.click('#weather-button'); await flush();
    assert.equal(ui.get('#weather-current').hidden, !cached);
    assert.equal(ui.get('#weather-daily').hidden, !cached);
    const stateText = ui.get('#weather-state').textContent;
    if (cached) {
      assert.match(stateText, /stale|older|cached|out.of.date|saved forecast|update delayed/i);
      assert.equal(ui.get('#weather-temperature').textContent, '64°F');
    } else if (state === 'disabled') assert.match(stateText, /not configured|not set up|disabled|enable|set up/i);
    else assert.match(stateText, /unavailable|could not|cannot|no forecast/i);
  });
});

test('late weather responses do not overwrite a newer open sheet or render while hidden', async t => {
  for (const reason of ['close-and-reopen', 'hidden']) await t.test(reason, async () => {
    const ui = harness(); await flush();
    const old = ui.defer('/api/weather', { ignoreAbort: true });
    ui.click('#weather-button'); await flush();
    if (reason === 'close-and-reopen') {
      ui.get('#weather-dialog').close();
      ui.click('#weather-button'); await flush();
      assert.equal(ui.get('#weather-temperature').textContent, '64°F');
    } else {
      ui.document.hidden = true; ui.document.dispatch('visibilitychange'); await flush();
    }
    const previous = ui.get('#weather-temperature').textContent;
    old.resolve(weatherFixture({ current: { time: '2026-10-25T08:00', temperature: 5, weatherCode: 71, isDay: true } }));
    await flush();
    assert.equal(ui.get('#weather-temperature').textContent, previous);
  });
});

test('weather refresh failures remain explicit and do not manufacture fresh conditions', async () => {
  const ui = harness(); await flush(); ui.click('#weather-button'); await flush();
  const pending = ui.defer('/api/weather');
  ui.click('#weather-refresh'); await flush();
  pending.reject(new Error('Network unavailable')); await flush();
  assert.match(ui.get('#weather-state').textContent, /unavailable|could not|cannot|failed|stale|cached/i);
  assert.notEqual(ui.get('#weather-refresh').disabled, true, 'A failed request remains retryable');
  assert.equal(ui.calls('/api/weather').length, 2, 'A failure must not silently replay a weather request');
});

test('wallpaper sends only the chosen fixed mode on Apply and never claims current readback', async () => {
  const ui = harness(); await flush();
  const modes = ui.document.querySelectorAll('[data-wallpaper]').map(button => button.dataset.wallpaper);
  assert.deepEqual(modes, ['auto', 'morning', 'dusk', 'night']);
  ui.click('#wallpaper-button'); await flush();
  assert.equal(ui.get('#wallpaper-dialog').open, true);
  assert.equal(ui.calls('/api/wallpaper').length, 0, 'Opening the sheet must never change the TV');
  for (const mode of modes) {
    const before = ui.calls('/api/wallpaper').length;
    ui.click(`[data-wallpaper="${mode}"]`); await flush();
    assert.equal(ui.calls('/api/wallpaper').length, before, 'Choosing a preview requires an explicit Apply');
    assert.equal(ui.get(`[data-wallpaper="${mode}"]`).getAttribute('aria-pressed'), 'true');
    assert.doesNotMatch(ui.get('#wallpaper-state').textContent, /current:|active:|currently (?:on|using)|tv is (?:showing|using)/i);
    ui.click('#wallpaper-apply'); await flush();
    const request = ui.calls('/api/wallpaper').at(-1);
    assert.equal(request.options.method, 'POST');
    assert.deepEqual(request.body, { mode });
    assert.equal(ui.calls('/api/wallpaper').length, before + 1);
    assert.doesNotMatch(ui.get('#wallpaper-state').textContent, /current:|active:|currently (?:on|using)|tv is (?:showing|using)/i);
  }
});

test('wallpaper failure does not report success or replay the action', async () => {
  const ui = harness(); await flush(); ui.click('#wallpaper-button');
  ui.click('[data-wallpaper="night"]');
  const pending = ui.defer('/api/wallpaper');
  ui.click('#wallpaper-apply'); await flush();
  pending.resolve({ error: 'wallpaper_unavailable' }, 503); await flush();
  assert.equal(ui.calls('/api/wallpaper').length, 1);
  const notice = ui.get('#wallpaper-dialog-notice');
  assert.equal(notice.hidden, false, 'The failure must be visible inside the open sheet');
  assert.match(notice.textContent, /could not|cannot|unavailable|failed|not confirmed/i);
  assert.doesNotMatch(notice.textContent, /applied|success/i);
  assert.equal(ui.get('#wallpaper-state').textContent, 'Choice: Night');
  assert.equal(ui.get('#wallpaper-apply').disabled, false);
});


test('stale weather labels Today only for the configured local date, never the first cached day', async () => {
  const weather = weatherFixture({ state: 'stale', ageSeconds: 90000, localDate: '2026-10-26' });
  const ui = harness({ weather }); await flush(); ui.click('#weather-button'); await flush();
  assert.notEqual(ui.get('#weather-day-0-name').textContent, 'Today');
  assert.equal(ui.get('#weather-day-1-name').textContent, 'Today');
  assert.equal(ui.get('#weather-summary-label').textContent, 'Today');
  assert.equal(ui.get('#weather-today-high').textContent, '71°');
  assert.equal(ui.get('#weather-today-low').textContent, '51°');
});

test('an old saved forecast without the configured local day shows dates rather than Today', async () => {
  const weather = weatherFixture({ state: 'stale', ageSeconds: 900000, localDate: '2026-11-05' });
  const ui = harness({ weather }); await flush(); ui.click('#weather-button'); await flush();
  for (let index = 0; index < 7; index++) assert.notEqual(ui.get(`#weather-day-${index}-name`).textContent, 'Today');
  assert.doesNotMatch(ui.get('#weather-summary-label').textContent, /today/i);
  assert.match(ui.get('#weather-summary-label').textContent, /forecast.*25/i);
  assert.match(ui.get('#weather-state').textContent, /saved|stale|delayed/i);
});


test('Open ESPN launches the installed app explicitly and disables while Fire TV is offline', async () => {
  const ui = harness(); await flush(); ui.click('#settings-button');
  assert.equal(ui.get('#espn-button').textContent, 'Open ESPN');
  ui.click('#espn-button'); await flush();
  assert.deepEqual(ui.calls('/api/app').map(request => request.body), [{ app: 'espn' }]);
  const pending = ui.defer('/api/status');
  await ui.clock.advance(6000);
  pending.resolve({ online: false, device: {}, date: {}, samsung: { enabled: true, paired: true } });
  await flush();
  assert.equal(ui.get('#espn-button').disabled, true);
  assert.equal(ui.calls('/api/app').length, 1);
  const dockApps = ui.document.querySelectorAll('[data-app]').filter(button => button.closest('.app-dock')).map(button => button.dataset.app);
  assert.deepEqual(dockApps, ['netflix', 'youtube', 'disney', 'paramount', 'hulu', 'npl']);
});


test('AirPlay help opens unpaired without API calls and closes with native Escape', async () => {
  const ui = harness({ paired: false }); await flush();
  assert.equal(ui.requests.length, 0);
  assert.equal(ui.get('#airplay-button').disabled, false);
  assert.equal(ui.get('#device-button').disabled, true);
  ui.click('#airplay-button');
  assert.equal(ui.get('#airplay-dialog').open, true);
  assert.match(ui.get('#airplay-dialog').textContent, /Screen Mirroring/);
  assert.match(ui.get('#airplay-dialog').textContent, /directly to Samsung/);
  assert.match(ui.get('#airplay-dialog').textContent, /does not need to stay awake/);
  assert.equal(ui.requests.length, 0);
  const event = ui.key('Escape', ui.get('#airplay-dialog'));
  assert.equal(event.defaultPrevented, false);
  await flush(); assert.equal(ui.get('#airplay-dialog').open, false);
  assert.equal(ui.requests.length, 0);
});

test('AirPlay help remains available offline and opening it tears down device polling', async () => {
  const ui = harness(); await flush();
  ui.click('#device-button'); await flush();
  assert.equal(ui.calls('/api/device').length, 1);
  ui.window.dispatch('offline');
  assert.equal(ui.get('#device-button').disabled, true);
  assert.equal(ui.get('#airplay-button').disabled, false);
  const before = ui.requests.length;
  ui.click('#airplay-button'); await flush();
  assert.equal(ui.get('#airplay-dialog').open, true);
  assert.equal(ui.get('#device-dialog').open, false);
  assert.equal(ui.requests.length, before, 'Opening the local guide sends no command or API request');
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/device').length, 1, 'AirPlay help does not keep device polling alive');
  assert.equal(ui.calls('/api/key').length, 0);
  assert.equal(ui.calls('/api/samsung/key').length, 0);
});
