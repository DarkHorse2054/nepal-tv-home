'use strict';

// Synthetic provider controls only. These fixtures never load a player or a URL.
const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');

const asset = __dirname + '/../app/src/main/assets/theater-highest-quality.js';
const origin = 'https://framextv.cc';
const path = '/embed/101';
const selectors = {
  settings: 'button[aria-label="Player Settings"]',
  menu: '[role="menu"][aria-label="Playback Settings"]',
  back: 'button[aria-label="Back to main settings"]',
  close: 'button[aria-label="Close settings"]',
  items: 'button[role="menuitem"]',
  radios: 'button[role="menuitemradio"]'
};

class Clock {
  constructor() { this.now = 0; this.next = 1; this.tasks = new Map(); this.executed = 0; }
  setTimeout(callback, delay = 0) {
    const id = this.next++;
    this.tasks.set(id, { at: this.now + Math.max(0, Number(delay) || 0), callback });
    return id;
  }
  clearTimeout(id) { this.tasks.delete(id); }
  setInterval(callback, delay = 0) {
    const id = this.next++;
    this.tasks.set(id, { at: this.now + Math.max(1, Number(delay) || 0), callback,
      interval: Math.max(1, Number(delay) || 0) });
    return id;
  }
  clearInterval(id) { this.tasks.delete(id); }
  tick(milliseconds, budget = 2000) {
    const target = this.now + milliseconds;
    let callbacks = 0;
    for (;;) {
      let nextId, nextTask;
      for (const [id, task] of this.tasks) {
        if (task.at <= target && (!nextTask || task.at < nextTask.at ||
            (task.at === nextTask.at && id < nextId))) { nextId = id; nextTask = task; }
      }
      if (!nextTask) break;
      assert.ok(++callbacks <= budget, 'timer work must remain bounded');
      this.executed++;
      this.now = nextTask.at;
      this.tasks.delete(nextId);
      if (nextTask.interval) this.tasks.set(nextId, { ...nextTask, at: this.now + nextTask.interval });
      nextTask.callback();
    }
    this.now = target;
  }
}

class Events {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, listener, options) {
    const entries = this.listeners.get(type) || [];
    if (!entries.some(entry => entry.listener === listener)) entries.push({ listener, once: !!(options && options.once) });
    this.listeners.set(type, entries);
  }
  removeEventListener(type, listener) {
    const entries = (this.listeners.get(type) || []).filter(entry => entry.listener !== listener);
    if (entries.length) this.listeners.set(type, entries); else this.listeners.delete(type);
  }
  dispatchEvent(event) {
    if (!event.target) event.target = this;
    for (const entry of [...(this.listeners.get(event.type) || [])]) {
      if (entry.once) this.removeEventListener(event.type, entry.listener);
      entry.listener.call(this, event);
    }
    return true;
  }
  count() { return [...this.listeners.values()].reduce((total, entries) => total + entries.length, 0); }
}

function forbidden(name) { return () => { throw new Error('Forbidden access: ' + name); }; }

class Button {
  constructor(fixture, name, label, action, attributes = {}) {
    this.fixture = fixture; this.name = name; this.label = label; this.action = action;
    this.attributes = attributes; this.disabled = false; this.tagName = 'BUTTON';
    this.parentElement = fixture.menu; this.parentNode = fixture.menu;
  }
  get textContent() { return this.label; }
  getAttribute(name) {
    if (name === 'src' || name === 'href') return forbidden(name)();
    if (name === 'aria-disabled') return this.disabled ? 'true' : (this.attributes[name] || null);
    if (name === 'disabled') return this.disabled ? '' : null;
    if (name === 'aria-expanded' && this.name === 'settings') return this.fixture.menuOpen ? 'true' : 'false';
    if (name === 'aria-checked' && this.name === 'radio') {
      const checked = this.group === 'mirrors'
        ? this.fixture.mirrorChecked === this.index || this.fixture.mirrorExtraChecked.has(this.index)
        : this.fixture.checked === this.index || this.fixture.extraChecked.has(this.index);
      return checked ? 'true' : 'false';
    }
    return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null;
  }
  querySelector(selector) {
    if (selector === 'span') return { textContent: this.name === 'back' ? 'Back' : this.label };
    if (selector === 'span:last-child') return { textContent: this.label };
    throw new Error('Unexpected button selector: ' + selector);
  }
  querySelectorAll(selector) {
    if (selector === 'span') return this.name === 'back'
      ? [{ textContent: 'Back' }, { textContent: 'Quality' }] : [{ textContent: this.label }];
    throw new Error('Unexpected button selector list: ' + selector);
  }
  click() {
    assert.ok(!this.disabled, 'disabled controls must not be clicked');
    this.fixture.clicks.push({ name: this.name, label: this.label, time: this.fixture.clock.now });
    this.action();
  }
}

