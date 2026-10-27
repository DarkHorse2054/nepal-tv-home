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
  removeEventListener(type, listener) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter(value => value !== listener));
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
    this.checked = Object.hasOwn(attributes, 'checked');
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
  removeAttribute(name) { delete this.attributes[name]; }
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
class Video extends Element {
  constructor(...args) {
    super(...args);
    this.paused = true;
    this.readyState = 0;
    this.playCalls = this.pauseCalls = this.loadCalls = 0;
    this.nativeHls = true;
    this.playError = null;
    this.currentTime = 0;
    this.seeking = false;
    this.seekable = { length: 0 };
  }
  get src() { return this.getAttribute('src') || ''; }
  set src(value) { this.setAttribute('src', value); }
  canPlayType(type) { return this.nativeHls && type === 'application/vnd.apple.mpegurl' ? 'probably' : ''; }
  play() {
    this.playCalls++;
    if (this.playError) return Promise.reject(this.playError);
    this.paused = false; this.readyState = 2;
    return Promise.resolve();
  }
  pause() { this.pauseCalls++; this.paused = true; }
  load() { this.loadCalls++; this.readyState = 0; }
}

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
      const Constructor = opening[1] === 'button' ? Button : opening[1] === 'video' ? Video : Element;
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
function previewResponse(options = {}) {
  const blob = new Blob([Uint8Array.from([255, 216, 255, 217])], { type: options.mime || 'image/jpeg' });
  const headers = { 'Content-Type': blob.type, 'Content-Length': String(options.length ?? blob.size), 'X-Preview-Captured-At': options.capturedAt ?? '2026-10-25T17:00:00+00:00' };
  return { ok: true, status: 200, headers: { get: key => headers[key] || null }, blob: async () => blob };
}
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
  const createdUrls = [];
  const revokedUrls = [];
  const warnings = [];
  const vibrations = [];
  const status = { online: true, device: { name: 'Test Fire TV' }, date: {}, samsung: { enabled: true, paired: true } };
  const livePlaylist = '/api/live/0123456789abcdef0123456789abcdef/index.m3u8';
  const video = document.querySelector('#live-video');
  video.nativeHls = fixtures.nativeHls !== false;
  video.playError = fixtures.playError || null;
  const players = [];
  if (fixtures.hls) {
    class FakeHls {
      static Events = { MANIFEST_PARSED: 'manifest', ERROR: 'error' };
      static isSupported() { return true; }
      constructor(config) { this.config = config; this.listeners = new Map(); this.destroyed = false; players.push(this); }
      on(event, listener) { this.listeners.set(event, listener); }
      emit(event, data = {}) { this.listeners.get(event)?.(event, data); }
      attachMedia(media) { this.media = media; }
      loadSource(source) { this.source = source; }
      destroy() { this.destroyed = true; this.listeners.clear(); }
    }
    window.Hls = FakeHls;
  }
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
    if (url === '/api/preview/frame') return Promise.resolve(previewResponse(fixtures.preview));
    if (url === '/api/session/resume') return Promise.resolve(fixtures.resume
      ? response(fixtures.resume, fixtures.resumeStatus || 200)
      : response({ error: 'trusted_device_missing' }, 401));
    if (url === '/api/session/pair') return Promise.resolve(response(fixtures.pair || {
      token: 'p'.repeat(43), remembered: request.body.remember, expiresIn: 28800,
    }));
    const defaults = {
      '/api/status': status,
      '/api/theater': { ok: true, state: 'request_sent' },
      '/api/device': fixtures.device || deviceFixture(),
      '/api/weather': fixtures.weather || weatherFixture(),
      '/api/live/start': fixtures.liveStart || { state: 'starting', playlist: livePlaylist, videoOnly: true },
      '/api/live/status': fixtures.liveStatus || { state: 'ready', playlist: livePlaylist, videoOnly: true },
      '/api/live/keepalive': fixtures.liveKeepalive || { state: 'ready', playlist: livePlaylist, videoOnly: true },
      '/api/live/stop': { state: 'stopped', videoOnly: true },
    };
    return Promise.resolve(response(defaults[url] || { ok: true, state: 'sent' }));
  };
  window.matchMedia = () => ({ matches: fixtures.reducedMotion === true });
  const session = new Map(fixtures.paired === false ? [] : [['nepal.remote.session', 'unit-test-token']]);
  const local = new Map(fixtures.remembered ? [['nepal.remote.remembered', '1']] : []);
  if (fixtures.hapticsPreference !== undefined) local.set('nepal.remote.haptics', fixtures.hapticsPreference);
  const navigator = fixtures.haptics ? { vibrate(duration) {
    vibrations.push({ duration, at: clock.now });
    if (fixtures.vibrationThrows) throw new Error('Vibration denied');
    return fixtures.vibrationResult !== false;
  } } : {};
  const context = vm.createContext({
    document, window, HTMLElement: Element, HTMLButtonElement: Button, fetch,
    console: { warn: (...values) => warnings.push(values) },
    sessionStorage: { getItem: key => session.get(key), setItem: (key, value) => session.set(key, value), removeItem: key => session.delete(key) },
    localStorage: { getItem: key => local.get(key), setItem: (key, value) => local.set(key, value), removeItem: key => local.delete(key) },
    navigator, AbortController, Uint8Array, URL: { createObjectURL: () => { const url = `blob:test-${createdUrls.length}`; createdUrls.push(url); return url; }, revokeObjectURL: url => revokedUrls.push(url) }, performance: { now: () => clock.now },
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
    pending.promise = new Promise((resolve, reject) => { pending.resolve = (data = { ok: true }, code = 200) => resolve(response(data, code)); pending.reject = reject; pending.resolveFrame = options => resolve(previewResponse(options)); });
    deferred.push(pending);
    return pending;
  }
  return { document, window, clock, requests, createdUrls, revokedUrls, warnings, vibrations, players, livePlaylist, session, local, get, click, pointer, tap, key, enterText, defer,
    calls: url => requests.filter(request => request.url === url) };
}

function openSnapshots(ui) { ui.click('#preview-button'); ui.click('[data-view-mode="snapshots"]'); }

test('remote press haptics are immediate and do not repeat on hold, compatibility click or response', async () => {
  const ui = harness({ haptics: true }); await flush();
  assert.equal(ui.vibrations.length, 0, 'Opening and checking status stay quiet');
  const request = ui.defer('/api/key'); const up = ui.get('[data-key="up"]');
  ui.pointer('pointerdown', up);
  assert.deepEqual(ui.vibrations, [{ duration: 8, at: 0 }]);
  assert.equal(ui.calls('/api/key').length, 0, 'The pulse happens before awaiting network work');
  await flush(); request.resolve(); await flush(); await ui.clock.advance(850);
  assert.ok(ui.calls('/api/key').length > 1, 'The held button still repeats controls');
  ui.pointer('pointerup', up); up.dispatch('click', { detail: 1 }); await flush();
  assert.equal(ui.vibrations.length, 1);
  ui.tap('[data-key="up"]'); await flush();
  assert.equal(ui.vibrations.length, 2, 'A new deliberate press gets its own pulse');
});

