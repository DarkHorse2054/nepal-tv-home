'use strict';

(() => {
  const $ = selector => document.querySelector(selector);
  const SESSION_KEY = 'nepal.remote.session';
  const POLL_MS = 6000;
  const MAX_QUEUE_AGE_MS = 1200;
  const ERROR_MESSAGES = {
    incorrect_pin: 'That code did not match. Check the code on your Mac.',
    invalid_pin: 'Enter the 6-digit code shown on your Mac.',
    pair_rate_limited: 'Too many attempts. Wait a minute and try again.',
    auth_rate_limited: 'Wait a minute, then pair this remote again.',
    tv_unavailable: 'Fire TV is offline. Turn it on, then reconnect.',
    control_busy: 'Another action is finishing. Try again in a moment.',
    control_rate_limited: 'Give Fire TV a moment, then try again.',
    command_failed: 'Fire TV could not complete that action.',
    pair_required: 'Pair this remote to continue.',
    session_expired: 'Your session ended. Pair this remote again.',
    invalid_text_length: 'Enter between 1 and 160 characters.',
    split_text_required: 'Send this text in smaller parts.',
    unsupported_text_character: 'Use English letters, numbers and standard symbols.',
    text_send_uncertain: 'Text may have reached the TV. Check the TV before trying again.',
    samsung_pair_required: 'Connect Samsung and select Allow on the TV.',
    samsung_authorization_denied: 'Samsung did not approve the connection. Connect again and select Allow.',
    samsung_pair_rate_limited: 'Wait a minute before connecting Samsung again.',
    samsung_busy: 'Samsung is busy. Try again in a moment.',
    samsung_tv_unavailable: 'Samsung is unavailable. Check that the TV is on.',
    samsung_connect_timeout: 'Samsung took too long to connect.',
    samsung_websockets_dependency_missing: 'Samsung setup is incomplete on your Mac.',
    samsung_dependency_missing: 'Samsung setup is incomplete on your Mac.',
    samsung_key_rate_limited: 'Give Samsung a moment, then try again.',
    samsung_authorization_pending: 'Approval is still pending. Connect again and select Allow on Samsung.',
    samsung_unavailable: 'Samsung could not complete that action.',
    samsung_connection_failed: 'Could not reach Samsung. Check that the TV is on.',
    samsung_timeout: 'Samsung took too long to respond.',
    samsung_not_configured: 'Samsung controls have not been set up on your Mac.',
    feature_rate_limited: 'Give the remote a moment, then refresh.',
    invalid_wallpaper_mode: 'Choose Auto, Morning, Dusk or Night.',
    wallpaper_unavailable: 'Wallpaper control is unavailable. Check that Nepal Home is up to date.',
  };
  const keyButtons = [...document.querySelectorAll('[data-key]')];
  const appButtons = [...document.querySelectorAll('[data-app]')];
  const samsungButtons = [...document.querySelectorAll('[data-samsung]')];
  const sheets = [...document.querySelectorAll('dialog')];
  const samsungLabels = {
    source: 'Open Sources', up: 'Up', down: 'Down', left: 'Left', right: 'Right',
    ok: 'OK', back: 'Back', volume_up: 'Volume up', volume_down: 'Volume down',
    mute: 'Mute', power_off: 'Power off', hdmi1: 'HDMI 1', hdmi2: 'HDMI 2', hdmi3: 'HDMI 3', hdmi4: 'HDMI 4',
  };
  let token = readToken();
  let online = false;
  let serverAvailable = true;
  let checkedStatus = false;
  let samsungEnabled = false;
  let samsungPaired = false;
  let samsungBusy = false;
  let samsungMessage = '';
  let lastSeen = null;
  let statusRequest = null;
  let reconnecting = false;
  let commandTail = Promise.resolve();
  let queuedCommands = 0;
  let commandEpoch = 0;
  let hold = null;
  let textSending = false;
  let textIntent = 0;
  let toastTimer;
  let toastTarget = null;

  function readToken() {
    try { return sessionStorage.getItem(SESSION_KEY) || ''; } catch { return ''; }
  }
  function saveToken(value) {
    token = value;
    try {
      if (value) sessionStorage.setItem(SESSION_KEY, value);
      else sessionStorage.removeItem(SESSION_KEY);
    } catch { /* Pairing works in memory when browser storage is unavailable. */ }
  }
  function lastSeenLabel() {
    return lastSeen ? `Last seen ${lastSeen.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : 'Not seen in this session';
  }
  function renderState() {
    const fireReady = Boolean(token && online && serverAvailable);
    const samsungReady = Boolean(token && serverAvailable && samsungPaired && !samsungBusy);
    document.body.classList.toggle('is-online', fireReady);
    document.body.classList.toggle('is-server-offline', !serverAvailable);
    for (const button of [...keyButtons, ...appButtons]) button.disabled = !fireReady;
    for (const button of samsungButtons) button.disabled = !samsungReady;
    $('#type-button').disabled = !fireReady;
    $('#sources-button').disabled = !token || !serverAvailable || !samsungEnabled;
    $('#samsung-pair').disabled = !token || !serverAvailable || samsungBusy;
    $('#samsung-pair').hidden = samsungPaired;
    $('#samsung-panel').hidden = !samsungEnabled;
    $('#samsung-setup').hidden = !samsungEnabled;
    $('#samsung-status-row').hidden = !samsungEnabled;
    $('#samsung-status').textContent = samsungBusy ? 'Sending…' : samsungPaired ? 'Paired' : 'Not paired';
    $('#samsung-message').textContent = samsungMessage || (samsungPaired
      ? 'Paired. Volume and Sources work independently of Fire TV. The remote cannot check the TV’s current input or power.'
      : 'Connect once, then select Allow on your Samsung.');
    $('#mac-status').textContent = serverAvailable ? 'Available' : 'Unavailable';
    $('#fire-status').textContent = !token ? 'Pair remote' : !serverAvailable ? 'Not checked' : !checkedStatus ? 'Checking…' : online ? 'Reachable' : 'Offline';
    $('#connection-label').textContent = !serverAvailable ? 'Mac offline' : !token ? 'Pair remote' : !checkedStatus ? 'Connecting' : online ? 'Connected' : 'Fire TV offline';
    $('#connection-banner').hidden = fireReady;
    $('#last-seen').hidden = !token || !checkedStatus || online;
    $('#last-seen').textContent = lastSeenLabel();
    $('#reconnect-button').disabled = reconnecting;
    $('#settings-reconnect').disabled = reconnecting;
    $('#reconnect-button').textContent = reconnecting ? 'Checking…' : !token ? 'Pair' : 'Reconnect';
    $('#settings-reconnect').lastChild.textContent = reconnecting ? 'Checking…' : !token ? 'Pair this remote' : 'Reconnect Fire TV';
    if (!serverAvailable) {
      $('#status-title').textContent = 'Your Mac is unavailable';
      $('#status-message').textContent = 'Keep the remote running on your Mac and connect to the same Wi-Fi.';
      $('#connection-help').textContent = 'Wake your Mac and check that the remote is still running. Your phone must be on the same Wi-Fi. Then tap Reconnect.';
    } else if (!token) {
      $('#status-title').textContent = 'Make yourself at home';
      $('#status-message').textContent = 'Pair with the code shown on your Mac.';
      $('#connection-help').textContent = 'Pair with the 6-digit code shown on your Mac. Keep this Mac awake and your phone on the same Wi-Fi.';
    } else if (!checkedStatus) {
      $('#status-title').textContent = 'Finding your Fire TV';
      $('#status-message').textContent = 'Checking the connection…';
      $('#connection-help').textContent = 'Keep Fire TV powered on and connected to the same Wi-Fi as this Mac.';
    } else {
      $('#status-title').textContent = 'Fire TV is offline';
      $('#status-message').textContent = samsungPaired ? 'Turn on Fire TV, then reconnect. Samsung controls are still available.' : 'Turn on Fire TV and check its Wi-Fi, then reconnect.';
      $('#connection-help').textContent = online ? 'Keep this Mac awake and your phone on the same Wi-Fi. Connected means Fire TV is reachable; it does not confirm that the screen is on.' : 'Turn on Fire TV and check its Wi-Fi. If you see an “Allow USB debugging” prompt on the TV, approve this Mac, then reconnect.';
    }
    updateTextButton();
    renderToolsState();
  }
  function setConnection(isOnline) {
    if (online && !isOnline) invalidateCommands();
    online = Boolean(isOnline && token);
    if (online) lastSeen = new Date();
    renderState();
  }
  function showSamsungLastSent(action) {
    if (!action || !Object.hasOwn(samsungLabels, action.key)) return;
    const observed = typeof action.sentAt === 'string' ? new Date(action.sentAt) : null;
    const time = observed && Number.isFinite(observed.getTime()) ? ` · ${observed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : '';
    $('#samsung-last-sent').textContent = `Last sent: ${samsungLabels[action.key]}${time}`;
    $('#samsung-last-sent').hidden = false;
  }
  function applySamsungSourceLabels(labels) {
    for (let index = 1; index <= 4; index++) {
      const key = `hdmi${index}`;
      const port = `HDMI ${index}`;
      const value = typeof labels?.[key] === 'string' ? labels[key].trim().slice(0, 80) : '';
      samsungLabels[key] = value ? `${port} (${value})` : port;
      const button = samsungButtons.find(candidate => candidate.dataset.samsung === key);
      if (button) { button.textContent = value || port; button.setAttribute('aria-label', `Test ${samsungLabels[key]}`); }
    }
  }
  function clearToast() {
    clearTimeout(toastTimer);
    if (!toastTarget) return;
    toastTarget.classList.remove('is-visible');
    if (toastTarget.id !== 'toast') toastTarget.hidden = true;
    toastTarget.textContent = '';
    toastTarget = null;
  }
  function toast(message) {
    clearToast();
    // Native dialogs live above the document's stacking order. Feedback must
    // be inside the open dialog to remain visible and announced there.
    const open = sheets.find(sheet => sheet.open);
    const element = (open && $(`#${open.id}-notice`)) || $('#toast');
    element.textContent = message;
    if (element.id === 'toast') element.classList.add('is-visible');
    else element.hidden = false;
    toastTarget = element;
    toastTimer = setTimeout(clearToast, 4200);
  }
  function openSheet(sheet) {
    invalidateCommands();
    clearToast();
    for (const other of sheets) if (other !== sheet && other.open) other.close();
    if (!sheet.open) sheet.showModal();
  }
  function openPairing(message = '') {
    $('#pair-error').textContent = message;
    $('#pair-pin').value = '';
    openSheet($('#pair-dialog'));
  }
  async function api(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeoutMs || 8000);
    const headers = { Accept: 'application/json' };
    const requestToken = token;
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (options.auth !== false && token) headers.Authorization = `Bearer ${token}`;
    try {
      const response = await fetch(path, {
        method: options.method || 'GET', headers, cache: 'no-store', credentials: 'same-origin', signal: controller.signal,
        ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
      });
      serverAvailable = true;
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = new Error(ERROR_MESSAGES[data.error] || 'The remote could not complete that request.');
        error.status = response.status;
        error.code = data.error;
        if (response.status === 401 && options.auth !== false && token === requestToken) {
          saveToken(''); online = false; samsungPaired = false; invalidateCommands(); renderState();
          openPairing('Your session ended. Enter the code shown on your Mac.');
        }
        throw error;
      }
      return data;
    } catch (error) {
      if (!error.status && options.probe === true) { serverAvailable = false; online = false; invalidateCommands(); renderState(); }
      throw error;
    } finally { clearTimeout(timeout); }
  }
  async function refreshStatus() {
    if (!token || statusRequest || document.hidden) return statusRequest;
    const requestToken = token;
    statusRequest = (async () => {
      try {
        const data = await api('/api/status', { probe: true });
        if (token !== requestToken) return;
        checkedStatus = true;
        samsungEnabled = data.samsung?.enabled === true;
        if (!samsungBusy) samsungPaired = data.samsung?.paired === true;
        applySamsungSourceLabels(data.samsung?.sourceLabels);
        showSamsungLastSent(data.samsung?.lastSent);
        if (typeof data.device?.name === 'string' && data.device.name) $('#device-name').textContent = data.device.name;
        if (typeof data.date?.nepal === 'string' && data.date.nepal) $('#nepal-date').textContent = data.date.nepal;
        const time = data.date?.secondaryTime || data.date?.kathmanduTime;
        if (typeof time === 'string' && time) $('#kathmandu-time').textContent = `${data.date.secondaryLabel || 'Kathmandu'} · ${time}`;
        setConnection(data.online === true);
      } catch { if (token === requestToken) renderState(); }
      finally { statusRequest = null; }
    })();
    return statusRequest;
  }
  async function reconnect() {
    if (!token) { openPairing(); return; }
    if (reconnecting) return;
    reconnecting = true;
    invalidateCommands();
    renderState();
    try {
      if (statusRequest) await statusRequest;
      const data = await api('/api/reconnect', { method: 'POST', body: {}, timeoutMs: 16000, probe: true });
      checkedStatus = true;
      setConnection(data.online === true);
      await refreshStatus();
      toast(online ? 'Fire TV reconnected.' : 'Fire TV is still offline. Check its power and Wi-Fi.');
    } catch (error) {
      if (error.status !== 401) toast(error.name === 'AbortError' || !error.status ? 'Cannot reach your Mac. Wake it and check your Wi-Fi.' : error.message);
    } finally { reconnecting = false; renderState(); }
  }
  function queueCommand(path, body, options = {}) {
    if (!online || !token || !serverAvailable || document.hidden || queuedCommands >= 3 || (options.holdRef && queuedCommands > 0)) return Promise.resolve(false);
    const requestToken = token;
    const epoch = commandEpoch;
    const enqueuedAt = performance.now();
    queuedCommands++;
    const task = commandTail.then(async () => {
      if (!online || !serverAvailable || token !== requestToken || commandEpoch !== epoch || document.hidden || performance.now() - enqueuedAt > MAX_QUEUE_AGE_MS || (options.holdRef && hold !== options.holdRef)) return false;
      try {
        await api(path, { method: 'POST', body, timeoutMs: options.text ? 12000 : 8000 });
        if (!options.holdRef && navigator.vibrate && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) navigator.vibrate(8);
        return true;
      } catch (error) {
        endHold();
        if (options.onError) options.onError(error);
        else if (error.status !== 401) toast(error.name === 'AbortError' ? 'The action may have reached the TV. Check before trying again.' : error.status ? error.message : 'The action could not be confirmed. Check the TV before trying again.');
        if (error.code === 'tv_unavailable') setConnection(false);
        return false;
      }
    }).finally(() => { queuedCommands--; });
    commandTail = task.catch(() => false);
    return task;
  }
  function sendKey(key, options) { return queueCommand('/api/key', { key }, options); }
  function beginHold(button, key, pointerId) {
    endHold();
    if (!online || !serverAvailable || button.disabled) return;
    button.classList.add('is-pressed');
    const current = { button, key, pointerId, count: 0, timer: null };
    hold = current;
    // A press is an intentional tap; only unsent repeats expire on release.
    sendKey(key);
    function repeat() {
      if (hold !== current || !online || !serverAvailable || document.hidden) return;
      if (++current.count > 12) { endHold(); return; }
      sendKey(key, { holdRef: current });
      current.timer = setTimeout(repeat, 200);
    }
    current.timer = setTimeout(repeat, 420);
  }
  function endHold() {
    if (!hold) return;
    clearTimeout(hold.timer);
    hold.button.classList.remove('is-pressed');
    hold = null;
  }
  function invalidateCommands() { commandEpoch++; endHold(); }
  for (const button of keyButtons) {
    const key = button.dataset.key;
    if (button.hasAttribute('data-repeat')) {
      button.addEventListener('pointerdown', event => {
        if (event.button !== 0 || button.disabled) return;
        event.preventDefault();
        button.setPointerCapture(event.pointerId);
        beginHold(button, key, event.pointerId);
      });
      for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) button.addEventListener(type, event => { if (hold?.pointerId === event.pointerId) endHold(); });
      button.addEventListener('click', event => { if (event.detail === 0) sendKey(key); });
      button.addEventListener('contextmenu', event => event.preventDefault());
    } else button.addEventListener('click', () => {
      sendKey(key).then(sent => {
        if (sent && key === 'wake') toast('Wake sent to Fire TV.');
        if (sent && key === 'sleep') toast('Sleep sent to Fire TV.');
      });
    });
  }
  for (const button of appButtons) button.addEventListener('click', () => {
    button.classList.add('is-pressed');
    setTimeout(() => button.classList.remove('is-pressed'), 200);
    queueCommand('/api/app', { app: button.dataset.app });
  });
  async function sendSamsung(key) {
    if (!token || !serverAvailable || !samsungPaired || samsungBusy) return;
    samsungBusy = true; renderState();
    try {
      const data = await api('/api/samsung/key', { method: 'POST', body: { key }, timeoutMs: 12000 });
      if (data.state === 'sent') showSamsungLastSent({ key, sentAt: data.sentAt });
    } catch (error) {
      if (error.code === 'samsung_pair_required' || error.code === 'samsung_authorization_denied') samsungPaired = false;
      if (error.status !== 401) toast(error.name === 'AbortError' ? 'Samsung took too long. Check the TV before trying again.' : error.status ? error.message : 'The action could not be confirmed. Check the TV before trying again.');
    } finally { samsungBusy = false; renderState(); }
  }
  for (const button of samsungButtons) button.addEventListener('click', () => sendSamsung(button.dataset.samsung));
  $('#samsung-pair').addEventListener('click', async () => {
    if (!token || !serverAvailable || samsungBusy) return;
    samsungBusy = true; samsungMessage = 'Select Allow for “Local TV Remote” on your Samsung.'; renderState();
    try {
      const data = await api('/api/samsung/pair', { method: 'POST', body: {}, timeoutMs: 38000 });
      samsungPaired = data.state === 'authorized';
      samsungMessage = samsungPaired ? 'Paired. Samsung controls are available independently of Fire TV.' : 'Approval timed out. Connect again while you are beside the TV.';
    } catch (error) {
      samsungMessage = error.name === 'AbortError' ? 'Samsung took too long. Try connecting again.' : error.message;
    } finally { samsungBusy = false; renderState(); }
  });
  $('#sources-button').addEventListener('click', () => {
    if (samsungPaired) openSheet($('#sources-dialog'));
    else { openSheet($('#settings-dialog')); $('#samsung-setup').scrollIntoView({ block: 'nearest' }); }
  });
  function updateTextButton() {
    const value = $('#tv-text').value;
    $('#tv-text').disabled = textSending;
    $('#text-count').textContent = `${value.length} / 160`;
    $('#text-submit').disabled = textSending || !online || !token || !serverAvailable || value.length === 0 || value.length > 160 || /[^\x20-\x7E]/.test(value);
    $('#text-submit').firstChild.textContent = textSending ? 'Sending…' : 'Send text';
  }
  $('#type-button').addEventListener('click', () => { textIntent++; textSending = false; $('#tv-text').value = ''; $('#text-error').textContent = ''; updateTextButton(); openSheet($('#type-dialog')); $('#tv-text').focus(); });
  $('#tv-text').addEventListener('input', () => {
    $('#text-error').textContent = /[^\x20-\x7E]/.test($('#tv-text').value) ? 'Use English letters, numbers and standard symbols. Line breaks are not supported.' : '';
    updateTextButton();
  });
  $('#text-form').addEventListener('submit', async event => {
    event.preventDefault();
    if ($('#text-submit').disabled) return;
    const intent = ++textIntent;
    const submittedText = $('#tv-text').value;
    textSending = true; $('#text-error').textContent = ''; updateTextButton();
    let reportedError = false;
    const sent = await queueCommand('/api/text', { text: submittedText }, {
      text: true,
      onError(error) {
        if (intent !== textIntent || !$('#type-dialog').open) return;
        reportedError = true;
        $('#text-error').textContent = error.code === 'text_send_uncertain' || !error.status ? 'Text may have reached the TV. Check the TV before trying again.' : error.message;
      },
    });
    if (intent !== textIntent || !$('#type-dialog').open) return;
    if (sent) { $('#tv-text').value = ''; toast('Text sent to TV.'); }
    else if (!reportedError) $('#text-error').textContent = 'Text was not sent. Wait a moment, then try again.';
    textSending = false; updateTextButton();
  });
  $('#type-dialog').addEventListener('close', () => {
    // A queued close event from an earlier opening must not clear a new draft.
    if ($('#type-dialog').open) return;
    textIntent++; textSending = false; $('#tv-text').value = ''; $('#text-error').textContent = ''; updateTextButton();
  });
  for (const button of document.querySelectorAll('[data-close]')) button.addEventListener('click', () => button.closest('dialog').close());
  for (const sheet of sheets) {
    sheet.addEventListener('close', invalidateCommands);
    sheet.addEventListener('close', () => {
      if (!sheet.open && toastTarget?.closest('dialog') === sheet) clearToast();
    });
    sheet.addEventListener('click', event => {
      if (event.target !== sheet) return;
      const rect = sheet.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) sheet.close();
    });
  }
  $('#settings-button').addEventListener('click', () => openSheet($('#settings-dialog')));
  $('#connection-button').addEventListener('click', () => token ? openSheet($('#settings-dialog')) : openPairing());
  $('#reconnect-button').addEventListener('click', reconnect);
  $('#settings-reconnect').addEventListener('click', reconnect);
  $('#pairing-button').addEventListener('click', () => openPairing());
  $('#pair-pin').addEventListener('input', () => { $('#pair-pin').value = $('#pair-pin').value.replace(/[^0-9]/g, '').slice(0, 6); $('#pair-error').textContent = ''; });
  $('#pair-form').addEventListener('submit', async event => {
    event.preventDefault();
    if ($('#pair-submit').disabled || !/^[0-9]{6}$/.test($('#pair-pin').value)) return;
    $('#pair-submit').disabled = true; $('#pair-submit').firstChild.textContent = 'Connecting…'; $('#pair-error').textContent = '';
    try {
      const data = await api('/api/pair', { method: 'POST', body: { pin: $('#pair-pin').value }, auth: false });
      if (typeof data.token !== 'string' || !data.token) throw new Error('The Mac did not return a pairing session. Try again.');
      saveToken(data.token); checkedStatus = false; $('#pair-pin').value = ''; $('#pair-dialog').close(); renderState(); await refreshStatus();
    } catch (error) {
      $('#pair-error').textContent = error.status === 401 || error.status === 403 ? 'That code did not match. Check the code on your Mac.' : error.name === 'AbortError' || !error.status ? 'Cannot reach your Mac. Wake it and check your Wi-Fi.' : error.message;
    } finally { $('#pair-submit').disabled = false; $('#pair-submit').firstChild.textContent = 'Connect remote'; }
  });
  // Device samples are requested only while their sheet is open and visible.
  let deviceIntent = 0;
  let deviceTimer = null;
  let deviceRequest = null;
  let deviceSample = null;
  let weatherIntent = 0;
  let weatherRequest = null;
  let weatherSample = null;
  let wallpaperChoice = 'auto';
  let wallpaperBusy = false;
  let wallpaperIntent = 0;
  const wallpaperModes = ['auto', 'morning', 'dusk', 'night'];
  const wallpaperButtons = [...document.querySelectorAll('[data-wallpaper]')];

  function featureReady() { return Boolean(token && serverAvailable); }
  function renderToolsState() {
    for (const id of ['device-button', 'weather-button', 'wallpaper-button']) $(`#${id}`).disabled = !featureReady();
    $('#wallpaper-apply').disabled = !featureReady() || !online || wallpaperBusy;
    for (const button of wallpaperButtons) button.disabled = wallpaperBusy;
    $('#wallpaper-apply').firstChild.textContent = wallpaperBusy ? 'Applying…' : 'Apply wallpaper';
  }
  function formatBytes(value) {
    if (!Number.isFinite(value) || value < 0) return '—';
    const unit = value >= 1024 ** 3 ? 1024 ** 3 : 1024 ** 2;
    return `${(value / unit).toLocaleString([], { maximumFractionDigits: 1 })} ${unit === 1024 ** 3 ? 'GiB' : 'MiB'}`;
  }
  function ageLabel(age) {
    if (!Number.isFinite(age) || age < 0) return 'Update time unavailable';
    if (age < 60) return 'Updated just now';
    if (age < 3600) return `Updated ${Math.floor(age / 60)} min ago`;
    if (age < 86400) return `Updated ${Math.floor(age / 3600)} hr ago`;
    return `Updated ${Math.floor(age / 86400)} days ago`;
  }
  function renderMetric(name, metric) {
    const valid = metric && ['totalBytes', 'availableBytes', 'usedBytes', 'usedPercent'].every(key => Number.isFinite(metric[key])) && metric.totalBytes > 0 && metric.usedPercent >= 0 && metric.usedPercent <= 100;
    $(`#${name}-percent`).textContent = valid ? `${Math.round(metric.usedPercent)}% used` : 'Unavailable';
    $(`#${name}-used`).textContent = valid ? `${formatBytes(metric.usedBytes)} / ${formatBytes(metric.totalBytes)}` : '—';
    $(`#${name}-available`).textContent = valid ? `${formatBytes(metric.availableBytes)} available` : 'No sample available';
    $(`#${name}-progress`).hidden = !valid;
    if (valid) $(`#${name}-progress`).value = metric.usedPercent;
  }
  function renderDevice(data, failed = false) {
    const haveSample = Boolean(data?.sampledAt && (data?.memory || data?.storage));
    renderMetric('memory', data?.memory);
    renderMetric('storage', data?.storage);
    $('#device-state').textContent = failed ? (haveSample ? 'Cannot update · saved snapshot' : 'Device stats are unavailable. Try again.')
      : !data ? 'Checking Fire TV…' : !data.online ? (haveSample ? 'Fire TV offline · saved snapshot' : 'Fire TV is offline') : !haveSample ? 'Device readings are unavailable' : data.stale ? 'Saved snapshot · update delayed' : 'Live snapshot';
    $('#device-updated').textContent = haveSample ? `${data.stale || failed || !data.online ? 'Saved sample · ' : ''}${ageLabel(data.ageSeconds)}` : 'No sample yet';
  }
  function stopDevicePolling() {
    deviceIntent++;
    clearTimeout(deviceTimer);
    deviceTimer = null;
    deviceRequest = null;
    $('#device-refresh').disabled = false;
  }
  async function refreshDevice() {
    if (!token || document.hidden || !$('#device-dialog').open || deviceRequest !== null) return;
    const intent = deviceIntent;
    const requestToken = token;
    deviceRequest = intent;
    $('#device-refresh').disabled = true;
    try {
      const data = await api('/api/device', { timeoutMs: 12000 });
      if (intent !== deviceIntent || token !== requestToken || document.hidden || !$('#device-dialog').open) return;
      deviceSample = data;
      renderDevice(data);
    } catch (error) {
      if (intent !== deviceIntent || token !== requestToken || document.hidden || !$('#device-dialog').open) return;
      if (error.status !== 401) renderDevice(deviceSample, true);
    } finally {
      if (intent === deviceIntent) {
        deviceRequest = null;
        $('#device-refresh').disabled = false;
        if ($('#device-dialog').open && !document.hidden && token) deviceTimer = setTimeout(refreshDevice, 10000);
      }
    }
  }
  $('#device-button').addEventListener('click', () => {
    stopDevicePolling();
    deviceSample = null;
    renderDevice(null);
    openSheet($('#device-dialog'));
    refreshDevice();
  });
  $('#device-refresh').addEventListener('click', () => { clearTimeout(deviceTimer); deviceTimer = null; refreshDevice(); });
  $('#device-dialog').addEventListener('close', () => { if (!$('#device-dialog').open) stopDevicePolling(); });

  function weatherDescription(code) {
    if (code === 0) return 'Clear';
    if (code === 1) return 'Mostly clear';
    if (code === 2) return 'Partly cloudy';
    if (code === 3) return 'Cloudy';
    if ([45, 48].includes(code)) return 'Fog';
    if ([51, 53, 55].includes(code)) return 'Drizzle';
    if ([56, 57].includes(code)) return 'Freezing drizzle';
    if ([61, 63, 65].includes(code)) return 'Rain';
    if ([66, 67].includes(code)) return 'Freezing rain';
    if ([71, 73, 75, 77].includes(code)) return 'Snow';
    if ([80, 81, 82].includes(code)) return 'Showers';
    if ([85, 86].includes(code)) return 'Snow showers';
    if ([95, 96, 99].includes(code)) return 'Thunderstorms';
    return 'Forecast';
  }
  function temperatureLabel(value, unit = '') { return Number.isFinite(value) ? `${Math.round(value)}°${unit}` : '—'; }
  function rainLabel(value) { return Number.isFinite(value) && value >= 0 && value <= 100 ? `${Math.round(value)}%` : '—'; }
  function renderWeather(data, failed = false) {
    const state = data?.state;
    const enabled = data?.enabled !== false && state !== 'disabled';
    const daily = Array.isArray(data?.daily) ? data.daily.slice(0, 7) : [];
    const current = data?.current;
    const haveForecast = enabled && (current || daily.length > 0);
    $('#weather-state').textContent = failed ? (haveForecast ? 'Cannot update · saved forecast' : 'Weather is unavailable. Try again.')
      : !data ? 'Loading forecast…' : !enabled ? 'Weather is not configured on your Mac.'
      : state === 'unavailable' ? 'Weather is unavailable. Try again.' : state === 'stale' ? 'Saved forecast · update delayed' : 'Current conditions';
    $('#weather-current').hidden = !haveForecast;
    $('#weather-daily').hidden = !enabled || daily.length === 0;
    $('#weather-location').textContent = typeof data?.location === 'string' ? data.location : 'Local weather';
    const unit = data?.temperatureUnit === 'C' ? 'C' : data?.temperatureUnit === 'F' ? 'F' : '';
    $('#weather-units').textContent = unit ? `°${unit}` : '';
    $('#weather-temperature').textContent = temperatureLabel(current?.temperature, unit);
    $('#weather-condition').textContent = current ? weatherDescription(current.weatherCode) : 'Current conditions unavailable';
    const localDate = typeof data?.localDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(data.localDate) ? data.localDate : null;
    const today = localDate ? daily.find(day => day.date === localDate) : null;
    const summary = today || daily[0];
    const summaryDate = typeof summary?.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(summary.date) ? new Date(`${summary.date}T12:00:00Z`) : null;
    $('#weather-summary-label').textContent = today ? 'Today' : summaryDate && Number.isFinite(summaryDate.getTime()) ? `Forecast · ${summaryDate.toLocaleDateString([], { month: 'short', day: 'numeric', timeZone: 'UTC' })}` : 'Forecast';
    $('#weather-today-high').textContent = temperatureLabel(summary?.high);
    $('#weather-today-low').textContent = temperatureLabel(summary?.low);
    $('#weather-today-rain').textContent = `Rain ${rainLabel(summary?.precipitationProbability)}`;
    for (let index = 0; index < 7; index++) {
      const day = daily[index];
      $(`#weather-day-${index}`).hidden = !enabled || !day;
      if (!day) continue;
      const date = typeof day.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day.date) ? new Date(`${day.date}T12:00:00Z`) : null;
      $(`#weather-day-${index}-name`).textContent = localDate && day.date === localDate ? 'Today' : date && Number.isFinite(date.getTime()) ? date.toLocaleDateString([], { weekday: 'short', timeZone: 'UTC' }) : `Day ${index + 1}`;
      $(`#weather-day-${index}-condition`).textContent = weatherDescription(day.weatherCode);
      $(`#weather-day-${index}-rain`).textContent = rainLabel(day.precipitationProbability);
      $(`#weather-day-${index}-rain`).setAttribute('aria-label', `Chance of rain ${rainLabel(day.precipitationProbability)}`);
      $(`#weather-day-${index}-high`).textContent = temperatureLabel(day.high);
      $(`#weather-day-${index}-low`).textContent = temperatureLabel(day.low);
    }
    $('#weather-updated').textContent = data?.sampledAt ? `${state === 'stale' || failed ? 'Saved forecast · ' : ''}${ageLabel(data.ageSeconds)}` : !enabled ? 'Set a weather location in the Mac configuration.' : 'No forecast yet';
  }
  async function refreshWeather() {
    if (!token || document.hidden || !$('#weather-dialog').open || weatherRequest !== null) return;
    const intent = weatherIntent;
    const requestToken = token;
    weatherRequest = intent;
    $('#weather-refresh').disabled = true;
    try {
      const data = await api('/api/weather', { timeoutMs: 12000 });
      if (intent !== weatherIntent || token !== requestToken || !$('#weather-dialog').open || document.hidden) return;
      weatherSample = data;
      renderWeather(data);
    } catch (error) {
      if (intent !== weatherIntent || token !== requestToken || !$('#weather-dialog').open || document.hidden) return;
      if (error.status !== 401) renderWeather(weatherSample, true);
    } finally {
      if (intent === weatherIntent) { weatherRequest = null; $('#weather-refresh').disabled = false; }
    }
  }
  $('#weather-button').addEventListener('click', () => {
    weatherIntent++;
    weatherRequest = null;
    weatherSample = null;
    renderWeather(null);
    openSheet($('#weather-dialog'));
    refreshWeather();
  });
  $('#weather-refresh').addEventListener('click', refreshWeather);
  $('#weather-dialog').addEventListener('close', () => { if (!$('#weather-dialog').open) { weatherIntent++; weatherRequest = null; $('#weather-refresh').disabled = false; } });

  function selectWallpaper(mode) {
    if (!wallpaperModes.includes(mode) || wallpaperBusy) return;
    wallpaperChoice = mode;
    for (const button of wallpaperButtons) button.setAttribute('aria-pressed', String(button.dataset.wallpaper === mode));
    $('#wallpaper-state').textContent = `Choice: ${mode[0].toUpperCase()}${mode.slice(1)}`;
  }
  for (const button of wallpaperButtons) button.addEventListener('click', () => selectWallpaper(button.dataset.wallpaper));
  $('#wallpaper-button').addEventListener('click', () => { wallpaperIntent++; openSheet($('#wallpaper-dialog')); selectWallpaper(wallpaperChoice); renderToolsState(); });
  $('#wallpaper-dialog').addEventListener('close', () => { if (!$('#wallpaper-dialog').open) wallpaperIntent++; });
  $('#wallpaper-apply').addEventListener('click', async () => {
    if (wallpaperBusy || !featureReady() || !online || !wallpaperModes.includes(wallpaperChoice)) return;
    const intent = wallpaperIntent;
    const mode = wallpaperChoice;
    wallpaperBusy = true;
    renderToolsState();
    let reported = false;
    const sent = await queueCommand('/api/wallpaper', { mode }, { onError(error) {
      reported = true;
      if (intent !== wallpaperIntent || !$('#wallpaper-dialog').open) return;
      toast(!error.status ? 'Wallpaper change could not be confirmed. Check Home before trying again.' : error.message);
    } });
    wallpaperBusy = false;
    renderToolsState();
    if (intent !== wallpaperIntent || !$('#wallpaper-dialog').open) return;
    if (sent) toast(`${mode[0].toUpperCase()}${mode.slice(1)} applied. Changes appear on Home.`);
    else if (!reported) toast('Wallpaper was not sent. Reconnect Fire TV and try again.');
  });
  document.addEventListener('visibilitychange', () => {
    stopDevicePolling();
    if (!document.hidden && $('#device-dialog').open) refreshDevice();
    if (document.hidden && $('#weather-dialog').open) { weatherIntent++; weatherRequest = null; $('#weather-refresh').disabled = false; }
    else if (!document.hidden && $('#weather-dialog').open) refreshWeather();
  });
  window.addEventListener('pagehide', stopDevicePolling);

  const keyboardKeys = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    Enter: 'ok', Escape: 'back', Backspace: 'back', ' ': 'play_pause',
    Home: 'home', h: 'home', H: 'home', m: 'menu', M: 'menu', ContextMenu: 'menu',
    r: 'rewind', R: 'rewind', f: 'fast_forward', F: 'fast_forward',
  };
  const repeatKeys = new Set(['up', 'down', 'left', 'right', 'rewind', 'fast_forward']);
  document.addEventListener('keydown', event => {
    if (event.ctrlKey || event.altKey || event.metaKey || event.isComposing) return;
    const target = event.target;
    if (target instanceof HTMLElement && (target.isContentEditable || target.closest('input,textarea,select,a,summary,[role="textbox"]'))) return;
    if (sheets.some(sheet => sheet.open)) {
      // Escape closes the sheet natively. Only arrows focused on its TV pad
      // control Samsung; the close button and other controls keep native keys.
      if ($('#sources-dialog').open && target instanceof HTMLElement && target.closest('.samsung-pad') && !event.repeat) {
        const key = keyboardKeys[event.key];
        if (['up', 'down', 'left', 'right'].includes(key)) { event.preventDefault(); sendSamsung(key); }
      }
      return;
    }
    if (target instanceof HTMLButtonElement && ['Enter', ' '].includes(event.key)) return;
    const key = keyboardKeys[event.key];
    if (!key || !online || !serverAvailable) return;
    event.preventDefault();
    if (event.repeat) return;
    const button = keyButtons.find(candidate => candidate.dataset.key === key);
    if (repeatKeys.has(key) && button) beginHold(button, key, 'keyboard');
    else sendKey(key);
  });
  document.addEventListener('keyup', event => { if (hold?.pointerId === 'keyboard' && keyboardKeys[event.key] === hold.key) endHold(); });
  window.addEventListener('blur', invalidateCommands);
  window.addEventListener('pagehide', invalidateCommands);
  document.addEventListener('visibilitychange', () => { if (document.hidden) invalidateCommands(); else refreshStatus(); });
  window.addEventListener('online', refreshStatus);
  window.addEventListener('offline', () => { serverAvailable = false; setConnection(false); invalidateCommands(); });
  renderState();
  if (token) refreshStatus();
  setInterval(refreshStatus, POLL_MS);
})();
