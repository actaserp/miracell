/* =====================================================================
 * sterilLogEngine.js — 미라셀 MES 멸균일지 자동산출 엔진
 *
 *  의존성 없음. 브라우저(PC/태블릿) + Node 공용.
 *  화면과 완전 분리 → 파일 텍스트만 주면 일지 값이 나온다.
 *
 *  흐름:
 *    1) parse(name, text)        파일 1개 → {kind, rows, from, to, interval}
 *    2) assignRoles(parsedList)  role 자동배정 (sterilizer/precondition/aeration)
 *    3) detectSegments(ctx)      구간 자동검출 (vacuum/conditioning/exposure/flush/...)
 *    4) computeFields(fields,ctx) 양식 필드 정의 → 자동산출값
 *
 *  ※ 산출값은 전부 "제안"이다. 담당자가 구간을 바꾸면 3~4만 다시 돌린다.
 * ===================================================================== */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) module.exports = factory();
    else root.SterilLog = factory();
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    /* ---------------------------------------------------------------
     * 설정 (현장 튜닝 지점)
     * --------------------------------------------------------------- */
    var CFG = {
        expRatio:      0.93,  // 노출구간 판정: PS >= max(PS) * 이 비율
        condRise:      20,    // 조절구간 종료: PS 가 최저압력 + 이 값 초과 시
        valleyRise:    10,    // 플러싱 밸리 인정: 밸리 이후 상승폭(kPa)
        tailTrimBand:  0.10,  // 꼬리 절단: 중앙값 대비 이 비율 이상 벗어난 끝부분 제거
        tailTrimMinAbs:3,     //           (최소 절대 편차. 이보다 작으면 자르지 않음)
        tailTrimMaxPct:0.05,  //           최대 절단 비율 (구간의 5% 초과 절단 금지)
        precondSpecKey:'precond_temp_min' // 전조절 시작점 = 이 필드 하한 도달 시점
    };

    /* ---------------------------------------------------------------
     * 유틸
     * --------------------------------------------------------------- */
    function splitLines(text) {
        return String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n')
                           .split('\n').filter(function (l) { return l.trim() !== ''; });
    }
    function splitCsv(line) {
        return line.split(',').map(function (c) { return c.trim(); });
    }
    // "Celsius(¡ÆC)" 같은 깨진 헤더도 부분일치로 찾는다
    function findCol(cols, keyword) {
        var k = keyword.toLowerCase();
        for (var i = 0; i < cols.length; i++) {
            if (cols[i].toLowerCase().indexOf(k) >= 0) return cols[i];
        }
        return null;
    }
    function toDate(s) {
        if (!s) return null;
        var m = String(s).trim()
            .match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?/);
        if (!m) return null;
        return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
    }
    function num(v) {
        if (v === null || v === undefined || v === '') return null;
        var n = parseFloat(v);
        return isNaN(n) ? null : n;
    }
    function median(arr) {
        if (!arr.length) return null;
        var a = arr.slice().sort(function (x, y) { return x - y; });
        return a[Math.floor(a.length / 2)];
    }
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function fmtDt(d) {
        if (!d) return '';
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
               ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    /* ---------------------------------------------------------------
     * 1) 파싱
     *    kind: 'sterilizer' | 'sterilizer_raw' | 'easylog' | 'unknown'
     * --------------------------------------------------------------- */
    function parse(fileName, text) {
        var out = { fileName: fileName, kind: 'unknown', serial: null,
                    rows: [], cols: [], from: null, to: null, interval: null,
                    scale: 1, warn: [] };
        var lines;
        try { lines = splitLines(text); } catch (e) { out.warn.push('읽기 실패'); return out; }
        if (lines.length < 2) { out.warn.push('데이터 없음'); return out; }

        var head = splitCsv(lines[0]);
        out.cols = head;

        var hasPS      = findCol(head, 'PS') !== null && findCol(head, 'CHAMBER') !== null;
        var isEasyLog  = head[0].toLowerCase().indexOf('easylog') >= 0;

        if (hasPS) {
            out.kind = 'sterilizer';
            out.rows = parseSterilizer(lines, head, out);
        } else if (isEasyLog) {
            out.kind = 'easylog';
            out.rows = parseEasyLog(lines, head, out);
        } else {
            out.warn.push('알 수 없는 형식');
            return out;
        }

        if (out.rows.length) {
            out.from = out.rows[0].t;
            out.to   = out.rows[out.rows.length - 1].t;
            var gaps = [];
            for (var i = 1; i < Math.min(out.rows.length, 60); i++) {
                gaps.push((out.rows[i].t - out.rows[i - 1].t) / 1000);
            }
            out.interval = median(gaps); // 초
        }
        return out;
    }

    // Date,Time,PS,HUMI,CHAMBER,CHAMBER LL,CHAMBER BS,CHAMBER LH
    function parseSterilizer(lines, head, out) {
        var idx = {};
        head.forEach(function (h, i) { idx[h] = i; });
        var valCols = ['PS', 'HUMI', 'CHAMBER', 'CHAMBER LL', 'CHAMBER BS', 'CHAMBER LH'];
        var rows = [], anyDecimal = false;

        for (var i = 1; i < lines.length; i++) {
            var c = splitCsv(lines[i]);
            if (c.length < 3) continue;
            var t = toDate(c[idx['Date']] + ' ' + c[idx['Time']]);
            if (!t) continue;
            var r = { t: t };
            valCols.forEach(function (k) {
                var raw = c[idx[k]];
                if (raw !== undefined && String(raw).indexOf('.') >= 0) anyDecimal = true;
                r[k] = num(raw);
            });
            rows.push(r);
        }
        // 정수 전용 스트림(LOG001 계열)이면 /10 스케일
        if (!anyDecimal && rows.length) {
            out.kind  = 'sterilizer_raw';
            out.scale = 10;
            rows.forEach(function (r) {
                valCols.forEach(function (k) { if (r[k] !== null) r[k] = r[k] / 10; });
            });
        }
        return rows;
    }

    // EasyLog USB,Time,Celsius(C),High Alarm,Low Alarm[,Humidity(%rh),...,Dew Point(C)],Serial Number
    function parseEasyLog(lines, head, out) {
        var cTemp = findCol(head, 'celsius');
        var cHumi = findCol(head, 'humidity');
        var cDew  = findCol(head, 'dew');
        var cTime = findCol(head, 'time');
        var cSer  = findCol(head, 'serial');
        var idx = {};
        head.forEach(function (h, i) { idx[h] = i; });

        out.hasHumidity = !!cHumi;
        var rows = [];
        for (var i = 1; i < lines.length; i++) {
            var c = splitCsv(lines[i]);
            if (c.length < 3) continue;
            var t = toDate(c[idx[cTime]]);
            if (!t) continue;
            rows.push({
                t: t,
                Celsius:  cTemp ? num(c[idx[cTemp]]) : null,
                Humidity: cHumi ? num(c[idx[cHumi]]) : null,
                DewPoint: cDew  ? num(c[idx[cDew]])  : null
            });
            if (!out.serial && cSer && c[idx[cSer]]) out.serial = c[idx[cSer]];
        }
        return rows;
    }

    /* ---------------------------------------------------------------
     * 2) role 자동배정
     *    - sterilizer     : PS/CHAMBER 헤더
     *      · 소수 스트림 = UseForCalc 'Y', 정수 스트림 = 'N'(보관만)
     *      · 정수만 단독이면 그걸 'Y'
     *    - easylog        : 멸균기 구간보다 앞 → precondition / 뒤 → aeration
     *      · 로거가 배치마다 들락거리므로 시리얼은 판별에 쓰지 않음(기록만)
     * --------------------------------------------------------------- */
    function assignRoles(parsedList) {
        var res = parsedList.map(function (p) {
            return { parsed: p, role: null, useForCalc: 'Y', detectedBy: 'auto', warn: [] };
        });

        var ster = res.filter(function (r) {
            return r.parsed.kind === 'sterilizer' || r.parsed.kind === 'sterilizer_raw';
        });
        ster.forEach(function (r) { r.role = 'sterilizer'; });

        var hasDecimal = ster.some(function (r) { return r.parsed.kind === 'sterilizer'; });
        ster.forEach(function (r) {
            if (hasDecimal && r.parsed.kind === 'sterilizer_raw') {
                r.useForCalc = 'N';   // 보조 스트림 — 보관만
            }
        });

        // 멸균기 구간 (여러 개면 합집합)
        var sFrom = null, sTo = null;
        ster.forEach(function (r) {
            if (!r.parsed.from) return;
            if (!sFrom || r.parsed.from < sFrom) sFrom = r.parsed.from;
            if (!sTo   || r.parsed.to   > sTo)   sTo   = r.parsed.to;
        });

        res.forEach(function (r) {
            if (r.role) return;
            var p = r.parsed;
            if (p.kind !== 'easylog') {
                r.role = 'etc'; r.useForCalc = 'N';
                if (p.kind === 'unknown') r.warn.push('형식을 알 수 없어 기타첨부로 분류');
                return;
            }
            if (!sFrom) {
                // 멸균기 로그가 아직 없음 → 컬럼 구성으로 추정만
                r.role = p.hasHumidity ? 'precondition' : 'aeration';
                r.detectedBy = 'manual';
                r.warn.push('멸균기 로그가 없어 컬럼 구성으로 추정했습니다. 확인해주세요.');
                return;
            }
            var overlap = (p.from <= sTo && p.to >= sFrom);
            if (overlap) {
                r.role = p.hasHumidity ? 'precondition' : 'aeration';
                r.detectedBy = 'manual';
                r.warn.push('멸균 구간과 시간이 겹칩니다. 전조절/통기를 확인해주세요.');
            } else if (p.to <= sFrom) {
                r.role = 'precondition';
            } else {
                r.role = 'aeration';
            }
        });
        return res;
    }

    /* ---------------------------------------------------------------
     * 3) 구간 자동검출
     *    ctx = { sterilizer:[parsed..], precondition:[..], aeration:[..] }
     *    반환 = { exposure:{from,to}, vacuum:{}, conditioning:{}, flush:{}, ... }
     * --------------------------------------------------------------- */
    function detectSegments(ctx, opts) {
        opts = opts || {};
        var seg = {};
        var s = pickRows(ctx.sterilizer);

        if (s.length) {
            var ps = s.map(function (r) { return r.PS; });
            var psMax = Math.max.apply(null, ps.filter(isNum));
            var psMinIdx = argMin(ps);
            var psMin = ps[psMinIdx];

            // 진공: 시작 ~ PS 최저점
            seg.vacuum = { from: s[0].t, to: s[psMinIdx].t };

            // 노출: PS >= max*ratio 인 최장 연속구간
            var thr = psMax * (opts.expRatio || CFG.expRatio);
            var run = longestRun(ps, function (v) { return isNum(v) && v >= thr; });
            if (run) seg.exposure = { from: s[run.s].t, to: s[run.e].t, _s: run.s, _e: run.e };

            // 조절: PS 최저점 "다음" 샘플 ~ 압력이 (최저+condRise) 넘기 직전
            //   최저점 자체는 진공(4번)의 산출값이자 가습 시작 전이므로 조절에서 제외한다.
            var condStart = Math.min(psMinIdx + 1, ps.length - 1);
            var condEnd = condStart;
            var rise = psMin + (opts.condRise || CFG.condRise);
            for (var i = condStart; i < ps.length; i++) {
                if (isNum(ps[i]) && ps[i] > rise) break;
                condEnd = i;
            }
            seg.conditioning = { from: s[condStart].t, to: s[condEnd].t };

            // 공기정화: 노출 종료 ~ 로그 끝
            if (seg.exposure) {
                seg.flush = { from: s[run.e].t, to: s[s.length - 1].t, _s: run.e, _e: s.length - 1 };
            }
        }

        // 전조절: 온도가 하한(기본 40℃) 도달한 시점 ~ 파일 끝(꼬리 절단)
        var pc = pickRows(ctx.precondition);
        if (pc.length) {
            var lo = (opts.precondTempLower !== undefined) ? opts.precondTempLower : 40;
            var startIdx = 0;
            for (var j = 0; j < pc.length; j++) {
                if (isNum(pc[j].Celsius) && pc[j].Celsius >= lo) { startIdx = j; break; }
            }
            // 문 개방 시 습도가 먼저 무너진다(온도는 열용량 때문에 늦게 따라옴)
            // → 절단 판정은 습도로만. 온도로 자르면 정상적인 온도 드리프트를 오절단함.
            var endIdx = trimTail(pc, startIdx, ['Humidity'], opts);
            seg.precond = { from: pc[startIdx].t, to: pc[endIdx].t };
            if (endIdx < pc.length - 1) seg.precond.trimmed = pc.length - 1 - endIdx;
        }

        // 통기: 파일 전 구간(꼬리 절단)
        var ae = pickRows(ctx.aeration);
        if (ae.length) {
            var aEnd = trimTail(ae, 0, ['Humidity'], opts);  // 통기 로거는 보통 온도만 → 절단 없음
            seg.aeration = { from: ae[0].t, to: ae[aEnd].t };
            if (aEnd < ae.length - 1) seg.aeration.trimmed = ae.length - 1 - aEnd;
        }

        return seg;
    }

    /* 꼬리 절단 — 챔버 문 개방/로거 회수 시점의 급변 샘플 제거.
     * 구간 중앙값에서 크게 벗어난 "끝부분"만 잘라낸다. 최대 tailTrimMaxPct 까지만. */
    function trimTail(rows, startIdx, cols, opts) {
        opts = opts || {};
        var band    = opts.tailTrimBand   !== undefined ? opts.tailTrimBand   : CFG.tailTrimBand;
        var minAbs  = opts.tailTrimMinAbs !== undefined ? opts.tailTrimMinAbs : CFG.tailTrimMinAbs;
        var maxPct  = opts.tailTrimMaxPct !== undefined ? opts.tailTrimMaxPct : CFG.tailTrimMaxPct;
        var last    = rows.length - 1;
        var n       = last - startIdx + 1;
        if (n < 20) return last;                 // 표본이 적으면 자르지 않는다
        var maxCut  = Math.floor(n * maxPct);
        var cutAt   = last;

        cols.forEach(function (col) {
            var vals = [];
            for (var i = startIdx; i <= last; i++) if (isNum(rows[i][col])) vals.push(rows[i][col]);
            if (vals.length < 20) return;
            var m = median(vals);
            var tol = Math.max(Math.abs(m) * band, minAbs);
            var k = last;
            while (k > last - maxCut && isNum(rows[k][col]) && Math.abs(rows[k][col] - m) > tol) k--;
            if (k < cutAt) cutAt = k;
        });
        return cutAt;
    }

    function isNum(v) { return v !== null && v !== undefined && !isNaN(v); }
    function argMin(a) {
        var bi = -1, bv = Infinity;
        for (var i = 0; i < a.length; i++) if (isNum(a[i]) && a[i] < bv) { bv = a[i]; bi = i; }
        return bi;
    }
    function longestRun(a, pred) {
        var best = null, s = -1;
        for (var i = 0; i <= a.length; i++) {
            var ok = (i < a.length) && pred(a[i]);
            if (ok && s < 0) s = i;
            if (!ok && s >= 0) {
                var run = { s: s, e: i - 1, len: i - s };
                if (!best || run.len > best.len) best = run;
                s = -1;
            }
        }
        return best;
    }
    // 여러 파일(로거 N개)의 행을 합침. UseForCalc='N' 은 호출부에서 이미 제외
    function pickRows(list) {
        if (!list || !list.length) return [];
        if (list.length === 1) return list[0].rows;
        var all = [];
        list.forEach(function (p) { all = all.concat(p.rows); });
        all.sort(function (a, b) { return a.t - b.t; });
        return all;
    }

    /* ---------------------------------------------------------------
     * 4) 필드 자동산출
     *    fields : steril_form_field 행 배열
     *    ctx    : { sterilizer:[], precondition:[], aeration:[], segments:{} }
     * --------------------------------------------------------------- */
    function computeFields(fields, ctx) {
        var out = {};
        fields.forEach(function (f) {
            var r = { key: f.FieldKey, value: null, source: f.SourceType,
                      auto: false, spec: null, note: null };

            if (f.SourceType === 'manual') { out[f.FieldKey] = r; return; }

            var seg = ctx.segments[f.SegmentKey];
            if (!seg) { r.note = '구간 미검출'; out[f.FieldKey] = r; return; }

            var list = ctx[f.SourceRole] || ctx[f.SourceType === 'sterilizer' ? 'sterilizer' : f.SourceRole];
            var rows = sliceByTime(pickRows(list), seg.from, seg.to);
            if (!rows.length) { r.note = '구간 내 데이터 없음'; out[f.FieldKey] = r; return; }

            var interval = medianInterval(rows);
            var v = null;

            switch (f.AggFunc) {
                case 'first':    v = fmtDt(rows[0].t); break;
                case 'last':     v = fmtDt(rows[rows.length - 1].t); break;
                case 'duration':
                    // 마지막 샘플도 1구간을 대표하므로 interval 을 더한다
                    v = round((rows[rows.length - 1].t - rows[0].t) / 3600000 + (interval / 3600), 2);
                    break;
                case 'count_valley':
                    v = countValleys(rows.map(function (x) { return x[f.SourceCol]; }));
                    break;
                default: {
                    var vals = rows.map(function (x) { return x[f.SourceCol]; }).filter(isNum);
                    if (!vals.length) { r.note = '해당 컬럼 값 없음'; break; }
                    if (f.AggFunc === 'min') v = Math.min.apply(null, vals);
                    else if (f.AggFunc === 'max') v = Math.max.apply(null, vals);
                    else if (f.AggFunc === 'avg') v = round(vals.reduce(function (a, b) { return a + b; }, 0) / vals.length, 1);
                }
            }

            r.value = v;
            r.auto  = (v !== null);

            // 규격 판정 (막지 않는다. 표시만)
            if (isNum(v) && (f.SpecLower !== null || f.SpecUpper !== null)) {
                var lo = f.SpecLower, hi = f.SpecUpper, ok = true;
                if (lo !== null && lo !== undefined && v < lo) ok = false;
                if (hi !== null && hi !== undefined && v > hi) ok = false;
                r.spec = ok ? 'ok' : 'out';
            }
            out[f.FieldKey] = r;
        });
        return out;
    }

    function sliceByTime(rows, from, to) {
        return rows.filter(function (r) { return r.t >= from && r.t <= to; });
    }
    function medianInterval(rows) {
        var g = [];
        for (var i = 1; i < Math.min(rows.length, 60); i++) g.push((rows[i].t - rows[i - 1].t) / 1000);
        return median(g) || 0;
    }
    function round(v, d) { var m = Math.pow(10, d); return Math.round(v * m) / m; }

    // 플러싱 횟수 = 국소 최저점 개수 (이후 상승폭이 valleyRise 이상인 것만)
    function countValleys(a, minRise) {
        minRise = minRise || CFG.valleyRise;
        var n = 0;
        for (var i = 1; i < a.length - 1; i++) {
            if (!isNum(a[i]) || !isNum(a[i - 1]) || !isNum(a[i + 1])) continue;
            if (a[i] <= a[i - 1] && a[i] < a[i + 1]) {
                var peak = a[i];
                for (var j = i + 1; j < a.length; j++) {
                    if (!isNum(a[j])) continue;
                    if (a[j] < a[j - 1]) break;
                    if (a[j] > peak) peak = a[j];
                }
                if (peak - a[i] >= minRise) n++;
            }
        }
        return n;
    }

    /* --------------------------------------------------------------- */
    return {
        CFG: CFG,
        parse: parse,
        assignRoles: assignRoles,
        detectSegments: detectSegments,
        computeFields: computeFields,
        _util: { fmtDt: fmtDt, pickRows: pickRows, countValleys: countValleys }
    };
}));