test('Samsung presses pulse before network work and busy presses stay quiet', async () => {
  const ui = harness({ haptics: true }); await flush();
  const request = ui.defer('/api/samsung/key');
  ui.click('[data-samsung="volume_up"]');
  assert.equal(ui.vibrations.length, 1);
  ui.get('[data-samsung="volume_down"]').dispatch('click');
  assert.equal(ui.vibrations.length, 1);
  await flush(); request.resolve({ state: 'sent' }); await flush();
  assert.equal(ui.vibrations.length, 1);
  assert.equal(ui.calls('/api/samsung/key').length, 1);
});

test('haptic preference persists, enabling previews once and disabling stays quiet', async () => {
  const ui = harness({ haptics: true, hapticsPreference: '0' }); await flush();
  assert.equal(ui.get('#haptics-toggle').checked, false);
  assert.equal(ui.get('#haptics-test').disabled, true);
  ui.click('[data-key="home"]'); await flush(); assert.equal(ui.vibrations.length, 0);
  ui.get('#haptics-toggle').checked = true; ui.get('#haptics-toggle').dispatch('change');
  assert.equal(ui.local.get('nepal.remote.haptics'), '1');
  assert.equal(ui.vibrations.length, 1);
  ui.click('#haptics-test'); assert.equal(ui.vibrations.length, 2);
  assert.equal(ui.calls('/api/key').length, 1, 'Testing haptics sends no TV control');
  ui.get('#haptics-toggle').checked = false; ui.get('#haptics-toggle').dispatch('change');
  ui.click('[data-key="home"]'); await flush();
  assert.equal(ui.local.get('nepal.remote.haptics'), '0');
  assert.equal(ui.vibrations.length, 2);
  const reopened = harness({ haptics: true, hapticsPreference: ui.local.get('nepal.remote.haptics') }); await flush();
  assert.equal(reopened.get('#haptics-toggle').checked, false);
});

test('reduced motion defaults haptics off and an explicit preference can enable them', async () => {
  const ui = harness({ haptics: true, reducedMotion: true }); await flush();
  assert.equal(ui.get('#haptics-toggle').checked, false);
  ui.tap('[data-key="up"]'); await flush(); assert.equal(ui.vibrations.length, 0);
  ui.get('#haptics-toggle').checked = true; ui.get('#haptics-toggle').dispatch('change');
  ui.tap('[data-key="up"]'); await flush(); assert.equal(ui.vibrations.length, 2);
  const reopened = harness({ haptics: true, reducedMotion: true, hapticsPreference: '1' }); await flush();
  assert.equal(reopened.get('#haptics-toggle').checked, true);
});

test('unsupported, hidden and offline controls never produce haptics', async () => {
  const unsupported = harness(); await flush();
  assert.equal(unsupported.get('#haptics-toggle').disabled, true);
  assert.equal(unsupported.get('#haptics-test').disabled, true);
  assert.match(unsupported.get('#haptics-help').textContent, /doesn’t support/);
  unsupported.click('[data-key="home"]'); await flush(); assert.equal(unsupported.calls('/api/key').length, 1);
  const ui = harness({ haptics: true }); await flush();
  ui.document.hidden = true; ui.document.dispatch('visibilitychange');
  ui.get('[data-key="home"]').dispatch('click');
  ui.get('[data-samsung="volume_up"]').dispatch('click'); await flush();
  assert.equal(ui.vibrations.length, 0);
  ui.document.hidden = false; ui.window.dispatch('offline');
  ui.get('[data-key="home"]').dispatch('click');
  ui.get('[data-samsung="volume_up"]').dispatch('click'); await flush();
  assert.equal(ui.vibrations.length, 0);
});

test('blocked or throwing vibration never interrupts or retries TV commands', async t => {
  for (const fixture of [{ vibrationThrows: true }, { vibrationResult: false }]) await t.test(JSON.stringify(fixture), async () => {
    const ui = harness({ haptics: true, ...fixture }); await flush();
    ui.click('[data-key="home"]'); await flush();
    ui.click('[data-samsung="volume_up"]'); await flush();
    assert.equal(ui.calls('/api/key').length, 1); assert.equal(ui.calls('/api/samsung/key').length, 1);
    ui.click('#settings-button'); ui.click('#haptics-test');
    assert.match(ui.get('#settings-dialog').textContent, /Haptics are unavailable/);
    assert.equal(ui.calls('/api/key').length, 1); assert.equal(ui.calls('/api/samsung/key').length, 1);
  });
});

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

