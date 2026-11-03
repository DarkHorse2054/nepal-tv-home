(function (config) {
    'use strict';
    var LIMIT = 180, MENU_LIMIT = 32, EPOCH_LIMIT = 12;
    var BUTTON = 'button[aria-label="Subtitles / Captions"]';
    var MENU = '[role="menu"][aria-label="Subtitle Options"]';
    var CLOSE = 'button[aria-label="Close subtitles menu"]';
    var SETTINGS = '[role="menu"][aria-label="Playback Settings"]';
    var origin = config && config.origin, path = config && config.path;
    var generation = config && config.generation;
    var target = config && config.resumeSeconds !== undefined ? config.resumeSeconds : 0;
    var enabled = !config || config.subtitlesEnabled === undefined ? true : config.subtitlesEnabled;
    var language = config && config.preferredLanguage || 'en';
    function finite(value) { return typeof value === 'number' && isFinite(value); }
    function locationMatches() {
        return window.location.origin === origin &&
            (window.location.pathname === path || window.location.pathname === path + '/');
    }
    function empty(reason) {
        return { generation: finite(generation) ? generation : 0, resumePhase: 'unavailable',
            resumeTarget: 0, resumeReason: reason, subtitlePhase: 'unavailable', subtitleLabel: '',
            subtitleCount: 0, subtitleReason: reason, subtitlesEnabled: true };
    }
    if ((origin !== 'https://framextv.cc' && origin !== 'https://framextv.xyz') ||
            typeof path !== 'string' || path.length > 160 ||
            !/^\/embed\/[1-9][0-9]{0,9}(?:\/[0-9]{1,4}\/[1-9][0-9]{0,4})?$/.test(path) ||
            !finite(generation) || Math.floor(generation) !== generation || generation < 0 || generation > 2147483647 ||
            !finite(target) || target < 0 || target > 604800 || typeof enabled !== 'boolean' ||
            typeof language !== 'string' || !/^[a-z]{2,3}(?:-[a-z]{2})?$/i.test(language) ||
            !locationMatches()) return empty('invalid_context');
    language = language.toLowerCase();
    var previous = window.__nepalTheaterPlayback;
    if (previous && typeof previous.getState === 'function') {
        var old = previous.getState();
        if (old && finite(old.generation) && old.generation >= generation) return old;
        if (typeof previous.stop === 'function') previous.stop();
    }

    var state = { generation: generation, resumePhase: target > 0 ? 'waiting' : 'skipped',
        resumeTarget: target, resumeReason: target > 0 ? '' : 'no_saved_position',
        subtitlePhase: 'waiting', subtitleLabel: '', subtitleCount: 0,
        subtitleReason: '', subtitlesEnabled: enabled };
    var alive = true, resumeTimer = null, subtitleTimer = null, listeners = [];
    var resumeTicks = 0, seekSent = false, seekVideo = null;
    var video = null, boundary = false, epochs = 0, subtitleWaits = 0, menuSteps = 0;
    var subtitleStage = 'wait', owned = false, pending = null, selectionSent = false;
    var manualSubtitles = false;
    var controller = { getState: snapshot, stop: stop, cancelResume: cancelResume,
        setSubtitles: function (value) {
            if (!active() || typeof value !== 'boolean') return snapshot();
            state.subtitlesEnabled = value; manualSubtitles = false; epochs = 0;
            startSubtitles(); return snapshot();
        } };
    window.__nepalTheaterPlayback = controller;

    function snapshot() {
        return { generation: state.generation, resumePhase: state.resumePhase,
            resumeTarget: state.resumeTarget, resumeReason: state.resumeReason,
            subtitlePhase: state.subtitlePhase, subtitleLabel: state.subtitleLabel,
            subtitleCount: state.subtitleCount, subtitleReason: state.subtitleReason,
            subtitlesEnabled: state.subtitlesEnabled };
    }
    function active() { return alive && window.__nepalTheaterPlayback === controller && locationMatches(); }
    function clearResume() {
        if (resumeTimer !== null) { window.clearTimeout(resumeTimer); resumeTimer = null; }
    }
    function clearSubtitle() {
        if (subtitleTimer !== null) { window.clearTimeout(subtitleTimer); subtitleTimer = null; }
    }
    function resumePending() { return state.resumePhase === 'waiting' || state.resumePhase === 'seeking'; }
    function finishResume(phase, reason) { clearResume(); state.resumePhase = phase; state.resumeReason = reason; }
    function cancelResume() {
        if (resumePending()) finishResume('cancelled', 'manual_seek');
        return snapshot();
    }
    function currentVideo() { return document.querySelector('video'); }
    function isCurrentVideo(element) {
        return element && typeof element.tagName === 'string' && element.tagName.toLowerCase() === 'video' &&
            element === currentVideo();
    }
    function text(element) {
        var value = element && element.textContent;
        if (typeof value !== 'string' || value.length > 120 || /[\x00-\x1F\x7F-\x9F]/.test(value) || /:\/\//.test(value)) return '';
        return value.replace(/\s+/g, ' ').trim().slice(0, 64);
    }
    function label(element) { return text(element && element.querySelector('span')); }
    function usable(element) {
        return element && !element.disabled && element.getAttribute('aria-disabled') !== 'true';
    }
    function closeOwned() {
        if (!owned) return;
        owned = false;
        if (!locationMatches() || window.__nepalTheaterPlayback !== controller) return;
        var menu = document.querySelector(MENU), close = menu && menu.querySelector(CLOSE);
        if (usable(close)) close.click();
    }
    function stop() {
        if (!alive) return snapshot();
        clearResume(); clearSubtitle(); closeOwned(); alive = false;
        for (var index = 0; index < listeners.length; index++) {
            listeners[index][0].removeEventListener(listeners[index][1], listeners[index][2], true);
        }
        listeners = [];
        if (resumePending()) { state.resumePhase = 'stopped'; state.resumeReason = 'stopped'; }
        state.subtitlePhase = 'stopped'; state.subtitleReason = 'stopped';
        return snapshot();
    }
    function guard() { if (active()) return true; if (alive) stop(); return false; }
    function listen(object, type, callback) {
        object.addEventListener(type, callback, true); listeners.push([object, type, callback]);
    }
    function queueResume(delay) {
        clearResume();
        if (active() && resumePending()) resumeTimer = window.setTimeout(function () {
            resumeTimer = null; resumeStep();
        }, delay);
    }
    function queueSubtitle(delay) {
        clearSubtitle();
        if (active() && !manualSubtitles) subtitleTimer = window.setTimeout(function () {
            subtitleTimer = null; subtitleStep();
        }, delay);
    }
    function seekable(element) {
        try {
            var ranges = element.seekable;
            if (!ranges || !finite(ranges.length)) return false;
            for (var index = 0; index < ranges.length && index < 32; index++) {
                var start = ranges.start(index), end = ranges.end(index);
                if (finite(start) && finite(end) && target >= start && target <= end) return true;
            }
        } catch (notReady) { /* Readiness may change while the provider swaps its source. */ }
        return false;
    }
    function resumeStep() {
        if (!guard() || !resumePending()) return;
        if (++resumeTicks > LIMIT) { finishResume('unavailable', 'resume_timeout'); return; }
        var element = currentVideo();
        if (seekSent && element !== seekVideo) { finishResume('unavailable', 'source_changed'); return; }
        if (!element || element.readyState < 1) { queueResume(500); return; }
        if (element.ended === true) { finishResume('unavailable', 'ended'); return; }
        var duration = element.duration;
        if (!finite(duration) || duration <= 0) { queueResume(500); return; }
        if (target >= duration - 1) { finishResume('unavailable', 'outside_duration'); return; }
        if (finite(element.currentTime) && Math.abs(element.currentTime - target) <= 2) {
            finishResume('done', seekSent ? 'restored' : 'already_at_position'); return;
        }
        if (!seekSent && seekable(element)) {
            seekSent = true; seekVideo = element; state.resumePhase = 'seeking';
            // Documented provider controller. No playback command or source URL.
            window.postMessage({ action: 'seek', time: target }, origin);
        }
        queueResume(500);
    }
    function qualityBusy() {
        var quality = window.__nepalTheaterQuality;
        return quality && typeof quality.getState === 'function' && quality.getState().phase === 'selecting';
    }
    function subtitleUnavailable(reason) {
        clearSubtitle(); closeOwned(); state.subtitlePhase = 'unavailable'; state.subtitleReason = reason;
    }
    function startSubtitles() {
        var closing = owned;
        clearSubtitle(); closeOwned();
        if (++epochs > EPOCH_LIMIT) { subtitleUnavailable('source_limit'); return; }
        subtitleWaits = 0; menuSteps = 0; subtitleStage = closing ? 'closing' : 'wait'; pending = null; selectionSent = false;
        state.subtitlePhase = closing ? 'selecting' : 'waiting'; state.subtitleReason = ''; state.subtitleCount = 0;
        queueSubtitle(closing ? 250 : 0);
    }
    function languageRank(value) {
        var lower = value.toLowerCase();
        if (language.indexOf('en') === 0) return /\benglish\b|\beng\b|^en(?:\b|[-_])/.test(lower) ? 2 : 1;
        return lower === language || lower.indexOf(language + ' ') === 0 ? 2 : 1;
    }
    function choices(menu) {
        var rows = menu.querySelectorAll('button[role="menuitemradio"]');
        var best = null, off = null, count = 0;
        for (var index = 0; index < rows.length && index < 64; index++) {
            if (!usable(rows[index])) continue;
            var name = label(rows[index]);
            if (!name) continue;
            var entry = { button: rows[index], label: name, index: index,
                checked: rows[index].getAttribute('aria-checked') === 'true', rank: languageRank(name) };
            if (name.toLowerCase() === 'off') { off = entry; continue; }
            count++;
            if (!best || entry.rank > best.rank || entry.rank > 1 && entry.rank === best.rank && entry.checked && !best.checked) best = entry;
        }
        state.subtitleCount = count;
        return state.subtitlesEnabled ? best : off;
    }
    function subtitleStep() {
        if (!guard() || manualSubtitles) return;
        if (subtitleStage === 'closing') {
            if (document.querySelector(MENU)) {
                if (++menuSteps >= MENU_LIMIT) { subtitleUnavailable('menu_close_timeout'); return; }
                queueSubtitle(250); return;
            }
            subtitleStage = 'wait'; state.subtitlePhase = 'waiting';
        }
        if (subtitleStage === 'wait') {
            if (qualityBusy() || document.querySelector(SETTINGS)) {
                if (++subtitleWaits >= LIMIT) { subtitleUnavailable('settings_busy'); return; }
                queueSubtitle(500); return;
            }
            var toggle = document.querySelector(BUTTON);
            if (document.querySelector(MENU) || toggle && toggle.getAttribute('aria-expanded') === 'true') {
                manualSubtitles = true; state.subtitlePhase = 'manual'; state.subtitleReason = 'existing_menu'; return;
            }
            if (document.querySelector('[role="menu"]') || document.querySelector('[role="dialog"]')) {
                if (++subtitleWaits >= LIMIT) { subtitleUnavailable('menu_busy'); return; }
                queueSubtitle(500); return;
            }
            var element = currentVideo();
            if (!element || element.readyState < 1 || !usable(toggle)) {
                if (++subtitleWaits >= LIMIT) { subtitleUnavailable('not_available'); return; }
                queueSubtitle(500); return;
            }
            state.subtitlePhase = 'selecting'; subtitleStage = 'options'; owned = true;
            toggle.click(); queueSubtitle(250); return;
        }
        if (qualityBusy()) {
            if (++subtitleWaits >= LIMIT) { subtitleUnavailable('settings_busy'); return; }
            queueSubtitle(500); return;
        }
        if (menuSteps >= MENU_LIMIT) { subtitleUnavailable(pending ? 'selection_unconfirmed' : 'no_tracks'); return; }
        menuSteps++;
        var current = document.querySelector(MENU);
        if (!current) {
            // A selection closes this provider menu; reopen only our own menu
            // on a later step to verify its real checked attribute.
            var button = document.querySelector(BUTTON);
            if (owned && usable(button) && button.getAttribute('aria-expanded') !== 'true') button.click();
            queueSubtitle(250); return;
        }
        var selected = choices(current);
        if (!selected) { queueSubtitle(250); return; }
        if (subtitleStage === 'confirm' && pending && selected.label === pending.label &&
                selected.index === pending.index && selected.checked) {
            state.subtitleLabel = selected.label;
            state.subtitlePhase = state.subtitlesEnabled ? 'selected' : 'off';
            state.subtitleReason = 'verified'; clearSubtitle(); closeOwned(); return;
        }
        if (!pending) {
            pending = selected; subtitleStage = 'confirm';
            if (!selected.checked) { selectionSent = true; selected.button.click(); }
        } else if (!selectionSent && !selected.checked) {
            // A previously checked choice changed before verification. Do not
            // fight the provider or send repeated selection commands.
            subtitleUnavailable('selection_changed'); return;
        }
        queueSubtitle(250);
    }
    function subtitleTarget(element) {
        for (var depth = 0; element && depth < 12; depth++, element = element.parentElement) {
            if (typeof element.getAttribute !== 'function') continue;
            var name = element.getAttribute('aria-label');
            if (name === 'Subtitles / Captions' || name === 'Subtitle Options' && element.getAttribute('role') === 'menu') return true;
        }
        return false;
    }
    function seekTarget(element) {
        for (var depth = 0; element && depth < 12; depth++, element = element.parentElement) {
            if (typeof element.getAttribute !== 'function') continue;
            var name = element.getAttribute('aria-label') || '';
            if (name === 'Video timeline scrubber' || /^(?:Seek|Rewind|Fast forward|Forward)\b/i.test(name)) return true;
        }
        return false;
    }
    function manual(event) {
        if (!event || event.isTrusted !== true) return;
        var key = event.key || '';
        if (seekTarget(event.target) || event.type === 'keydown' &&
                /^(?:ArrowLeft|ArrowRight|Home|End|j|J|l|L)$/.test(key)) cancelResume();
        if (state.subtitlePhase === 'selecting' || subtitleTarget(event.target)) {
            clearSubtitle(); owned = false; manualSubtitles = true;
            state.subtitlePhase = 'manual'; state.subtitleReason = 'manual_interaction';
        }
    }
    function media(event) {
        if (!guard() || !isCurrentVideo(event.target)) return;
        var element = event.target;
        if (seekSent && resumePending() && element !== seekVideo) finishResume('unavailable', 'source_changed');
        if (resumePending()) queueResume(0);
        if (element !== video) {
            video = element; boundary = event.type !== 'loadedmetadata';
            if (!manualSubtitles) startSubtitles();
        } else if (event.type === 'loadstart' || event.type === 'emptied') {
            if (!boundary) { boundary = true; if (!manualSubtitles) startSubtitles(); }
        } else if (event.type === 'loadedmetadata') boundary = false;
    }

    listen(window, 'pointerdown', manual); listen(window, 'mousedown', manual);
    listen(window, 'touchstart', manual); listen(window, 'keydown', manual);
    listen(window, 'input', manual); listen(window, 'change', manual);
    listen(document, 'loadedmetadata', media); listen(document, 'durationchange', media);
    listen(document, 'loadstart', media); listen(document, 'emptied', media); listen(document, 'seeked', media);
    video = currentVideo(); startSubtitles(); queueResume(0);
    return snapshot();
})
