/* =====================================================
   REALTIME TRANSLATOR – APP.JS
   Web Speech API + MyMemory Translation + Floating UI
   ===================================================== */

'use strict';

// =====================================================
// STATE
// =====================================================
const state = {
  isListening: false,
  langSource: 'en',       // 'en' or 'vi'
  langTarget: 'vi',       // 'vi' or 'en'
  langSourceLabel: '🇺🇸 EN',
  langTargetLabel: '🇻🇳 VI',
  langSourceFull: 'English',
  langTargetFull: 'Tiếng Việt',
  langSourceFlag: '🇺🇸',
  langTargetFlag: '🇻🇳',
  totalWords: 0,
  totalSentences: 0,
  audioSource: 'mic',     // 'mic' | 'demo'
  floatingVisible: true,
  floatingMinimized: false,
  demoTimer: null,
  recognition: null,
  currentOriginal: '',
  currentTranslated: '',
  translateTimeout: null,
  latencyStart: 0,
  vizInterval: null,
};

// =====================================================
// DEMO SENTENCES (for no-mic scenario)
// =====================================================
const DEMO_EN = [
  "The quick brown fox jumps over the lazy dog.",
  "Artificial intelligence is transforming the way we live and work.",
  "Real-time translation technology has advanced significantly in recent years.",
  "Scientists have discovered a new method to generate clean energy from water.",
  "The global economy is recovering faster than expected after the pandemic.",
  "Language barriers are being broken down by modern translation tools.",
  "Machine learning algorithms can now recognize speech with high accuracy.",
  "Technology companies are investing billions in natural language processing.",
  "The future of communication lies in seamless multilingual interaction.",
  "Breaking news: World leaders gather for the annual summit on climate change.",
];

const DEMO_VI = [
  "Công nghệ trí tuệ nhân tạo đang thay đổi cuộc sống hàng ngày của chúng ta.",
  "Hệ thống dịch thuật thời gian thực ngày càng trở nên chính xác hơn.",
  "Các nhà khoa học Việt Nam đã phát triển thuật toán nhận dạng giọng nói mới.",
  "Kinh tế Việt Nam tiếp tục tăng trưởng mạnh trong quý đầu năm.",
  "Công nghệ học máy đang được ứng dụng rộng rãi trong nhiều lĩnh vực.",
  "Các rào cản ngôn ngữ đang dần được xóa bỏ nhờ công nghệ hiện đại.",
  "Thị trường công nghệ toàn cầu đạt kỷ lục về doanh thu năm nay.",
  "Ứng dụng dịch thuật thông minh giúp giao tiếp dễ dàng hơn bao giờ hết.",
];

// =====================================================
// DOM ELEMENTS
// =====================================================
const dom = {
  btnStart: document.getElementById('btn-start-translate'),
  btnSwap: document.getElementById('btn-swap-lang'),
  statusDot: document.getElementById('status-dot'),
  statusText: document.getElementById('status-text'),
  langSourceName: document.getElementById('lang-source-name'),
  langTargetName: document.getElementById('lang-target-name'),
  langSourceBadge: document.getElementById('lang-source-badge'),
  langTargetBadge: document.getElementById('lang-target-badge'),
  originalTag: document.getElementById('original-tag'),
  translatedTag: document.getElementById('translated-tag'),
  originalText: document.getElementById('original-text'),
  translatedText: document.getElementById('translated-text'),
  typingCursor1: document.getElementById('typing-cursor-1'),
  typingCursor2: document.getElementById('typing-cursor-2'),
  historyList: document.getElementById('history-list'),
  btnClearHistory: document.getElementById('btn-clear-history'),
  statWords: document.getElementById('stat-words'),
  statSentences: document.getElementById('stat-sentences'),
  statLatency: document.getElementById('stat-latency'),
  statAccuracy: document.getElementById('stat-accuracy'),
  toast: document.getElementById('toast'),
  fontSizeSlider: document.getElementById('font-size-slider'),
  fontSizeVal: document.getElementById('font-size-val'),
  opacitySlider: document.getElementById('opacity-slider'),
  opacityVal: document.getElementById('opacity-val'),
  floatingWidget: document.getElementById('floating-widget'),
  floatingBubble: document.getElementById('floating-bubble'),
  widgetDragHandle: document.getElementById('widget-drag-handle'),
  widgetMinimizeBtn: document.getElementById('widget-minimize-btn'),
  widgetCloseBtn: document.getElementById('widget-close-btn'),
  widgetSwapBtn: document.getElementById('widget-swap-btn'),
  widgetBody: document.getElementById('widget-body'),
  widgetDot: document.querySelector('.widget-dot'),
  soundWave: document.getElementById('sound-wave'),
  btnIcon: document.querySelector('.btn-icon'),
  btnText: document.querySelector('.btn-text'),
  liveDot: document.getElementById('live-dot'),
  vizBars: document.querySelectorAll('.viz-bar'),
  srcCodeTag: document.getElementById('src-code-tag'),
  tgtCodeTag: document.getElementById('tgt-code-tag'),
};