class Fixture {
  constructor(options = {}) {
    this.clock = new Clock(); this.window = new Events(); this.document = new Events();
    this.clicks = []; this.menuOpen = !!options.preexistingMenu; this.tab = options.preexistingTab || 'main';
    this.settingsPresent = options.settingsPresent !== false; this.renderDelay = options.renderDelay ?? 30;
    this.optionsDelay = options.optionsDelay || 0; this.optionsVisibleAt = 0;
    this.ignoreSelection = !!options.ignoreSelection; this.selectionMedia = !!options.selectionMedia;
    this.checked = options.checked ?? 0; this.generation = options.generation || 1;
    this.menu = {
      tagName: 'DIV',
      getAttribute: name => name === 'role' ? 'menu' : name === 'aria-label' ? 'Playback Settings' : null,
      querySelector: selector => this.find(selector),
      querySelectorAll: selector => this.findAll(selector)
    };
    this.primaryList = { tagName: 'DIV', getAttribute: () => null, parentElement: this.menu };
    this.mirrorList = { tagName: 'DIV', getAttribute: () => null, parentElement: this.primaryList, textContent: 'Mirrors (0)' };
    this.settings = new Button(this, 'settings', 'Player Settings', () => {
      this.clock.setTimeout(() => { this.menuOpen = true; this.tab = 'main'; }, this.renderDelay);
    }, { 'aria-label': 'Player Settings' });
    this.settings.disabled = !!options.settingsDisabled;
    this.quality = new Button(this, 'quality', 'Quality', () => {
      this.clock.setTimeout(() => {
        this.tab = 'quality'; this.optionsVisibleAt = this.clock.now + this.optionsDelay;
      }, this.renderDelay);
    }, { role: 'menuitem' });
    this.back = new Button(this, 'back', 'Quality', () => {
      this.clock.setTimeout(() => { this.tab = 'main'; }, this.renderDelay);
    }, { 'aria-label': 'Back to main settings' });
    this.close = new Button(this, 'close', 'Close settings', () => {
      this.clock.setTimeout(() => { this.menuOpen = false; }, this.renderDelay);
    }, { 'aria-label': 'Close settings' });
    this.setOptions(options.options || ['Auto', '720p', '1080p (8.0 Mbps)', '2160p (12.0 Mbps)']);
    this.setMirrors(options.mirrors || [], options.mirrorChecked ?? 0);
    this.video = this.newVideo();
    this.document.querySelector = selector => this.find(selector);
    this.document.querySelectorAll = selector => this.findAll(selector);
    this.document.documentElement = { tagName: 'HTML' };
    for (const name of ['cookie', 'URL', 'baseURI']) Object.defineProperty(this.document, name, { get: forbidden('document.' + name) });
    const location = {};
    Object.defineProperties(location, {
      origin: { get: () => options.actualOrigin || origin },
      pathname: { get: () => options.actualPath || path }
    });
    for (const name of ['href', 'search', 'hash', 'host', 'hostname', 'protocol', 'port']) {
      Object.defineProperty(location, name, { get: forbidden('location.' + name) });
    }
    this.window.location = location;
    this.window.document = this.document;
    this.window.window = this.window;
    this.window.self = this.window;
    const clock = this.clock;
    class FixtureDate extends Date {
      constructor(...args) { super(...(args.length ? args : [clock.now])); }
      static now() { return clock.now; }
    }
    const globals = {
      window: this.window, document: this.document, location,
      setTimeout: clock.setTimeout.bind(clock), clearTimeout: clock.clearTimeout.bind(clock),
      setInterval: clock.setInterval.bind(clock), clearInterval: clock.clearInterval.bind(clock),
      Date: FixtureDate, performance: { now: () => clock.now },
      fetch: forbidden('fetch'), XMLHttpRequest: forbidden('XMLHttpRequest'),
      WebSocket: forbidden('WebSocket'), URL: forbidden('URL'),
      navigator: { sendBeacon: forbidden('sendBeacon') },
      console: { log() {}, warn() {}, error() {} }
    };
    Object.assign(this.window, globals);
    this.window.open = forbidden('window.open'); this.window.postMessage = forbidden('postMessage');
    this.context = vm.createContext(globals);
    this.installSource = fs.readFileSync(asset, 'utf8');
    this.install = vm.runInContext(this.installSource, this.context, { filename: asset });
    assert.equal(typeof this.install, 'function', 'asset must evaluate to its injection function');
    this.start(options.config || {});
  }
  setOptions(options, checked = this.checked) {
    this.checked = checked;
    this.extraChecked = new Set();
    this.radios = options.map((value, index) => {
      const option = typeof value === 'string' ? { label: value } : value;
      if (option.checked) this.extraChecked.add(index);
      const button = new Button(this, 'radio', option.label, () => {
        this.clock.setTimeout(() => {
          if (!this.ignoreSelection) { this.checked = index; this.extraChecked.clear(); }
          this.tab = 'main';
          if (this.selectionMedia) {
            this.video = this.newVideo();
            this.media('loadstart'); this.media('emptied'); this.media('loadedmetadata');
          }
        }, this.renderDelay);
      }, { role: 'menuitemradio' });
      button.index = index; button.disabled = !!option.disabled; button.group = 'primary';
      button.parentElement = this.primaryList; button.parentNode = this.primaryList;
      return button;
    });
  }
  setMirrors(options, checked = 0) {
    this.mirrorChecked = checked; this.mirrorExtraChecked = new Set();
    this.mirrorList.textContent = 'Mirrors (' + options.length + ')';
    this.mirrorRadios = options.map((value, index) => {
      const option = typeof value === 'string' ? { label: value } : value;
      if (option.checked) this.mirrorExtraChecked.add(index);
      const button = new Button(this, 'radio', option.label, () => {
        this.clock.setTimeout(() => {
          this.mirrorChecked = index; this.mirrorExtraChecked.clear(); this.tab = 'main';
        }, this.renderDelay);
      }, { role: 'menuitemradio' });
      button.index = index; button.disabled = !!option.disabled; button.group = 'mirrors';
      button.parentElement = this.mirrorList; button.parentNode = this.mirrorList;
      return button;
    });
  }
  newVideo() {
    const video = { tagName: 'VIDEO', readyState: 1,
      play: forbidden('video.play'), pause: forbidden('video.pause'),
      getAttribute: name => name === 'src' ? forbidden('video.src')() : null };
    for (const name of ['src', 'currentSrc', 'currentTime', 'paused', 'volume', 'playbackRate']) {
      Object.defineProperty(video, name, { get: forbidden('video.' + name), set: forbidden('video.' + name) });
    }
    return video;
  }
  find(selector) {
    if (selector === selectors.settings) return this.settingsPresent ? this.settings : null;
    if (selector === selectors.menu) return this.menuOpen ? this.menu : null;
    if (selector === selectors.back) return this.menuOpen && this.tab === 'quality' ? this.back : null;
    if (selector === selectors.close) return this.menuOpen ? this.close : null;
    if (selector === 'video') return this.video;
    throw new Error('Unexpected selector: ' + selector);
  }
  findAll(selector) {
    if (selector === selectors.radios) return this.menuOpen && this.tab === 'quality' && this.clock.now >= this.optionsVisibleAt ? [...this.radios, ...this.mirrorRadios] : [];
    if (selector === selectors.items) return this.menuOpen && this.tab === 'main' ? [this.quality] : [];
    if (selector === 'video') return this.video ? [this.video] : [];
    throw new Error('Unexpected selector list: ' + selector);
  }
  start(config = {}) {
    this.install({ origin, path, generation: this.generation, ...config });
    return this.window.__nepalTheaterQuality;
  }
  state() {
    const state = JSON.parse(JSON.stringify(this.window.__nepalTheaterQuality.getState()));
    assert.deepEqual(Object.keys(state).sort(), ['generation', 'epoch', 'phase', 'attempts', 'quality', 'height', 'bitrateMbps', 'verified', 'reason'].sort());
    assert.ok(['waiting', 'selecting', 'selected', 'unavailable', 'manual', 'stopped'].includes(state.phase));
    assert.equal(typeof state.verified, 'boolean');
    assert.ok(Number.isInteger(state.generation));
    assert.ok(Number.isInteger(state.epoch) && state.epoch >= 0);
    assert.ok(Number.isInteger(state.attempts) && state.attempts >= 0);
    assert.ok(state.quality === null || typeof state.quality === 'string');
    assert.ok(state.height === null || (Number.isInteger(state.height) && state.height >= 0));
    assert.ok(state.bitrateMbps === null || (typeof state.bitrateMbps === 'number' && state.bitrateMbps >= 0));
    assert.ok(state.reason === null || typeof state.reason === 'string');
    assert.ok(state.epoch <= 12, 'provider epochs must be bounded');
    assert.ok(this.clicks.length <= 32, 'automatic UI steps must be bounded');
    return state;
  }
  settle(milliseconds = 110000) { this.clock.tick(milliseconds); return this.state(); }
  trusted(type = 'pointerdown') { this.window.dispatchEvent({ type, isTrusted: true }); }
  media(type, video = this.video) { this.document.dispatchEvent({ type, target: video, isTrusted: true }); }
  radioClicks() { return this.clicks.filter(click => click.name === 'radio'); }
}

