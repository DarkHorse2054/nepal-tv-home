'use strict';
// Same packaged asset, synthetic provider UI and media metadata. No TV/network.
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const asset = __dirname + '/../app/src/main/assets/theater-playback.js';
const origin = 'https://framextv.cc', path = '/embed/101';
const S = { toggle: 'button[aria-label="Subtitles / Captions"]',
  menu: '[role="menu"][aria-label="Subtitle Options"]',
  settings: '[role="menu"][aria-label="Playback Settings"]',
  close: 'button[aria-label="Close subtitles menu"]', radios: 'button[role="menuitemradio"]' };
const forbidden = name => () => { throw new Error('Forbidden: ' + name); };

class Clock {
  constructor() { this.now = 0; this.next = 1; this.tasks = new Map(); this.executed = 0; }
  setTimeout(callback, delay = 0) { const id = this.next++; this.tasks.set(id, { at: this.now + delay, callback }); return id; }
  clearTimeout(id) { this.tasks.delete(id); }
  tick(ms) {
    const end = this.now + ms; let count = 0;
    for (;;) {
      const next = [...this.tasks].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) break;
      assert.ok(++count < 1500, 'all timer work is bounded');
      this.tasks.delete(next[0]); this.now = next[1].at; this.executed++; next[1].callback();
    }
    this.now = end;
  }
}
class Events {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, callback) { const list = this.listeners.get(type) || []; list.push(callback); this.listeners.set(type, list); }
  removeEventListener(type, callback) { const list = (this.listeners.get(type) || []).filter(item => item !== callback); if (list.length) this.listeners.set(type, list); else this.listeners.delete(type); }
  dispatchEvent(event) { for (const callback of [...(this.listeners.get(event.type) || [])]) callback(event); }
  count() { return [...this.listeners.values()].reduce((n, list) => n + list.length, 0); }
}
class Button {
  constructor(fixture, kind, label, attrs, action) { Object.assign(this, { fixture, kind, label, attrs, action, disabled: false, tagName: 'BUTTON' }); }
  getAttribute(name) {
    if (['src', 'href'].includes(name)) forbidden(name)();
    if (name === 'aria-expanded' && this.kind === 'toggle') return String(this.fixture.menuOpen);
    if (name === 'aria-checked' && this.kind === 'radio') return String(this.fixture.checked === this.index);
    return this.attrs[name] || null;
  }
  querySelector(selector) { assert.equal(selector, 'span'); return { textContent: this.label }; }
  click() { assert.equal(this.disabled, false); this.fixture.clicks.push(this.label); this.action(); }
}
class Fixture {
  constructor(options = {}) {
    this.options = options; this.clock = new Clock(); this.window = new Events(); this.document = new Events();
    this.clicks = []; this.messages = []; this.menuOpen = !!options.preexistingMenu; this.checked = options.checked ?? 0;
    this.settingsOpen = !!options.settingsOpen; this.unknownMenu = !!options.unknownMenu;
    this.unknownDialog = !!options.unknownDialog; this.qualityPhase = options.qualityPhase || 'selected';
    this.togglePresent = options.togglePresent !== false; this.renderDelay = options.renderDelay ?? 30;
    this.optionsVisibleAt = options.optionsDelay || 0;
    this.menu = { tagName: 'DIV', getAttribute: name => name === 'role' ? 'menu' : name === 'aria-label' ? 'Subtitle Options' : null,
      querySelector: selector => { assert.equal(selector, S.close); return this.close; },
      querySelectorAll: selector => { assert.equal(selector, S.radios); return this.clock.now >= this.optionsVisibleAt ? this.radios : []; } };
    this.toggle = new Button(this, 'toggle', 'Subtitles / Captions', { 'aria-label': 'Subtitles / Captions' }, () => {
      this.clock.setTimeout(() => { this.menuOpen = !this.menuOpen; }, this.renderDelay);
    });
    this.toggle.disabled = !!options.toggleDisabled;
    this.close = new Button(this, 'close', 'Close subtitles menu', { 'aria-label': 'Close subtitles menu' }, () => {
      this.clock.setTimeout(() => { this.menuOpen = false; }, this.renderDelay);
    });
    this.setTracks(options.tracks || ['Off', 'Nepali', 'English'], this.checked);
    this.video = options.noVideo ? null : this.newVideo(options);
    this.document.querySelector = selector => {
      if (selector === 'video') return this.video;
      if (selector === S.toggle) return this.togglePresent ? this.toggle : null;
      if (selector === S.menu) return this.menuOpen ? this.menu : null;
      if (selector === S.settings) return this.settingsOpen ? {} : null;
      if (selector === '[role="menu"]') return this.unknownMenu || this.menuOpen || this.settingsOpen ? {} : null;
      if (selector === '[role="dialog"]') return this.unknownDialog ? {} : null;
      throw new Error('Unexpected selector: ' + selector);
    };
    for (const name of ['cookie', 'URL', 'baseURI']) Object.defineProperty(this.document, name, { get: forbidden('document.' + name) });
    const location = {};
    Object.defineProperties(location, { origin: { get: () => this.options.actualOrigin || origin }, pathname: { get: () => this.options.actualPath || path } });
    for (const name of ['href', 'search', 'hash']) Object.defineProperty(location, name, { get: forbidden('location.' + name) });
    Object.assign(this.window, { window: this.window, document: this.document, location,
      setTimeout: this.clock.setTimeout.bind(this.clock), clearTimeout: this.clock.clearTimeout.bind(this.clock),
      __nepalTheaterQuality: { getState: () => ({ phase: this.qualityPhase }) },
      postMessage: (message, sentOrigin) => {
        assert.equal(sentOrigin, origin); assert.deepEqual(Object.keys(message).sort(), ['action', 'time']);
        assert.equal(message.action, 'seek'); assert.equal(typeof message.time, 'number');
        this.messages.push(JSON.parse(JSON.stringify(message)));
        const video = this.video;
        this.clock.setTimeout(() => {
          if (!this.options.ignoreSeek) video.currentTime = message.time + (this.options.seekOffset || 0);
          this.media('seeked', video);
        }, this.options.seekDelay ?? 30);
      } });
    for (const name of ['localStorage', 'sessionStorage']) Object.defineProperty(this.window, name, { get: forbidden(name) });
    const context = vm.createContext({ window: this.window, document: this.document,
      fetch: forbidden('fetch'), XMLHttpRequest: forbidden('XHR'), WebSocket: forbidden('WebSocket'),
      URL: forbidden('URL'), MutationObserver: forbidden('MutationObserver'),
      navigator: { sendBeacon: forbidden('beacon') } });
    this.install = vm.runInContext(fs.readFileSync(asset, 'utf8'), context, { filename: asset });
    assert.equal(typeof this.install, 'function'); this.start(options.config || {});
  }
  newVideo(options = {}) {
    const video = { tagName: 'VIDEO', readyState: options.readyState ?? 1, ended: !!options.ended,
      duration: options.duration ?? 600, currentTime: options.currentTime ?? 0,
      seekable: { length: options.seekable === false ? 0 : 1, start: () => 0, end: () => options.duration ?? 600 },
      play: forbidden('play'), pause: forbidden('pause') };
    for (const name of ['src', 'currentSrc', 'paused', 'textTracks', 'volume', 'playbackRate']) {
      Object.defineProperty(video, name, { get: forbidden('video.' + name), set: forbidden('video.' + name) });
    }
    return video;
  }
  setTracks(labels, checked = 0) {
    this.checked = checked;
    this.radios = labels.map((value, index) => {
      const item = typeof value === 'string' ? { label: value } : value;
      const button = new Button(this, 'radio', item.label, { role: 'menuitemradio' }, () => {
        this.clock.setTimeout(() => { if (!this.options.ignoreSubtitle) this.checked = index; this.menuOpen = false; }, this.renderDelay);
      });
      button.index = index; button.disabled = !!item.disabled; button.parentElement = this.menu; return button;
    });
  }
  start(changes = {}) { this.install({ origin, path, generation: 1, resumeSeconds: 0, subtitlesEnabled: true, preferredLanguage: 'en', ...changes }); return this.controller; }
  get controller() { return this.window.__nepalTheaterPlayback; }
  state() { const result = JSON.parse(JSON.stringify(this.controller.getState())); assert.ok(JSON.stringify(result).length < 1024); return result; }
  settle(ms = 100000) { this.clock.tick(ms); return this.state(); }
  media(type, video = this.video) { this.document.dispatchEvent({ type, target: video, isTrusted: true }); }
  trusted(type, target, key = '') { this.window.dispatchEvent({ type, target, key, isTrusted: true }); }
}

