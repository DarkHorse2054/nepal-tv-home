'use strict';

(() => {
  const SESSION_KEY = 'nepal.remote.session';
  const POLL_MS = 6000;
  const REPEAT_DELAY_MS = 420;
  const REPEAT_INTERVAL_MS = 180;
  const REPEAT_LIMIT = 12;
  const ERROR_MESSAGES = {
    incorrect_pin: 'That PIN did not match. Check the terminal and try again.',
    invalid_pin: 'Enter the 6-digit PIN shown in the terminal.',
    pair_rate_limited: 'Too many PIN attempts. Wait a minute and try again.',
    auth_rate_limited: 'Please wait a minute, then pair this remote again.',
    tv_unavailable: 'Fire TV is unavailable. Check that it is powered on.',
    control_busy: 'Fire TV is busy. Try again in a moment.',
    control_rate_limited: 'Give Fire TV a moment, then try again.',
    command_failed: 'Fire TV could not complete that action.',
    pair_required: 'Pair this remote before continuing.',
    session_expired: 'Your session ended. Enter the PIN to reconnect.',
    samsung_pair_required: 'Connect Samsung and select Allow on the TV.',
    samsung_authorization_denied: 'Samsung did not approve the connection. Select Allow on the TV when you reconnect.',
    samsung_pair_rate_limited: 'Wait a minute before connecting Samsung again.',
    samsung_busy: 'Samsung is busy. Try again in a moment.',
    samsung_tv_unavailable: 'Samsung is unavailable. Check that the TV is on.',
    samsung_connect_timeout: 'Samsung took too long to connect.',
    samsung_websockets_dependency_missing: 'The Samsung connection library is missing on this computer.',
    samsung_dependency_missing: 'The Samsung connection library is missing on this computer.',
    samsung_key_rate_limited: 'Give Samsung a moment, then try again.',
    samsung_authorization_pending: 'Samsung approval is still pending. Connect again and select Allow on the TV.',
    samsung_unavailable: 'Samsung could not complete that action.',
    samsung_connection_failed: 'Could not connect to Samsung. Check that the TV is on.',
    samsung_timeout: 'Samsung took too long to respond.',
    samsung_not_configured: 'Samsung controls are not configured for this remote.',
  };
  const keyButtons = [...document.querySelectorAll('[data-key]')];
  const appButtons = [...document.querySelectorAll('[data-app]')];
  const samsungButtons = [...document.querySelectorAll('[data-samsung]')];
  const samsungPair = document.querySelector('#samsung-pair');
  const samsungMessage = document.querySelector('#samsung-message');
  const samsungLastSent = document.querySelector('#samsung-last-sent');
  const samsungLabels = {
    source: 'Open Sources', up: 'Up', down: 'Down', left: 'Left', right: 'Right',
    ok: 'OK', back: 'Back', volume_up: 'Volume up', volume_down: 'Volume down',
    mute: 'Mute', power_off: 'Power off', hdmi1: 'HDMI 1', hdmi2: 'HDMI 2',
    hdmi3: 'HDMI 3', hdmi4: 'HDMI 4',
  };
  const dialog = document.querySelector('#pair-dialog');
  const form = document.querySelector('#pair-form');
  const pinInput = document.querySelector('#pair-pin');
  const pairSubmit = document.querySelector('#pair-submit');
  const pairError = document.querySelector('#pair-error');
  const connectionLabel = document.querySelector('#connection-label');
  const statusMessage = document.querySelector('#status-message');
  let token = readToken();
  let online = false;
  let statusRequest = null;
  let commandTail = Promise.resolve();
  let queuedCommands = 0;
  let hold = null;
  let toastTimer;
  let samsungPaired = false;
  let samsungBusy = false;

  function setSamsung(paired, message) {
    samsungPaired = Boolean(paired && token);
    samsungPair.disabled = !token || samsungBusy;
    samsungPair.hidden = samsungPaired;
    for (const button of samsungButtons) button.disabled = !samsungPaired || samsungBusy;
    samsungMessage.textContent = message || (samsungPaired
      ? 'Ready. Samsung controls work independently of Fire TV.'
      : 'Connect once and select Allow on your Samsung.');
  }

  function showSamsungLastSent(action) {
    if (!action || !Object.hasOwn(samsungLabels, action.key)) return;
    // A successful socket write is not confirmation that the TV acted on it.
    const observedTime = typeof action.sentAt === 'string' ? new Date(action.sentAt) : null;
    const timeLabel = observedTime && Number.isFinite(observedTime.getTime())
      ? ` · ${observedTime.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : '';
    samsungLastSent.textContent = `Last sent: ${samsungLabels[action.key]}${timeLabel}`;
    samsungLastSent.hidden = false;
  }

  function applySamsungSourceLabels(labels) {
    for (let index = 1; index <= 4; index++) {
      const key = `hdmi${index}`;
      const port = `HDMI ${index}`;
      const value = labels && typeof labels[key] === 'string' ? labels[key].trim().slice(0, 80) : '';
      const label = value || port;
      samsungLabels[key] = label.toLowerCase() === port.toLowerCase() ? port : `${port} (${label})`;
      const button = samsungButtons.find(candidate => candidate.dataset.samsung === key);
      button.textContent = label;
      button.setAttribute('aria-label', `Test ${samsungLabels[key]} key`);
      button.title = `Test ${samsungLabels[key]} key. Check the TV for the result.`;
    }
  }

  function readToken() {
    try { return sessionStorage.getItem(SESSION_KEY) || ''; } catch { return ''; }
  }

  function saveToken(value) {
    token = value;
    try {
      if (value) sessionStorage.setItem(SESSION_KEY, value);
      else sessionStorage.removeItem(SESSION_KEY);
    } catch { /* Pairing still works for this page if browser storage is unavailable. */ }
  }

  function setOnline(value, message) {
    online = Boolean(value && token);
    document.body.classList.toggle('is-online', online);
    connectionLabel.textContent = online ? 'Connected' : token ? 'TV offline' : 'Pair remote';
    for (const button of [...keyButtons, ...appButtons]) button.disabled = !online;
    if (!online) endHold();
    if (!token || !samsungBusy) setSamsung(samsungPaired);
    statusMessage.textContent = message || (online
      ? 'Ready when you are. Hold an arrow to keep moving.'
      : token ? 'Fire TV is unavailable. Keep it powered and on the same network.'
      : 'Pair once to control your Fire TV from here.');
  }

  function toast(message) {
    const element = document.querySelector('#toast');
    clearTimeout(toastTimer);
    element.textContent = message;
    element.classList.add('is-visible');
    toastTimer = setTimeout(() => element.classList.remove('is-visible'), 3000);
  }

  function openPairing(message = '') {
    endHold();
    pairError.textContent = message;
    document.querySelector('#pair-close').hidden = !token;
    pinInput.value = '';
    if (!dialog.open) dialog.showModal();
    setTimeout(() => pinInput.focus(), 50);
  }

  async function api(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeoutMs || 8000);
    const headers = { Accept: 'application/json' };
    if (options.body) headers['Content-Type'] = 'application/json';
    if (options.auth !== false && token) headers.Authorization = `Bearer ${token}`;
    try {
      const response = await fetch(path, {
        method: options.method || 'GET', headers, cache: 'no-store',
        credentials: 'same-origin', signal: controller.signal,
        ...(options.body ? { body: JSON.stringify(options.body) } : {}),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = new Error(ERROR_MESSAGES[data.error] || 'The remote could not complete that request.');
        error.status = response.status;
        error.code = data.error;
        if (response.status === 401 && options.auth !== false) {
          saveToken('');
          setOnline(false);
          openPairing('Your session ended. Enter the PIN to reconnect.');
        }
        throw error;
      }
      return data;
    } finally { clearTimeout(timeout); }
  }

  async function refreshStatus() {
    if (!token || statusRequest || document.hidden) return;
    const requestToken = token;
    document.body.classList.add('is-checking');
    statusRequest = (async () => {
      try {
        const data = await api('/api/status');
        if (token !== requestToken) return;
        setOnline(data.online);
        document.querySelector('#samsung-panel').hidden = data.samsung?.enabled !== true;
        if (!samsungBusy) setSamsung(data.samsung?.paired === true);
        applySamsungSourceLabels(data.samsung?.sourceLabels);
        showSamsungLastSent(data.samsung?.lastSent);
        if (typeof data.device?.name === 'string') document.querySelector('#device-name').textContent = data.device.name;
        if (typeof data.device?.model === 'string') document.querySelector('#device-model').textContent = data.device.model;
        if (typeof data.date?.nepal === 'string' && data.date.nepal) document.querySelector('#nepal-date').textContent = data.date.nepal;
        const secondaryTime = data.date?.secondaryTime || data.date?.kathmanduTime;
        if (typeof secondaryTime === 'string' && secondaryTime) document.querySelector('#kathmandu-time').textContent = `${data.date.secondaryLabel || 'Kathmandu'} · ${secondaryTime}`;
      } catch (error) {
        if (token === requestToken) setOnline(false, error.name === 'AbortError'
          ? 'The connection is taking too long. Tap the status above to try again.'
          : 'The remote server is unavailable. Keep it running on your computer.');
      } finally {
        statusRequest = null;
        document.body.classList.remove('is-checking');
      }
    })();
    return statusRequest;
  }

  function sendCommand(path, body, label, repeating = false) {
    if (!online || !token || (repeating && queuedCommands > 0) || queuedCommands >= 4) return Promise.resolve(false);
    const requestToken = token;
    queuedCommands++;
    const task = commandTail.then(async () => {
      if (!online || token !== requestToken) return false;
      try {
        await api(path, { method: 'POST', body });
        if (!repeating && navigator.vibrate) navigator.vibrate(8);
        return true;
      } catch (error) {
        if (error.status !== 401) {
          endHold();
          toast(error.name === 'AbortError' ? 'Fire TV took too long to respond.' : error.message);
          refreshStatus();
        }
        return false;
      }
    }).finally(() => { queuedCommands--; });
    commandTail = task.catch(() => false);
    return task;
  }

  function sendKey(key, repeating = false) {
    return sendCommand('/api/key', { key }, key, repeating);
  }

  function beginHold(button, key, pointerId) {
    endHold();
    if (!online || button.disabled) return;
    button.classList.add('is-pressed');
    const current = { button, key, pointerId, count: 0, timer: null };
    hold = current;
    sendKey(key);
    function repeat() {
      if (hold !== current || !online) return;
      if (++current.count > REPEAT_LIMIT) { endHold(); return; }
      sendKey(key, true);
      current.timer = setTimeout(repeat, REPEAT_INTERVAL_MS);
    }
    current.timer = setTimeout(repeat, REPEAT_DELAY_MS);
  }

  function endHold() {
    if (!hold) return;
    clearTimeout(hold.timer);
    hold.button.classList.remove('is-pressed');
    hold = null;
  }

  for (const button of keyButtons) {
    const key = button.dataset.key;
    if (button.hasAttribute('data-repeat')) {
      button.addEventListener('pointerdown', event => {
        if (event.button !== 0 || button.disabled) return;
        event.preventDefault();
        button.setPointerCapture(event.pointerId);
        beginHold(button, key, event.pointerId);
      });
      for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
        button.addEventListener(type, event => {
          if (hold?.pointerId === event.pointerId) endHold();
        });
      }
      // A zero-detail click is a keyboard or assistive-technology activation.
      button.addEventListener('click', event => { if (event.detail === 0) sendKey(key); });
    } else {
      button.addEventListener('click', () => {
        sendKey(key).then(sent => {
          if (sent && key === 'wake') toast('Wake sent to Fire TV.');
          if (sent && key === 'sleep') toast('Sleep sent to Fire TV.');
        });
      });
    }
  }

  for (const button of appButtons) {
    button.addEventListener('click', () => {
      button.classList.add('is-pressed');
      setTimeout(() => button.classList.remove('is-pressed'), 220);
      sendCommand('/api/app', { app: button.dataset.app }, button.getAttribute('aria-label'));
    });
  }

  samsungPair.addEventListener('click', async () => {
    if (!token || samsungBusy) return;
    samsungBusy = true;
    setSamsung(false, 'Select Allow for “Local TV Remote” on your Samsung now.');
    try {
      const data = await api('/api/samsung/pair', { method: 'POST', body: {}, timeoutMs: 38000 });
      samsungBusy = false;
      setSamsung(data.state === 'authorized', data.state === 'authorized'
        ? 'Connected. Samsung panel controls are ready.'
        : 'Approval timed out. Connect again when you are beside the TV.');
    } catch (error) {
      samsungBusy = false;
      setSamsung(false, error.name === 'AbortError' ? 'Samsung took too long. Try connecting again.' : error.message);
    }
  });

  async function sendSamsung(key) {
    if (!token || !samsungPaired || samsungBusy) return;
    samsungBusy = true;
    setSamsung(true, 'Sending to Samsung…');
    try {
      const data = await api('/api/samsung/key', { method: 'POST', body: { key }, timeoutMs: 12000 });
      if (data.state === 'sent') showSamsungLastSent({ key, sentAt: data.sentAt });
    } catch (error) {
      if (error.code === 'samsung_pair_required' || error.code === 'samsung_authorization_denied') samsungPaired = false;
      toast(error.name === 'AbortError' ? 'Samsung took too long to respond.' : error.message);
    } finally {
      samsungBusy = false;
      setSamsung(samsungPaired);
    }
  }
  for (const button of samsungButtons) button.addEventListener('click', () => sendSamsung(button.dataset.samsung));

  const keyboardKeys = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    Enter: 'ok', Escape: 'back', Backspace: 'back', ' ': 'play_pause',
    Home: 'home', h: 'home', H: 'home', m: 'menu', M: 'menu',
    ContextMenu: 'menu', r: 'rewind', R: 'rewind', f: 'fast_forward', F: 'fast_forward',
  };
  const repeatKeys = new Set(['up', 'down', 'left', 'right', 'rewind', 'fast_forward']);
  document.addEventListener('keydown', event => {
    if (dialog.open || event.ctrlKey || event.altKey || event.metaKey) return;
    const target = event.target;
    if (target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
    const key = keyboardKeys[event.key];
    if (!key) return;
    if (target instanceof HTMLElement && target.closest('#samsung-panel')) {
      if ((target instanceof HTMLButtonElement || target.tagName === 'SUMMARY') && (event.key === 'Enter' || event.key === ' ')) return;
      if (['up', 'down', 'left', 'right', 'ok', 'back'].includes(key)) {
        event.preventDefault();
        if (!event.repeat) sendSamsung(key);
      }
      return;
    }
    if (!online) return;
    // Enter/Space on a focused button retains native keyboard activation and focus behavior.
    if (target instanceof HTMLButtonElement && (event.key === 'Enter' || event.key === ' ')) return;
    event.preventDefault();
    if (event.repeat) return;
    const button = keyButtons.find(candidate => candidate.dataset.key === key);
    if (repeatKeys.has(key) && button) beginHold(button, key, 'keyboard');
    else sendKey(key);
  });
  document.addEventListener('keyup', event => {
    if (hold?.pointerId === 'keyboard' && keyboardKeys[event.key] === hold.key) endHold();
  });
  window.addEventListener('blur', endHold);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) endHold();
    else refreshStatus();
  });
  document.querySelector('#connection-button').addEventListener('click', () => {
    if (token) refreshStatus();
    else openPairing();
  });
  document.querySelector('#pairing-button').addEventListener('click', () => openPairing());
  document.querySelector('#pair-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', event => {
    if (!token) event.preventDefault();
  });
  pinInput.addEventListener('input', () => {
    pinInput.value = pinInput.value.replace(/[^0-9]/g, '').slice(0, 6);
    pairError.textContent = '';
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (pairSubmit.disabled || !/^[0-9]{6}$/.test(pinInput.value)) return;
    pairSubmit.disabled = true;
    pairSubmit.firstChild.textContent = 'Connecting…';
    pairError.textContent = '';
    try {
      const data = await api('/api/pair', { method: 'POST', body: { pin: pinInput.value }, auth: false });
      if (typeof data.token !== 'string' || !data.token) throw new Error('The server did not return a pairing session.');
      saveToken(data.token);
      pinInput.value = '';
      dialog.close();
      connectionLabel.textContent = 'Connecting';
      await refreshStatus();
    } catch (error) {
      pairError.textContent = error.status === 401 || error.status === 403
        ? 'That PIN did not match. Check the terminal and try again.'
        : error.name === 'AbortError' ? 'The server took too long. Please try again.' : error.message;
      pinInput.focus();
      pinInput.select();
    } finally {
      pairSubmit.disabled = false;
      pairSubmit.firstChild.textContent = 'Connect remote';
    }
  });

  setOnline(false);
  setSamsung(false);
  if (token) refreshStatus();
  else openPairing();
  setInterval(refreshStatus, POLL_MS);
})();
