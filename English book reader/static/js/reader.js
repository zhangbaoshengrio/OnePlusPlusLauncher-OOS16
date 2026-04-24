'use strict';

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────
const state = {
  book:           null,   // { filename, title, totalPages }
  currentPage:    0,
  vocabulary:     [],     // loaded from server
  selectedWord:   null,   // word currently shown in popup
  selectedCtx:    '',     // sentence context for selected word
  lookupCache:    new Map(),  // free-api cache: word -> data
  dicts:          [],     // installed dicts: [{id, name, name_short, enabled, builtin}]
  availableDicts: [],     // not-yet-installed dicts
  activeDictId:   null,   // currently visible tab id
  dictResults:    {},     // multi-dict results: word -> {dictId: data}
};

// ─────────────────────────────────────────────
// DOM refs
// ─────────────────────────────────────────────
const $ = id => document.getElementById(id);

const dom = {
  fileInput:       $('file-input'),
  welcome:         $('welcome'),
  dropZone:        $('drop-zone'),
  bookReader:      $('book-reader'),
  bookText:        $('book-text'),

  prevTop:         $('prev-top'),
  nextTop:         $('next-top'),
  prevBot:         $('prev-bot'),
  nextBot:         $('next-bot'),
  pageLabelTop:    $('page-label-top'),
  pageLabelBot:    $('page-label-bot'),

  navBookInfo:     $('nav-book-info'),
  navBookTitle:    $('nav-book-title'),
  navPageBadge:    $('nav-page-badge'),

  dictPopup:            $('dict-popup'),
  popupWord:            $('popup-word'),
  popupPhonetic:        $('popup-phonetic'),
  popupTabBar:          $('popup-tab-bar'),
  tabSettingsBtn:       $('tab-settings-btn'),
  popupBody:            $('popup-body'),
  popupClose:           $('popup-close'),
  addBtn:               $('add-btn'),
  dictSettingsDropdown: $('dict-settings-dropdown'),
  dsdList:              $('dsd-list'),
  dsdManageBtn:         $('dsd-manage-btn'),
  dictManageOverlay:    $('dict-manage-overlay'),
  dmmBody:              $('dmm-body'),
  dmmClose:             $('dmm-close'),

  wordList:        $('word-list'),
  emptyState:      $('empty-state'),
  vocabCount:      $('vocab-count'),

  pageLoader:      $('page-loader'),

  recentBtn:       $('recent-btn'),
  recentMenu:      $('recent-menu'),
};

// ─────────────────────────────────────────────
// Init
// ─────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  loadVocabulary();
  initDicts();
  setupUpload();
  setupPagination();
  setupWordClick();
  setupPopup();
  setupDictSettings();
  setupExports();
  setupRecentMenu();
  setupKeyboard();
});

// ─────────────────────────────────────────────
// Upload & drag-drop
// ─────────────────────────────────────────────
function setupUpload() {
  dom.fileInput.addEventListener('change', e => {
    const file = e.target.files[0];
    if (file) uploadFile(file);
    e.target.value = '';
  });

  const dz = dom.dropZone;
  dz.addEventListener('dragover', e => { e.preventDefault(); dz.classList.add('drag-over'); });
  dz.addEventListener('dragleave', () => dz.classList.remove('drag-over'));
  dz.addEventListener('drop', e => {
    e.preventDefault();
    dz.classList.remove('drag-over');
    const file = e.dataTransfer.files[0];
    if (file) uploadFile(file);
  });
}

async function uploadFile(file) {
  const ext = file.name.split('.').pop().toLowerCase();
  if (!['txt', 'epub'].includes(ext)) {
    alert('Only .txt and .epub files are supported.');
    return;
  }

  showLoader(true);
  const fd = new FormData();
  fd.append('file', file);

  try {
    const resp = await fetch('/upload', { method: 'POST', body: fd });
    const data = await resp.json();
    if (data.error) { alert(data.error); return; }

    state.book = { filename: data.filename, title: data.title, totalPages: data.total_pages };
    state.currentPage = 0;
    await loadPage(0);
    showReader();
    updateNavInfo();
  } catch (err) {
    alert('Upload failed: ' + err.message);
  } finally {
    showLoader(false);
  }
}