// =====================================================
// UTILITIES
// =====================================================
function showToast(msg, duration = 3000) {
  dom.toast.textContent = msg;
  dom.toast.classList.add('show');
  setTimeout(() => dom.toast.classList.remove('show'), duration);
}

function setStatus(state_type, msg) {
  dom.statusDot.className = 'status-dot';
  if (state_type === 'listening') dom.statusDot.classList.add('listening');
  if (state_type === 'error') dom.statusDot.classList.add('error');
  if (state_type === 'translating') dom.statusDot.classList.add('translating');
  dom.statusText.textContent = msg;
}

function animateNumber(el, val) {
  const current = parseInt(el.textContent) || 0;
  const step = val > current ? 1 : -1;
  if (current === val) return;
  let i = current;
  const t = setInterval(() => {
    i += step;
    el.textContent = i;
    if (i === val) clearInterval(t);
  }, 20);
}

// =====================================================
// TRANSLATION ENGINE (MyMemory API – Free, No Key Required)
// =====================================================
async function translateText(text, srcLang, tgtLang) {
  if (!text || text.trim().length < 2) return '';
  const langPair = `${srcLang}|${tgtLang}`;
  const url = `https://api.mymemory.translated.net/get?q=${encodeURIComponent(text)}&langpair=${langPair}`;
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 2500); // timeout 2.5s — fail nhanh hơn
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timeout);
    const data = await res.json();
    if (data.responseStatus === 200) {
      return data.responseData.translatedText || '';
    }
    return '[Lỗi dịch]';
  } catch (e) {
    if (e.name === 'AbortError') return '[Quá thời gian]';
    console.error('Translation error:', e);
    return '[Đang offline]';
  }
}

/**
 * Cắt câu dài tại ranh giới dấu câu / từ — tránh gửi đoạn quá dài cho API (giảm lag)
 */
function truncateForTranslation(text, maxLen = 120) {
  if (text.length <= maxLen) return text;
  const sub = text.substring(0, maxLen);
  const punctIdx = Math.max(sub.lastIndexOf('.'), sub.lastIndexOf('!'), sub.lastIndexOf('?'), sub.lastIndexOf(','), sub.lastIndexOf(';'));
  if (punctIdx > maxLen / 2) return sub.substring(0, punctIdx + 1).trim();
  const spaceIdx = sub.lastIndexOf(' ');
  return spaceIdx > 0 ? sub.substring(0, spaceIdx).trim() : sub.trim();
}

// =====================================================
// DISPLAY HELPERS
// =====================================================
function setOriginalText(text, isPartial = false) {
  if (isPartial) {
    dom.originalText.innerHTML = `<span>${escapeHtml(text)}</span>`;
    dom.typingCursor1.classList.add('active');
  } else {
    dom.originalText.innerHTML = escapeHtml(text);
    dom.typingCursor1.classList.remove('active');
  }
}

function setTranslatedText(text, isLoading = false) {
  if (isLoading) {
    dom.translatedText.innerHTML = `<span class="placeholder-text">Đang dịch...</span>`;
    dom.typingCursor2.classList.add('active');
  } else if (!text) {
    dom.translatedText.innerHTML = `<span class="placeholder-text">Bản dịch sẽ xuất hiện tại đây...</span>`;
    dom.typingCursor2.classList.remove('active');
  } else {
    dom.translatedText.innerHTML = escapeHtml(text);
    dom.typingCursor2.classList.remove('active');
  }
}

function escapeHtml(t) {
  return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#039;');
}

