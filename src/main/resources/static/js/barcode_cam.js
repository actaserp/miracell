/* ══════════════════════════════════════════════════════════════════════
   BarcodeCam — 바코드 카메라 스캔 공통 모듈

   자재투입(불출) · 세척 · 수리 세 화면이 각자 카메라를 열고 있었다.
   포맷 목록, 중복 억제 시간, 겹침 방지, 오류 문구가 전부 조금씩 달라
   한 곳을 고치면 나머지가 뒤처졌다. 그 부분만 여기로 모은다.

   ★ 껍데기는 호출자가 정한다
     세척은 전체화면 시트, 자재투입·수리는 입력칸 아래 인라인 상자다.
     스캔이 「주된 작업」인지 「폼 채우기 보조」인지가 달라서, 하나로
     통일하면 어느 한쪽이 불편해진다. 이 모듈은 <video> 엘리먼트 하나만
     받아서 그 안을 채운다. 어디에 어떻게 놓을지는 화면이 결정한다.

   ★ object-fit 은 contain 이어야 한다  ← 이게 「어색함」의 정체였다
     BarcodeDetector.detect(video) 는 화면에 보이는 부분이 아니라
     원본 프레임 전체를 훑는다. cover 로 잘라 보여주면 눈에 안 보이는
     바깥에서 인식이 되고, 조준틀 안에 맞춰도 반응이 없다.
     화면이 거짓말을 하게 된다. contain 은 검출기가 보는 것을 그대로
     보여주므로, 카메라가 기기 모서리에 붙어 생기는 시차도 눈으로 보고
     스스로 보정할 수 있다. 이 모듈이 video 에 직접 걸어둔다.

   사용법
   ─────────────────────────────────────────────────────────────────
     const cam = BarcodeCam.create({
         video:      document.getElementById('scanVideo'),
         continuous: true,           // 세척·불출 true / 수리 false
         onScan(raw, info) { ... },  // info: {lot, gtin14, eff, qty}
         onStatus(msg) { ... },      // 화면에 띄울 상태 문구
         onError(msg) { ... }        // 열기 실패 사유 (사용자에게 보여줄 문장)
     });
     await cam.start();
     cam.stop();
   ══════════════════════════════════════════════════════════════════════ */