// ─────────────────────────────────────────────
// Recent books dropdown
// ─────────────────────────────────────────────
function setupRecentMenu() {
  if (!dom.recentBtn) return;

  dom.recentBtn.addEventListener('click', e => {
    e.stopPropagation();
    dom.recentMenu.classList.toggle('open');
  });

  document.addEventListener('click', () => dom.recentMenu.classList.remove('open'));

  dom.recentMenu.querySelectorAll('.dropdown-item').forEach(item => {
    item.addEventListener('click', () => {
      dom.recentMenu.classList.remove('open');
      loadRecentBook(item.dataset.filename);
    });
  });
}

async function loadRecentBook(filename) {
  showLoader(true);
  try {
    const resp = await fetch(`/api/page?book=${encodeURIComponent(filename)}&page=0`);
    const data = await resp.json();
    if (data.error) { alert(data.error); return; }

    const title = filename.replace(/\.(txt|epub)$/i, '').replace(/[_-]/g, ' ');
    state.book = { filename, title, totalPages: data.total_pages };
    state.currentPage = 0;
    renderPage(data);
    showReader();
    updateNavInfo();
  } catch (err) {
    alert('Failed to load book: ' + err.message);
  } finally {
    showLoader(false);
  }
}

// ─────────────────────────────────────────────
// Pagination
// ─────────────────────────────────────────────
function setupPagination() {
  dom.prevTop.addEventListener('click', () => changePage(-1));
  dom.nextTop.addEventListener('click', () => changePage( 1));
  dom.prevBot.addEventListener('click', () => changePage(-1));
  dom.nextBot.addEventListener('click', () => changePage( 1));
}

async function loadPage(page) {
  showLoader(true);
  try {
    const { filename } = state.book;
    const resp = await fetch(`/api/page?book=${encodeURIComponent(filename)}&page=${page}`);
    const data = await resp.json();
    if (data.error) throw new Error(data.error);
    renderPage(data);
  } catch (err) {
    alert('Failed to load page: ' + err.message);
  } finally {
    showLoader(false);
  }
}

function changePage(delta) {
  if (!state.book) return;
  const next = state.currentPage + delta;
  if (next < 0 || next >= state.book.totalPages) return;
  closePopup();
  loadPage(next);
}

function renderPage(data) {
  state.currentPage = data.page;
  state.book.totalPages = data.total_pages;

  dom.bookText.innerHTML = '';
  data.paragraphs.forEach(text => {
    if (!text.trim()) return;
    dom.bookText.appendChild(buildParagraph(text));
  });

  markVocabWords();
  updatePaginationUI();

  // Scroll reader back to top
  const ra = document.querySelector('.reader-area');
  if (ra) ra.scrollTop = 0;
}

