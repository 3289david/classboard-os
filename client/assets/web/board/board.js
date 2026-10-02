/* 중동중학교 전자칠판 - classroom board UI.
   Data: school data server (state + shared data), NEIS / Comcigan / school homepage via the server,
   and the device itself (Native). Every feature is shown as an app. */
(function () {
  'use strict';
  const C = window.CB;
  const $ = C.$, esc = C.esc;
  const N = window.Native || null;

  const S = {
    device: null,
    state: null,
    rev: -1,
    online: true,
    data: {},
    dataLoaded: false,
    sys: null,
    view: 'home',
    manualView: null,
    seg: { type: 'none' },
    segKey: '',
    mealPick: null,
    timer: { mode: 'period', running: false, endAt: 0, remain: 0, preset: 0 },
    panel: null,
    panelOpts: {},
    recent: [],
    pin: null,         // entered installer PIN while 관리 is unlocked
    pinUntil: 0,
    setSection: 'display',
    lastAlertAt: 0,
    seenPosts: null,
    booted: false,
  };

  // ---------------------------------------------------------------- utils
  function ic(name, cls) { return window.icon(name, cls); }
  function fillIcons(root) { C.$$('[data-ic]', root).forEach((el) => { el.outerHTML = ic(el.getAttribute('data-ic')); }); }
  function toast(msg, ms) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.remove('on');
    void t.offsetWidth;
    t.classList.add('on');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('on'), ms || 3500);
  }
  function native(fn) {
    if (!N) { toast('전자칠판 앱에서만 사용할 수 있는 기능입니다'); return null; }
    try {
      const r = N[fn].apply(N, Array.prototype.slice.call(arguments, 1));
      if (typeof r === 'string' && (r.charAt(0) === '{' || r.charAt(0) === '[')) {
        const o = JSON.parse(r);
        if (o && o.error) toast(o.error, 5000);
        return o;
      }
      return r;
    } catch (e) { toast(String(e.message || e)); return null; }
  }
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const cls = () => (S.device && S.device.cls) || '';
  const grade = () => { const c = cls(); return c ? Number(c.split('-')[0]) : 0; };
  const config = () => (S.state && S.state.config) || {};
  const settings = () => (S.device && S.device.settings) || {};
  function classLabel(c) { if (!c) return ''; const p = c.split('-'); return p[0] + '학년 ' + p[1] + '반'; }
  function validCls(g, c) { const G = Number(g), K = Number(c); return G >= 1 && G <= 6 && K >= 1 && K <= 30 && /^\d+$/.test(String(g)) && /^\d+$/.test(String(c)); }
  function pinHeaders() { return { token: '' }; }
  async function localPost(path, body) {
    const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Pin': S.pin || '' }, body: JSON.stringify(body || {}) });
    const txt = await res.text();
    let d = {};
    try { d = txt ? JSON.parse(txt) : {}; } catch (e) { d = {}; }
    if (!res.ok) { const e = new Error(d.error || ('HTTP ' + res.status)); e.status = res.status; throw e; }
    return d;
  }

  // ---------------------------------------------------------------- boot
  const bootAt = Date.now();
  function bootStatus(t) { const el = $('#boot-status'); if (el) el.textContent = t; }
  function bootDone() {
    if (bootDone.done) return;
    bootDone.done = true;
    setTimeout(() => {
      $('#boot').classList.add('done');
      const app = $('#app');
      app.classList.add('entering');
      setTimeout(() => app.classList.remove('entering'), 1800);
    }, Math.max(0, 2300 - (Date.now() - bootAt)));
  }

  async function boot() {
    fillIcons(document);
    $('#p-close').innerHTML = ic('x');
    $('#p-close').onclick = closePanel;
    try { const th = localStorage.getItem('cb_theme'); if (th) document.documentElement.dataset.theme = th; } catch (e) { /* ignore */ }
    try { S.recent = JSON.parse(localStorage.getItem('cb_recent') || '[]'); } catch (e) { S.recent = []; }
    tickClock();
    setInterval(tickClock, 1000);
    bootStatus('전자칠판 서비스 연결 중');
    for (;;) {
      try { S.device = await C.get('/api/local/device', pinHeaders()); break; } catch (e) { await sleep(800); }
    }
    applyLite();
    if (!S.device.setUp) { bootDone(); setupWizard(); return; }
    bootStatus('학교 데이터 불러오는 중');
    start();
  }

  // Low-end boards: no animations, shadows or blur. Auto-detected, can be forced in 설정 → 홈 화면.
  function perfInfo() {
    if (!N || !N.perf) return null;
    try { return JSON.parse(N.perf()); } catch (e) { return null; }
  }
  function liteAuto() {
    const p = perfInfo();
    return !!p && (p.lowRam || p.memClass < 256 || p.cores <= 4);
  }
  function applyLite() {
    const mode = (S.device && S.device.home && S.device.home.lite) || 'auto';
    const on = mode === 'on' || (mode === 'auto' && liteAuto());
    document.documentElement.classList.toggle('lite', on);
    S.lite = on;
  }

  function start() {
    if (S.booted) return;
    S.booted = true;
    renderNavKeys();
    renderDock();
    renderAppPages();
    initPages();
    $('#g-search').onclick = (ev) => openApp('browser', ev.currentTarget);
    stateLoop();
    loadData();
    pollSys();
    setInterval(loadData, 5 * 60 * 1000);
    setInterval(pollSys, 20000);
    setInterval(tick, 1000);
    // server link status comes from the device's sync client (the state long-poll only reports on change)
    const pollDevice = () => C.get('/api/local/device', pinHeaders()).then((d) => { S.device = d; if (d.sync) { S.online = !!d.sync.online; S.disconnected = !!d.sync.disconnected; } renderTop(); }).catch(() => {});
    setTimeout(pollDevice, 2500);
    setInterval(pollDevice, 15000);
    setTimeout(bootDone, 6000);
  }

  async function stateLoop() {
    for (;;) {
      try {
        const r = await C.get('/api/state?wait=1&since=' + S.rev, pinHeaders());
        S.online = r.online !== false && !r.offline;
        if (r.state) { S.state = r.state; S.rev = r.rev; renderAll(); if (S.dataLoaded) bootDone(); }
        else renderTop();
      } catch (e) {
        S.online = false;
        renderTop();
        await sleep(2000);
      }
    }
  }

  async function loadData() {
    if (!S.device) return;
    try { S.data = await C.get('/api/local/data?cls=' + encodeURIComponent(cls()), pinHeaders()); S.dataLoaded = true; } catch (e) { /* keep cache */ }
    renderAll();
    bootDone();
  }

  function pollSys() {
    if (!N) return;
    try { S.sys = JSON.parse(N.sys()); } catch (e) { S.sys = null; }
    renderTop();
    if (S.panel === 'settings') refreshSettingsLive();
  }

  window.onNative = function (ev, data) {
    switch (ev) {
      case 'data': loadData(); break;
      case 'device': C.get('/api/local/device', pinHeaders()).then((d) => { S.device = d; renderAll(); }); break;
      case 'segment': S.manualView = null; tick(true); break;
      case 'reset': goHome(); S.timer = { mode: 'period', running: false, endAt: 0, remain: 0, preset: 0 }; break;
      case 'back': navKey('back'); break;
      case 'home': goHome(); break;
      case 'recents': openRecents(); break;
      case 'resume': pollSys(); break;
      case 'sleep': $('#sleep').classList.add('on'); break;
      case 'perms': if (S.panel === 'settings') renderSettings(); pollSys(); break;
      case 'folder': if (S.panel === 'files') renderFiles(); break;
      case 'storage': onStorage(data || {}); break;
      case 'capture':
        if (!data.ok) toast(data.error || '캡처 실패', 5000);
        else if (data.kind === 'shot') toast('화면을 저장했습니다: ' + (data.where || ''));
        else if (data.kind === 'recording') { toast(data.mic ? '녹화를 시작했습니다 (마이크 포함)' : '녹화를 시작했습니다'); pollSys(); }
        else if (data.kind === 'recorded') { toast('녹화를 저장했습니다: ' + (data.where || '')); pollSys(); }
        break;
      case 'opened': if (!data.ok) toast('열기 실패: ' + data.error, 5000); break;
      default: break;
    }
  };
  document.addEventListener('pointerdown', () => { $('#sleep').classList.remove('on'); }, true);

  function renderAll() {
    if (!S.device) return;
    renderTop();
    tick(true);
    renderHome();
    renderLesson();
    renderBreak();
  }

  // ---------------------------------------------------------------- top bar
  let lastTime = '';
  function tickClock() {
    const d = new Date();
    const hh = C.pad(d.getHours()), mm = C.pad(d.getMinutes());
    if (hh + mm !== lastTime) {
      const prev = lastTime;
      lastTime = hh + mm;
      $('#t-time').textContent = hh + ':' + mm;
      // digits that changed roll in from below
      $('#g-time').innerHTML = (hh + mm).split('').map((ch, i) => (i === 2 ? '<span class="colon">:</span>' : '') + '<span class="' + (prev && prev[i] !== ch ? 'roll' : '') + '">' + ch + '</span>').join('');
    }
    $('#g-date').textContent = (d.getMonth() + 1) + '월 ' + d.getDate() + '일 ' + C.DOW[d.getDay()] + '요일';
  }

  function renderTop() {
    if (!S.device) return;
    const cfg = config();
    $('#t-school').textContent = cfg.displayName || (cfg.school && cfg.school.name) || '중동중학교';
    $('#t-class').textContent = cls() ? classLabel(cls()) : (S.device.name || '교실 미지정');
    const st = [];
    const ok = S.online && S.state;
    st.push('<span class="st' + (ok ? '' : ' bad') + '"><span class="dot"></span>' + (ok ? '학교 서버' : S.disconnected ? '서버에서 연결 해제됨' : '서버 연결 끊김') + '</span>');
    const net = S.sys && S.sys.network;
    if (net) {
      if (!net.connected) st.push('<span class="st bad">' + ic('wifiOff') + '네트워크 없음</span>');
      else if (net.transport === 'ethernet') st.push('<span class="st">' + ic('ethernet') + '유선</span>');
      else st.push('<span class="st' + (net.internet ? '' : ' bad') + '">' + ic('wifi') + esc(net.ssid || 'Wi-Fi') + '</span>');
    }
    if (S.sys && S.sys.device && S.sys.device.recording) st.push('<span class="st bad">' + ic('rec') + '녹화 중</span>');
    const html = st.join('');
    if ($('#t-status')._h !== html) { $('#t-status').innerHTML = html; $('#t-status')._h = html; }
    const w = S.data && S.data.weather && S.data.weather.current;
    const wh = w ? ic(window.weatherIcon(w.weather_code)) + '<b>' + Math.round(w.temperature_2m) + '°</b><span class="muted">' + esc(window.weatherText(w.weather_code)) + '</span>' : '';
    if ($('#t-weather')._h !== wh) { $('#t-weather').innerHTML = wh; $('#t-weather')._h = wh; }
    const air = S.data && S.data.weather && S.data.weather.air;
    const gw = w ? ic(window.weatherIcon(w.weather_code)) + Math.round(w.temperature_2m) + '° ' + esc(window.weatherText(w.weather_code)) +
      (air ? ' · 미세먼지 ' + (air.pm10 <= 30 ? '좋음' : air.pm10 <= 80 ? '보통' : air.pm10 <= 150 ? '나쁨' : '매우나쁨') : '') : '';
    if ($('#g-wx')._h !== gw) { $('#g-wx').innerHTML = gw; $('#g-wx')._h = gw; }
  }

  // ---------------------------------------------------------------- period logic
  function days() { return C.classDays(S.data && S.data.timetable); }
  function hasTimetable() { return !!(S.data && S.data.timetable); }
  function periodsOn(date) { return C.periodsFor(date, days(), S.state, cls()); }
  function slots() { return C.slots(config(), S.data && S.data.timetable && S.data.timetable.times); }
  function isWeekend(iso) { const d = C.parseIso(iso).getDay(); return d === 0 || d === 6; }
  function schoolDayToday() {
    const t = C.today();
    if (hasTimetable()) {
      const known = Object.keys(days());
      if (known.indexOf(t) >= 0) return periodsOn(t).some((e) => !e.cancel && e.s);
      if (known.length) return false;
    }
    return !isWeekend(t);
  }
  function dayEvent() {
    const now = C.nowMin();
    return (config().dayEvents || []).find((e) => { const a = C.parseHm(e.start), b = C.parseHm(e.end); return a >= 0 && now >= a && now < b; }) || null;
  }
  function computeSeg() {
    if (!schoolDayToday()) return { type: 'none' };
    const sl = slots();
    if (!sl.length) return { type: 'none' };
    return C.segment(sl, periodsOn(C.today()), C.nowMin());
  }
  function autoView() {
    const s = settings();
    if (S.seg.type === 'class' && s.autoLesson !== false) return 'lesson';
    if ((S.seg.type === 'break' || (S.seg.type === 'before' && S.seg.slot && S.seg.slot.start - C.nowMin() <= 15)) && s.autoBreak !== false) return 'break';
    return 'home';
  }
  function setView(v) {
    if (S.view === v && $('#v-' + v).classList.contains('on')) return;
    S.view = v;
    C.$$('.view').forEach((el) => el.classList.toggle('on', el.id === 'v-' + v));
  }
  function tick(force) {
    const seg = computeSeg();
    const key = seg.type + (seg.slot ? seg.slot.p : '');
    const changed = key !== S.segKey;
    S.seg = seg;
    if (changed) { S.segKey = key; S.manualView = null; if (seg.type === 'class') S.timer = { mode: 'period', running: false, endAt: 0, remain: 0, preset: 0 }; }
    const want = S.manualView || autoView();
    if (want !== S.view || changed) setView(want);
    if (changed && force !== true) { renderHome(); renderLesson(); renderBreak(); }
    // Low-end boards: only the countdowns change every second; everything else once a minute.
    const minute = Math.floor(C.nowMin());
    if (force === true || changed || minute !== S.lastMinute) {
      S.lastMinute = minute;
      renderNowCard();
      renderWidgets();
      renderLessonProgress();
      if (S.view === 'home') renderTimetable();
    }
    if (S.view === 'lesson') renderTimer();
    if (S.view === 'break') renderBreakHero();
  }

  // ---------------------------------------------------------------- home
  function renderHome() {
    renderNowCard();
    renderTimetable();
    renderNotices($('#c-notices'), true);
    renderMeals();
    renderTiles();
    renderWidgets();
  }

  function ring(frac, big, small) {
    const r = 44, c = 2 * Math.PI * r;
    const f = Math.max(0, Math.min(1, frac));
    return '<div class="ring"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="' + r + '" fill="none" stroke-width="8"/>' +
      '<circle cx="50" cy="50" r="' + r + '" fill="none" stroke-width="8" stroke-linecap="round" stroke-dasharray="' + c + '" stroke-dashoffset="' + (c * (1 - f)) + '"/></svg>' +
      '<div class="lbl"><b>' + big + '</b><span>' + small + '</span></div></div>';
  }
  function entryFor(p) { return periodsOn(C.today()).find((e) => e.p === p) || null; }
  function roomOf(e) { return (e && e.room) || ''; }
  // Comcigan marks substitutions; when only the teacher changed, say so
  function chLabel(e) {
    if (!e || !e.ch) return '';
    if (e.os && e.os !== e.s) return '시간표 변경 (원래 ' + e.os + ')';
    return '교사 변경' + (e.ot ? ' (원래 ' + e.ot + ' 선생님)' : '');
  }

  function renderNowCard() {
    const el = $('#c-now');
    if (!el || !S.device) return;
    const seg = S.seg;
    const nowM = C.nowMin();
    let html = '<h3>' + ic('clock') + '지금</h3><div class="swap">';
    if (!cls()) {
      html += '<div class="subj" style="font-size:2.6rem">학급이 지정되지 않았습니다</div><div class="meta">설정 앱 → 교실에서 학년과 반을 지정하세요.</div>';
    } else if (!S.dataLoaded) {
      html += '<div class="skel" style="height:5rem;width:60%"></div>';
    } else if (!hasTimetable() && !slots().length) {
      html += '<div class="subj" style="font-size:2.6rem">시간표를 불러오는 중입니다</div><div class="meta">학교 서버에서 컴시간 시간표를 받아 옵니다.</div>';
    } else if (seg.type === 'class') {
      const e = entryFor(seg.slot.p);
      const total = seg.slot.end - seg.slot.start, left = seg.slot.end - nowM;
      const nx = seg.next ? entryFor(seg.next.p) : null;
      html += '<div class="row">' + ring(left / total, Math.ceil(left) + '<small style="font-size:1.1rem">분</small>', '남음') + '<div style="min-width:0">' +
        '<div class="period">' + seg.slot.p + '교시 · ' + C.hm(seg.slot.start) + '~' + C.hm(seg.slot.end) + '</div>' +
        '<div class="subj">' + esc(e ? e.s || '수업' : '수업') + '</div><div class="meta">' +
        (e && e.t ? '<span>' + ic('user') + esc(e.t) + ' 선생님</span>' : '') + (roomOf(e) ? '<span>' + ic('door') + esc(roomOf(e)) + '</span>' : '') +
        (e && e.ch ? '<span>' + ic('info') + esc(chLabel(e)) + '</span>' : '') + '</div></div></div>';
      html += '<div class="next">' + ic('right') + (seg.next ? '<span>다음</span><b>' + seg.next.p + '교시 ' + esc(nx ? nx.s : '') + '</b><span style="opacity:.75">' + C.hm(seg.next.start) + (nx && roomOf(nx) ? ' · ' + esc(roomOf(nx)) : '') + '</span>' : '<span>오늘 마지막 수업입니다</span>') + '</div>';
    } else if (seg.type === 'break' || seg.type === 'before') {
      const e = entryFor(seg.slot.p);
      const left = seg.slot.start - nowM;
      const ev = dayEvent();
      html += '<div class="row">' + ring(seg.gap ? 1 - left / seg.gap : 0, Math.ceil(left) + '<small style="font-size:1.1rem">분</small>', '후 시작') + '<div style="min-width:0">' +
        '<div class="period">' + esc(ev ? ev.name : seg.type === 'before' ? '수업 전' : '쉬는 시간') + '</div>' +
        '<div class="subj">' + seg.slot.p + '교시 ' + esc(e ? e.s : '') + '</div><div class="meta">' +
        (e && e.t ? '<span>' + ic('user') + esc(e.t) + ' 선생님</span>' : '') + (roomOf(e) ? '<span>' + ic('door') + esc(roomOf(e)) + '</span>' : '') +
        '<span>' + ic('clock') + C.hm(seg.slot.start) + ' 시작</span></div></div></div>';
    } else if (seg.type === 'after') {
      html += '<div class="subj">오늘 수업 끝</div><div class="meta"><span>' + ic('check') + '마지막 ' + seg.last.p + '교시 ' + C.hm(seg.last.end) + ' 종료</span></div>';
    } else {
      html += '<div class="subj">오늘은 수업이 없습니다</div><div class="meta">' + esc(C.dateLabel(C.today())) + '</div>';
    }
    html += '</div>';
    const shape = html.replace(/stroke-dashoffset="[^"]*"/, '').replace(/<b>\d+<small/, '<b><small');
    if (el._shape === shape) {
      // same content: glide the ring and update the minutes in place
      const m = /stroke-dashoffset="([^"]*)"/.exec(html);
      const c = el.querySelector('.ring svg circle:last-child');
      if (m && c) c.setAttribute('stroke-dashoffset', m[1]);
      const n = /<b>(\d+)<small/.exec(html), b = el.querySelector('.ring .lbl b');
      if (n && b && b.firstChild && b.firstChild.nodeValue !== n[1]) b.firstChild.nodeValue = n[1];
      return;
    }
    el._shape = shape;
    el.innerHTML = html;
  }

  function nextSchoolDate(from) {
    const known = Object.keys(days()).sort();
    for (let i = 1; i <= 14; i++) {
      const d = C.addDays(from, i);
      if (known.indexOf(d) >= 0) { if (periodsOn(d).some((e) => e.s && !e.cancel)) return d; continue; }
      if (!known.length && !isWeekend(d)) return d;
    }
    return C.addDays(from, 1);
  }
  function ttSource() {
    const tt = S.data && S.data.timetable;
    const err = S.data && S.data.errors && (S.data.errors.comci || S.data.errors.timetable);
    return tt ? (tt.source === 'comcigan' ? '컴시간알리미' : 'NEIS') + ' 기준' + (tt.updated ? ' · 수정 ' + esc(tt.updated) : '') + (tt.fetchedAt ? ' · 갱신 ' + C.ago(tt.fetchedAt) : '') + (err ? ' · 최근 갱신 실패' : '') : '';
  }

  function renderTimetable() {
    const el = $('#c-tt');
    if (!el) return;
    el.onclick = (ev) => openPanel('timetable', {}, ev.currentTarget);
    let html;
    if (!S.dataLoaded) html = '<div class="skel"></div>'.repeat(6);
    else if (!cls()) html = '<div class="empty">' + ic('info') + '학급이 지정되지 않았습니다</div>';
    else if (!hasTimetable()) html = '<div class="empty">' + ic('info') + '시간표를 불러오는 중입니다</div>';
    else {
      const ps = periodsOn(C.today());
      if (!ps.length) html = '<div class="empty">' + ic('calendar') + '오늘은 수업이 없습니다 · 눌러서 다음 수업일 보기</div>';
      else {
        const sl = slots(), nowM = C.nowMin();
        html = ps.map((e) => {
          const s = sl.find((x) => x.p === e.p);
          let c = 'p';
          if (e.cancel) c += ' cancel';
          if (e.ch || e.ov) c += ' ch';
          if (S.seg.type === 'class' && S.seg.slot.p === e.p) c += ' cur';
          else if (s && nowM >= s.end) c += ' past';
          return '<div class="' + c + '"><div class="n">' + e.p + '교시</div><div class="s">' + esc(e.s || '-') + '</div><div class="t">' + esc(e.t || (s ? C.hm(s.start) : '')) + '</div></div>';
        }).join('');
      }
    }
    if (el._h !== html) { el.innerHTML = html; el._h = html; }
  }

  function renderTimetableApp() {
    const body = $('#p-body');
    if (!hasTimetable()) { body.innerHTML = '<div class="empty">' + ic('info') + '시간표를 불러오는 중입니다</div>'; return; }
    const sl = slots();
    const nowM = C.nowMin();
    const today = C.today();
    const next = nextSchoolDate(today);
    const isToday = (d) => d === today;
    const cellCls = (date, e) => {
      const s = sl.find((x) => x.p === e.p);
      let c = e.cancel ? ' cancel' : '';
      if (isToday(date) && S.seg.type === 'class' && S.seg.slot.p === e.p) c += ' cur';
      else if (isToday(date) && s && nowM >= s.end) c += ' past';
      return c;
    };
    // today, large
    const tp = periodsOn(today);
    const big = tp.length ? '<div class="tt-big">' + tp.map((e) => {
      const s = sl.find((x) => x.p === e.p);
      return '<div class="tb' + cellCls(today, e) + '"><span class="n">' + e.p + '교시</span><span class="tm">' + (s ? C.hm(s.start) + ' ~ ' + C.hm(s.end) : '') + '</span>' +
        '<b>' + esc(e.s || '-') + '</b><span class="t">' + esc([e.t ? e.t + ' 선생님' : '', roomOf(e)].filter(Boolean).join(' · ')) + '</span>' +
        (e.ch ? '<span class="chip orange">' + esc(chLabel(e)) + '</span>' : '') + '</div>';
    }).join('') + '</div>' : '<div class="empty">' + ic('calendar') + '오늘은 수업이 없습니다</div>';
    // next school day, small
    const np = periodsOn(next);
    const small = np.length ? '<div class="tt-small">' + np.map((e) => '<div class="ts' + (e.cancel ? ' cancel' : '') + '"><span class="n">' + e.p + '</span><b>' + esc(e.s || '-') + '</b>' +
      (e.ch ? '<i class="dot"></i>' : '') + '</div>').join('') + '</div>' : '<div class="empty">수업이 없는 날입니다</div>';
    // this school week (Mon-Fri); on weekends the coming week
    const dow = C.parseIso(today).getDay();
    const monday = C.addDays(today, dow === 0 ? 1 : dow === 6 ? 2 : 1 - dow);
    const week = [0, 1, 2, 3, 4].map((i) => C.addDays(monday, i));
    const rows = Math.max(0, ...week.map((d) => periodsOn(d).reduce((m, e) => Math.max(m, e.p), 0)));
    let grid = '<div class="tt-week" style="grid-template-columns:4.6rem repeat(5,1fr)"><div></div>' +
      week.map((d) => '<div class="wh' + (isToday(d) ? ' today' : '') + '">' + C.DOW[C.parseIso(d).getDay()] + '<small>' + C.shortDate(d).split('(')[0] + '</small></div>').join('');
    for (let p = 1; p <= rows; p++) {
      const s = sl.find((x) => x.p === p);
      grid += '<div class="wp">' + p + '<small>' + (s ? C.hm(s.start) : '') + '</small></div>';
      week.forEach((d) => {
        const e = periodsOn(d).find((x) => x.p === p);
        grid += e ? '<div class="wc' + cellCls(d, e) + (isToday(d) ? ' today' : '') + (e.ch ? ' ch' : '') + '"><b>' + esc(e.s || '-') + '</b><small>' + esc(e.t || '') + '</small></div>'
          : '<div class="wc empty-c' + (isToday(d) ? ' today' : '') + '"></div>';
      });
    }
    grid += '</div>';
    if (!rows) grid = '<div class="empty">' + ic('calendar') + '이번 주 시간표가 없습니다</div>';
    body.innerHTML = '<div class="tt-app"><div class="tt-left"><div class="section-t">' + ic('clock') + '오늘 · ' + C.dateLabel(today) + '</div>' + big +
      '<div class="section-t" style="margin-top:1.6rem">' + ic('right') + (next === C.addDays(today, 1) ? '내일' : '다음 수업일') + ' · ' + C.dateLabel(next) + '</div>' + small + '</div>' +
      '<div class="tt-right"><div class="section-t">' + ic('grid') + '이번 주</div>' + grid + '<div class="src">' + ttSource() + '</div></div></div>';
  }


  // school homepage posts (가정통신문 · 공지사항 · 영양 소식)
  function homepageBoards() {
    const hp = S.data && S.data.homepage;
    const rank = (n) => (n === '가정통신문' ? 0 : n.indexOf('가정통신문') >= 0 ? 1 : 2);
    const recent = C.addDays(C.today(), -60);
    return ((hp && hp.boards) || []).slice().sort((a, b) => rank(a.name) - rank(b.name)).map((b) => Object.assign({}, b, {
      items: (b.items || []).slice().sort((x, y) => ((y.pinned && y.date >= recent) - (x.pinned && x.date >= recent)) || (x.date < y.date ? 1 : x.date > y.date ? -1 : 0)),
    }));
  }
  function homepageLatest(n) {
    if (S.data && S.data.latest && S.data.latest.length) return S.data.latest.slice(0, n);
    const cutoff = C.addDays(C.today(), -30);
    const all = [];
    homepageBoards().forEach((b) => (b.items || []).forEach((it) => {
      if (it.pinned && it.date < cutoff) return;
      all.push(Object.assign({ board: b.name, menuId: b.menuId }, it));
    }));
    all.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
    return all.slice(0, n);
  }
  function hpRow(it) {
    const isNew = it.date >= C.addDays(C.today(), -2);
    return '<div class="notice' + (isNew ? ' new' : '') + '" data-hp="' + esc(it.menuId + '|' + it.bbsId + '|' + it.nttId + '|' + (it.sen ? 1 : 0)) + '"><div class="t">' + (it.pinned ? ic('pinned') : '') + esc(it.title) + '</div>' +
      '<div class="b">' + esc(it.board || '') + ' · ' + esc(it.date) + (it.file ? ' · 첨부파일' : '') + '</div></div>';
  }
  function bindHp(el) {
    C.$$('[data-hp]', el).forEach((n) => {
      n.onclick = (ev) => { const p = n.dataset.hp.split('|'); openPanel('post', { menuId: p[0], bbsId: p[1], nttId: p[2], sen: p[3] }, ev.currentTarget); };
    });
  }
  function renderNotices(el, compact) {
    if (!el) return;
    let html;
    if (!S.dataLoaded) html = '<div class="skel-lines"><div class="skel"></div><div class="skel"></div><div class="skel"></div></div>';
    else {
      const items = homepageLatest(el.id === 'c-notices' ? 6 : compact ? 2 : 20);
      const err = S.data.errors && S.data.errors.homepage;
      html = items.length ? items.map(hpRow).join('')
        : '<div class="empty">' + ic('file') + (err ? '학교 홈페이지를 불러오지 못했습니다' : '학교 홈페이지에서 불러오는 중입니다') + '</div>';
    }
    if (el._h === html) return;
    el._h = html;
    el.innerHTML = html;
    bindHp(el);
    if (el.id === 'c-notices') $('#n-head').onclick = (ev) => openPanel('notices', {}, ev.currentTarget);
  }
  function renderNoticesApp() {
    const body = $('#p-body');
    const boards = homepageBoards();
    const err = S.data && S.data.errors && S.data.errors.homepage;
    if (!boards.length) { body.innerHTML = '<div class="empty">' + ic('info') + (err ? esc(err.message) : '학교 홈페이지에서 불러오는 중입니다') + '</div>'; return; }
    const pick = S.panelOpts.menuId || boards[0].menuId;
    const b = boards.find((x) => x.menuId === pick) || boards[0];
    $('#p-tools').innerHTML = '<div class="tabs">' + boards.map((x) => '<button class="' + (x === b ? 'on' : '') + '" data-mid="' + esc(x.menuId) + '">' + esc(x.name) + '</button>').join('') + '</div>';
    C.$$('[data-mid]').forEach((x) => { x.onclick = () => { S.panelOpts.menuId = x.dataset.mid; renderNoticesApp(); }; });
    body.innerHTML = '<div>' + (b.items || []).map((it) => hpRow(Object.assign({ board: b.name, menuId: b.menuId }, it))).join('') +
      '<div class="src">' + esc(b.name) + ' 전체 ' + (b.total || 0) + '건 · 최근 ' + (b.items || []).length + '건 · ' + C.ago(S.data.homepage.fetchedAt) + ' 갱신</div></div>';
    bindHp(body);
  }
  async function renderPostApp() {
    const o = S.panelOpts;
    const body = $('#p-body');
    $('#p-tools').innerHTML = '<button class="btn sm" id="hp-back">' + ic('left') + '목록</button>';
    $('#hp-back').onclick = () => openPanel('notices', { menuId: o.menuId });
    const it = homepageBoards().reduce((f, b) => f || (b.items || []).find((x) => x.nttId === o.nttId), null);
    body.innerHTML = '<div class="dots-loader"><i></i><i></i><i></i>학교 홈페이지에서 불러오는 중</div>';
    try {
      const d = await C.get('/api/homepage/detail?menuId=' + encodeURIComponent(o.menuId) + '&bbsId=' + encodeURIComponent(o.bbsId) + '&nttId=' + encodeURIComponent(o.nttId) + '&sen=' + (o.sen === '1' ? 1 : 0), pinHeaders());
      if (S.panel !== 'post' || S.panelOpts !== o) return;
      body.innerHTML = '<div><h1 style="font-size:2.6rem;margin-bottom:.6rem;letter-spacing:-.02em">' + esc(d.title || (it && it.title) || '') + '</h1>' +
        '<p class="muted" style="margin-bottom:1.4rem">' + esc(it ? it.date + ' · ' + it.author : '') + '</p>' +
        (d.files.length ? '<div class="mats" style="margin-bottom:1.4rem">' + d.files.map((f, i) => '<button class="mat" data-f="' + i + '">' + ic(fileIcon(f.name)) + esc(f.name) + ' <span class="dim">' + C.bytes(f.size) + '</span></button>').join('') + '</div>' : '') +
        (d.body ? '<div style="font-size:1.5rem;line-height:1.65;white-space:pre-wrap">' + esc(d.body) + '</div>' : '') +
        d.images.map((src) => '<img src="' + esc(src) + '" alt="" style="max-width:100%;margin-top:1rem;border-radius:.8rem;background:#fff">').join('') +
        '<div id="post-doc"></div></div>';
      const docFile = d.files.find((f) => DOC_EXT.test(f.name));
      if (docFile) inlineDoc(docFile, o);
      C.$$('[data-f]', body).forEach((b) => {
        b.onclick = (ev) => {
          const f = d.files[Number(b.dataset.f)];
          if (DOC_EXT.test(f.name)) { openPanel('doc', { url: f.url, name: f.name, back: { app: 'post', opts: o } }, ev.currentTarget); return; }
          toast('"' + f.name + '" 여는 중...');
          native('openWebFile', f.url, f.name);
        };
      });
    } catch (e) {
      if (S.panel !== 'post' || S.panelOpts !== o) return;
      body.innerHTML = '<div class="empty" style="flex-direction:column;align-items:flex-start;gap:1rem">' + esc(e.message) + '<button class="btn pri" id="hp-retry">' + ic('refresh') + '다시 시도</button></div>';
      $('#hp-retry').onclick = () => renderPostApp();
    }
  }
  function fileIcon(name) {
    const n = (name || '').toLowerCase();
    if (/\.pdf$/.test(n)) return 'pdf';
    if (/\.(ppt|pptx|key|odp)$/.test(n)) return 'slides';
    if (/\.(png|jpe?g|gif|webp|bmp)$/.test(n)) return 'image';
    if (/\.(mp4|mov|avi|mkv|webm)$/.test(n)) return 'video';
    return 'file';
  }

  // meals
  function mealDay() {
    const m = S.data && S.data.meals;
    const h = new Date().getHours();
    let date = C.today();
    let list = (m && m.days && m.days[date]) || [];
    const lastEnd = list.length ? list[list.length - 1].code : 0;
    if (!list.length || (h >= 14 && lastEnd <= 2) || h >= 19) {
      for (let i = 1; i <= 7; i++) { const d = C.addDays(C.today(), i); if (m && m.days && m.days[d] && m.days[d].length) { if (h >= 14 || !list.length) { date = d; list = m.days[d]; } break; } }
    }
    return { m, date, list, h };
  }
  function renderMeals() {
    const el = $('#c-meal');
    const md = mealDay();
    $('#meal-title').textContent = '급식 · ' + (md.date === C.today() ? '오늘' : C.shortDate(md.date));
    const tabs = $('#meal-tabs');
    let html, th = '';
    if (!S.dataLoaded) html = '<div class="skel-lines"><div class="skel"></div><div class="skel"></div></div>';
    else if (!md.list.length) html = '<div class="empty">' + ic('meal') + (S.data.errors && S.data.errors.meals ? '급식 정보를 불러오지 못했습니다' : '등록된 급식이 없습니다') + '</div>';
    else {
      let pick = md.list.find((x) => x.type === S.mealPick);
      if (!pick) {
        if (md.date === C.today()) pick = (md.h < 9 && md.list.find((x) => x.code === 1)) || (md.h < 14 && md.list.find((x) => x.code === 2)) || md.list.find((x) => x.code === 3) || md.list[0];
        else pick = md.list.find((x) => x.code === 2) || md.list[0];
      }
      th = md.list.length > 1 ? md.list.map((x) => '<button class="' + (x === pick ? 'on' : '') + '" data-t="' + esc(x.type) + '">' + esc(x.type) + '</button>').join('') : '<span class="chip">' + esc(pick.type) + '</span>';
      html = '<div class="dishes">' + pick.dishes.map((d) => esc(d.name) + (d.al && d.al.length ? '<span class="al">' + d.al.join('.') + '</span>' : '')).join(' · ') + '</div>';
    }
    if (tabs._h !== th) { tabs.innerHTML = th; tabs._h = th; C.$$('button', tabs).forEach((b) => { b.onclick = (ev) => { ev.stopPropagation(); S.mealPick = b.dataset.t; renderMeals(); }; }); }
    if (el._h !== html) { el.innerHTML = html; el._h = html; }
    el.onclick = (ev) => openPanel('meal', {}, ev.currentTarget);
  }
  function renderMealApp() {
    const md = mealDay();
    const body = $('#p-body');
    if (!md.list.length) { body.innerHTML = '<div class="empty">' + ic('meal') + '등록된 급식이 없습니다</div>'; return; }
    body.innerHTML = '<div><div class="section-t">' + ic('meal') + C.dateLabel(md.date) + '</div><div style="display:grid;grid-template-columns:repeat(' + md.list.length + ',1fr);gap:1.6rem">' + md.list.map((x) => {
      const used = {};
      return '<div class="card meal"><h3>' + esc(x.type) + '<span class="right dim" style="font-weight:500">' + esc(x.kcal || '') + '</span></h3><ul style="list-style:none">' +
        x.dishes.map((d) => { (d.al || []).forEach((a) => { used[a] = true; }); return '<li style="font-size:1.5rem;font-weight:600;padding:.25rem 0">' + esc(d.name) + (d.al && d.al.length ? ' <span style="font-size:.9rem;color:var(--orange)">' + d.al.join('.') + '</span>' : '') + '</li>'; }).join('') + '</ul>' +
        (Object.keys(used).length ? '<div class="allergy-legend">알레르기: ' + Object.keys(used).map((k) => k + '.' + (C.ALLERGENS[k] || '')).join('  ') + '</div>' : '') + '</div>';
    }).join('') + '</div></div>';
  }

  // exams & schedule
  // calendar events merged and classified by the school server; computed here only for old cached data
  function allEvents() {
    const ev = S.data && S.data.events && S.data.events.items;
    if (!ev) return C.events(S.data, S.state, grade());
    const g = grade();
    return ev.filter((e) => !g || !e.grades || !e.grades.length || e.grades.indexOf(g) >= 0);
  }
  function examItems() {
    const out = [];
    allEvents().forEach((e) => { if (e.kind === '시험' && (e.endDate || e.date) >= C.today()) out.push({ date: e.date, endDate: e.endDate, name: e.name }); });
    const seen = {};
    return out.filter((x) => { const k = x.date + x.name; if (seen[k]) return false; seen[k] = true; return true; });
  }
  function examDday(e) { return e.date < C.today() ? '진행 중' : C.dday(e.date); }
  function upcomingEvents() {
    return allEvents().filter((e) => (e.endDate || e.date) >= C.today() && e.kind !== '시험');
  }
  function mini(el, icon, head, value, sub, red) {
    const h = '<span class="h">' + ic(icon) + esc(head) + '</span><span class="v' + (red ? ' red' : '') + '">' + value + '</span><span class="d">' + sub + '</span>';
    if (el._h !== h) { el.innerHTML = h; el._h = h; }
  }
  function renderTiles() {
    const ex = examItems();
    if (ex.length) mini($('#c-exam'), 'exam', '시험', examDday(ex[0]), esc(ex[0].name) + ' · ' + C.rangeLabel(ex[0]), true);
    else mini($('#c-exam'), 'exam', '시험', S.dataLoaded ? '없음' : '-', '예정된 시험 없음');
    $('#c-exam').onclick = (ev) => openPanel('calendar', {}, ev.currentTarget);
    const w = S.data && S.data.weather, air = w && w.air;
    if (w && w.current) {
      const pm = air ? Math.round(air.pm10) : null;
      const pmTxt = pm == null ? '' : '미세먼지 ' + pm + ' ' + (pm <= 30 ? '좋음' : pm <= 80 ? '보통' : pm <= 150 ? '나쁨' : '매우나쁨');
      mini($('#c-wx'), window.weatherIcon(w.current.weather_code), '날씨', Math.round(w.current.temperature_2m) + '° ' + esc(window.weatherText(w.current.weather_code)), pmTxt);
    } else mini($('#c-wx'), 'wSun', '날씨', '-', '불러오는 중');
    $('#c-wx').onclick = (ev) => openPanel('weather', {}, ev.currentTarget);
    const ne = upcomingEvents()[0];
    if (ne) mini($('#c-next'), 'calendar', '다음 일정', esc(ne.name), C.shortDate(ne.date) + ' · ' + C.dday(ne.date));
    else mini($('#c-next'), 'calendar', '다음 일정', S.dataLoaded ? '없음' : '-', '학사일정');
    $('#c-next').onclick = (ev) => openPanel('calendar', {}, ev.currentTarget);
  }
  function renderCalendarApp() {
    const ex = examItems();
    const ev = upcomingEvents();
    $('#p-body').innerHTML = '<div style="display:grid;grid-template-columns:1fr 1.3fr;gap:2.4rem"><div><div class="section-t">' + ic('exam') + '시험</div>' +
      (ex.length ? ex.map((e) => '<div class="list-row"><b style="font-size:1.9rem;color:var(--red);min-width:7rem">' + examDday(e) + '</b><div class="grow"><b style="font-size:1.4rem">' + esc(e.name) + '</b><span>' + C.rangeLabel(e) + '</span></div></div>').join('') : '<div class="empty">예정된 시험이 없습니다</div>') +
      '</div><div><div class="section-t">' + ic('calendar') + '학사 일정</div>' +
      (ev.length ? ev.slice(0, 40).map((e) => '<div class="ev"><span class="dt">' + C.rangeLabel(e) + '</span><span class="nm">' + esc(e.name) + '</span><span class="chip ' + (C.KIND_COLOR[e.kind] || 'gray') + '">' + esc(e.kind) + '</span></div>').join('') : '<div class="empty">예정된 일정이 없습니다</div>') +
      '<div class="src">NEIS 학사일정</div></div></div>';
  }

  // weather
  function weatherHtml(big) {
    const w = S.data && S.data.weather;
    if (!w || !w.current) return '<div class="empty">' + ic('info') + '날씨를 불러오는 중입니다</div>';
    const c = w.current, d = w.daily || {};
    let days2 = '';
    (d.time || []).slice(0, 3).forEach((t, i) => {
      days2 += '<div>' + (i === 0 ? '오늘' : C.shortDate(t)) + ic(window.weatherIcon(d.weather_code[i])) + Math.round(d.temperature_2m_min[i]) + '° / ' + Math.round(d.temperature_2m_max[i]) + '°' +
        (d.precipitation_probability_max && d.precipitation_probability_max[i] != null ? '<div class="dim">강수 ' + d.precipitation_probability_max[i] + '%</div>' : '') + '</div>';
    });
    const air = w.air;
    const g10 = (v) => v <= 30 ? ['좋음', 'green'] : v <= 80 ? ['보통', 'blue'] : v <= 150 ? ['나쁨', 'orange'] : ['매우나쁨', 'red'];
    const g25 = (v) => v <= 15 ? ['좋음', 'green'] : v <= 35 ? ['보통', 'blue'] : v <= 75 ? ['나쁨', 'orange'] : ['매우나쁨', 'red'];
    let airHtml = '';
    if (air) {
      const a = g10(air.pm10), b = g25(air.pm2_5);
      airHtml = '<div class="air"><span class="chip ' + a[1] + '">미세먼지 ' + Math.round(air.pm10) + ' ' + a[0] + '</span><span class="chip ' + b[1] + '">초미세먼지 ' + Math.round(air.pm2_5) + ' ' + b[0] + '</span></div>';
    }
    return '<div class="wx-big"' + (big ? ' style="gap:2rem"' : '') + '>' + ic(window.weatherIcon(c.weather_code)) + '<div><b' + (big ? ' style="font-size:5rem"' : '') + '>' + Math.round(c.temperature_2m) + '°</b><div class="muted"' + (big ? ' style="font-size:1.4rem"' : '') + '>' + esc(window.weatherText(c.weather_code)) +
      ' · 체감 ' + Math.round(c.apparent_temperature) + '° · 습도 ' + c.relative_humidity_2m + '% · 바람 ' + Math.round(c.wind_speed_10m) + 'km/h</div></div></div><div class="wx-days">' + days2 + '</div>' + airHtml +
      '<div class="src">Open-Meteo · ' + esc(config().locationName || '') + ' · ' + C.ago(w.fetchedAt) + ' 갱신</div>';
  }
  function renderWeatherApp() { $('#p-body').innerHTML = '<div style="max-width:70rem">' + weatherHtml(true) + '</div>'; }

  // ---------------------------------------------------------------- lesson view
  function renderLesson() {
    const el = $('#l-main');
    if (!el) return;
    const seg = S.seg;
    const p = seg.type === 'class' ? seg.slot.p : seg.slot ? seg.slot.p : 0;
    const e = p ? entryFor(p) : null;
    let html;
    if (!p) html = '<div class="p">수업</div><div class="s">' + (seg.type === 'after' ? '오늘 수업 끝' : '수업 시간이 아닙니다') + '</div><div class="t">' + esc(C.dateLabel(C.today())) + '</div>';
    else {
      html = '<div class="p">' + p + '교시' + (seg.type === 'class' ? '' : ' · 곧 시작') + '</div><div class="s">' + esc(e ? e.s || '수업' : '수업') + '</div>' +
        (e && e.t ? '<div class="t">' + esc(e.t) + ' 선생님</div>' : '') +
        '<div class="facts">' + (seg.slot ? '<span class="chip">' + ic('clock') + C.hm(seg.slot.start) + ' ~ ' + C.hm(seg.slot.end) + '</span>' : '') +
        (roomOf(e) ? '<span class="chip">' + ic('door') + esc(roomOf(e)) + '</span>' : '') + (e && e.ch ? '<span class="chip orange">' + ic('info') + esc(chLabel(e)) + '</span>' : '') + '</div>' +
        (seg.type === 'class' ? '<div class="progress"><i id="l-prog"></i></div>' : '');
    }
    if (el._h !== html) { el.innerHTML = html; el._h = html; }
    renderLessonProgress();
    const tt = $('#l-tt');
    const ps = periodsOn(C.today()), sl = slots(), nowM = C.nowMin();
    const th = ps.length ? '<div class="tt">' + ps.map((x) => {
      const s = sl.find((y) => y.p === x.p);
      let c = 'p' + (x.cancel ? ' cancel' : '');
      if (seg.type === 'class' && seg.slot.p === x.p) c += ' cur'; else if (s && nowM >= s.end) c += ' past';
      return '<div class="' + c + '"><div class="n">' + x.p + '<small>' + (s ? C.hm(s.start) : '') + '</small></div><div class="s">' + esc(x.s || '-') + '<small>' + esc(x.t || '') + '</small></div></div>';
    }).join('') + '</div>' : '<div class="empty">오늘은 수업이 없습니다</div>';
    if (tt._h !== th) { tt.innerHTML = th; tt._h = th; }
  }
  function renderLessonProgress() {
    const bar = $('#l-prog');
    if (!bar || S.seg.type !== 'class') return;
    const s = S.seg.slot;
    bar.style.width = Math.max(0, Math.min(100, (C.nowMin() - s.start) / (s.end - s.start) * 100)) + '%';
  }
  function renderTimer() {
    const el = $('#l-timer');
    if (!el || S.view !== 'lesson') return;
    const t = S.timer;
    let secs, label;
    if (t.mode === 'custom') { secs = t.running ? Math.round((t.endAt - Date.now()) / 1000) : t.remain; label = '수업 타이머'; }
    else if (S.seg.type === 'class') { secs = Math.round((S.seg.slot.end - C.nowMin()) * 60); label = '수업 종료까지'; }
    else { secs = null; label = '수업 타이머'; }
    if (t.mode === 'custom' && t.running && secs <= 0 && !t.rang) { t.rang = true; if (N) N.chime('soft'); }
    const abs = Math.abs(secs || 0);
    const txt = secs == null ? '--:--' : (secs < 0 ? '-' : '') + C.pad(Math.floor(abs / 60)) + ':' + C.pad(abs % 60);
    const cls2 = secs != null && secs < 0 ? 'over' : secs != null && secs <= 300 ? 'warn' : '';
    const sig = t.mode + t.running;
    if (el.dataset.sig !== sig) {
      el.dataset.sig = sig;
      el.innerHTML = '<div class="muted" style="font-size:1.3rem;font-weight:700" id="tm-l"></div><div class="big" id="tm-b"></div><div class="ctrl">' +
        (t.mode === 'custom' ? '<button class="btn pri" data-t="toggle">' + ic(t.running ? 'pause' : 'play') + (t.running ? '일시정지' : '시작') + '</button><button class="btn" data-t="reset">' + ic('reset') + '초기화</button><button class="btn" data-t="period">' + ic('clock') + '교시 시간</button>'
          : '<button class="btn" data-t="m5">5분</button><button class="btn" data-t="m10">10분</button><button class="btn" data-t="m15">15분</button><button class="btn" data-t="m45">' + C.defaultMinutes(config()) + '분</button>') + '</div>';
      C.$$('[data-t]', el).forEach((b) => { b.onclick = () => timerAction(b.dataset.t); });
    }
    $('#tm-l').textContent = label;
    const b = $('#tm-b');
    b.textContent = txt;
    b.className = 'big ' + cls2;
  }
  function timerAction(a) {
    const t = S.timer;
    if (a.charAt(0) === 'm') { const m = a === 'm45' ? C.defaultMinutes(config()) : Number(a.slice(1)); Object.assign(t, { mode: 'custom', preset: m * 60, remain: m * 60, running: true, endAt: Date.now() + m * 60000, rang: false }); }
    else if (a === 'toggle') { if (t.running) { t.remain = Math.round((t.endAt - Date.now()) / 1000); t.running = false; } else { t.endAt = Date.now() + t.remain * 1000; t.running = true; t.rang = false; } }
    else if (a === 'reset') { t.running = false; t.remain = t.preset; t.rang = false; }
    else if (a === 'period') { t.mode = 'period'; t.running = false; }
    renderTimer();
  }

  // ---------------------------------------------------------------- break view
  function renderBreak() {
    renderBreakHero();
    const w = $('#b-weather');
    const wh = '<h3>' + ic('wSun') + '날씨</h3>' + weatherHtml(false);
    if (w._h !== wh) { w.innerHTML = wh; w._h = wh; }
    renderNotices($('#b-side'), false);
  }
  function renderBreakHero() {
    const el = $('#b-hero');
    if (!el || S.view !== 'break') return;
    const seg = S.seg;
    if (!seg.slot) { el.innerHTML = ''; return; }
    const secs = Math.round(Math.max(0, seg.slot.start - C.nowMin()) * 60);
    const e = entryFor(seg.slot.p);
    const txt = C.pad(Math.floor(secs / 60)) + ':' + C.pad(secs % 60);
    const ev = dayEvent();
    const head = '<div class="lbl">' + esc(ev ? ev.name + ' · 다음 수업까지' : seg.type === 'before' ? '수업 시작까지' : '쉬는 시간 · 다음 수업까지') + '</div>';
    const tail = '<div class="nx">' + seg.slot.p + '교시 ' + esc(e ? e.s : '') + '<small>' + esc([e && e.t ? e.t + ' 선생님' : '', roomOf(e)].filter(Boolean).join(' · ')) + '</small></div>';
    if (el._head !== head + tail) { el.innerHTML = head + '<div class="cnt" id="b-cnt"></div>' + tail; el._head = head + tail; }
    $('#b-cnt').textContent = txt;
  }

  // ---------------------------------------------------------------- apps
  const APPS = {
    lesson: { name: '수업', open: () => { closePanel(); S.manualView = 'lesson'; setView('lesson'); } },
    timetable: { name: '시간표', render: renderTimetableApp },
    meal: { name: '급식', render: renderMealApp },
    notices: { name: '가정통신문', render: renderNoticesApp },
    post: { name: '가정통신문', icon: 'notices', render: renderPostApp, hidden: true },
    calendar: { name: '학사일정', render: renderCalendarApp },
    weather: { name: '날씨', render: renderWeatherApp },
    board: { name: '칠판', render: renderBoardApp, full: true },
    memo: { name: '화면 메모', open: () => native('memo') },
    alerts: { name: '학급 알림', open: () => { if (N && N.alertPanel) native('alertPanel'); else { S.setSection = 'alerts'; openApp('settings'); } } },
    browser: { name: '인터넷', open: openSearch },
    capture: { name: '화면 캡처', open: () => native('capture', false) },
    record: { name: '화면 녹화', open: () => { native('capture', true); setTimeout(pollSys, 1500); } },
    split: { name: '화면 분할', render: () => renderAndroidApps(true) },
    files: { name: '파일', render: renderFiles },
    hdmi: { name: '외부 입력', render: renderInputs },
    doc: { name: '문서', icon: 'notices', render: renderDocApp, hidden: true, full: true },
    room: { name: '교실 정보', render: renderRoom },
    drawer: { name: '모든 앱', render: renderDrawer, hidden: true },
    settings: { name: '설정', render: renderSettings },
  };
  const DEFAULT_DOCK = ['timetable', 'meal', 'notices', 'board', 'alerts'];
  const PER_PAGE = 16;
  let androidApps = null;

  function listAndroidApps() {
    if (androidApps) return androidApps;
    androidApps = [];
    if (N) { try { androidApps = JSON.parse(N.apps()); } catch (e) { androidApps = []; } }
    return androidApps;
  }
  function svgFor(id) { return window.appIconSvg((APPS[id] && APPS[id].icon) || id); }
  function appLabel(id) {
    if (id.indexOf('pkg:') === 0) { const a = listAndroidApps().find((x) => x.pkg === id.slice(4)); return a ? a.label : id.slice(4); }
    return APPS[id] ? APPS[id].name : id;
  }
  function tileFor(id) {
    if (id.indexOf('pkg:') === 0) return '<span class="tile"><img src="/api/local/icon?pkg=' + encodeURIComponent(id.slice(4)) + '" alt="" loading="lazy"></span>';
    return '<span class="tile">' + svgFor(id) + '</span>';
  }
  function appIcon(id, small) {
    const key = id.indexOf('pkg:') === 0 ? 'data-pkg="' + esc(id.slice(4)) + '"' : 'data-app="' + id + '"';
    return '<button class="appicon' + (small ? ' small' : '') + '" ' + key + '>' + tileFor(id) + '<span class="lb">' + esc(appLabel(id)) + '</span></button>';
  }
  function bindApps(root) {
    C.$$('[data-app]', root).forEach((b) => { b.onclick = (ev) => { if (b._long) { b._long = false; return; } openApp(b.dataset.app, ev.currentTarget); }; longPress(b, 'app:' + b.dataset.app); });
    C.$$('[data-pkg]', root).forEach((b) => {
      b.onclick = () => { if (b._long) { b._long = false; return; } const r = native('launch', b.dataset.pkg); if (r && !r.error) rememberAndroid(b.dataset.pkg); };
      longPress(b, 'pkg:' + b.dataset.pkg);
    });
  }
  function dockIds() {
    const d = S.device && S.device.home && S.device.home.dock;
    return (d && d.length ? d : DEFAULT_DOCK).filter((id) => id.indexOf('pkg:') === 0 || (APPS[id] && !APPS[id].hidden));
  }
  function renderDock() {
    const d = $('#dock');
    d.innerHTML = dockIds().map((id) => appIcon(id)).join('');
    bindApps(d);
  }
  async function saveHome(patch) {
    try {
      const h = await localPost('/api/local/home', patch);
      S.device.home = h;
      renderDock();
      return true;
    } catch (e) { toast(e.message); return false; }
  }
  function toggleDock(id) {
    const cur = dockIds().slice();
    const i = cur.indexOf(id);
    if (i >= 0) cur.splice(i, 1);
    else if (cur.length >= 6) { toast('하단에는 앱을 6개까지 둘 수 있습니다'); return; }
    else cur.push(id);
    saveHome({ dock: cur }).then((ok) => { if (ok) toast(i >= 0 ? '하단에서 뺐습니다' : '하단에 추가했습니다', 1800); });
  }

  // long press on an icon: a small menu like an Android launcher
  function longPress(el, id) {
    let t = null, sx = 0, sy = 0;
    el.addEventListener('pointerdown', (e) => {
      sx = e.clientX; sy = e.clientY;
      clearTimeout(t);
      t = setTimeout(() => { el._long = true; iconMenu(el, id.replace(/^app:/, '')); }, 550);
    });
    el.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) clearTimeout(t); });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach((n) => el.addEventListener(n, () => clearTimeout(t)));
    el.addEventListener('contextmenu', (e) => e.preventDefault());
  }
  function iconMenu(el, id) {
    const m = $('#ctx');
    const inDock = dockIds().indexOf(id) >= 0;
    m.innerHTML = '<div class="ctx-h">' + tileFor(id) + '<b>' + esc(appLabel(id)) + '</b></div>' +
      '<button data-c="open">열기</button><button data-c="dock">' + (inDock ? '하단에서 빼기' : '하단에 추가') + '</button>' +
      (id.indexOf('pkg:') === 0 ? '<button data-c="info">앱 정보</button>' : '');
    m.classList.add('on');
    // place next to the icon, kept inside the screen using the menu's real size
    const r = el.getBoundingClientRect(), pr = $('#app').getBoundingClientRect();
    const mw = m.offsetWidth, mh = m.offsetHeight;
    m.style.left = Math.min(pr.width - mw - 12, Math.max(12, r.left - pr.left + r.width / 2 - mw / 2)) + 'px';
    const below = r.bottom - pr.top + mh + 12 < pr.height;
    m.style.top = (below ? r.bottom - pr.top + 8 : Math.max(12, r.top - pr.top - mh - 8)) + 'px';
    m.style.bottom = '';
    C.$$('[data-c]', m).forEach((b) => {
      b.onclick = () => {
        m.classList.remove('on');
        if (b.dataset.c === 'dock') toggleDock(id);
        else if (b.dataset.c === 'info') native('open', 'appInfo:' + id.slice(4));
        else if (id.indexOf('pkg:') === 0) native('launch', id.slice(4));
        else openApp(id, el);
      };
    });
    setTimeout(() => document.addEventListener('pointerdown', function off(e) {
      if (!m.contains(e.target)) { m.classList.remove('on'); document.removeEventListener('pointerdown', off, true); }
    }, true), 0);
  }

  // search: opens the browser on the chosen search engine
  const ENGINES = { naver: ['네이버', 'https://m.naver.com/'], google: ['Google', 'https://www.google.com/'], daum: ['다음', 'https://m.daum.net/'] };
  function engine() { return ENGINES[(S.device && S.device.home && S.device.home.search) || 'naver'] || ENGINES.naver; }
  function openSearch() { closeRecents(); native('openUrl', engine()[1]); }

  // apps continue sideways as launcher pages: school apps first, then installed Android apps
  function renderAppPages() {
    const pages = $('#pages');
    C.$$('.pg-apps', pages).forEach((p) => p.remove());
    const items = Object.keys(APPS).filter((id) => !APPS[id].hidden).map((id) => appIcon(id));
    listAndroidApps().forEach((a) => { items.push(appIcon('pkg:' + a.pkg)); });
    for (let i = 0; i < items.length; i += PER_PAGE) {
      const k = i / PER_PAGE;
      const pg = document.createElement('div');
      pg.className = 'page pg-apps';
      pg.innerHTML = '<div class="wcol">' + [WIDGETS[(2 * k) % WIDGETS.length], WIDGETS[(2 * k + 1) % WIDGETS.length]].map((w) => '<button class="widget" data-w="' + w + '"></button>').join('') + '</div>' +
        '<div class="agrid">' + items.slice(i, i + PER_PAGE).join('') + '</div>';
      C.$$('[data-w]', pg).forEach((b) => { b.onclick = (ev) => openWidget(b.dataset.w, ev.currentTarget); });
      pages.appendChild(pg);
      bindApps(pg);
    }
    renderDots();
    renderWidgets();
  }

  // home-screen widgets placed next to the app icons
  const WIDGETS = ['next', 'meal', 'dday', 'weather', 'event', 'notice'];
  function widgetHtml(w) {
    const box = (icon, head, value, sub, cls2) => '<div class="wh">' + ic(icon) + esc(head) + '</div><div class="wv' + (cls2 ? ' ' + cls2 : '') + '">' + value + '</div><div class="wd">' + (sub || '&nbsp;') + '</div>';
    if (!S.dataLoaded) return box('clock', '', '-', '');
    if (w === 'next') {
      const seg = S.seg;
      const slot = seg.type === 'class' ? seg.next : seg.slot;
      const e = slot ? entryFor(slot.p) : null;
      if (seg.type === 'class') { const cur = entryFor(seg.slot.p); return box('book', '지금 · ' + seg.slot.p + '교시', esc(cur ? cur.s : '수업'), Math.ceil(seg.slot.end - C.nowMin()) + '분 남음' + (slot && e ? ' · 다음 ' + esc(e.s) : '')); }
      if (slot && e) return box('clock', '다음 수업 · ' + slot.p + '교시', esc(e.s), C.hm(slot.start) + ' 시작' + (e.t ? ' · ' + esc(e.t) + ' 선생님' : ''));
      const nd = hasTimetable() ? nextSchoolDate(C.today()) : null;
      const first = nd ? periodsOn(nd).find((x) => x.s && !x.cancel) : null;
      return box('clock', '다음 수업', first ? esc(first.s) : '없음', nd ? C.dateLabel(nd) + ' 1교시' : '');
    }
    if (w === 'meal') {
      const md = mealDay();
      const pick = md.list.find((x) => x.code === 2) || md.list[0];
      return box('meal', '급식 · ' + (md.date === C.today() ? '오늘' : C.shortDate(md.date)), pick ? esc(pick.dishes.map((d) => d.name).join(' · ')) : '없음', pick ? esc(pick.type) : '', 'sm');
    }
    if (w === 'dday') {
      const ex = examItems()[0];
      return box('exam', '시험', ex ? examDday(ex) : '없음', ex ? esc(ex.name) + ' · ' + C.rangeLabel(ex) : '예정된 시험 없음', ex ? 'red' : '');
    }
    if (w === 'weather') {
      const wx = S.data.weather && S.data.weather.current;
      return box(wx ? window.weatherIcon(wx.weather_code) : 'wSun', '날씨', wx ? Math.round(wx.temperature_2m) + '°' : '-', wx ? esc(window.weatherText(wx.weather_code)) + ' · 체감 ' + Math.round(wx.apparent_temperature) + '°' : '');
    }
    if (w === 'event') {
      const ev = upcomingEvents()[0];
      return box('calendar', '다음 일정', ev ? esc(ev.name) : '없음', ev ? C.rangeLabel(ev) + ' · ' + C.dday(ev.date) : '', 'sm');
    }
    const it = homepageLatest(1)[0];
    return box('file', '새 소식', it ? esc(it.title) : '없음', it ? esc(it.board + ' · ' + it.date) : '', 'sm');
  }
  function renderWidgets() {
    C.$$('.widget[data-w]').forEach((b) => { const h = widgetHtml(b.dataset.w); if (b._h !== h) { b.innerHTML = h; b._h = h; } });
  }
  function openWidget(w, el) {
    const map = { next: 'timetable', meal: 'meal', dday: 'calendar', weather: 'weather', event: 'calendar', notice: 'notices' };
    openApp(map[w], el);
  }
  function renderDots() {
    const pages = $('#pages'), dots = $('#dots');
    const n = pages.children.length;
    dots.innerHTML = Array.from({ length: n }, (_, i) => '<button data-pg="' + i + '" aria-label="' + (i + 1) + '페이지"></button>').join('');
    C.$$('[data-pg]', dots).forEach((b) => { b.onclick = () => goPage(Number(b.dataset.pg)); });
    syncDots();
  }
  function syncDots() {
    const pages = $('#pages');
    const i = Math.round(pages.scrollLeft / Math.max(1, pages.clientWidth));
    C.$$('#dots [data-pg]').forEach((b, k) => b.classList.toggle('on', k === i));
  }

  // launcher pages: swipe (touch scrolls natively; mouse drag for pointer devices), dots follow
  function initPages() {
    const pages = $('#pages');
    pages.addEventListener('scroll', syncDots, { passive: true });
    window.addEventListener('resize', () => goPage(currentPage(), true));
    let drag = null;
    pages.addEventListener('pointerdown', (e) => {
      if (e.pointerType !== 'mouse' || e.button !== 0) return;
      drag = { x: e.clientX, left: pages.scrollLeft, moved: false, page: currentPage() };
    });
    window.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) < 8) return;
      drag.moved = true;
      pages.classList.add('dragging');
      pages.scrollLeft = drag.left - dx;
    });
    window.addEventListener('pointerup', (e) => {
      if (!drag) return;
      const d = drag;
      drag = null;
      if (!d.moved) return;
      const dx = e.clientX - d.x;
      pages.classList.remove('dragging');
      goPage(d.page + (dx < -60 ? 1 : dx > 60 ? -1 : 0));
      // swallow the click that ends a drag
      const stop = (ev) => { ev.stopPropagation(); ev.preventDefault(); };
      window.addEventListener('click', stop, { capture: true, once: true });
      setTimeout(() => window.removeEventListener('click', stop, { capture: true }), 50);
    });
  }
  function currentPage() { const p = $('#pages'); return Math.round(p.scrollLeft / Math.max(1, p.clientWidth)); }
  function goPage(i, instant) {
    const p = $('#pages');
    const n = p.children.length;
    i = Math.max(0, Math.min(n - 1, i));
    // scroll-snap-stop would halt a smooth jump at the next page, so snapping is paused while it glides
    p.classList.add('dragging');
    p.scrollTo({ left: i * p.clientWidth, behavior: instant ? 'auto' : 'smooth' });
    clearTimeout(goPage._t);
    goPage._t = setTimeout(() => p.classList.remove('dragging'), instant ? 0 : 700);
  }
  function openApp(id, fromEl) {
    const a = APPS[id];
    if (!a) return;
    S.recent = [id].concat(S.recent.filter((x) => x !== id)).slice(0, 8);
    try { localStorage.setItem('cb_recent', JSON.stringify(S.recent)); } catch (e) { /* ignore */ }
    if (a.open) { closeRecents(); a.open(); return; }
    openPanel(id, {}, fromEl);
  }

  function openPanel(name, opts, fromEl) {
    const a = APPS[name] || { name: '', icon: 'apps', c: '#5b8cff' };
    const p = $('#panel');
    closeRecents();
    clearTimeout(closePanel._t);
    // open from the tapped icon / card
    if (fromEl && fromEl.getBoundingClientRect) {
      const r = fromEl.getBoundingClientRect(), pr = $('#app').getBoundingClientRect();
      p.style.setProperty('--ox', (r.left + r.width / 2 - pr.left) + 'px');
      p.style.setProperty('--oy', (r.top + r.height / 2 - pr.top) + 'px');
    } else if (!S.panel) { p.style.setProperty('--ox', '50%'); p.style.setProperty('--oy', '90%'); }
    const wasOpen = !!S.panel;
    S.panel = name;
    S.panelOpts = opts || {};
    $('#p-icon').innerHTML = svgFor(name);
    p.classList.toggle('full', !!a.full);
    $('#p-title').textContent = a.name;
    $('#p-tools').innerHTML = '';
    p.classList.remove('closing');
    if (!wasOpen) { p.classList.remove('on'); void p.offsetWidth; }
    p.classList.add('on');
    refreshPanel();
  }
  function closePanel() {
    if (!S.panel) return;
    S.panel = null;
    const p = $('#panel');
    p.classList.add('closing');
    clearTimeout(closePanel._t);
    closePanel._t = setTimeout(() => { p.classList.remove('on', 'closing'); $('#p-body').innerHTML = ''; }, 300);
  }
  function refreshPanel() {
    const a = APPS[S.panel];
    const body = $('#p-body');
    body.innerHTML = '';
    body.style.padding = S.panel === 'settings' || S.panel === 'files' ? '0' : '';
    if (a && a.render) a.render();
  }
  function goHome() {
    // like Android: home again while already home returns to the first page
    const alreadyHome = !S.panel && !$('#recents').classList.contains('on') && S.view === 'home';
    closeRecents();
    closePanel();
    S.manualView = 'home';
    setView('home');
    if (alreadyHome) goPage(0);
  }

  // drawer: built-in apps + installed Android apps
  function renderDrawer() {
    const body = $('#p-body');
    const own = Object.keys(APPS).filter((id) => !APPS[id].hidden);
    body.innerHTML = '<div><div class="section-t">' + ic('school') + '중동중학교</div><div class="app-grid">' + own.map((id) => appIcon(id, true)).join('') + '</div>' +
      '<div class="section-t">' + ic('apps') + '설치된 앱</div><div id="android-apps"></div></div>';
    bindApps(body);
    renderAndroidApps(false, $('#android-apps'));
  }
  function renderAndroidApps(split, target) {
    const body = target || $('#p-body');
    if (!N) { body.innerHTML = '<div class="empty">' + ic('info') + '전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    let apps = [];
    try { apps = JSON.parse(N.apps()); } catch (e) { apps = []; }
    if (!target) $('#p-tools').innerHTML = '<input id="app-q" placeholder="앱 검색" style="width:22rem">';
    const draw = () => {
      const q = (($('#app-q') || {}).value || '').trim().toLowerCase();
      const list = apps.filter((a) => !q || a.label.toLowerCase().indexOf(q) >= 0 || a.pkg.indexOf(q) >= 0);
      let html = split ? '<p class="muted" style="margin-bottom:1.2rem;font-size:1.1rem">고른 앱이 이 화면 옆에 열립니다. <button class="btn sm" id="exit-split">' + ic('x') + '분할 종료</button></p>' : '';
      html += '<div class="app-grid">' + list.map((a) => '<div class="app" data-pkg="' + esc(a.pkg) + '"><img class="app-img" src="/api/local/icon?pkg=' + encodeURIComponent(a.pkg) + '" alt="" loading="lazy"><span>' + esc(a.label) + '</span>' +
        '</div>').join('') + '</div>';
      body.innerHTML = html;
      C.$$('.app', body).forEach((el) => {
        el.onclick = () => {
          const r = split ? native('launchSplit', el.dataset.pkg) : native('launch', el.dataset.pkg);
          if (r && !r.error) { rememberAndroid(el.dataset.pkg); closePanel(); }
        };
      });
      C.$$('[data-split]', body).forEach((el) => { el.onclick = (ev) => { ev.stopPropagation(); const r = native('launchSplit', el.dataset.split); if (r && !r.error) closePanel(); }; });
      const ex = $('#exit-split');
      if (ex) ex.onclick = () => native('exitSplit');
    };
    if ($('#app-q')) $('#app-q').oninput = draw;
    draw();
  }
  function rememberAndroid(pkg) {
    S.recent = ['pkg:' + pkg].concat(S.recent.filter((x) => x !== 'pkg:' + pkg)).slice(0, 8);
    try { localStorage.setItem('cb_recent', JSON.stringify(S.recent)); } catch (e) { /* ignore */ }
  }

  // recents (□ key)
  function openRecents() {
    const el = $('#recents');
    if (el.classList.contains('on')) { closeRecents(); return; }
    const list = S.recent.filter((id) => id.indexOf('pkg:') === 0 || (APPS[id] && !APPS[id].hidden));
    let cards = list.map((id) => {
      if (id.indexOf('pkg:') === 0) {
        const pkg = id.slice(4);
        return '<button class="rc" data-rpkg="' + esc(pkg) + '"><img class="app-img" src="/api/local/icon?pkg=' + encodeURIComponent(pkg) + '" alt=""><b>앱</b></button>';
      }
      const a = APPS[id];
      return '<button class="rc" data-rapp="' + id + '">' + tileFor(id) + '<b>' + esc(a.name) + '</b></button>';
    }).join('');
    if (!cards) cards = '<div class="empty" style="color:#a9b4c3">최근에 연 앱이 없습니다</div>';
    el.innerHTML = '<h2>최근 앱</h2><div class="cards">' + cards + '</div>' + (N ? '<button class="btn" id="rc-sys">' + ic('apps') + '안드로이드 최근 앱</button>' : '');
    el.classList.add('on');
    C.$$('[data-rapp]', el).forEach((b) => { b.onclick = (ev) => openApp(b.dataset.rapp, ev.currentTarget); });
    C.$$('[data-rpkg]', el).forEach((b) => { b.onclick = () => { closeRecents(); native('launch', b.dataset.rpkg); }; });
    const sys = $('#rc-sys');
    if (sys) sys.onclick = () => { closeRecents(); const r = native('nav', 'recents'); if (r && r.noA11y) toast('설정 앱 → 권한에서 접근성 서비스를 켜면 안드로이드 최근 앱을 열 수 있습니다', 5000); };
    el.onclick = (ev) => { if (ev.target === el) closeRecents(); };
  }
  function closeRecents() { $('#recents').classList.remove('on'); }

  // ---------------------------------------------------------------- navigation keys
  function renderNavKeys() {
    const el = $('#navkeys');
    el.innerHTML = '<button data-nav="back" aria-label="뒤로">' + ic('navBack') + '</button><button data-nav="home" aria-label="홈">' + ic('navHome') + '</button><button data-nav="recents" aria-label="최근 앱">' + ic('navRecents') + '</button>';
    C.$$('[data-nav]', el).forEach((b) => { b.onclick = () => navKey(b.dataset.nav); });
  }
  function navKey(k) {
    if (k === 'back') {
      if ($('#class-alert').classList.contains('on')) { hideClassAlert(); return; }
      if ($('#recents').classList.contains('on')) { closeRecents(); return; }
      if (S.panel === 'post') { openPanel('notices', { menuId: S.panelOpts.menuId }); return; }
      if (S.panel === 'doc' && S.panelOpts.back) { openPanel(S.panelOpts.back.app, S.panelOpts.back.opts || {}); return; }
      if (S.panel) { closePanel(); return; }
      S.manualView = null;
      setView(autoView() === S.view ? 'home' : autoView());
    } else if (k === 'home') goHome();
    else if (k === 'recents') openRecents();
  }

  // ---------------------------------------------------------------- class alerts (on the board itself)
  const ALERTS = [
    { type: 'quiet', label: '조용히 해주세요', sub: '수업이 곧 시작됩니다', icon: 'quiet', color: 'indigo', c: '#7d7bff', flash: true, sound: 'quiet' },
    { type: 'clean', label: '청소 시작', sub: '각자 맡은 청소 구역으로 이동해 주세요', icon: 'broom', color: 'green', c: '#2fb87a', sound: 'soft' },
    { type: 'ready', label: '수업 준비', sub: '교과서와 준비물을 책상 위에 꺼내 주세요', icon: 'book', color: 'orange', c: '#ff9f2e', sound: 'soft' },
    { type: 'notice', label: '전달사항 있습니다', sub: '앞을 봐 주세요', icon: 'megaphone', color: 'teal', c: '#14a3a3', sound: 'soft' },
  ];
  function fireClassAlert(a) {
    if (N && N.classAlert && a.type) { native('classAlert', a.type); return; }
    if (S.seg.type === 'class') { toast('수업 중에는 학급 알림을 보낼 수 없습니다'); return; }
    const st = settings();
    if (st.classAlerts === false) { toast('설정에서 학급 알림이 꺼져 있습니다'); return; }
    const cool = (st.alertCooldown || 10) * 1000;
    if (Date.now() - S.lastAlertAt < cool) { toast(Math.ceil((cool - (Date.now() - S.lastAlertAt)) / 1000) + '초 후 다시 보낼 수 있습니다'); return; }
    S.lastAlertAt = Date.now();
    if (N && st.alertSound !== false) N.chime(a.sound || 'soft');
    showClassAlert(a, st.alertSeconds || 8);
  }
  function showClassAlert(a, secs) {
    closePanel();
    const el = $('#class-alert');
    el.className = 'class-alert c-' + a.color + (a.flash ? ' flash' : '');
    $('#edge-flash').classList.toggle('on', !!a.flash);
    el.innerHTML = ic(a.icon) + '<div class="t">' + esc(a.label) + '</div>' + (a.sub ? '<div class="s">' + esc(a.sub) + '</div>' : '') + '<div class="bar2"><i></i></div>';
    el.classList.remove('on');
    void el.offsetWidth;
    el.classList.add('on');
    const bar = el.querySelector('.bar2 i');
    bar.style.transition = 'transform ' + secs + 's linear';
    setTimeout(() => { bar.style.transform = 'scaleX(0)'; }, 40);
    el.onclick = hideClassAlert;
    clearTimeout(showClassAlert._t);
    showClassAlert._t = setTimeout(hideClassAlert, secs * 1000);
  }
  function hideClassAlert() { $('#class-alert').classList.remove('on', 'flash'); $('#edge-flash').classList.remove('on'); }

  // ---------------------------------------------------------------- files: internal storage and USB drives
  const FS = { path: null, root: null };
  const IMG = /\.(png|jpe?g|gif|webp|bmp)$/i;
  function placeIcon(kind) {
    return { internal: 'storage', download: 'download', board: 'image', record: 'video', docs: 'file', photos: 'image', usb: 'usb' }[kind] || 'folder';
  }
  function renderFiles() {
    const body = $('#p-body');
    if (!N || !N.fsRoots) { body.innerHTML = '<div class="empty">' + ic('info') + '전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    let info;
    try { info = JSON.parse(N.fsRoots()); } catch (e) { info = { access: false, places: [], usb: [] }; }
    if (!info.access) {
      body.innerHTML = '<div class="fs-perm"><div class="tile">' + svgFor('files') + '</div><h2>파일을 보려면 권한이 필요합니다</h2>' +
        '<p>내부 저장공간에 저장된 칠판 · 캡처 · 녹화 파일과 USB 메모리를 보려면 "모든 파일 접근"을 허용해 주세요.</p>' +
        '<button class="btn pri" id="fs-allow">' + ic('lock') + '권한 허용하기</button></div>';
      $('#fs-allow').onclick = () => native('open', 'allFiles');
      return;
    }
    const places = info.places.filter((p) => p.kind === 'internal' || p.exists !== false);
    const usb = info.usb || [];
    if (!FS.path || !places.concat(usb).some((p) => FS.path.indexOf(p.path) === 0)) {
      FS.path = usb.length && FS.preferUsb ? usb[0].path : places[0].path;
      FS.preferUsb = false;
    }
    const placeRow = (p, kind) => {
      const on = FS.path === p.path || (FS.path.indexOf(p.path + '/') === 0 && !places.concat(usb).some((q) => q !== p && q.path.length > p.path.length && FS.path.indexOf(q.path) === 0));
      const cap = p.total ? '<span class="cap"><i style="width:' + Math.round((p.total - p.free) / p.total * 100) + '%"></i></span><small>' + C.bytes(p.free) + ' 남음</small>' : '';
      return '<button class="fs-place' + (on ? ' on' : '') + '" data-root="' + esc(p.path) + '">' + ic(placeIcon(kind || p.kind)) + '<span class="nm">' + esc(p.label) + cap + '</span></button>';
    };
    body.style.padding = '0';
    body.innerHTML = '<div class="fs"><div class="fs-side">' + places.map((p) => placeRow(p)).join('') +
      '<div class="fs-group">USB · 외부 저장장치</div>' + (usb.length ? usb.map((p) => placeRow(p, 'usb')).join('') : '<div class="fs-none">USB 메모리를 꽂으면 여기에 나타납니다</div>') +
      '</div><div class="fs-main" id="fs-main"></div></div>';
    C.$$('[data-root]', body).forEach((b) => { b.onclick = () => { FS.path = b.dataset.root; renderFiles(); }; });
    renderDirList(places.concat(usb));
  }
  function renderDirList(roots) {
    const main = $('#fs-main');
    let d;
    try { d = JSON.parse(N.fsList(FS.path)); } catch (e) { d = { error: String(e) }; }
    const root = roots.filter((p) => FS.path.indexOf(p.path) === 0).sort((x, y) => y.path.length - x.path.length)[0];
    const rel = root ? FS.path.slice(root.path.length).split('/').filter(Boolean) : [];
    let crumbs = '<button data-go="' + esc(root ? root.path : FS.path) + '">' + esc(root ? root.label : FS.path) + '</button>';
    let acc = root ? root.path : '';
    rel.forEach((seg) => { acc += '/' + seg; crumbs += ic('right') + '<button data-go="' + esc(acc) + '">' + esc(seg) + '</button>'; });
    if (d.error) {
      main.innerHTML = '<div class="fs-crumbs">' + crumbs + '</div><div class="empty">' + ic('info') + esc(d.error) + '</div>';
    } else if (!d.items.length) {
      main.innerHTML = '<div class="fs-crumbs">' + crumbs + '</div><div class="empty">' + ic('folder') + '빈 폴더입니다</div>';
    } else {
      main.innerHTML = '<div class="fs-crumbs">' + crumbs + '<span class="dim">' + d.items.length + '개</span></div><div class="fs-list">' + d.items.map((f, i) => {
        const when = new Date(f.mtime);
        const date = C.iso(when) === C.today() ? '오늘 ' + C.pad(when.getHours()) + ':' + C.pad(when.getMinutes()) : C.iso(when).replace(/-/g, '. ');
        const thumb = !f.dir && IMG.test(f.name) ? '<img class="fs-th" loading="lazy" src="/api/local/thumb?path=' + encodeURIComponent(f.path) + '" alt="">' : '<span class="fs-ic">' + ic(f.dir ? 'folder' : fileIcon(f.name)) + '</span>';
        return '<button class="fs-row" data-i="' + i + '">' + thumb + '<span class="fs-nm">' + esc(f.name) + '</span><span class="fs-meta">' + (f.dir ? f.count + '개 항목' : C.bytes(f.size)) + '</span><span class="fs-meta">' + date + '</span></button>';
      }).join('') + '</div>';
      C.$$('.fs-row', main).forEach((r) => {
        r.onclick = () => {
          const f = d.items[Number(r.dataset.i)];
          if (f.dir) { FS.path = f.path; renderDirList(roots); }
          else if (DOC_EXT.test(f.name)) openPanel('doc', { path: f.path, name: f.name, back: { app: 'files' } }, r);
          else native('fsOpen', f.path);
        };
      });
    }
    C.$$('[data-go]', main).forEach((b) => { b.onclick = () => { FS.path = b.dataset.go; renderDirList(roots); }; });
  }
  function onStorage(d) {
    if (d.mounted) {
      toast('USB 메모리가 연결되었습니다. 누르면 파일 앱에서 엽니다.', 6000);
      $('#toast').onclick = () => { $('#toast').classList.remove('on'); FS.preferUsb = true; FS.path = null; openApp('files'); };
    } else toast('USB 메모리가 분리되었습니다', 3000);
    if (S.panel === 'files') { if (d.mounted) { FS.preferUsb = true; FS.path = null; } renderFiles(); }
  }

  // ---------------------------------------------------------------- 문서 보기 (PDF · HWP · 오피스)
  // The school server turns the file into page pictures; a PDF can also be drawn by the board itself.
  const DOC_EXT = /\.(pdf|hwp|hwpx|docx?|pptx?|xlsx?|odt|odp|ods)$/i;
  const DOC = { zoom: 1 };
  async function renderDocApp() {
    const o = S.panelOpts;
    const body = $('#p-body');
    const isPdf = /\.pdf$/i.test(o.name || '');
    body.innerHTML = '<div class="doc"><div class="doc-bar"><b class="doc-nm">' + esc(o.name || '문서') + '</b><span class="doc-pg" id="doc-pg"></span>' +
      '<button class="bb-t" data-z="-1">' + ic('minus') + '</button><button class="bb-t" data-z="0">맞춤</button><button class="bb-t" data-z="1">' + ic('plus') + '</button>' +
      '<button class="bb-t" id="doc-ext">다른 앱으로 열기</button><button class="bb-t" id="doc-x">' + ic('x') + '</button></div>' +
      '<div class="doc-pages" id="doc-pages"><div class="doc-wait"><span class="dots-loader"><i></i><i></i><i></i></span><span id="doc-step">문서를 여는 중</span></div></div></div>';
    $('#doc-x').onclick = () => navKey('back');
    $('#doc-ext').onclick = () => { if (o.path) native('fsOpen', o.path); else native('openWebFile', o.url, o.name); };
    C.$$('[data-z]', body).forEach((b) => { b.onclick = () => { const z = Number(b.dataset.z); DOC.zoom = z === 0 ? 1 : Math.max(1, Math.min(3, DOC.zoom + z * 0.5)); applyZoom(); }; });
    const step = (t) => { const el = $('#doc-step'); if (el) el.textContent = t; };
    const alive = () => S.panel === 'doc' && S.panelOpts === o;
    let pages = 0, src = null, err = null;
    try {
      const d = await loadDoc(o, step, alive);
      pages = d.pages; src = d.src;
    } catch (e) { err = e; }
    if (!alive()) return;
    const box = $('#doc-pages');
    if (err || !pages) {
      box.innerHTML = '<div class="doc-wait err">' + esc((err && err.message) || '쪽이 없는 문서입니다') + '<button class="btn pri" id="doc-retry">' + ic('refresh') + '다시 시도</button></div>';
      $('#doc-retry').onclick = () => renderDocApp();
      return;
    }
    DOC.zoom = 1;
    box.innerHTML = Array.from({ length: pages }, (_, i) => '<div class="doc-page"><img loading="lazy" decoding="async" src="' + src(i + 1) + '" alt="' + (i + 1) + '쪽"></div>').join('');
    const pg = $('#doc-pg');
    const upd = () => {
      const kids = box.children, mid = box.scrollTop + box.clientHeight / 2;
      let n = 1;
      for (let i = 0; i < kids.length; i++) if (kids[i].offsetTop <= mid) n = i + 1;
      pg.textContent = n + ' / ' + pages + '쪽';
    };
    box.onscroll = upd;
    upd();
    applyZoom();
  }
  // attachment pages inside the post (가정통신문 · 공지사항)
  async function inlineDoc(f, o) {
    const box = $('#post-doc');
    if (!box) return;
    box.innerHTML = '<div class="post-doc-h">' + ic(fileIcon(f.name)) + '<b>' + esc(f.name) + '</b><button class="btn sm" id="pd-open">' + ic('search') + '크게 보기</button></div>' +
      '<div class="post-doc-wait"><span class="dots-loader"><i></i><i></i><i></i></span><span id="pd-step">첨부파일을 펼치는 중</span></div>';
    $('#pd-open').onclick = (ev) => openPanel('doc', { url: f.url, name: f.name, back: { app: 'post', opts: o } }, ev.currentTarget);
    const alive = () => S.panel === 'post' && S.panelOpts === o && document.body.contains(box);
    try {
      const d = await loadDoc({ url: f.url, name: f.name }, (t) => { const el = $('#pd-step'); if (el) el.textContent = t; }, alive);
      if (!alive()) return;
      const wait = box.querySelector('.post-doc-wait');
      wait.outerHTML = Array.from({ length: d.pages }, (_, i) => '<img class="post-doc-pg" loading="lazy" decoding="async" src="' + d.src(i + 1) + '" alt="' + (i + 1) + '쪽">').join('');
      C.$$('.post-doc-pg', box).forEach((im) => { im.onclick = (ev) => openPanel('doc', { url: f.url, name: f.name, back: { app: 'post', opts: o } }, ev.currentTarget); });
    } catch (e) {
      if (!alive()) return;
      const wait = box.querySelector('.post-doc-wait');
      if (wait) wait.innerHTML = '<span class="muted">' + esc(e.message) + '</span>';
    }
  }

  /** Pages of a document: from the school server, or drawn on the board for PDFs. */
  async function loadDoc(o, step, alive) {
    const isPdf = /\.pdf$/i.test(o.name || '');
    if (o.path && isPdf) {
      // a PDF on this board: the built-in renderer is quick, nothing to send anywhere
      const d = await C.get('/api/local/pdf/info?path=' + encodeURIComponent(o.path));
      return { pages: d.pages, src: (p) => '/api/local/pdf/page?key=' + d.key + '&p=' + p };
    }
    try {
      let d = o.path ? await localPost('/api/local/docupload?path=' + encodeURIComponent(o.path), {})
        : await C.get('/api/doc/info?url=' + encodeURIComponent(o.url) + '&name=' + encodeURIComponent(o.name));
      const t0 = Date.now();
      while (d.status === 'working' && alive() && Date.now() - t0 < 180000) {
        step('학교 서버가 문서를 준비하는 중 · ' + (d.step || ''));
        await sleep(1200);
        d = await C.get('/api/doc/info?id=' + d.id);
      }
      if (d.status === 'error') throw new Error(d.error);
      if (d.status !== 'ready') throw new Error('문서 준비가 너무 오래 걸립니다');
      return { pages: d.pages, src: (p) => '/api/doc/page?id=' + d.id + '&p=' + p };
    } catch (e) {
      if (!(isPdf && o.url) || !alive()) throw e;
      // school server unavailable: draw the PDF on the board
      step('전자칠판에서 직접 여는 중');
      const d = await C.get('/api/local/pdf/info?url=' + encodeURIComponent(o.url) + '&name=' + encodeURIComponent(o.name));
      return { pages: d.pages, src: (p) => '/api/local/pdf/page?key=' + d.key + '&p=' + p };
    }
  }
  function applyZoom() {
    const box = $('#doc-pages');
    if (box) box.style.setProperty('--z', DOC.zoom);
  }

  // ---------------------------------------------------------------- 외부 입력 (HDMI)
  function renderInputs() {
    const body = $('#p-body');
    if (!N || !N.inputs) { body.innerHTML = '<div class="empty">' + ic('info') + '전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    let d;
    try { d = JSON.parse(N.inputs()); } catch (e) { d = { inputs: [], apps: [] }; }
    const btn = (x) => '<button class="in-btn" data-in="' + esc(x.id) + '"><span class="in-port">' + esc(x.type === '앱' ? '앱' : x.type) + '</span><b>' + esc(x.label) + '</b></button>';
    let html = '';
    if (d.inputs.length) html += '<div class="section-t">' + ic('tv') + '외부 입력</div><div class="in-grid">' + d.inputs.map(btn).join('') + '</div>';
    if (d.apps.length) html += '<div class="section-t">' + ic('apps') + '제조사 입력 전환 앱</div><div class="in-grid">' + d.apps.map(btn).join('') + '</div>';
    if (!html) {
      html = '<div class="fs-perm"><div class="tile">' + svgFor('hdmi') + '</div><h2>외부 입력을 찾지 못했습니다</h2>' +
        '<p>이 기기는 안드로이드 표준 외부 입력(TV 입력)을 제공하지 않고, 입력 전환 앱도 찾지 못했습니다. ' +
        '전자칠판 리모컨이나 본체의 입력(소스) 버튼으로 HDMI를 선택해 주세요.</p></div>';
    } else {
      html += '<p class="muted" style="margin-top:1.6rem;font-size:1.1rem">외부 입력 화면에서 돌아올 때는 화면 아래의 ○ 버튼을 누르세요.</p>';
    }
    body.innerHTML = '<div>' + html + '</div>';
    C.$$('[data-in]', body).forEach((b) => { b.onclick = () => { const r = native('openInput', b.dataset.in); if (r && !r.error) closePanel(); }; });
  }

  // ---------------------------------------------------------------- room info
  function renderRoom() {
    const seg = S.seg;
    const cur = seg.type === 'class' ? entryFor(seg.slot.p) : null;
    const nxSlot = seg.type === 'class' ? seg.next : seg.slot;
    const nx = nxSlot ? entryFor(nxSlot.p) : null;
    const homeroom = (S.data && S.data.timetable && S.data.timetable.homeroom) || '';
    const contacts = (S.state && S.state.contacts) || [];
    $('#p-body').innerHTML = '<div><div class="tool-grid">' +
      '<div class="tool">' + ic('school') + '<span>이 교실</span><b>' + esc(cls() ? classLabel(cls()) : '미지정') + '</b></div>' +
      '<div class="tool">' + ic('teacher') + '<span>담임</span><b>' + esc(homeroom ? homeroom + ' 선생님' : '정보 없음') + '</b></div>' +
      '<div class="tool">' + ic('user') + '<span>지금 수업</span><b>' + esc(cur ? cur.s + (cur.t ? ' · ' + cur.t + ' 선생님' : '') : '수업 시간이 아닙니다') + '</b></div>' +
      '<div class="tool">' + ic('right') + '<span>다음 수업</span><b>' + esc(nx ? nxSlot.p + '교시 ' + nx.s + (roomOf(nx) ? ' · ' + roomOf(nx) : '') : '없음') + '</b></div></div>' +
      '<div class="section-t">' + ic('phone') + '학교 연락처</div>' +
      (contacts.length ? contacts.map((c) => '<div class="list-row">' + ic('phone') + '<div class="grow"><b>' + esc(c.name) + '</b><span>' + esc([c.dept, c.location].filter(Boolean).join(' · ')) + '</span></div><b style="font-size:1.5rem;color:var(--accent)">' + esc(c.phone || '') + '</b></div>').join('') : '<div class="empty">등록된 연락처가 없습니다</div>') + '</div>';
  }

  // ---------------------------------------------------------------- settings app
  const SECTIONS = [
    { id: 'home', name: '홈 화면', icon: 'apps', c: '#f08c2e' },
    { id: 'display', name: '화면', icon: 'sun', c: '#3b82f6' },
    { id: 'sound', name: '소리', icon: 'volume', c: '#e5484d' },
    { id: 'network', name: '네트워크 · 서버', icon: 'wifi', c: '#14a3a3' },
    { id: 'bluetooth', name: '블루투스', icon: 'bluetooth', c: '#5b8cff' },
    { id: 'storage', name: '저장공간', icon: 'storage', c: '#64748b' },
    { id: 'alerts', name: '학급 알림', icon: 'bell', c: '#7d7bff', lock: true, group: '관리' },
    { id: 'classroom', name: '교실', icon: 'school', c: '#2fb87a', lock: true },
    { id: 'automation', name: '자동 실행 · 절전', icon: 'power', c: '#ff9f2e', lock: true },
    { id: 'permissions', name: '권한', icon: 'lock', c: '#a26bf5', lock: true },
    { id: 'about', name: '정보', icon: 'info', c: '#5b6474' },
  ];
  function unlocked() { return S.pin && S.pinUntil > Date.now(); }
  function renderSettings() {
    const body = $('#p-body');
    const sec = SECTIONS.find((s) => s.id === S.setSection) || SECTIONS[0];
    let nav = '';
    SECTIONS.forEach((s) => {
      if (s.group) nav += '<div class="group-t">' + s.group + '</div>';
      nav += '<button class="' + (s === sec ? 'on' : '') + '" data-sec="' + s.id + '"><span class="si" style="--c:' + s.c + '">' + ic(s.icon) + '</span>' + s.name + (s.lock ? ic(unlocked() ? 'check' : 'lock', 'lock') : '') + '</button>';
    });
    body.innerHTML = '<div class="settings"><div class="nav">' + nav + '</div><div class="content" id="set-c"></div></div>';
    C.$$('[data-sec]', body).forEach((b) => { b.onclick = () => { S.setSection = b.dataset.sec; renderSettings(); }; });
    const c = $('#set-c');
    if (sec.lock && !unlocked()) { pinPad(c, sec.name, () => renderSettings()); return; }
    if (unlocked()) S.pinUntil = Date.now() + 5 * 60 * 1000;
    const R = { home: setHomeScreen, display: setDisplay, sound: setSound, network: setNetwork, bluetooth: setBluetooth, storage: setStorage, alerts: setAlerts, classroom: setClassroom, automation: setAutomation, permissions: setPermissions, about: setAbout }[sec.id];
    R(c);
  }
  function refreshSettingsLive() {
    if (['network', 'bluetooth', 'storage', 'about'].indexOf(S.setSection) >= 0 && $('#set-c') && !$('#set-c').contains(document.activeElement)) renderSettings();
  }
  function row(label, desc, control) { return '<div class="set-row"><div class="lb"><b>' + label + '</b>' + (desc ? '<span>' + desc + '</span>' : '') + '</div>' + (control || '') + '</div>'; }
  function sw(id, on) { return '<label class="switch2"><input type="checkbox" id="' + id + '"' + (on ? ' checked' : '') + '><i></i></label>'; }
  function group(title, rows) { return '<div class="set-group">' + (title ? '<div class="gt">' + title + '</div>' : '') + rows + '</div>'; }
  function slider(id, min, max, val) { const p = (val - min) / Math.max(1, max - min) * 100; return '<input type="range" class="range" id="' + id + '" min="' + min + '" max="' + max + '" value="' + val + '" style="--p:' + p + '%">'; }
  function bindSlider(id, fn) { const s = $('#' + id); if (!s) return; s.oninput = () => { s.style.setProperty('--p', ((s.value - s.min) / Math.max(1, s.max - s.min) * 100) + '%'); fn(Number(s.value)); }; }
  async function saveSettings(patch) {
    const next = Object.assign({}, settings(), patch);
    try { await localPost('/api/local/settings', next); S.device = await C.get('/api/local/device', pinHeaders()); toast('저장했습니다', 1800); } catch (e) { toast(e.message); }
  }
  function setOpen(c) { C.$$('[data-open]', c).forEach((b) => { b.onclick = () => native('open', b.dataset.open); }); }

  function setHomeScreen(c) {
    const dock = dockIds();
    const all = Object.keys(APPS).filter((id) => !APPS[id].hidden).concat(listAndroidApps().map((a) => 'pkg:' + a.pkg));
    const h = (S.device && S.device.home) || {};
    const p = perfInfo();
    c.innerHTML = '<h1>홈 화면</h1>' +
      group('하단 앱 (' + dock.length + '/6)', dock.map((id, i) => '<div class="set-row dock-row">' + tileFor(id) + '<div class="lb"><b>' + esc(appLabel(id)) + '</b></div>' +
        '<button class="btn sm" data-mv="' + i + ':-1"' + (i === 0 ? ' disabled' : '') + '>' + ic('left') + '</button><button class="btn sm" data-mv="' + i + ':1"' + (i === dock.length - 1 ? ' disabled' : '') + '>' + ic('right') + '</button>' +
        '<button class="btn sm" data-rm="' + i + '">빼기</button></div>').join('') +
        '<div class="set-row" style="display:block"><div class="lb" style="margin-bottom:.8rem"><b>추가할 앱</b><span>누르면 하단에 들어갑니다. 홈 화면에서 아이콘을 길게 눌러도 됩니다.</span></div><div class="pick-grid">' +
        all.filter((id) => dock.indexOf(id) < 0).map((id) => '<button class="pick" data-add="' + esc(id) + '">' + tileFor(id) + '<span>' + esc(appLabel(id)) + '</span></button>').join('') + '</div></div>' +
        row('기본값으로', '시간표, 급식, 가정통신문, 칠판, 학급 알림', '<button class="btn sm" id="dock-reset">되돌리기</button>')) +
      group('수업 시작 전 알림', row('알림 켜기', '수업이 시작되기 전에 다음 교시와 과목을 화면에 띄웁니다. 수업 중과 수업이 끝날 때는 알리지 않습니다', sw('pc-on', h.preClass !== false)) +
        row('몇 분 전', '', '<div class="tabs">' + [1, 2, 3, 5].map((m) => '<button data-pcm="' + m + '" class="' + ((h.preClassMin || 2) === m ? 'on' : '') + '">' + m + '분</button>').join('') + '</div>') +
        row('알림음', '', sw('pc-snd', h.preClassSound !== false))) +
      group('검색', row('검색 엔진', '홈 화면 검색창과 인터넷 앱', '<div class="tabs">' + Object.keys(ENGINES).map((k) => '<button data-eng="' + k + '" class="' + ((h.search || 'naver') === k ? 'on' : '') + '">' + ENGINES[k][0] + '</button>').join('') + '</div>')) +
      group('성능', row('저사양 모드', '애니메이션과 그림자를 끄고 화면을 덜 자주 다시 그립니다' + (p ? ' · 이 기기: 메모리 ' + Math.round(p.totalMem / 1073741824 * 10) / 10 + 'GB, 코어 ' + p.cores + '개' : ''),
        '<div class="tabs">' + [['auto', '자동' + (liteAuto() ? '(켜짐)' : '(꺼짐)')], ['on', '켜기'], ['off', '끄기']].map((x) => '<button data-lite="' + x[0] + '" class="' + ((h.lite || 'auto') === x[0] ? 'on' : '') + '">' + x[1] + '</button>').join('') + '</div>'));
    const set = (next) => saveHome({ dock: next }).then(() => renderSettings());
    C.$$('[data-mv]', c).forEach((b) => { b.onclick = () => { const [i, d] = b.dataset.mv.split(':').map(Number); const n = dock.slice(); const t = n[i]; n[i] = n[i + d]; n[i + d] = t; set(n); }; });
    C.$$('[data-rm]', c).forEach((b) => { b.onclick = () => { const n = dock.slice(); n.splice(Number(b.dataset.rm), 1); set(n); }; });
    C.$$('[data-add]', c).forEach((b) => { b.onclick = () => { if (dock.length >= 6) { toast('하단에는 앱을 6개까지 둘 수 있습니다'); return; } set(dock.concat([b.dataset.add])); }; });
    $('#dock-reset').onclick = () => set(DEFAULT_DOCK.slice());
    $('#pc-on').onchange = (e) => saveHome({ preClass: e.target.checked });
    $('#pc-snd').onchange = (e) => saveHome({ preClassSound: e.target.checked });
    C.$$('[data-pcm]', c).forEach((b) => { b.onclick = () => saveHome({ preClassMin: Number(b.dataset.pcm) }).then(() => renderSettings()); });
    C.$$('[data-eng]', c).forEach((b) => { b.onclick = () => saveHome({ search: b.dataset.eng }).then(() => renderSettings()); });
    C.$$('[data-lite]', c).forEach((b) => { b.onclick = () => saveHome({ lite: b.dataset.lite }).then(() => { applyLite(); renderSettings(); }); });
  }

  // ---------------------------------------------------------------- 칠판 (blackboard)
  const BB_BG = {
    green: { name: '칠판', fill: '#264c3a', ink: ['#f7f7f2', '#ffe066', '#ff9fb2', '#8fd0ff', '#b6e36b'] },
    black: { name: '흑판', fill: '#1d2024', ink: ['#f7f7f2', '#ffe066', '#ff9fb2', '#8fd0ff', '#b6e36b'] },
    white: { name: '화이트보드', fill: '#fbfbf8', ink: ['#1f2328', '#2f6ae0', '#e0413a', '#1f9d5b', '#8a4fe0'] },
  };
  const BB = { bg: 'green', grid: false, pages: [[]], page: 0, color: 0, size: 1, eraser: false };
  function renderBoardApp() {
    const body = $('#p-body');
    const bg = BB_BG[BB.bg];
    body.innerHTML = '<div class="bb bb-' + BB.bg + (BB.grid ? ' grid' : '') + '"><canvas id="bb-c"></canvas>' +
      '<div class="bb-bar">' +
      bg.ink.map((c, i) => '<button class="bb-ink' + (!BB.eraser && BB.color === i ? ' on' : '') + '" data-ink="' + i + '" style="--ink:' + c + '"></button>').join('') +
      '<i class="bb-sep"></i>' +
      [0, 1, 2].map((s) => '<button class="bb-size' + (BB.size === s ? ' on' : '') + '" data-size="' + s + '"><i style="width:' + [0.5, 0.9, 1.4][s] + 'rem;height:' + [0.5, 0.9, 1.4][s] + 'rem"></i></button>').join('') +
      '<button class="bb-t' + (BB.eraser ? ' on' : '') + '" data-t="eraser">지우개</button><button class="bb-t" data-t="undo">되돌리기</button><button class="bb-t" data-t="clear">모두 지우기</button>' +
      '<i class="bb-sep"></i>' +
      '<button class="bb-t" data-t="bg">' + bg.name + '</button><button class="bb-t' + (BB.grid ? ' on' : '') + '" data-t="grid">모눈</button>' +
      '<i class="bb-sep"></i>' +
      '<button class="bb-t" data-t="prev"' + (BB.page === 0 ? ' disabled' : '') + '>' + ic('left') + '</button><span class="bb-pg">' + (BB.page + 1) + ' / ' + BB.pages.length + '</span>' +
      '<button class="bb-t" data-t="next">' + (BB.page === BB.pages.length - 1 ? ic('plus') : ic('right')) + '</button>' +
      '<button class="bb-t" data-t="save">저장</button><button class="bb-t" data-t="close">' + ic('x') + '</button></div></div>';
    const cv = $('#bb-c');
    const ctx = cv.getContext('2d', { desynchronized: true });
    const dpr = S.lite ? 1 : Math.min(window.devicePixelRatio || 1, 1.5);
    // Sizes come from layout (clientWidth), not getBoundingClientRect: the app window opens with a zoom
    // animation, and measuring the scaled box made strokes land below the finger.
    const fit = () => {
      if (!cv.isConnected) return;
      const w = cv.clientWidth, h = cv.clientHeight;
      if (cv.width === Math.round(w * dpr) && cv.height === Math.round(h * dpr)) return;
      cv.width = Math.round(w * dpr);
      cv.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      redraw();
    };
    const at = (e) => {
      const r = cv.getBoundingClientRect();
      const sx = cv.clientWidth / (r.width || 1), sy = cv.clientHeight / (r.height || 1);
      return [(e.clientX - r.left) * sx, (e.clientY - r.top) * sy];
    };
    const widths = () => [3, 6, 12][BB.size];
    const styleFor = (st) => {
      ctx.globalCompositeOperation = st.erase ? 'destination-out' : 'source-over';
      ctx.strokeStyle = st.c;
      ctx.lineWidth = st.erase ? st.w * 6 : st.w;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.globalAlpha = 1;
    };
    const drawStroke = (st, from) => {
      const p = st.pts;
      styleFor(st);
      ctx.beginPath();
      const i0 = Math.max(0, (from || 0) - 2);
      ctx.moveTo(p[i0], p[i0 + 1]);
      if (p.length === 2) ctx.lineTo(p[0] + 0.1, p[1] + 0.1);
      for (let i = i0 + 2; i < p.length; i += 2) ctx.lineTo(p[i], p[i + 1]);
      ctx.stroke();
    };
    function redraw() {
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, cv.width, cv.height);
      ctx.restore();
      BB.pages[BB.page].forEach((st) => drawStroke(st, 0));
    }
    const live = {};
    cv.addEventListener('pointerdown', (e) => {
      cv.setPointerCapture(e.pointerId);
      fit();
      const st = { c: bg.ink[BB.color], w: widths(), erase: BB.eraser, pts: at(e) };
      live[e.pointerId] = st;
      BB.pages[BB.page].push(st);
      drawStroke(st, 0);
    });
    cv.addEventListener('pointermove', (e) => {
      const st = live[e.pointerId];
      if (!st) return;
      const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
      const from = st.pts.length;
      (evs.length ? evs : [e]).forEach((x) => { const p = at(x); st.pts.push(p[0], p[1]); });
      drawStroke(st, from);
    });
    const end = (e) => { delete live[e.pointerId]; };
    cv.addEventListener('pointerup', end);
    cv.addEventListener('pointercancel', end);
    C.$$('[data-ink]', body).forEach((b) => { b.onclick = () => { BB.color = Number(b.dataset.ink); BB.eraser = false; renderBoardApp(); }; });
    C.$$('[data-size]', body).forEach((b) => { b.onclick = () => { BB.size = Number(b.dataset.size); renderBoardApp(); }; });
    C.$$('[data-t]', body).forEach((b) => {
      b.onclick = () => {
        const t = b.dataset.t, pg = BB.pages[BB.page];
        if (t === 'eraser') BB.eraser = !BB.eraser;
        else if (t === 'undo') { pg.pop(); redraw(); return; }
        else if (t === 'clear') { if (!pg.length) return; pg.length = 0; redraw(); return; }
        else if (t === 'bg') { const k = Object.keys(BB_BG); BB.bg = k[(k.indexOf(BB.bg) + 1) % k.length]; BB.color = 0; }
        else if (t === 'grid') BB.grid = !BB.grid;
        else if (t === 'prev') BB.page = Math.max(0, BB.page - 1);
        else if (t === 'next') { if (BB.page === BB.pages.length - 1) BB.pages.push([]); BB.page++; }
        else if (t === 'save') { saveBoard(cv); return; }
        else if (t === 'close') { closePanel(); return; }
        renderBoardApp();
      };
    });
    requestAnimationFrame(fit);
    setTimeout(fit, 60);
    setTimeout(fit, 500); // after the window's opening animation
  }
  function saveBoard(cv) {
    const out = document.createElement('canvas');
    out.width = cv.width;
    out.height = cv.height;
    const x = out.getContext('2d');
    x.fillStyle = BB_BG[BB.bg].fill;
    x.fillRect(0, 0, out.width, out.height);
    x.drawImage(cv, 0, 0);
    const d = new Date();
    const name = 'board_' + C.today().replace(/-/g, '') + '_' + C.pad(d.getHours()) + C.pad(d.getMinutes()) + C.pad(d.getSeconds());
    const r = native('saveImage', out.toDataURL('image/png'), name);
    if (r && r.ok) toast('칠판을 저장했습니다: ' + r.where);
  }

  function setDisplay(c) {
    const br = (S.sys && S.sys.brightness) || {};
    const st = settings();
    const dark = document.documentElement.dataset.theme !== 'light';
    c.innerHTML = '<h1>화면</h1>' +
      group('', '<div class="set-row slider"><div class="lb"><b>밝기</b><span>' + (br.auto ? '자동 밝기 사용 중' : '') + '</span></div><span class="val" id="bri-v">' + (br.value > 0 ? Math.round(br.value / 2.55) + '%' : '') + '</span>' + slider('bri', 1, 255, br.value > 0 ? br.value : 128) + '</div>' +
        row('자동 밝기', '주변 밝기에 맞춤' + (br.canWrite ? '' : ' (권한 필요)'), sw('bri-auto', br.auto)) +
        row('어두운 테마', '끄면 밝은 테마', sw('theme', dark))) +
      group('자동 화면 전환', row('수업 시간에 수업 화면', '교시가 시작되면 자동으로 전환', sw('autoLesson', st.autoLesson !== false)) +
        row('쉬는 시간 화면', '다음 수업까지 카운트다운', sw('autoBreak', st.autoBreak !== false))) +
      group('', row('디스플레이 설정', '안드로이드 설정 열기', '<button class="btn sm" data-open="display">열기</button>'));
    bindSlider('bri', (v) => { if (N) N.setBrightness(v); $('#bri-v').textContent = Math.round(v / 2.55) + '%'; });
    $('#bri-auto').onchange = (e) => { native('setAutoBrightness', e.target.checked); setTimeout(pollSys, 300); };
    $('#theme').onchange = (e) => {
      const th = e.target.checked ? '' : 'light';
      document.documentElement.dataset.theme = th;
      try { localStorage.setItem('cb_theme', th); } catch (x) { /* ignore */ }
    };
    // auto view switches are board-level settings; they are harmless, so no PIN is needed for them
    $('#autoLesson').onchange = (e) => saveSettingsNoPin({ autoLesson: e.target.checked });
    $('#autoBreak').onchange = (e) => saveSettingsNoPin({ autoBreak: e.target.checked });
    setOpen(c);
  }
  async function saveSettingsNoPin(patch) {
    if (!unlocked()) { toast('설정 → 학급 알림 등 관리 항목에서 PIN을 입력한 뒤 바꿀 수 있습니다', 4000); renderSettings(); return; }
    saveSettings(patch);
  }
  function setSound(c) {
    const vol = (S.sys && S.sys.volume) || { cur: 0, max: 15 };
    c.innerHTML = '<h1>소리</h1>' + group('', '<div class="set-row slider"><div class="lb"><b>미디어 볼륨</b></div><span class="val" id="vol-v">' + vol.cur + ' / ' + vol.max + '</span>' + slider('vol', 0, vol.max, vol.cur) + '</div>' +
      row('알림음 들어 보기', '학급 알림 "조용히 해주세요" 벨 소리', '<button class="btn sm" id="t-quiet">' + ic('play') + '재생</button>') +
      row('소리 설정', '안드로이드 설정 열기', '<button class="btn sm" data-open="sound">열기</button>'));
    bindSlider('vol', (v) => { if (N) N.setVolume(v); $('#vol-v').textContent = v + ' / ' + vol.max; });
    $('#t-quiet').onclick = () => { if (N) N.chime('quiet'); };
    setOpen(c);
  }
  function setNetwork(c) {
    const net = (S.sys && S.sys.network) || {};
    const d = S.device || {};
    const sy = d.sync || {};
    c.innerHTML = '<h1>네트워크 · 서버</h1>' +
      group('학교 서버', row('연결 상태', esc(sy.hubUrl || d.hubUrl || ''), '<span class="chip ' + (sy.online ? 'green' : 'red') + '">' + (sy.online ? '연결됨' : '끊김') + '</span>') +
        row('마지막 동기화', sy.lastOk ? C.ago(sy.lastOk) : '아직 없음', '') + (sy.error && !sy.online ? row('오류', esc(sy.error), '') : '') +
        row('연결 확인', '서버에 지금 접속해 봅니다', '<button class="btn sm" id="probe">' + ic('refresh') + '확인</button>')) +
      group('Wi-Fi', row('연결', net.connected ? (net.transport === 'wifi' ? esc(net.ssid || 'Wi-Fi (이름 확인: 위치 권한 필요)') : net.transport === 'ethernet' ? '유선 LAN' : esc(net.transport || '')) : '연결 없음', '<button class="btn sm" data-open="wifi">Wi-Fi 설정</button>') +
        (net.connected ? row('인터넷', net.internet ? '사용 가능' : '확인 안 됨', '') : '') + (net.ip ? row('IP 주소', esc(net.ip), '') : '') +
        (net.rssi != null ? row('신호', net.rssi + ' dBm · ' + (net.level + 1) + '/5 · ' + net.linkMbps + ' Mbps · ' + (net.freqMHz > 4900 ? '5GHz' : '2.4GHz'), '') : ''));
    $('#probe').onclick = async () => {
      $('#probe').disabled = true;
      try { const r = await C.get('/api/local/probe', pinHeaders()); toast(r.ok ? '서버 연결 정상 (' + r.url + ')' : '서버에 연결할 수 없습니다: ' + r.error, 5000); } catch (e) { toast(e.message); }
      $('#probe').disabled = false;
    };
    setOpen(c);
  }
  function setBluetooth(c) {
    const bt = (S.sys && S.sys.bluetooth) || {};
    let rows = '';
    if (!bt.supported) rows = row('블루투스', '지원하지 않는 기기입니다', '');
    else if (bt.permission === false) rows = row('블루투스 권한', '연결된 기기를 보려면 권한이 필요합니다', '<button class="btn sm" data-open="bluetoothPerm">허용</button>');
    else rows = row('상태', (bt.enabled ? '켜짐' : '꺼짐') + (bt.name ? ' · ' + esc(bt.name) : ''), '') + ((bt.bonded || []).map((d) => row(esc(d.name || d.address), esc(d.address), '<span class="chip ' + (d.connected ? 'green' : 'gray') + '">' + (d.connected ? '연결됨' : '등록됨') + '</span>')).join('') || row('등록된 기기', '없음', ''));
    c.innerHTML = '<h1>블루투스</h1>' + group('', rows + row('블루투스 설정', '기기 연결 · 해제', '<button class="btn sm" data-open="bluetooth">열기</button>'));
    setOpen(c);
  }
  function setStorage(c) {
    const s = (S.sys && S.sys.storage) || [];
    c.innerHTML = '<h1>저장공간</h1>' + group('', s.map((v) => '<div class="set-row" style="flex-wrap:wrap"><div class="lb"><b>' + esc(v.label) + '</b><span>' + (v.total ? C.bytes(v.free) + ' 남음 / ' + C.bytes(v.total) : esc(v.state || '')) + '</span></div>' +
      (v.total ? '<div class="bar" style="flex-basis:100%"><i style="width:' + Math.round((v.total - v.free) / v.total * 100) + '%"></i></div>' : '') + '</div>').join('') +
      row('저장공간 설정', '안드로이드 설정 열기', '<button class="btn sm" data-open="storage">열기</button>'));
    setOpen(c);
  }
  function setAlerts(c) {
    const st = settings();
    c.innerHTML = '<h1>학급 알림</h1><p class="muted" style="margin:-.6rem 0 1.4rem;font-size:1.1rem">누르면 이 전자칠판에 크게 표시됩니다.</p>' +
      '<div class="ca-grid">' + ALERTS.map((a, i) => '<button class="ca-btn" data-ca="' + i + '" style="--c:' + a.c + '">' + ic(a.icon) + '<div>' + esc(a.label) + '<span>' + esc(a.sub) + '</span></div></button>').join('') + '</div>' +
      group('직접 입력', '<div class="set-row"><div class="lb" style="flex:1"><input id="ca-text" type="text" placeholder="예: 5분 뒤 강당으로 이동합니다" style="width:100%"></div><button class="btn pri" id="ca-send">' + ic('send') + '띄우기</button></div>') +
      group('설정', row('학급 알림 사용', '', sw('ca-on', st.classAlerts !== false)) + row('알림음', '"조용히 해주세요"는 크게 울리는 벨', sw('ca-sound', st.alertSound !== false)) +
        row('표시 시간', '초', '<input type="number" id="ca-sec" min="3" max="60" value="' + (st.alertSeconds || 8) + '" style="width:8rem">') +
        row('다시 보내기 대기', '초 (연속으로 누르는 것 방지)', '<input type="number" id="ca-cool" min="0" max="120" value="' + (st.alertCooldown == null ? 10 : st.alertCooldown) + '" style="width:8rem">') +
        row('', '', '<button class="btn pri" id="ca-save">' + ic('check') + '저장</button>'));
    C.$$('[data-ca]', c).forEach((b) => { b.onclick = () => fireClassAlert(ALERTS[Number(b.dataset.ca)]); });
    $('#ca-send').onclick = () => {
      const t = $('#ca-text').value.trim();
      if (!t) { toast('띄울 문구를 입력하세요'); return; }
      if (S.seg.type === 'class') { toast('수업 중에는 학급 알림을 보낼 수 없습니다'); return; }
      fireClassAlert({ label: t, sub: '', icon: 'megaphone', color: 'violet', sound: 'soft' });
    };
    $('#ca-save').onclick = () => saveSettings({ classAlerts: $('#ca-on').checked, alertSound: $('#ca-sound').checked, alertSeconds: Math.max(3, Number($('#ca-sec').value) || 8), alertCooldown: Math.max(0, Number($('#ca-cool').value) || 0) });
  }
  function setClassroom(c) {
    const d = S.device || {};
    c.innerHTML = '<h1>교실</h1>' + group('', '<div class="set-row"><div class="lb"><b>학년 · 반</b><span>시간표와 급식 표시 기준</span></div><input id="s-g" type="number" min="1" max="6" value="' + esc((d.cls || '').split('-')[0] || '') + '" style="width:7rem"> <input id="s-c" type="number" min="1" max="30" value="' + esc((d.cls || '').split('-')[1] || '') + '" style="width:7rem"></div>' +
      row('기기 이름', '서버 상태 페이지에 표시', '<input id="s-name" type="text" value="' + esc(d.name || '') + '">') +
      row('', '', '<button class="btn pri" id="s-save">' + ic('check') + '저장</button>')) +
      group('설치 PIN', row('새 PIN', '관리 항목을 잠그는 PIN (4자리 이상)', '<input id="s-pin" type="password" inputmode="numeric" style="width:12rem">') + row('', '', '<button class="btn" id="s-pin-save">PIN 변경</button>')) +
      group('학교 서버 주소', row('주소', '비워 두면 기본 서버 ' + esc(d.defaultServer || ''), '<input id="s-srv" type="text" value="' + esc(d.serverOverride ? d.hubUrl : '') + '" placeholder="' + esc(d.defaultServer || '') + '" style="width:26rem">') +
        row('', '', '<button class="btn" id="s-srv-save">주소 저장</button>'));
    $('#s-srv-save').onclick = async () => {
      try { await localPost('/api/local/device', { serverUrl: $('#s-srv').value.trim() }); S.device = await C.get('/api/local/device', pinHeaders()); toast('서버 주소: ' + S.device.hubUrl); setTimeout(loadData, 3000); } catch (e) { toast(e.message); }
    };
    $('#s-save').onclick = async () => {
      const g = $('#s-g').value.trim(), k = $('#s-c').value.trim();
      if ((g || k) && !validCls(g, k)) { toast('학년은 1~6, 반은 1~30 사이 숫자로 입력하세요'); return; }
      try { await localPost('/api/local/device', { cls: g && k ? g + '-' + k : '', name: $('#s-name').value.trim() }); S.device = await C.get('/api/local/device', pinHeaders()); toast('저장했습니다'); loadData(); } catch (e) { toast(e.message); }
    };
    $('#s-pin-save').onclick = async () => {
      const p = $('#s-pin').value;
      if (p.length < 4) { toast('PIN은 4자리 이상이어야 합니다'); return; }
      try { await localPost('/api/local/device', { pin: p }); S.pin = p; toast('PIN을 바꿨습니다'); } catch (e) { toast(e.message); }
    };
  }
  function setAutomation(c) {
    const st = settings();
    const pw = st.power || {};
    let apps = [];
    if (N) { try { apps = JSON.parse(N.apps()); } catch (e) { apps = []; } }
    const rules = st.launchRules || [];
    c.innerHTML = '<h1>자동 실행 · 절전</h1>' +
      group('절전', row('일과 시간 외 화면 끄기', '기기 관리자 권한이 있으면 화면을 잠급니다', sw('pw', pw.enabled)) +
        row('켜짐', '', '<input id="pw-on" type="time" value="' + esc(pw.on || '07:30') + '" style="width:12rem">') + row('꺼짐', '', '<input id="pw-off" type="time" value="' + esc(pw.off || '17:30') + '" style="width:12rem">') +
        row('주말에도 켜기', '', sw('pw-we', pw.weekends)) + row('수업 종료 시 초기화', '수업이 끝나면 화면 메모를 지우고 홈으로 (기본 꺼짐)', sw('reset', st.resetOnEnd === true)) +
        row('다른 앱 위 탐색 버튼', '뒤로 · 홈 · 최근 앱 막대', sw('fnav', st.floatingNav !== false)) + row('', '', '<button class="btn pri" id="pw-save">' + ic('check') + '저장</button>')) +
      group('수업 시작 시 자동 실행 앱', (rules.length ? rules.map((r, i) => row((r.period ? r.period + '교시' : '모든 교시') + (r.dow ? ' · ' + C.DOW[r.dow - 1] + '요일' : ''), esc((apps.find((a) => a.pkg === r.pkg) || { label: r.pkg }).label), '<button class="btn sm" data-del-rule="' + i + '">' + ic('trash') + '</button>')).join('') : row('규칙 없음', '', '')) +
        '<div class="set-row"><select id="r-p"><option value="0">모든 교시</option>' + [1, 2, 3, 4, 5, 6, 7].map((p) => '<option value="' + p + '">' + p + '교시</option>').join('') + '</select>' +
        '<select id="r-d"><option value="0">매일</option>' + [2, 3, 4, 5, 6].map((x) => '<option value="' + x + '">' + C.DOW[x - 1] + '요일</option>').join('') + '</select>' +
        '<select id="r-a" style="flex:1">' + apps.map((a) => '<option value="' + esc(a.pkg) + '">' + esc(a.label) + '</option>').join('') + '</select><button class="btn" id="r-add">' + ic('plus') + '추가</button></div>');
    $('#pw-save').onclick = () => saveSettings({ power: { enabled: $('#pw').checked, on: $('#pw-on').value || '07:30', off: $('#pw-off').value || '17:30', weekends: $('#pw-we').checked }, resetOnEnd: $('#reset').checked, floatingNav: $('#fnav').checked });
    $('#r-add').onclick = () => saveSettings({ launchRules: rules.concat([{ period: Number($('#r-p').value), dow: Number($('#r-d').value), pkg: $('#r-a').value }]) }).then(() => renderSettings());
    C.$$('[data-del-rule]', c).forEach((b) => { b.onclick = () => { const r = rules.slice(); r.splice(Number(b.dataset.delRule), 1); saveSettings({ launchRules: r }).then(() => renderSettings()); }; });
  }
  function setPermissions(c) {
    const perms = (S.device && S.device.perms) || {};
    const rows = [
      ['defaultHome', '기본 홈 앱', '전원을 켜면 이 화면이 바로 뜸', 'home'], ['overlay', '다른 앱 위에 표시', '화면 메모, 탐색 버튼', 'overlay'],
      ['writeSettings', '시스템 설정 변경', '밝기 조절', 'writeSettings'], ['accessibility', '접근성 서비스', '뒤로 · 최근 앱, 화면 분할', 'accessibility'],
      ['deviceAdmin', '기기 관리자', '일과 종료 후 화면 끄기', 'deviceAdmin'], ['notifications', '알림', '실행 상태 표시', 'notifications'],
      ['location', '위치', 'Wi-Fi 이름 표시', 'location'], ['bluetooth', '블루투스', '연결된 기기 표시', 'bluetoothPerm'], ['microphone', '마이크', '화면 녹화 소리', 'microphone'], ['allFiles', '모든 파일 접근', '파일 앱 (내부 저장공간 · USB)', 'allFiles'],
    ];
    c.innerHTML = '<h1>권한</h1>' + group('', rows.map((p) => '<div class="set-row"><span class="dot' + (perms[p[0]] ? ' on' : '') + '"></span><div class="lb"><b>' + p[1] + '</b><span>' + p[2] + '</span></div><button class="btn sm" data-open="' + p[3] + '">' + (perms[p[0]] ? '설정' : '허용') + '</button></div>').join('')) +
      group('시스템', row('안드로이드 설정', '', '<button class="btn sm" data-open="settings">열기</button>') + row('날짜 · 시간', '', '<button class="btn sm" data-open="date">열기</button>') + row('앱 정보', '', '<button class="btn sm" data-open="appInfo">열기</button>'));
    setOpen(c);
  }
  function setAbout(c) {
    const d = S.device || {};
    const data = S.data || {};
    const at = (k) => data[k] && data[k].fetchedAt ? C.ago(data[k].fetchedAt) : '없음';
    const errs = data.errors || {};
    c.innerHTML = '<h1>정보</h1>' + group('', row('중동중학교 전자칠판', '버전 ' + esc(d.version || ''), '') + row('학교 서버', esc((d.sync && d.sync.hubUrl) || d.hubUrl || ''), '') + row('기기 ID', esc(d.deviceId || ''), '')) +
      group('데이터', row('시간표', ttSource() || '없음', '') + row('급식', at('meals'), '') + row('학사일정', at('schedule'), '') + row('날씨', at('weather'), '') + row('홈페이지', at('homepage'), '') +
        Object.keys(errs).map((k) => row('오류: ' + esc(k), esc(errs[k].message), '')).join('') +
        row('새로고침', '지금 모든 데이터를 다시 받습니다', '<button class="btn sm" id="refresh">' + ic('refresh') + '새로고침</button>') +
        row('화면 다시 불러오기', '', '<button class="btn sm" id="reload">' + ic('reset') + '다시 불러오기</button>'));
    $('#refresh').onclick = async () => { await C.post('/api/local/refresh', {}, pinHeaders()).catch(() => {}); toast('새로고침을 요청했습니다'); setTimeout(loadData, 5000); };
    $('#reload').onclick = () => { if (N) N.reload(); else location.reload(); };
  }

  function pinPad(target, purpose, done) {
    let pin = '';
    target.innerHTML = '<div class="pin" id="pinbox"><div style="width:4.4rem;height:4.4rem;border-radius:1.2rem;background:var(--card2);display:flex;align-items:center;justify-content:center">' + ic('lock') + '</div><h2>' + esc(purpose) + '</h2><p>설치할 때 정한 PIN을 입력하세요</p><div class="dots" id="pdots"></div><div class="keys">' +
      [1, 2, 3, 4, 5, 6, 7, 8, 9, 'C', 0, '확인'].map((k) => '<button data-k="' + k + '">' + k + '</button>').join('') + '</div><div class="err" id="perr"></div></div>';
    const dots = () => { $('#pdots').innerHTML = pin.split('').map(() => '<i class="f"></i>').join('') + (pin.length < 4 ? '<i></i>'.repeat(4 - pin.length) : ''); };
    dots();
    C.$$('[data-k]', target).forEach((b) => {
      b.onclick = async () => {
        const k = b.dataset.k;
        if (k === 'C') pin = '';
        else if (k === '확인') {
          try {
            const res = await fetch('/api/local/pin', { method: 'POST', headers: { 'X-Pin': pin } });
            if (!res.ok) throw new Error('PIN이 올바르지 않습니다');
            S.pin = pin; S.pinUntil = Date.now() + 5 * 60 * 1000;
            done();
            return;
          } catch (e) {
            $('#perr').textContent = e.message; pin = '';
            const box = $('#pinbox'); box.classList.remove('shake'); void box.offsetWidth; box.classList.add('shake');
          }
        } else if (pin.length < 12) pin += k;
        dots();
      };
    });
  }

  // ---------------------------------------------------------------- first-run setup
  function setupWizard() {
    const wrap = $('#setup');
    const box = $('#setup-box');
    wrap.classList.add('on');
    const W = { cls: '', name: '' };
    const steps = (n) => '<div class="steps">' + [1, 2, 3].map((i) => '<i class="' + (i <= n ? 'on' : '') + '"></i>').join('') + '</div>';

    function step1() {
      box.innerHTML = '<div class="step">' + steps(1) + '<h1>중동중학교 전자칠판</h1><p class="lead">이 전자칠판이 있는 교실을 알려 주세요. 시간표와 급식은 학교 서버에서 자동으로 받아 옵니다.</p><div class="form">' +
        '<div class="row2"><label>학년<input id="w-g" type="number" min="1" max="6" inputmode="numeric"></label><label>반<input id="w-c" type="number" min="1" max="30" inputmode="numeric"></label></div>' +
        '<label>기기 이름 (선택)<input id="w-name" placeholder="예: 3학년 2반 전자칠판"></label><div class="err-text" id="w-err"></div>' +
        '<div><button class="btn pri" id="w-next">다음 ' + ic('right') + '</button></div></div></div>';
      $('#w-next').onclick = () => {
        const g = $('#w-g').value.trim(), c = $('#w-c').value.trim();
        if (!validCls(g, c)) { $('#w-err').textContent = '학년은 1~6, 반은 1~30 사이 숫자로 입력하세요'; return; }
        W.cls = g + '-' + c;
        W.name = $('#w-name').value.trim() || g + '학년 ' + c + '반';
        step2();
      };
    }
    function step2() {
      box.innerHTML = '<div class="step">' + steps(2) + '<h1>설치 PIN 만들기</h1><p class="lead">설정 앱의 관리 항목(학급 알림, 교실, 자동 실행, 권한)을 잠그는 PIN입니다. 4자리 이상 숫자로 정하세요.</p><div class="form">' +
        '<label>PIN<input id="w-pin" type="password" inputmode="numeric" autocomplete="new-password"></label><label>PIN 확인<input id="w-pin2" type="password" inputmode="numeric"></label><div class="err-text" id="w-err"></div>' +
        '<div style="display:flex;gap:.6rem"><button class="btn" id="w-back">' + ic('left') + '이전</button><button class="btn pri" id="w-next">완료</button></div></div></div>';
      $('#w-back').onclick = step1;
      $('#w-next').onclick = async () => {
        const p = $('#w-pin').value;
        if (p.length < 4) { $('#w-err').textContent = 'PIN은 4자리 이상이어야 합니다'; return; }
        if (p !== $('#w-pin2').value) { $('#w-err').textContent = 'PIN이 서로 다릅니다'; return; }
        try {
          await C.post('/api/local/setup', { cls: W.cls, name: W.name, pin: p }, pinHeaders());
          S.device = await C.get('/api/local/device', pinHeaders());
          step3();
        } catch (e) { $('#w-err').textContent = e.message; }
      };
    }
    async function step3() {
      box.innerHTML = '<div class="step">' + steps(3) + '<h1>학교 서버 연결</h1><p class="lead" id="w-s"><span class="dots-loader"><i></i><i></i><i></i>' + esc(S.device.hubUrl) + ' 에 연결하는 중</span></p><div><button class="btn pri" id="w-go">시작하기 ' + ic('right') + '</button></div></div>';
      $('#w-go').onclick = finish;
      try {
        const r = await C.get('/api/local/probe', pinHeaders());
        $('#w-s').innerHTML = r.ok ? '<span class="ok-text">' + ic('check') + ' 학교 서버에 연결되었습니다.</span>' : '<span class="err-text">학교 서버에 아직 연결되지 않았습니다 (' + esc(r.error) + '). 연결되면 자동으로 데이터를 받아 오고, 그전까지는 인터넷에서 직접 받아 옵니다.</span>';
      } catch (e) { $('#w-s').textContent = e.message; }
    }
    function finish() {
      wrap.classList.remove('on');
      bootStatus('학교 데이터 불러오는 중');
      start();
    }
    step1();
  }

  boot();
})();
