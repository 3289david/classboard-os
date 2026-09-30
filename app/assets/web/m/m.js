/* Mobile portal: administrators and students (QR pages, class officer board). */
(function () {
  'use strict';
  const C = window.CB;
  const $ = C.$, esc = C.esc;
  const ic = (n) => window.icon(n);
  const root = $('#root');

  // On the board, the portal is embedded and must not remember logins.
  const q0 = new URLSearchParams(location.search);
  const EMBED = q0.get('board') === '1';
  let memToken = q0.get('t') || '';
  const getTok = () => (EMBED ? memToken : C.token());
  const setTok = (t) => { if (EMBED) memToken = t || ''; else C.setToken(t); };
  const api = (m, p, b, o) => C.api(m, p, b, Object.assign({ token: getTok() }, o || {}));
  const get = (p) => api('GET', p);
  const post = (p, b) => api('POST', p, b || {});
  const del = (p) => api('DELETE', p);

  let state = null;
  let me = null;
  let tab = null;

  function toast(msg, ms) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('on'), ms || 3000);
  }
  function title(t) { $('#title').textContent = t; document.title = t + ' · 교실 OS'; }
  function params() { const h = location.hash; const i = h.indexOf('?'); return new URLSearchParams(i >= 0 ? h.slice(i + 1) : ''); }
  function route() { const h = location.hash.replace(/^#/, ''); return (h.split('?')[0] || '/'); }
  function classLabel(c) { if (!c) return ''; const p = c.split('-'); return p[0] + '학년 ' + p[1] + '반'; }
  async function loadState() { try { state = (await C.get('/api/state', { token: '' })).state; } catch (e) { state = null; } return state; }
  function card(h2, body, right) { return '<div class="card"><h2>' + h2 + (right ? '<span class="right">' + right + '</span>' : '') + '</h2>' + body + '</div>'; }
  function val(id) { const el = document.getElementById(id); if (!el) return ''; return el.type === 'checkbox' ? el.checked : el.value.trim(); }
  function clsOptions(selected, allowEmpty) {
    const counts = (state && state.config && state.config._classCounts) || null;
    let list = [];
    if (counts) Object.keys(counts).forEach((g) => { for (let c = 1; c <= counts[g]; c++) list.push(g + '-' + c); });
    const known = new Set(list);
    Object.keys((state && state.classes) || {}).forEach((c) => { if (!known.has(c)) list.push(c); });
    ((me && me.classes) || []).forEach((c) => { if (list.indexOf(c) < 0) list.push(c); });
    list.sort((a, b) => { const x = a.split('-').map(Number), y = b.split('-').map(Number); return x[0] - y[0] || x[1] - y[1]; });
    return (allowEmpty ? '<option value="">선택</option>' : '') + list.map((c) => '<option value="' + c + '"' + (c === selected ? ' selected' : '') + '>' + classLabel(c) + '</option>').join('');
  }
  function clsInput(id, value) {
    return '<div class="grid2"><label>학년<input id="' + id + '-g" type="number" min="1" max="6" value="' + esc((value || '').split('-')[0] || '') + '"></label><label>반<input id="' + id + '-c" type="number" min="1" max="30" value="' + esc((value || '').split('-')[1] || '') + '"></label></div>';
  }
  function clsVal(id) { const g = val(id + '-g'), c = val(id + '-c'); return g && c ? g + '-' + c : ''; }
  function myDefaultCls() { return (me && me.classes && me.classes[0]) || localStorage.getItem('cb_last_cls') || Object.keys((state && state.classes) || {})[0] || ''; }
  function rememberCls(c) { try { localStorage.setItem('cb_last_cls', c); } catch (e) { /* ignore */ } }

  // class counts from comcigan (for class pickers)
  async function loadClassCounts() {
    try {
      const r = await C.get('/api/local/comci/classes', { token: '' });
      if (state && state.config && r.classCounts) state.config._classCounts = r.classCounts;
    } catch (e) { /* no comcigan data */ }
  }

  // ---------------------------------------------------------------- router
  async function render() {
    const r = route();
    const p = params();
    $('#back').innerHTML = r === '/' ? ic('school') : ic('left');
    $('#back').onclick = () => { if (r !== '/') history.length > 1 ? history.back() : (location.hash = '#/'); };
    if (!state) await loadState();
    try {
      switch (r) {
        case '/att': return pageAttend(p.get('c'), p.get('t'));
        case '/read': return pageRead(p.get('n'), p.get('c'));
        case '/notices': return pageNotices(p.get('c'));
        case '/hp': return pageHomepagePost(p.get('m'), p.get('b'), p.get('n'), p.get('s'));
        case '/officer': return pageOfficer(p.get('c'));
        case '/staff': return pageStaff();
        default: return pageHome();
      }
    } catch (e) {
      root.innerHTML = card(ic('alert') + '오류', '<p>' + esc(e.message) + '</p>');
    }
  }
  window.addEventListener('hashchange', render);

  function pageHome() {
    title((state && state.config && (state.config.displayName || (state.config.school && state.config.school.name))) || '교실 OS');
    const c = localStorage.getItem('cb_last_cls') || '';
    root.innerHTML = '<div class="big-choice">' +
      '<button data-go="#/staff">' + ic('lock') + '<div><b>관리자</b><span>공지, 오늘의 수업, 출석, 학급, 긴급 알림, 학교 설정</span></div></button>' +
      '<button data-go="#/notices' + (c ? '?c=' + c : '') + '">' + ic('user') + '<div><b>학생</b><span>우리 반 공지 확인</span></div></button>' +
      '<button data-go="#/officer' + (c ? '?c=' + c : '') + '">' + ic('bell') + '<div><b>학급 임원</b><span>회장 · 부회장 알림 버튼</span></div></button></div>';
    C.$$('[data-go]').forEach((b) => { b.onclick = () => { location.hash = b.dataset.go; }; });
  }

  // ---------------------------------------------------------------- student pages
  function numberPicker(cls, onPick, note) {
    const cs = state && state.classes && state.classes[cls];
    const nums = (cs && cs.numbers) || [];
    if (!nums.length) return '<div class="empty">담임 선생님이 학생 명단을 아직 등록하지 않았습니다.</div>';
    setTimeout(() => {
      C.$$('.nums button').forEach((b) => { b.onclick = () => { C.$$('.nums button').forEach((x) => x.classList.toggle('on', x === b)); onPick(Number(b.dataset.n)); }; });
    }, 0);
    return (note ? '<p class="muted" style="margin-bottom:.7rem">' + note + '</p>' : '') + '<div class="nums">' + nums.slice().sort((a, b) => a - b).map((n) => '<button data-n="' + n + '">' + n + '</button>').join('') + '</div>';
  }

  function pageAttend(cls, t) {
    title('QR 출석 · ' + classLabel(cls));
    rememberCls(cls);
    let no = 0;
    root.innerHTML = card(ic('users') + classLabel(cls) + ' 출석', numberPicker(cls, (n) => { no = n; $('#att-go').disabled = false; }, '자기 번호를 누른 뒤 출석하기를 누르세요.') +
      '<div style="margin-top:1rem"><button class="btn pri block" id="att-go" disabled>' + ic('check') + '출석하기</button></div><div id="att-res"></div>');
    $('#att-go').onclick = async () => {
      try {
        const r = await post('/api/student/attend', { cls, t, no });
        const already = r.result && r.result.indexOf('already:') === 0;
        root.innerHTML = card(ic('check') + '완료', '<div class="pill-ok">' + ic('check') + '</div><p class="center" style="font-size:1.3rem;font-weight:800">' + no + '번 ' + (already ? '이미 ' + (C.ATT[r.result.split(':')[1]] || '처리') + '되어 있습니다' : '출석했습니다') + '</p>');
      } catch (e) { $('#att-res').innerHTML = '<p style="color:var(--red);margin-top:.8rem">' + esc(e.message) + '</p>'; }
    };
  }

  function pageNotices(cls) {
    title('공지 · ' + classLabel(cls));
    if (!cls) { root.innerHTML = card(ic('info') + '학급 선택', '<div class="form">' + clsInput('nc') + '<button class="btn pri" id="nc-go">보기</button></div>'); $('#nc-go').onclick = () => { location.hash = '#/notices?c=' + clsVal('nc'); }; return; }
    rememberCls(cls);
    const list = C.noticesFor(state, cls);
    root.innerHTML = list.length ? list.map((n) => card((n.pinned ? ic('pinned') : ic('megaphone')) + esc(n.title), '<div class="muted" style="white-space:pre-wrap">' + esc(n.body || '') + '</div><div class="row" style="margin-top:.7rem"><span class="chip ' + (n.level === 'urgent' ? 'red' : n.level === 'class' ? 'blue' : 'orange') + '">' + esc(C.LEVEL[n.level] || '일반') + '</span><span class="dim">' + esc(n.author || '') + ' · ' + C.dateTime(n.createdAt) + '</span>' +
      (n.needRead ? '<a class="btn sm pri" href="#/read?n=' + n.id + '&c=' + cls + '" style="margin-left:auto">' + ic('check') + '읽음 확인</a>' : '') + '</div>')).join('') : card(ic('megaphone') + '공지', '<div class="empty">등록된 공지가 없습니다</div>');
    homepageSection().then((h) => { if (route() === '/notices' && h) root.insertAdjacentHTML('beforeend', h); });
  }

  async function homepageSection() {
    let d = null;
    try { d = await C.get('/api/local/data', { token: '' }); } catch (e) { d = null; }
    const boards = (d && d.homepage && d.homepage.boards) || [];
    if (!boards.length) return '';
    return boards.map((b) => card(ic('file') + esc(b.name), (b.items || []).map((it) =>
      '<a class="item" style="text-decoration:none;color:inherit" href="#/hp?m=' + encodeURIComponent(b.menuId) + '&b=' + encodeURIComponent(it.bbsId) + '&n=' + encodeURIComponent(it.nttId) + '&s=' + (it.sen ? 1 : 0) + '">' +
      '<div class="grow"><b>' + (it.pinned ? ic('pinned') + ' ' : '') + esc(it.title) + '</b><span class="sub">' + esc(it.date) + (it.file ? ' · 첨부파일' : '') + '</span></div></a>').join('') || '<div class="empty">게시물이 없습니다</div>')).join('');
  }

  async function pageHomepagePost(menuId, bbsId, nttId, sen) {
    title('가정통신문');
    root.innerHTML = card(ic('refresh') + '불러오는 중', '<div class="empty">학교 홈페이지에서 불러오는 중...</div>');
    try {
      const d = await C.get('/api/homepage/detail?menuId=' + encodeURIComponent(menuId) + '&bbsId=' + encodeURIComponent(bbsId) + '&nttId=' + encodeURIComponent(nttId) + '&sen=' + (sen === '1' ? 1 : 0), { token: '' });
      root.innerHTML = card(ic('file') + esc(d.title || '가정통신문'),
        (d.files.length ? d.files.map((f) => '<div class="item">' + ic('download') + '<div class="grow"><b>' + esc(f.name) + '</b><span class="sub">' + C.bytes(f.size) + '</span></div><a class="btn sm pri" href="' + esc(f.url) + '" target="_blank" rel="noopener">받기</a></div>').join('') : '') +
        (d.body ? '<div style="white-space:pre-wrap;margin-top:.8rem">' + esc(d.body) + '</div>' : '') +
        d.images.map((src) => '<img src="' + esc(src) + '" alt="" style="max-width:100%;margin-top:.8rem;border-radius:.5rem">').join('') +
        '<p class="dim" style="margin-top:1rem"><a href="' + esc(d.url) + '" target="_blank" rel="noopener">학교 홈페이지에서 보기</a></p>');
    } catch (e) {
      root.innerHTML = card(ic('alert') + '오류', '<p>' + esc(e.message) + '</p>');
    }
  }

  function pageRead(nid, cls) {
    const n = ((state && state.notices) || []).find((x) => x.id === nid);
    title('공지 읽음 확인');
    if (!n) { root.innerHTML = card(ic('info') + '공지', '<div class="empty">삭제되었거나 없는 공지입니다</div>'); return; }
    let no = 0;
    root.innerHTML = card(ic('megaphone') + esc(n.title), '<div style="white-space:pre-wrap">' + esc(n.body || '') + '</div><div class="dim" style="margin-top:.6rem">' + esc(n.author || '') + ' · ' + C.dateTime(n.createdAt) + '</div>') +
      card(ic('check') + '읽음 확인 · ' + classLabel(cls), numberPicker(cls, (x) => { no = x; $('#rd-go').disabled = false; }, '번호를 선택하고 확인을 누르세요.') + '<div style="margin-top:1rem"><button class="btn pri block" id="rd-go" disabled>확인했습니다</button></div>');
    $('#rd-go').onclick = async () => {
      try { await post('/api/student/read', { noticeId: nid, cls, no }); toast(no + '번 읽음 확인 완료'); $('#rd-go').disabled = true; $('#rd-go').textContent = '확인 완료'; } catch (e) { toast(e.message); }
    };
  }

  function uploadWithProgress(url, file, bar, done) {
    const x = new XMLHttpRequest();
    x.open('PUT', url);
    x.setRequestHeader('X-Filename', encodeURIComponent(file.name));
    const t = getTok();
    if (t) x.setRequestHeader('X-Token', t);
    x.upload.onprogress = (e) => { if (e.lengthComputable && bar) bar.style.width = Math.round(e.loaded / e.total * 100) + '%'; };
    x.onload = () => {
      let r = {};
      try { r = JSON.parse(x.responseText); } catch (e) { r = {}; }
      if (x.status >= 400) toast(r.error || '업로드 실패');
      else done(r);
    };
    x.onerror = () => toast('네트워크 오류로 업로드하지 못했습니다');
    x.send(file);
  }

  // ---------------------------------------------------------------- login
  function loginForm(kind, cls, onDone) {
    root.innerHTML = card(ic('lock') + (kind === 'officer' ? '임원 로그인' : '로그인'), '<div class="form">' + (kind === 'officer' ? clsInput('lg', cls) : '') +
      '<label>이름<input id="lg-name" autocomplete="username"></label><label>PIN<input id="lg-pin" type="password" inputmode="numeric" autocomplete="current-password"></label>' +
      '<button class="btn pri" id="lg-go">' + ic('lock') + '로그인</button><p class="dim">' + (kind === 'officer' ? '담임 선생님이 등록한 이름과 PIN을 입력하세요.' : '관리자가 만든 계정의 이름과 PIN을 입력하세요.') + '</p></div>');
    $('#lg-go').onclick = async () => {
      try {
        const body = { kind, name: val('lg-name'), pin: $('#lg-pin').value };
        if (kind === 'officer') body.cls = clsVal('lg');
        const r = await C.post('/api/login', body, { token: '' });
        setTok(r.token);
        me = r.user;
        onDone();
      } catch (e) { toast(e.message); }
    };
  }

  async function ensureMe(roles, kind, cls) {
    if (getTok()) {
      try { me = (await get('/api/me')).user; } catch (e) { me = null; setTok(''); }
    }
    if (me && roles.indexOf(me.role) >= 0) return true;
    loginForm(kind || 'staff', cls, render);
    return false;
  }
  function logoutBtn() { return '<button class="btn sm" id="logout">' + ic('logout') + '로그아웃</button>'; }
  function bindLogout() { const b = $('#logout'); if (b) b.onclick = async () => { try { await post('/api/logout'); } catch (e) { /* ignore */ } setTok(''); me = null; render(); }; }

  // ---------------------------------------------------------------- officer
  async function pageOfficer(cls) {
    title('임원 알림판');
    if (!(await ensureMe(['officer'], 'officer', cls))) return;
    $('#who').textContent = me.name + ' (' + (me.title || '임원') + ')';
    let st = null;
    const draw = async () => {
      try { st = await get('/api/officer/status'); } catch (e) { if (e.status === 401) { setTok(''); me = null; render(); return; } }
      await loadState();
      const s = st.settings;
      const nowSrv = st.now;
      const cool = Math.max(0, Math.ceil((st.cooldownUntil - nowSrv) / 1000));
      const blocked = st.blockedUntil > nowSrv;
      let note = '';
      if (!s.enabled) note = '선생님이 임원 버튼을 꺼 두었습니다.';
      else if (!st.inWindow) note = '지금은 사용 가능한 시간이 아닙니다.';
      else if (blocked) note = '반복 사용으로 ' + new Date(st.blockedUntil).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }) + '까지 제한됩니다.';
      else if (cool) note = cool + '초 후 다시 누를 수 있습니다.';
      const disabled = !s.enabled || !st.inWindow || blocked || cool > 0;
      root.innerHTML = card(ic('bell') + classLabel(me.cls) + ' 알림판', '<p class="muted" style="margin-bottom:.9rem">버튼을 누르면 교실 전자칠판에 크게 표시됩니다. 누른 사람의 이름은 칠판에 표시되지 않으며, 선생님만 확인할 수 있습니다.</p>' +
        (note ? '<p style="color:var(--orange);font-weight:700;margin-bottom:.8rem">' + esc(note) + '</p>' : '') +
        '<div class="officer-btns">' + C.OFFICER_TYPES.filter((t) => (s.types || []).indexOf(t.type) >= 0).map((t) => '<button class="bg-' + t.color + '" data-t="' + t.type + '"' + (disabled ? ' disabled' : '') + '>' + ic(t.icon) + esc(t.label) + '</button>').join('') + '</div>', logoutBtn());
      bindLogout();
      C.$$('[data-t]').forEach((b) => {
        b.onclick = async () => {
          try { await post('/api/officer/press', { type: b.dataset.t }); toast('전자칠판에 표시했습니다'); } catch (e) { toast(e.message, 4000); }
          draw();
        };
      });
      clearTimeout(pageOfficer._t);
      if (route() === '/officer') pageOfficer._t = setTimeout(draw, cool > 0 ? 1000 : 10000);
    };
    draw();
  }

  // ---------------------------------------------------------------- staff
  const TABS = [
    ['lesson', '수업', 'book'], ['notice', '공지', 'megaphone'], ['plan', '과제 · 일정', 'calendar'],
    ['class', '학급', 'users'], ['emergency', '긴급', 'alert'], ['settings', '설정', 'settings'],
  ];
  let curCls = '';

  async function pageStaff() {
    title('관리자');
    if (!(await ensureMe(['teacher', 'admin']))) return;
    $('#who').textContent = me.name;
    await loadState();
    await loadClassCounts();
    startCallWatch();
    const tabs = TABS;
    if (!tab || !tabs.find((t) => t[0] === tab)) tab = params().get('tab') || 'lesson';
    root.innerHTML = '<div class="tabs">' + tabs.map((t) => '<button data-tab="' + t[0] + '" class="' + (t[0] === tab ? 'on' : '') + '">' + ic(t[2]) + t[1] + '</button>').join('') + '</div><div id="tab"></div><div class="row" style="justify-content:flex-end">' + logoutBtn() + '<button class="btn sm" id="chg-pin">' + ic('lock') + 'PIN 변경</button></div>';
    bindLogout();
    $('#chg-pin').onclick = changePin;
    C.$$('[data-tab]').forEach((b) => { b.onclick = () => { tab = b.dataset.tab; C.$$('[data-tab]').forEach((x) => x.classList.toggle('on', x === b)); drawTab(); }; });
    drawTab();
  }

  function changePin() {
    const el = $('#tab');
    el.innerHTML = card(ic('lock') + 'PIN 변경', '<div class="form"><label>기존 PIN<input id="op" type="password" inputmode="numeric"></label><label>새 PIN (4자리 이상)<input id="np" type="password" inputmode="numeric"></label><button class="btn pri" id="pin-go">변경</button></div>');
    $('#pin-go').onclick = async () => { try { await post('/api/me/pin', { oldPin: $('#op').value, newPin: $('#np').value }); toast('PIN을 변경했습니다'); drawTab(); } catch (e) { toast(e.message); } };
  }

  async function drawTab() {
    const el = $('#tab');
    el.innerHTML = '<div class="empty">불러오는 중...</div>';
    await loadState();
    const fn = { lesson: tabLesson, notice: tabNotice, plan: tabPlan, class: tabClassAll, emergency: tabEmergency, settings: tabSettings }[tab];
    try { await fn(el); } catch (e) { el.innerHTML = card(ic('alert') + '오류', esc(e.message)); }
  }

  // composite tabs: several small sections on one page
  async function sections(el, fns) {
    el.innerHTML = fns.map((_, i) => '<div id="sec' + i + '"></div>').join('');
    for (let i = 0; i < fns.length; i++) await fns[i](document.getElementById('sec' + i));
  }
  const tabPlan = (el) => sections(el, [tabHomework, tabOverrides, tabSchedule]);
  const tabClassAll = (el) => sections(el, [tabAttendance, tabClass]);
  const tabSettings = (el) => sections(el, [tabSchool, tabUsers, tabDirectory, tabDevices]);

  // generic collection helpers
  async function saveItem(coll, item) { return post('/api/c/' + coll, item); }
  async function deleteItem(coll, id) { if (!confirm('삭제할까요?')) return false; await del('/api/c/' + coll + '/' + id); return true; }
  function bindDeletes(el, coll) {
    C.$$('[data-del]', el).forEach((b) => { b.onclick = async () => { try { if (await deleteItem(coll, b.dataset.del)) { toast('삭제했습니다'); drawTab(); } } catch (e) { toast(e.message); } }; });
  }

  // ---- 오늘의 수업
  async function tabLesson(el) {
    const cls = myDefaultCls();
    const materials = ((state && state.files) || []).filter((f) => f.kind === 'material');
    const mine = ((state && state.lessons) || []).filter((l) => l.date >= C.addDays(C.today(), -1)).sort((a, b) => (a.date + a.period < b.date + b.period ? -1 : 1));
    const nowMap = (state && state.lessonNow) || {};
    el.innerHTML = card(ic('book') + '오늘의 수업 화면 만들기', '<div class="form">' +
      '<label>학급<select id="ls-cls">' + clsOptions(cls, true) + '</select></label>' +
      '<div class="grid2"><label>날짜<input id="ls-date" type="date" value="' + C.today() + '"></label><label>교시<input id="ls-p" type="number" min="1" max="9" placeholder="예: 3"></label></div>' +
      '<label>과목<input id="ls-subj" placeholder="비우면 시간표 과목"></label>' +
      '<label>학습 목표<textarea id="ls-goal" placeholder="예: 화학 반응의 종류를 이해한다."></textarea></label>' +
      '<label>준비물<input id="ls-sup" placeholder="예: 교과서, 실험복"></label>' +
      '<label>수업 자료 (올린 파일 중 선택)<select id="ls-files" multiple size="' + Math.min(6, Math.max(2, materials.length)) + '">' + materials.map((f) => '<option value="' + f.id + '">' + esc(f.name) + '</option>').join('') + '</select></label>' +
      '<label>새 자료 올리기 (PDF, PPT, 한글, 이미지 등)<input type="file" id="ls-up" multiple></label><div class="bar-g"><i id="ls-prog" style="width:0"></i></div>' +
      '<label>링크 (한 줄에 하나: 제목 | 주소)<textarea id="ls-links" placeholder="수업 영상 | https://..."></textarea></label>' +
      '<label>수업 타이머 (분, 선택)<input id="ls-timer" type="number" min="0" max="120"></label>' +
      '<label>안내 문구<input id="ls-note"></label>' +
      '<div class="row"><button class="btn pri" id="ls-save">' + ic('check') + '저장 (해당 교시에 자동 표시)</button><button class="btn" id="ls-now">' + ic('monitor') + '저장하고 지금 칠판에 띄우기</button></div></div>') +
      card(ic('list') + '등록된 수업 화면', mine.length ? mine.map((l) => '<div class="item"><div class="grow"><b>' + esc(classLabel(l.cls)) + ' · ' + C.shortDate(l.date) + ' ' + l.period + '교시 ' + esc(l.subject || '') + '</b><span class="sub">' + esc(l.goal || '') + '</span>' +
        (nowMap[l.cls] && nowMap[l.cls].id === l.id ? '<span class="chip green">지금 표시 중</span>' : '') + '</div><button class="btn sm" data-show="' + l.id + '|' + l.cls + '">' + ic('monitor') + '띄우기</button><button class="btn sm" data-del="' + l.id + '">' + ic('trash') + '</button></div>').join('') : '<div class="empty">없음</div>');
    bindDeletes(el, 'lessons');
    $('#ls-up').onchange = () => {
      const files = Array.from($('#ls-up').files);
      const clsNow = val('ls-cls');
      let i = 0;
      const next = () => {
        if (i >= files.length) { toast('자료를 올렸습니다'); return; }
        const f = files[i++];
        uploadWithProgress('/api/files?kind=material&cls=' + encodeURIComponent(clsNow), f, $('#ls-prog'), (r) => {
          state.files = (state.files || []).concat([r]);
          $('#ls-files').insertAdjacentHTML('beforeend', '<option value="' + r.id + '" selected>' + esc(r.name) + '</option>');
          next();
        });
      };
      next();
    };
    const collect = () => {
      const cls2 = val('ls-cls');
      if (!cls2) throw new Error('학급을 선택하세요');
      const links = val('ls-links').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => { const p = l.split('|'); return p.length > 1 ? { title: p[0].trim(), url: p.slice(1).join('|').trim() } : { title: '', url: l }; });
      return { cls: cls2, date: val('ls-date'), period: Number(val('ls-p')), subject: val('ls-subj'), goal: val('ls-goal'), supplies: val('ls-sup'),
        files: Array.from($('#ls-files').selectedOptions).map((o) => o.value), links, timerMin: Number(val('ls-timer')) || 0, note: val('ls-note') };
    };
    $('#ls-save').onclick = async () => { try { const it = collect(); await saveItem('lessons', it); rememberCls(it.cls); toast('저장했습니다'); drawTab(); } catch (e) { toast(e.message); } };
    $('#ls-now').onclick = async () => {
      try { const it = collect(); const r = await saveItem('lessons', it); await post('/api/class/' + it.cls + '/lesson-now', { lessonId: r.id }); toast('전자칠판에 띄웠습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
    C.$$('[data-show]', el).forEach((b) => { b.onclick = async () => { const p = b.dataset.show.split('|'); try { await post('/api/class/' + p[1] + '/lesson-now', { lessonId: p[0] }); toast('전자칠판에 띄웠습니다'); drawTab(); } catch (e) { toast(e.message); } }; });
  }

  // ---- 공지
  async function tabNotice(el) {
    const list = ((state && state.notices) || []).slice().reverse();
    el.innerHTML = card(ic('megaphone') + '공지 작성', '<div class="form"><label>제목<input id="nt-title"></label><label>내용<textarea id="nt-body"></textarea></label>' +
      '<div class="grid2"><label>종류<select id="nt-level"><option value="normal">일반 공지</option><option value="urgent">긴급 공지 (태풍, 조기 하교, 급식 변경)</option><option value="class">학급 공지 (수행평가, 준비물, 숙제)</option></select></label>' +
      '<label>대상<select id="nt-scope"><option value="school">학교 전체</option><option value="grade">학년</option><option value="class">반</option></select></label></div>' +
      '<div id="nt-target"></div><label>게시 종료일 (선택)<input id="nt-exp" type="date"></label>' +
      '<label class="chk"><input type="checkbox" id="nt-pin">상단 고정</label><label class="chk"><input type="checkbox" id="nt-read">학생 읽음 확인 받기</label>' +
      '<button class="btn pri" id="nt-save">' + ic('send') + '게시</button></div>') +
      card(ic('list') + '게시된 공지', list.length ? list.map((n) => {
        const rc = n.readCounts || {};
        const reads = Object.keys(rc).map((c) => classLabel(c) + ' 읽음 ' + rc[c] + '명 / 미확인 ' + Math.max(0, ((state.classes[c] || {}).rosterSize || 0) - rc[c]) + '명').join('\n');
        return '<div class="item"><div class="grow"><b>' + (n.pinned ? ic('pinned') : '') + esc(n.title) + '</b><span class="sub">' + esc(C.LEVEL[n.level] || '일반') + ' · ' + esc(n.scope === 'class' ? classLabel(n.target) : n.scope === 'grade' ? n.target + '학년' : '전체') + ' · ' + C.dateTime(n.createdAt) + (reads ? '\n' + esc(reads) : '') + '</span></div>' +
          '<button class="btn sm" data-pin="' + n.id + '">' + (n.pinned ? '고정 해제' : '고정') + '</button><button class="btn sm" data-del="' + n.id + '">' + ic('trash') + '</button></div>';
      }).join('') : '<div class="empty">없음</div>');
    const tgt = () => {
      const s = val('nt-scope');
      $('#nt-target').innerHTML = s === 'grade' ? '<label>학년<input id="nt-grade" type="number" min="1" max="6"></label>' : s === 'class' ? '<label>반<select id="nt-cls">' + clsOptions(myDefaultCls(), true) + '</select></label>' : '';
    };
    $('#nt-scope').onchange = tgt;
    tgt();
    bindDeletes(el, 'notices');
    $('#nt-save').onclick = async () => {
      const scope = val('nt-scope');
      try {
        await saveItem('notices', { title: val('nt-title'), body: val('nt-body'), level: val('nt-level'), scope, target: scope === 'grade' ? val('nt-grade') : scope === 'class' ? val('nt-cls') : '',
          pinned: val('nt-pin'), needRead: val('nt-read'), expires: val('nt-exp') });
        toast('게시했습니다'); drawTab();
      } catch (e) { toast(e.message); }
    };
    C.$$('[data-pin]', el).forEach((b) => { b.onclick = async () => { const n = state.notices.find((x) => x.id === b.dataset.pin); try { await saveItem('notices', { id: n.id, title: n.title, scope: n.scope, target: n.target, pinned: !n.pinned }); drawTab(); } catch (e) { toast(e.message); } }; });
  }

  // ---- 준비물 / 과제
  async function tabHomework(el) {
    const list = ((state && state.homework) || []).filter((h) => h.date >= C.addDays(C.today(), -3)).sort((a, b) => (a.date < b.date ? -1 : 1));
    el.innerHTML = card(ic('backpack') + '준비물 · 과제 입력', '<div class="form"><label>학급<select id="hw-cls">' + clsOptions(myDefaultCls(), true) + '</select></label>' +
      '<div class="grid2"><label>종류<select id="hw-kind"><option value="prep">준비물 (해당 날짜에 가져올 것)</option><option value="task">과제 (마감일)</option></select></label><label>날짜<input id="hw-date" type="date" value="' + C.addDays(C.today(), 1) + '"></label></div>' +
      '<label>내용<input id="hw-text" placeholder="예: 체육복, 줄넘기 / 수학 72~75쪽"></label><button class="btn pri" id="hw-save">' + ic('plus') + '추가</button></div>') +
      card(ic('list') + '등록된 항목', list.length ? list.map((h) => '<div class="item"><div class="grow"><b>' + esc(h.text) + '</b><span class="sub">' + esc(classLabel(h.cls)) + ' · ' + (h.kind === 'prep' ? '준비물' : '과제') + ' · ' + C.dateLabel(h.date) + '</span></div><button class="btn sm" data-del="' + h.id + '">' + ic('trash') + '</button></div>').join('') : '<div class="empty">없음</div>');
    bindDeletes(el, 'homework');
    $('#hw-save').onclick = async () => { try { await saveItem('homework', { cls: val('hw-cls'), kind: val('hw-kind'), date: val('hw-date'), text: val('hw-text') }); rememberCls(val('hw-cls')); toast('추가했습니다'); drawTab(); } catch (e) { toast(e.message); } };
  }

  // ---- 출석
  async function tabAttendance(el) {
    const cls = curCls || myDefaultCls();
    const date = tabAttendance.date || C.today();
    el.innerHTML = card(ic('users') + '출석부', '<div class="grid2"><label class="form">학급<select id="at-cls">' + clsOptions(cls, true) + '</select></label><label class="form">날짜<input id="at-date" type="date" value="' + date + '"></label></div><div id="at-list" style="margin-top:.8rem"></div>');
    $('#at-cls').onchange = () => { curCls = val('at-cls'); drawTab(); };
    curCls = cls;
    $('#at-date').onchange = () => { tabAttendance.date = val('at-date'); drawTab(); };
    if (!cls) { $('#at-list').innerHTML = '<div class="empty">학급을 선택하세요</div>'; return; }
    const pv = await get('/api/class/' + cls + '/private');
    const day = (pv.attendance && pv.attendance[date]) || {};
    if (!pv.roster.length) { $('#at-list').innerHTML = '<div class="empty">학생 명단이 없습니다. "학급 관리" 탭에서 먼저 등록하세요.</div>'; return; }
    const counts = { present: 0, absent: 0, late: 0, early: 0 };
    Object.keys(day).forEach((k) => { counts[day[k].status] = (counts[day[k].status] || 0) + 1; });
    $('#at-list').innerHTML = '<div class="row" style="margin-bottom:.6rem"><span class="chip green">출석 ' + counts.present + '</span><span class="chip red">결석 ' + counts.absent + '</span><span class="chip orange">지각 ' + counts.late + '</span><span class="chip violet">조퇴 ' + counts.early + '</span><span class="chip">미확인 ' + (pv.roster.length - Object.keys(day).length) + '</span>' +
      '<button class="btn sm" id="at-all">나머지 모두 출석</button></div>' +
      pv.roster.map((s) => { const r = day[s.no] || {}; return '<div class="att-row"><span class="no">' + s.no + '</span><div><b>' + esc(s.name || '') + '</b>' + (r.note ? '<span class="dim"> ' + esc(r.note) + '</span>' : '') + '<div class="st">' + ['present', 'absent', 'late', 'early'].map((k) => '<button class="' + k + (r.status === k ? ' on' : '') + '" data-no="' + s.no + '" data-st="' + k + '">' + C.ATT[k] + '</button>').join('') + '</div></div></div>'; }).join('');
    C.$$('[data-st]', el).forEach((b) => {
      b.onclick = async () => {
        const cur = day[b.dataset.no] && day[b.dataset.no].status;
        const st = cur === b.dataset.st ? '' : b.dataset.st;
        try { await post('/api/class/' + cls + '/attendance', { date, no: Number(b.dataset.no), status: st }); drawTab(); } catch (e) { toast(e.message); }
      };
    });
    $('#at-all').onclick = async () => {
      const items = pv.roster.filter((s) => !day[s.no]).map((s) => ({ no: s.no, status: 'present' }));
      try { await post('/api/class/' + cls + '/attendance', { date, items }); drawTab(); } catch (e) { toast(e.message); }
    };
  }

  // ---- 학급 관리
  async function tabClass(el) {
    const cls = curCls || myDefaultCls();
    el.innerHTML = '<div id="cl-body"></div>';
    if (!cls) return;
    const pv = await get('/api/class/' + cls + '/private');
    const os = pv.officerSettings;
    const body = $('#cl-body');
    body.innerHTML =
      card(ic('users') + '학생 명단', '<p class="dim">한 줄에 한 명씩 "번호 이름" 형식으로 입력하세요. 이름은 선택입니다.</p><textarea id="cl-roster" style="min-height:10rem">' + esc(pv.roster.map((s) => s.no + ' ' + (s.name || '')).join('\n')) + '</textarea><button class="btn pri" id="cl-roster-save" style="margin-top:.6rem">' + ic('check') + '명단 저장</button>') +
      card(ic('user') + '담임 · 교실', '<div class="form"><label>담임 선생님<input id="cl-home" value="' + esc(pv.homeroom || '') + '"></label><label>교실 위치<input id="cl-room" value="' + esc(pv.room || '') + '" placeholder="예: 본관 3층 302호"></label>' +
        '<label>교과 담당 (한 줄에 "과목 | 선생님 | 교실")<textarea id="cl-subj">' + esc(Object.keys(pv.subjectInfo || {}).map((k) => k + ' | ' + (pv.subjectInfo[k].teacher || '') + ' | ' + (pv.subjectInfo[k].room || '')).join('\n')) + '</textarea></label>' +
        '<p class="dim">컴시간에서 선생님 이름이 가려져 표시되는 경우 여기서 정한 이름과 교실이 전자칠판에 쓰입니다.</p><button class="btn pri" id="cl-info-save">' + ic('check') + '저장</button></div>') +
      card(ic('bell') + '임원 (회장 · 부회장)', '<div id="cl-off">' + pv.officers.map((o) => '<div class="grid3" style="margin-bottom:.5rem" data-oid="' + o.id + '"><input class="o-name" value="' + esc(o.name) + '"><select class="o-title"><option' + (o.title === '회장' ? ' selected' : '') + '>회장</option><option' + (o.title === '부회장' ? ' selected' : '') + '>부회장</option></select><input class="o-pin" placeholder="새 PIN (변경 시)" inputmode="numeric"></div>' +
        (o.blockedUntil > Date.now() ? '<p class="dim">' + esc(o.name) + ': ' + new Date(o.blockedUntil).toLocaleTimeString('ko-KR') + '까지 제한 중</p>' : '')).join('') + '</div>' +
        '<button class="btn sm" id="cl-off-add">' + ic('plus') + '임원 추가</button> <button class="btn pri" id="cl-off-save">' + ic('check') + '임원 저장</button><p class="dim" style="margin-top:.5rem">이름을 비우고 저장하면 삭제됩니다.</p>') +
      card(ic('sliders') + '임원 버튼 설정', '<div class="form"><label class="chk"><input type="checkbox" id="os-en"' + (os.enabled ? ' checked' : '') + '>임원 버튼 사용</label><label class="chk"><input type="checkbox" id="os-snd"' + (os.sound ? ' checked' : '') + '>알림음 재생</label>' +
        '<div class="grid3"><label>표시 시간(초)<input id="os-dur" type="number" min="3" max="30" value="' + os.durationSec + '"></label><label>쿨다운(초)<input id="os-cool" type="number" min="5" max="600" value="' + os.cooldownSec + '"></label><label>10분 최대 횟수<input id="os-max" type="number" min="1" max="50" value="' + os.maxPer10min + '"></label></div>' +
        '<div class="row"><button class="btn pri" id="os-save">' + ic('check') + '설정 저장</button><button class="btn" id="os-unblock">제한 해제</button></div></div>') +
      card(ic('eye') + '임원 버튼 기록 (교사만 확인)', pv.officerLog.length ? pv.officerLog.slice(0, 40).map((a) => '<div class="item"><div class="grow"><b>' + esc(C.officerType(a.type).label) + '</b><span class="sub">' + esc(a.name) + ' (' + esc(a.title || '') + ') · ' + C.dateTime(a.at) + '</span></div></div>').join('') : '<div class="empty">기록 없음</div>');
    $('#cl-roster-save').onclick = async () => {
      const students = val('cl-roster').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => { const m = /^(\d+)\s*(.*)$/.exec(l); return m ? { no: Number(m[1]), name: m[2].trim() } : null; }).filter(Boolean);
      try { await post('/api/class/' + cls + '/roster', { students }); toast(students.length + '명 저장'); } catch (e) { toast(e.message); }
    };
    $('#cl-info-save').onclick = async () => {
      const subjectInfo = {};
      val('cl-subj').split('\n').forEach((l) => { const p = l.split('|').map((x) => x.trim()); if (p[0]) subjectInfo[p[0]] = { teacher: p[1] || '', room: p[2] || '' }; });
      try { await post('/api/class/' + cls + '/info', { homeroom: val('cl-home'), room: val('cl-room'), subjectInfo }); toast('저장했습니다'); } catch (e) { toast(e.message); }
    };
    $('#cl-off-add').onclick = () => { $('#cl-off').insertAdjacentHTML('beforeend', '<div class="grid3" style="margin-bottom:.5rem" data-oid=""><input class="o-name" placeholder="이름"><select class="o-title"><option>회장</option><option>부회장</option></select><input class="o-pin" placeholder="PIN (4자리 이상)" inputmode="numeric"></div>'); };
    $('#cl-off-save').onclick = async () => {
      const officers = C.$$('#cl-off [data-oid]').map((r) => ({ id: r.dataset.oid, name: r.querySelector('.o-name').value.trim(), title: r.querySelector('.o-title').value, pin: r.querySelector('.o-pin').value.trim() }));
      try { await post('/api/class/' + cls + '/officers', { officers }); toast('임원을 저장했습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
    $('#os-save').onclick = async () => {
      try {
        await post('/api/class/' + cls + '/officer-settings', { enabled: val('os-en'), sound: val('os-snd'), durationSec: Number(val('os-dur')), cooldownSec: Number(val('os-cool')), maxPer10min: Number(val('os-max')) });
        toast('저장했습니다');
      } catch (e) { toast(e.message); }
    };
    $('#os-unblock').onclick = async () => { try { await post('/api/class/' + cls + '/officer-settings', { clearBlocks: true }); toast('제한을 해제했습니다'); drawTab(); } catch (e) { toast(e.message); } };
  }

  // ---- 호출 (선생님 오세요)
  let callTimer = null;
  let seenCalls = {};
  function startCallWatch() {
    if (callTimer) return;
    const check = async () => {
      if (!me || (me.role !== 'teacher' && me.role !== 'admin')) return;
      try {
        const r = await get('/api/teacher/calls');
        const open = r.calls.filter((c) => !c.acked);
        const fresh = open.filter((c) => !seenCalls[c.id]);
        open.forEach((c) => { seenCalls[c.id] = true; });
        const b = $('#call-banner');
        if (open.length) {
          const c = open[0];
          b.innerHTML = ic('bell') + '<div class="grow">' + esc(classLabel(c.cls)) + '에서 선생님을 호출했습니다 · ' + C.ago(c.at) + '</div><button class="btn sm" id="call-ack">확인</button>';
          b.classList.add('on');
          $('#call-ack').onclick = async () => { await post('/api/teacher/calls/ack', { id: c.id }); b.classList.remove('on'); check(); };
          if (fresh.length) { beep(); if (navigator.vibrate) navigator.vibrate([300, 150, 300]); }
        } else b.classList.remove('on');
      } catch (e) { /* offline */ }
    };
    check();
    callTimer = setInterval(check, 5000);
  }
  function beep() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      [0, 0.3].forEach((t, i) => {
        const o = ctx.createOscillator(), g = ctx.createGain();
        o.frequency.value = i ? 784 : 1046;
        g.gain.setValueAtTime(0.0001, ctx.currentTime + t);
        g.gain.exponentialRampToValueAtTime(0.4, ctx.currentTime + t + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + t + 0.28);
        o.connect(g); g.connect(ctx.destination); o.start(ctx.currentTime + t); o.stop(ctx.currentTime + t + 0.3);
      });
    } catch (e) { /* audio unavailable */ }
  }


  // ---- 보강 · 대체 수업
  async function tabOverrides(el) {
    const ov = ((state && state.overrides) || []).filter((o) => o.date >= C.today()).sort((a, b) => (a.date + a.period < b.date + b.period ? -1 : 1));
    el.innerHTML = card(ic('edit') + '보강 · 대체 수업 · 교실 변경', '<div class="form"><label>학급<select id="ov-cls">' + clsOptions(myDefaultCls(), true) + '</select></label>' +
      '<div class="grid2"><label>날짜<input id="ov-date" type="date" value="' + C.today() + '"></label><label>교시<input id="ov-p" type="number" min="1" max="9"></label></div>' +
      '<div class="grid2"><label>종류<select id="ov-kind"><option>보강</option><option>대체</option><option>교실변경</option><option>휴강</option></select></label><label>과목<input id="ov-subj"></label></div>' +
      '<div class="grid2"><label>선생님<input id="ov-t"></label><label>교실<input id="ov-room"></label></div>' +
      '<button class="btn pri" id="ov-save">' + ic('check') + '적용 (전자칠판에 즉시 반영)</button></div>' +
      (ov.length ? ov.map((o) => '<div class="item"><div class="grow"><b>' + esc(classLabel(o.cls)) + ' · ' + C.shortDate(o.date) + ' ' + o.period + '교시 · ' + esc(o.kind) + '</b><span class="sub">' + esc([o.subject, o.teacher, o.room].filter(Boolean).join(' · ')) + '</span></div><button class="btn sm" data-del="' + o.id + '">' + ic('trash') + '</button></div>').join('') : ''));
    bindDeletes(el, 'overrides');
    $('#ov-save').onclick = async () => {
      try { await saveItem('overrides', { cls: val('ov-cls'), date: val('ov-date'), period: Number(val('ov-p')), kind: val('ov-kind'), subject: val('ov-subj'), teacher: val('ov-t'), room: val('ov-room') }); toast('적용했습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
  }

  // ---- 일정 · 시험
  async function tabSchedule(el) {
    const ev = ((state && state.events) || []).slice().sort((a, b) => (a.date < b.date ? -1 : 1));
    const ex = ((state && state.exams) || []).slice().sort((a, b) => (a.date + (a.period || '') < b.date + (b.period || '') ? -1 : 1));
    el.innerHTML = card(ic('exam') + '시험 정보', '<div class="form"><div class="grid2"><label>시험 이름<input id="ex-name" placeholder="예: 2학기 중간고사"></label><label>학년<input id="ex-g" type="number" min="1" max="6"></label></div>' +
      '<div class="grid3"><label>날짜<input id="ex-date" type="date"></label><label>교시<input id="ex-p" type="number" min="1" max="9"></label><label>과목<input id="ex-subj"></label></div>' +
      '<label>시험 범위<textarea id="ex-range" style="min-height:4rem"></textarea></label><label>준비물<input id="ex-sup" placeholder="예: 컴퓨터용 사인펜, 계산기"></label><button class="btn pri" id="ex-save">' + ic('plus') + '추가</button></div>' +
      '<div style="margin-top:.8rem">' + (ex.length ? ex.map((e) => '<div class="item"><div class="grow"><b>' + esc((e.name ? e.name + ' · ' : '') + e.subject) + ' ' + C.dday(e.date) + '</b><span class="sub">' + (e.grade ? e.grade + '학년 · ' : '') + C.dateLabel(e.date) + (e.period ? ' ' + e.period + '교시' : '') + (e.range ? '\n범위: ' + esc(e.range) : '') + (e.supplies ? '\n준비물: ' + esc(e.supplies) : '') + '</span></div><button class="btn sm" data-del-ex="' + e.id + '">' + ic('trash') + '</button></div>').join('') : '<div class="empty">없음</div>') + '</div>') +
      card(ic('calendar') + '학사 일정 추가', '<p class="dim">NEIS 학사일정은 자동으로 표시됩니다. 수행평가, 학교 행사 등 추가 일정을 입력하세요.</p><div class="form"><label>제목<input id="ev-title"></label>' +
        '<div class="grid2"><label>시작일<input id="ev-date" type="date"></label><label>종료일 (선택)<input id="ev-end" type="date"></label></div>' +
        '<div class="grid2"><label>종류<select id="ev-kind"><option>수행평가</option><option>시험</option><option>행사</option><option>방학</option><option>휴업일</option><option>입학</option><option>졸업</option></select></label><label>대상 학년 (쉼표, 비우면 전체)<input id="ev-grades" placeholder="예: 1,2"></label></div>' +
        '<button class="btn pri" id="ev-save">' + ic('plus') + '추가</button></div><div style="margin-top:.8rem">' +
        (ev.length ? ev.map((e) => '<div class="item"><div class="grow"><b>' + esc(e.title) + '</b><span class="sub">' + esc(e.kind || '') + ' · ' + C.dateLabel(e.date) + (e.endDate ? ' ~ ' + C.dateLabel(e.endDate) : '') + '</span></div><button class="btn sm" data-del="' + e.id + '">' + ic('trash') + '</button></div>').join('') : '<div class="empty">없음</div>') + '</div>');
    bindDeletes(el, 'events');
    C.$$('[data-del-ex]', el).forEach((b) => { b.onclick = async () => { if (await deleteItem('exams', b.dataset.delEx)) drawTab(); }; });
    $('#ex-save').onclick = async () => { try { await saveItem('exams', { name: val('ex-name'), grade: Number(val('ex-g')) || 0, date: val('ex-date'), period: Number(val('ex-p')) || 0, subject: val('ex-subj'), range: val('ex-range'), supplies: val('ex-sup') }); toast('추가했습니다'); drawTab(); } catch (e) { toast(e.message); } };
    $('#ev-save').onclick = async () => {
      try { await saveItem('events', { title: val('ev-title'), date: val('ev-date'), endDate: val('ev-end'), kind: val('ev-kind'), grades: val('ev-grades').split(',').map((x) => Number(x.trim())).filter(Boolean) }); toast('추가했습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
  }

  // ---- 긴급 알림 (관리자)
  async function tabEmergency(el) {
    let devices = {};
    try { devices = (await get('/api/admin/devices')).devices; } catch (e) { devices = {}; }
    const alerts = ((state && state.alerts) || []).slice().reverse();
    const devCount = Object.keys(devices).length;
    el.innerHTML = card(ic('alert') + '긴급 안내 보내기', '<p class="dim">모든 전자칠판에 즉시 전체 화면으로 표시되며, 각 교실에서 "확인"을 누를 때까지 사라지지 않습니다. 인터넷이 끊겨도 교내 네트워크로 전달됩니다.</p><div class="form">' +
      '<label>제목<input id="em-title" value="긴급 안내"></label><label>내용<textarea id="em-text" placeholder="예: 우천으로 인해 6교시 체육수업이 변경되었습니다."></textarea></label>' +
      '<label>대상<select id="em-target"><option value="all">전체</option>' + [1, 2, 3, 4, 5, 6].map((g) => '<option value="' + g + '">' + g + '학년</option>').join('') + '</select></label>' +
      '<button class="btn red" id="em-send">' + ic('send') + '모든 전자칠판에 보내기</button></div>') +
      card(ic('list') + '보낸 안내', alerts.length ? alerts.map((a) => {
        const acks = a.acks || {};
        const ackN = Object.keys(acks).length;
        return '<div class="item"><div class="grow"><b>' + esc(a.title) + (a.active ? ' <span class="chip red">표시 중</span>' : ' <span class="chip">종료</span>') + '</b><span class="sub">' + esc(a.text) + '\n' + C.dateTime(a.at) + ' · ' + esc(a.author) + ' · 확인 ' + ackN + '/' + devCount + '대' +
          (ackN ? '\n' + Object.keys(acks).map((k) => esc(acks[k].name || k) + ' ' + C.dateTime(acks[k].at)).join(', ') : '') + '</span></div>' + (a.active ? '<button class="btn sm" data-close="' + a.id + '">모두 해제</button>' : '') + '</div>';
      }).join('') : '<div class="empty">없음</div>');
    $('#em-send').onclick = async () => {
      if (!confirm('모든 대상 전자칠판에 긴급 안내를 보낼까요?')) return;
      try { await post('/api/admin/emergency', { title: val('em-title'), text: val('em-text'), target: val('em-target') }); toast('보냈습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
    C.$$('[data-close]', el).forEach((b) => { b.onclick = async () => { await post('/api/admin/emergency/close', { id: b.dataset.close }); drawTab(); }; });
  }

  // ---- 학교 설정 (관리자)
  async function tabSchool(el) {
    const cfg = (state && state.config) || {};
    const bell = cfg.bell || [];
    const feeds = cfg.feeds || [];
    el.innerHTML = card(ic('school') + '연결된 학교', '<div class="item"><div class="grow"><b>NEIS: ' + esc(cfg.school ? cfg.school.name + ' (' + cfg.school.atptName + ')' : '미설정') + '</b><span class="sub">급식 · 학사일정' + (cfg.hasNeisKey ? ' · 인증키 사용' : ' · 인증키 없음(샘플 한도 적용)') + '</span></div></div>' +
      '<div class="item"><div class="grow"><b>컴시간: ' + esc(cfg.comci ? cfg.comci.name + ' (' + cfg.comci.region + ', ' + cfg.comci.code + ')' : '미설정') + '</b><span class="sub">시간표 · 변경 사항 · 교시 시작 시각</span></div></div>' +
      '<div class="form" style="margin-top:.6rem"><div class="row"><input id="sc-q" placeholder="학교 이름" style="flex:1"><button class="btn pri" id="sc-go">' + ic('search') + '검색</button></div><div id="sc-res"></div></div>') +
      card(ic('clock') + '교시 시간', '<div class="form"><label>수업 시간(분)<input id="pm" type="number" min="30" max="120" value="' + esc(cfg.periodMinutes || '') + '" placeholder="비우면 학교급 기준 (초 40, 중 45, 고 50)"></label>' +
        '<label>시종 시각 직접 입력 (선택, 한 줄에 "08:40-09:25", 1교시부터)<textarea id="bell">' + esc(bell.map((b) => b.start + '-' + b.end).join('\n')) + '</textarea></label><p class="dim">비우면 컴시간 교시 시작 시각 + 수업 시간으로 계산합니다.</p>' +
        '<label>표시 이름 (선택)<input id="dn" value="' + esc(cfg.displayName || '') + '"></label>' +
        '<label>NEIS 인증키 (선택, open.neis.go.kr)<input id="nk" placeholder="' + (cfg.hasNeisKey ? '설정됨 - 바꿀 때만 입력' : '') + '"></label><button class="btn pri" id="gen-save">' + ic('check') + '저장</button></div>') +
      card(ic('pin') + '날씨 위치', '<div class="form"><div class="grid2"><label>위도<input id="lat" value="' + esc(cfg.lat == null ? '' : cfg.lat) + '"></label><label>경도<input id="lon" value="' + esc(cfg.lon == null ? '' : cfg.lon) + '"></label></div><label>표시 이름<input id="ln" value="' + esc(cfg.locationName || '') + '"></label>' +
        '<div class="row"><input id="geo-q" placeholder="주소 또는 학교 이름으로 찾기" style="flex:1"><button class="btn" id="geo-go">' + ic('search') + '찾기</button></div><div id="geo-res"></div><button class="btn pri" id="geo-save">' + ic('check') + '위치 저장</button></div>') +
      card(ic('file') + '학교 홈페이지 (공지사항 · 가정통신문)', '<p class="dim">서울 학교 홈페이지(sen.ms.kr, sen.hs.kr, sen.es.kr 등)의 공지사항과 가정통신문 게시판을 자동으로 찾아 전자칠판에 표시합니다. 비우면 NEIS에 등록된 홈페이지 주소를 사용합니다.</p>' +
        '<div class="form"><label>홈페이지 주소<input id="hp-url" value="' + esc(cfg.homepageUrl || '') + '" placeholder="' + esc((cfg.school && cfg.school.homepage) || '예: joongdong.sen.ms.kr') + '"></label><button class="btn pri" id="hp-save">' + ic('check') + '저장</button></div>') +
      card(ic('link') + '학교 홈페이지 공지 (RSS)', '<p class="dim">학교 홈페이지 게시판의 RSS 주소를 등록하면 전자칠판 공지에 함께 표시됩니다. 한 줄에 "이름 | RSS 주소".</p><textarea id="feeds">' + esc(feeds.map((f) => f.name + ' | ' + f.url).join('\n')) + '</textarea><button class="btn pri" id="feeds-save" style="margin-top:.6rem">' + ic('check') + '저장</button>');
    let pick = { school: null, comci: null };
    $('#sc-go').onclick = async () => {
      const q = val('sc-q');
      try {
        const [n, c] = await Promise.all([get('/api/admin/neis-search?q=' + encodeURIComponent(q)), get('/api/admin/comci-search?q=' + encodeURIComponent(q.replace(/등학교$|학교$/, ''))).catch(() => ({ items: [] }))]);
        $('#sc-res').innerHTML = '<p class="dim" style="margin-top:.6rem">NEIS</p>' + n.items.map((s, i) => '<div class="item"><div class="grow"><b>' + esc(s.name) + '</b><span class="sub">' + esc(s.atptName + ' · ' + s.address) + '</span></div><button class="btn sm" data-n="' + i + '">선택</button></div>').join('') +
          '<p class="dim" style="margin-top:.6rem">컴시간</p>' + c.items.map((s, i) => '<div class="item"><div class="grow"><b>' + esc(s.name) + '</b><span class="sub">' + esc(s.region) + ' · ' + s.code + '</span></div><button class="btn sm" data-c="' + i + '">선택</button></div>').join('') +
          '<button class="btn pri" id="sc-save" style="margin-top:.6rem">' + ic('check') + '선택한 학교로 저장</button>';
        C.$$('[data-n]').forEach((b) => { b.onclick = () => { pick.school = n.items[Number(b.dataset.n)]; C.$$('[data-n]').forEach((x) => x.classList.toggle('pri', x === b)); }; });
        C.$$('[data-c]').forEach((b) => { b.onclick = () => { pick.comci = c.items[Number(b.dataset.c)]; C.$$('[data-c]').forEach((x) => x.classList.toggle('pri', x === b)); }; });
        $('#sc-save').onclick = async () => {
          const body = {};
          if (pick.school) body.school = pick.school;
          if (pick.comci) body.comci = pick.comci;
          try { await post('/api/admin/config', body); await C.post('/api/local/refresh', {}).catch(() => {}); toast('저장했습니다. 데이터를 새로 불러옵니다'); drawTab(); } catch (e) { toast(e.message); }
        };
      } catch (e) { toast(e.message); }
    };
    $('#gen-save').onclick = async () => {
      const b = {};
      const lines = val('bell').split('\n').map((l) => l.trim()).filter(Boolean);
      b.bell = lines.map((l) => { const p = l.split('-'); return { start: (p[0] || '').trim(), end: (p[1] || '').trim() }; }).filter((x) => C.parseHm(x.start) >= 0 && C.parseHm(x.end) >= 0);
      b.periodMinutes = Number(val('pm')) || null;
      b.displayName = val('dn') || null;
      if (val('nk')) b.neisKey = val('nk');
      try { await post('/api/admin/config', b); toast('저장했습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
    $('#geo-go').onclick = async () => {
      try {
        const r = await get('/api/admin/geocode?q=' + encodeURIComponent(val('geo-q')));
        $('#geo-res').innerHTML = r.items.map((g, i) => '<div class="item"><div class="grow"><span class="sub">' + esc(g.name) + '</span></div><button class="btn sm" data-g="' + i + '">사용</button></div>').join('') || '<div class="empty">결과 없음</div>';
        C.$$('[data-g]').forEach((b) => { b.onclick = () => { const g = r.items[Number(b.dataset.g)]; $('#lat').value = g.lat; $('#lon').value = g.lon; }; });
      } catch (e) { toast(e.message); }
    };
    $('#geo-save').onclick = async () => {
      const lat = parseFloat(val('lat')), lon = parseFloat(val('lon'));
      try { await post('/api/admin/config', { lat: isNaN(lat) ? null : lat, lon: isNaN(lon) ? null : lon, locationName: val('ln') }); toast('저장했습니다'); } catch (e) { toast(e.message); }
    };
    $('#hp-save').onclick = async () => {
      try { await post('/api/admin/config', { homepageUrl: val('hp-url') || null }); await C.post('/api/local/refresh', {}).catch(() => {}); toast('저장했습니다. 게시판을 불러옵니다'); } catch (e) { toast(e.message); }
    };
    $('#feeds-save').onclick = async () => {
      const f = val('feeds').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => { const p = l.split('|'); return p.length > 1 ? { name: p[0].trim(), url: p.slice(1).join('|').trim() } : { name: '홈페이지', url: l }; });
      try { await post('/api/admin/config', { feeds: f }); toast('저장했습니다'); } catch (e) { toast(e.message); }
    };
  }

  // ---- 계정 (관리자)
  async function tabUsers(el) {
    const r = await get('/api/admin/users');
    const roleName = { admin: '관리자', teacher: '관리자' };
    el.innerHTML = card(ic('user') + '관리자 계정', '<div class="form"><input type="hidden" id="u-id"><div class="grid2"><label>이름<input id="u-name"></label><label>담당 학급 (쉼표, 예: 3-2)<input id="u-cls"></label></div>' +
      '<label>PIN (새 계정 필수, 수정 시 바꿀 때만)<input id="u-pin" type="password" inputmode="numeric"></label>' +
      '<div class="row"><button class="btn pri" id="u-save">' + ic('check') + '저장</button><button class="btn" id="u-new">새로 입력</button></div></div>') +
      card(ic('users') + '계정 목록', r.users.map((u) => '<div class="item"><div class="grow"><b>' + esc(u.name) + '</b><span class="sub">' + roleName[u.role] + (u.classes.length ? ' · ' + u.classes.join(', ') : '') + '</span></div><button class="btn sm" data-edit="' + u.id + '">' + ic('edit') + '</button>' + (u.id !== me.id ? '<button class="btn sm" data-udel="' + u.id + '">' + ic('trash') + '</button>' : '') + '</div>').join(''));
    const fill = (u) => { $('#u-id').value = u ? u.id : ''; $('#u-name').value = u ? u.name : ''; $('#u-cls').value = u ? u.classes.join(', ') : ''; $('#u-pin').value = ''; };
    $('#u-new').onclick = () => fill(null);
    C.$$('[data-edit]', el).forEach((b) => { b.onclick = () => { fill(r.users.find((u) => u.id === b.dataset.edit)); window.scrollTo(0, 0); }; });
    C.$$('[data-udel]', el).forEach((b) => { b.onclick = async () => { if (!confirm('계정을 삭제할까요?')) return; try { await del('/api/admin/users/' + b.dataset.udel); drawTab(); } catch (e) { toast(e.message); } }; });
    $('#u-save').onclick = async () => {
      const classes = val('u-cls').split(',').map((x) => x.trim()).filter((x) => /^\d+-\d+$/.test(x));
      try { await post('/api/admin/users', { id: val('u-id'), name: val('u-name'), role: 'admin', classes, pin: $('#u-pin').value }); toast('저장했습니다'); drawTab(); } catch (e) { toast(e.message); }
    };
  }

  // ---- 연락처 · 특별실 (관리자)
  async function tabDirectory(el) {
    const cts = (state && state.contacts) || [];
    const rooms = (state && state.rooms) || [];
    el.innerHTML = card(ic('phone') + '교내 연락처', '<div class="form"><div class="grid2"><label>이름 · 부서명<input id="ct-name" placeholder="예: 보건실"></label><label>소속<input id="ct-dept" placeholder="예: 행정실, 2학년부"></label></div>' +
      '<div class="grid3"><label>내선<input id="ct-ext"></label><label>전화<input id="ct-phone"></label><label>위치<input id="ct-loc"></label></div><label>메모<input id="ct-note"></label><button class="btn pri" id="ct-save">' + ic('plus') + '추가</button></div>' +
      cts.map((c) => '<div class="item"><div class="grow"><b>' + esc(c.name) + '</b><span class="sub">' + esc([c.dept, c.ext && '내선 ' + c.ext, c.phone, c.location].filter(Boolean).join(' · ')) + '</span></div><button class="btn sm" data-del-ct="' + c.id + '">' + ic('trash') + '</button></div>').join('')) +
      card(ic('door') + '특별실', '<div class="form"><div class="grid3"><label>이름<input id="rm-name" placeholder="예: 과학실"></label><label>위치<input id="rm-loc" placeholder="예: 본관 3층"></label><label>메모<input id="rm-note"></label></div><button class="btn pri" id="rm-save">' + ic('plus') + '추가</button></div>' +
        rooms.map((r) => '<div class="item"><div class="grow"><b>' + esc(r.name) + '</b><span class="sub">' + esc([r.location, r.note].filter(Boolean).join(' · ')) + '</span></div><button class="btn sm" data-del-rm="' + r.id + '">' + ic('trash') + '</button></div>').join(''));
    $('#ct-save').onclick = async () => { try { await saveItem('contacts', { name: val('ct-name'), dept: val('ct-dept'), ext: val('ct-ext'), phone: val('ct-phone'), location: val('ct-loc'), note: val('ct-note') }); drawTab(); } catch (e) { toast(e.message); } };
    $('#rm-save').onclick = async () => { try { await saveItem('rooms', { name: val('rm-name'), location: val('rm-loc'), note: val('rm-note') }); drawTab(); } catch (e) { toast(e.message); } };
    C.$$('[data-del-ct]', el).forEach((b) => { b.onclick = async () => { if (await deleteItem('contacts', b.dataset.delCt)) drawTab(); }; });
    C.$$('[data-del-rm]', el).forEach((b) => { b.onclick = async () => { if (await deleteItem('rooms', b.dataset.delRm)) drawTab(); }; });
  }

  // ---- 기기 (관리자)
  async function tabDevices(el) {
    const r = await get('/api/admin/devices');
    const ids = Object.keys(r.devices);
    el.innerHTML = card(ic('monitor') + '연결된 전자칠판 (' + ids.length + '대)', ids.length ? ids.map((id) => {
      const d = r.devices[id];
      const on = Date.now() - d.lastSeen < 60000;
      return '<div class="item"><div class="grow"><b>' + esc(d.name || id) + ' ' + (on ? '<span class="chip green">연결됨</span>' : '<span class="chip">' + (d.lastSeen ? C.ago(d.lastSeen) : '기록 없음') + '</span>') + '</b><span class="sub">' + esc(d.cls ? classLabel(d.cls) : '교실 미지정') + (d.ip ? ' · ' + esc(d.ip) : '') + '</span></div></div>';
    }).join('') : '<div class="empty">등록된 기기가 없습니다</div>');
  }

  render();
})();