test('uncertain fast-key sends stop held repeats and never replay the action', async () => {
  const ui = harness(); await flush();
  const pending = ui.defer('/api/key');
  ui.pointer('pointerdown', ui.get('[data-key="right"]'));
  await flush(); await ui.clock.advance(500);
  pending.resolve({ error: 'key_send_uncertain' }, 503); await flush();
  await ui.clock.advance(2000);
  assert.equal(ui.calls('/api/key').length, 1);
  assert.match(ui.get('#toast').textContent, /may have reached the TV/);
  assert.equal(ui.get('[data-key="right"]').classList.contains('is-pressed'), false);
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

test('Samsung certificate failures block controls until explicit pairing succeeds, despite stale status', async t => {
  for (const code of ['certificate_required', 'certificate_mismatch', 'certificate_unavailable', 'invalid_certificate']) await t.test(code, async () => {
    const ui = harness(); await flush(); ui.click('#sources-button');
    const pending = ui.defer('/api/samsung/key');
    ui.click('[data-samsung="source"]'); await flush();
    pending.resolve({ state: 'error', error: `samsung_${code}` }, 503); await flush();
    assert.equal(ui.get('[data-samsung="source"]').disabled, true);
    assert.equal(ui.get('[data-samsung="volume_up"]').disabled, true);
    assert.match(ui.get('#sources-dialog-notice').textContent, /identity|verified/);
    assert.match(ui.get('#samsung-message').textContent, /Review|review/);
    assert.equal(ui.warnings.length, 0, 'Identity errors must not log certificate or token material');
    await ui.clock.advance(6000); // The fake status still advertises its old paired state.
    assert.equal(ui.get('[data-samsung="source"]').disabled, true);
    assert.equal(ui.calls('/api/samsung/key').length, 1, 'No implicit retry or re-pair is allowed');
    assert.equal(ui.calls('/api/samsung/pair').length, 0);
    ui.click('#sources-button');
    assert.equal(ui.get('#settings-dialog').open, true);
    const pair = ui.defer('/api/samsung/pair');
    ui.click('#samsung-pair'); await flush();
    pair.resolve({ state: 'authorized' }); await flush();
    assert.equal(ui.get('[data-samsung="source"]').disabled, false);
    assert.equal(ui.calls('/api/samsung/pair').length, 1);
  });
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
  assert.deepEqual(modes, ['auto', 'morning', 'dusk', 'night', 'annapurna', 'pashupati']);
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

test('Theater Settings shortcut sends its fixed app alias once and follows TV availability', async () => {
  const ui = harness(); await flush(); ui.click('#settings-button');
  assert.equal(ui.get('#framextv-button').textContent, 'Open Theater');
  ui.click('#framextv-button'); await flush();
  assert.deepEqual(ui.calls('/api/app').map(request => request.body), [{ app: 'framextv' }]);
  assert.equal(ui.get('#settings-dialog').open, true);
  ui.window.dispatch('offline');
  assert.equal(ui.get('#framextv-button').disabled, true);
  assert.equal(ui.calls('/api/app').length, 1);
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


test('screen preview opens without capturing; Start sends one authenticated fixed frame request', async () => {
  const ui = harness(); await flush();
  openSnapshots(ui); await flush();
  assert.equal(ui.calls('/api/preview/frame').length, 0);
  assert.equal(ui.get('#preview-image').hidden, true);
  ui.click('#preview-toggle'); await flush();
  const request = ui.calls('/api/preview/frame')[0];
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.headers.Authorization, 'Bearer unit-test-token');
  assert.deepEqual(request.body, {});
  assert.equal(request.options.cache, 'no-store');
  assert.equal(ui.get('#preview-image').hidden, false);
  assert.match(ui.get('#preview-image').getAttribute('src'), /^blob:/);
  assert.match(ui.get('#preview-captured').textContent, /Capture started/);
  assert.equal(ui.get('#preview-toggle').textContent, 'Stop preview');
});

test('preview captures sequentially, revokes replaced frames, and pauses without another capture', async () => {
  const ui = harness(); await flush(); openSnapshots(ui);
  const pending = ui.defer('/api/preview/frame');
  ui.click('#preview-toggle'); await flush();
  await ui.clock.advance(10000);
  assert.equal(ui.calls('/api/preview/frame').length, 1);
  pending.resolveFrame(); await flush();
  await ui.clock.advance(3000);
  assert.equal(ui.calls('/api/preview/frame').length, 2);
  assert.deepEqual(ui.revokedUrls, [ui.createdUrls[0]]);
  ui.click('#preview-toggle'); await flush();
  assert.match(ui.get('#preview-state').textContent, /paused/);
  assert.equal(ui.get('#preview-image').hidden, false);
  await ui.clock.advance(15000);
  assert.equal(ui.calls('/api/preview/frame').length, 2);
});

test('closing, hiding, leaving or losing connection clears preview and cancels further frames', async t => {
  for (const reason of ['close', 'hidden', 'pagehide', 'offline']) await t.test(reason, async () => {
    const ui = harness(); await flush(); openSnapshots(ui); ui.click('#preview-toggle'); await flush();
    if (reason === 'close') ui.get('#preview-dialog').close();
    if (reason === 'hidden') { ui.document.hidden = true; ui.document.dispatch('visibilitychange'); }
    if (reason === 'pagehide') ui.window.dispatch('pagehide');
    if (reason === 'offline') ui.window.dispatch('offline');
    await flush(); await ui.clock.advance(10000);
    assert.equal(ui.calls('/api/preview/frame').length, 1);
    assert.equal(ui.get('#preview-image').hidden, true);
    assert.equal(ui.get('#preview-image').getAttribute('src'), null);
    assert.deepEqual(ui.revokedUrls, ui.createdUrls);
  });
});

test('late frame after stop cannot restore a private image or restart preview', async () => {
  const ui = harness(); await flush(); openSnapshots(ui);
  const pending = ui.defer('/api/preview/frame', { ignoreAbort: true });
  ui.click('#preview-toggle'); await flush();
  ui.get('#preview-dialog').close(); await flush();
  assert.equal(pending.request.options.signal.aborted, true);
  pending.resolveFrame(); await flush(); await ui.clock.advance(10000);
  assert.equal(ui.get('#preview-image').hidden, true);
  assert.equal(ui.createdUrls.length, 0);
  assert.equal(ui.calls('/api/preview/frame').length, 1);
});

test('preview session expiry clears its image and opens pairing instead of retrying', async () => {
  const ui = harness(); await flush(); openSnapshots(ui);
  const pending = ui.defer('/api/preview/frame'); ui.click('#preview-toggle'); await flush();
  pending.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.get('#pair-dialog').open, true);
  assert.equal(ui.get('#preview-dialog').open, false);
  assert.equal(ui.get('#preview-image').hidden, true);
  await ui.clock.advance(10000);
  assert.equal(ui.calls('/api/preview/frame').length, 1);
});

test('invalid or oversized preview response never gets an object URL', async t => {
  for (const preview of [{ mime: 'image/svg+xml' }, { length: 1048577 }, { capturedAt: 'unknown' }]) await t.test(JSON.stringify(preview), async () => {
    const ui = harness({ preview }); await flush(); openSnapshots(ui); ui.click('#preview-toggle'); await flush();
    assert.equal(ui.createdUrls.length, 0);
    assert.equal(ui.get('#preview-image').hidden, true);
    assert.match(ui.get('#preview-state').textContent, /usable screen image/);
  });
});

test('preview navigation sends fixed TV actions while Escape only closes the preview', async () => {
  const ui = harness(); await flush(); openSnapshots(ui);
  ui.key('ArrowUp', ui.get('.preview-up')); await flush();
  assert.equal(ui.calls('/api/key')[0].body.key, 'up');
  ui.key('Escape', ui.get('#preview-toggle')); await flush();
  assert.equal(ui.get('#preview-dialog').open, false);
  assert.equal(ui.calls('/api/key').length, 1);
});

test('preview remains disabled until paired and does not resume when visible again', async () => {
  const unpaired = harness({ paired: false }); await flush();
  assert.equal(unpaired.get('#preview-button').disabled, true);
  const ui = harness(); await flush(); openSnapshots(ui); ui.click('#preview-toggle'); await flush();
  ui.document.hidden = true; ui.document.dispatch('visibilitychange');
  ui.document.hidden = false; ui.document.dispatch('visibilitychange'); await flush(); await ui.clock.advance(10000);
  assert.equal(ui.calls('/api/preview/frame').length, 1);
  assert.equal(ui.get('#preview-toggle').textContent, 'Start preview');
});

test('Live view opens passively in video mode without starting or probing a stream', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button'); await flush();
  assert.equal(ui.get('[data-view-mode="video"]').getAttribute('aria-pressed'), 'true');
  assert.equal(ui.get('#preview-toggle').textContent, 'Start live video');
  assert.equal(ui.get('#live-video').hidden, true);
  assert.equal(ui.get('#live-video').playCalls, 0);
  assert.equal(ui.requests.filter(request => request.url.startsWith('/api/live/')).length, 0);
  assert.equal(ui.calls('/api/preview/frame').length, 0);
  ui.get('#preview-dialog').close(); await flush();
  assert.equal(ui.calls('/api/live/stop').length, 0, 'An unopened producer needs no stop request');
});

test('explicit Live start authenticates, waits for ready, and labels playing only after a media event', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
  const start = ui.calls('/api/live/start')[0];
  assert.equal(start.options.method, 'POST');
  assert.equal(start.options.headers.Authorization, 'Bearer unit-test-token');
  assert.deepEqual(start.body, {});
  assert.equal(start.options.credentials, 'same-origin');
  assert.equal(ui.get('#live-video').hidden, true, 'Starting is not a playable stream');
  await ui.clock.advance(1200);
  const video = ui.get('#live-video');
  assert.equal(ui.calls('/api/live/status').length, 1);
  assert.equal(ui.calls('/api/live/status')[0].options.headers.Authorization, 'Bearer unit-test-token');
  assert.equal(video.src, ui.livePlaylist);
  assert.equal(video.hidden, false);
  assert.equal(video.hasAttribute('muted'), true);
  assert.equal(video.hasAttribute('playsinline'), true);
  assert.equal(video.hasAttribute('controls'), true);
  assert.equal(video.playCalls, 1);
  assert.doesNotMatch(ui.get('#preview-state').textContent, /playing/);
  video.dispatch('playing');
  assert.match(ui.get('#preview-state').textContent, /playing/);
  video.dispatch('waiting');
  assert.match(ui.get('#preview-state').textContent, /Buffering/);
  assert.equal(ui.calls('/api/preview/frame').length, 0, 'Video does not drive screenshot captures');
  assert.equal(ui.get('#preview-toggle').textContent, 'Stop live view');
});

test('visible Live view renews its owner lease and polls without duplicating the player', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
  await ui.clock.advance(21000);
  assert.equal(ui.calls('/api/live/keepalive').length, 2);
  for (const request of ui.calls('/api/live/keepalive')) {
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.headers.Authorization, 'Bearer unit-test-token');
    assert.deepEqual(request.body, {});
  }
  assert.equal(ui.get('#live-video').playCalls, 1);
  assert.equal(ui.calls('/api/live/start').length, 1);
});

