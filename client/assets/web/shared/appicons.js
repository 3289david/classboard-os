/* App icons for the board's own apps. Drawn by hand in a 64x64 grid on a rounded-square tile. */
(function () {
  'use strict';
  const TILE = 'M32 0C49.7 0 54.2 0 58.6 4.4 63 8.8 64 14.3 64 32s-1 23.2-5.4 27.6C54.2 64 49.7 64 32 64S9.8 64 5.4 59.6C1 55.2 0 49.7 0 32S1 8.8 5.4 4.4C9.8 0 14.3 0 32 0Z';
  const DOW = ['일', '월', '화', '수', '목', '금', '토'];

  function grad(id, a, b) {
    return '<linearGradient id="' + id + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="' + a + '"/><stop offset="1" stop-color="' + b + '"/></linearGradient>';
  }
  function svg(id, defs, bg, body) {
    return '<svg class="aicon" viewBox="0 0 64 64" aria-hidden="true"><defs>' + defs + '<clipPath id="t-' + id + '"><path d="' + TILE + '"/></clipPath></defs>' +
      '<g clip-path="url(#t-' + id + ')"><path d="' + TILE + '" fill="' + bg + '"/>' + body + '</g>' +
      '<path d="' + TILE + '" fill="none" stroke="rgba(0,0,0,.08)" stroke-width="1"/></svg>';
  }
  function colored(id, a, b, body) { return svg(id, grad('g-' + id, a, b), 'url(#g-' + id + ')', body); }

  // gear with n teeth as a single path
  function gear(cx, cy, R, r, n) {
    let d = '';
    for (let i = 0; i < n; i++) {
      const a0 = (i / n) * Math.PI * 2, step = Math.PI * 2 / n;
      const pts = [[a0 - step * 0.22, R], [a0 - step * 0.14, R + 4.2], [a0 + step * 0.14, R + 4.2], [a0 + step * 0.22, R], [a0 + step * 0.5, r + 0.6]];
      pts.forEach((p, k) => { const x = cx + Math.cos(p[0]) * p[1], y = cy + Math.sin(p[0]) * p[1]; d += (i === 0 && k === 0 ? 'M' : 'L') + x.toFixed(2) + ' ' + y.toFixed(2); });
    }
    return d + 'Z';
  }

  const ICONS = {
    lesson: () => colored('lesson', '#62A0FF', '#2F67E6',
      '<path d="M12 19.5c6.5-2.3 13-1.8 19 2.2v25.5c-6-3.8-12.5-4.3-19-2.2Z" fill="#fff"/>' +
      '<path d="M52 19.5c-6.5-2.3-13-1.8-19 2.2v25.5c6-3.8 12.5-4.3 19-2.2Z" fill="#E4EDFF"/>' +
      '<path d="M16 26.5c3.6-.9 7.2-.6 10.6.9M16 31.5c3.6-.9 7.2-.6 10.6.9M16 36.5c3.6-.9 7.2-.6 10.6.9" stroke="#B7CBF4" stroke-width="1.7" fill="none" stroke-linecap="round"/>' +
      '<path d="M41 20.6v10.6l2.4-1.8 2.4 1.8V20" fill="#FF6B5E"/>'),
    timetable: () => svg('timetable', grad('g-timetable', '#FFFFFF', '#EEF1F6'), 'url(#g-timetable)',
      '<rect x="11" y="12" width="42" height="8" rx="2.5" fill="#6E62F6"/>' +
      [['#FFB547', '#E6EAF1', '#5B9BFF'], ['#E6EAF1', '#3ECF8E', '#E6EAF1'], ['#FF7A85', '#E6EAF1', '#B07CFF']].map((row, y) =>
        row.map((c, x) => '<rect x="' + (11 + x * 14.5) + '" y="' + (24 + y * 10) + '" width="13" height="8" rx="2.2" fill="' + c + '"/>').join('')).join('')),
    meal: () => colored('meal', '#FFBE55', '#FF8A2A',
      '<rect x="9" y="15" width="46" height="35" rx="8" fill="#F3F5F8"/>' +
      '<rect x="13" y="19" width="11.5" height="10" rx="3" fill="#6CC070"/>' +
      '<rect x="26.3" y="19" width="11.5" height="10" rx="3" fill="#F2643D"/>' +
      '<rect x="39.6" y="19" width="11.4" height="10" rx="3" fill="#F7C948"/>' +
      '<rect x="13" y="32" width="18" height="14" rx="5" fill="#fff" stroke="#D9DFE7" stroke-width="1.2"/>' +
      '<rect x="33" y="32" width="18" height="14" rx="5" fill="#E7A25A"/>' +
      '<path d="M36 37.5h12" stroke="#F6C98F" stroke-width="1.6" stroke-linecap="round"/>'),
    notices: () => colored('notices', '#36D2C2', '#109C96',
      '<rect x="18" y="12" width="28" height="26" rx="2.5" fill="#fff"/>' +
      '<path d="M23 19h18M23 24h18M23 29h12" stroke="#A3DDD7" stroke-width="2" stroke-linecap="round"/>' +
      '<path d="M11 28.5 32 41l21-12.5V47a4 4 0 0 1-4 4H15a4 4 0 0 1-4-4Z" fill="#F1FBFA"/>' +
      '<path d="M11 47.5 26.5 37.6M53 47.5 37.5 37.6" stroke="#C6EBE7" stroke-width="1.6"/>'),
    calendar: () => {
      const d = new Date();
      return svg('calendar', grad('g-calendar', '#FFFFFF', '#F2F4F8'), 'url(#g-calendar)',
        '<rect x="0" y="0" width="64" height="19" fill="#F2493F"/>' +
        '<text x="32" y="14" text-anchor="middle" font-size="10.5" font-weight="700" fill="#fff" font-family="inherit">' + DOW[d.getDay()] + '요일</text>' +
        '<text x="32" y="49.5" text-anchor="middle" font-size="29" font-weight="600" fill="#1F2328" letter-spacing="-1" font-family="inherit">' + d.getDate() + '</text>');
    },
    weather: () => colored('weather', '#62C6FF', '#2C83E6',
      '<circle cx="40" cy="22" r="9.5" fill="#FFD43B"/>' +
      '<path d="M18.5 47h25a8.5 8.5 0 0 0 .6-17 11.5 11.5 0 0 0-21.8 1.6A7.8 7.8 0 0 0 18.5 47Z" fill="#fff"/>'),
    memo: () => colored('memo', '#FFDD6E', '#F6B400',
      '<rect x="13" y="13" width="30" height="37" rx="4" fill="#FFFDF4"/>' +
      '<path d="M18 21h20M18 27h20M18 33h13" stroke="#EFD27E" stroke-width="2" stroke-linecap="round"/>' +
      '<path d="M31.5 43.5 49 26l4 4-17.5 17.5Z" fill="#FF7A45"/><path d="M49 26l2-2a2.8 2.8 0 0 1 4 4l-2 2Z" fill="#F4A7B0"/>' +
      '<path d="M31.5 43.5 29.5 49.5l6-2Z" fill="#FFE0B8"/><path d="M30.3 47.1 29.5 49.5l2.4-.8Z" fill="#3A3A3A"/>'),
    board: () => colored('board', '#A9713F', '#7A4A27',
      '<rect x="7.5" y="9" width="49" height="38" rx="3.5" fill="#2F5F48"/>' +
      '<rect x="7.5" y="9" width="49" height="38" rx="3.5" fill="url(#g-board-s)" opacity=".5"/>' +
      '<path d="M14 21c2.6-4.2 5.6-4.2 6.8 0s4.2 4.4 6.6.2M14 30.5h16M14 37h22M37 20.5l3.4 3.4 6.6-7" stroke="#F4F6F2" stroke-width="2.1" fill="none" stroke-linecap="round" stroke-linejoin="round" opacity=".92"/>' +
      '<rect x="9" y="49.5" width="46" height="4.5" rx="2" fill="#5E3A1E"/><rect x="40" y="47" width="9" height="3.4" rx="1.6" fill="#fff"/>')
      .replace('</defs>', grad('g-board-s', '#3E7A5C', '#21483A') + '</defs>'),
    capture: () => colored('capture', '#6E7789', '#434A59',
      '<path d="M15 24v-5a3 3 0 0 1 3-3h5M49 24v-5a3 3 0 0 0-3-3h-5M15 40v5a3 3 0 0 0 3 3h5M49 40v5a3 3 0 0 1-3 3h-5" stroke="#fff" stroke-width="3.2" fill="none" stroke-linecap="round"/>' +
      '<circle cx="32" cy="32" r="6" fill="#fff"/>'),
    record: () => colored('record', '#3D434D', '#1C1F24',
      '<circle cx="32" cy="32" r="15" stroke="#fff" stroke-width="3" fill="none"/><circle cx="32" cy="32" r="9" fill="#FF4747"/>'),
    split: () => colored('split', '#8F82FF', '#5844E6',
      '<rect x="12" y="16" width="18.5" height="32" rx="4" fill="#fff"/><rect x="33.5" y="16" width="18.5" height="32" rx="4" fill="#fff" opacity=".62"/>'),
    files: () => colored('files', '#53A0FF', '#2361E8',
      '<path d="M11 22a4 4 0 0 1 4-4h10.5l4 4H49a4 4 0 0 1 4 4v2H11Z" fill="#BCD6FF"/>' +
      '<rect x="11" y="25" width="42" height="24" rx="4" fill="#fff"/><path d="M27 37h10" stroke="#BCD6FF" stroke-width="2.4" stroke-linecap="round"/>'),
    room: () => colored('room', '#3BCB95', '#139568',
      '<rect x="21" y="12" width="22" height="40" rx="2.5" fill="#fff"/><rect x="25.5" y="17" width="13" height="5.5" rx="1.2" fill="#BFEBD8"/>' +
      '<circle cx="37.5" cy="34" r="2" fill="#139568"/><path d="M14 52h36" stroke="#BFEBD8" stroke-width="2.4" stroke-linecap="round"/>'),
    settings: () => colored('settings', '#A1AAB8', '#5F6877',
      '<path d="' + gear(32, 32, 14.5, 12.5, 9) + '" fill="#fff"/><circle cx="32" cy="32" r="5.6" fill="#7F8897"/>'),
    browser: () => colored('browser', '#45BFFF', '#1570E6',
      '<circle cx="32" cy="32" r="17" fill="#fff"/>' +
      '<g stroke="#2A86EE" stroke-width="2" fill="none"><ellipse cx="32" cy="32" rx="7" ry="17"/><path d="M15 32h34M18 23h28M18 41h28"/></g>'),
    hdmi: () => colored('hdmi', '#4B5466', '#262B35',
      '<rect x="10" y="17" width="44" height="30" rx="4" fill="#3B4250"/><rect x="12" y="19" width="40" height="26" rx="3" fill="#121418"/>' +
      '<path d="M18 26h28l-3.5 8.5h-21Z" fill="#fff"/><path d="M22 28.2h20" stroke="#121418" stroke-width="1.6" stroke-dasharray="1.6 1.4"/>' +
      '<text x="32" y="42.5" text-anchor="middle" font-size="6.6" font-weight="700" fill="#9AA4B5" font-family="inherit" letter-spacing=".6">HDMI</text>'),
    alerts: () => colored('alerts', '#8C7BFF', '#5640E0',
      '<path d="M32 13c-7.2 0-12 5.6-12 12.6v8.2l-3.6 5.6c-.9 1.4.1 3.1 1.7 3.1h27.8c1.6 0 2.6-1.7 1.7-3.1L44 33.8v-8.2C44 18.6 39.2 13 32 13Z" fill="#fff"/>' +
      '<path d="M26.6 46a5.6 5.6 0 0 0 10.8 0Z" fill="#fff"/><circle cx="44.5" cy="17.5" r="5" fill="#FF5A52" stroke="#6A55F0" stroke-width="2"/>'),
    pick: () => colored('pick', '#FF7AA8', '#E0457B',
      '<rect x="9" y="17" width="25" height="25" rx="5" fill="#fff" transform="rotate(-10 21.5 29.5)"/>' +
      '<g fill="#E0457B" transform="rotate(-10 21.5 29.5)"><circle cx="15.5" cy="23" r="2.1"/><circle cx="21.5" cy="29.5" r="2.1"/><circle cx="27.5" cy="36" r="2.1"/></g>' +
      '<rect x="30" y="24" width="24" height="24" rx="5" fill="#FFE3EE" transform="rotate(12 42 36)"/>' +
      '<g fill="#E0457B" transform="rotate(12 42 36)"><circle cx="36" cy="30" r="2"/><circle cx="48" cy="30" r="2"/><circle cx="36" cy="42" r="2"/><circle cx="48" cy="42" r="2"/></g>'),
    drawer: () => colored('drawer', '#7C8594', '#4C5462',
      [0, 1, 2].map((y) => [0, 1, 2].map((x) => '<circle cx="' + (20 + x * 12) + '" cy="' + (20 + y * 12) + '" r="4" fill="#fff"/>').join('')).join('')),
  };

  window.appIconSvg = function (id) { return (ICONS[id] || ICONS.drawer)(); };
})();
