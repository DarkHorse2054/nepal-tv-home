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
function harness() {
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
      options.signal.addEventListener('abort', () => pending.reject(Object.assign(new Error('Timed out'), { name: 'AbortError' })), { once: true });
      return pending.promise;
    }
    return Promise.resolve(response(url === '/api/status' ? status : { ok: true, state: 'sent' }));
  };
  window.matchMedia = () => ({ matches: false });
  const session = new Map([['nepal.remote.session', 'unit-test-token']]);
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
  function defer(url) {
    const pending = { url };
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