test('Live video falls back to a usable Play button when autoplay is rejected', async () => {
  const ui = harness({ playError: new Error('NotAllowedError') }); await flush();
  ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  assert.equal(ui.get('#live-play').hidden, false);
  assert.match(ui.get('#preview-state').textContent, /Tap Play/);
  assert.equal(ui.get('#live-video').hasAttribute('controls'), true);
  ui.get('#live-video').playError = null;
  ui.click('#live-play'); await flush();
  assert.equal(ui.get('#live-video').playCalls, 2);
  ui.get('#live-video').dispatch('playing');
  assert.equal(ui.get('#live-play').hidden, true);
  assert.match(ui.get('#preview-state').textContent, /playing/);
});

test('Chromium uses the local HLS player with bounded buffers and destroys it on stop', async () => {
  const ui = harness({ nativeHls: false, hls: true }); await flush();
  ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  assert.equal(ui.players.length, 1);
  const player = ui.players[0];
  assert.deepEqual(JSON.parse(JSON.stringify(player.config)), {
    enableWorker: false, lowLatencyMode: false,
    liveSyncDurationCount: 2, liveMaxLatencyDurationCount: 4,
    liveSyncOnStallIncrease: 0.25, maxBufferLength: 6, backBufferLength: 6,
  });
  assert.equal(player.source, ui.livePlaylist);
  assert.equal(player.media, ui.get('#live-video'));
  assert.equal(ui.get('#live-video').playCalls, 0);
  player.emit('manifest'); await flush();
  assert.equal(ui.get('#live-video').playCalls, 1);
  ui.click('#preview-toggle'); await flush();
  assert.equal(player.destroyed, true);
  assert.equal(ui.get('#live-video').hidden, true);
  assert.equal(ui.get('#live-video').src, '');
  assert.equal(ui.get('#live-video').paused, true);
  assert.equal(ui.calls('/api/live/stop').length, 1);
  assert.equal(ui.calls('/api/live/stop')[0].options.keepalive, true);
  assert.equal(ui.calls('/api/live/stop')[0].options.headers.Authorization, 'Bearer unit-test-token');
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/live/keepalive').length, 0);
});

test('supported MSE HLS is preferred even when the browser advertises native HLS', async () => {
  const ui = harness({ nativeHls: true, hls: true }); await flush();
  ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  assert.equal(ui.players.length, 1);
  assert.equal(ui.players[0].source, ui.livePlaylist);
  assert.equal(ui.get('#live-video').src, '', 'A native-support hint must not bypass the supported local MSE player');
  assert.equal(ui.get('#live-video').playCalls, 0);
  ui.players[0].emit('manifest'); await flush();
  assert.equal(ui.get('#live-video').playCalls, 1);
});

test('native HLS remains the fallback when MSE HLS is unavailable', async () => {
  const ui = harness({ nativeHls: true }); await flush();
  ui.window.Hls = { isSupported: () => false };
  ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  assert.equal(ui.players.length, 0);
  assert.equal(ui.get('#live-video').src, ui.livePlaylist);
  assert.equal(ui.get('#live-video').playCalls, 1);
});

test('resuming delayed live playback catches up for Hls.js and native HLS', async t => {
  for (const hls of [false, true]) await t.test(hls ? 'Hls.js' : 'native', async () => {
    const ui = harness({ hls }); await flush();
    ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
    const video = ui.get('#live-video');
    if (hls) { ui.players[0].emit('manifest'); await flush(); ui.players[0].liveSyncPosition = 19; }
    video.seekable = { length: 1, start: () => 10, end: () => 20 };
    video.currentTime = 11;
    video.paused = true;
    video.dispatch('progress');
    assert.equal(video.currentTime, 11, 'A deliberate pause remains paused');
    video.paused = false;
    video.dispatch('play');
    assert.equal(video.currentTime, hls ? 19 : 18);
    ui.click('#preview-toggle'); await flush();
    video.currentTime = 11; video.paused = false;
    video.dispatch('play'); video.dispatch('progress');
    assert.equal(video.currentTime, 11, 'Stopped sessions have no catch-up listeners');
  });
});

test('native HLS bounds playback drift without seeking healthy or user-seeking video', async () => {
  const ui = harness(); await flush();
  ui.click('#preview-button'); ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  const video = ui.get('#live-video');
  video.dispatch('timeupdate'); // No seekable range during startup.
  video.seekable = { length: 1, start: () => 10, end: () => 20 };
  video.currentTime = 17;
  video.dispatch('timeupdate');
  assert.equal(video.currentTime, 17);
  video.currentTime = 11; video.seeking = true;
  video.dispatch('progress');
  assert.equal(video.currentTime, 11);
  video.seeking = false; video.dispatch('timeupdate');
  assert.equal(video.currentTime, 18);
  video.currentTime = 11;
  video.seekable = { length: 1, start: () => { throw new Error('Range changed'); } };
  assert.doesNotThrow(() => video.dispatch('progress'));
  assert.equal(ui.calls('/api/live/stop').length, 0, 'A changing range must not end video');
});

test('closing, hiding, leaving, losing connection or switching modes stops continuous video', async t => {
  for (const reason of ['close', 'hidden', 'pagehide', 'offline', 'snapshots', 'other-sheet']) await t.test(reason, async () => {
    const ui = harness(); await flush(); ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
    await ui.clock.advance(1200);
    if (reason === 'close') ui.get('#preview-dialog').close();
    if (reason === 'hidden') { ui.document.hidden = true; ui.document.dispatch('visibilitychange'); }
    if (reason === 'pagehide') ui.window.dispatch('pagehide');
    if (reason === 'offline') ui.window.dispatch('offline');
    if (reason === 'snapshots') ui.click('[data-view-mode="snapshots"]');
    if (reason === 'other-sheet') ui.click('#airplay-button');
    await flush(); await ui.clock.advance(30000);
    assert.equal(ui.calls('/api/live/stop').length, 1);
    assert.equal(ui.get('#live-video').hidden, true);
    assert.equal(ui.get('#live-video').src, '');
    assert.equal(ui.get('#live-video').paused, true);
    assert.equal(ui.calls('/api/live/keepalive').length, 0);
    assert.equal(ui.calls('/api/preview/frame').length, 0, 'Mode switch remains passive');
    if (reason === 'hidden') {
      ui.document.hidden = false; ui.document.dispatch('visibilitychange'); await flush();
      assert.equal(ui.calls('/api/live/start').length, 1, 'Becoming visible must not restart video');
    }
  });
});