function addToHistory(original, translated) {
  const emptyEl = dom.historyList.querySelector('.history-empty');
  if (emptyEl) emptyEl.remove();

  const item = document.createElement('div');
  item.className = 'history-item';
  item.innerHTML = `<div class="hi-orig">${escapeHtml(original.substring(0, 80))}${original.length > 80 ? '…' : ''}</div><div class="hi-trans">${escapeHtml(translated.substring(0, 80))}${translated.length > 80 ? '…' : ''}</div>`;
  dom.historyList.insertBefore(item, dom.historyList.firstChild);

  // Max 15 items
  while (dom.historyList.children.length > 15) {
    dom.historyList.removeChild(dom.historyList.lastChild);
  }
}

// =====================================================
// WEB SPEECH RECOGNITION
// =====================================================
function setupSpeechRecognition() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    showToast('⚠️ Trình duyệt không hỗ trợ Speech Recognition! Dùng Chrome/Edge.', 5000);
    return null;
  }

  const recognition = new SpeechRecognition();
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.maxAlternatives = 1;
  recognition.lang = state.langSource === 'en' ? 'en-US' : 'vi-VN';

  recognition.onstart = () => {
    setStatus('listening', `🎤 Đang lắng nghe ${state.langSourceFull}...`);
    dom.liveDot.style.background = 'var(--green)';
    dom.liveDot.style.boxShadow = '0 0 8px var(--green)';
    startVizAnimation();
  };

  recognition.onresult = async (event) => {
    let interimTranscript = '';
    let finalTranscript = '';

    for (let i = event.resultIndex; i < event.results.length; ++i) {
      const transcript = event.results[i][0].transcript;
      if (event.results[i].isFinal) {
        finalTranscript += transcript;
      } else {
        interimTranscript += transcript;
      }
    }

    // Show partial / interim
    if (interimTranscript) {
      setOriginalText(interimTranscript, true);
      setTranslatedText('', true);
    }

    // Translate final
    if (finalTranscript.trim()) {
      state.currentOriginal = finalTranscript.trim();
      setOriginalText(state.currentOriginal, false);
      setStatus('translating', '🔄 Đang dịch...');
      state.latencyStart = performance.now();

      // Cắt câu dài trước khi gửi API
      const textToTranslate = truncateForTranslation(state.currentOriginal);
      const translated = await translateText(textToTranslate, state.langSource, state.langTarget);
      const latency = Math.round(performance.now() - state.latencyStart);
      state.currentTranslated = translated;

      setTranslatedText(translated);
      setStatus('listening', `🎤 Đang lắng nghe ${state.langSourceFull}...`);

      // Stats
      const wordCount = state.currentOriginal.split(/\s+/).filter(Boolean).length;
      state.totalWords += wordCount;
      state.totalSentences += 1;
      animateNumber(dom.statWords, state.totalWords);
      animateNumber(dom.statSentences, state.totalSentences);
      dom.statLatency.textContent = latency + 'ms';
      dom.statAccuracy.textContent = '~95%';

      // History
      addToHistory(state.currentOriginal, translated);
    }
  };

  recognition.onerror = (e) => {
    console.warn('Speech error:', e.error);
    if (e.error === 'not-allowed') {
      setStatus('error', '❌ Bị từ chối truy cập micro!');
      showToast('❌ Cần cho phép truy cập Microphone!', 4000);
      stopListening();
    } else if (e.error === 'no-speech') {
      setStatus('listening', `🎤 Không nghe thấy... tiếp tục lắng nghe`);
    } else if (e.error === 'network') {
      setStatus('error', '❌ Lỗi mạng khi nhận dạng giọng nói');
    }
  };

  recognition.onend = () => {
    if (state.isListening && state.audioSource === 'mic') {
      // Auto-restart
      try { recognition.start(); } catch(e) {}
    } else {
      stopListening();
    }
  };

  return recognition;
}