test('highest available height wins before bitrate and is verified after asynchronous menu return', () => {
  const fixture = new Fixture({ options: ['Auto', '1080p (50 Mbps)', '2160p (4 Mbps)'] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.height, 2160); assert.equal(state.bitrateMbps, 4);
  assert.equal(fixture.checked, 2);
  assert.deepEqual(fixture.radioClicks().map(click => click.label), ['2160p (4 Mbps)']);
  assert.ok(fixture.clicks.filter(click => click.name === 'quality').length >= 2, 'selection must be re-read in the quality tab');
  assert.equal(fixture.menuOpen, false, 'the helper closes only the menu it opened');
});

test('bitrate breaks equal-height ties and an already checked equal winner is retained', () => {
  const fixture = new Fixture({ checked: 3, options: ['Auto', '1080p (8 Mbps)', '1080p (12.3 Mbps)', '1080p (12.3 Mbps)'] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.height, 1080); assert.equal(state.bitrateMbps, 12.3);
  assert.equal(fixture.checked, 3); assert.equal(fixture.radioClicks().length, 0);
});

test('a checked lower bitrate does not outrank a better option at the same height', () => {
  const fixture = new Fixture({ checked: 1, options: ['Auto', '1080p (8 Mbps)', '1080p (12.3 Mbps)'] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.bitrateMbps, 12.3);
  assert.equal(fixture.checked, 2); assert.equal(fixture.radioClicks().length, 1);
});

test('4K is recognized and checked highest quality needs no radio click', () => {
  const fixture = new Fixture({ checked: 2, options: ['Auto', '1080p (20 Mbps)', '4K'] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.height, 2160);
  assert.equal(state.verified, true); assert.equal(fixture.radioClicks().length, 0);
});

test('a checked same-label mirror does not falsely verify the unchecked primary adaptive level', () => {
  const fixture = new Fixture({ checked: 0, options: ['Auto', '1080p (8 Mbps)'],
    mirrors: ['1080p (8 Mbps)', '720p'], mirrorChecked: 0 });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.height, 1080); assert.equal(fixture.checked, 1);
  assert.equal(fixture.radioClicks().length, 1);
  assert.equal(fixture.mirrorChecked, 0, 'the independent mirror selection stays unchanged');
});

test('a checked 2160p mirror cannot outrank a primary 1080p adaptive option', () => {
  const fixture = new Fixture({ checked: 0, options: ['Auto', '1080p (8 Mbps)'],
    mirrors: ['2160p (40 Mbps)', '1080p (12 Mbps)'], mirrorChecked: 0 });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.height, 1080); assert.equal(state.bitrateMbps, 8);
  assert.equal(fixture.checked, 1); assert.equal(fixture.mirrorChecked, 0);
  assert.deepEqual(fixture.radioClicks().map(click => click.label), ['1080p (8 Mbps)']);
});

test('a single-level fallback can legitimately have both Auto and its numeric primary checked', () => {
  const fixture = new Fixture({ checked: 0, options: ['Auto', { label: '1080p (6 Mbps)', checked: true }] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.height, 1080); assert.equal(state.bitrateMbps, 6);
  assert.equal(fixture.radioClicks().length, 0);
});

test('unknown and Auto-only options are not guessed', () => {
  const fixture = new Fixture({ options: ['Auto', 'Original', 'Best available'] });
  const state = fixture.settle();
  assert.equal(state.phase, 'unavailable'); assert.equal(state.verified, false);
  assert.equal(fixture.radioClicks().length, 0);
});

test('disabled higher quality is ignored in favor of an enabled numeric option', () => {
  const fixture = new Fixture({ options: ['Auto', '1080p (8 Mbps)', { label: '2160p (20 Mbps)', disabled: true }] });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.height, 1080);
  assert.equal(fixture.checked, 1); assert.equal(state.verified, true);
});

test('a disabled settings button is never clicked and cannot wait forever', () => {
  const fixture = new Fixture({ settingsDisabled: true });
  const state = fixture.settle();
  assert.equal(state.phase, 'unavailable'); assert.equal(fixture.clicks.length, 0);
});

test('a menu already open belongs to the user and is left untouched', () => {
  const fixture = new Fixture({ preexistingMenu: true });
  const state = fixture.settle();
  assert.equal(state.phase, 'manual'); assert.equal(fixture.clicks.length, 0);
  assert.equal(fixture.menuOpen, true);
});

test('all supported trusted gestures cancel automation without later UI changes', () => {
  for (const type of ['pointerdown', 'mousedown', 'touchstart', 'keydown']) {
    const fixture = new Fixture({ optionsDelay: 3000 });
    fixture.clock.tick(600);
    assert.equal(fixture.state().phase, 'selecting');
    const clicks = fixture.clicks.length;
    fixture.trusted(type);
    const state = fixture.settle();
    assert.equal(state.phase, 'manual', type); assert.equal(state.verified, false);
    assert.equal(fixture.clicks.length, clicks, type);
  }
});

test('ordinary trusted input while waiting does not disable a later ready player', () => {
  const fixture = new Fixture({ settingsPresent: false });
  const play = { tagName: 'BUTTON', getAttribute: () => null, parentElement: null };
  fixture.window.dispatchEvent({ type: 'pointerdown', isTrusted: true, target: play });
  assert.equal(fixture.state().phase, 'waiting');
  fixture.settingsPresent = true;
  assert.equal(fixture.settle().phase, 'selected');
});

test('trusted interaction during selection stops later automatic clicks', () => {
  const fixture = new Fixture({ optionsDelay: 3000 });
  fixture.clock.tick(1200);
  const before = fixture.clicks.length;
  fixture.trusted('keydown');
  assert.equal(fixture.settle().phase, 'manual');
  assert.equal(fixture.clicks.length, before);
});

test('synthetic untrusted events do not cancel automatic selection', () => {
  const fixture = new Fixture();
  fixture.window.dispatchEvent({ type: 'pointerdown', isTrusted: false });
  fixture.window.dispatchEvent({ type: 'keydown', isTrusted: false });
  assert.equal(fixture.settle().phase, 'selected');
});

test('delayed React quality options are awaited and then verified', () => {
  const fixture = new Fixture({ optionsDelay: 2000, renderDelay: 80 });
  fixture.clock.tick(1000);
  assert.notEqual(fixture.state().phase, 'unavailable');
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(fixture.checked, 3);
});

test('quality clicks that never become checked cannot be reported as verified', () => {
  const fixture = new Fixture({ ignoreSelection: true });
  const state = fixture.settle();
  assert.equal(state.phase, 'unavailable'); assert.equal(state.verified, false);
  assert.ok(state.attempts <= 32);
  assert.ok(fixture.radioClicks().length >= 1 && fixture.radioClicks().length <= 3);
});

test('failed confirmation releases its own-switch hold so a new provider source can retry', () => {
  const fixture = new Fixture({ ignoreSelection: true });
  const failed = fixture.settle();
  assert.equal(failed.phase, 'unavailable'); assert.equal(failed.verified, false);
  fixture.ignoreSelection = false;
  fixture.setOptions(['Auto', '1080p (6 Mbps)'], 0);
  fixture.media('loadstart'); fixture.media('loadedmetadata');
  const retried = fixture.settle();
  assert.ok(retried.epoch > failed.epoch);
  assert.equal(retried.phase, 'selected'); assert.equal(retried.verified, true);
  assert.equal(retried.height, 1080); assert.equal(fixture.checked, 1);
});

test('media replacement caused by a quality click does not start a recursive epoch', () => {
  const fixture = new Fixture({ selectionMedia: true });
  const state = fixture.settle();
  assert.equal(state.phase, 'selected'); assert.equal(state.verified, true);
  assert.equal(state.epoch, 1); assert.equal(fixture.radioClicks().length, 1);
});

test('a provider loadstart on the same video starts a fresh bounded quality epoch', () => {
  const fixture = new Fixture();
  const first = fixture.settle();
  fixture.setOptions(['Auto', '720p', '1080p (6 Mbps)'], 0);
  fixture.media('loadstart');
  fixture.media('loadedmetadata');
  const next = fixture.settle();
  assert.ok(next.epoch > first.epoch); assert.equal(next.phase, 'selected');
  assert.equal(next.height, 1080); assert.equal(fixture.checked, 2);
});

test('a new video element starts a fresh epoch and old media events do not restart it', () => {
  const fixture = new Fixture();
  const first = fixture.settle();
  const old = fixture.video;
  fixture.video = fixture.newVideo();
  fixture.setOptions(['Auto', '720p'], 0);
  fixture.media('loadstart'); fixture.media('loadedmetadata');
  const next = fixture.settle();
  assert.ok(next.epoch > first.epoch); assert.equal(next.height, 720);
  const clicks = fixture.clicks.length;
  fixture.media('loadedmetadata', old);
  assert.equal(fixture.settle().epoch, next.epoch);
  assert.equal(fixture.clicks.length, clicks);
});

test('repeated metadata for the current video does not reopen verified controls', () => {
  const fixture = new Fixture();
  const selected = fixture.settle();
  const clicks = fixture.clicks.length;
  for (let index = 0; index < 20; index++) fixture.media('loadedmetadata');
  const state = fixture.settle();
  assert.equal(state.epoch, selected.epoch); assert.equal(fixture.clicks.length, clicks);
  assert.equal(state.phase, 'selected');
});

test('a trusted settings-button child after selection preserves the user override', () => {
  const fixture = new Fixture();
  fixture.settle();
  const child = { tagName: 'SPAN', getAttribute: () => null, parentElement: fixture.settings };
  fixture.window.dispatchEvent({ type: 'pointerdown', isTrusted: true, target: child });
  assert.equal(fixture.state().phase, 'manual');
  const clicks = fixture.clicks.length;
  fixture.setOptions(['Auto', '720p'], 0);
  fixture.media('loadstart'); fixture.media('loadedmetadata');
  assert.equal(fixture.settle().phase, 'manual');
  assert.equal(fixture.clicks.length, clicks);
});

test('unrelated trusted playback input after selection still permits source quality reapplication', () => {
  const fixture = new Fixture();
  const initial = fixture.settle();
  const play = { tagName: 'BUTTON', getAttribute: name => name === 'aria-label' ? 'Play' : null, parentElement: null };
  fixture.window.dispatchEvent({ type: 'pointerdown', isTrusted: true, target: play });
  fixture.window.dispatchEvent({ type: 'keydown', isTrusted: true, target: play });
  assert.equal(fixture.state().phase, 'selected');
  fixture.setOptions(['Auto', '1080p (6 Mbps)'], 0);
  fixture.media('loadstart'); fixture.media('loadedmetadata');
  const reapplied = fixture.settle();
  assert.ok(reapplied.epoch > initial.epoch);
  assert.equal(reapplied.phase, 'selected'); assert.equal(reapplied.verified, true);
  assert.equal(reapplied.height, 1080); assert.equal(fixture.checked, 1);
});

test('missing player UI expires within a bounded polling budget', () => {
  const fixture = new Fixture({ settingsPresent: false });
  const state = fixture.settle(110000);
  assert.equal(state.phase, 'unavailable'); assert.equal(state.verified, false);
  assert.equal(fixture.clicks.length, 0);
  assert.ok(fixture.clock.executed <= 185, 'waiting polls must not exceed the 180-poll budget');
  assert.equal(fixture.clock.tasks.size, 0);
});

test('quality waits for automatic subtitle selection before opening its menu', () => {
  const fixture = new Fixture();
  let subtitlePhase = 'selecting';
  fixture.window.__nepalTheaterPlayback = { getState: () => ({ subtitlePhase }) };
  fixture.clock.tick(1500);
  assert.equal(fixture.clicks.length, 0);
  subtitlePhase = 'selected';
  assert.equal(fixture.settle().phase, 'selected');
});

test('a stuck subtitle controller does not cause unbounded quality polling', () => {
  const fixture = new Fixture();
  fixture.window.__nepalTheaterPlayback = { getState: () => ({ subtitlePhase: 'selecting' }) };
  assert.equal(fixture.settle(110000).reason, 'subtitle_menu_busy');
  assert.equal(fixture.clicks.length, 0);
  assert.equal(fixture.clock.tasks.size, 0);
});

test('stop removes scheduled work and listeners and ignores subsequent media events', () => {
  const fixture = new Fixture({ settingsPresent: false });
  fixture.clock.tick(600);
  fixture.window.__nepalTheaterQuality.stop();
  assert.equal(fixture.state().phase, 'stopped');
  assert.equal(fixture.clock.tasks.size, 0);
  assert.equal(fixture.window.count() + fixture.document.count(), 0);
  fixture.settingsPresent = true; fixture.media('loadstart'); fixture.trusted();
  assert.equal(fixture.settle().phase, 'stopped'); assert.equal(fixture.clicks.length, 0);
});

test('stop retains the last confirmed quality metadata while removing event listeners', () => {
  const fixture = new Fixture();
  const confirmed = fixture.settle();
  fixture.window.__nepalTheaterQuality.stop();
  const stopped = fixture.state();
  assert.equal(stopped.phase, 'stopped'); assert.equal(stopped.verified, true);
  for (const key of ['quality', 'height', 'bitrateMbps']) assert.equal(stopped[key], confirmed[key]);
  assert.equal(fixture.clock.tasks.size, 0);
  assert.equal(fixture.window.count() + fixture.document.count(), 0);
});

test('lower generation is ignored, equal generation deduplicates, and higher generation replaces', () => {
  const fixture = new Fixture({ generation: 5, settingsPresent: false });
  const original = fixture.window.__nepalTheaterQuality;
  const listeners = fixture.window.count() + fixture.document.count();
  const timers = fixture.clock.tasks.size;
  fixture.start({ generation: 4 });
  assert.equal(fixture.window.__nepalTheaterQuality, original);
  fixture.start({ generation: 5 });
  assert.equal(fixture.window.__nepalTheaterQuality, original);
  assert.equal(fixture.window.count() + fixture.document.count(), listeners);
  assert.equal(fixture.clock.tasks.size, timers);
  fixture.start({ generation: 6 });
  assert.notEqual(fixture.window.__nepalTheaterQuality, original);
  assert.equal(original.getState().phase, 'stopped');
  assert.equal(fixture.state().generation, 6);
  assert.equal(fixture.window.count() + fixture.document.count(), listeners);
  fixture.settingsPresent = true;
  assert.equal(fixture.settle().phase, 'selected');
});

test('only exact origin and embed path with an optional trailing slash can activate', () => {
  for (const options of [
    { actualOrigin: 'https://framextv.cc.attacker.invalid' },
    { actualOrigin: 'http://framextv.cc' },
    { actualPath: '/embed/101/elsewhere' },
    { actualPath: '/embed/1010' }
  ]) {
    const fixture = new Fixture(options);
    fixture.clock.tick(60000);
    assert.equal(fixture.clicks.length, 0);
    assert.equal(fixture.clock.tasks.size, 0);
    assert.equal(fixture.window.count() + fixture.document.count(), 0);
  }
  for (const options of [{ actualPath: path + '/' }]) {
    const fixture = new Fixture(options);
    assert.equal(fixture.settle().phase, 'selected');
  }
});

test('leaving the configured embed origin stops automation before another UI step', () => {
  const options = { settingsPresent: false, actualOrigin: origin };
  const fixture = new Fixture(options);
  fixture.clock.tick(600);
  options.actualOrigin = 'https://elsewhere.invalid';
  fixture.settingsPresent = true;
  assert.equal(fixture.settle().phase, 'stopped'); assert.equal(fixture.clicks.length, 0);
  assert.equal(fixture.clock.tasks.size, 0);
  assert.equal(fixture.window.count() + fixture.document.count(), 0);
});

test('provider churn cannot create unlimited epochs or automatic UI steps', () => {
  const fixture = new Fixture({ settingsPresent: false });
  for (let index = 0; index < 40; index++) {
    fixture.video = fixture.newVideo();
    fixture.media('loadstart'); fixture.media('emptied'); fixture.media('loadedmetadata');
    fixture.clock.tick(600);
  }
  const state = fixture.settle();
  assert.equal(state.phase, 'unavailable');
  assert.ok(state.epoch <= 12); assert.ok(fixture.clicks.length <= 32);
});