test('late Start is stopped before a new generation can start and cannot restore video', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button');
  const start = ui.defer('/api/live/start', { ignoreAbort: true });
  const stop = ui.defer('/api/live/stop');
  ui.click('#preview-toggle'); await flush();
  ui.get('#preview-dialog').close(); await flush(); ui.click('#preview-button'); await flush();
  assert.equal(ui.get('#preview-toggle').disabled, true);
  assert.equal(ui.get('#preview-toggle').textContent, 'Stopping…');
  assert.equal(ui.calls('/api/live/stop').length, 0, 'Do not race Stop ahead of pending Start');
  start.resolve({ state: 'ready', videoOnly: true, playlist: ui.livePlaylist }); await flush();
  assert.equal(ui.calls('/api/live/stop').length, 1);
  assert.equal(ui.get('#live-video').playCalls, 0);
  assert.equal(ui.get('#preview-toggle').disabled, true, 'Old cleanup must finish before the next Start');
  stop.resolve({ state: 'stopped', videoOnly: true }); await flush();
  assert.equal(ui.get('#preview-toggle').disabled, false);
  assert.equal(ui.calls('/api/live/start').length, 1);
  ui.click('#preview-toggle'); await flush();
  assert.equal(ui.calls('/api/live/start').length, 2);
});

test('Stop aborts in-flight status and ignores a late ready response', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button');
  const status = ui.defer('/api/live/status', { ignoreAbort: true });
  ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  ui.click('#preview-toggle'); await flush();
  assert.equal(status.request.options.signal.aborted, true);
  status.resolve({ state: 'ready', videoOnly: true, playlist: ui.livePlaylist }); await flush(); await ui.clock.advance(30000);
  assert.equal(ui.get('#live-video').hidden, true);
  assert.equal(ui.get('#live-video').playCalls, 0);
  assert.equal(ui.calls('/api/live/status').length, 1);
  assert.equal(ui.calls('/api/live/keepalive').length, 0);
});

test('late cleanup uses its captured owner token and cannot expire a newly paired session', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button');
  const start = ui.defer('/api/live/start', { ignoreAbort: true });
  const stop = ui.defer('/api/live/stop');
  ui.click('#preview-toggle'); await flush();
  ui.get('#preview-dialog').close(); await flush();
  ui.click('#pairing-button');
  const pair = ui.defer('/api/session/pair');
  ui.get('#pair-pin').value = '123456'; ui.get('#pair-form').dispatch('submit'); await flush();
  pair.resolve({ token: 'new-session-token' }); await flush();
  assert.equal(ui.get('#pair-dialog').open, false);
  start.resolve({ state: 'ready', videoOnly: true, playlist: ui.livePlaylist }); await flush();
  assert.equal(ui.calls('/api/live/stop')[0].options.headers.Authorization, 'Bearer unit-test-token');
  stop.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.get('#pair-dialog').open, false, 'Old cleanup must not log out the new session');
  assert.equal(ui.get('#live-video').playCalls, 0);
  ui.click('[data-key="home"]'); await flush();
  assert.equal(ui.calls('/api/key')[0].options.headers.Authorization, 'Bearer new-session-token');
});

test('remembered browsers restore a session on open without PIN, controls or capture', async () => {
  const restored = 'r'.repeat(43);
  const ui = harness({ paired: false, remembered: true, resume: { token: restored, remembered: true } });
  await flush(); await ui.clock.advance(0);
  assert.equal(ui.calls('/api/session/resume').length, 1);
  assert.deepEqual(ui.calls('/api/session/resume')[0].body, {});
  assert.equal(ui.calls('/api/session/resume')[0].options.headers.Authorization, undefined);
  assert.equal(ui.session.get('nepal.remote.session'), restored);
  assert.equal(ui.local.get('nepal.remote.remembered'), '1');
  assert.equal(ui.get('[data-key="home"]').disabled, false);
  assert.equal(ui.get('#pair-dialog').open, false);
  for (const route of ['/api/session/pair', '/api/key', '/api/reconnect', '/api/preview/frame', '/api/live/start']) assert.equal(ui.calls(route).length, 0, route);
});

test('pairing remembers by default and stores only a non-secret local flag', async t => {
  for (const remember of [true, false]) await t.test(String(remember), async () => {
    const ui = harness({ paired: false }); await flush();
    assert.equal(ui.get('#pair-remember').checked, true);
    ui.click('#connection-button'); ui.get('#pair-remember').checked = remember;
    ui.get('#pair-pin').value = '123456'; ui.get('#pair-form').dispatch('submit'); await flush();
    assert.deepEqual(ui.calls('/api/session/pair')[0].body, { pin: '123456', remember });
    assert.equal(ui.local.get('nepal.remote.remembered'), remember ? '1' : undefined);
    assert.deepEqual([...ui.local.values()], remember ? ['1'] : []);
    assert.equal(ui.get('#pair-dialog').open, false);
    assert.equal(ui.get('#forget-device').hidden, false);
  });
});

test('expired access restores once, discards queued inputs and never replays a failed key', async () => {
  const restored = 'r'.repeat(43);
  const ui = harness({ remembered: true, resume: { token: restored, remembered: true } }); await flush();
  const key = ui.defer('/api/key');
  ui.tap('[data-key="up"]'); await flush();
  ui.click('[data-key="home"]');
  key.resolve({ error: 'session_expired' }, 401); await flush(); await ui.clock.advance(0);
  assert.equal(ui.calls('/api/session/resume').length, 1);
  assert.equal(ui.calls('/api/key').length, 1);
  assert.equal(ui.get('#pair-dialog').open, false);
  ui.click('[data-key="home"]'); await flush();
  assert.equal(ui.calls('/api/key').length, 2);
  assert.equal(ui.calls('/api/key')[1].options.headers.Authorization, `Bearer ${restored}`);
});

test('concurrent expired requests share one restoration', async () => {
  const ui = harness({ remembered: true }); await flush();
  const status = ui.defer('/api/status'); const key = ui.defer('/api/key'); const resume = ui.defer('/api/session/resume');
  await ui.clock.advance(6000); ui.tap('[data-key="up"]'); await flush();
  key.resolve({ error: 'session_expired' }, 401); await flush();
  status.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.calls('/api/session/resume').length, 1);
  resume.resolve({ token: 'r'.repeat(43), remembered: true }); await flush(); await ui.clock.advance(0);
  assert.equal(ui.get('[data-key="home"]').disabled, false);
  assert.equal(ui.calls('/api/key').length, 1);
});

test('expired saved pairing clears the hint and asks for PIN without retrying controls', async () => {
  const ui = harness({ remembered: true }); await flush();
  const key = ui.defer('/api/key'); ui.click('[data-key="home"]'); await flush();
  key.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.local.has('nepal.remote.remembered'), false);
  assert.equal(ui.session.has('nepal.remote.session'), false);
  assert.equal(ui.get('#pair-dialog').open, true);
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/session/resume').length, 1);
  assert.equal(ui.calls('/api/key').length, 1);
});