function buildParagraph(text) {
  const p = document.createElement('p');
  p.className = 'book-para';

  // Split into word tokens and non-word tokens
  const tokens = text.split(/(\b[a-zA-Z]+(?:'[a-zA-Z]+)?\b)/);
  tokens.forEach(tok => {
    if (/^[a-zA-Z]/.test(tok)) {
      const span = document.createElement('span');
      span.className = 'word-token';
      span.textContent = tok;
      span.dataset.word = tok.toLowerCase();
      p.appendChild(span);
    } else {
      p.appendChild(document.createTextNode(tok));
    }
  });
  return p;
}

function updatePaginationUI() {
  const pg    = state.currentPage + 1;
  const total = state.book.totalPages;
  const label = `Page ${pg} / ${total}`;

  dom.pageLabelTop.textContent = label;
  dom.pageLabelBot.textContent = label;
  dom.navPageBadge.textContent = `[${pg}/${total}]`;

  dom.prevTop.disabled = state.currentPage === 0;
  dom.prevBot.disabled = state.currentPage === 0;
  dom.nextTop.disabled = state.currentPage >= total - 1;
  dom.nextBot.disabled = state.currentPage >= total - 1;
}

// ─────────────────────────────────────────────
// Dictionary initialisation
// ─────────────────────────────────────────────
async function initDicts() {
  try {
    const resp = await fetch('/api/dictionaries');
    const data = await resp.json();
    state.dicts          = data.installed  || [];
    state.availableDicts = data.available  || [];
    const firstEnabled   = state.dicts.find(d => d.enabled);
    state.activeDictId   = firstEnabled ? firstEnabled.id : (state.dicts[0]?.id || null);
    renderPopupTabs();
  } catch (e) {
    console.error('initDicts failed:', e);
  }
}

function renderPopupTabs() {
  // Remove all existing tab buttons (keep the gear btn)
  dom.popupTabBar.querySelectorAll('.dict-tab').forEach(t => t.remove());

  const enabled = state.dicts.filter(d => d.enabled);
  // Ensure activeDictId is among enabled
  if (!enabled.find(d => d.id === state.activeDictId)) {
    state.activeDictId = enabled[0]?.id || null;
  }

  enabled.forEach(dict => {
    const tab = document.createElement('button');
    tab.className = 'dict-tab' + (dict.id === state.activeDictId ? ' active' : '');
    tab.dataset.dictId = dict.id;
    tab.textContent    = dict.name_short || dict.name;
    tab.addEventListener('click', () => switchDictTab(dict.id));
    dom.popupTabBar.insertBefore(tab, dom.tabSettingsBtn);
  });
}

function switchDictTab(dictId) {
  state.activeDictId = dictId;
  dom.popupTabBar.querySelectorAll('.dict-tab').forEach(t =>
    t.classList.toggle('active', t.dataset.dictId === dictId));
  dom.popupBody.querySelectorAll('.dict-panel').forEach(p =>
    p.classList.toggle('active', p.dataset.dictId === dictId));

  // Update phonetic from this tab's result
  const key = state.selectedWord?.toLowerCase();
  if (key && state.dictResults[key]) {
    const res = state.dictResults[key][dictId];
    if (res?.phonetic) dom.popupPhonetic.textContent = res.phonetic;
  }
}

// ─────────────────────────────────────────────
// Word click → dictionary popup
// ─────────────────────────────────────────────
function setupWordClick() {
  dom.bookText.addEventListener('click', async e => {
    const span = e.target.closest('.word-token');
    if (!span) return;

    document.querySelectorAll('.word-token.active').forEach(s => s.classList.remove('active'));
    span.classList.add('active');

    const word = span.textContent;
    state.selectedWord = word;
    state.selectedCtx  = extractSentence(span.closest('p').textContent, word);

    showPopupLoading(word);
    positionPopup(span);
    await lookupAllDicts(word);
    updateAddBtn();
  });
}

function extractSentence(paraText, word) {
  const safe   = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match  = new RegExp(`\\b${safe}\\b`, 'i').exec(paraText);
  if (!match) return paraText.slice(0, 180).trim();

  const pos = match.index;
  let start = pos, end = pos + word.length;

  while (start > 0 && !/[.!?]/.test(paraText[start - 1])) start--;
  while (start < pos && /\s/.test(paraText[start])) start++;
  while (end < paraText.length && !/[.!?]/.test(paraText[end])) end++;
  if (end < paraText.length) end++;

  let sentence = paraText.slice(start, end).trim().replace(/\s+/g, ' ');

  if (sentence.length > 300) {
    const wi = sentence.toLowerCase().indexOf(word.toLowerCase());
    if (wi >= 0) {
      const s = Math.max(0, wi - 110);
      const e = Math.min(sentence.length, wi + word.length + 110);
      sentence = (s > 0 ? '…' : '') + sentence.slice(s, e) + (e < sentence.length ? '…' : '');
    } else {
      sentence = sentence.slice(0, 297) + '…';
    }
  }
  return sentence;
}

async function lookupAllDicts(word) {
  const key     = word.toLowerCase();
  const enabled = state.dicts.filter(d => d.enabled);
  if (!state.dictResults[key]) state.dictResults[key] = {};

  // Build one loading panel per enabled dict
  dom.popupBody.innerHTML = enabled.map(d => `
    <div class="dict-panel${d.id === state.activeDictId ? ' active' : ''}" data-dict-id="${d.id}">
      <div class="popup-loading">查询中…</div>
    </div>
  `).join('');

  // Fetch all dicts in parallel
  await Promise.allSettled(enabled.map(async dict => {
    // Use cached result if available
    if (state.dictResults[key][dict.id]) {
      const panel = dom.popupBody.querySelector(`[data-dict-id="${dict.id}"]`);
      if (panel) fillDictPanel(panel, state.dictResults[key][dict.id]);
      maybeUpdatePhonetic(dict.id, state.dictResults[key][dict.id]);
      return;
    }
    const data = await fetchDictSource(dict.id, key);
    state.dictResults[key][dict.id] = data;
    const panel = dom.popupBody.querySelector(`[data-dict-id="${dict.id}"]`);
    if (panel) fillDictPanel(panel, data);
    maybeUpdatePhonetic(dict.id, data);
  }));
}

async function fetchDictSource(dictId, word) {
  try {
    if (dictId === 'free-api') {
      if (state.lookupCache.has(word)) return state.lookupCache.get(word);
      const resp = await fetch(`/api/lookup/${encodeURIComponent(word)}`);
      const data = await resp.json();
      state.lookupCache.set(word, data);
      return data;
    }
    if (dictId === 'md-tea') {
      const resp = await fetch(`/api/lookup/md-tea/${encodeURIComponent(word)}`);
      return await resp.json();
    }
    return { word, found: false, phonetic: '', meanings: [] };
  } catch {
    return { word, found: false, phonetic: '', meanings: [] };
  }
}

function maybeUpdatePhonetic(dictId, data) {
  if (dictId === state.activeDictId && data?.phonetic) {
    dom.popupPhonetic.textContent = data.phonetic;
  } else if (!dom.popupPhonetic.textContent && data?.phonetic) {
    dom.popupPhonetic.textContent = data.phonetic;
  }
}

function fillDictPanel(panel, data) {
  if (!data?.found || !data.meanings?.length) {
    panel.innerHTML = '<div class="no-definition">No definition found.</div>';
  } else {
    panel.innerHTML = data.meanings.map(m => `
      <div class="meaning">
        <span class="pos-tag">${esc(m.pos)}</span>
        ${m.definitions.map(d => `
          <div class="def-item">
            <div class="def-text">${esc(d.definition)}</div>
            ${d.example ? `<div class="def-example">${esc(d.example)}</div>` : ''}
          </div>
        `).join('')}
      </div>
    `).join('');
  }
}

// ─────────────────────────────────────────────
// Popup management
// ─────────────────────────────────────────────
function setupPopup() {
  dom.popupClose.addEventListener('click', closePopup);
  dom.addBtn.addEventListener('click', addCurrentWordToVocab);

  document.addEventListener('click', e => {
    if (!dom.dictPopup.contains(e.target) &&
        !dom.dictSettingsDropdown.contains(e.target) &&
        !e.target.classList.contains('word-token')) {
      closePopup();
      closeSettingsDropdown();
    }
  });
}

function showPopupLoading(word) {
  dom.popupWord.textContent     = word;
  dom.popupPhonetic.textContent = '';
  dom.addBtn.textContent = '+ Add to Vocabulary';
  dom.addBtn.disabled    = false;
  dom.addBtn.className   = 'add-btn';
  dom.dictPopup.classList.add('visible');
  closeSettingsDropdown();
}

// ─────────────────────────────────────────────
// Dict settings dropdown
// ─────────────────────────────────────────────
function setupDictSettings() {
  dom.tabSettingsBtn.addEventListener('click', e => {
    e.stopPropagation();
    toggleSettingsDropdown();
  });

  dom.dsdManageBtn.addEventListener('click', () => {
    closeSettingsDropdown();
    openManageModal();
  });

  dom.dmmClose.addEventListener('click', closeManageModal);
  dom.dictManageOverlay.addEventListener('click', e => {
    if (e.target === dom.dictManageOverlay) closeManageModal();
  });
}

function toggleSettingsDropdown() {
  if (dom.dictSettingsDropdown.classList.contains('visible')) {
    closeSettingsDropdown();
  } else {
    openSettingsDropdown();
  }
}

function openSettingsDropdown() {
  renderSettingsDropdown();
  // Position the dropdown aligned to the right of the popup, below the tab bar
  const popupRect  = dom.dictPopup.getBoundingClientRect();
  const btnRect    = dom.tabSettingsBtn.getBoundingClientRect();
  dom.dictSettingsDropdown.style.top   = (btnRect.bottom + 6) + 'px';
  dom.dictSettingsDropdown.style.right = (window.innerWidth - popupRect.right) + 'px';
  dom.dictSettingsDropdown.classList.add('visible');
}

function closeSettingsDropdown() {
  dom.dictSettingsDropdown.classList.remove('visible');
}

function renderSettingsDropdown() {
  const enabledCount = state.dicts.filter(d => d.enabled).length;
  dom.dsdList.innerHTML = state.dicts.map(d => `
    <div class="dsd-item">
      <span class="dsd-name">${esc(d.name_short || d.name)}</span>
      <label class="toggle-switch">
        <input type="checkbox" data-dict-id="${d.id}"
          ${d.enabled ? 'checked' : ''}
          ${d.enabled && enabledCount <= 1 ? 'disabled' : ''}>
        <span class="toggle-track"></span>
      </label>
    </div>
  `).join('');

  dom.dsdList.querySelectorAll('input[type=checkbox]').forEach(cb => {
    cb.addEventListener('change', async () => {
      await toggleDict(cb.dataset.dictId);
    });
  });
}

async function toggleDict(dictId) {
  try {
    const resp = await fetch('/api/dictionaries/toggle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: dictId }),
    });
    const data = await resp.json();
    if (data.error) { alert(data.error); return; }

    const d = state.dicts.find(x => x.id === dictId);
    if (d) d.enabled = data.enabled;

    if (!data.enabled && state.activeDictId === dictId) {
      state.activeDictId = state.dicts.find(x => x.enabled)?.id || null;
    }
    renderPopupTabs();
    renderSettingsDropdown();
    // Refresh visible panels if popup is open
    if (state.selectedWord && dom.dictPopup.classList.contains('visible')) {
      await lookupAllDicts(state.selectedWord);
    }
  } catch (e) {
    console.error('toggleDict failed:', e);
  }
}

// ─────────────────────────────────────────────
// Dict management modal
// ─────────────────────────────────────────────
async function openManageModal() {
  await refreshDictList();
  renderManageModal();
  dom.dictManageOverlay.classList.add('visible');
}

function closeManageModal() {
  dom.dictManageOverlay.classList.remove('visible');
}

async function refreshDictList() {
  try {
    const resp = await fetch('/api/dictionaries');
    const data = await resp.json();
    state.dicts          = data.installed || [];
    state.availableDicts = data.available || [];
    if (!state.dicts.find(d => d.id === state.activeDictId && d.enabled)) {
      state.activeDictId = state.dicts.find(d => d.enabled)?.id || null;
    }
    renderPopupTabs();
  } catch (e) {
    console.error('refreshDictList failed:', e);
  }
}

function renderManageModal() {
  const available = state.availableDicts || [];
  dom.dmmBody.innerHTML = `
    <div class="dmm-section">
      <div class="dmm-section-title">已安装的词典</div>
      ${state.dicts.map(d => `
        <div class="dmm-dict-item">
          <div class="dmm-dict-info">
            <div class="dmm-dict-name">${esc(d.name)}</div>
            <div class="dmm-dict-desc">${esc(d.description || '')}</div>
          </div>
          <label class="toggle-switch">
            <input type="checkbox" class="dmm-toggle" data-dict-id="${d.id}"
              ${d.enabled ? 'checked' : ''}
              ${d.enabled && state.dicts.filter(x => x.enabled).length <= 1 ? 'disabled' : ''}>
            <span class="toggle-track"></span>
          </label>
        </div>
      `).join('')}
    </div>
    ${available.length ? `
      <div class="dmm-section">
        <div class="dmm-section-title">下载更多词典</div>
        ${available.map(d => `
          <div class="dmm-dict-item">
            <div class="dmm-dict-info">
              <div class="dmm-dict-name">${esc(d.name)}</div>
              <div class="dmm-dict-desc">${esc(d.description || '')}</div>
            </div>
            <button class="dmm-install-btn" data-dict-id="${d.id}">安装</button>
          </div>
        `).join('')}
      </div>
    ` : ''}
  `;

  dom.dmmBody.querySelectorAll('.dmm-toggle').forEach(cb => {
    cb.addEventListener('change', async () => {
      await toggleDict(cb.dataset.dictId);
      renderManageModal();
    });
  });

  dom.dmmBody.querySelectorAll('.dmm-install-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.textContent = '安装中…';
      btn.disabled    = true;
      await installDict(btn.dataset.dictId);
      renderManageModal();
    });
  });
}