test('resume waits for metadata and a seekable range, sends one normal seek and verifies later', () => {
  const f = new Fixture({ readyState: 0, seekable: false, config: { resumeSeconds: 120 } });
  f.clock.tick(1000); assert.equal(f.messages.length, 0); assert.equal(f.state().resumePhase, 'waiting');
  f.video.readyState = 1; f.media('loadedmetadata'); f.clock.tick(1000); assert.equal(f.messages.length, 0);
  f.video.seekable.length = 1; f.media('durationchange');
  assert.equal(f.settle().resumePhase, 'done'); assert.deepEqual(f.messages, [{ action: 'seek', time: 120 }]);
  assert.equal(f.video.currentTime, 120);
});
test('zero position skips seeking, while already-restored positions need no duplicate seek', () => {
  const zero = new Fixture(); assert.equal(zero.settle().resumePhase, 'skipped'); assert.equal(zero.messages.length, 0);
  const existing = new Fixture({ currentTime: 119, config: { resumeSeconds: 120 } });
  assert.equal(existing.settle().resumePhase, 'done'); assert.equal(existing.messages.length, 0);
});
test('a user Pause leaves pending resume intact and no asset playback calls occur', () => {
  const f = new Fixture({ readyState: 0, config: { resumeSeconds: 70 } });
  f.trusted('keydown', { getAttribute: () => 'Pause' }, 'k');
  assert.equal(f.state().resumePhase, 'waiting'); f.video.readyState = 1; f.media('loadedmetadata');
  assert.equal(f.settle().resumePhase, 'done'); assert.equal(f.messages.length, 1);
});
test('manual seek via timeline, accessibility input, keyboard or native cancel cancels only pending resume', () => {
  for (const mode of ['timeline', 'input', 'change', 'keyboard', 'native']) {
    const f = new Fixture({ readyState: 0, config: { resumeSeconds: 90 } });
    if (mode === 'native') f.controller.cancelResume();
    else f.trusted(mode === 'timeline' ? 'pointerdown' : mode === 'keyboard' ? 'keydown' : mode,
      { getAttribute: () => mode !== 'keyboard' ? 'Video timeline scrubber' : null }, 'ArrowRight');
    f.video.readyState = 1; f.media('loadedmetadata');
    assert.equal(f.settle().resumePhase, 'cancelled'); assert.equal(f.messages.length, 0);
  }
});
test('unacknowledged seek times out without retrying or claiming a restored position', () => {
  const f = new Fixture({ ignoreSeek: true, config: { resumeSeconds: 80 } });
  assert.equal(f.settle().resumePhase, 'unavailable'); assert.equal(f.messages.length, 1);
  assert.ok(f.clock.executed < 250); assert.equal(f.clock.tasks.size, 0);
});
test('ended and out-of-duration saved positions are not replayed', () => {
  for (const options of [{ ended: true }, { duration: 80 }]) {
    const f = new Fixture({ ...options, config: { resumeSeconds: 90 } });
    assert.equal(f.settle().resumePhase, 'unavailable'); assert.equal(f.messages.length, 0);
  }
});
test('source replacement before dispatch can resume, but a dispatched seek is not replayed', () => {
  const waiting = new Fixture({ readyState: 0, config: { resumeSeconds: 65 } });
  waiting.clock.tick(1000); waiting.video = waiting.newVideo(); waiting.media('loadedmetadata');
  assert.equal(waiting.settle().resumePhase, 'done'); assert.equal(waiting.messages.length, 1);
  const sent = new Fixture({ seekDelay: 3000, config: { resumeSeconds: 65 } });
  sent.clock.tick(100); sent.video = sent.newVideo(); sent.media('loadstart');
  assert.equal(sent.settle().resumePhase, 'unavailable'); assert.equal(sent.messages.length, 1);
});
test('a completed resume is not repeated on later provider reload or stale media events', () => {
  const f = new Fixture({ config: { resumeSeconds: 100 } });
  f.settle(); const old = f.video; f.video = f.newVideo(); f.media('loadstart'); f.media('loadedmetadata');
  f.media('loadedmetadata', old); assert.equal(f.settle().resumePhase, 'done'); assert.equal(f.messages.length, 1);
});
test('English is preferred over other offered tracks and selected state is verified after reopening', () => {
  const f = new Fixture({ tracks: ['Off', 'Nepali', 'English (In-Stream)'] });
  const state = f.settle(); assert.equal(state.subtitlePhase, 'selected'); assert.equal(state.subtitleLabel, 'English (In-Stream)');
  assert.equal(state.subtitleCount, 2); assert.equal(f.checked, 2); assert.equal(f.menuOpen, false);
  assert.equal(f.clicks.filter(label => label === 'Subtitles / Captions').length, 2);
});
test('without English the first actual track is chosen without inventing a language', () => {
  const f = new Fixture({ tracks: ['Off', 'Nepali', 'Spanish'], checked: 2 });
  assert.equal(f.settle().subtitleLabel, 'Nepali'); assert.equal(f.checked, 1);
});
test('an already checked English track needs no repeated selection', () => {
  const f = new Fixture({ tracks: ['Off', 'English'], checked: 1 });
  assert.equal(f.settle().subtitlePhase, 'selected'); assert.equal(f.clicks.filter(label => label === 'English').length, 0);
});
test('native Off clears the current track through the real Off radio and can be enabled again', () => {
  const f = new Fixture(); f.settle(); f.controller.setSubtitles(false);
  assert.equal(f.settle().subtitlePhase, 'off'); assert.equal(f.checked, 0); assert.equal(f.state().subtitlesEnabled, false);
  f.controller.setSubtitles(true); assert.equal(f.settle().subtitlePhase, 'selected'); assert.equal(f.checked, 2);
});
test('changing the native preference while an owned subtitle menu is rendering does not become a user override', () => {
  const f = new Fixture({ optionsDelay: 1000 }); f.clock.tick(300);
  assert.equal(f.state().subtitlePhase, 'selecting'); f.controller.setSubtitles(false);
  assert.equal(f.settle().subtitlePhase, 'off'); assert.equal(f.checked, 0);
});
test('missing, Off-only and URL-like subtitle labels never become fake available tracks', () => {
  for (const options of [{ togglePresent: false }, { tracks: ['Off'] }, { tracks: ['Off', 'https://invalid.example/subtitle'] }]) {
    const f = new Fixture(options); const state = f.settle();
    assert.equal(state.subtitlePhase, 'unavailable'); assert.equal(state.subtitleCount, 0); assert.equal(state.subtitleLabel, '');
    assert.equal(f.messages.length, 0); assert.equal(f.menuOpen, false);
  }
});
test('disabled tracks are ignored and delayed provider options are awaited', () => {
  const f = new Fixture({ tracks: ['Off', { label: 'English', disabled: true }, 'Nepali'], optionsDelay: 1500 });
  assert.equal(f.settle().subtitleLabel, 'Nepali'); assert.equal(f.checked, 2);
});
test('an unacknowledged subtitle selection is not reported selected or repeatedly clicked', () => {
  const f = new Fixture({ ignoreSubtitle: true });
  assert.equal(f.settle().subtitlePhase, 'unavailable'); assert.equal(f.clicks.filter(label => label === 'English').length, 1);
});
test('quality automation and user-opened settings are never commandeered', () => {
  const f = new Fixture({ qualityPhase: 'selecting' }); f.clock.tick(2000); assert.deepEqual(f.clicks, []);
  f.qualityPhase = 'selected'; f.settingsOpen = true; f.clock.tick(2000); assert.deepEqual(f.clicks, []);
  f.settingsOpen = false; assert.equal(f.settle().subtitlePhase, 'selected');
  const user = new Fixture({ preexistingMenu: true });
  assert.equal(user.settle().subtitlePhase, 'manual'); assert.deepEqual(user.clicks, []); assert.equal(user.menuOpen, true);
});
test('unknown provider menus or dialogs are left alone until the user closes them', () => {
  for (const type of ['unknownMenu', 'unknownDialog']) {
    const f = new Fixture({ [type]: true }); f.clock.tick(2000); assert.deepEqual(f.clicks, []);
    f[type] = false; assert.equal(f.settle().subtitlePhase, 'selected');
  }
});
test('trusted user interaction during subtitle automation yields without closing the user menu', () => {
  const f = new Fixture({ optionsDelay: 2000 }); f.clock.tick(1000);
  const before = f.clicks.length; f.trusted('keydown', {}, 'k');
  assert.equal(f.settle().subtitlePhase, 'manual'); assert.equal(f.clicks.length, before); assert.equal(f.menuOpen, true);
});
test('a manual subtitle override survives new sources until an explicit native preference change', () => {
  const f = new Fixture(); f.settle(); f.trusted('pointerdown', { parentElement: f.toggle, getAttribute: () => null });
  const before = f.clicks.length; f.media('loadstart'); f.media('loadedmetadata');
  assert.equal(f.settle().subtitlePhase, 'manual'); assert.equal(f.clicks.length, before);
  f.controller.setSubtitles(false); assert.equal(f.settle().subtitlePhase, 'off');
});
test('subtitle choices reapply on a genuine source change without repeating on ordinary seek events', () => {
  const f = new Fixture(); f.settle(); const before = f.clicks.length;
  f.media('seeked'); f.media('durationchange'); f.clock.tick(2000); assert.equal(f.clicks.length, before);
  f.setTracks(['Off', 'Spanish', 'English SDH']); f.media('loadstart'); f.media('loadedmetadata');
  assert.equal(f.settle().subtitleLabel, 'English SDH'); assert.equal(f.checked, 2);
});
test('stop removes all listeners/timers, retains terminal metadata and cannot be rearmed by media', () => {
  const f = new Fixture({ readyState: 0, config: { resumeSeconds: 40 } }); f.clock.tick(1000); f.controller.stop();
  assert.equal(f.state().resumePhase, 'stopped'); assert.equal(f.state().subtitlePhase, 'stopped');
  assert.equal(f.clock.tasks.size, 0); assert.equal(f.window.count() + f.document.count(), 0);
  f.video.readyState = 1; f.media('loadedmetadata'); f.controller.setSubtitles(true); f.settle();
  assert.deepEqual(f.clicks, []); assert.deepEqual(f.messages, []);
});
test('stop retains a verified resume result and the last actual subtitle metadata', () => {
  const f = new Fixture({ config: { resumeSeconds: 40 } }); const before = f.settle(); f.controller.stop();
  const after = f.state(); assert.equal(after.resumePhase, 'done'); assert.equal(after.resumeTarget, 40);
  assert.equal(after.subtitleLabel, before.subtitleLabel); assert.equal(after.subtitleCount, before.subtitleCount);
  assert.equal(f.clock.tasks.size, 0); assert.equal(f.window.count() + f.document.count(), 0);
});
test('duplicate and stale generations do not reset work, newer generation stops old ownership', () => {
  const f = new Fixture({ readyState: 0, config: { generation: 5, resumeSeconds: 40 } }); const old = f.controller;
  f.start({ generation: 4 }); assert.equal(f.controller, old); f.start({ generation: 5 }); assert.equal(f.controller, old);
  f.start({ generation: 6 }); assert.notEqual(f.controller, old); assert.equal(old.getState().resumePhase, 'stopped');
  assert.equal(f.settle().generation, 6); assert.equal(f.window.count() + f.document.count(), 11);
});
test('exact origin/path enforcement permits only the optional trailing slash and kills stale callbacks', () => {
  for (const options of [{ actualOrigin: 'https://framextv.cc.invalid' }, { actualPath: path + '/wrong' }]) {
    const f = new Fixture(options); f.clock.tick(100000); assert.deepEqual(f.clicks, []); assert.deepEqual(f.messages, []);
    assert.equal(f.clock.tasks.size, 0); assert.equal(f.window.count() + f.document.count(), 0);
  }
  const valid = new Fixture({ actualPath: path + '/' }); assert.equal(valid.settle().subtitlePhase, 'selected');
  const departed = new Fixture({ readyState: 0, config: { resumeSeconds: 40 } });
  departed.clock.tick(1000); departed.options.actualOrigin = 'https://elsewhere.invalid';
  assert.equal(departed.settle().resumePhase, 'stopped'); assert.deepEqual(departed.messages, []);
});