test('unavailable saved pairing keeps its hint and reconnect can retry', async () => {
  const ui = harness({ remembered: true }); await flush();
  const key = ui.defer('/api/key'); const resume = ui.defer('/api/session/resume');
  ui.click('[data-key="home"]'); await flush(); key.resolve({ error: 'session_expired' }, 401); await flush();
  resume.reject(new TypeError('Offline')); await flush();
  assert.equal(ui.local.get('nepal.remote.remembered'), '1');
  assert.equal(ui.get('#mac-status').textContent, 'Unavailable');
  assert.equal(ui.get('#pair-dialog').open, false);
  const retry = ui.defer('/api/session/resume'); ui.click('#reconnect-button'); await flush();
  retry.resolve({ token: 'r'.repeat(43), remembered: true }); await flush(); await ui.clock.advance(0);
  assert.equal(ui.get('#mac-status').textContent, 'Available');
  assert.equal(ui.get('[data-key="home"]').disabled, false);
});

test('forget revokes before clearing saved state and keeps controls disabled during the request', async () => {
  const ui = harness({ remembered: true }); await flush(); ui.click('#settings-button');
  const forget = ui.defer('/api/session/forget'); ui.click('#forget-device'); await flush();
  assert.equal(ui.get('[data-key="home"]').disabled, true);
  assert.equal(ui.get('#forget-device').disabled, true);
  assert.equal(ui.local.get('nepal.remote.remembered'), '1');
  forget.resolve({ ok: true }); await flush(); await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/session/forget')[0].options.headers.Authorization, 'Bearer unit-test-token');
  assert.equal(ui.local.size, 0); assert.equal(ui.session.size, 0);
  assert.equal(ui.get('#settings-dialog').open, false);
  assert.equal(ui.get('#pair-dialog').open, true);
  assert.match(ui.get('#pair-error').textContent, /forgotten/);
  assert.equal(ui.calls('/api/session/resume').length, 0);
  assert.equal(ui.calls('/api/key').length, 0);
});

test('failed durable forget keeps credentials and does not claim successful revocation', async () => {
  const ui = harness({ remembered: true }); await flush(); ui.click('#settings-button');
  const forget = ui.defer('/api/session/forget'); ui.click('#forget-device'); await flush();
  forget.resolve({ error: 'trusted_device_storage_unavailable' }, 503); await flush();
  assert.equal(ui.local.get('nepal.remote.remembered'), '1');
  assert.equal(ui.session.get('nepal.remote.session'), 'unit-test-token');
  assert.equal(ui.get('#settings-dialog').open, true);
  assert.equal(ui.get('#pair-dialog').open, false);
  assert.equal(ui.get('#forget-device').disabled, false);
  assert.doesNotMatch(ui.get('#toast').textContent, /was forgotten/);
});

test('forget with expired access restores pairing and requires a fresh deliberate forget', async () => {
  const ui = harness({ remembered: true, resume: { token: 'r'.repeat(43), remembered: true } }); await flush();
  ui.click('#settings-button'); const forget = ui.defer('/api/session/forget');
  ui.click('#forget-device'); await flush(); forget.resolve({ error: 'session_expired' }, 401);
  await flush(); await ui.clock.advance(0);
  assert.equal(ui.calls('/api/session/forget').length, 1);
  assert.equal(ui.local.get('nepal.remote.remembered'), '1');
  assert.equal(ui.session.get('nepal.remote.session'), 'r'.repeat(43));
  assert.match(ui.get('#settings-dialog').textContent, /Tap Forget this device again/);
  ui.click('#forget-device'); await flush();
  assert.equal(ui.calls('/api/session/forget').length, 2);
  assert.equal(ui.local.size, 0); assert.equal(ui.session.size, 0);
});

test('late restoration cannot replace a newer manual pairing', async () => {
  const ui = harness({ remembered: true }); await flush();
  const key = ui.defer('/api/key'); const resume = ui.defer('/api/session/resume');
  ui.click('[data-key="home"]'); await flush(); key.resolve({ error: 'session_expired' }, 401); await flush();
  ui.click('#pairing-button'); ui.get('#pair-pin').value = '123456'; ui.get('#pair-form').dispatch('submit'); await flush();
  assert.equal(ui.session.get('nepal.remote.session'), 'p'.repeat(43));
  resume.resolve({ token: 'r'.repeat(43), remembered: true }); await flush(); await ui.clock.advance(0);
  assert.equal(ui.session.get('nepal.remote.session'), 'p'.repeat(43));
  assert.equal(ui.get('#pair-dialog').open, false);
  assert.equal(ui.get('[data-key="home"]').disabled, false);
});

test('restoring expired video access stops capture and never resumes video automatically', async () => {
  const ui = harness({ remembered: true, resume: { token: 'r'.repeat(43), remembered: true } }); await flush();
  ui.click('#preview-button'); const status = ui.defer('/api/live/status');
  ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  status.resolve({ error: 'session_expired' }, 401); await flush(); await ui.clock.advance(30000);
  assert.equal(ui.get('#pair-dialog').open, false);
  assert.equal(ui.get('#live-video').hidden, true);
  assert.equal(ui.calls('/api/live/start').length, 1);
  assert.equal(ui.calls('/api/live/stop').length, 1);
  assert.equal(ui.calls('/api/preview/frame').length, 0);
  assert.equal(ui.get('[data-key="home"]').disabled, false);
});

test('Live status failures clear video and do not retry or mislabel the whole Mac offline', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
  await ui.clock.advance(1200);
  const status = ui.defer('/api/live/status');
  await ui.clock.advance(5000);
  status.resolve({ state: 'error', error: 'live_connection_lost', videoOnly: true }); await flush();
  assert.equal(ui.get('#live-video').hidden, true);
  assert.match(ui.get('#preview-state').textContent, /connection ended/);
  assert.equal(ui.get('#mac-status').textContent, 'Available');
  assert.equal(ui.calls('/api/live/stop').length, 1);
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/live/start').length, 1);
  assert.equal(ui.calls('/api/live/keepalive').length, 0);
});

test('expired Live authentication stops the stream and opens pairing without replay', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button');
  const status = ui.defer('/api/live/status');
  ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  status.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.get('#pair-dialog').open, true);
  assert.equal(ui.get('#preview-dialog').open, false);
  assert.equal(ui.get('#live-video').hidden, true);
  assert.equal(ui.calls('/api/live/stop').length, 1);
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/live/start').length, 1);
});

test('invalid Live playlist paths cannot load remote media or leak tokens', async t => {
  for (const playlist of ['https://example.com/live.m3u8', '/api/live/../index.m3u8', '/api/live/0123456789abcdef0123456789abcdef/index.m3u8?token=secret']) await t.test(playlist, async () => {
    const ui = harness({ liveStart: { state: 'ready', videoOnly: true, playlist } }); await flush();
    ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
    assert.equal(ui.get('#live-video').playCalls, 0);
    assert.equal(ui.get('#live-video').src, '');
    assert.equal(ui.calls('/api/live/stop').length, 1);
    assert.match(ui.get('#preview-state').textContent, /invalid video session/);
  });
});

