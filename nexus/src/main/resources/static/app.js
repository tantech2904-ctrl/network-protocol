(function () {
  'use strict';

  const AVATAR_COLORS = [
    '#007AFF',
    '#AF52DE',
    '#34C759',
    '#FF9500',
    '#FF2D55',
    '#5856D6',
    '#00C7BE'
  ];

  const DEFAULT_NAMES = [
    'Pilot', 'Rover', 'Nova', 'Atlas', 'Echo',
    'Orion', 'Pulse', 'Zephyr', 'Aero', 'Phoenix'
  ];

  let currentUserId = localStorage.getItem('nexus_user_id');
  let currentUserName = localStorage.getItem('nexus_user_name');
  let currentUserColor = localStorage.getItem('nexus_user_color');

  if (!currentUserId) {
    currentUserId = 'user-' + Math.random().toString(16).substring(2, 8);
    localStorage.setItem('nexus_user_id', currentUserId);
  }
  if (!currentUserName) {
    currentUserName = DEFAULT_NAMES[Math.floor(Math.random() * DEFAULT_NAMES.length)];
    localStorage.setItem('nexus_user_name', currentUserName);
  }
  if (!currentUserColor) {
    currentUserColor = AVATAR_COLORS[Math.floor(Math.random() * AVATAR_COLORS.length)];
    localStorage.setItem('nexus_user_color', currentUserColor);
  }

  let modalSelectedColor = currentUserColor;

  const statusDot = document.getElementById('status-dot');
  const statusLabel = document.getElementById('status-label');
  const peerName = document.getElementById('peer-name');
  const peerCountBadge = document.getElementById('peer-count-badge');
  const peerRosterStack = document.getElementById('peer-roster-stack');
  const btnProfile = document.getElementById('btn-profile');
  const headerUserAvatar = document.getElementById('header-user-avatar');
  const profileModal = document.getElementById('profile-modal');
  const modalCloseBtn = document.getElementById('modal-close-btn');
  const modalAvatarPreview = document.getElementById('modal-avatar-preview');
  const modalNamePreview = document.getElementById('modal-name-preview');
  const profileNameInput = document.getElementById('profile-name-input');
  const colorPalette = document.getElementById('color-palette');
  const btnSaveProfile = document.getElementById('btn-save-profile');

  const detailsToggleBtn = document.getElementById('details-toggle-btn');
  const protocolBanner = document.getElementById('protocol-banner');
  const bannerGateway = document.getElementById('banner-gateway');
  const bannerUdpPort = document.getElementById('banner-udp-port');
  const telemetrySummary = document.getElementById('telemetry-summary');
  const telemetryTbody = document.getElementById('telemetry-tbody');
  const btnDemoLoss = document.getElementById('btn-demo-loss');
  const btnDemoFail = document.getElementById('btn-demo-fail');
  const btnDemoImage = document.getElementById('btn-demo-image');
  const btnDemoBob = document.getElementById('btn-demo-bob');
  const btnDemoSensor = document.getElementById('btn-demo-sensor');
  const btnAttachImage = document.getElementById('btn-attach-image');
  const fileInput = document.getElementById('file-input');
  const conversationContainer = document.getElementById('conversation-container');
  const emptyState = document.getElementById('empty-state');
  const messagesList = document.getElementById('messages-list');
  const commandPalette = document.getElementById('command-palette');
  const commandPaletteList = document.getElementById('command-palette-list');
  const composerForm = document.getElementById('composer-form');
  const messageInput = document.getElementById('message-input');
  const sendBtn = document.getElementById('send-btn');

  const messagesMap = new Map();
  let ws = null;
  let reconnectTimer = null;
  let reconnectDelay = 1000;
  let telemetrySent = 0;
  let telemetryRecv = 0;
  let lastLatency = null;

  const COMMANDS = [
    { cmd: '/name ', name: '/name <handle>', desc: 'Change your public display handle across the network' },
    { cmd: '/drop ', name: '/drop <text>', desc: 'Simulate 1 dropped packet (demonstrates ACK timeout & retransmit)' },
    { cmd: '/fail ', name: '/fail <text>', desc: 'Simulate offline peer (demonstrates retry exhaustion to FAILED)' },
    { cmd: '/image ', name: '/image [ttlSeconds]', desc: 'Transfer chunked photo over UDP with ephemeral timer' },
    { cmd: '/peer ', name: '/peer <alice|bob|sensor> <text>', desc: 'Send targeted message to a specific peer node' },
    { cmd: '/sim ', name: '/sim <alice|bob|sensor> [msg]', desc: 'Trigger incoming UDP transmission from simulated peer' },
    { cmd: '/stats', name: '/stats', desc: 'Display active sessions and UDP protocol metrics' },
    { cmd: '/clear', name: '/clear', desc: 'Clear conversation history' },
    { cmd: '/help', name: '/help', desc: 'Show all available protocol showcase commands' }
  ];

  function getInitials(name) {
    if (!name || !name.trim()) return '?';
    const trimmed = name.trim();
    const words = trimmed.split(/\s+/);
    if (words.length >= 2) {
      return (words[0][0] + words[1][0]).toUpperCase();
    }
    return trimmed.substring(0, Math.min(2, trimmed.length)).toUpperCase();
  }

  function init() {
    updateHeaderProfileAvatar();
    setupEventListeners();
    connectWebSocket();
    loadStatusFallback();
  }

  function updateHeaderProfileAvatar() {
    const initials = getInitials(currentUserName);
    if (headerUserAvatar) {
      headerUserAvatar.textContent = initials;
      headerUserAvatar.style.backgroundColor = currentUserColor;
    }
    const rosterSelf = document.getElementById('roster-avatar-self');
    if (rosterSelf) {
      rosterSelf.textContent = initials;
      rosterSelf.style.backgroundColor = currentUserColor;
      rosterSelf.title = currentUserName + ' (You)';
    }
  }

  function setupEventListeners() {
    if (detailsToggleBtn) {
      detailsToggleBtn.addEventListener('click', function () {
        protocolBanner.classList.toggle('hidden');
      });
    }

    if (btnProfile) {
      btnProfile.addEventListener('click', openProfileModal);
    }
    if (modalCloseBtn) {
      modalCloseBtn.addEventListener('click', closeProfileModal);
    }
    if (profileModal) {
      profileModal.addEventListener('click', function (e) {
        if (e.target === profileModal) {
          closeProfileModal();
        }
      });
    }
    if (profileNameInput) {
      profileNameInput.addEventListener('input', updateModalPreview);
    }
    if (btnSaveProfile) {
      btnSaveProfile.addEventListener('click', saveProfile);
    }

    if (btnDemoLoss) {
      btnDemoLoss.addEventListener('click', function () {
        submitCommandDirect('/drop Testing automatic UDP retransmission');
      });
    }

    if (btnDemoFail) {
      btnDemoFail.addEventListener('click', function () {
        submitCommandDirect('/fail Testing peer retry exhaustion');
      });
    }

    if (btnDemoImage) {
      btnDemoImage.addEventListener('click', function () {
        submitCommandDirect('/image 15');
      });
    }

    if (btnDemoBob) {
      btnDemoBob.addEventListener('click', function () {
        submitCommandDirect('/sim bob Urgent field message from Bob');
      });
    }

    if (btnDemoSensor) {
      btnDemoSensor.addEventListener('click', function () {
        submitCommandDirect('/sim sensor Atmospheric reading recorded');
      });
    }

    if (btnAttachImage && fileInput) {
      btnAttachImage.addEventListener('click', function () {
        fileInput.click();
      });

      fileInput.addEventListener('change', function () {
        if (fileInput.files && fileInput.files[0]) {
          submitCommandDirect('/image 20');
          fileInput.value = '';
        }
      });
    }

    messageInput.addEventListener('input', function () {
      autoResizeTextarea();
      updateSendButtonState();
      handleCommandAutocomplete();
    });

    messageInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!commandPalette.classList.contains('hidden') && commandPaletteList.firstChild) {
          const selected = commandPaletteList.querySelector('.selected') || commandPaletteList.firstChild;
          if (selected && selected.dataset.cmd) {
            selectCommand(selected.dataset.cmd);
            return;
          }
        }
        if (!sendBtn.disabled) {
          composerForm.dispatchEvent(new Event('submit'));
        }
      } else if (e.key === 'Escape') {
        hideCommandPalette();
      }
    });

    document.addEventListener('click', function (e) {
      if (!commandPalette.contains(e.target) && e.target !== messageInput) {
        hideCommandPalette();
      }
    });

    composerForm.addEventListener('submit', function (e) {
      e.preventDefault();
      hideCommandPalette();
      sendMessage();
    });
  }

  function openProfileModal() {
    modalSelectedColor = currentUserColor;
    if (profileNameInput) {
      profileNameInput.value = currentUserName;
    }
    updateModalPreview();
    renderColorSwatches();
    if (profileModal) {
      profileModal.classList.remove('hidden');
      if (profileNameInput) {
        profileNameInput.focus();
      }
    }
  }

  function closeProfileModal() {
    if (profileModal) {
      profileModal.classList.add('hidden');
    }
  }

  function updateModalPreview() {
    const name = (profileNameInput && profileNameInput.value.trim()) || 'Anonymous';
    if (modalAvatarPreview) {
      modalAvatarPreview.textContent = getInitials(name);
      modalAvatarPreview.style.backgroundColor = modalSelectedColor;
    }
    if (modalNamePreview) {
      modalNamePreview.textContent = name;
    }
  }

  function renderColorSwatches() {
    if (!colorPalette) return;
    colorPalette.innerHTML = '';
    AVATAR_COLORS.forEach(function (color) {
      const swatch = document.createElement('button');
      swatch.type = 'button';
      swatch.className = 'color-swatch' + (color === modalSelectedColor ? ' active' : '');
      swatch.style.backgroundColor = color;
      swatch.setAttribute('aria-label', 'Color ' + color);
      swatch.addEventListener('click', function () {
        modalSelectedColor = color;
        renderColorSwatches();
        updateModalPreview();
      });
      colorPalette.appendChild(swatch);
    });
  }

  function saveProfile() {
    const newName = profileNameInput ? profileNameInput.value.trim() : '';
    if (newName) {
      currentUserName = newName;
      localStorage.setItem('nexus_user_name', currentUserName);
    }
    currentUserColor = modalSelectedColor;
    localStorage.setItem('nexus_user_color', currentUserColor);

    updateHeaderProfileAvatar();
    sendProfileUpdate();
    closeProfileModal();
  }

  function sendProfileUpdate() {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        action: 'SET_PROFILE',
        senderId: currentUserId,
        name: currentUserName,
        color: currentUserColor
      }));
    }
  }

  function updatePeerRoster(peers) {
    if (!peerRosterStack) return;
    peerRosterStack.innerHTML = '';

    const selfDiv = document.createElement('div');
    selfDiv.className = 'roster-avatar';
    selfDiv.id = 'roster-avatar-self';
    selfDiv.style.backgroundColor = currentUserColor;
    selfDiv.textContent = getInitials(currentUserName);
    selfDiv.title = currentUserName + ' (You)';
    peerRosterStack.appendChild(selfDiv);

    let count = 1;
    if (Array.isArray(peers)) {
      peers.forEach(function (p) {
        if (p.id && p.id !== currentUserId) {
          count++;
          const pDiv = document.createElement('div');
          pDiv.className = 'roster-avatar';
          pDiv.style.backgroundColor = p.color || '#8E8E93';
          pDiv.textContent = getInitials(p.name);
          pDiv.title = (p.name || 'Peer') + ' (Connected)';
          peerRosterStack.appendChild(pDiv);
        }
      });
    }

    if (peerCountBadge) {
      peerCountBadge.textContent = count === 1 ? '1 online' : count + ' online';
    }
  }

  function handleCommandAutocomplete() {
    const val = messageInput.value;
    if (val.startsWith('/')) {
      const query = val.toLowerCase();
      const matched = COMMANDS.filter(function (c) {
        return c.cmd.toLowerCase().startsWith(query) || c.name.toLowerCase().includes(query);
      });

      if (matched.length > 0) {
        renderCommandPalette(matched);
      } else {
        hideCommandPalette();
      }
    } else {
      hideCommandPalette();
    }
  }

  function renderCommandPalette(commands) {
    commandPaletteList.innerHTML = '';
    commands.forEach(function (c, idx) {
      const item = document.createElement('div');
      item.className = 'command-item' + (idx === 0 ? ' selected' : '');
      item.dataset.cmd = c.cmd;
      item.innerHTML = '<span class="command-name">' + c.name + '</span><span class="command-desc">' + c.desc + '</span>';
      item.addEventListener('click', function () {
        selectCommand(c.cmd);
      });
      commandPaletteList.appendChild(item);
    });
    commandPalette.classList.remove('hidden');
  }

  function hideCommandPalette() {
    commandPalette.classList.add('hidden');
  }

  function selectCommand(cmd) {
    messageInput.value = cmd;
    hideCommandPalette();
    messageInput.focus();
    autoResizeTextarea();
    updateSendButtonState();
  }

  function submitCommandDirect(cmdText) {
    messageInput.value = cmdText;
    sendMessage();
  }

  function autoResizeTextarea() {
    messageInput.style.height = 'auto';
    messageInput.style.height = Math.min(messageInput.scrollHeight, 100) + 'px';
  }

  function updateSendButtonState() {
    const hasText = messageInput.value.trim().length > 0;
    const isConnected = ws && ws.readyState === WebSocket.OPEN;
    sendBtn.disabled = !hasText || !isConnected;
  }

  function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = protocol + '//' + window.location.host + '/ws';

    try {
      ws = new WebSocket(wsUrl);
    } catch (e) {
      scheduleReconnect();
      return;
    }

    ws.onopen = function () {
      reconnectDelay = 1000;
      updateConnectionStatus('connected', 'Gateway Connected • NEXUS UDP Ready');
      updateSendButtonState();
      sendProfileUpdate();
    };

    ws.onmessage = function (event) {
      try {
        const data = JSON.parse(event.data);
        handleSocketMessage(data);
      } catch (err) {
        console.error('Invalid WebSocket JSON:', err);
      }
    };

    ws.onclose = function () {
      updateConnectionStatus('disconnected', 'Disconnected • Reconnecting...');
      updateSendButtonState();
      scheduleReconnect();
    };

    ws.onerror = function () {
      ws.close();
    };
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function () {
      connectWebSocket();
    }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 1.5, 8000);
  }

  function updateConnectionStatus(state, label) {
    statusDot.className = 'status-dot status-' + state;
    statusLabel.textContent = label;
  }

  function handleSocketMessage(data) {
    if (!data || !data.type) return;

    switch (data.type) {
      case 'HISTORY':
        if (Array.isArray(data.messages)) {
          data.messages.forEach(function (msg) {
            renderOrUpdateMessage(msg);
          });
        }
        break;

      case 'MESSAGE_ACCEPTED':
      case 'PEER_MESSAGE':
        if (data.message) {
          renderOrUpdateMessage(data.message);
        }
        break;

      case 'STATUS_UPDATED':
        if (data.messageId && data.status) {
          updateMessageStatus(data.messageId, data.status, data.retryCount);
        }
        break;

      case 'ROSTER_UPDATED':
        if (Array.isArray(data.peers)) {
          updatePeerRoster(data.peers);
        }
        break;

      case 'TELEMETRY':
        if (data.telemetry) {
          handleTelemetry(data.telemetry);
        }
        break;

      case 'CLEAR_HISTORY':
        messagesList.innerHTML = '';
        messagesMap.clear();
        if (emptyState) {
          emptyState.style.display = 'flex';
        }
        break;

      case 'SYSTEM_STATUS':
        updateSystemInfo(data);
        break;
    }
  }

  function updateSystemInfo(info) {
    if (info.localUdpPort && bannerUdpPort) {
      bannerUdpPort.textContent = info.localUdpPort;
    }
    if (bannerGateway) {
      bannerGateway.textContent = 'HTTP & WS (Port ' + window.location.port + ')';
    }
  }

  function handleTelemetry(t) {
    if (t.direction === 'OUT') {
      telemetrySent++;
    } else {
      telemetryRecv++;
    }
    if (t.latencyMs > 0) {
      lastLatency = t.latencyMs;
    }

    if (telemetrySummary) {
      telemetrySummary.textContent = 'Sent: ' + telemetrySent + ' | Recv: ' + telemetryRecv + ' | RTT: ' + (lastLatency !== null ? lastLatency + 'ms' : '--');
    }

    if (!telemetryTbody) return;

    const emptyRow = telemetryTbody.querySelector('.telemetry-empty');
    if (emptyRow) {
      emptyRow.remove();
    }

    const row = document.createElement('tr');
    const timeStr = formatTimeShort(t.timestamp);

    row.innerHTML = '<td>' + timeStr + '</td>'
      + '<td class="' + (t.direction === 'OUT' ? 'telemetry-dir-out' : 'telemetry-dir-in') + '">' + t.direction + '</td>'
      + '<td>' + t.type + '</td>'
      + '<td>' + t.seq + '</td>'
      + '<td>' + t.ack + '</td>'
      + '<td class="' + (t.flags === 'RETRANSMIT' ? 'telemetry-flag-retransmit' : '') + '">' + t.flags + '</td>'
      + '<td>' + t.checksum + '</td>'
      + '<td>' + t.length + 'B</td>';

    telemetryTbody.insertBefore(row, telemetryTbody.firstChild);

    while (telemetryTbody.children.length > 30) {
      telemetryTbody.removeChild(telemetryTbody.lastChild);
    }
  }

  function loadStatusFallback() {
    fetch('/api/status')
      .then(function (res) { return res.json(); })
      .then(function (data) {
        if (data.port && bannerGateway) {
          bannerGateway.textContent = 'HTTP & WS (Port ' + data.port + ')';
        }
      })
      .catch(function () {});
  }

  function sendMessage() {
    const text = messageInput.value.trim();
    if (!text || !ws || ws.readyState !== WebSocket.OPEN) {
      return;
    }

    if (text.startsWith('/name ')) {
      const newName = text.substring(6).trim();
      if (newName) {
        currentUserName = newName;
        localStorage.setItem('nexus_user_name', currentUserName);
        updateHeaderProfileAvatar();
        sendProfileUpdate();
      }
      messageInput.value = '';
      autoResizeTextarea();
      updateSendButtonState();
      return;
    }

    const payload = JSON.stringify({
      action: 'SEND_MESSAGE',
      senderId: currentUserId,
      senderName: currentUserName,
      senderColor: currentUserColor,
      content: text
    });

    ws.send(payload);

    messageInput.value = '';
    autoResizeTextarea();
    updateSendButtonState();
    messageInput.focus();
  }

  function renderOrUpdateMessage(msg) {
    if (!msg || !msg.id) return;

    if (emptyState) {
      emptyState.style.display = 'none';
    }

    if (messagesMap.has(msg.id)) {
      updateMessageStatus(msg.id, msg.status, msg.retryCount);
      return;
    }

    const group = document.createElement('div');
    group.id = 'msg-' + msg.id;

    const isSelf = (msg.senderId && msg.senderId === currentUserId) || (msg.sender === 'user' && (!msg.senderId || msg.senderId === 'local-user'));

    if (msg.messageType === 'SYSTEM') {
      group.className = 'message-group system-group';
      const bubble = document.createElement('div');
      bubble.className = 'message-bubble';
      bubble.textContent = msg.content;
      group.appendChild(bubble);
    } else if (isSelf) {
      group.className = 'message-group outgoing';

      const bubble = document.createElement('div');
      if (msg.messageType === 'IMAGE' && msg.mediaData) {
        bubble.className = 'message-bubble message-bubble-image';
        renderImageCard(bubble, msg);
      } else {
        bubble.className = 'message-bubble';
        bubble.textContent = msg.content;
      }
      group.appendChild(bubble);

      const meta = document.createElement('div');
      meta.className = 'message-meta';

      const timeSpan = document.createElement('span');
      timeSpan.className = 'message-time';
      timeSpan.textContent = formatTimestamp(msg.timestamp);
      meta.appendChild(timeSpan);

      const statusSpan = document.createElement('span');
      statusSpan.className = 'message-status';
      statusSpan.id = 'status-' + msg.id;
      statusSpan.textContent = formatStatusLabel(msg.status, msg.retryCount);
      meta.appendChild(statusSpan);

      group.appendChild(meta);
    } else {
      group.className = 'message-group incoming';

      const row = document.createElement('div');
      row.className = 'message-incoming-row';

      const avatar = document.createElement('div');
      avatar.className = 'message-sender-avatar';
      avatar.style.backgroundColor = msg.peerColor || '#8E8E93';
      avatar.textContent = getInitials(msg.peerName || 'Peer');
      row.appendChild(avatar);

      const body = document.createElement('div');
      body.className = 'message-incoming-body';

      const peerHeader = document.createElement('div');
      peerHeader.className = 'message-peer-header';
      const peerBadge = document.createElement('span');
      peerBadge.className = 'message-peer-badge';
      peerBadge.textContent = msg.peerName || 'Peer';
      if (msg.peerColor) {
        peerBadge.style.color = msg.peerColor;
      }
      peerHeader.appendChild(peerBadge);
      body.appendChild(peerHeader);

      const bubble = document.createElement('div');
      if (msg.messageType === 'IMAGE' && msg.mediaData) {
        bubble.className = 'message-bubble message-bubble-image';
        renderImageCard(bubble, msg);
      } else {
        bubble.className = 'message-bubble';
        bubble.textContent = msg.content;
      }
      body.appendChild(bubble);

      const meta = document.createElement('div');
      meta.className = 'message-meta';
      const timeSpan = document.createElement('span');
      timeSpan.className = 'message-time';
      timeSpan.textContent = formatTimestamp(msg.timestamp);
      meta.appendChild(timeSpan);
      body.appendChild(meta);

      row.appendChild(body);
      group.appendChild(row);
    }

    messagesList.appendChild(group);
    messagesMap.set(msg.id, msg);
    scrollToBottom();
  }

  function renderImageCard(bubble, msg) {
    const card = document.createElement('div');
    card.className = 'photo-card';

    const img = document.createElement('img');
    img.src = msg.mediaData;
    img.alt = 'NEXUS Transfer';
    card.appendChild(img);

    if (msg.ttlSeconds > 0) {
      const badge = document.createElement('div');
      badge.className = 'photo-ttl-badge';
      let remaining = msg.ttlSeconds;
      badge.textContent = '⏳ ' + remaining + 's';
      card.appendChild(badge);

      const countdown = setInterval(function () {
        remaining--;
        if (remaining <= 0) {
          clearInterval(countdown);
          card.innerHTML = '<div class="photo-expired-notice">[Photo expired and wiped]</div>';
        } else {
          badge.textContent = '⏳ ' + remaining + 's';
        }
      }, 1000);
    }

    bubble.appendChild(card);
  }

  function updateMessageStatus(id, status, retryCount) {
    const statusElem = document.getElementById('status-' + id);
    const msg = messagesMap.get(id);
    if (msg) {
      msg.status = status;
      if (typeof retryCount === 'number') {
        msg.retryCount = retryCount;
      }
    }

    if (statusElem) {
      const retries = msg && msg.retryCount ? msg.retryCount : (retryCount || 0);
      statusElem.textContent = formatStatusLabel(status, retries);
      statusElem.className = 'message-status';

      if (status === 'FAILED') {
        statusElem.classList.add('status-failed');
      } else if (status === 'RETRANSMITTING') {
        statusElem.classList.add('status-retransmitting');
      }
    }
  }

  function formatStatusLabel(status, retryCount) {
    const retries = retryCount || 0;
    switch (status) {
      case 'GATEWAY_ACCEPTED':
        return 'Sending...';
      case 'NEXUS_SUBMITTED':
        return 'Submitting...';
      case 'UDP_SENT':
        return 'UDP Sent';
      case 'RETRANSMITTING':
        return 'Retransmitting (attempt ' + retries + '/4)...';
      case 'PEER_ACKNOWLEDGED':
        return retries > 0
          ? 'Acknowledged (after ' + retries + ' retry)'
          : 'Acknowledged';
      case 'DELIVERED_TO_APP':
        return 'Delivered';
      case 'FAILED':
        return 'Failed (retries exhausted)';
      default:
        return '';
    }
  }

  function formatTimestamp(ts) {
    if (!ts) return '';
    const date = new Date(ts);
    let hours = date.getHours();
    let minutes = date.getMinutes();
    const ampm = hours >= 12 ? 'PM' : 'AM';
    hours = hours % 12;
    hours = hours ? hours : 12;
    minutes = minutes < 10 ? '0' + minutes : minutes;
    return hours + ':' + minutes + ' ' + ampm;
  }

  function formatTimeShort(ts) {
    if (!ts) return '';
    const date = new Date(ts);
    const m = date.getMinutes();
    const s = date.getSeconds();
    const ms = date.getMilliseconds();
    return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s) + '.' + (ms < 100 ? (ms < 10 ? '00' + ms : '0' + ms) : ms);
  }

  function scrollToBottom() {
    conversationContainer.scrollTop = conversationContainer.scrollHeight;
  }

  init();
})();
