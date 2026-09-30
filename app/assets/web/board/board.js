/* Classroom board UI. All data comes from the school hub (state), NEIS / Comcigan (local data) and the device itself (Native). */
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
    sys: null,
    view: 'home',
    manualView: null,
    manualSeg: '',
    ttOffset: 0,
    mealPick: null,
    seg: { type: 'none' },
    segKey: '',
    admin: null, // { token, until }
    shownClassAlerts: {},
    localAcks: {},
    timer: { mode: 'period', running: false, endAt: 0, remain: 0, preset: 0 },
    slideIdx: 0,
    lastSlideAt: 0,
    panel: null,
    splitMode: false,
    booted: false,
  };

  // ---------------------------------------------------------------- utils
  function ic(name, cls) { return window.icon(name, cls); }
  function fillIcons(root) { C.$$('[data-ic]', root).forEach((el) => { el.outerHTML = ic(el.getAttribute('data-ic')); }); }
  function toast(msg, ms) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('on'), ms || 3500);
  }
  function native(fn) {
    if (!N) { toast('전자칠판 앱에서만 사용할 수 있는 기능입니다'); return null; }
    try {
      const args = Array.prototype.slice.call(arguments, 1);
      const r = N[fn].apply(N, args);
      if (typeof r === 'string' && r.charAt(0) === '{' || typeof r === 'string' && r.charAt(0) === '[') {
        const o = JSON.parse(r);
        if (o && o.error) { toast(o.error, 5000); }
        return o;
      }
      return r;
    } catch (e) { toast(String(e.message || e)); return null; }
  }
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const cls = () => (S.device && S.device.cls) || '';
  const grade = () => { const c = cls(); return c ? Number(c.split('-')[0]) : 0; };
  const config = () => (S.state && S.state.config) || {};
  const classState = () => (S.state && S.state.classes && S.state.classes[cls()]) || null;
  const settings = () => (S.device && S.device.settings) || {};
  function portalBase() {
    if (!S.device) return '';
    if (S.device.role === 'hub') return 'http://' + (S.device.ip || '127.0.0.1') + ':' + S.device.port;
    return S.device.hubUrl || ('http://' + S.device.ip + ':' + S.device.port);
  }
  function validCls(g, c) { const G = Number(g), K = Number(c); return G >= 1 && G <= 6 && K >= 1 && K <= 30 && /^\d+$/.test(g) && /^\d+$/.test(c); }
  function classLabel(c) { if (!c) return ''; const p = c.split('-'); return p[0] + '학년 ' + p[1] + '반'; }

  // ---------------------------------------------------------------- boot
  async function boot() {
    fillIcons(document);
    $('#p-close').innerHTML = ic('x');
    $('#p-close').onclick = closePanel;
    $('#scrim').onclick = closePanel;
    try { const th = localStorage.getItem('cb_theme'); if (th) document.documentElement.dataset.theme = th; } catch (e) { /* ignore */ }
    tickClock();
    setInterval(tickClock, 1000);
    for (;;) {
      try { S.device = await C.get('/api/local/device'); break; } catch (e) { await sleep(800); }
    }
    if (!S.device.role) { setupWizard(); return; }
    start();
  }

  function start() {
    if (S.booted) return;
    S.booted = true;
    renderNavKeys();
    renderDock();
    stateLoop();
    loadData();
    pollSys();
    setInterval(loadData, 5 * 60 * 1000);
    setInterval(pollSys, 20000);
    setInterval(tick, 1000);
    setInterval(() => { if (S.device) C.get('/api/local/device').then((d) => { S.device = d; renderTop(); }).catch(() => {}); }, 30000);
    $('#tt-icon').outerHTML = ic('clock');
    C.$$('#tt-tabs button').forEach((b) => { b.onclick = () => { S.ttOffset = Number(b.dataset.d); C.$$('#tt-tabs button').forEach((x) => x.classList.toggle('on', x === b)); renderTimetable(); }; });
    $('#em-ack').onclick = ackEmergency;
    $('#att-qr-btn').innerHTML = ic('qr') + ' QR 출석';
    $('#att-qr-btn').onclick = () => openQrBig('att');
  }

  async function stateLoop() {
    for (;;) {
      try {
        const r = await C.get('/api/state?wait=1&since=' + S.rev);
        S.online = r.online !== false && !r.offline;
        if (r.state) { S.state = r.state; S.rev = r.rev; onState(); }
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
    try { S.data = await C.get('/api/local/data?cls=' + encodeURIComponent(cls())); } catch (e) { /* keep cache */ }
    renderAll();
  }

  function pollSys() {
    if (!N) return;
    try { S.sys = JSON.parse(N.sys()); } catch (e) { S.sys = null; }
    renderTop();
    if (S.panel === 'quick') renderQuick();
  }

  window.onNative = function (ev, data) {
    switch (ev) {
      case 'state': break;
      case 'data': loadData(); break;
      case 'device':
        C.get('/api/local/device').then((d) => {
          S.device = d;
          // Setup finished elsewhere (e.g. from the admin portal): leave the wizard.
          if (d.role && !S.booted && !S.inWizardAdmin) { $('#setup').classList.remove('on'); start(); }
          renderAll();
        });
        break;
      case 'segment': S.manualView = null; tick(true); break;
      case 'reset': classEndReset(); break;
      case 'back': navKey('back'); break;
      case 'recents': openPanel('apps'); break;
      case 'home': closePanel(); S.manualView = null; setView(autoView()); break;
      case 'resume': pollSys(); break;
      case 'sleep': $('#sleep').classList.add('on'); break;
      case 'perms': if (S.panel === 'settings') renderSettings(); pollSys(); break;
      case 'folder': if (S.panel === 'files') renderFiles(); break;
      case 'capture':
        if (!data.ok) toast(data.error || '캡처 실패', 5000);
        else if (data.kind === 'shot') toast('화면을 저장했습니다: ' + (data.where || ''));
        else if (data.kind === 'recording') { toast(data.mic ? '녹화를 시작했습니다 (마이크 포함)' : '녹화를 시작했습니다'); pollSys(); renderDock(); }
        else if (data.kind === 'recorded') { toast('녹화를 저장했습니다: ' + (data.where || '')); pollSys(); renderDock(); }
        break;
      case 'upload': toast(data.ok ? '"' + data.name + '" 업로드 완료' : '업로드 실패: ' + (data.error || data.body), 5000); break;
      case 'opened': if (!data.ok) toast('열기 실패: ' + data.error, 5000); break;
      default: break;
    }
  };
  document.addEventListener('pointerdown', () => { $('#sleep').classList.remove('on'); }, true);

  // ---------------------------------------------------------------- state reactions
  function onState() {
    // A teacher pressed "show on board now": switch to the lesson screen immediately.
    const ln = S.state && S.state.lessonNow && S.state.lessonNow[cls()];
    const lnKey = ln ? ln.id + ':' + ln.at : '';
    if (S.lessonNowKey !== undefined && lnKey && lnKey !== S.lessonNowKey && Date.now() - ln.at < 60000) {
      closePanel();
      S.manualView = 'lesson';
      setView('lesson');
    }
    S.lessonNowKey = lnKey;
    renderAll();
    checkEmergency();
    checkClassAlerts();
    renderTicker();
    if (S.panel && S.panel !== 'apps' && S.panel !== 'files' && S.panel !== 'settings' && S.panel !== 'portal') refreshPanel();
  }

  function renderAll() {
    if (!S.device) return;
    renderTop();
    tick(true);
    renderHome();
    renderLesson();
    renderBreak();
  }

  function targetsMe(t) {
    if (!t || t === 'all') return true;
    if (t.indexOf('-') >= 0) return t === cls();
    return cls().indexOf(t + '-') === 0;
  }

  function activeEmergency() {
    const alerts = (S.state && S.state.alerts) || [];
    let act = null;
    let acked = {};
    if (N) { try { JSON.parse(N.acks()).forEach((id) => { acked[id] = true; }); } catch (e) { /* ignore */ } }
    alerts.forEach((a) => {
      if (!a.active || !targetsMe(a.target)) return;
      if ((a.acks && a.acks[S.device.deviceId]) || acked[a.id] || S.localAcks[a.id]) return;
      act = a;
    });
    return act;
  }

  function checkEmergency() {
    const a = activeEmergency();
    const el = $('#emergency');
    if (!a) { el.classList.remove('on'); return; }
    $('#em-title').textContent = a.title || '긴급 안내';
    $('#em-text').textContent = a.text;
    $('#em-meta').textContent = C.dateTime(a.at) + ' · ' + (a.author || '');
    el.dataset.id = a.id;
    el.classList.add('on');
  }

  async function ackEmergency() {
    const id = $('#emergency').dataset.id;
    S.localAcks[id] = true;
    $('#emergency').classList.remove('on');
    try { await C.post('/api/local/ack', { alertId: id }); } catch (e) { toast('확인 전송 실패: ' + e.message); }
    checkEmergency();
  }

  function checkClassAlerts() {
    const list = (S.state && S.state.classAlerts) || [];
    const firstRun = !checkClassAlerts.ran;
    checkClassAlerts.ran = true;
    list.forEach((a) => {
      if (S.shownClassAlerts[a.id]) return;
      S.shownClassAlerts[a.id] = true;
      const st = classState();
      const secs = (st && st.officerSettings && st.officerSettings.durationSec) || 8;
      // Skip alerts that already expired (e.g. shown by the native overlay while another app was open).
      if (firstRun || a.cls !== cls() || Date.now() - a.at > secs * 1000) return;
      showClassAlert(a.type);
    });
  }

  function showClassAlert(type) {
    const t = C.officerType(type);
    const st = classState();
    const secs = (st && st.officerSettings && st.officerSettings.durationSec) || 8;
    const el = $('#class-alert');
    el.className = 'class-alert c-' + t.color;
    el.innerHTML = ic(t.icon) + '<div class="t" style="color:var(--text)">' + esc(t.type === 'teacher' ? '선생님을 호출했습니다' : t.label) + '</div><div class="s">' + esc(t.sub) + '</div><div class="bar2"><i></i></div>';
    requestAnimationFrame(() => {
      el.classList.add('on');
      const bar = el.querySelector('.bar2 i');
      bar.style.transition = 'transform ' + secs + 's linear';
      requestAnimationFrame(() => { bar.style.transform = 'scaleX(0)'; });
    });
    el.onclick = () => el.classList.remove('on');
    clearTimeout(showClassAlert._t);
    showClassAlert._t = setTimeout(() => el.classList.remove('on'), secs * 1000);
  }

  function renderTicker() {
    const b = S.state && S.state.broadcast;
    const el = $('#ticker');
    if (b && b.live) {
      $('#ticker-cap').textContent = (b.title ? '[' + b.title + '] ' : '') + (b.caption || '방송 중입니다');
      el.classList.add('on');
    } else el.classList.remove('on');
  }

  // ---------------------------------------------------------------- top bar
  function tickClock() {
    const d = new Date();
    $('#t-time').textContent = C.pad(d.getHours()) + ':' + C.pad(d.getMinutes());
    $('#t-date').textContent = d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일 ' + C.DOW[d.getDay()] + '요일';
  }

  function renderTop() {
    if (!S.device) return;
    const cfg = config();
    $('#t-school').textContent = cfg.displayName || (cfg.school && cfg.school.name) || (S.state ? '학교 설정 필요' : '학교 서버 연결 중');
    $('#t-class').textContent = cls() ? classLabel(cls()) : (S.device.name || '교실 미지정');
    const st = [];
    const hubOk = S.online && S.state;
    st.push('<span class="st' + (hubOk ? '' : ' bad') + '">' + ic('school') + (hubOk ? (S.device.role === 'hub' ? '학교 서버' : '서버 연결') : '서버 끊김') + '</span>');
    const net = S.sys && S.sys.network;
    if (net) {
      if (!net.connected) st.push('<span class="st bad">' + ic('wifiOff') + '네트워크 없음</span>');
      else if (net.transport === 'ethernet') st.push('<span class="st">' + ic('ethernet') + '유선</span>');
      else st.push('<span class="st' + (net.internet ? '' : ' bad') + '">' + ic('wifi') + esc(net.ssid || 'Wi-Fi') + (net.internet ? '' : ' (인터넷 없음)') + '</span>');
    }
    if (S.sys && S.sys.device && S.sys.device.recording) st.push('<span class="st bad">' + ic('rec') + '녹화 중</span>');
    $('#t-status').innerHTML = st.join('');
    const w = S.data && S.data.weather && S.data.weather.current;
    $('#t-weather').innerHTML = w ? ic(window.weatherIcon(w.weather_code)) + '<b>' + Math.round(w.temperature_2m) + '°</b><span class="muted">' + esc(window.weatherText(w.weather_code)) + '</span>' : '';
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
    S.view = v;
    C.$$('.view').forEach((el) => el.classList.toggle('on', el.id === 'v-' + v));
    C.$$('#dock [data-view]').forEach((b) => b.classList.toggle('on', b.dataset.view === v));
  }

  function tick(force) {
    const seg = computeSeg();
    const key = seg.type + (seg.slot ? seg.slot.p : '');
    const changed = key !== S.segKey;
    S.seg = seg;
    if (changed) {
      S.segKey = key;
      S.manualView = null;
      if (seg.type === 'class') resetTimerForPeriod();
    }
    const want = S.manualView || autoView();
    if (want !== S.view || force === true || changed) setView(want);
    if (changed && force !== true) { renderHome(); renderLesson(); renderBreak(); }
    renderNowCard();
    renderTimer();
    renderBreakHero();
    if (S.view === 'break') rotateBreakSide();
  }

  // ---------------------------------------------------------------- home view
  function renderHome() {
    renderNowCard();
    renderTimetable();
    renderNotices($('#c-notices'), true);
    renderHomework();
    renderDday();
    renderMeals();
    renderEvents();
    renderAttendance();
  }

  function ring(frac, big, small) {
    const r = 44, c = 2 * Math.PI * r;
    const f = Math.max(0, Math.min(1, frac));
    return '<div class="ring"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="' + r + '" fill="none" stroke="rgba(255,255,255,.22)" stroke-width="8"/>' +
      '<circle cx="50" cy="50" r="' + r + '" fill="none" stroke="#fff" stroke-width="8" stroke-linecap="round" stroke-dasharray="' + c + '" stroke-dashoffset="' + (c * (1 - f)) + '"/></svg>' +
      '<div class="lbl"><b>' + big + '</b><span>' + small + '</span></div></div>';
  }

  function entryFor(p) { return periodsOn(C.today()).find((e) => e.p === p) || null; }
  function lessonFor(p) {
    const st = S.state;
    if (!st) return null;
    const now = st.lessonNow && st.lessonNow[cls()];
    if (now && now.id && Date.now() - now.at < 3 * 3600 * 1000) {
      const l = (st.lessons || []).find((x) => x.id === now.id);
      if (l) return l;
    }
    return (st.lessons || []).find((l) => l.cls === cls() && l.date === C.today() && Number(l.period) === p) || null;
  }
  function roomOf(e) {
    if (e && e.room) return e.room;
    const cs = classState();
    return (cs && cs.room) || '';
  }

  function renderNowCard() {
    const el = $('#c-now');
    if (!el || !S.device) return;
    const seg = S.seg;
    const nowM = C.nowMin();
    let html = '<h3>' + ic('clock') + '지금</h3>';
    if (!cls()) {
      html += '<div class="subj" style="font-size:2.2rem">교실이 지정되지 않았습니다</div><div class="meta">설정에서 이 전자칠판의 학급을 지정하면 시간표가 표시됩니다.</div>';
    } else if (!hasTimetable() && !slots().length) {
      html += '<div class="subj" style="font-size:2.2rem">시간표 연결 필요</div><div class="meta">관리자가 학교 설정에서 컴시간 학교를 선택하면 자동으로 표시됩니다.</div>';
    } else if (seg.type === 'class') {
      const e = entryFor(seg.slot.p);
      const total = seg.slot.end - seg.slot.start, left = seg.slot.end - nowM;
      const nx = seg.next ? entryFor(seg.next.p) : null;
      html += '<div class="row">' + ring(left / total, Math.ceil(left) + '<small style="font-size:1rem">분</small>', '남음') + '<div style="min-width:0">' +
        '<div class="period">' + seg.slot.p + '교시 · ' + C.hm(seg.slot.start) + '~' + C.hm(seg.slot.end) + '</div>' +
        '<div class="subj">' + esc(e ? e.s || '수업' : '수업') + '</div><div class="meta">' +
        (e && e.t ? '<span>' + ic('user') + esc(e.t) + ' 선생님</span>' : '') + (roomOf(e) ? '<span>' + ic('door') + esc(roomOf(e)) + '</span>' : '') +
        (e && e.ov ? '<span>' + ic('info') + esc(e.ov) + '</span>' : e && e.ch ? '<span>' + ic('info') + '시간표 변경</span>' : '') + '</div></div></div>';
      html += '<div class="next">' + ic('right') + (seg.next ? '<span>다음</span><b>' + seg.next.p + '교시 ' + esc(nx ? nx.s : '') + '</b><span class="dim" style="color:rgba(255,255,255,.75)">' + C.hm(seg.next.start) + (nx && roomOf(nx) ? ' · ' + esc(roomOf(nx)) : '') + '</span>' : '<span>오늘 마지막 수업입니다</span>') + '</div>';
    } else if (seg.type === 'break' || seg.type === 'before') {
      const e = entryFor(seg.slot.p);
      const left = seg.slot.start - nowM;
      html += '<div class="row">' + ring(seg.gap ? 1 - left / seg.gap : 0, Math.ceil(left) + '<small style="font-size:1rem">분</small>', '후 시작') + '<div style="min-width:0">' +
        '<div class="period">' + (seg.type === 'before' ? '수업 전' : '쉬는 시간') + '</div>' +
        '<div class="subj">' + seg.slot.p + '교시 ' + esc(e ? e.s : '') + '</div><div class="meta">' +
        (e && e.t ? '<span>' + ic('user') + esc(e.t) + ' 선생님</span>' : '') + (roomOf(e) ? '<span>' + ic('door') + esc(roomOf(e)) + '</span>' : '') +
        '<span>' + ic('clock') + C.hm(seg.slot.start) + ' 시작</span></div></div></div>';
    } else if (seg.type === 'after') {
      html += '<div class="subj">오늘 수업 끝</div><div class="meta"><span>' + ic('check') + '마지막 ' + seg.last.p + '교시 ' + C.hm(seg.last.end) + ' 종료</span></div>';
    } else {
      html += '<div class="subj">오늘은 수업이 없습니다</div><div class="meta">' + esc(C.dateLabel(C.today())) + '</div>';
    }
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

  function renderTimetable() {
    const el = $('#c-tt');
    if (!el) return;
    const date = S.ttOffset === 0 ? C.today() : nextSchoolDate(C.today());
    $('#tt-title').textContent = '시간표 · ' + C.dateLabel(date);
    const tt = S.data && S.data.timetable;
    const err = S.data && S.data.errors && (S.data.errors.comci || S.data.errors.timetable);
    if (!cls()) { el.innerHTML = '<div class="empty">' + ic('info') + '학급이 지정되지 않았습니다</div>'; return; }
    if (!tt) {
      el.innerHTML = '<div class="empty">' + ic('info') + (err ? '시간표를 불러오지 못했습니다: ' + esc(err.message) : '관리자 설정에서 컴시간 학교를 연결하세요') + '</div>';
      return;
    }
    const ps = periodsOn(date);
    const sl = slots();
    if (!ps.length) { el.innerHTML = '<div class="empty">' + ic('calendar') + '수업이 없는 날입니다</div>'; return; }
    const nowM = C.nowMin(), isToday = date === C.today();
    el.innerHTML = '<div class="tt">' + ps.map((e) => {
      const s = sl.find((x) => x.p === e.p);
      let c = 'p';
      if (e.cancel) c += ' cancel';
      if (isToday && S.seg.type === 'class' && S.seg.slot.p === e.p) c += ' cur';
      else if (isToday && s && nowM >= s.end) c += ' past';
      const tag = e.ov ? '<span class="chip orange tag">' + esc(e.ov) + '</span>' : e.ch ? '<span class="chip orange tag">변경' + (e.os ? ' (' + esc(e.os) + ')' : '') + '</span>' : e.cancel ? '<span class="chip gray tag">없음</span>' : '';
      return '<div class="' + c + '"><div class="n">' + e.p + '<small>' + (s ? C.hm(s.start) : '') + '</small></div><div class="s">' + esc(e.s || (e.cancel ? '수업 없음' : '-')) +
        '<small>' + esc([e.t, roomOf(e)].filter(Boolean).join(' · ')) + '</small></div>' + tag + '</div>';
    }).join('') + '</div><div class="src">' + (tt.source === 'comcigan' ? '컴시간알리미' : 'NEIS') + ' 기준' + (tt.updated ? ' · 수정 ' + esc(tt.updated) : '') + (tt.fetchedAt ? ' · 갱신 ' + C.ago(tt.fetchedAt) : '') + (err ? ' · 최근 갱신 실패' : '') + '</div>';
  }

  function renderNotices(el, withFeeds) {
    if (!el) return;
    const list = C.noticesFor(S.state, cls());
    const cs = classState();
    const rs = cs ? cs.rosterSize : 0;
    let html = list.map((n) => {
      const read = n.readCounts && n.readCounts[cls()] || 0;
      return '<div class="notice ' + esc(n.level || 'normal') + '" data-nid="' + esc(n.id) + '"><div class="t">' + (n.pinned ? ic('pinned') : '') + esc(n.title) + '</div>' +
        (n.body ? '<div class="b">' + esc(n.body) + '</div>' : '') + '<div class="m"><span class="chip ' + (n.level === 'urgent' ? 'red' : n.level === 'class' ? 'blue' : 'orange') + '">' + esc(C.LEVEL[n.level] || '일반') + '</span>' +
        '<span>' + esc(n.scope === 'class' ? classLabel(n.target) : n.scope === 'grade' ? n.target + '학년' : '전체') + '</span><span>' + esc(n.author || '') + ' · ' + C.ago(n.createdAt) + '</span>' +
        (n.needRead && rs ? '<span>읽음 ' + read + '명 / 미확인 ' + Math.max(0, rs - read) + '명</span>' : '') + '</div></div>';
    }).join('');
    const feeds = withFeeds && S.data && S.data.feeds && S.data.feeds.items || [];
    if (feeds.length) {
      html += '<div class="section-t" style="margin-top:.8rem">' + ic('link') + '학교 홈페이지</div>' + feeds.slice(0, 8).map((f) =>
        '<div class="notice"><div class="t" style="font-size:1.15rem">' + esc(f.title) + '</div><div class="m"><span>' + esc(f.source) + '</span><span>' + esc(f.date) + '</span></div></div>').join('');
    }
    el.innerHTML = html || '<div class="empty">' + ic('megaphone') + '등록된 공지가 없습니다</div>';
    C.$$('[data-nid]', el).forEach((n) => { n.onclick = () => openNotice(n.dataset.nid); });
    const cnt = $('#n-count');
    if (cnt && el.id === 'c-notices') cnt.innerHTML = list.length ? '<span class="chip">' + list.length + '</span>' : '';
  }

  function renderHomework() {
    const el = $('#c-hw');
    const all = ((S.state && S.state.homework) || []).filter((h) => h.cls === cls());
    const next = nextSchoolDate(C.today());
    const prep = all.filter((h) => h.kind === 'prep' && (h.date === next || h.date === C.today() && new Date().getHours() < 9));
    const task = all.filter((h) => h.kind !== 'prep' && h.date >= C.today()).sort((a, b) => (a.date < b.date ? -1 : 1));
    const li = (arr, withDate) => arr.length ? '<ul>' + arr.slice(0, 6).map((h) => '<li>' + esc(h.text) + (withDate && h.date !== C.today() ? ' <span class="dim" style="font-size:.9rem">(~' + C.shortDate(h.date) + ')</span>' : '') + '</li>').join('') + '</ul>' : '<div class="empty" style="padding:.3rem 0">없음</div>';
    el.innerHTML = '<div class="hw"><div class="box"><h4>' + ic('backpack') + (prep.length && prep[0].date === C.today() ? '오늘' : C.shortDate(next)) + ' 준비물</h4>' + li(prep) +
      '</div><div class="box"><h4>' + ic('clipboard') + '과제</h4>' + li(task, true) + '</div></div>';
  }

  function examItems() {
    const g = grade();
    const out = [];
    ((S.state && S.state.exams) || []).forEach((e) => {
      if (g && e.grade && Number(e.grade) !== g) return;
      if (e.date >= C.today()) out.push({ date: e.date, name: (e.name ? e.name + ' ' : '') + e.subject });
    });
    C.events(S.data, S.state, g).forEach((e) => { if (e.kind === '시험' && e.date >= C.today()) out.push({ date: e.date, name: e.name }); });
    const seen = {};
    return out.sort((a, b) => (a.date < b.date ? -1 : 1)).filter((x) => { const k = x.date + x.name; if (seen[k]) return false; seen[k] = true; return true; });
  }

  function renderDday() {
    const el = $('#c-dday');
    const ex = examItems();
    el.innerHTML = ex.length ? '<div class="dday">' + ex.slice(0, 3).map((e) => '<div class="d"><b>' + C.dday(e.date) + '</b><span>' + esc(e.name) + '</span><span class="dim">' + C.shortDate(e.date) + '</span></div>').join('') + '</div>'
      : '<div class="empty">' + ic('exam') + '예정된 시험이 없습니다</div>';
  }

  function renderMeals() {
    const el = $('#c-meal');
    const m = S.data && S.data.meals;
    const err = S.data && S.data.errors && S.data.errors.meals;
    const h = new Date().getHours();
    let date = C.today();
    let list = (m && m.days && m.days[date]) || [];
    const lastEnd = list.length ? list[list.length - 1].code : 0;
    if (!list.length || (h >= 14 && lastEnd <= 2) || h >= 19) {
      for (let i = 1; i <= 7; i++) { const d = C.addDays(C.today(), i); if (m && m.days && m.days[d] && m.days[d].length) { if (h >= 14 || !list.length) { date = d; list = m.days[d]; } break; } }
    }
    $('#meal-title').textContent = '급식 · ' + (date === C.today() ? '오늘' : C.shortDate(date));
    const tabs = $('#meal-tabs');
    if (!list.length) {
      tabs.innerHTML = '';
      el.innerHTML = '<div class="empty">' + ic('meal') + (err ? '급식 정보를 불러오지 못했습니다' : m ? '등록된 급식이 없습니다' : '학교 설정 후 NEIS에서 불러옵니다') + '</div>';
      return;
    }
    let pick = list.find((x) => x.type === S.mealPick);
    if (!pick) {
      if (date === C.today()) pick = (h < 9 && list.find((x) => x.code === 1)) || (h < 14 && list.find((x) => x.code === 2)) || list.find((x) => x.code === 3) || list[0];
      else pick = list.find((x) => x.code === 2) || list[0];
    }
    tabs.innerHTML = list.length > 1 ? list.map((x) => '<button class="' + (x === pick ? 'on' : '') + '" data-t="' + esc(x.type) + '">' + esc(x.type) + '</button>').join('') : '<span class="chip">' + esc(pick.type) + '</span>';
    C.$$('button', tabs).forEach((b) => { b.onclick = () => { S.mealPick = b.dataset.t; renderMeals(); }; });
    const used = {};
    el.innerHTML = '<ul>' + pick.dishes.map((d) => { (d.al || []).forEach((a) => { used[a] = true; }); return '<li>' + esc(d.name) + (d.al && d.al.length ? '<span class="al">' + d.al.join('.') + '</span>' : '') + '</li>'; }).join('') + '</ul>' +
      '<div class="kcal">' + esc(pick.kcal || '') + '</div>' +
      (Object.keys(used).length ? '<div class="allergy-legend">알레르기: ' + Object.keys(used).map((k) => k + '.' + (C.ALLERGENS[k] || '')).join('  ') + '</div>' : '');
  }

  function renderEvents() {
    const el = $('#c-events');
    const list = C.events(S.data, S.state, grade()).filter((e) => (e.endDate || e.date) >= C.today() && e.kind !== '공휴일' || e.kind === '공휴일' && e.date >= C.today());
    if (!list.length) {
      const err = S.data && S.data.errors && S.data.errors.schedule;
      el.innerHTML = '<div class="empty">' + ic('calendar') + (err ? '학사일정을 불러오지 못했습니다' : '예정된 일정이 없습니다') + '</div>';
      return;
    }
    el.innerHTML = list.slice(0, 12).map((e) => '<div class="ev"><span class="dt">' + C.shortDate(e.date) + '</span><span class="nm">' + esc(e.name) + '</span><span class="chip ' + (C.KIND_COLOR[e.kind] || 'gray') + '">' + esc(e.kind) + '</span></div>').join('');
  }

  function renderAttendance() {
    const el = $('#c-att');
    const cs = classState();
    const card = $('#c-att-card');
    if (!cls() || !cs || !cs.rosterSize) {
      el.innerHTML = '<div class="empty">' + ic('users') + '담임 선생님이 학생 명단을 등록하면 표시됩니다</div>';
      $('#att-qr-btn').classList.toggle('hidden', true);
      return;
    }
    $('#att-qr-btn').classList.toggle('hidden', false);
    const a = cs.attToday || {};
    card.classList.remove('hidden');
    el.innerHTML = '<div class="att"><div class="nums"><div><b>' + (a.present || 0) + '</b><span>출석</span></div><div><b style="color:var(--red)">' + (a.absent || 0) + '</b><span>결석</span></div><div><b style="color:var(--orange)">' + (a.late || 0) + '</b><span>지각</span></div><div><b style="color:var(--violet)">' + (a.early || 0) + '</b><span>조퇴</span></div><div><b class="dim">' + Math.max(0, cs.rosterSize - (a.marked || 0)) + '</b><span>미확인</span></div></div></div>';
  }

  // ---------------------------------------------------------------- lesson view
  function renderLesson() {
    const el = $('#l-main');
    if (!el) return;
    const seg = S.seg;
    let p = seg.type === 'class' ? seg.slot.p : seg.slot ? seg.slot.p : 0;
    const l0 = lessonFor(p);
    if (!p && l0 && l0.date === C.today()) p = Number(l0.period) || 0;
    const e = p ? entryFor(p) : null;
    const l = l0;
    if (!p && !l) {
      el.className = 'lesson-main lesson-empty';
      el.innerHTML = '<div class="big">수업 화면</div><p class="muted" style="font-size:1.4rem;margin-top:1rem">수업 시간이 되면 이 화면이 자동으로 표시됩니다. 선생님이 포털에서 "오늘의 수업"을 등록하면 학습 목표와 자료가 함께 나타납니다.</p>';
      renderNotices($('#l-notices'), false);
      return;
    }
    el.className = 'lesson-main';
    const subj = (l && l.subject) || (e && e.s) || '수업';
    const teacher = l && l.subject && e && e.s && l.subject !== e.s ? l.author || '' : (e && e.t) || (l && l.author) || '';
    let html = '<div class="head"><span class="p">' + (p ? p + '교시' : '오늘의 수업') + '</span><span class="s">' + esc(subj) + '</span>' + (teacher ? '<span class="t">' + esc(teacher) + ' 선생님</span>' : '') + (roomOf(e) ? '<span class="chip">' + ic('door') + esc(roomOf(e)) + '</span>' : '') + '</div>';
    if (l) {
      if (l.goal) html += '<section><h4>' + ic('pinned') + '학습 목표</h4><div class="goal">' + esc(l.goal) + '</div></section>';
      if (l.supplies) html += '<section><h4>' + ic('backpack') + '준비물</h4><div class="sup">' + esc(l.supplies) + '</div></section>';
      const files = (l.files || []).map((id) => ((S.state && S.state.files) || []).find((f) => f.id === id)).filter(Boolean);
      const links = l.links || [];
      if (files.length || links.length) {
        html += '<section><h4>' + ic('folder') + '수업 자료</h4><div class="mats">' +
          files.map((f) => '<button class="mat" data-file="' + esc(f.id) + '">' + ic(fileIcon(f.name)) + esc(f.name) + '</button>').join('') +
          links.map((k, i) => '<button class="mat" data-link="' + i + '">' + ic('link') + esc(k.title || k.url) + '</button>').join('') + '</div></section>';
      }
      if (l.note) html += '<section><h4>' + ic('info') + '안내</h4><div class="sup" style="font-size:1.5rem">' + esc(l.note) + '</div></section>';
    } else {
      html += '<section><h4>' + ic('info') + '오늘의 수업</h4><div class="sup muted" style="font-size:1.4rem">등록된 수업 화면이 없습니다. 선생님 포털에서 학습 목표와 자료를 등록할 수 있습니다.</div></section>';
      const prep = ((S.state && S.state.homework) || []).filter((h) => h.cls === cls() && h.kind === 'prep' && h.date === C.today());
      if (prep.length) html += '<section><h4>' + ic('backpack') + '오늘 준비물</h4><div class="sup">' + prep.map((h) => esc(h.text)).join('<br>') + '</div></section>';
    }
    el.innerHTML = html;
    C.$$('[data-file]', el).forEach((b) => { b.onclick = () => { const f = S.state.files.find((x) => x.id === b.dataset.file); if (f) { toast('"' + f.name + '" 여는 중...'); native('openRemote', f.id, f.name, f.mime || ''); } }; });
    C.$$('[data-link]', el).forEach((b) => { b.onclick = () => native('openUrl', l.links[Number(b.dataset.link)].url); });
    renderNotices($('#l-notices'), false);
    if (l && l.timerMin > 0 && S.timer.preset !== l.timerMin * 60 && !S.timer.running) {
      S.timer.preset = l.timerMin * 60;
      S.timer.remain = S.timer.preset;
      if (S.seg.type !== 'class') S.timer.mode = 'custom';
      $('#l-timer').dataset.sig = '';
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

  function resetTimerForPeriod() {
    S.timer.mode = 'period';
    S.timer.running = false;
    S.timer.preset = 0;
  }

  function renderTimer() {
    const el = $('#l-timer');
    if (!el || S.view !== 'lesson') return;
    let secs, label;
    const t = S.timer;
    if (t.mode === 'custom') {
      secs = t.running ? Math.round((t.endAt - Date.now()) / 1000) : t.remain;
      label = '수업 타이머';
    } else if (S.seg.type === 'class') {
      secs = Math.round((S.seg.slot.end - C.nowMin()) * 60);
      label = '수업 종료까지';
    } else { secs = null; label = '수업 타이머'; }
    if (t.mode === 'custom' && t.running && secs <= 0 && !t.rang) { t.rang = true; if (N) N.chime('soft'); }
    const abs = Math.abs(secs || 0);
    const txt = secs == null ? '--:--' : (secs < 0 ? '-' : '') + C.pad(Math.floor(abs / 60)) + ':' + C.pad(abs % 60);
    const cls2 = secs != null && secs < 0 ? 'over' : secs != null && secs <= 300 ? 'warn' : '';
    const sig = txt + cls2 + t.mode + t.running;
    if (el.dataset.sig === sig) return;
    el.dataset.sig = sig;
    el.innerHTML = '<div class="muted" style="font-size:1.3rem;font-weight:700">' + label + '</div><div class="big ' + cls2 + '">' + txt + '</div><div class="ctrl">' +
      (t.mode === 'custom' ? '<button class="btn pri" data-t="toggle">' + ic(t.running ? 'pause' : 'play') + (t.running ? '일시정지' : '시작') + '</button><button class="btn" data-t="reset">' + ic('reset') + '초기화</button><button class="btn" data-t="period">' + ic('clock') + '교시 시간</button>'
        : '<button class="btn" data-t="m5">5분</button><button class="btn" data-t="m10">10분</button><button class="btn" data-t="m15">15분</button><button class="btn" data-t="m45">' + C.defaultMinutes(config()) + '분</button>') + '</div>';
    C.$$('[data-t]', el).forEach((b) => { b.onclick = () => timerAction(b.dataset.t); });
  }

  function timerAction(a) {
    const t = S.timer;
    if (a.charAt(0) === 'm') {
      const m = a === 'm45' ? C.defaultMinutes(config()) : Number(a.slice(1));
      t.mode = 'custom'; t.preset = m * 60; t.remain = t.preset; t.running = true; t.endAt = Date.now() + t.remain * 1000; t.rang = false;
    } else if (a === 'toggle') {
      if (t.running) { t.remain = Math.round((t.endAt - Date.now()) / 1000); t.running = false; } else { t.endAt = Date.now() + t.remain * 1000; t.running = true; t.rang = false; }
    } else if (a === 'reset') { t.running = false; t.remain = t.preset; t.rang = false; }
    else if (a === 'period') { t.mode = 'period'; t.running = false; }
    $('#l-timer').dataset.sig = '';
    renderTimer();
  }

  // ---------------------------------------------------------------- break view
  function renderBreak() { renderBreakHero(); renderBreakWeather(); rotateBreakSide(true); }

  function renderBreakHero() {
    const el = $('#b-hero');
    if (!el || S.view !== 'break') return;
    const seg = S.seg;
    if (!seg.slot) { el.innerHTML = ''; return; }
    const left = Math.max(0, seg.slot.start - C.nowMin());
    const secs = Math.round(left * 60);
    const e = entryFor(seg.slot.p);
    const txt = C.pad(Math.floor(secs / 60)) + ':' + C.pad(secs % 60);
    const slides = slideFiles();
    const sig = txt + (e ? e.s : '') + slides.length + S.slideIdx;
    if (el.dataset.sig === sig) return;
    el.dataset.sig = sig;
    let html = '';
    if (slides.length && settings().breakSlides !== false) {
      const f = slides[S.slideIdx % slides.length];
      html += '<div class="slide"><img src="/api/files/' + encodeURIComponent(f.id) + '?inline=1" alt=""><div class="cap">' + seg.slot.p + '교시 ' + esc(e ? e.s : '') + ' · ' + txt + ' 후 시작</div></div>';
    } else {
      html += '<div class="lbl">' + (seg.type === 'before' ? '수업 시작까지' : '쉬는 시간 · 다음 수업까지') + '</div><div class="cnt">' + txt + '</div>' +
        '<div class="nx">' + seg.slot.p + '교시 ' + esc(e ? e.s : '') + '<small>' + esc([e && e.t ? e.t + ' 선생님' : '', roomOf(e)].filter(Boolean).join(' · ')) + '</small></div>';
      const l = lessonFor(seg.slot.p);
      if (l && l.supplies) html += '<div class="muted" style="font-size:1.5rem;margin-top:1rem">' + ic('backpack') + ' 준비물: ' + esc(l.supplies) + '</div>';
    }
    el.innerHTML = html;
  }

  function slideFiles() {
    return ((S.state && S.state.files) || []).filter((f) => f.kind === 'slide' && (!f.cls || f.cls === cls()) && /^image\//.test(f.mime || ''));
  }

  function renderBreakWeather() {
    const el = $('#b-weather');
    const w = S.data && S.data.weather;
    if (!w || !w.current) {
      el.innerHTML = '<h3>' + ic('wSun') + '날씨</h3><div class="empty">' + ic('info') + '관리자가 학교 위치를 설정하면 표시됩니다</div>';
      return;
    }
    const c = w.current, d = w.daily || {};
    let days2 = '';
    (d.time || []).slice(0, 3).forEach((t, i) => {
      days2 += '<div>' + (i === 0 ? '오늘' : C.shortDate(t)) + ic(window.weatherIcon(d.weather_code[i])) + Math.round(d.temperature_2m_min[i]) + '° / ' + Math.round(d.temperature_2m_max[i]) + '°' +
        (d.precipitation_probability_max && d.precipitation_probability_max[i] != null ? '<div class="dim">강수 ' + d.precipitation_probability_max[i] + '%</div>' : '') + '</div>';
    });
    const air = w.air;
    const grade10 = (v) => v == null ? '' : v <= 30 ? ['좋음', 'green'] : v <= 80 ? ['보통', 'blue'] : v <= 150 ? ['나쁨', 'orange'] : ['매우나쁨', 'red'];
    const grade25 = (v) => v == null ? '' : v <= 15 ? ['좋음', 'green'] : v <= 35 ? ['보통', 'blue'] : v <= 75 ? ['나쁨', 'orange'] : ['매우나쁨', 'red'];
    let airHtml = '';
    if (air) {
      const a = grade10(air.pm10), b = grade25(air.pm2_5);
      airHtml = '<div class="air"><span class="chip ' + a[1] + '">미세먼지 ' + Math.round(air.pm10) + ' ' + a[0] + '</span><span class="chip ' + b[1] + '">초미세먼지 ' + Math.round(air.pm2_5) + ' ' + b[0] + '</span></div>';
    }
    el.innerHTML = '<h3>' + ic('wSun') + '날씨 · ' + esc(config().locationName || '') + '</h3><div class="wx-big">' + ic(window.weatherIcon(c.weather_code)) + '<div><b>' + Math.round(c.temperature_2m) + '°</b><div class="muted">' + esc(window.weatherText(c.weather_code)) +
      ' · 체감 ' + Math.round(c.apparent_temperature) + '° · 습도 ' + c.relative_humidity_2m + '%</div></div></div><div class="wx-days">' + days2 + '</div>' + airHtml +
      '<div class="src">Open-Meteo · ' + C.ago(w.fetchedAt) + '</div>';
  }

  function rotateBreakSide(force) {
    const now = Date.now();
    if (!force && now - S.lastSlideAt < 10000) return;
    S.lastSlideAt = now;
    S.slideIdx++;
    const b = S.state && S.state.broadcast;
    const el = $('#b-side');
    if (!el) return;
    if (b && (b.live || b.lunch)) {
      $('#b-side-title').textContent = b.live ? '방송' : '점심 방송';
      el.innerHTML = '<div class="notice urgent"><div class="t">' + ic('radio') + esc(b.title || '학교 방송') + '</div><div class="b" style="-webkit-line-clamp:6">' + esc(b.live ? b.caption || '' : b.lunch) + '</div></div>';
    } else {
      $('#b-side-title').textContent = '공지';
      renderNotices(el, true);
    }
  }

  // ---------------------------------------------------------------- dock
  function renderDock() {
    const rec = S.sys && S.sys.device && S.sys.device.recording;
    const items = [
      ['view', 'home', 'home', '홈'], ['view', 'lesson', 'book', '수업'], ['view', 'break', 'clock', '쉬는시간'], ['sep'],
      ['panel', 'apps', 'apps', '앱'], ['act', 'memo', 'pen', '화면 메모'], ['act', 'shot', 'camera', '캡처'], ['act', 'rec', rec ? 'stop' : 'rec', rec ? '녹화 중지' : '녹화'],
      ['panel', 'split', 'split', '화면 분할'], ['panel', 'files', 'usb', '파일'], ['sep'],
      ['panel', 'qr', 'qr', 'QR'], ['panel', 'room', 'door', '교실 정보'], ['panel', 'contacts', 'phone', '연락처'], ['panel', 'portal', 'teacher', '교사'], ['sep'],
      ['panel', 'quick', 'sliders', '빠른 설정'], ['panel', 'settings', 'settings', '설정'],
    ];
    let html = '';
    items.forEach((it) => {
      if (it[0] === 'sep') { html += '<span class="sep"></span>'; return; }
      html += '<button data-' + it[0] + '="' + it[1] + '" class="' + (it[1] === 'rec' && rec ? 'rec' : '') + '">' + ic(it[2]) + '<span>' + it[3] + '</span></button>';
    });
    let recent = [];
    if (N) { try { recent = JSON.parse(N.recent()).slice(0, 5); } catch (e) { recent = []; } }
    if (recent.length) html += '<span class="sep"></span><div class="apps-quick">' + recent.map((p) => '<button data-launch="' + esc(p) + '" style="min-width:auto"><img src="/api/local/icon?pkg=' + encodeURIComponent(p) + '" alt=""></button>').join('') + '</div>';
    const dock = $('#dock');
    dock.innerHTML = html;
    C.$$('[data-view]', dock).forEach((b) => { b.onclick = () => { S.manualView = b.dataset.view; closePanel(); setView(b.dataset.view); }; });
    C.$$('[data-panel]', dock).forEach((b) => { b.onclick = () => openPanel(b.dataset.panel); });
    C.$$('[data-act]', dock).forEach((b) => { b.onclick = () => action(b.dataset.act); });
    C.$$('[data-launch]', dock).forEach((b) => { b.onclick = () => { native('launch', b.dataset.launch); }; });
    C.$$('#dock [data-view]').forEach((b) => b.classList.toggle('on', b.dataset.view === S.view));
  }

  /** Android-style navigation keys on the left of the bottom bar. */
  function renderNavKeys() {
    const el = $('#navkeys');
    el.innerHTML = '<button data-nav="back" title="뒤로">' + ic('navBack') + '</button><button data-nav="home" title="홈">' + ic('navHome') + '</button><button data-nav="recents" title="최근 앱">' + ic('navRecents') + '</button>';
    C.$$('[data-nav]', el).forEach((b) => { b.onclick = () => navKey(b.dataset.nav); });
  }

  function navKey(k) {
    if (k === 'back') {
      if ($('#class-alert').classList.contains('on')) { $('#class-alert').classList.remove('on'); return; }
      if (S.panel) { closePanel(); return; }
      S.manualView = null;
      setView(autoView());
    } else if (k === 'home') {
      closePanel();
      S.manualView = 'home';
      setView('home');
    } else if (k === 'recents') {
      const r = N ? native('nav', 'recents') : null;
      if (!r || r.noA11y) openPanel('apps');
    }
  }

  function action(a) {
    if (a === 'memo') native('memo');
    else if (a === 'shot') native('capture', false);
    else if (a === 'rec') { native('capture', true); setTimeout(() => { pollSys(); renderDock(); }, 1500); }
  }

  function classEndReset() {
    closePanel();
    S.manualView = null;
    S.timer = { mode: 'period', running: false, endAt: 0, remain: 0, preset: 0 };
    setView(autoView());
  }

  // ---------------------------------------------------------------- panels
  function openPanel(name, opts) {
    S.panel = name;
    S.panelOpts = opts || {};
    const p = $('#panel');
    p.classList.toggle('side', name === 'quick');
    p.classList.add('on');
    $('#scrim').classList.add('on');
    $('#p-tools').innerHTML = '';
    refreshPanel();
  }
  function closePanel() {
    if (S.panel === 'portal') $('#p-body').innerHTML = '';
    S.panel = null;
    $('#panel').classList.remove('on');
    $('#scrim').classList.remove('on');
  }
  function refreshPanel() {
    const titles = { apps: '앱', split: '화면 분할로 열기', files: '파일 · USB', qr: 'QR 코드', room: '교실 정보', contacts: '교내 연락처', quick: '빠른 설정', settings: '설정', portal: '교사 · 관리자 포털', notice: '공지', qrbig: 'QR' };
    $('#p-title').textContent = titles[S.panel] || '';
    switch (S.panel) {
      case 'apps': renderApps(false); break;
      case 'split': renderApps(true); break;
      case 'files': renderFiles(); break;
      case 'qr': renderQr(); break;
      case 'qrbig': renderQrBig(); break;
      case 'room': renderRoom(); break;
      case 'contacts': renderContacts(); break;
      case 'quick': renderQuick(); break;
      case 'settings': renderSettings(); break;
      case 'portal': renderPortal(); break;
      case 'notice': renderNoticeDetail(); break;
      default: break;
    }
  }

  // apps
  function renderApps(split) {
    const body = $('#p-body');
    if (!N) { body.innerHTML = '<div class="empty">' + ic('info') + '전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    let apps = [];
    try { apps = JSON.parse(N.apps()); } catch (e) { apps = []; }
    let recent = [];
    try { recent = JSON.parse(N.recent()); } catch (e) { recent = []; }
    $('#p-tools').innerHTML = '<input id="app-q" placeholder="앱 검색" style="width:22rem">';
    const draw = () => {
      const q = ($('#app-q').value || '').trim().toLowerCase();
      const list = apps.filter((a) => !q || a.label.toLowerCase().indexOf(q) >= 0 || a.pkg.indexOf(q) >= 0);
      const tile = (a) => '<div class="app" data-pkg="' + esc(a.pkg) + '"><img src="/api/local/icon?pkg=' + encodeURIComponent(a.pkg) + '" alt="" loading="lazy"><span>' + esc(a.label) + '</span>' +
        (!split ? '<button class="sp" data-split="' + esc(a.pkg) + '" title="화면 분할">' + ic('split') + '</button>' : '') + '</div>';
      let html = '';
      if (split) html += '<p class="muted" style="margin-bottom:1rem;font-size:1.1rem">선택한 앱이 전자칠판 화면 옆에 열립니다. 분할을 끝내려면 가운데 경계선을 끝까지 밀거나 아래 버튼을 누르세요. <button class="btn sm" id="exit-split">' + ic('x') + '분할 종료</button></p>';
      const rec = recent.map((p) => apps.find((a) => a.pkg === p)).filter(Boolean);
      if (rec.length && !q) html += '<div class="section-t">' + ic('clock') + '최근 실행</div><div class="app-grid">' + rec.map(tile).join('') + '</div><div class="section-t">' + ic('apps') + '전체 앱</div>';
      html += '<div class="app-grid">' + list.map(tile).join('') + '</div>';
      body.innerHTML = html;
      C.$$('.app', body).forEach((el) => { el.onclick = () => { const r = split ? native('launchSplit', el.dataset.pkg) : native('launch', el.dataset.pkg); if (r && !r.error) { closePanel(); setTimeout(renderDock, 800); } }; });
      C.$$('[data-split]', body).forEach((el) => { el.onclick = (ev) => { ev.stopPropagation(); const r = native('launchSplit', el.dataset.split); if (r && !r.error) closePanel(); }; });
      const ex = $('#exit-split');
      if (ex) ex.onclick = () => native('exitSplit');
    };
    $('#app-q').oninput = draw;
    draw();
  }

  // files
  let fileNav = null; // { tree, stack: [{id, name}] }
  function renderFiles() {
    const body = $('#p-body');
    if (!N) { body.innerHTML = '<div class="empty">' + ic('info') + '전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    if (fileNav) { renderDir(); return; }
    let sys = S.sys;
    try { sys = JSON.parse(N.sys()); } catch (e) { /* keep */ }
    let trees = [];
    try { trees = JSON.parse(N.trees()); } catch (e) { trees = []; }
    let html = '<div class="section-t">' + ic('storage') + '저장 장치</div>';
    (sys && sys.storage || []).forEach((v) => {
      const used = v.total ? (v.total - v.free) / v.total : 0;
      html += '<div class="list-row">' + ic(v.removable ? 'usb' : 'storage') + '<div class="grow"><b>' + esc(v.label) + '</b>' +
        (v.total ? '<div class="bar"><i style="width:' + Math.round(used * 100) + '%"></i></div><span>' + C.bytes(v.free) + ' 남음 / ' + C.bytes(v.total) + '</span>' : '<span>' + esc(v.state || '') + '</span>') + '</div>' +
        (v.index != null ? '<button class="btn" data-vol="' + v.index + '">' + ic('folder') + '폴더 열기</button>' : '') + '</div>';
    });
    html += '<div class="section-t">' + ic('folder') + '열어 둔 폴더</div>';
    html += trees.length ? trees.map((t) => '<div class="list-row">' + ic('folder') + '<div class="grow"><b>' + esc(decodeURIComponent(t.name)) + '</b></div><button class="btn pri" data-tree="' + esc(t.uri) + '">열기</button><button class="btn" data-forget="' + esc(t.uri) + '">' + ic('x') + '</button></div>').join('')
      : '<div class="empty">' + ic('info') + 'USB를 연결한 뒤 "폴더 열기"로 접근을 허용하세요</div>';
    html += '<div style="margin-top:1rem"><button class="btn" id="pick-any">' + ic('search') + '다른 위치에서 폴더 선택</button></div>';
    body.innerHTML = html;
    C.$$('[data-vol]', body).forEach((b) => { b.onclick = () => native('pickFolder', Number(b.dataset.vol)); });
    C.$$('[data-tree]', body).forEach((b) => { b.onclick = () => { fileNav = { tree: b.dataset.tree, stack: [] }; renderDir(); }; });
    C.$$('[data-forget]', body).forEach((b) => { b.onclick = () => { native('forgetTree', b.dataset.forget); renderFiles(); }; });
    $('#pick-any').onclick = () => native('pickFolder', -1);
  }

  function renderDir() {
    const body = $('#p-body');
    const top = fileNav.stack[fileNav.stack.length - 1];
    const r = native('listDir', fileNav.tree, top ? top.id : '');
    if (!r || r.error) { fileNav = null; renderFiles(); return; }
    const items = r.items.sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name));
    let html = '<div class="crumbs"><button class="btn sm" id="fs-back">' + ic('left') + '뒤로</button><span class="chip">' + esc(decodeURIComponent(fileNav.tree.split('/').pop())) + '</span>' +
      fileNav.stack.map((s) => '<span class="dim">/</span><span class="chip">' + esc(s.name) + '</span>').join('') + '</div>';
    html += items.length ? items.map((f, i) => '<div class="file-row" data-i="' + i + '">' + ic(f.dir ? 'folder' : fileIcon(f.name)) + '<span>' + esc(f.name) + '</span><span class="sz">' + (f.dir ? '' : C.bytes(f.size)) + '</span>' +
      (f.dir ? '<span></span>' : '<button class="btn sm" data-up="' + i + '">' + ic('upload') + '수업 자료로 올리기</button>') + '</div>').join('') : '<div class="empty">' + ic('folder') + '빈 폴더입니다</div>';
    body.innerHTML = html;
    $('#fs-back').onclick = () => { if (fileNav.stack.length) fileNav.stack.pop(); else fileNav = null; renderFiles(); };
    C.$$('.file-row', body).forEach((row) => {
      row.onclick = () => {
        const f = items[Number(row.dataset.i)];
        if (f.dir) { fileNav.stack.push({ id: f.id, name: f.name }); renderDir(); } else native('openDoc', fileNav.tree, f.id, f.mime || '');
      };
    });
    C.$$('[data-up]', body).forEach((b) => {
      b.onclick = (ev) => {
        ev.stopPropagation();
        const f = items[Number(b.dataset.up)];
        staffLogin('자료 업로드', ['teacher', 'admin'], (tok) => { native('uploadDoc', fileNav.tree, f.id, f.name, tok, cls()); toast('"' + f.name + '" 업로드 중...'); openPanel('files'); });
      };
    });
  }

  // QR
  function qrTargets() {
    const base = portalBase();
    const c = cls();
    const out = [];
    const cs = classState();
    if (c && cs && cs.rosterSize) out.push({ key: 'att', title: 'QR 출석', sub: '번호를 선택하면 출석 처리됩니다 (20초마다 바뀜)', dynamic: true });
    if (c) out.push({ key: 'notices', title: '공지 확인', sub: '공지를 읽고 확인 표시', url: base + '/m/#/notices?c=' + c });
    ((S.state && S.state.surveys) || []).filter((s) => s.cls === c && s.open !== false).forEach((s) => out.push({ key: 's' + s.id, title: '설문: ' + s.question, sub: '응답 ' + (s.responseCount || 0) + '명', url: base + '/m/#/survey?s=' + s.id + '&c=' + c }));
    ((S.state && S.state.assignments) || []).filter((a) => a.cls === c && a.open !== false).forEach((a) => out.push({ key: 'a' + a.id, title: '과제 제출: ' + a.title, sub: a.due ? '마감 ' + C.shortDate(a.due) : '', url: base + '/m/#/submit?a=' + a.id + '&c=' + c }));
    if (c) out.push({ key: 'mat', title: '수업 자료 받기', sub: '선생님이 올린 자료 다운로드', url: base + '/m/#/materials?c=' + c });
    if (c) out.push({ key: 'off', title: '임원 알림판', sub: '회장 · 부회장 전용', url: base + '/m/#/officer?c=' + c });
    out.push({ key: 'staff', title: '교사 포털', sub: '공지 · 수업 화면 · 출석 관리', url: base + '/m/#/staff' });
    return out;
  }

  function renderQr() {
    const body = $('#p-body');
    const list = qrTargets();
    body.innerHTML = '<p class="muted" style="margin-bottom:1rem;font-size:1.1rem">같은 학교 네트워크(Wi-Fi)에 연결된 휴대폰으로 스캔하세요. 서버 주소: ' + esc(portalBase()) + '</p><div class="qr-grid">' +
      list.map((q) => '<button class="qr-card" data-k="' + esc(q.key) + '">' + (q.dynamic ? '<div class="qr" style="display:flex;align-items:center;justify-content:center;background:var(--card2)">' + ic('qr') + '</div>' : window.qrSvg(q.url, { border: 2 })) +
        '<b>' + esc(q.title) + '</b><span>' + esc(q.sub || '') + '</span></button>').join('') + '</div>';
    C.$$('[data-k]', body).forEach((b) => { b.onclick = () => openQrBig(b.dataset.k); });
  }

  function openQrBig(key) { openPanel('qrbig', { key }); }

  async function renderQrBig() {
    const key = S.panelOpts.key;
    const q = qrTargets().find((x) => x.key === key);
    const body = $('#p-body');
    if (!q) { body.innerHTML = '<div class="empty">' + ic('info') + '사용할 수 없는 QR입니다</div>'; return; }
    $('#p-tools').innerHTML = '<button class="btn" id="qr-back">' + ic('left') + '목록</button>';
    $('#qr-back').onclick = () => openPanel('qr');
    if (!q.dynamic) {
      body.innerHTML = '<div class="qr-big">' + window.qrSvg(q.url, { border: 2 }) + '<div class="info"><b>' + esc(q.title) + '</b><span>' + esc(q.sub) + '</span><span class="dim" style="font-size:1rem">' + esc(q.url) + '</span></div></div>';
      return;
    }
    const draw = async () => {
      if (S.panel !== 'qrbig' || S.panelOpts.key !== key) return;
      try {
        const t = await C.get('/api/local/att-token?cls=' + encodeURIComponent(cls()));
        const url = portalBase() + '/m/#/att?c=' + cls() + '&t=' + t.token;
        const cs = classState();
        const a = (cs && cs.attToday) || {};
        body.innerHTML = '<div class="qr-big">' + window.qrSvg(url, { border: 2 }) + '<div class="info"><b>QR 출석 · ' + esc(classLabel(cls())) + '</b><span>휴대폰으로 스캔한 뒤 자기 번호를 선택하세요</span>' +
          '<span style="font-size:2.4rem;font-weight:800;margin-top:1.4rem;color:var(--text)">' + (a.marked || 0) + ' / ' + ((cs && cs.rosterSize) || 0) + '명 확인</span><span class="dim" style="font-size:1rem">QR은 20초마다 바뀝니다</span></div></div>';
        setTimeout(draw, Math.max(3, Math.min(20, t.expiresIn || 20)) * 1000);
      } catch (e) {
        body.innerHTML = '<div class="empty">' + ic('alert') + 'QR 토큰을 받을 수 없습니다: ' + esc(e.message) + '</div>';
        setTimeout(draw, 5000);
      }
    };
    draw();
  }

  // notice detail
  function openNotice(id) { openPanel('notice', { id }); }
  function renderNoticeDetail() {
    const n = ((S.state && S.state.notices) || []).find((x) => x.id === S.panelOpts.id);
    const body = $('#p-body');
    if (!n) { body.innerHTML = '<div class="empty">삭제된 공지입니다</div>'; return; }
    const url = portalBase() + '/m/#/read?n=' + n.id + '&c=' + cls();
    const cs = classState();
    const read = (n.readCounts && n.readCounts[cls()]) || 0;
    body.innerHTML = '<div style="display:grid;grid-template-columns:1fr auto;gap:2rem;align-items:start"><div><div class="chip ' + (n.level === 'urgent' ? 'red' : n.level === 'class' ? 'blue' : 'orange') + '">' + esc(C.LEVEL[n.level] || '일반') + '</div>' +
      '<h1 style="font-size:2.6rem;margin:.8rem 0">' + esc(n.title) + '</h1><div style="font-size:1.6rem;white-space:pre-wrap;line-height:1.55">' + esc(n.body || '') + '</div>' +
      '<p class="muted" style="margin-top:1.2rem">' + esc(n.author || '') + ' · ' + C.dateTime(n.createdAt) + '</p></div>' +
      (cls() && cs && cs.rosterSize ? '<div class="qr-card" style="width:22rem">' + window.qrSvg(url, { border: 2 }) + '<b>읽음 확인</b><span>읽음 ' + read + '명 / 미확인 ' + Math.max(0, cs.rosterSize - read) + '명</span></div>' : '') + '</div>';
  }

  // room info
  function renderRoom() {
    const body = $('#p-body');
    const cs = classState();
    const seg = S.seg;
    const cur = seg.type === 'class' ? entryFor(seg.slot.p) : null;
    const nxSlot = seg.type === 'class' ? seg.next : seg.slot;
    const nx = nxSlot ? entryFor(nxSlot.p) : null;
    const homeroom = cs && cs.homeroom;
    let html = '<div class="tool-grid" style="grid-template-columns:repeat(auto-fill,minmax(20rem,1fr))">' +
      '<div class="tool">' + ic('door') + '<span>현재 교실</span><b>' + esc(cur && roomOf(cur) ? roomOf(cur) : (cs && cs.room) || (cls() ? classLabel(cls()) + ' 교실' : '미지정')) + '</b></div>' +
      '<div class="tool">' + ic('user') + '<span>담당 선생님</span><b>' + esc(cur && cur.t ? cur.t + ' 선생님 (' + cur.s + ')' : homeroom ? '담임 ' + homeroom + ' 선생님' : '정보 없음') + '</b></div>' +
      '<div class="tool">' + ic('right') + '<span>다음 수업 교실</span><b>' + esc(nx ? (nxSlot.p + '교시 ' + nx.s + ' · ' + (roomOf(nx) || '우리 교실')) : '다음 수업 없음') + '</b></div>' +
      (homeroom ? '<div class="tool">' + ic('teacher') + '<span>담임</span><b>' + esc(homeroom) + ' 선생님</b></div>' : '') + '</div>';
    const rooms = (S.state && S.state.rooms) || [];
    html += '<div class="section-t">' + ic('pin') + '특별실 위치</div>';
    html += rooms.length ? rooms.map((r) => '<div class="list-row">' + ic('door') + '<div class="grow"><b>' + esc(r.name) + '</b><span>' + esc(r.location || '') + (r.note ? ' · ' + esc(r.note) : '') + '</span></div></div>').join('')
      : '<div class="empty">' + ic('info') + '관리자가 포털에서 특별실을 등록하면 표시됩니다</div>';
    const info = cs && cs.subjectInfo ? Object.keys(cs.subjectInfo) : [];
    if (info.length) html += '<div class="section-t">' + ic('book') + '교과 담당</div>' + info.map((k) => '<div class="list-row"><div class="grow"><b>' + esc(k) + '</b><span>' + esc([cs.subjectInfo[k].teacher, cs.subjectInfo[k].room].filter(Boolean).join(' · ')) + '</span></div></div>').join('');
    body.innerHTML = html;
  }

  // contacts
  function renderContacts() {
    const body = $('#p-body');
    const list = (S.state && S.state.contacts) || [];
    if (!$('#ct-q')) $('#p-tools').innerHTML = '<input id="ct-q" placeholder="부서, 이름, 번호 검색" style="width:26rem">';
    const draw = () => {
      const q = ($('#ct-q').value || '').trim();
      const f = list.filter((c) => !q || [c.dept, c.name, c.phone, c.ext, c.location, c.note].join(' ').indexOf(q) >= 0);
      body.innerHTML = f.length ? '<div class="contact-list">' + f.map((c) => '<div class="contact"><b>' + esc(c.name) + '</b><div class="d">' + esc([c.dept, c.location].filter(Boolean).join(' · ')) + '</div>' +
        '<div class="ph">' + esc(c.ext ? '내선 ' + c.ext : '') + (c.ext && c.phone ? '  ' : '') + esc(c.phone || '') + '</div>' + (c.note ? '<div class="d">' + esc(c.note) + '</div>' : '') + '</div>').join('') + '</div>'
        : '<div class="empty">' + ic('phone') + (list.length ? '검색 결과가 없습니다' : '관리자가 포털에서 교내 연락처를 등록하면 표시됩니다') + '</div>';
    };
    $('#ct-q').oninput = draw;
    draw();
  }

  // quick settings
  function renderQuick() {
    const body = $('#p-body');
    if (!N) { body.innerHTML = '<div class="empty">전자칠판 앱에서만 사용할 수 있습니다</div>'; return; }
    const s = S.sys || {};
    const vol = s.volume || { cur: 0, max: 15 };
    const br = s.brightness || {};
    const net = s.network || {};
    const bt = s.bluetooth || {};
    const bat = s.battery;
    let html = '<div class="qs">';
    html += '<div class="tile"><h4>' + ic(vol.cur ? 'volume' : 'mute') + '볼륨<span class="right" id="vol-v">' + vol.cur + ' / ' + vol.max + '</span></h4><input type="range" class="range" id="vol" min="0" max="' + vol.max + '" value="' + vol.cur + '"></div>';
    html += '<div class="tile"><h4>' + ic('sun') + '밝기<span class="right">' + (br.auto ? '자동' : br.value >= 0 ? Math.round(br.value / 2.55) + '%' : '') + '</span></h4><input type="range" class="range" id="bri" min="1" max="255" value="' + (br.value > 0 ? br.value : 128) + '">' +
      '<div style="display:flex;gap:.5rem;margin-top:.4rem"><button class="btn sm" id="bri-auto">' + (br.auto ? '자동 밝기 끄기' : '자동 밝기') + '</button>' + (br.canWrite ? '' : '<button class="btn sm" data-open="writeSettings">시스템 밝기 권한 허용</button>') + '</div></div>';
    html += '<div class="tile"><h4>' + ic(net.transport === 'ethernet' ? 'ethernet' : net.connected ? 'wifi' : 'wifiOff') + '네트워크<span class="right"><button class="btn sm" data-open="wifi">Wi-Fi 설정</button></span></h4><dl class="kv">' +
      '<dt>연결</dt><dd>' + (net.connected ? (net.transport === 'wifi' ? 'Wi-Fi ' + esc(net.ssid || '(이름 확인 불가: 위치 권한 필요)') : net.transport === 'ethernet' ? '유선 LAN' : esc(net.transport)) : '연결 없음') + '</dd>' +
      (net.connected ? '<dt>인터넷</dt><dd>' + (net.internet ? '사용 가능' : '확인 안 됨') + '</dd>' : '') + (net.ip ? '<dt>IP</dt><dd>' + esc(net.ip) + '</dd>' : '') +
      (net.rssi != null ? '<dt>신호</dt><dd>' + net.rssi + ' dBm · ' + (net.level + 1) + '/5 · ' + net.linkMbps + ' Mbps · ' + (net.freqMHz > 4900 ? '5GHz' : '2.4GHz') + '</dd>' : '') + '</dl></div>';
    html += '<div class="tile"><h4>' + ic('bluetooth') + '블루투스<span class="right"><button class="btn sm" data-open="bluetooth">블루투스 설정</button></span></h4>';
    if (!bt.supported) html += '<div class="dim">블루투스를 지원하지 않는 기기입니다</div>';
    else if (bt.permission === false) html += '<button class="btn sm" data-open="bluetoothPerm">블루투스 권한 허용</button>';
    else html += '<div class="muted">' + (bt.enabled ? '켜짐' : '꺼짐') + (bt.name ? ' · ' + esc(bt.name) : '') + '</div>' + ((bt.bonded || []).length ? (bt.bonded.map((d) => '<div class="list-row" style="padding:.5rem 0">' + ic('bluetooth') + '<div class="grow"><b>' + esc(d.name || d.address) + '</b></div>' + (d.connected ? '<span class="chip green">연결됨</span>' : '<span class="chip gray">등록됨</span>') + '</div>').join('')) : '<div class="dim">등록된 기기가 없습니다</div>');
    html += '</div>';
    html += '<div class="tile"><h4>' + ic('storage') + '저장공간</h4>' + (s.storage || []).map((v) => '<div style="margin-bottom:.5rem"><div style="display:flex;justify-content:space-between"><span>' + esc(v.label) + '</span><span class="muted">' + (v.total ? C.bytes(v.free) + ' 남음' : esc(v.state || '')) + '</span></div>' +
      (v.total ? '<div class="bar"><i style="width:' + Math.round((v.total - v.free) / v.total * 100) + '%"></i></div>' : '') + '</div>').join('') + '</div>';
    if (bat && bat.present) html += '<div class="tile"><h4>' + ic('battery') + '배터리<span class="right">' + bat.level + '%' + (bat.plugged ? ' · 충전 중' : '') + '</span></h4></div>';
    html += '<div class="tile"><h4>' + ic('power') + '화면</h4><div style="display:flex;gap:.5rem;flex-wrap:wrap"><button class="btn" id="q-sleep">' + ic('moon') + '지금 절전</button><button class="btn" id="q-theme">' + ic('sun') + '밝은/어두운 테마</button><button class="btn" data-open="display">디스플레이 설정</button></div></div>';
    html += '</div>';
    body.innerHTML = html;
    $('#vol').oninput = (e) => { N.setVolume(Number(e.target.value)); $('#vol-v').textContent = e.target.value + ' / ' + vol.max; };
    $('#bri').oninput = (e) => { N.setBrightness(Number(e.target.value)); };
    $('#bri-auto').onclick = () => { native('setAutoBrightness', !br.auto); pollSys(); };
    $('#q-sleep').onclick = () => { closePanel(); N.sleepNow(); };
    $('#q-theme').onclick = () => {
      const cur = document.documentElement.dataset.theme === 'light' ? '' : 'light';
      document.documentElement.dataset.theme = cur;
      try { localStorage.setItem('cb_theme', cur); } catch (e) { /* ignore */ }
    };
    C.$$('[data-open]', body).forEach((b) => { b.onclick = () => native('open', b.dataset.open); });
  }

  // staff login (PIN) used for admin settings and uploads
  function staffLogin(purpose, roles, done) {
    if (S.admin && S.admin.until > Date.now() && roles.indexOf(S.admin.role) >= 0) { done(S.admin.token); return; }
    openPanel('login');
    $('#p-title').textContent = purpose + ' · 로그인';
    const body = $('#p-body');
    let pin = '';
    body.innerHTML = '<div class="form" style="margin:0 auto;justify-items:center"><label style="width:24rem">이름<input id="lg-name" autocomplete="off"></label>' +
      '<div class="pin"><div class="dots" id="lg-dots"></div><div class="keys">' + [1, 2, 3, 4, 5, 6, 7, 8, 9, 'C', 0, '확인'].map((k) => '<button data-k="' + k + '">' + k + '</button>').join('') + '</div><div class="err" id="lg-err"></div></div></div>';
    const dots = () => { $('#lg-dots').innerHTML = pin.split('').map(() => '<i class="f"></i>').join('') + (pin.length < 4 ? '<i></i>'.repeat(4 - pin.length) : ''); };
    dots();
    C.$$('[data-k]', body).forEach((b) => {
      b.onclick = async () => {
        const k = b.dataset.k;
        if (k === 'C') pin = '';
        else if (k === '확인') {
          try {
            const r = await C.post('/api/login', { kind: 'staff', name: $('#lg-name').value.trim(), pin }, { token: '' });
            if (roles.indexOf(r.user.role) < 0) throw new Error('권한이 없는 계정입니다');
            S.admin = { token: r.token, role: r.user.role, name: r.user.name, until: Date.now() + 10 * 60 * 1000 };
            done(r.token);
          } catch (e) { $('#lg-err').textContent = e.message; pin = ''; }
        } else if (pin.length < 12) pin += k;
        dots();
      };
    });
  }

  // settings
  function renderSettings() {
    const body = $('#p-body');
    if (!(S.admin && S.admin.until > Date.now() && S.admin.role === 'admin')) {
      const noUsers = S.state && S.state.setupDone === false;
      if (noUsers && S.device.role === 'hub') { setupWizard(true); closePanel(); return; }
      staffLogin('설정', ['admin'], () => openPanel('settings'));
      return;
    }
    S.admin.until = Date.now() + 10 * 60 * 1000;
    const d = S.device;
    const st = d.settings || {};
    const pw = st.power || {};
    const perms = d.perms || {};
    const sync = d.sync || {};
    let apps = [];
    if (N) { try { apps = JSON.parse(N.apps()); } catch (e) { apps = []; } }
    const rules = st.launchRules || [];
    const permRows = [
      ['defaultHome', '기본 홈 앱', '전원을 켜면 교실 OS가 바로 뜨도록 기본 홈 앱으로 지정', 'home'],
      ['overlay', '다른 앱 위에 표시', '긴급 알림 · 임원 알림 · 화면 메모를 다른 앱 위에 표시', 'overlay'],
      ['writeSettings', '시스템 설정 변경', '밝기 조절', 'writeSettings'],
      ['accessibility', '접근성 서비스', '화면 분할 실행', 'accessibility'],
      ['deviceAdmin', '기기 관리자', '일과 종료 후 화면 끄기', 'deviceAdmin'],
      ['notifications', '알림', '실행 상태 표시', 'notifications'],
      ['location', '위치', 'Wi-Fi 이름(SSID) 표시', 'location'],
      ['bluetooth', '블루투스', '연결된 블루투스 기기 표시', 'bluetoothPerm'],
      ['microphone', '마이크', '화면 녹화 시 소리 녹음', 'microphone'],
    ];
    body.innerHTML = '<div style="display:grid;grid-template-columns:1fr 1fr;gap:2rem">' +
      '<div><div class="section-t">' + ic('monitor') + '이 전자칠판</div><div class="form">' +
      '<label>기기 이름<input id="s-name" value="' + esc(d.name || '') + '" placeholder="예: 3학년 2반 전자칠판"></label>' +
      '<div class="row2"><label>학년<input id="s-g" type="number" min="1" max="6" value="' + esc((d.cls || '').split('-')[0] || '') + '"></label><label>반<input id="s-c" type="number" min="1" max="30" value="' + esc((d.cls || '').split('-')[1] || '') + '"></label></div>' +
      (d.role === 'client' ? '<label>학교 서버 주소<input id="s-hub" value="' + esc(d.hubUrl || '') + '"></label>' : '') +
      '<p class="muted">역할: ' + (d.role === 'hub' ? '학교 서버(허브)' : '교실 단말') + ' · IP ' + esc(d.ip || '-') + ':' + d.port + ' · 서버 ' + (sync.online ? '연결됨' : '끊김' + (sync.error ? ' (' + esc(sync.error) + ')' : '')) + ' · v' + esc(d.version || '') + '</p>' +
      '<button class="btn pri" id="s-save-dev">' + ic('check') + '기기 정보 저장</button></div>' +
      '<div class="section-t">' + ic('layers') + '자동 화면 전환</div>' +
      '<label class="switch">수업 시간에 수업 화면 자동 표시<input type="checkbox" id="s-autoLesson"' + (st.autoLesson !== false ? ' checked' : '') + '></label>' +
      '<label class="switch">쉬는 시간 화면 자동 표시<input type="checkbox" id="s-autoBreak"' + (st.autoBreak !== false ? ' checked' : '') + '></label>' +
      '<label class="switch">쉬는 시간에 행사 사진 슬라이드<input type="checkbox" id="s-breakSlides"' + (st.breakSlides !== false ? ' checked' : '') + '></label>' +
      '<label class="switch">수업 종료 시 자동 초기화 (메모 지우기, 홈으로)<input type="checkbox" id="s-resetOnEnd"' + (st.resetOnEnd !== false ? ' checked' : '') + '></label>' +
      '<label class="switch">다른 앱 위에 뒤로 · 홈 · 최근 앱 버튼 표시<input type="checkbox" id="s-floatingNav"' + (st.floatingNav !== false ? ' checked' : '') + '></label>' +
      '<div class="section-t">' + ic('power') + '자동 절전</div>' +
      '<label class="switch">일과 시간 외 화면 끄기<input type="checkbox" id="s-pw"' + (pw.enabled ? ' checked' : '') + '></label>' +
      '<div class="row2 form" style="margin-top:.6rem"><label>켜짐<input id="s-on" type="time" value="' + esc(pw.on || '07:30') + '"></label><label>꺼짐<input id="s-off" type="time" value="' + esc(pw.off || '17:30') + '"></label></div>' +
      '<label class="switch">주말에도 켜기<input type="checkbox" id="s-we"' + (pw.weekends ? ' checked' : '') + '></label>' +
      '<div class="section-t">' + ic('play') + '수업 시작 시 자동 실행 앱</div><div id="s-rules">' + rules.map((r, i) => '<div class="list-row"><div class="grow"><b>' + (r.period ? r.period + '교시' : '모든 교시') + (r.dow ? ' · ' + C.DOW[r.dow - 1] + '요일' : '') + '</b><span>' + esc((apps.find((a) => a.pkg === r.pkg) || { label: r.pkg }).label) + '</span></div><button class="btn sm" data-del-rule="' + i + '">' + ic('trash') + '</button></div>').join('') + '</div>' +
      '<div class="form" style="margin-top:.6rem"><div class="row2"><label>교시<select id="r-p"><option value="0">모든 교시</option>' + [1, 2, 3, 4, 5, 6, 7, 8].map((p) => '<option value="' + p + '">' + p + '교시</option>').join('') + '</select></label>' +
      '<label>요일<select id="r-d"><option value="0">매일</option>' + [2, 3, 4, 5, 6].map((x) => '<option value="' + x + '">' + C.DOW[x - 1] + '요일</option>').join('') + '</select></label></div>' +
      '<label>앱<select id="r-a">' + apps.map((a) => '<option value="' + esc(a.pkg) + '">' + esc(a.label) + '</option>').join('') + '</select></label><button class="btn" id="r-add">' + ic('plus') + '규칙 추가</button></div>' +
      '<div style="margin-top:1.2rem;display:flex;gap:.6rem;flex-wrap:wrap"><button class="btn pri" id="s-save">' + ic('check') + '자동화 설정 저장</button></div></div>' +
      '<div><div class="section-t">' + ic('lock') + '권한</div>' + permRows.map((p) => '<div class="perm"><span class="dot' + (perms[p[0]] ? ' on' : '') + '"></span><div class="grow"><b>' + p[1] + '</b><span>' + p[2] + '</span></div><button class="btn sm" data-open="' + p[3] + '">' + (perms[p[0]] ? '설정' : '허용') + '</button></div>').join('') +
      '<div class="section-t">' + ic('refresh') + '데이터</div><div style="display:flex;gap:.6rem;flex-wrap:wrap"><button class="btn" id="s-refresh">' + ic('refresh') + '시간표 · 급식 · 일정 새로고침</button><button class="btn" id="s-portal">' + ic('school') + '학교 설정 (관리자 포털)</button></div>' +
      '<div id="s-errors" class="muted" style="margin-top:.6rem"></div>' +
      '<div class="section-t">' + ic('settings') + '시스템</div><div style="display:flex;gap:.6rem;flex-wrap:wrap"><button class="btn" data-open="settings">안드로이드 설정</button><button class="btn" data-open="date">날짜 · 시간</button><button class="btn" data-open="appInfo">앱 정보</button><button class="btn" id="s-reload">' + ic('refresh') + '화면 다시 불러오기</button><button class="btn red" id="s-logout">' + ic('logout') + '관리자 로그아웃</button></div></div></div>';
    const errs = S.data && S.data.errors || {};
    $('#s-errors').innerHTML = Object.keys(errs).map((k) => '<div class="err-text">' + esc(k) + ': ' + esc(errs[k].message) + '</div>').join('') || '<span class="ok-text">최근 데이터 갱신 오류 없음</span>';
    C.$$('[data-open]', body).forEach((b) => { b.onclick = () => native('open', b.dataset.open); });
    const tok = { token: S.admin.token };
    $('#s-save-dev').onclick = async () => {
      const g = $('#s-g').value.trim(), c = $('#s-c').value.trim();
      if ((g || c) && !validCls(g, c)) { toast('학년은 1~6, 반은 1~30 사이 숫자로 입력하세요'); return; }
      const payload = { name: $('#s-name').value.trim(), cls: g && c ? g + '-' + c : '' };
      if ($('#s-hub')) payload.hubUrl = $('#s-hub').value.trim();
      try { await C.post('/api/local/device', payload, tok); S.device = await C.get('/api/local/device'); toast('저장했습니다'); loadData(); renderAll(); } catch (e) { toast(e.message); }
    };
    const saveSettings = async (extra) => {
      const next = Object.assign({}, st, {
        autoLesson: $('#s-autoLesson').checked, autoBreak: $('#s-autoBreak').checked, breakSlides: $('#s-breakSlides').checked, resetOnEnd: $('#s-resetOnEnd').checked, floatingNav: $('#s-floatingNav').checked,
        power: { enabled: $('#s-pw').checked, on: $('#s-on').value || '07:30', off: $('#s-off').value || '17:30', weekends: $('#s-we').checked },
      }, extra || {});
      try { await C.post('/api/local/settings', next, tok); S.device = await C.get('/api/local/device'); toast('저장했습니다'); renderSettings(); } catch (e) { toast(e.message); }
    };
    $('#s-save').onclick = () => saveSettings();
    $('#r-add').onclick = () => { const r = rules.concat([{ period: Number($('#r-p').value), dow: Number($('#r-d').value), pkg: $('#r-a').value }]); saveSettings({ launchRules: r }); };
    C.$$('[data-del-rule]', body).forEach((b) => { b.onclick = () => { const r = rules.slice(); r.splice(Number(b.dataset.delRule), 1); saveSettings({ launchRules: r }); }; });
    $('#s-refresh').onclick = async () => { await C.post('/api/local/refresh', {}); toast('새로고침을 요청했습니다'); setTimeout(loadData, 4000); };
    $('#s-portal').onclick = () => openPanel('portal', { hash: '#/staff', token: S.admin.token });
    $('#s-reload').onclick = () => { if (N) N.reload(); else location.reload(); };
    $('#s-logout').onclick = () => { S.admin = null; closePanel(); };
  }

  // portal (embedded, token kept only in memory)
  function renderPortal() {
    const body = $('#p-body');
    const hash = S.panelOpts.hash || '#/staff';
    body.style.padding = '0';
    body.innerHTML = '<iframe src="/m/?board=1' + (S.panelOpts.token ? '&t=' + encodeURIComponent(S.panelOpts.token) : '') + hash + '" style="width:100%;height:100%;border:0;background:var(--bg)"></iframe>';
    const restore = () => { body.style.padding = ''; };
    setTimeout(() => { if (S.panel !== 'portal') restore(); }, 0);
    $('#p-close').addEventListener('click', restore, { once: true });
  }

  // ---------------------------------------------------------------- setup wizard
  function setupWizard(adminOnly) {
    const wrap = $('#setup');
    const box = $('#setup-box');
    wrap.classList.add('on');
    const W = { role: adminOnly ? 'hub' : '', hub: '', token: '' };

    function stepRole() {
      box.innerHTML = '<h1>교실 OS 시작하기</h1><p class="lead">이 전자칠판의 역할을 선택하세요. 학교에 한 대만 "학교 서버"로 두고 나머지는 "교실 단말"로 연결합니다. 인터넷이 끊겨도 같은 교내 네트워크 안에서는 알림이 동작합니다.</p>' +
        '<div class="choice"><button data-r="hub">' + ic('school') + '<b>학교 서버 (허브)</b><span>공지 · 긴급 알림 · 계정 · 학급 데이터를 보관합니다. 교무실 등 항상 켜 두는 기기에 권장합니다.</span></button>' +
        '<button data-r="client">' + ic('monitor') + '<b>교실 단말</b><span>교내 네트워크의 학교 서버에 연결해 시간표 · 공지 · 알림을 표시합니다.</span></button></div>';
      C.$$('[data-r]', box).forEach((b) => { b.onclick = () => { W.role = b.dataset.r; stepDevice(); }; });
    }

    function stepDevice() {
      box.innerHTML = '<h1>' + (W.role === 'hub' ? '학교 서버 설정' : '학교 서버 연결') + '</h1><p class="lead">' + (W.role === 'hub' ? '이 기기의 이름과 담당 학급(선택)을 입력하세요.' : '같은 네트워크에서 찾은 학교 서버를 선택하거나 주소를 입력하세요.') + '</p>' +
        '<div class="form">' + (W.role === 'client' ? '<div id="w-hubs"><div class="empty">' + ic('search') + '학교 서버를 찾는 중...</div></div><label>서버 주소 직접 입력<input id="w-hub" placeholder="예: 192.168.0.10"></label>' : '') +
        '<label>기기 이름<input id="w-name" placeholder="예: 3학년 2반 전자칠판"></label>' +
        '<div class="row2"><label>학년 (교실이 아니면 비움)<input id="w-g" type="number" min="1" max="6"></label><label>반<input id="w-c" type="number" min="1" max="30"></label></div>' +
        '<div class="err-text" id="w-err"></div><div style="display:flex;gap:.6rem"><button class="btn" id="w-back">' + ic('left') + '이전</button><button class="btn pri" id="w-next">다음</button></div></div>';
      $('#w-back').onclick = stepRole;
      if (W.role === 'client') {
        const find = async () => {
          if (!$('#w-hubs')) return;
          try {
            const r = await C.get('/api/local/discover');
            $('#w-hubs').innerHTML = r.hubs.length ? r.hubs.map((h) => '<div class="list-row">' + ic('school') + '<div class="grow"><b>' + esc(h.school || '학교 서버') + '</b><span>' + esc(h.url) + '</span></div><button class="btn' + (W.hub === h.url ? ' pri' : '') + '" data-hub="' + esc(h.url) + '">' + (W.hub === h.url ? '선택됨' : '선택') + '</button></div>').join('')
              : '<div class="empty">' + ic('search') + '학교 서버를 찾는 중... (서버 기기가 켜져 있고 같은 네트워크인지 확인하세요)</div>';
            C.$$('[data-hub]', box).forEach((b) => { b.onclick = () => { W.hub = b.dataset.hub; $('#w-hub').value = W.hub; find(); }; });
          } catch (e) { /* retry */ }
          setTimeout(find, 2500);
        };
        find();
      }
      $('#w-next').onclick = async () => {
        const g = $('#w-g').value.trim(), c = $('#w-c').value.trim();
        if ((g || c) && !validCls(g, c)) { $('#w-err').textContent = '학년은 1~6, 반은 1~30 사이 숫자로 입력하세요'; return; }
        const payload = { role: W.role, name: $('#w-name').value.trim() || (g && c ? g + '학년 ' + c + '반' : '전자칠판'), cls: g && c ? g + '-' + c : '' };
        if (W.role === 'client') {
          const url = ($('#w-hub').value || W.hub).trim();
          if (!url) { $('#w-err').textContent = '학교 서버를 선택하거나 주소를 입력하세요'; return; }
          const pr = await C.get('/api/local/probe?url=' + encodeURIComponent(url));
          if (!pr.ok || !pr.info || !pr.info.hub) { $('#w-err').textContent = '학교 서버에 연결할 수 없습니다: ' + (pr.error || '응답 없음'); return; }
          payload.hubUrl = pr.url;
        }
        try {
          await C.post('/api/local/device', payload, { token: '' });
          S.device = await C.get('/api/local/device');
          if (W.role === 'hub') { await sleep(600); stepAdmin(); } else finish();
        } catch (e) { $('#w-err').textContent = e.message; }
      };
    }

    async function stepAdmin() {
      S.inWizardAdmin = true;
      let st = null;
      try { st = (await C.get('/api/state')).state; } catch (e) { st = null; }
      if (st && st.setupDone) { stepSchoolLogin(); return; }
      box.innerHTML = '<h1>관리자 계정 만들기</h1><p class="lead">학교 설정, 계정 관리, 긴급 알림 발송에 사용하는 관리자 계정입니다. PIN은 4자리 이상 숫자로 정하세요.</p><div class="form">' +
        '<label>관리자 이름<input id="a-name" placeholder="예: 정보부장"></label><label>PIN<input id="a-pin" type="password" inputmode="numeric" autocomplete="new-password"></label><label>PIN 확인<input id="a-pin2" type="password" inputmode="numeric"></label>' +
        '<div class="err-text" id="a-err"></div><button class="btn pri" id="a-next">계정 만들기</button></div>';
      $('#a-next').onclick = async () => {
        const pin = $('#a-pin').value;
        if (pin !== $('#a-pin2').value) { $('#a-err').textContent = 'PIN이 서로 다릅니다'; return; }
        try {
          const r = await C.post('/api/setup/init', { name: $('#a-name').value.trim(), pin }, { token: '' });
          W.token = r.token;
          S.admin = { token: r.token, role: 'admin', name: r.user.name, until: Date.now() + 10 * 60 * 1000 };
          stepSchool();
        } catch (e) { $('#a-err').textContent = e.message; }
      };
    }

    function stepSchoolLogin() {
      staffLogin('학교 설정', ['admin'], (tok) => { W.token = tok; closePanel(); stepSchool(); });
      wrap.classList.remove('on');
    }

    function stepSchool() {
      wrap.classList.add('on');
      const tok = { token: W.token };
      const sel = { school: null, comci: null, geo: null };
      box.innerHTML = '<h1>학교 선택</h1><p class="lead">NEIS(급식 · 학사일정)와 컴시간알리미(시간표)에서 학교를 찾아 연결합니다. 인터넷 연결이 필요합니다.</p><div class="form" style="max-width:none">' +
        '<div style="display:flex;gap:.6rem"><input id="sc-q" placeholder="학교 이름 (예: 서울고등학교)" style="flex:1"><button class="btn pri" id="sc-go">' + ic('search') + '검색</button></div>' +
        '<div id="sc-neis"></div><div id="sc-comci"></div><div id="sc-geo"></div><div class="err-text" id="sc-err"></div>' +
        '<div style="display:flex;gap:.6rem"><button class="btn" id="sc-skip">나중에 설정</button><button class="btn pri" id="sc-save" disabled>' + ic('check') + '저장하고 시작</button></div></div>';
      const upd = () => { $('#sc-save').disabled = !sel.school; };
      $('#sc-go').onclick = async () => {
        const q = $('#sc-q').value.trim();
        if (!q) return;
        $('#sc-err').textContent = '';
        $('#sc-neis').innerHTML = '<div class="empty">' + ic('search') + 'NEIS 검색 중...</div>';
        try {
          const r = await C.get('/api/admin/neis-search?q=' + encodeURIComponent(q), tok);
          $('#sc-neis').innerHTML = '<div class="section-t">' + ic('school') + 'NEIS 학교 (급식 · 학사일정)</div>' + (r.items.length ? r.items.map((s, i) => '<div class="list-row"><div class="grow"><b>' + esc(s.name) + '</b><span>' + esc(s.atptName + ' · ' + s.address) + '</span></div><button class="btn" data-ns="' + i + '">선택</button></div>').join('') : '<div class="empty">검색 결과 없음</div>');
          C.$$('[data-ns]', box).forEach((b) => {
            b.onclick = async () => {
              sel.school = r.items[Number(b.dataset.ns)];
              C.$$('[data-ns]', box).forEach((x) => { x.className = 'btn' + (x === b ? ' pri' : ''); x.textContent = x === b ? '선택됨' : '선택'; });
              upd();
              searchComci(sel.school.name.replace(/등학교$|학교$/, ''));
              geocode();
            };
          });
        } catch (e) { $('#sc-neis').innerHTML = ''; $('#sc-err').textContent = 'NEIS 검색 실패: ' + e.message; }
      };
      async function searchComci(q) {
        $('#sc-comci').innerHTML = '<div class="empty">' + ic('search') + '컴시간 검색 중...</div>';
        try {
          const r = await C.get('/api/admin/comci-search?q=' + encodeURIComponent(q), tok);
          const region = sel.school.region.replace(/특별시|광역시|특별자치시|특별자치도|도$/g, '').slice(0, 2);
          const items = r.items.slice().sort((a, b) => (b.name === sel.school.name) - (a.name === sel.school.name) || (b.region.indexOf(region) >= 0) - (a.region.indexOf(region) >= 0));
          $('#sc-comci').innerHTML = '<div class="section-t">' + ic('clock') + '컴시간알리미 (시간표)</div>' + (items.length ? items.slice(0, 12).map((s) => '<div class="list-row"><div class="grow"><b>' + esc(s.name) + '</b><span>' + esc(s.region) + ' · 코드 ' + s.code + '</span></div><button class="btn" data-cc="' + s.code + '">선택</button></div>').join('') : '<div class="empty">컴시간에 등록되지 않은 학교입니다. NEIS 시간표를 사용합니다.</div>');
          C.$$('[data-cc]', box).forEach((b) => {
            b.onclick = () => {
              sel.comci = items.find((x) => String(x.code) === b.dataset.cc);
              C.$$('[data-cc]', box).forEach((x) => { x.className = 'btn' + (x === b ? ' pri' : ''); x.textContent = x === b ? '선택됨' : '선택'; });
            };
          });
        } catch (e) { $('#sc-comci').innerHTML = '<div class="err-text">컴시간 검색 실패: ' + esc(e.message) + '</div>'; }
      }
      async function geocode() {
        $('#sc-geo').innerHTML = '<div class="empty">' + ic('pin') + '학교 위치 찾는 중...</div>';
        let r = null;
        try { r = await C.get('/api/admin/geocode?q=' + encodeURIComponent(sel.school.name), tok); } catch (e) { r = null; }
        if (!r || !r.items.length) { try { r = await C.get('/api/admin/geocode?q=' + encodeURIComponent(sel.school.address.split(',')[0]), tok); } catch (e) { r = null; } }
        if (r && r.items.length) {
          sel.geo = r.items[0];
          $('#sc-geo').innerHTML = '<div class="list-row">' + ic('pin') + '<div class="grow"><b>날씨 위치</b><span>' + esc(sel.geo.name) + '</span></div><span class="chip green">자동 설정</span></div>';
        } else $('#sc-geo').innerHTML = '<div class="dim">학교 위치를 찾지 못했습니다. 나중에 관리자 포털에서 날씨 위치를 입력할 수 있습니다.</div>';
      }
      $('#sc-save').onclick = async () => {
        const cfg = { school: sel.school };
        if (sel.comci) cfg.comci = sel.comci;
        if (sel.geo) { cfg.lat = sel.geo.lat; cfg.lon = sel.geo.lon; cfg.locationName = sel.school.name; }
        try { await C.post('/api/admin/config', cfg, tok); await C.post('/api/local/refresh', {}); finish(); } catch (e) { $('#sc-err').textContent = e.message; }
      };
      $('#sc-skip').onclick = finish;
    }

    function finish() {
      S.inWizardAdmin = false;
      wrap.classList.remove('on');
      start();
      setTimeout(loadData, 3000);
      setTimeout(loadData, 10000);
    }

    if (adminOnly) stepAdmin(); else stepRole();
  }

  boot();
})();