test('a Stop timeout releases only the local barrier and never starts video automatically', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button'); ui.click('#preview-toggle'); await flush();
  ui.defer('/api/live/stop'); ui.click('#preview-toggle'); await flush();
  assert.equal(ui.get('#preview-toggle').disabled, true);
  await ui.clock.advance(20000);
  assert.equal(ui.get('#preview-toggle').disabled, false);
  assert.equal(ui.get('#preview-toggle').textContent, 'Start live video');
  assert.equal(ui.calls('/api/live/start').length, 1);
  assert.equal(ui.get('#live-video').hidden, true);
});

test('Live D-pad controls work before playback and while paused; Escape only closes the sheet', async () => {
  const ui = harness(); await flush(); ui.click('#preview-button');
  ui.key('ArrowUp', ui.get('.preview-up')); await flush();
  ui.click('#preview-toggle'); await flush(); await ui.clock.advance(1200);
  ui.get('#live-video').pause(); ui.get('#live-video').dispatch('pause');
  ui.key('ArrowDown', ui.get('.preview-down')); await flush();
  assert.deepEqual(ui.calls('/api/key').map(request => request.body.key), ['up', 'down']);
  ui.key('Escape', ui.get('#preview-toggle')); await flush();
  assert.equal(ui.get('#preview-dialog').open, false);
  assert.equal(ui.calls('/api/key').length, 2);
  assert.equal(ui.calls('/api/live/stop').length, 1);
});

test('native and fatal HLS diagnostics include only their selected safe fields', async () => {
  const native = harness(); await flush(); native.click('#preview-button'); native.click('#preview-toggle'); await flush();
  await native.clock.advance(1200);
  native.get('#live-video').error = { code: 4, message: 'Unsupported media', privateValue: 'do-not-log' };
  native.get('#live-video').dispatch('error'); await flush();
  assert.equal(native.warnings.length, 1);
  assert.deepEqual(native.warnings[0], ['Nepal Remote: native video error', JSON.stringify({ code: 4, message: 'Unsupported media' })]);
  const hls = harness({ nativeHls: false, hls: true }); await flush(); hls.click('#preview-button'); hls.click('#preview-toggle'); await flush();
  await hls.clock.advance(1200);
  hls.players[0].emit('error', { fatal: true, type: 'networkError', details: 'manifestLoadError',
    response: { code: 401, url: 'private-media-url', data: 'private-data' }, token: 'private-token' });
  await flush();
  assert.equal(hls.warnings.length, 1);
  assert.deepEqual(hls.warnings[0], ['Nepal Remote: fatal HLS error', JSON.stringify({ type: 'networkError', details: 'manifestLoadError', responseCode: 401 })]);
  assert.equal(hls.players[0].destroyed, true);
});


function enterTheater(ui, value) {
  const input = ui.get('#theater-request');
  assert.equal(input.disabled, false, 'Theater requests must be editable before changing a draft');
  input.value = value;
  input.dispatch('input');
}

test('Play on Theater is a visible paired action with a labeled, focused request field', async () => {
  const ui = harness(); await flush();
  const button = ui.get('#theater-button');
  assert.equal(button.closest('#settings-dialog'), null);
  assert.equal(button.getAttribute('aria-controls'), 'theater-dialog');
  ui.click(button);
  assert.equal(ui.get('#theater-dialog').open, true);
  assert.equal(ui.document.activeElement, ui.get('#theater-request'));
  assert.equal(ui.get('#theater-submit').disabled, true);
  assert.match(ui.get('#theater-dialog').textContent, /phone keyboard’s microphone/);
  assert.match(ui.get('#theater-examples').textContent, /Breaking Bad s2e3/);
  assert.equal(ui.calls('/api/theater').length, 0);
});

test('Theater actions require pairing, a reachable Mac and an online Fire TV', async () => {
  const unpaired = harness({ paired: false }); await flush();
  assert.equal(unpaired.get('#theater-button').disabled, true);
  unpaired.get('#theater-button').dispatch('click');
  assert.equal(unpaired.get('#theater-dialog').open, false);
  enterTheater(unpaired, 'play Inception');
  unpaired.get('#theater-form').dispatch('submit'); await flush();
  assert.equal(unpaired.calls('/api/theater').length, 0);

  const ui = harness(); await flush(); ui.click('#theater-button');
  enterTheater(ui, 'play Inception');
  ui.window.dispatch('offline');
  assert.equal(ui.get('#theater-button').disabled, true);
  assert.equal(ui.get('#theater-submit').disabled, true);
  assert.match(ui.get('#theater-state').textContent, /Mac is unavailable/);
  ui.get('#theater-form').dispatch('submit'); await flush();
  assert.equal(ui.calls('/api/theater').length, 0);

  const tvOffline = harness(); await flush();
  const status = tvOffline.defer('/api/status');
  await tvOffline.clock.advance(6000);
  status.resolve({ online: false, samsung: { enabled: true, paired: true } }); await flush();
  assert.equal(tvOffline.get('#theater-button').disabled, true);
  tvOffline.get('#theater-button').dispatch('click');
  assert.equal(tvOffline.calls('/api/theater').length, 0);
});

test('Theater sends one trimmed request, locks duplicate submits and confirms delivery without claiming playback', async () => {
  const ui = harness(); await flush(); ui.click('#theater-button');
  enterTheater(ui, '  play Inception (2010)  ');
  const pending = ui.defer('/api/theater');
  ui.get('#theater-form').dispatch('submit');
  ui.get('#theater-form').dispatch('submit'); await flush();
  assert.equal(ui.get('#theater-request').disabled, true);
  assert.equal(ui.get('#theater-submit').disabled, true);
  assert.deepEqual(ui.calls('/api/theater').map(request => request.body), [{ request: 'play Inception (2010)' }]);
  pending.resolve({ ok: true, state: 'request_sent' }); await flush();
  assert.equal(ui.get('#theater-request').value, '');
  assert.equal(ui.get('#theater-request').disabled, false);
  assert.equal(ui.get('#theater-dialog-notice').textContent, 'Request sent to Theater. Check the TV.');
  assert.equal(ui.get('#theater-dialog-notice').hidden, false);
  assert.equal(ui.calls('/api/theater').length, 1);
  assert.equal([...ui.local.values()].some(value => String(value).includes('Inception')), false);
  assert.equal([...ui.session.values()].some(value => String(value).includes('Inception')), false);
  assert.equal(ui.warnings.length, 0);
});

test('Theater validates Unicode codepoint bounds and rejects empty, control or incomplete text', async t => {
  for (const value of ['', '  ', 'x'.repeat(161), 'play\nInception', 'play\tInception', 'x\u0085y', '\uD800']) await t.test(JSON.stringify(value), async () => {
    const ui = harness(); await flush(); ui.click('#theater-button'); enterTheater(ui, value);
    assert.equal(ui.get('#theater-submit').disabled, true);
    ui.get('#theater-form').dispatch('submit'); await flush();
    assert.equal(ui.calls('/api/theater').length, 0);
  });
  const ui = harness(); await flush(); ui.click('#theater-button');
  const value = '🎬'.repeat(160);
  enterTheater(ui, value);
  assert.equal(ui.get('#theater-count').textContent, '160 / 160');
  assert.equal(ui.get('#theater-submit').disabled, false);
  ui.get('#theater-form').dispatch('submit'); await flush();
  assert.equal(ui.calls('/api/theater')[0].body.request, value);
});

