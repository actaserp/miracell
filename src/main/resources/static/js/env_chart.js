/* ═══════════════════════════════════════════════════════════
   환경 센서 추이 그래프 — 통합·1공장 대시보드 팝업 + 환경 데이터 이력 화면 공용

   EnvChart.open({spjangcd, groupCode, groupName})        대시보드 팝업 (최근 6h/24h/7일/30일)
   EnvChart.render(host, data, {t0, t1})                   화면 안에 그리기 (이력 화면)
       data = 서버 /env_trend · /env_history 응답 {sensors, points, bucket_sec}
       t0·t1 = 가로축 범위 (KST 를 UTC 처럼 계산한 ms)

   ★ 외부 차트 라이브러리를 쓰지 않는다. 현장망에서 CDN 이 막힐 수 있다.
   ★ 기준 밖 구간은 선을 빨갛게, 기준선은 빨간 점선. 수신 끊김 구간은 잇지 않는다.
   ★ t 는 KST 를 UTC 처럼 계산한 값이라 getUTC* 로 읽는다(브라우저 시간대와 무관).
   ═══════════════════════════════════════════════════════════ */
(function (w) {
    'use strict';

    var API = '/api/dashboard/main/env_trend';
    var PERIODS = [
        {h: 6, nm: '6시간'}, {h: 24, nm: '24시간'}, {h: 24 * 7, nm: '7일'}, {h: 24 * 30, nm: '30일'}
    ];
    var KIND_NM = {temp: '온도', humi: '습도', press: '차압'};
    var KIND_UNIT = {temp: '°C', humi: '%', press: ''};
    var KIND_DIG = {temp: 1, humi: 1, press: 2};
    var COLOR = {temp: '#e8833a', humi: '#2f6fed', press: '#7a6ff0'};
    var KST = 9 * 3600 * 1000;

    function esc(v) {
        return String(v === null || v === undefined ? '' : v)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function has(v) { return v !== null && v !== undefined && v !== ''; }
    function pad(n) { return String(n).padStart(2, '0'); }
    function fmtT(ms, withDate) {
        var d = new Date(ms);
        var hm = pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes());
        return withDate ? pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()) + ' ' + hm : hm;
    }
    function kindNm(s) { return KIND_NM[s.kind] || s.tag_name || s.kind; }
    function unitOf(s) { return has(s.unit) ? s.unit : (KIND_UNIT[s.kind] || ''); }
    function digOf(s) { return KIND_DIG[s.kind] || 2; }
    function isOut(v, s) {
        return (has(s.min_val) && v < Number(s.min_val)) || (has(s.max_val) && v > Number(s.max_val));
    }

    function css() {
        if (document.getElementById('envChartCss')) return;
        var s = document.createElement('style');
        s.id = 'envChartCss';
        s.textContent = ''
            + '.ecx-mask{position:fixed;inset:0;background:rgba(20,26,36,.45);z-index:10000;display:flex;'
            + 'align-items:center;justify-content:center;font-family:Pretendard,"Malgun Gothic",sans-serif}'
            + '.ecx-box{background:#fff;border-radius:12px;width:min(1100px,calc(100vw - 24px));max-height:calc(100vh - 40px);'
            + 'display:flex;flex-direction:column;box-shadow:0 12px 40px rgba(20,26,36,.3);overflow:hidden}'
            + '.ecx-h{display:flex;align-items:center;gap:10px;padding:12px 16px;background:#2f3a4c;color:#fff;font-weight:800;font-size:14px;flex-wrap:wrap}'
            + '.ecx-h .pd{display:flex;gap:4px;margin-left:auto}'
            + '.ecx-h .pd button{border:1px solid #56627a;background:transparent;color:#cfd6e2;border-radius:6px;'
            + 'padding:4px 11px;font-weight:800;font-size:12px;cursor:pointer}'
            + '.ecx-h .pd button.on{background:#fff;color:#2f3a4c;border-color:#fff}'
            + '.ecx-h .x{background:none;border:0;color:#fff;font-size:16px;cursor:pointer;margin-left:6px}'
            + '.ecx-b{padding:12px 16px 16px;overflow:auto}'
            + '.ecx-c{position:relative;margin-bottom:14px;font-family:Pretendard,"Malgun Gothic",sans-serif}'
            + '.ecx-c .t{font-size:13px;font-weight:800;color:#1f2733;display:flex;gap:10px;align-items:baseline;margin-bottom:4px;flex-wrap:wrap}'
            + '.ecx-c .t small{color:#8a94a4;font-weight:700;font-size:11.5px}'
            + '.ecx-c .t .out{color:#d64545}'
            + '.ecx-c canvas{display:block;width:100%;height:220px;border:1px solid #eef1f6;border-radius:8px;touch-action:pan-y;background:#fff}'
            + '.ecx-tip{position:absolute;pointer-events:none;background:#1f2733;color:#fff;font-size:11.5px;font-weight:700;'
            + 'padding:5px 8px;border-radius:6px;white-space:nowrap;display:none;z-index:2}'
            + '.ecx-empty{padding:40px;text-align:center;color:#8a94a4;font-size:13px}';
        document.head.appendChild(s);
    }

    /* ─────────────────────────────────────────────
       그리기 (인스턴스) — host 안에 센서마다 그래프 하나
       ───────────────────────────────────────────── */
    function Chart(host, data, range) {
        this.host = host;
        this.data = data;
        this.t0 = range.t0;
        this.t1 = range.t1;
        var map = {};
        (data.points || []).forEach(function (p) {
            (map[p.tagname] = map[p.tagname] || []).push({t: Number(p.t), v: Number(p.v), lo: Number(p.lo), hi: Number(p.hi)});
        });
        this.list = (data.sensors || []).map(function (s) { return {s: s, pts: map[s.tag_name] || []}; });
        this.build();
    }

    Chart.prototype.build = function () {
        var self = this;
        if (!this.list.length) { this.host.innerHTML = '<div class="ecx-empty">센서가 없습니다.</div>'; return; }
        this.host.innerHTML = this.list.map(function (x, i) {
            var s = x.s, dig = digOf(s), unit = unitOf(s);
            var rg = (has(s.min_val) || has(s.max_val))
                ? '기준 ' + (has(s.min_val) ? Number(s.min_val) : '') + '~' + (has(s.max_val) ? Number(s.max_val) : '') + unit
                : '기준 미설정';
            var outN = x.pts.filter(function (p) { return isOut(p.v, s); }).length;
            var outPct = x.pts.length ? Math.round(outN / x.pts.length * 1000) / 10 : 0;
            var sum = x.pts.length
                ? '평균 ' + (x.pts.reduce(function (a, p) { return a + p.v; }, 0) / x.pts.length).toFixed(dig)
                + ' · 최저 ' + Math.min.apply(null, x.pts.map(function (p) { return p.lo; })).toFixed(dig)
                + ' · 최고 ' + Math.max.apply(null, x.pts.map(function (p) { return p.hi; })).toFixed(dig)
                : '기간 안에 수신된 값이 없습니다';
            return '<div class="ecx-c"><div class="t">' + esc(kindNm(s)) + '<small>' + esc(rg) + '</small>'
                + '<small>' + sum + '</small>'
                + (outN ? '<small class="out">기준 밖 ' + outPct + '%</small>' : '') + '</div>'
                + '<canvas data-i="' + i + '"></canvas><div class="ecx-tip"></div></div>';
        }).join('');
        this.host.querySelectorAll('canvas').forEach(function (cv) { self.bind(cv); });
        this.drawAll();
    };

    Chart.prototype.drawAll = function () {
        var self = this;
        this.host.querySelectorAll('.ecx-c canvas').forEach(function (cv) { self.draw(cv); });
    };

    Chart.prototype.geom = function (cv) {
        var x = this.list[Number(cv.getAttribute('data-i'))];
        var W = cv.clientWidth, H = cv.clientHeight;
        var L = 46, R = 12, T = 10, B = 24, t0 = this.t0, t1 = this.t1;
        var ys = [];
        x.pts.forEach(function (p) { ys.push(p.lo, p.hi); });
        if (has(x.s.min_val)) ys.push(Number(x.s.min_val));
        if (has(x.s.max_val)) ys.push(Number(x.s.max_val));
        var y0 = ys.length ? Math.min.apply(null, ys) : 0, y1 = ys.length ? Math.max.apply(null, ys) : 1;
        var padY = (y1 - y0) * 0.12 || 1;
        y0 -= padY; y1 += padY;
        return {
            x: x, W: W, H: H, L: L, R: R, T: T, B: B, y0: y0, y1: y1,
            px: function (t) { return L + (t - t0) / (t1 - t0) * (W - L - R); },
            py: function (v) { return T + (1 - (v - y0) / (y1 - y0)) * (H - T - B); }
        };
    };

    Chart.prototype.draw = function (cv) {
        var g = this.geom(cv), x = g.x, s = x.s, k = s.kind, bsec = this.data.bucket_sec;
        var dpr = w.devicePixelRatio || 1;
        cv.width = Math.round(g.W * dpr); cv.height = Math.round(g.H * dpr);
        var c = cv.getContext('2d');
        c.setTransform(dpr, 0, 0, dpr, 0, 0);
        c.clearRect(0, 0, g.W, g.H);
        c.font = '11px Pretendard, "Malgun Gothic", sans-serif';

        c.strokeStyle = '#eef1f6'; c.fillStyle = '#8a94a4'; c.lineWidth = 1;
        c.textAlign = 'right'; c.textBaseline = 'middle';
        for (var i = 0; i <= 4; i++) {
            var v = g.y0 + (g.y1 - g.y0) * i / 4, y = g.py(v);
            c.beginPath(); c.moveTo(g.L, y); c.lineTo(g.W - g.R, y); c.stroke();
            c.fillText(v.toFixed(digOf(s)), g.L - 6, y);
        }
        c.textBaseline = 'top';
        var multiDay = (this.t1 - this.t0) > 26 * 3600 * 1000;
        for (var j = 0; j <= 6; j++) {
            var t = this.t0 + (this.t1 - this.t0) * j / 6;
            c.textAlign = j === 0 ? 'left' : (j === 6 ? 'right' : 'center');
            c.fillText(fmtT(t, multiDay), g.px(t), g.H - g.B + 6);
        }

        c.fillStyle = 'rgba(214,69,69,.06)';
        if (has(s.max_val)) { var ym = g.py(Number(s.max_val)); if (ym > g.T) c.fillRect(g.L, g.T, g.W - g.L - g.R, ym - g.T); }
        if (has(s.min_val)) { var yn = g.py(Number(s.min_val)); if (yn < g.H - g.B) c.fillRect(g.L, yn, g.W - g.L - g.R, g.H - g.B - yn); }
        c.setLineDash([5, 4]); c.strokeStyle = '#d64545';
        [s.min_val, s.max_val].forEach(function (lim) {
            if (!has(lim)) return;
            var yl = g.py(Number(lim));
            c.beginPath(); c.moveTo(g.L, yl); c.lineTo(g.W - g.R, yl); c.stroke();
        });
        c.setLineDash([]);

        var pts = x.pts;
        if (!pts.length) return;
        var gap = bsec * 1000 * 3;

        if (bsec >= 600) {   /* 긴 기간 — 구간 안 최저~최고 띠 */
            c.fillStyle = 'rgba(47,111,237,.10)';
            for (var a = 0; a < pts.length; a++) {
                var bw = Math.max(1, g.px(pts[a].t + bsec * 1000) - g.px(pts[a].t));
                c.fillRect(g.px(pts[a].t), g.py(pts[a].hi), bw, Math.max(1, g.py(pts[a].lo) - g.py(pts[a].hi)));
            }
        }
        c.lineWidth = 1.8; c.lineJoin = 'round';
        for (var n = 1; n < pts.length; n++) {
            var p0 = pts[n - 1], p1 = pts[n];
            if (p1.t - p0.t > gap) continue;
            c.strokeStyle = (isOut(p0.v, s) || isOut(p1.v, s)) ? '#d64545' : (COLOR[k] || '#2f6fed');
            c.beginPath(); c.moveTo(g.px(p0.t), g.py(p0.v)); c.lineTo(g.px(p1.t), g.py(p1.v)); c.stroke();
        }
        if (pts.length === 1) {
            c.fillStyle = COLOR[k] || '#2f6fed';
            c.beginPath(); c.arc(g.px(pts[0].t), g.py(pts[0].v), 2.5, 0, Math.PI * 2); c.fill();
        }
    };

    Chart.prototype.bind = function (cv) {
        var self = this, tip = cv.nextElementSibling;
        var show = function (clientX) {
            var g = self.geom(cv), pts = g.x.pts, s = g.x.s, bsec = self.data.bucket_sec;
            if (!pts.length) return;
            var r = cv.getBoundingClientRect();
            var t = self.t0 + (clientX - r.left - g.L) / (g.W - g.L - g.R) * (self.t1 - self.t0);
            var best = null, bd = Infinity;
            for (var i = 0; i < pts.length; i++) {
                var dd = Math.abs(pts[i].t - t);
                if (dd < bd) { bd = dd; best = pts[i]; }
            }
            self.draw(cv);
            if (!best || bd > bsec * 1000 * 3) { tip.style.display = 'none'; return; }
            var c = cv.getContext('2d'), bx = g.px(best.t), by = g.py(best.v);
            c.strokeStyle = 'rgba(31,39,51,.35)'; c.lineWidth = 1;
            c.beginPath(); c.moveTo(bx, g.T); c.lineTo(bx, g.H - g.B); c.stroke();
            c.fillStyle = isOut(best.v, s) ? '#d64545' : (COLOR[s.kind] || '#2f6fed');
            c.beginPath(); c.arc(bx, by, 3.5, 0, Math.PI * 2); c.fill();
            var dig = digOf(s), unit = unitOf(s);
            tip.innerHTML = fmtT(best.t, true) + ' · ' + best.v.toFixed(dig) + unit
                + (bsec >= 600 ? ' (' + best.lo.toFixed(dig) + '~' + best.hi.toFixed(dig) + ')' : '');
            tip.style.display = 'block';
            var left = bx + 10;
            if (left + tip.offsetWidth > g.W) left = bx - tip.offsetWidth - 10;
            tip.style.left = left + 'px';
            tip.style.top = (cv.offsetTop + Math.max(4, by - 30)) + 'px';
        };
        cv.addEventListener('mousemove', function (e) { show(e.clientX); });
        cv.addEventListener('mouseleave', function () { tip.style.display = 'none'; self.draw(cv); });
        cv.addEventListener('touchstart', function (e) { if (e.touches[0]) show(e.touches[0].clientX); }, {passive: true});
        cv.addEventListener('touchmove', function (e) { if (e.touches[0]) show(e.touches[0].clientX); }, {passive: true});
    };

    var charts = [];
    w.addEventListener('resize', function () {
        charts = charts.filter(function (ch) { return document.body.contains(ch.host); });
        charts.forEach(function (ch) { ch.drawAll(); });
    });

    function render(host, data, range) {
        css();
        var ch = new Chart(host, data, range);
        charts = charts.filter(function (x) { return x.host !== host; });
        charts.push(ch);
        return ch;
    }

    /* ─────────────────────────────────────────────
       대시보드 팝업
       ───────────────────────────────────────────── */
    var st = {opt: null, hours: 24};
    var root = null;

    function open(opt) {
        css();
        st.opt = opt || {};
        if (!root) {
            root = document.createElement('div');
            root.className = 'ecx-mask';
            root.addEventListener('click', function (e) { if (e.target === root) close(); });
            document.body.appendChild(root);
            document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });
        }
        root.style.display = 'flex';
        root.innerHTML = '<div class="ecx-box"><div class="ecx-h">'
            + '<span>' + esc(st.opt.groupName || st.opt.groupCode) + ' · 추이</span>'
            + '<span class="pd">' + PERIODS.map(function (p) {
                return '<button type="button" data-h="' + p.h + '"' + (p.h === st.hours ? ' class="on"' : '') + '>' + p.nm + '</button>';
            }).join('') + '</span>'
            + '<button type="button" class="x" title="닫기">✕</button></div>'
            + '<div class="ecx-b" id="ecxBody"><div class="ecx-empty">불러오는 중…</div></div></div>';
        root.querySelector('.x').onclick = close;
        root.querySelector('.pd').onclick = function (e) {
            var b = e.target.closest('button[data-h]');
            if (!b) return;
            st.hours = Number(b.getAttribute('data-h'));
            root.querySelectorAll('.pd button').forEach(function (x) { x.classList.toggle('on', x === b); });
            load();
        };
        load();
    }

    function close() { if (root) root.style.display = 'none'; }

    function load() {
        var body = document.getElementById('ecxBody');
        body.innerHTML = '<div class="ecx-empty">불러오는 중…</div>';
        AjaxUtil.getAsyncData(API, {spjangcd: st.opt.spjangcd, group_code: st.opt.groupCode, hours: st.hours}, function (res) {
            if (!res || res.success === false || !res.data) {
                body.innerHTML = '<div class="ecx-empty">조회하지 못했습니다' + (res && res.message ? ' — ' + esc(res.message) : '') + '</div>';
                return;
            }
            var now = Date.now() + KST;
            render(body, res.data, {t0: now - res.data.hours * 3600 * 1000, t1: now});
        });
    }

    w.EnvChart = {open: open, close: close, render: render, fmtT: fmtT, KIND_NM: KIND_NM, KIND_UNIT: KIND_UNIT, KIND_DIG: KIND_DIG};
})(window);