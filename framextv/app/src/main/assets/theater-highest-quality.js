(function (config) {
    'use strict';

    var WAIT_LIMIT = 180, STEP_LIMIT = 32, EPOCH_LIMIT = 12, SELECTION_LIMIT = 3;
    var MENU = '[role="menu"][aria-label="Playback Settings"]';
    var SETTINGS = 'button[aria-label="Player Settings"]';
    var CLOSE = 'button[aria-label="Close settings"]';
    var BACK = 'button[aria-label="Back to main settings"]';
    var origin = config && config.origin, path = config && config.path;
    var generation = config && config.generation;

    function diagnostic(reason) {
        return { generation: typeof generation === 'number' ? generation : 0,
            epoch: 0, phase: 'unavailable', attempts: 0, quality: '', height: 0,
            bitrateMbps: 0, verified: false, reason: reason };
    }
    function sameLocation() {
        return window.location.origin === origin &&
            (window.location.pathname === path || window.location.pathname === path + '/');
    }
    if ((origin !== 'https://framextv.cc' && origin !== 'https://framextv.xyz') ||
            typeof path !== 'string' || path.length > 160 ||
            !/^\/embed\/[1-9][0-9]{0,9}(?:\/[0-9]{1,4}\/[1-9][0-9]{0,4})?$/.test(path) ||
            typeof generation !== 'number' || !isFinite(generation) ||
            Math.floor(generation) !== generation || generation < 0 || generation > 2147483647 ||
            !sameLocation()) return diagnostic('context_mismatch');

    var previous = window.__nepalTheaterQuality;
    if (previous && typeof previous.getState === 'function') {
        var previousState = previous.getState();
        if (previousState && typeof previousState.generation === 'number' &&
                previousState.generation >= generation) return previousState;
        if (typeof previous.stop === 'function') previous.stop();
    }

    var state = { generation: generation, epoch: 0, phase: 'waiting', attempts: 0,
        quality: '', height: 0, bitrateMbps: 0, verified: false, reason: '' };
    var timer = null, settleTimer = null, alive = true, ownedMenu = false;
    var video = null, stage = 'wait', waits = 0, selections = 0;
    var pending = null, boundaryOpen = false, metadataWakeUsed = false, ownSwitch = false;
    var listeners = [];
    var controller = { stop: function () { shutdown('stopped', 'stopped', true); },
        getState: function () {
            return { generation: state.generation, epoch: state.epoch, phase: state.phase,
                attempts: state.attempts, quality: state.quality, height: state.height,
                bitrateMbps: state.bitrateMbps, verified: state.verified, reason: state.reason };
        } };
    window.__nepalTheaterQuality = controller;

    function active() {
        return alive && window.__nepalTheaterQuality === controller && sameLocation();
    }
    function clearTimer() {
        if (timer !== null) { window.clearTimeout(timer); timer = null; }
    }
    function clearSettle() {
        if (settleTimer !== null) { window.clearTimeout(settleTimer); settleTimer = null; }
        ownSwitch = false;
    }
    function text(element) {
        var value = element && element.textContent;
        return typeof value === 'string' && value.length <= 120 ? value.replace(/\s+/g, ' ').trim() : '';
    }
    function enabled(element) {
        return element && !element.disabled && element.getAttribute('aria-disabled') !== 'true';
    }
    function firstSpan(element) { return text(element && element.querySelector('span')); }
    function menu() { return document.querySelector(MENU); }
    function closeOwned() {
        if (!ownedMenu) return;
        ownedMenu = false;
        if (!sameLocation() || window.__nepalTheaterQuality !== controller) return;
        var current = menu(), close = current && current.querySelector(CLOSE);
        if (enabled(close)) close.click();
    }
    function shutdown(phase, reason, close) {
        if (!alive) return;
        clearTimer(); clearSettle();
        alive = false;
        if (close) closeOwned();
        else ownedMenu = false;
        for (var index = 0; index < listeners.length; index++) {
            listeners[index][0].removeEventListener(listeners[index][1], listeners[index][2], true);
        }
        listeners = [];
        state.phase = phase; state.reason = reason;
    }
    function listen(target, type, callback) {
        target.addEventListener(type, callback, true);
        listeners.push([target, type, callback]);
    }
    function schedule(delay) {
        clearTimer();
        if (!active()) return;
        timer = window.setTimeout(function () { timer = null; advance(); }, delay);
    }
    function unavailable(reason) {
        clearTimer(); clearSettle(); closeOwned();
        state.phase = 'unavailable'; state.reason = reason;
    }
    function beginEpoch(nextVideo, boundary) {
        if (!active()) return;
        clearTimer(); clearSettle(); closeOwned();
        if (state.epoch >= EPOCH_LIMIT) { shutdown('unavailable', 'epoch_limit', false); return; }
        state.epoch++; state.attempts = 0; state.phase = 'waiting'; state.reason = '';
        state.verified = false;
        video = nextVideo; stage = 'wait'; waits = 0; selections = 0; pending = null;
        boundaryOpen = !!boundary; metadataWakeUsed = false;
        schedule(0);
    }
    function settingsTarget(target) {
        for (var depth = 0; target && depth < 12; depth++, target = target.parentElement) {
            if (typeof target.getAttribute !== 'function') continue;
            var label = target.getAttribute('aria-label');
            if (label === 'Player Settings' || label === 'Playback Settings' &&
                    target.getAttribute('role') === 'menu') return true;
        }
        return false;
    }
    function manual(event) {
        if (event && event.isTrusted === true &&
                (state.phase === 'selecting' || settingsTarget(event.target))) {
            shutdown('manual', 'manual_interaction', false);
        }
    }
    function isVideo(element) {
        return element && typeof element.tagName === 'string' && element.tagName.toLowerCase() === 'video';
    }
    function media(event) {
        if (!active()) { if (alive) shutdown('stopped', 'context_changed', false); return; }
        var target = event && event.target;
        if (!isVideo(target) || target !== document.querySelector('video')) return;
        if (ownSwitch) {
            // A quality/source choice can itself replace or reload the video.
            video = target;
            return;
        }
        if (target !== video) {
            beginEpoch(target, event.type !== 'loadedmetadata');
        } else if (event.type === 'loadedmetadata') {
            if (boundaryOpen) boundaryOpen = false;
            // Metadata updates on the same selected video are not new sources.
            if (state.phase === 'selected') return;
            if (!metadataWakeUsed && state.attempts < STEP_LIMIT) {
                metadataWakeUsed = true;
                if (state.phase === 'unavailable' && stage === 'wait') state.phase = 'waiting';
                schedule(0);
            }
        } else if (!boundaryOpen) {
            beginEpoch(target, true);
        }
    }
    function qualityTab(current) {
        var back = current && current.querySelector(BACK);
        if (!back) return false;
        var spans = back.querySelectorAll('span');
        return spans.length > 0 && text(spans[spans.length - 1]).toLowerCase() === 'quality';
    }
    function qualityEntry(current) {
        var entries = current.querySelectorAll('button[role="menuitem"]');
        for (var index = 0; index < entries.length && index < 64; index++) {
            if (enabled(entries[index]) && firstSpan(entries[index]).toLowerCase() === 'quality') return entries[index];
        }
        return null;
    }
    function score(button) {
        if (!enabled(button)) return null;
        var label = firstSpan(button);
        var resolution = /^(?:(\d{3,4})p|([248])k)\b/i.exec(label);
        if (!resolution) return null;
        var height = resolution[1] ? Number(resolution[1]) :
            resolution[2] === '2' ? 1440 : resolution[2] === '4' ? 2160 : 4320;
        if (height < 144 || height > 8192) return null;
        var match = /\b(\d{1,3}(?:\.\d{1,3})?)\s*Mbps\b/i.exec(label);
        var bitrate = match ? Number(match[1]) : 0;
        return { button: button, height: height, bitrate: bitrate,
            checked: button.getAttribute('aria-checked') === 'true',
            key: height + ':' + bitrate,
            label: height + 'p' + (bitrate ? ' (' + bitrate + ' Mbps)' : '') };
    }
    function bestOption(current) {
        var options = current.querySelectorAll('button[role="menuitemradio"]'), best = null, primary = null;
        // Auto and the primary level/source options are siblings. A separate
        // nested Mirrors group uses the same radio role but independent state.
        for (var anchor = 0; anchor < options.length && anchor < 64; anchor++) {
            if (firstSpan(options[anchor]).toLowerCase() === 'auto') {
                primary = options[anchor].parentElement;
                break;
            }
        }
        if (!primary) return null;
        for (var index = 0; index < options.length && index < 64; index++) {
            if (options[index].parentElement !== primary) continue;
            var candidate = score(options[index]);
            if (candidate && (!best || candidate.height > best.height ||
                    candidate.height === best.height && candidate.bitrate > best.bitrate ||
                    candidate.height === best.height && candidate.bitrate === best.bitrate &&
                    candidate.checked && !best.checked)) best = candidate;
        }
        return best;
    }
    function remember(candidate) {
        state.quality = candidate.label; state.height = candidate.height;
        state.bitrateMbps = candidate.bitrate;
    }
    function confirmed(candidate) {
        remember(candidate); state.verified = true;
        state.phase = 'selected'; state.reason = 'highest_offered';
        clearTimer(); closeOwned();
        if (ownSwitch) {
            // Let events caused by this selection settle without a new epoch.
            settleTimer = window.setTimeout(function () { settleTimer = null; ownSwitch = false; }, 2500);
        }
    }
    function choose(candidate) {
        pending = candidate; remember(candidate); stage = 'confirm';
        if (!candidate.checked) {
            if (selections >= SELECTION_LIMIT) { unavailable('selection_limit'); return; }
            selections++; ownSwitch = true;
            candidate.button.click();
        }
        // React updates and selected attributes must be observed on a later step.
        schedule(250);
    }
    function advance() {
        if (!active()) { if (alive) shutdown('stopped', 'context_changed', false); return; }
        if (stage === 'wait') {
            var playback = window.__nepalTheaterPlayback;
            if (playback && typeof playback.getState === 'function' &&
                    playback.getState().subtitlePhase === 'selecting') {
                if (++waits >= WAIT_LIMIT) { unavailable('subtitle_menu_busy'); return; }
                schedule(500); return;
            }
            var settings = document.querySelector(SETTINGS);
            if (menu() || settings && settings.getAttribute('aria-expanded') === 'true') {
                shutdown('manual', 'existing_menu', false); return;
            }
            var currentVideo = document.querySelector('video');
            if (currentVideo && currentVideo !== video) {
                if (video) { beginEpoch(currentVideo, false); return; }
                video = currentVideo;
            }
            if (!video || video.readyState < 1 || !enabled(settings)) {
                if (++waits >= WAIT_LIMIT) { unavailable('not_ready'); return; }
                schedule(500); return;
            }
            ownedMenu = true; state.phase = 'selecting'; stage = 'menu';
            settings.click(); schedule(250); return;
        }
        if (state.attempts >= STEP_LIMIT) { unavailable('attempt_limit'); return; }
        state.attempts++;
        var current = menu();
        if (!current) { schedule(250); return; }
        if (!qualityTab(current)) {
            var entry = qualityEntry(current);
            if (entry) entry.click();
            schedule(250); return;
        }
        var best = bestOption(current);
        if (!best) { schedule(250); return; }
        if (stage !== 'confirm') { choose(best); return; }
        if (best.checked && pending && best.key === pending.key) { confirmed(best); return; }
        if (pending && (best.height > pending.height ||
                best.height === pending.height && best.bitrate > pending.bitrate)) {
            choose(best); return;
        }
        schedule(250);
    }

    listen(window, 'pointerdown', manual); listen(window, 'mousedown', manual);
    listen(window, 'touchstart', manual); listen(window, 'keydown', manual);
    listen(document, 'loadstart', media); listen(document, 'emptied', media);
    listen(document, 'loadedmetadata', media);
    beginEpoch(null, false);
    return controller.getState();
})
