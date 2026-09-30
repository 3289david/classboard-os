/* Shared helpers for the board UI and the mobile portal. */
(function () {
  const C = {};

  C.esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  };
  C.$ = (sel, root) => (root || document).querySelector(sel);
  C.$$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  // ---------------------------------------------------------------- API
  C.token = function () { try { return localStorage.getItem('cb_token') || ''; } catch (e) { return ''; } };
  C.setToken = function (t) { try { if (t) localStorage.setItem('cb_token', t); else localStorage.removeItem('cb_token'); } catch (e) { /* storage unavailable */ } };

  C.api = async function (method, path, body, opts) {
    const o = opts || {};
    const headers = {};
    const tok = o.token != null ? o.token : C.token();
    if (tok) headers['X-Token'] = tok;
    let payload;
    if (body instanceof Blob) {
      payload = body;
      if (o.filename) headers['X-Filename'] = encodeURIComponent(o.filename);
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch(path, { method, headers, body: payload });
    let data = null;
    const text = await res.text();
    try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { raw: text }; }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('HTTP ' + res.status));
      err.status = res.status;
      throw err;
    }
    return data;
  };
  C.get = (p, o) => C.api('GET', p, undefined, o);
  C.post = (p, b, o) => C.api('POST', p, b || {}, o);
  C.del = (p, o) => C.api('DELETE', p, undefined, o);

  // ---------------------------------------------------------------- time
  C.pad = (n) => (n < 10 ? '0' : '') + n;
  C.iso = function (d) { return d.getFullYear() + '-' + C.pad(d.getMonth() + 1) + '-' + C.pad(d.getDate()); };
  C.today = () => C.iso(new Date());
  C.addDays = function (iso, n) { const d = C.parseIso(iso); d.setDate(d.getDate() + n); return C.iso(d); };
  C.parseIso = function (iso) { const p = iso.split('-').map(Number); return new Date(p[0], p[1] - 1, p[2]); };
  C.hm = function (min) { return C.pad(Math.floor(min / 60)) + ':' + C.pad(min % 60); };
  C.parseHm = function (s) { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || '').trim()); return m ? Number(m[1]) * 60 + Number(m[2]) : -1; };
  C.nowMin = function () { const d = new Date(); return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60; };
  C.DOW = ['일', '월', '화', '수', '목', '금', '토'];
  C.dateLabel = function (iso) { const d = C.parseIso(iso); return (d.getMonth() + 1) + '월 ' + d.getDate() + '일 (' + C.DOW[d.getDay()] + ')'; };
  C.shortDate = function (iso) { const d = C.parseIso(iso); return (d.getMonth() + 1) + '/' + d.getDate() + '(' + C.DOW[d.getDay()] + ')'; };
  C.dday = function (iso) {
    const a = C.parseIso(C.today()), b = C.parseIso(iso);
    const n = Math.round((b - a) / 86400000);
    return n === 0 ? 'D-DAY' : n > 0 ? 'D-' + n : 'D+' + -n;
  };
  C.ago = function (ms) {
    const s = Math.floor((Date.now() - ms) / 1000);
    if (s < 60) return '방금';
    if (s < 3600) return Math.floor(s / 60) + '분 전';
    if (s < 86400) return Math.floor(s / 3600) + '시간 전';
    return Math.floor(s / 86400) + '일 전';
  };
  C.dateTime = function (ms) { const d = new Date(ms); return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + C.pad(d.getHours()) + ':' + C.pad(d.getMinutes()); };
  C.bytes = function (n) {
    if (n == null || n < 0) return '-';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(n >= 100 ? 0 : 1)) + u[i];
  };

  // ---------------------------------------------------------------- bell schedule & timetable
  C.defaultMinutes = function (config) {
    if (config && config.periodMinutes > 0) return config.periodMinutes;
    const kind = (config && config.school && config.school.kind) || '';
    if (kind.indexOf('초') >= 0) return 40;
    if (kind.indexOf('중') >= 0) return 45;
    return 50;
  };
  /** [{p, start, end}] in minutes. Manual bell overrides Comcigan start times. */
  C.slots = function (config, times) {
    const out = [];
    const bell = config && config.bell;
    if (bell && bell.length) {
      bell.forEach((b, i) => {
        const s = C.parseHm(b.start), e = C.parseHm(b.end);
        if (s >= 0 && e > s) out.push({ p: i + 1, start: s, end: e });
      });
      return out;
    }
    const len = C.defaultMinutes(config);
    (times || []).forEach((t, i) => { const s = C.parseHm(t); if (s >= 0) out.push({ p: i + 1, start: s, end: s + len }); });
    return out;
  };

  /** Map of ISO date -> [{p, s, t, room, ch, os, ot, cancel}] for the class. */
  C.classDays = function (timetable) {
    const map = {};
    if (!timetable || !timetable.weeks) return map;
    timetable.weeks.forEach((w) => {
      (w.dates || []).forEach((d, i) => { if (w.days && w.days[i]) map[d] = w.days[i]; });
    });
    return map;
  };

  /** Periods of a date with teacher overrides (보강/대체/교실변경/휴강) and subject info applied. */
  C.periodsFor = function (date, days, state, cls) {
    const base = (days[date] || []).map((e) => Object.assign({}, e));
    const byP = {};
    base.forEach((e) => { byP[e.p] = e; });
    ((state && state.overrides) || []).forEach((o) => {
      if (o.cls !== cls || o.date !== date) return;
      const p = Number(o.period);
      const e = byP[p] || { p };
      if (!byP[p]) { byP[p] = e; base.push(e); }
      if (o.kind === '휴강') { e.cancel = true; e.s = ''; }
      else {
        if (o.subject) { if (!e.ch) { e.os = e.s; e.ot = e.t; } e.s = o.subject; }
        if (o.teacher) e.t = o.teacher;
        e.cancel = false;
      }
      if (o.room) e.room = o.room;
      e.ov = o.kind || '변경';
      e.note = o.note || '';
    });
    const info = (state && state.classes && state.classes[cls] && state.classes[cls].subjectInfo) || {};
    base.forEach((e) => {
      const si = info[e.s];
      if (si) {
        if (!e.t && si.teacher) e.t = si.teacher;
        if (si.teacher && e.t && e.t.indexOf('*') >= 0) e.t = si.teacher;
        if (!e.room && si.room) e.room = si.room;
      }
    });
    base.sort((a, b) => a.p - b.p);
    return base;
  };

  /** Where we are in the school day. */
  C.segment = function (slots, periods, nowMin) {
    // With a known timetable only the day's real periods count; without one, every bell slot does.
    const list = periods && periods.length ? slots.filter((s) => periods.some((e) => e.p === s.p && !e.cancel)) : slots;
    if (!list.length) return { type: 'none' };
    for (let i = 0; i < list.length; i++) {
      const s = list[i];
      if (nowMin >= s.start && nowMin < s.end) return { type: 'class', slot: s, next: list[i + 1] || null, index: i };
      if (nowMin < s.start) {
        const prev = list[i - 1];
        return { type: i === 0 ? 'before' : 'break', slot: s, prev: prev || null, next: s, gap: prev ? s.start - prev.end : null };
      }
    }
    return { type: 'after', last: list[list.length - 1] };
  };

  // ---------------------------------------------------------------- meals
  C.ALLERGENS = { 1: '난류', 2: '우유', 3: '메밀', 4: '땅콩', 5: '대두', 6: '밀', 7: '고등어', 8: '게', 9: '새우', 10: '돼지고기',
    11: '복숭아', 12: '토마토', 13: '아황산류', 14: '호두', 15: '닭고기', 16: '쇠고기', 17: '오징어', 18: '조개류', 19: '잣' };

  // ---------------------------------------------------------------- schedule
  C.eventKind = function (name, dayType) {
    const n = name || '';
    // 수능 is not this school's exam; for a middle school it is a closure day or a notice
    if (/수학능력|수능/.test(n)) return /휴업/.test(dayType || '') ? '휴업일' : '행사';
    if (/고사|시험|평가원|모의|학력평가/.test(n)) return '시험';
    if (/수행/.test(n)) return '수행평가';
    if (/방학/.test(n)) return '방학';
    if (/재량|휴업/.test(n) || /휴업/.test(dayType || '')) return '휴업일';
    if (/입학/.test(n)) return '입학';
    if (/졸업/.test(n)) return '졸업';
    if (/공휴일|대체|설날|추석|성탄|어린이날|현충일|광복절|개천절|한글날|삼일절|석가/.test(n) || dayType === '공휴일') return '공휴일';
    return '행사';
  };
  C.KIND_COLOR = { '시험': 'red', '수행평가': 'orange', '방학': 'green', '휴업일': 'teal', '입학': 'blue', '졸업': 'blue', '공휴일': 'gray', '행사': 'violet' };

  /** Merge NEIS schedule and teacher-entered events into [{date, endDate, name, kind, grades, source}] */
  C.events = function (data, state, grade) {
    const out = [];
    const skip = /^토요휴업일$|^토요일$/;
    ((data && data.schedule && data.schedule.items) || []).forEach((e) => {
      if (skip.test(e.name)) return;
      if (grade && e.grades && e.grades.length && e.grades.indexOf(grade) < 0) return;
      out.push({ date: e.date, name: e.name, kind: C.eventKind(e.name, e.dayType), grades: e.grades, source: 'NEIS', detail: e.detail });
    });
    ((state && state.events) || []).forEach((e) => {
      if (grade && e.grades && e.grades.length && e.grades.map(Number).indexOf(grade) < 0) return;
      out.push({ date: e.date, endDate: e.endDate, name: e.title, kind: e.kind || C.eventKind(e.title), grades: e.grades, source: '교사', detail: e.detail });
    });
    out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
    // one entry per multi-day event (e.g. 기말고사 over three days, bridging weekends)
    const merged = [];
    out.forEach((e) => {
      const prev = merged.find((m) => m.name === e.name && m.source === e.source && C.addDays(m.endDate || m.date, 3) >= e.date && (m.endDate || m.date) < e.date);
      if (prev) prev.endDate = e.endDate && e.endDate > e.date ? e.endDate : e.date;
      else merged.push(Object.assign({}, e));
    });
    return merged;
  };
  C.rangeLabel = function (e) { return C.shortDate(e.date) + (e.endDate && e.endDate > e.date ? ' ~ ' + C.shortDate(e.endDate) : ''); };

  // ---------------------------------------------------------------- notices
  C.noticesFor = function (state, cls) {
    const grade = cls ? cls.split('-')[0] : '';
    const today = C.today();
    const list = ((state && state.notices) || []).filter((n) => {
      if (n.expires && n.expires < today) return false;
      if (n.scope === 'school' || !n.scope) return true;
      if (n.scope === 'grade') return String(n.target) === grade;
      if (n.scope === 'class') return n.target === cls;
      return false;
    });
    const rank = { urgent: 0, normal: 1, class: 2 };
    list.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (rank[a.level] || 1) - (rank[b.level] || 1) || (b.createdAt || 0) - (a.createdAt || 0));
    return list;
  };
  C.LEVEL = { urgent: '긴급', normal: '일반', class: '학급' };

  C.OFFICER_TYPES = [
    { type: 'quiet', label: '조용히 해주세요', sub: '수업이 곧 시작됩니다', icon: 'quiet', color: 'indigo' },
    { type: 'clean', label: '청소 시작', sub: '각자 맡은 청소 구역으로 이동해 주세요', icon: 'broom', color: 'green' },
    { type: 'ready', label: '수업 준비', sub: '교과서와 준비물을 책상 위에 꺼내 주세요', icon: 'book', color: 'orange' },
    { type: 'teacher', label: '선생님 오세요', sub: '담당 선생님께 알림을 보냈습니다', icon: 'teacher', color: 'violet' },
    { type: 'notice', label: '전달사항 있습니다', sub: '임원의 안내를 들어 주세요', icon: 'megaphone', color: 'teal' },
  ];
  C.officerType = (t) => C.OFFICER_TYPES.find((x) => x.type === t) || { label: '학급 알림', sub: '', icon: 'bell', color: 'gray' };

  C.ATT = { present: '출석', absent: '결석', late: '지각', early: '조퇴' };

  window.CB = C;
})();