async function installDict(dictId) {
  try {
    await fetch('/api/dictionaries/install', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: dictId }),
    });
    await refreshDictList();
  } catch (e) {
    console.error('installDict failed:', e);
  }
}

function positionPopup(targetEl) {
  const rect       = targetEl.getBoundingClientRect();
  const pw         = 360;
  const margin     = 12;
  const estHeight  = 320;

  let left = rect.left;
  let top  = rect.bottom + margin;

  if (left + pw > window.innerWidth - margin) left = window.innerWidth - pw - margin;
  left = Math.max(margin, left);

  if (top + estHeight > window.innerHeight - margin) {
    top = rect.top - estHeight - margin;
    if (top < margin) top = margin;
  }

  dom.dictPopup.style.left = left + 'px';
  dom.dictPopup.style.top  = top  + 'px';
}

function closePopup() {
  dom.dictPopup.classList.remove('visible');
  document.querySelectorAll('.word-token.active').forEach(s => s.classList.remove('active'));
  state.selectedWord = null;
  state.selectedCtx  = '';
}

function updateAddBtn() {
  if (!state.selectedWord) return;
  const exists = state.vocabulary.some(
    v => v.word.toLowerCase() === state.selectedWord.toLowerCase()
  );
  dom.addBtn.textContent = exists ? '✓ Already Added' : '+ Add to Vocabulary';
  dom.addBtn.disabled    = exists;
  dom.addBtn.className   = exists ? 'add-btn done' : 'add-btn';
}