test('uncertain Theater failure retains the draft and never retries automatically', async t => {
  for (const outcome of ['uncertain', 'network', 'wrong-ack']) await t.test(outcome, async () => {
    const ui = harness(); await flush(); ui.click('#theater-button');
    enterTheater(ui, 'play Breaking Bad s2e3');
    const pending = ui.defer('/api/theater');
    ui.get('#theater-form').dispatch('submit'); await flush();
    if (outcome === 'uncertain') pending.resolve({ error: 'theater_send_uncertain' }, 503);
    else if (outcome === 'network') pending.reject(new Error('Disconnected'));
    else pending.resolve({ ok: true, state: 'playing' });
    await flush();
    assert.equal(ui.get('#theater-request').value, 'play Breaking Bad s2e3');
    assert.match(ui.get('#theater-error').textContent, /Check the TV before trying again/);
    assert.equal(ui.get('#theater-dialog-notice').hidden, true);
    assert.equal(ui.get('#theater-submit').disabled, false);
    await ui.clock.advance(30000);
    assert.equal(ui.calls('/api/theater').length, 1);
  });
});

test('closing, hiding or leaving drops an unsent Theater request behind another control', async t => {
  for (const reason of ['close', 'hidden', 'pagehide']) await t.test(reason, async () => {
    const ui = harness(); await flush();
    const blocking = ui.defer('/api/key');
    ui.click('[data-key="home"]'); await flush();
    ui.click('#theater-button'); enterTheater(ui, 'play Inception');
    ui.get('#theater-form').dispatch('submit'); await flush();
    assert.equal(ui.calls('/api/theater').length, 0);
    if (reason === 'close') ui.get('#theater-dialog').close();
    if (reason === 'hidden') { ui.document.hidden = true; ui.document.dispatch('visibilitychange'); }
    if (reason === 'pagehide') ui.window.dispatch('pagehide');
    blocking.resolve(); await flush();
    assert.equal(ui.calls('/api/theater').length, 0);
    ui.document.hidden = false; ui.document.dispatch('visibilitychange'); await flush();
    await ui.clock.advance(6000);
    assert.equal(ui.calls('/api/theater').length, 0, 'Returning cannot replay the old request');
    if (reason === 'close' || reason === 'pagehide') assert.equal(ui.get('#theater-request').value, '');
  });
});

test('a Theater request that expires in the input queue stays unsent and keeps its draft', async () => {
  const ui = harness(); await flush();
  const blocking = ui.defer('/api/key');
  ui.click('[data-key="home"]'); await flush();
  ui.click('#theater-button'); enterTheater(ui, 'play Inception');
  ui.get('#theater-form').dispatch('submit'); await flush();
  await ui.clock.advance(1201);
  blocking.resolve(); await flush();
  assert.equal(ui.calls('/api/theater').length, 0);
  assert.equal(ui.get('#theater-request').value, 'play Inception');
  assert.match(ui.get('#theater-error').textContent, /Request was not sent/);
});

test('late Theater outcomes cannot clear or report into a reopened newer draft', async t => {
  for (const outcome of ['sent', 'uncertain']) await t.test(outcome, async () => {
    const ui = harness(); await flush(); ui.click('#theater-button'); enterTheater(ui, 'old request');
    const pending = ui.defer('/api/theater');
    ui.get('#theater-form').dispatch('submit'); await flush();
    ui.get('#theater-dialog').close();
    ui.click('#theater-button'); enterTheater(ui, 'new request'); await flush();
    if (outcome === 'sent') pending.resolve({ ok: true, state: 'request_sent' });
    else pending.resolve({ error: 'theater_send_uncertain' }, 503);
    await flush();
    assert.equal(ui.get('#theater-request').value, 'new request');
    assert.equal(ui.get('#theater-error').textContent, '');
    assert.equal(ui.get('#theater-request').disabled, false);
    assert.equal(ui.get('#theater-dialog-notice').hidden, true);
    assert.equal(ui.calls('/api/theater').length, 1);
  });
});

test('backgrounding an in-flight Theater request preserves the draft and requires a fresh press', async () => {
  const ui = harness(); await flush(); ui.click('#theater-button'); enterTheater(ui, 'play Inception');
  const pending = ui.defer('/api/theater');
  ui.get('#theater-form').dispatch('submit'); await flush();
  ui.document.hidden = true; ui.document.dispatch('visibilitychange'); await flush();
  pending.resolve({ ok: true, state: 'request_sent' }); await flush();
  ui.document.hidden = false; ui.document.dispatch('visibilitychange'); await flush();
  assert.equal(ui.get('#theater-request').value, 'play Inception');
  assert.match(ui.get('#theater-error').textContent, /Check the TV before trying again/);
  assert.equal(ui.get('#theater-dialog-notice').hidden, true);
  assert.equal(ui.calls('/api/theater').length, 1);
});

test('an old Theater 401 preserves a newly paired session and newer draft', async () => {
  const ui = harness(); await flush(); ui.click('#theater-button'); enterTheater(ui, 'old request');
  const pending = ui.defer('/api/theater');
  ui.get('#theater-form').dispatch('submit'); await flush();
  ui.get('#pair-pin').value = '123456';
  ui.get('#pair-form').dispatch('submit'); await flush();
  assert.equal(ui.session.get('nepal.remote.session'), 'p'.repeat(43));
  enterTheater(ui, 'new request');
  pending.resolve({ error: 'session_expired' }, 401); await flush();
  assert.equal(ui.session.get('nepal.remote.session'), 'p'.repeat(43));
  assert.equal(ui.get('#pair-dialog').open, false);
  assert.equal(ui.get('#theater-request').value, 'new request');
  assert.equal(ui.get('#theater-error').textContent, '');
  assert.equal(ui.calls('/api/theater').length, 1);
});


test('Theater allows time for waking Fire TV before its launch acknowledgement', async () => {
  const ui = harness(); await flush(); ui.click('#theater-button');
  enterTheater(ui, 'play Inception (2010)');
  const pending = ui.defer('/api/theater');
  ui.get('#theater-form').dispatch('submit'); await flush();
  await ui.clock.advance(9000);
  assert.equal(pending.request.options.signal.aborted, false);
  assert.equal(ui.get('#theater-request').disabled, true);
  pending.resolve({ ok: true, state: 'request_sent' }); await flush();
  assert.equal(ui.get('#theater-request').value, '');
  assert.equal(ui.calls('/api/theater').length, 1);
});

test('Theater wake and launch remain bounded without replaying a timed-out request', async () => {
  const ui = harness(); await flush(); ui.click('#theater-button');
  enterTheater(ui, 'play Inception (2010)');
  const pending = ui.defer('/api/theater');
  ui.get('#theater-form').dispatch('submit'); await flush();
  await ui.clock.advance(12001);
  assert.equal(pending.request.options.signal.aborted, true);
  assert.equal(ui.get('#theater-request').value, 'play Inception (2010)');
  assert.match(ui.get('#theater-error').textContent, /Check the TV before trying again/);
  await ui.clock.advance(30000);
  assert.equal(ui.calls('/api/theater').length, 1);
});
