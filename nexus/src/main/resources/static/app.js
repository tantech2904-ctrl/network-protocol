(function () {
  'use strict';

  const statusDot = document.getElementById('status-dot');
  const statusLabel = document.getElementById('status-label');
  const peerName = document.getElementById('peer-name');
  const modeBadge = document.getElementById('mode-badge');
  const activePeerAvatar = document.getElementById('active-peer-avatar');
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
    { cmd: '/drop ', name: '/drop <text>', desc: 'Simulate 1 dropped packet (demonstrates ACK timeout & retransmit)' },
    { cmd: '/fail ', name: '/fail <text>', desc: 'Simulate offline peer (demonstrates retry exhaustion to FAILED)' },
    { cmd: '/image ', name: '/image [ttlSeconds]', desc: 'Transfer chunked photo over UDP with ephemeral timer' },
    { cmd: '/peer ', name: '/peer <alice|bob|sensor> <text>', desc: 'Send targeted message to a specific peer node' },
    { cmd: '/sim ', name: '/sim <alice|bob|sensor> [msg]', desc: 'Trigger incoming UDP transmission from simulated peer' },
    { cmd: '/stats', name: '/stats', desc: 'Display active sessions and UDP protocol metrics' },
    { cmd: '/clear', name: '/clear', desc: 'Clear conversation history' },
    { cmd: '/help', name: '/help', desc: 'Show all available protocol showcase commands' }
  ];

  function init() {
    setupEventListeners();
    connectWebSocket();
    loadStatusFallback();
  }

  function setupEventListeners() {
    detailsToggleBtn.addEventListener('click', function () {
      protocolBanner.classList.toggle('hidden');
    });

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

  function handleCommandAutocomplete() {
    const val = messageInput.value;
    if (val.startsWith('/')) {
      const query = val.toLowerCase();
      const matched = COMMANDS.filter(function (c) {
        return c.cmd.toLowerCase().startsWith(query) || c.name.toLowerCase().includes(query);
      });

      if (matched.length > 0) {
        renderCommandPalette(matched);
        return;
      }
    }
    hideCommandPalette();
  }

  function renderCommandPalette(commands) {
    commandPaletteList.innerHTML = '';
    commands.forEach(function (c, idx) {
      const item = document.createElement('div');
      item.className = 'command-item' + (idx === 0 ? ' selected' : '');
      item.dataset.cmd = c.cmd;

      const name = document.createElement('span');
      name.className = 'command-name';
      name.textContent = c.name;

      const desc = document.createElement('span');
      desc.className = 'command-desc';
      desc.textContent = c.desc;

      item.appendChild(name);
      item.appendChild(desc);

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

  function submitCommandDirect(cmd) {
    messageInput.value = cmd;
    autoResizeTextarea();
    updateSendButtonState();
    composerForm.dispatchEvent(new Event('submit'));
  }

  function autoResizeTextarea() {
    messageInput.style.height = 'auto';
    const newHeight = Math.min(messageInput.scrollHeight, 100);
    messageInput.style.height = newHeight + 'px';
  }

  function updateSendButtonState() {
    const text = messageInput.value.trim();
    const isConnected = ws && ws.readyState === WebSocket.OPEN;
    sendBtn.disabled = !isConnected || text.length === 0;
  }

  function connectWebSocket() {
    clearTimeout(reconnectTimer);
    updateConnectionStatus('connecting', 'Connecting to Gateway...');

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
    if (info.localUdpPort) {
      bannerUdpPort.textContent = info.localUdpPort;
    }
    bannerGateway.textContent = 'HTTP & WS (Port ' + window.location.port + ')';
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

    telemetrySummary.textContent = 'Sent: ' + telemetrySent + ' | Recv: ' + telemetryRecv + ' | RTT: ' + (lastLatency !== null ? lastLatency + 'ms' : '--');

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
        if (data.port) {
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

    const payload = JSON.stringify({
      action: 'SEND_MESSAGE',
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

    if (msg.messageType === 'SYSTEM') {
      group.className = 'message-group system-group';
    } else {
      group.className = 'message-group ' + (msg.sender === 'user' ? 'outgoing' : 'incoming');
    }

    if (msg.sender !== 'user' && msg.messageType !== 'SYSTEM') {
      const peerHeader = document.createElement('div');
      peerHeader.className = 'message-peer-header';

      const peerBadge = document.createElement('span');
      peerBadge.className = 'message-peer-badge';
      peerBadge.textContent = msg.peerName || 'Peer';
      if (msg.peerColor) {
        peerBadge.style.color = msg.peerColor;
      }
      peerHeader.appendChild(peerBadge);
      group.appendChild(peerHeader);
    }

    const bubble = document.createElement('div');

    if (msg.messageType === 'IMAGE' && msg.mediaData) {
      bubble.className = 'message-bubble message-bubble-image';
      renderImageCard(bubble, msg);
    } else {
      bubble.className = 'message-bubble';
      bubble.textContent = msg.content;
    }
    group.appendChild(bubble);

    if (msg.messageType !== 'SYSTEM') {
      const meta = document.createElement('div');
      meta.className = 'message-meta';

      const timeSpan = document.createElement('span');
      timeSpan.className = 'message-time';
      timeSpan.textContent = formatTimestamp(msg.timestamp);
      meta.appendChild(timeSpan);

      if (msg.sender === 'user') {
        const statusSpan = document.createElement('span');
        statusSpan.className = 'message-status';
        statusSpan.id = 'status-' + msg.id;
        statusSpan.textContent = formatStatusLabel(msg.status, msg.retryCount);
        meta.appendChild(statusSpan);
      }

      group.appendChild(meta);
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