// ─────────────────────────────────────────────
// Vocabulary
// ─────────────────────────────────────────────
async function addCurrentWordToVocab() {
  if (!state.selectedWord) return;

  // Read definition from the currently active dict panel
  const activePanel = dom.popupBody.querySelector(`.dict-panel[data-dict-id="${state.activeDictId}"]`)
                   || dom.popupBody;
  const posEl = activePanel.querySelector('.pos-tag');
  const defEl = activePanel.querySelector('.def-text');
  const definition = (posEl && defEl)
    ? `(${posEl.textContent}) ${defEl.textContent}`
    : (defEl ? defEl.textContent : '');

  try {
    const resp = await fetch('/api/vocab', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        word:       state.selectedWord,
        definition: definition,
        sentence:   state.selectedCtx,
        source:     state.book?.title || '',
      }),
    });
    const data = await resp.json();

    if (data.status === 'added') {
      dom.addBtn.textContent = '✓ Added!';
      dom.addBtn.className   = 'add-btn done';
      dom.addBtn.disabled    = true;
      await loadVocabulary();
    } else if (data.status === 'exists') {
      updateAddBtn();
    }
  } catch (err) {
    console.error('Add vocab failed:', err);
  }
}

async function loadVocabulary() {
  try {
    const resp = await fetch('/api/vocab');
    state.vocabulary = await resp.json();
    renderVocabulary();
  } catch (err) {
    console.error('Load vocab failed:', err);
  }
}

