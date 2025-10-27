(() => {
  const CONTEXT_COST = 200;

  const elements = {
    roomSetup: document.getElementById('room-setup'),
    joinRoomForm: document.getElementById('join-room-form'),
    createRoomForm: document.getElementById('create-room-form'),
    joinRoomId: document.getElementById('join-room-id'),
    joinUsername: document.getElementById('join-username'),
    createRoomName: document.getElementById('create-room-name'),
    createRoomDb: document.getElementById('create-room-db'),
    createUsername: document.getElementById('create-username'),
    scoreboard: document.getElementById('scoreboard'),
    roomNameLabel: document.getElementById('room-name-label'),
    roomIdLabel: document.getElementById('room-id-label'),
    playerLabel: document.getElementById('player-name-label'),
    totalPoints: document.getElementById('total-points'),
    currentStreak: document.getElementById('current-streak'),
    bestStreak: document.getElementById('best-streak'),
    leaderboardBody: document.getElementById('leaderboard-body'),
    gameCard: document.getElementById('game-card'),
    messageContent: document.getElementById('message-content'),
    messageMeta: document.getElementById('message-meta'),
    attachments: document.getElementById('attachments'),
    embeds: document.getElementById('embeds'),
    choices: document.getElementById('choices'),
    contextBtn: document.getElementById('context-btn'),
    contextContainer: document.getElementById('context'),
    contextBefore: document.getElementById('context-before'),
    contextAfter: document.getElementById('context-after'),
    result: document.getElementById('result'),
    nextBtn: document.getElementById('next-btn')
  };

  const state = {
    roomId: null,
    roomName: null,
    username: null,
    currentQuestionId: null,
    contextUnlocked: false,
    isLocked: false,
    latestScore: { totalPoints: 0, currentStreak: 0, bestStreak: 0 }
  };
  let leaderboardRefreshInFlight = false;

  function init() {
    elements.joinRoomForm.addEventListener('submit', handleJoinRoom);
    elements.createRoomForm.addEventListener('submit', handleCreateRoom);
    elements.nextBtn.addEventListener('click', fetchRandomMessage);
    elements.contextBtn.addEventListener('click', requestContext);
    disableGameUI();

    setInterval(() => {
      if (state.roomId) {
        refreshLeaderboard();
      }
    }, 15000);
  }

  async function handleJoinRoom(event) {
    event.preventDefault();
    const roomId = sanitizeRoomId(elements.joinRoomId.value);
    const username = sanitizeUsername(elements.joinUsername.value);
    if (!roomId || !username) {
      alert('Please provide both room ID and username.');
      return;
    }

    const info = await fetchRoomInfo(roomId);
    if (!info) {
      alert('Room not found. Double-check the room ID.');
      return;
    }

    setActiveRoom(roomId, info.displayName, username);
    await fetchRandomMessage();
  }

  async function handleCreateRoom(event) {
    event.preventDefault();
    const file = elements.createRoomDb.files[0];
    const username = sanitizeUsername(elements.createUsername.value);
    if (!file || !username) {
      alert('Please choose a database file and username.');
      return;
    }

    const roomName = sanitizeRoomName(elements.createRoomName.value);
    try {
      const base64 = await readFileAsBase64(file);
      const params = new URLSearchParams();
      if (roomName) {
        params.set('roomName', roomName);
      }
      params.set('dbBase64', base64);

      const response = await fetch('/api/rooms', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      });

      if (!response.ok) {
        const message = await response.text();
        throw new Error(message || 'Failed to create room.');
      }

      const data = await response.json();
      setActiveRoom(data.roomId, data.displayName, username);
      await fetchRandomMessage();
      alert(`Room created! Share this ID with others: ${data.roomId}`);
    } catch (error) {
      alert(error.message || 'Unable to create room.');
    }
  }

  function setActiveRoom(roomId, roomName, username) {
    state.roomId = sanitizeRoomId(roomId);
    state.roomName = roomName || state.roomId;
    state.username = sanitizeUsername(username) || 'Player';
    state.latestScore = { totalPoints: 0, currentStreak: 0, bestStreak: 0 };
    state.contextUnlocked = false;

    elements.roomNameLabel.textContent = state.roomName;
    elements.roomIdLabel.textContent = state.roomId;
    elements.playerLabel.textContent = state.username;
    clearContext();
    resetResult();
    updateScoreboardDisplay();
    refreshLeaderboard();
    enableGameUI();
  }

  async function fetchRandomMessage() {
    if (!ensureReady()) {
      return;
    }
    state.currentQuestionId = null;
    state.contextUnlocked = false;
    state.isLocked = false;
    updateContextButton();
    showLoadingState();
    try {
      const response = await fetch(`/api/random-message?roomId=${encodeURIComponent(state.roomId)}&username=${encodeURIComponent(state.username)}`);
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `Server responded ${response.status}`);
      }
      const data = await response.json();
      state.currentQuestionId = data.questionId;
      renderMessage(data);
      updateScoreboard(data.score);
      resetResult();
    } catch (error) {
      renderError(error.message || 'Unable to fetch the next message.');
    }
  }

  async function submitChoice(choice) {
    if (!ensureReady() || state.isLocked || !state.currentQuestionId) {
      return;
    }

    const choiceId = choice.participantId;
    const choiceLabel = choice.displayName || choice.fullName || 'Unknown';

    state.isLocked = true;
    setChoicesDisabled(true);

    try {
      const params = new URLSearchParams({
        roomId: state.roomId,
        username: state.username,
        questionId: state.currentQuestionId,
        choiceId
      });
      const response = await fetch('/api/guess', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `Server responded ${response.status}`);
      }
      const data = await response.json();
      decorateChoiceButtons(choiceId, data.correctChoiceId);
      renderGuessResult(data, choiceLabel);
      updateScoreboard(data);
      refreshLeaderboard();
      state.currentQuestionId = null;
    } catch (error) {
      renderError(error.message || 'Unable to check your guess.');
      setChoicesDisabled(false);
      state.isLocked = false;
    }
  }

  async function requestContext() {
    if (!ensureReady() || state.contextUnlocked || state.isLocked || !state.currentQuestionId) {
      return;
    }
    if (state.latestScore.totalPoints < CONTEXT_COST) {
      return;
    }

    state.latestScore.totalPoints = Math.max(0, state.latestScore.totalPoints - CONTEXT_COST);
    updateScoreboardDisplay();
    updateContextButton();
    showContextLoading();

    const params = new URLSearchParams({
      roomId: state.roomId,
      username: state.username,
      questionId: state.currentQuestionId
    });

    try {
      const response = await fetch('/api/context', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `Server responded ${response.status}`);
      }
      const data = await response.json();
      renderContext(data.context || null);
      state.contextUnlocked = true;
      updateScoreboard(data.score);
      refreshLeaderboard();
      updateContextButton();
    } catch (error) {
      alert(error.message || 'Unable to unlock context.');
      state.contextUnlocked = false;
      updateScoreboardDisplay();
      clearContext();
      updateContextButton();
    }
  }

  async function refreshLeaderboard() {
    if (!state.roomId || leaderboardRefreshInFlight) {
      return;
    }
    leaderboardRefreshInFlight = true;
    try {
      const response = await fetch(`/api/rooms?roomId=${encodeURIComponent(state.roomId)}`);
      if (!response.ok) {
        return;
      }
      const data = await response.json();
      state.roomName = data.displayName || state.roomId;
      elements.roomNameLabel.textContent = state.roomName;
      renderLeaderboard(data.leaderboard || []);
    } catch (error) {
      console.error('Failed to refresh leaderboard:', error);
    } finally {
      leaderboardRefreshInFlight = false;
    }
  }

  async function fetchRoomInfo(roomId) {
    try {
      const response = await fetch(`/api/rooms?roomId=${encodeURIComponent(roomId)}`);
      if (!response.ok) {
        return null;
      }
      return response.json();
    } catch {
      return null;
    }
  }

  function renderMessage(data) {
    const content = data.content ?? '(no content provided)';
    elements.messageContent.textContent = content;
    elements.messageMeta.textContent = data.timestamp
      ? `Sent at ${new Date(data.timestamp).toLocaleString()}`
      : '';

    renderAttachments(Array.isArray(data.attachments) ? data.attachments : []);
    renderEmbeds(Array.isArray(data.embeds) ? data.embeds : []);
    renderChoices(Array.isArray(data.choices) ? data.choices : []);
    state.contextUnlocked = false;
    clearContext();
    updateContextButton();
  }

  function renderGuessResult(result, choiceLabel) {
    elements.result.classList.remove('hidden', 'correct', 'incorrect');
    const template = document.getElementById('result-template');
    const clone = template.content.cloneNode(true);
    const title = clone.querySelector('.result-title');
    const body = clone.querySelector('.result-body');

    if (result.correct) {
      title.textContent = 'Correct! 🎉';
      elements.result.classList.add('correct');
      body.textContent = [
        `Well done! ${result.displayName || 'Unknown'} sent that message.`,
        `${formatSignedPoints(result.awardedPoints)} pts (base ${formatPoints(result.basePoints)} × streak x${formatMultiplier(result.streakMultiplier)})`,
        `Answered in ${formatSeconds(result.elapsedSeconds)} s`
      ].join('\n');
    } else {
      title.textContent = 'Not quite...';
      elements.result.classList.add('incorrect');
      const fullName = result.fullName ? ` (${result.fullName})` : '';
      body.textContent = [
        `You picked ${choiceLabel}, but it was ${result.displayName || 'Unknown'}${fullName}.`,
        `${formatSignedPoints(result.awardedPoints)} pts (base ${formatPoints(result.basePoints)} available)`,
        `Answered in ${formatSeconds(result.elapsedSeconds)} s`
      ].join('\n');
    }

    elements.result.replaceChildren(clone);
  }

  function renderError(message) {
    elements.result.classList.remove('hidden', 'correct', 'incorrect');
    elements.result.textContent = message;
  }

  function resetResult() {
    elements.result.classList.add('hidden');
    elements.result.classList.remove('correct', 'incorrect');
    elements.result.textContent = '';
    state.isLocked = false;
    setChoicesDisabled(false);
  }

  function showLoadingState() {
    elements.messageContent.textContent = 'Loading message…';
    elements.messageMeta.textContent = '';
    elements.attachments.classList.add('hidden');
    elements.attachments.replaceChildren();
    elements.embeds.classList.add('hidden');
    elements.embeds.replaceChildren();
    elements.choices.classList.add('hidden');
    elements.choices.replaceChildren();
    resetResult();
    clearContext();
    updateContextButton();
  }

  function renderAttachments(attachments) {
    elements.attachments.replaceChildren();
    if (!attachments || attachments.length === 0) {
      elements.attachments.classList.add('hidden');
      return;
    }

    elements.attachments.classList.remove('hidden');
    attachments.forEach((attachment) => {
      if (!attachment || !attachment.url) {
        return;
      }
      const wrapper = document.createElement('div');
      wrapper.className = 'attachment';

      if (isImageAttachment(attachment)) {
        const img = document.createElement('img');
        img.src = attachment.url;
        img.alt = attachment.fileName || 'Attachment image';
        img.className = 'attachment-image';
        img.loading = 'lazy';
        wrapper.appendChild(img);
      } else if (isVideoAttachment(attachment)) {
        const video = document.createElement('video');
        video.src = attachment.url;
        video.className = 'embed-video';
        video.controls = true;
        video.preload = 'metadata';
        wrapper.appendChild(video);
      } else {
        const link = document.createElement('a');
        link.href = attachment.url;
        link.target = '_blank';
        link.rel = 'noreferrer noopener';
        link.className = 'attachment-link';
        link.textContent = attachment.fileName || attachment.url;
        wrapper.appendChild(link);
      }

      elements.attachments.appendChild(wrapper);
    });
  }

  function renderEmbeds(embeds) {
    elements.embeds.replaceChildren();
    if (!embeds || embeds.length === 0) {
      elements.embeds.classList.add('hidden');
      return;
    }

    elements.embeds.classList.remove('hidden');
    embeds.forEach((embed) => {
      if (!embed || typeof embed !== 'object') {
        return;
      }
      const wrapper = document.createElement('div');
      wrapper.className = 'embed';

      if (embed.title) {
        const header = document.createElement('div');
        header.className = 'embed-header';
        const title = document.createElement('h3');
        title.className = 'embed-title';
        title.textContent = embed.title;
        header.appendChild(title);

        if (embed.url) {
          const link = document.createElement('a');
          link.href = embed.url;
          link.target = '_blank';
          link.rel = 'noreferrer noopener';
          link.className = 'embed-link';
          link.textContent = 'Open';
          header.appendChild(link);
        }
        wrapper.appendChild(header);
      }

      if (embed.description) {
        const description = document.createElement('p');
        description.className = 'embed-description';
        description.textContent = embed.description;
        wrapper.appendChild(description);
      }

      if (embed.video && embed.video.url) {
        const videoUrl = embed.video.url;
        if (isEmbeddableVideoUrl(videoUrl)) {
          const video = document.createElement('video');
          video.src = videoUrl;
          video.controls = true;
          video.preload = 'metadata';
          video.className = 'embed-video';
          if (embed.video.height) {
            video.style.maxHeight = `${embed.video.height}px`;
          }
          wrapper.appendChild(video);
        } else {
          const iframeSrc = buildEmbedIframeSrc(embed);
          if (iframeSrc) {
            const iframe = document.createElement('iframe');
            iframe.src = iframeSrc;
            iframe.allowFullscreen = true;
            iframe.className = 'embed-iframe';
            wrapper.appendChild(iframe);
          }
        }
      } else if (embed.thumbnail && embed.thumbnail.url) {
        const thumb = document.createElement('img');
        thumb.src = embed.thumbnail.url;
        thumb.alt = embed.title || 'Embed thumbnail';
        thumb.className = 'embed-thumbnail';
        thumb.loading = 'lazy';
        wrapper.appendChild(thumb);
      }

      if (embed.url && !wrapper.querySelector('.embed-link')) {
        const link = document.createElement('a');
        link.href = embed.url;
        link.target = '_blank';
        link.rel = 'noreferrer noopener';
        link.className = 'embed-link';
        link.textContent = embed.url;
        wrapper.appendChild(link);
      }

      elements.embeds.appendChild(wrapper);
    });
  }

  function renderChoices(choices) {
    elements.choices.replaceChildren();
    if (!choices || choices.length === 0) {
      elements.choices.classList.add('hidden');
      return;
    }

    elements.choices.classList.remove('hidden');
    choices.forEach((choice) => {
      if (!choice || !choice.participantId) {
        return;
      }
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'choice-btn';
      button.dataset.choiceId = choice.participantId;
      button.textContent = choice.displayName || choice.fullName || 'Unknown';
      button.addEventListener('click', () => submitChoice(choice));
      elements.choices.appendChild(button);
    });
  }

  function renderContext(context) {
    if (!elements.contextContainer) {
      return;
    }
    elements.contextContainer.classList.remove('hidden');
    renderContextEntry(elements.contextBefore, context?.before, 'No earlier message available.');
    renderContextEntry(elements.contextAfter, context?.after, 'No later message available.');
  }

  function renderContextEntry(target, snippet, fallback) {
    if (!target) {
      return;
    }
    target.innerHTML = '';
    if (!snippet) {
      const placeholder = document.createElement('div');
      placeholder.className = 'context-empty';
      placeholder.textContent = fallback;
      target.appendChild(placeholder);
      return;
    }
    const authorEl = document.createElement('div');
    authorEl.className = 'context-author';
    authorEl.textContent = snippet.displayName || 'Unknown';

    const timestampEl = document.createElement('div');
    timestampEl.className = 'context-timestamp';
    timestampEl.textContent = snippet.timestamp
      ? new Date(snippet.timestamp).toLocaleString()
      : 'Unknown time';

    const contentEl = document.createElement('div');
    contentEl.className = 'context-content';
    contentEl.textContent = snippet.content || '(no content)';

    target.appendChild(authorEl);
    target.appendChild(timestampEl);
    target.appendChild(contentEl);
  }

  function clearContext() {
    if (!elements.contextContainer) {
      return;
    }
    elements.contextContainer.classList.add('hidden');
    renderContextEntry(elements.contextBefore, null, 'No earlier message available.');
    renderContextEntry(elements.contextAfter, null, 'No later message available.');
  }

  function renderLeaderboard(entries) {
    elements.leaderboardBody.innerHTML = '';
    if (!entries || entries.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 4;
      cell.textContent = 'No players yet.';
       cell.dataset.label = 'Info';
      row.appendChild(cell);
      elements.leaderboardBody.appendChild(row);
      return;
    }

    entries.forEach((entry) => {
      const row = document.createElement('tr');
      if (entry.username === state.username) {
        row.classList.add('highlight');
      }

      const cells = [
        { label: 'Player', value: entry.username },
        { label: 'Points', value: formatPoints(entry.totalPoints) },
        { label: 'Streak', value: entry.currentStreak },
        { label: 'Best', value: entry.bestStreak }
      ];

      cells.forEach(({ label, value }) => {
        const cell = document.createElement('td');
        cell.dataset.label = label;
        cell.textContent = value;
        row.appendChild(cell);
      });

      elements.leaderboardBody.appendChild(row);
    });
  }

  function updateScoreboard(score) {
    if (score) {
      state.latestScore = {
        totalPoints: Number(score.totalPoints ?? 0),
        currentStreak: Number(score.currentStreak ?? 0),
        bestStreak: Number(score.bestStreak ?? 0)
      };
    }
    updateScoreboardDisplay();
    updateContextButton();
  }

  function updateScoreboardDisplay() {
    elements.totalPoints.textContent = formatPoints(state.latestScore.totalPoints);
    elements.currentStreak.textContent = state.latestScore.currentStreak.toString();
    elements.bestStreak.textContent = state.latestScore.bestStreak.toString();
  }

  function setChoicesDisabled(disabled) {
    elements.choices.querySelectorAll('button.choice-btn').forEach((button) => {
      button.disabled = disabled;
    });
    updateContextButton();
  }

  function showContextLoading() {
    elements.contextContainer.classList.remove('hidden');
    renderContextEntry(elements.contextBefore, null, 'Loading context…');
    renderContextEntry(elements.contextAfter, null, 'Loading context…');
  }

  function updateContextButton() {
    if (!elements.contextBtn) {
      return;
    }
    const disabled = !state.roomId
      || !state.currentQuestionId
      || state.contextUnlocked
      || state.isLocked
      || state.latestScore.totalPoints < CONTEXT_COST;
    elements.contextBtn.disabled = disabled;
    elements.contextBtn.textContent = state.contextUnlocked
      ? 'Context unlocked'
      : `Buy Context (-${formatPoints(CONTEXT_COST)} pts)`;
  }

  function enableGameUI() {
    elements.roomSetup.classList.add('hidden');
    elements.scoreboard.classList.remove('hidden');
    elements.gameCard.classList.remove('hidden');
    elements.contextBtn.disabled = true;
  }

  function disableGameUI() {
    elements.roomSetup.classList.remove('hidden');
    elements.scoreboard.classList.add('hidden');
    elements.gameCard.classList.add('hidden');
  }

  function decorateChoiceButtons(selectedId, correctId) {
    elements.choices.querySelectorAll('button.choice-btn').forEach((button) => {
      const id = button.dataset.choiceId;
      button.disabled = true;
      if (id === correctId) {
        button.classList.add('correct');
      } else if (id === selectedId) {
        button.classList.add('incorrect');
      }
    });
  }

  function ensureReady() {
    if (!state.roomId || !state.username) {
      alert('Join or create a room first.');
      return false;
    }
    return true;
  }

  function formatPoints(value) {
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(Math.max(0, Math.round(value)));
  }

  function formatSignedPoints(value) {
    const rounded = Math.round(value);
    const formatted = formatPoints(Math.abs(rounded));
    return rounded >= 0 ? `+${formatted}` : `-${formatted}`;
  }

  function formatSeconds(value) {
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(Math.max(0, value));
  }

  function formatMultiplier(value) {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Math.max(0, value));
  }

  function isImageAttachment(attachment) {
    const source = (attachment.fileName || attachment.url || '').toLowerCase();
    return /\.(png|jpe?g|gif|webp|bmp|svg)$/.test(source);
  }

  function isVideoAttachment(attachment) {
    const source = (attachment.fileName || attachment.url || '').toLowerCase();
    return /\.(mp4|mov|webm|mkv|avi)$/.test(source);
  }

  function isEmbeddableVideoUrl(url) {
    try {
      const parsed = new URL(url, window.location.href);
      return /\.(mp4|mov|webm|mkv)$/.test(parsed.pathname.toLowerCase());
    } catch {
      return false;
    }
  }

  function buildEmbedIframeSrc(embed) {
    if (!embed || !embed.url) {
      return null;
    }
    const url = embed.url;
    if (url.includes('youtube.com/watch') || url.includes('youtube.com/shorts')) {
      const videoId = extractYouTubeId(url);
      return videoId ? `https://www.youtube.com/embed/${videoId}` : null;
    }
    if (url.includes('youtu.be/')) {
      const id = url.split('youtu.be/')[1].split(/[?&]/)[0];
      return id ? `https://www.youtube.com/embed/${id}` : null;
    }
    if (url.includes('vimeo.com/')) {
      const match = url.match(/vimeo\.com\/(\d+)/);
      return match ? `https://player.vimeo.com/video/${match[1]}` : null;
    }
    if (embed.video && embed.video.url) {
      return embed.video.url;
    }
    return null;
  }

  function extractYouTubeId(url) {
    try {
      const urlObj = new URL(url, window.location.href);
      if (urlObj.hostname === 'youtu.be') {
        return urlObj.pathname.slice(1);
      }
      if (urlObj.searchParams.has('v')) {
        return urlObj.searchParams.get('v');
      }
      const pathMatch = urlObj.pathname.match(/\/shorts\/([^/]+)/);
      return pathMatch ? pathMatch[1] : null;
    } catch {
      return null;
    }
  }

  function readFileAsBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error('Failed to read file.'));
      reader.onload = () => {
        const buffer = new Uint8Array(reader.result);
        let binary = '';
        buffer.forEach((byte) => {
          binary += String.fromCharCode(byte);
        });
        resolve(btoa(binary));
      };
      reader.readAsArrayBuffer(file);
    });
  }

  init();

  function sanitizeUsername(value) {
    if (!value) {
      return '';
    }
    const cleaned = value.replace(/[^\w\s\-]/g, '').trim().replace(/\s+/g, ' ');
    return cleaned.slice(0, 32);
  }

  function sanitizeRoomId(value) {
    if (!value) {
      return '';
    }
    return value.trim().toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 10);
  }

  function sanitizeRoomName(value) {
    if (!value) {
      return '';
    }
    return value.replace(/[^\w\s\-]/g, '').trim().replace(/\s+/g, ' ').slice(0, 40);
  }
})();