// =====================================================
// DEMO MODE (Text simulation without mic)
// =====================================================
let demoIndex = 0;
async function runDemoStep() {
  if (!state.isListening) return;

  const sentences = state.langSource === 'en' ? DEMO_EN : DEMO_VI;
  const sentence = sentences[demoIndex % sentences.length];
  demoIndex++;

  // Simulate partial typing
  let displayText = '';
  const words = sentence.split(' ');

  for (let i = 0; i < words.length; i++) {
    if (!state.isListening) return;
    displayText += (i > 0 ? ' ' : '') + words[i];
    setOriginalText(displayText, true);
    await sleep(140 + Math.random() * 80);
  }

  // Full sentence shown, now translate
  setOriginalText(sentence, false);
  setStatus('translating', '🔄 Đang dịch...');
  state.latencyStart = performance.now();

  // Cắt câu dài trước khi gửi API (demo mode)
  const textToTranslate = truncateForTranslation(sentence);
  const translated = await translateText(textToTranslate, state.langSource, state.langTarget);
  const latency = Math.round(performance.now() - state.latencyStart);

  setTranslatedText(translated);
  setStatus('listening', `📻 Demo đang chạy... (${state.langSourceFull})`);

  const wordCount = sentence.split(/\s+/).length;
  state.totalWords += wordCount;
  state.totalSentences += 1;
  animateNumber(dom.statWords, state.totalWords);
  animateNumber(dom.statSentences, state.totalSentences);
  dom.statLatency.textContent = latency + 'ms';
  dom.statAccuracy.textContent = '~93%';
  addToHistory(sentence, translated);

  // Schedule next
  if (state.isListening) {
    state.demoTimer = setTimeout(() => runDemoStep(), 4000 + Math.random() * 2000);
  }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// =====================================================
// VIZ ANIMATION (Audio bars)
// =====================================================
function startVizAnimation() {
  clearInterval(state.vizInterval);
  state.vizInterval = setInterval(() => {
    dom.vizBars.forEach(bar => {
      const h = state.isListening ? (4 + Math.random() * 18) : 4;
      bar.style.height = h + 'px';
      bar.classList.toggle('active', state.isListening);
    });
  }, 120);
}

function stopVizAnimation() {
  clearInterval(state.vizInterval);
  dom.vizBars.forEach(bar => { bar.style.height = '4px'; bar.classList.remove('active'); });
}

// =====================================================
// START / STOP CONTROL
// =====================================================
function startListening() {
  state.isListening = true;
  dom.btnStart.classList.add('active');
  dom.btnIcon.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>`;
  dom.btnText.textContent = 'Dừng dịch';
  dom.soundWave.classList.add('active');

  if (!state.floatingVisible) {
    restoreWidget();
  }
  if (state.floatingMinimized) {
    expandWidget();
  }

  if (state.audioSource === 'mic') {
    state.recognition = setupSpeechRecognition();
    if (!state.recognition) {
      stopListening();
      return;
    }
    try {
      state.recognition.start();
    } catch (e) {
      showToast('Lỗi khởi động nhận dạng giọng nói', 3000);
      stopListening();
    }
  } else {
    // Demo mode
    setStatus('listening', `Demo âm thanh đang chạy (${state.langSourceFull})`);
    startVizAnimation();
    runDemoStep();
  }
}

function stopListening() {
  state.isListening = false;
  dom.btnStart.classList.remove('active');
  dom.btnIcon.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
  dom.btnText.textContent = 'Bắt đầu dịch';
  dom.soundWave.classList.remove('active');
  setStatus('idle', 'Hệ thống sẵn sàng');

  if (state.recognition) {
    try { state.recognition.stop(); } catch(e) {}
    state.recognition = null;
  }
  if (state.demoTimer) {
    clearTimeout(state.demoTimer);
    state.demoTimer = null;
  }
  stopVizAnimation();
  dom.liveDot.style.background = '';
  dom.liveDot.style.boxShadow = '';
}

// =====================================================
// LANGUAGE SWAP
// =====================================================
function swapLanguages() {
  // Swap state
  [state.langSource, state.langTarget] = [state.langTarget, state.langSource];
  [state.langSourceFull, state.langTargetFull] = [state.langTargetFull, state.langSourceFull];

  const isEnSource = state.langSource === 'en';
  state.langSourceLabel = isEnSource ? 'EN' : 'VI';
  state.langTargetLabel = isEnSource ? 'VI' : 'EN';

  // Update UI
  dom.langSourceName.textContent = state.langSourceFull;
  dom.langTargetName.textContent = state.langTargetFull;
  if (dom.srcCodeTag) dom.srcCodeTag.textContent = state.langSourceLabel;
  if (dom.tgtCodeTag) dom.tgtCodeTag.textContent = state.langTargetLabel;
  if (dom.originalTag) dom.originalTag.textContent = state.langSourceLabel;
  if (dom.translatedTag) dom.translatedTag.textContent = state.langTargetLabel;

  // Swap displayed content
  const tempOrig = dom.originalText.innerHTML;
  dom.originalText.innerHTML = dom.translatedText.innerHTML;
  dom.translatedText.innerHTML = tempOrig;

  // Restart recognition with new language
  if (state.isListening) {
    if (state.recognition) {
      try { state.recognition.stop(); } catch(e) {}
    }
    if (state.audioSource === 'mic') {
      state.recognition = setupSpeechRecognition();
      if (state.recognition) {
        try { state.recognition.start(); } catch(e) {}
      }
    }
  }

  showToast(`Đã đổi chiều: ${state.langSourceFull} → ${state.langTargetFull}`);
}

// =====================================================
// FLOATING WIDGET – DRAG & DROP
// =====================================================
(function setupDragDrop() {
  let isDragging = false;
  let startX, startY, startLeft, startTop;

  dom.widgetDragHandle.addEventListener('mousedown', (e) => {
    if (e.target.closest('.widget-btn')) return;
    isDragging = true;
    const rect = dom.floatingWidget.getBoundingClientRect();
    const parentRect = dom.floatingWidget.parentElement.getBoundingClientRect();
    startX = e.clientX;
    startY = e.clientY;
    startLeft = rect.left - parentRect.left;
    startTop = rect.top - parentRect.top;
    dom.floatingWidget.style.transition = 'none';
    dom.floatingWidget.style.right = 'unset';
    dom.floatingWidget.style.left = startLeft + 'px';
    dom.floatingWidget.style.top = startTop + 'px';
    e.preventDefault();
  });

  // Touch support
  dom.widgetDragHandle.addEventListener('touchstart', (e) => {
    if (e.target.closest('.widget-btn')) return;
    const touch = e.touches[0];
    isDragging = true;
    const rect = dom.floatingWidget.getBoundingClientRect();
    const parentRect = dom.floatingWidget.parentElement.getBoundingClientRect();
    startX = touch.clientX;
    startY = touch.clientY;
    startLeft = rect.left - parentRect.left;
    startTop = rect.top - parentRect.top;
    dom.floatingWidget.style.transition = 'none';
    dom.floatingWidget.style.right = 'unset';
    dom.floatingWidget.style.left = startLeft + 'px';
    dom.floatingWidget.style.top = startTop + 'px';
    e.preventDefault();
  }, { passive: false });

  document.addEventListener('mousemove', (e) => {
    if (!isDragging) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    const parent = dom.floatingWidget.parentElement;
    const pRect = parent.getBoundingClientRect();
    const wRect = dom.floatingWidget.getBoundingClientRect();
    let newLeft = Math.max(0, Math.min(startLeft + dx, pRect.width - wRect.width));
    let newTop = Math.max(0, Math.min(startTop + dy, pRect.height - wRect.height));
    dom.floatingWidget.style.left = newLeft + 'px';
    dom.floatingWidget.style.top = newTop + 'px';
  });

  document.addEventListener('touchmove', (e) => {
    if (!isDragging) return;
    const touch = e.touches[0];
    const dx = touch.clientX - startX;
    const dy = touch.clientY - startY;
    const parent = dom.floatingWidget.parentElement;
    const pRect = parent.getBoundingClientRect();
    const wRect = dom.floatingWidget.getBoundingClientRect();
    let newLeft = Math.max(0, Math.min(startLeft + dx, pRect.width - wRect.width));
    let newTop = Math.max(0, Math.min(startTop + dy, pRect.height - wRect.height));
    dom.floatingWidget.style.left = newLeft + 'px';
    dom.floatingWidget.style.top = newTop + 'px';
    e.preventDefault();
  }, { passive: false });

  document.addEventListener('mouseup', () => {
    if (isDragging) {
      isDragging = false;
      dom.floatingWidget.style.transition = '';
    }
  });
  document.addEventListener('touchend', () => {
    if (isDragging) {
      isDragging = false;
      dom.floatingWidget.style.transition = '';
    }
  });
})();

// =====================================================
// WIDGET CONTROLS
// =====================================================
function minimizeWidget() {
  state.floatingMinimized = true;
  dom.floatingWidget.style.display = 'none';
  dom.floatingBubble.classList.remove('hidden');
  showToast('Widget đã thu nhỏ – nhấn bong bóng để mở lại');
}

function expandWidget() {
  state.floatingMinimized = false;
  dom.floatingWidget.style.display = 'block';
  dom.floatingBubble.classList.add('hidden');
}

function hideWidget() {
  state.floatingVisible = false;
  dom.floatingWidget.style.display = 'none';
  dom.floatingBubble.classList.add('hidden');
  showToast('Widget đã đóng – nhấn "Bắt đầu dịch" để hiện lại');
}

function restoreWidget() {
  state.floatingVisible = true;
  state.floatingMinimized = false;
  dom.floatingWidget.style.display = 'block';
  dom.floatingBubble.classList.add('hidden');
}

dom.widgetMinimizeBtn.addEventListener('click', minimizeWidget);
dom.widgetCloseBtn.addEventListener('click', hideWidget);
dom.floatingBubble.addEventListener('click', expandWidget);
dom.widgetSwapBtn.addEventListener('click', () => { swapLanguages(); });

// =====================================================
// MAIN CONTROLS EVENT LISTENERS
// =====================================================
dom.btnStart.addEventListener('click', () => {
  if (state.isListening) {
    stopListening();
  } else {
    startListening();
  }
});

dom.btnSwap.addEventListener('click', swapLanguages);

dom.btnClearHistory.addEventListener('click', () => {
  dom.historyList.innerHTML = '<div class="history-empty">Chưa có lịch sử...</div>';
  showToast('Đã xóa lịch sử');
});

// Audio source toggle
document.querySelectorAll('input[name="audio-source"]').forEach(radio => {
  radio.addEventListener('change', (e) => {
    state.audioSource = e.target.value;
    if (state.isListening) {
      stopListening();
      setTimeout(() => startListening(), 300);
    }
    if (e.target.value === 'demo') {
      showToast('📻 Chế độ Demo: Tự động phát câu mẫu');
    } else {
      showToast('🎤 Chế độ Micro: Hãy nói vào micro');
    }
  });
});

// Font size slider
dom.fontSizeSlider.addEventListener('input', (e) => {
  const val = e.target.value;
  dom.fontSizeVal.textContent = val + 'px';
  dom.originalText.style.fontSize = val + 'px';
  dom.translatedText.style.fontSize = val + 'px';
});

// Opacity slider
dom.opacitySlider.addEventListener('input', (e) => {
  const val = e.target.value;
  dom.opacityVal.textContent = val + '%';
  dom.floatingWidget.style.opacity = val / 100;
});

// =====================================================
// KEYBOARD SHORTCUTS
// =====================================================
document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT') return;
  if (e.code === 'Space') { e.preventDefault(); dom.btnStart.click(); }
  if (e.code === 'KeyS') swapLanguages();
  if (e.code === 'KeyM') minimizeWidget();
  if (e.code === 'Escape') { if (state.isListening) stopListening(); }
});

// =====================================================
// CLOCK UPDATE (Phone notch time)
// =====================================================
function updateClock() {
  const now = new Date();
  const h = String(now.getHours()).padStart(2, '0');
  const m = String(now.getMinutes()).padStart(2, '0');
  document.querySelector('.notch-time').textContent = `${h}:${m}`;
}
updateClock();
setInterval(updateClock, 10000);

// =====================================================
// INIT
// =====================================================
function init() {
  // Initial placeholder state
  setStatus('idle', 'Chưa kết nối micro');
  setOriginalText('', false);
  dom.originalText.innerHTML = '<span class="placeholder-text">Đang chờ âm thanh...</span>';
  setTranslatedText('');

  // Widget visible by default
  state.floatingVisible = true;

  showToast('👋 Chào mừng! Nhấn "Bắt đầu dịch" để bắt đầu.', 4000);

  console.log(`
  ╔═══════════════════════════════════════╗
  ║  RealtimeTranslator Web Simulator     ║
  ║  Phím tắt:                            ║
  ║  [Space]  – Bật/Tắt dịch             ║
  ║  [S]      – Đảo chiều ngôn ngữ       ║
  ║  [M]      – Thu nhỏ widget           ║
  ║  [Esc]    – Dừng dịch                ║
  ╚═══════════════════════════════════════╝
  `);
}

init();