function renderVocabulary() {
  dom.vocabCount.textContent = state.vocabulary.length;

  if (state.vocabulary.length === 0) {
    dom.wordList.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">✏️</div>
        <p>No words saved yet</p>
        <p class="empty-hint">Click on words while reading to look them up</p>
      </div>`;
    markVocabWords();
    return;
  }

  dom.wordList.innerHTML = '';
  state.vocabulary.forEach(item => {
    const card = document.createElement('div');
    card.className = 'vocab-card';
    card.innerHTML = `
      <div class="vocab-card-top">
        <span class="vocab-word">${esc(item.word)}</span>
        <button class="vocab-del" data-id="${item.id}" title="Remove">×</button>
      </div>
      ${item.definition ? `<div class="vocab-def">${esc(item.definition)}</div>` : ''}
      ${item.sentence   ? `<div class="vocab-sentence">"${esc(trunc(item.sentence, 100))}"</div>` : ''}
      ${item.source     ? `<div class="vocab-source">— ${esc(item.source)}</div>` : ''}
    `;
    card.querySelector('.vocab-del').addEventListener('click', () => deleteVocabItem(item.id));
    dom.wordList.appendChild(card);
  });

  markVocabWords();
}

async function deleteVocabItem(id) {
  try {
    await fetch(`/api/vocab/${id}`, { method: 'DELETE' });
    await loadVocabulary();
  } catch (err) {
    console.error('Delete vocab failed:', err);
  }
}

function markVocabWords() {
  const set = new Set(state.vocabulary.map(v => v.word.toLowerCase()));
  document.querySelectorAll('.word-token').forEach(span => {
    span.classList.toggle('in-vocab', set.has(span.dataset.word));
  });
}

// ─────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────
function setupExports() {
  $('exp-apkg')      .addEventListener('click', () => doExport('apkg'));
  $('exp-pdf')       .addEventListener('click', () => doExport('pdf'));
  $('exp-txt-detail').addEventListener('click', () => doExport('txt_detailed'));
  $('exp-txt-words') .addEventListener('click', () => doExport('txt_words'));
}

function doExport(fmt) {
  if (state.vocabulary.length === 0) {
    alert('Vocabulary is empty — add some words first.');
    return;
  }
  window.location.href = `/api/export/${fmt}`;
}

// ─────────────────────────────────────────────
// Keyboard shortcuts
// ─────────────────────────────────────────────
function setupKeyboard() {
  document.addEventListener('keydown', e => {
    if (e.target.matches('input,textarea,select')) return;
    if (e.key === 'ArrowLeft')  changePage(-1);
    if (e.key === 'ArrowRight') changePage( 1);
    if (e.key === 'Escape')     closePopup();
  });
}

// ─────────────────────────────────────────────
// UI helpers
// ─────────────────────────────────────────────
function showReader() {
  dom.welcome.style.display    = 'none';
  dom.bookReader.style.display = 'block';
}

function updateNavInfo() {
  if (!state.book) return;
  dom.navBookInfo.style.display = 'flex';
  dom.navBookTitle.textContent  = state.book.title;
}

function showLoader(show) {
  dom.pageLoader.style.display = show ? 'flex' : 'none';
}

function esc(str) {
  if (!str) return '';
  const d = document.createElement('div');
  d.appendChild(document.createTextNode(str));
  return d.innerHTML;
}

function trunc(str, max) {
  return str.length <= max ? str : str.slice(0, max - 1) + '…';
}