(function (global) {
    'use strict';

    var FORMATS = ['code_128', 'code_39', 'ean_13', 'ean_8',
        'qr_code', 'data_matrix', 'itf', 'codabar'];

    var TICK_MS = 220;      // 프레임 훑는 간격
    var DUP_MS = 2500;      // 같은 값 무시 시간 (카메라는 프레임마다 잡힌다)

    /* ── 환경 점검 ────────────────────────────────────────────────
       버튼을 숨기지 않고, 누른 뒤에 무엇이 막는지 말해주기 위한 것.
       사내망(http)에서 버튼이 사라지면 현장에서는 고칠 실마리가 없다. */
    function check() {
        var secure = global.isSecureContext
            || location.protocol === 'https:'
            || location.hostname === 'localhost'
            || location.hostname === '127.0.0.1';

        if (!secure) {
            return {ok: false, reason: 'insecure',
                msg: 'HTTPS 로 접속해야 카메라를 쓸 수 있습니다.\n'
                    + '(현재 ' + location.protocol + '//' + location.hostname + ')\n'
                    + '스캐너나 직접 입력은 그대로 쓰실 수 있습니다.'};
        }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            return {ok: false, reason: 'nomedia',
                msg: '이 브라우저에서는 카메라를 열 수 없습니다.\n스캐너로 입력해 주세요.'};
        }
        if (!global.BarcodeDetector) {
            return {ok: false, reason: 'nodetector',
                msg: '이 브라우저는 바코드 인식을 지원하지 않습니다.\n'
                    + '안드로이드 크롬에서 열거나, 스캐너로 입력해 주세요.'};
        }
        return {ok: true};
    }

    /* getUserMedia 실패 사유별 안내 — 조치가 각각 다르다 */
    function openError(e) {
        var n = (e && e.name) || '';
        if (n === 'NotAllowedError' || n === 'SecurityError') {
            return '카메라 권한이 거부되었습니다.\n주소창 자물쇠 → 권한 → 카메라를 허용해 주세요.';
        }
        if (n === 'NotFoundError' || n === 'DevicesNotFoundError') {
            return '이 기기에서 카메라를 찾지 못했습니다.';
        }
        if (n === 'NotReadableError' || n === 'TrackStartError') {
            return '다른 앱이 카메라를 쓰고 있습니다. 그 앱을 닫고 다시 시도하세요.';
        }
        if (n === 'OverconstrainedError') {
            return '후면 카메라를 찾지 못했습니다.';
        }
        return '카메라를 열 수 없습니다. (' + (n || '알 수 없는 오류') + ')';
    }

    /* ── GS1-128 파싱 ─────────────────────────────────────────────
       (01)GTIN (10)LOT (17)유효기한 (30)수량.
       가변길이 AI 는 FNC1(GS, 0x1D)에서 끊긴다. 라벨·스캐너에 따라
       GS 가 오기도 하고 안 오기도 한다.
       자사 발행 Code128 은 값이 곧 로트번호라 파싱에 안 걸리고
       raw 가 그대로 lot 으로 내려간다. */
    var FIXED = {'01': 14, '11': 6, '13': 6, '15': 6, '17': 6};

    function parse(raw) {
        var out = {raw: raw, gtin14: '', lot: '', eff: '', qty: null};
        if (!raw) return out;
        var s = String(raw).trim();

        if (s.indexOf('(') >= 0) {                    // (01)…(10)… 표기
            var re = /\((\d{2,4})\)([^(]*)/g, m;
            while ((m = re.exec(s)) !== null) putAi(out, m[1], m[2]);
            if (!out.lot) out.lot = s;
            return out;
        }
        if (!/^\d/.test(s)) { out.lot = s; return out; }   // 숫자로 시작 안 하면 자사 로트

        var i = 0, guard = 0;
        while (i < s.length && guard++ < 40) {
            var ai = s.substr(i, 2);
            if (!/^\d{2}$/.test(ai)) break;
            i += 2;
            var val;
            if (FIXED[ai] !== undefined) { val = s.substr(i, FIXED[ai]); i += FIXED[ai]; }
            else {
                var gs = s.indexOf('\u001D', i);
                val = (gs >= 0) ? s.substring(i, gs) : s.substring(i);
                i = (gs >= 0) ? gs + 1 : s.length;
            }
            putAi(out, ai, val);
        }
        if (!out.lot) out.lot = s;      // AI 구조가 아니었다 — 원문이 곧 로트
        return out;
    }

    function putAi(out, ai, val) {
        val = (val || '').replace(/\u001D/g, '').trim();
        if (ai === '01') out.gtin14 = val;
        else if (ai === '10') out.lot = val;
        else if (ai === '17') out.eff = val;
        else if (ai === '30' || ai === '37') out.qty = parseFloat(val);
    }

    /* ── 성공·실패 신호 ───────────────────────────────────────────
       성공은 짧고 높게 한 번, 실패는 낮게 두 번. 화면을 안 봐도 구분된다. */
    function beep(ok) {
        try {
            var Ctx = global.AudioContext || global.webkitAudioContext;
            if (!Ctx) return;
            var ctx = new Ctx();
            var play = function (freq, at, dur) {
                var o = ctx.createOscillator(), g = ctx.createGain();
                o.connect(g); g.connect(ctx.destination);
                o.frequency.value = freq;
                g.gain.setValueAtTime(0.08, ctx.currentTime + at);
                o.start(ctx.currentTime + at);
                o.stop(ctx.currentTime + at + dur);
            };
            if (ok) play(1400, 0, 0.08);
            else { play(320, 0, 0.12); play(320, 0.18, 0.12); }
        } catch (e) { /* 소리는 있으면 좋은 것. 없다고 막지 않는다 */ }
        if (navigator.vibrate) navigator.vibrate(ok ? 40 : [50, 60, 50]);
    }

    /* ── 인스턴스 ─────────────────────────────────────────────── */
    function create(opt) {
        opt = opt || {};
        var video = opt.video;
        var continuous = opt.continuous !== false;   // 기본은 연속
        var onScan = opt.onScan || function () {};
        var onStatus = opt.onStatus || function () {};
        var onError = opt.onError || function (m) { global.alert && global.alert(m); };

        var stream = null, det = null, timer = null;
        var busy = false, lastCode = '', lastAt = 0, running = false;

        function status(m) { try { onStatus(m); } catch (e) {} }

        function loop() {
            clearTimeout(timer);
            timer = setTimeout(function () {
                if (!running || !det) return;
                /* readyState < 2 면 아직 그릴 프레임이 없다 */
                if (!busy && video && video.readyState >= 2) {
                    busy = true;
                    det.detect(video).then(function (codes) {
                        busy = false;
                        if (!running || !codes || !codes.length) return;
                        var raw = codes[0].rawValue || '';
                        if (!raw) return;

                        /* 카메라는 프레임마다 같은 값을 잡는다 */
                        var now = Date.now();
                        if (raw === lastCode && now - lastAt < DUP_MS) return;
                        lastCode = raw; lastAt = now;

                        beep(true);
                        try { onScan(raw, parse(raw)); } catch (e) { console.error('[BarcodeCam]', e); }

                        if (!continuous) { stop(); return; }   // 단발이면 여기서 닫는다
                    }).catch(function () {
                        busy = false;              // 프레임 하나 실패는 무시
                    });
                }
                loop();
            }, TICK_MS);
        }

        function start() {
            var chk = check();
            if (!chk.ok) { onError(chk.msg, chk.reason); return Promise.resolve(false); }
            if (running) return Promise.resolve(true);
            if (!video) { onError('영상 표시 영역이 없습니다.'); return Promise.resolve(false); }

            status('카메라 준비 중…');
            try {
                det = new global.BarcodeDetector({formats: FORMATS});
            } catch (e) {
                onError('바코드 인식기를 만들 수 없습니다.'); return Promise.resolve(false);
            }

            return navigator.mediaDevices.getUserMedia({
                video: {facingMode: {ideal: 'environment'}, width: {ideal: 1280}}
            }).then(function (st) {
                stream = st;
                /* ★ 검출기가 보는 것과 화면에 보이는 것을 일치시킨다 */
                video.style.objectFit = 'contain';
                video.setAttribute('playsinline', '');
                video.muted = true;
                video.srcObject = st;
                return video.play();
            }).then(function () {
                running = true;
                lastCode = ''; lastAt = 0; busy = false;
                status(continuous ? '라벨을 비추세요 · 계속 찍으시면 됩니다' : '라벨을 비추세요');
                loop();
                return true;
            }).catch(function (e) {
                onError(openError(e));
                stop();
                return false;
            });
        }

        function stop() {
            running = false;
            clearTimeout(timer); timer = null;
            if (stream) {
                stream.getTracks().forEach(function (t) { t.stop(); });
                stream = null;
            }
            if (video) video.srcObject = null;
            det = null; busy = false;
        }

        /* ── 줌 · 토치 ────────────────────────────────────────────
           작은 라벨에는 시차 보정보다 줌 2배가 훨씬 크게 듣는다.
           지원 여부는 기기마다 다르므로 호출자가 물어보고 버튼을 낸다. */
        function track() {
            return stream && stream.getVideoTracks ? stream.getVideoTracks()[0] : null;
        }

        function caps() {
            var t = track();
            if (!t || !t.getCapabilities) return {};
            try { return t.getCapabilities() || {}; } catch (e) { return {}; }
        }

        function canZoom() { return !!caps().zoom; }
        function canTorch() { return !!caps().torch; }

        function zoom(v) {
            var t = track(), c = caps();
            if (!t || !c.zoom) return false;
            var val = Math.min(c.zoom.max, Math.max(c.zoom.min, v));
            try { t.applyConstraints({advanced: [{zoom: val}]}); return true; }
            catch (e) { return false; }
        }

        function torch(on) {
            var t = track();
            if (!t || !caps().torch) return false;
            try { t.applyConstraints({advanced: [{torch: !!on}]}); return true; }
            catch (e) { return false; }
        }

        return {
            start: start,
            stop: stop,
            isRunning: function () { return running; },
            toggle: function () { return running ? (stop(), Promise.resolve(false)) : start(); },
            canZoom: canZoom, canTorch: canTorch, zoom: zoom, torch: torch,
            zoomRange: function () { return caps().zoom || null; }
        };
    }

    global.BarcodeCam = {
        create: create,
        check: check,       // 버튼 옆 안내문에 쓴다
        parse: parse,       // 스캐너 입력에도 같은 파서를 태울 수 있다
        beep: beep,
        FORMATS: FORMATS
    };
})(window);