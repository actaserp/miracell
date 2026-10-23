/*!
 * M-CELL \uc81c\uc870\ubb38\uc11c (2\uacf5\uc7a5) \u2014 \uc5d1\uc140 \uc591\uc2dd \ucc44\uc6b0\uae30 \u00b7 \uc778\uc1c4 \u00b7 \ucca8\ubd80
 *
 *   McellDoc.open({unitId: 12})                 \uc720\ub2db id \ub85c
 *   McellDoc.open({lot: 'M4A-2610-001'})         \ub85c\ud2b8\ubc88\ud638\ub85c
 *   McellDoc.open({unitId: 12, docs: ['F803-6']}) \ud2b9\uc815 \ubb38\uc11c\ub9cc
 *
 * \ubb38\uc11c 4\uc885 (\ubaa8\ub378 M2 / M4 / M4A \ubcc4 \uc591\uc2dd)
 *   F705-33 \uc81c\uc870\uacf5\uc815\uad00\ub9ac \u00b7 F803-6 \uacf5\uc815\uac80\uc0ac \u00b7 F803-7 \uc644\uc81c\ud488\uac80\uc0ac \u00b7 F803-8 \ucd5c\uc885\uac80\uc0ac
 *
 * \u2605 \uc5d1\uc140\uc740 \uc591\uc2dd \ud30c\uc77c(/template)\uc758 \uc140 \uac12\ub9cc \ubc14\uafd4 \ub0b4\ub824\ubc1b\ub294\ub2e4(JSZip \uc73c\ub85c XML \uc9c1\uc811 \uc218\uc815).
 *   \uadf8\ub798\uc11c \uc11c\uc2dd\u00b7\ubcd1\ud569\u00b7\uadf8\ub9bc\u00b7\uc778\uc1c4\uc124\uc815\uc774 \uadf8\ub300\ub85c \ub0a8\ub294\ub2e4. \ub77c\uc774\ube0c\ub7ec\ub9ac\ub85c \uc5f4\uc5c8\ub2e4 \ub2e4\uc2dc \uc4f0\uba74 \uadf8\ub9bc\uc774 \ube60\uc9c4\ub2e4.
 * \u2605 \uc140 \uc704\uce58(LAYOUT)\ub294 \uc591\uc2dd \uc5d1\uc140\uc5d0\uc11c \ubf51\uc544 \ub454 \uac83\uc774\ub2e4. \uc591\uc2dd\uc774 \ubc14\ub00c\uba74 LAYOUT \uc744 \ub2e4\uc2dc \ub9cc\ub4e4\uc5b4\uc57c \ud55c\ub2e4.
 */
(function (global) {
    'use strict';

    var API = '/api/production/mcell/doc';
    var JSZIP_URL = 'https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js';
    var DOC_NAMES = {
        'F705-33': '\uc81c\uc870\uacf5\uc815\uad00\ub9ac',
        'F803-6': '\uacf5\uc815\uac80\uc0ac CHECK SHEET',
        'F803-7': '\uc644\uc81c\ud488\uac80\uc0ac CHECK SHEET',
        'F803-8': '\ucd5c\uc885\uac80\uc0ac CHECK SHEET'
    };
    var DOC_ORDER = ['F705-33', 'F803-6', 'F803-7', 'F803-8'];
    /* \uc644\uc81c\ud488(\uc720\ub2db) \ud488\ubaa9 \u2192 \ubaa8\ub378. \uc5c6\uc73c\uba74 \ud488\uba85\uc73c\ub85c \ud310\ub2e8 */
    var MODEL_BY_CODE = {'WIP-MC20022N': 'M2', 'WIP-MC60066': 'M4', 'WIP-MC4144': 'M4A', 'WIP-MC4244': 'M4A'};   // M4A(N) 은 M4A 문서



    // \u2500\u2500 \uacf5\ud1b5 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c];
        });
    }
    function toast(m) {
        var t = document.getElementById('mdocToast');
        if (!t) { t = document.createElement('div'); t.id = 'mdocToast'; t.className = 'mdoc-toast'; document.body.appendChild(t); }
        t.textContent = m; t.classList.add('on');
        clearTimeout(t._h); t._h = setTimeout(function () { t.classList.remove('on'); }, 2600);
    }
    function csrf() {
        var el = document.querySelector('[name=_csrf]');
        return el ? el.value : '';
    }
    function getJson(path, params) {
        var q = Object.keys(params || {}).filter(function (k) { return params[k] != null && params[k] !== ''; })
            .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
        return fetch(API + path + (q ? '?' + q : ''), {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(function (r) { return r.json(); })
            .then(function (r) { if (r && r.success === false) throw new Error(r.message || '\uc870\ud68c \uc2e4\ud328'); return r ? r.data : null; });
    }
    function postForm(path, fd) {
        if (!(fd instanceof FormData)) {
            var f = new FormData();
            Object.keys(fd || {}).forEach(function (k) { if (fd[k] != null) f.append(k, fd[k]); });
            fd = f;
        }
        var tk = csrf();
        if (tk) fd.append('_csrf', tk);
        return fetch(API + path, {method: 'POST', credentials: 'same-origin', body: fd,
            headers: tk ? {'X-CSRF-TOKEN': tk, 'Accept': 'application/json'} : {'Accept': 'application/json'}})
            .then(function (r) { return r.json(); })
            .then(function (r) { if (r && r.success === false) throw new Error(r.message || '\ucc98\ub9ac \uc2e4\ud328'); return r; });
    }
    function loadScript(url) {
        return new Promise(function (ok, bad) {
            if (global.JSZip) return ok();
            var s = document.createElement('script');
            s.src = url; s.onload = function () { ok(); }; s.onerror = function () { bad(new Error('JSZip \ub85c\ub4dc \uc2e4\ud328 (\uc778\ud130\ub137 \uc5f0\uacb0 \ud655\uc778)')); };
            document.head.appendChild(s);
        });
    }
    function modelOf(unit) {
        if (!unit) return null;
        if (MODEL_BY_CODE[unit.mat_code]) return MODEL_BY_CODE[unit.mat_code];
        var t = String((unit.mat_name || '') + ' ' + (unit.mat_code || '')).toUpperCase().replace(/\s/g, '');
        if (/CELL4-?A|M4-?A/.test(t)) return 'M4A';
        if (/CELL4|M4/.test(t)) return 'M4';
        if (/CELL2|M2/.test(t)) return 'M2';
        return null;
    }
    function judgeTxt(r) {
        return r === 'pass' ? '\ud569\uaca9' : r === 'fail' ? '\ubd88\ud569\uaca9' : r === 'na' ? '\ud574\ub2f9\uc5c6\uc74c' : '';
    }

    // \u2500\u2500 \ub370\uc774\ud130 \u2192 \uc140 \uac12 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    /* \uc870\ub9bd \ube14\ub85d(\uc81c\uc870\uacf5\uc815\uad00\ub9ac \uacf5\uc815\uba85) \u2194 \uc870\ub9bd \uc2a4\ud15d(\ud488\uba85) \ub9e4\uce6d \uaddc\uce59. \uc704\uc5d0\uc11c\ubd80\ud130 \uba3c\uc800 \ub9de\ub294 \uac83 */
    var STEP_RULES = [
        {blk: /\ud78c\uc9c0/, step: /\ud78c\uc9c0/},
        {blk: /Right/i, step: /Right|\uc6b0\uce21/i},
        {blk: /Left/i, step: /Left|\uc88c\uce21/i},
        {blk: /SMPS/i, step: /SMPS/i},
        {blk: /LCD/i, step: /LCD|\ubaa8\ub2c8\ud130/i, not: /\ud78c\uc9c0/},
        {blk: /\ucc54\ubc84/, step: /\ucc54\ubc84/},
        {blk: /\ub85c\ud130|ROTOR/i, step: /\ub85c\ud130|ROTOR/i},
        {blk: /\ud310\uae08/, step: /Lower|\ud310\uae08/i},
        {blk: /\ubcf8\uccb4\s*COVER/i, step: /Upper|\ucee4\ubc84|COVER/i},
        {blk: /COVER/i, step: /Upper|\ucee4\ubc84|COVER/i},
        {blk: /\ubcf8\uccb4/, step: /\ubcf8\uccb4/}
    ];
    function matchStep(blockName, steps, used) {
        for (var i = 0; i < STEP_RULES.length; i++) {
            var rule = STEP_RULES[i];
            if (!rule.blk.test(blockName)) continue;
            var s = steps.filter(function (x) {
                return !used[x.step_id] && rule.step.test(x.mat_name || '') && !(rule.not && rule.not.test(x.mat_name || ''));
            })[0];
            if (s) { used[s.step_id] = 1; return s; }
            return null;
        }
        return null;
    }
    function procForm(D, code) {
        return (D.proc_insp || []).filter(function (x) { return x.form_code === code; })[0];
    }
    /* ★ 조립 화면에서 주요자재별로 입력한 부품 LOT (mcell_unit_sn.PartLotNo) — 같은 자재 n번째 칸 → n번 */
    var CUR_D = null;
    function partLot(mid, n) {
        var l = ((CUR_D && CUR_D.sns) || []).filter(function (x) { return +x.mat_id === +mid && x.part_lot; });
        var x = l.filter(function (y) { return +y.seq_no === n; })[0] || l[n - 1] || (n > 1 ? null : l[0]);
        return x ? x.part_lot : '';
    }
    function finWho(fc) {   // ★ 최종검사자 = 체크에 기록된 검사자, 없으면 포장 작업자
        var a = (fc || []).filter(function (x) { return x.actor_name; })[0];
        return a ? a.actor_name : '';
    }
    function finalForm(D, code) {
        return (D.final_insp || []).filter(function (x) { return x.form_code === code; })[0];
    }
    function packChecks(D, code) {
        return ((D.pack && D.pack.checks) || []).filter(function (x) { return x.form_code === code; });
    }

    /** \ubb38\uc11c \ud558\ub098\uc758 {\uc140\uc8fc\uc18c: \uac12} */
    /* \u2605 \uac80\uc0ac \uc591\uc2dd \ud654\uba74\uc5d0\uc11c \uad00\ub9ac\ud558\ub294 \uc5d1\uc140 \ub9e4\ud551(doc_forms)\uc73c\ub85c \uce78\uc744 \ucc44\uc6b4\ub2e4.
         \uadf8 \ubb38\uc11c\u00b7\ubaa8\ub378\uc5d0 \ub9e4\ud551\ub41c \uc591\uc2dd\uc774 \uc5c6\uc73c\uba74 null \u2192 \uc544\ub798 \ub0b4\uc7a5 \uce78 \uc704\uce58(LAYOUT)\ub85c \ucc44\uc6b4\ub2e4. */
    function pcm(v) { if (!v) return {}; if (typeof v === 'object') return v; try { return JSON.parse(v); } catch (e) { return {}; } }
    function arr(v) { return v == null ? [] : (Array.isArray(v) ? v : [v]); }
    /* \u2605 \uc131\uc801\uc11c \ubc88\ud638 = \uc7a5\ube44\ubc88\ud638(S/N) \ub05d 6\uc790\ub9ac (\uc608: M4FO10010091 \u2192 010091). S/N \uc5c6\uc73c\uba74 \ub85c\ud2b8\ubc88\ud638 */
    function refNo(D) {
        var sn = String((D.unit && D.unit.serial_no) || '').replace(/\s/g, '');
        if (sn && !/^SN-/i.test(sn)) return sn.length > 6 ? sn.slice(-6) : sn;
        return '';   // ★ 최종 장비번호(S/N) 부여 전에는 성적서 번호를 비워 둔다 (임시 SN-xxx · 로트번호 쓰지 않음)
    }
    function refTxt(prefix, D) { var n = refNo(D); return n ? prefix + n : ''; }
    function verOf(doc, model, D) { return (D.doc_ver || {})[model + '|' + doc] || null; }
    function cellsFromForms(doc, model, D) {
        // \u2605 \uc591\uc2dd \ubc84\uc804 \u2014 \uc774 \uc720\ub2db\uc774 \uc0dd\uc0b0\ub420 \ub54c \uc720\ud6a8\ud588\ub358 \ubc84\uc804\uc774 \uc608\uc804 \ubc84\uc804\uc774\uba74 \uadf8 \ub54c \uace0\uc815\ud574 \ub454 \ub9e4\ud551\uc744 \uc4f4\ub2e4
        var ver = verOf(doc, model, D), src = D.doc_forms || [];
        if (ver && ver.map_json) { var vm = pcm(ver.map_json); if (Array.isArray(vm)) src = vm; }
        var forms = src.filter(function (f) { return f.doc_code === doc && (!f.model || f.model === model); });
        if (!forms.length) return null;
        var lot = D.unit.lot_number || '', c = {};
        function put(ref, v) { if (ref && v != null && v !== '') c[String(ref).toUpperCase()] = String(v); }
        forms.forEach(function (f) {
            var h = pcm(f.cell_map);
            if (h.lot) put(h.lot, doc === 'F705-33' ? lot : 'Lot No. : ' + lot);
            if (h.ref) put(h.ref, refTxt(h.refPrefix || '', D));
        });
        forms.forEach(function (f) {
            var items = f.items || [];
            if (doc === 'F803-6' || doc === 'F803-7') {
                var r = doc === 'F803-6' ? procForm(D, f.form_code) : finalForm(D, f.form_code);
                if (!r) return;
                items.forEach(function (it) {
                    var m = pcm(it.cell_map), J = arr(m.judge), V = arr(m.val), Dt = arr(m.date), W = arr(m.who);
                    var n = Math.max(J.length, V.length, 1);
                    for (var k = 0; k < n; k++) {
                        var res = (r.items || []).filter(function (x) {
                            return +x.seq_no === +it.seq_no && (x.repeat_no == null || +x.repeat_no === k + 1);
                        })[0];
                        if (!res) continue;
                        put(V[k], res.value); put(J[k], judgeTxt(res.result));
                        put(Dt[k] || Dt[0], r.end_date); put(W[k] || W[0], res.actor_name || r.actor_name);
                    }
                });
            }
            if (doc === 'F803-8') {
                var ch = packChecks(D, f.form_code);
                items.forEach(function (it) {
                    var m = pcm(it.cell_map), res = ch.filter(function (x) { return +x.seq_no === +it.seq_no; })[0];
                    if (!res) return;
                    put(arr(m.val)[0], res.value); put(arr(m.judge)[0], judgeTxt(res.result));
                    put(arr(m.date)[0], D.pack && D.pack.end_date); put(arr(m.who)[0], D.pack && D.pack.actor_name);
                });
            }
            if (doc === 'F705-33') { CUR_D = D;
                var ptr = {}, fis = D.final_insp || [];
                items.forEach(function (it) {
                    var m = pcm(it.cell_map), k = m.kind || 'asm';
                    if (k === 'asm') {
                        /* ★ 같은 양식 줄을 다른 모델 어셈블리도 쓴다 (예: M4A(N) 챔버 WIP-MA4207 → M4A 챔버 줄) — CellMap.altMat */
                        var asmIds = [+it.material_id].concat(arr(m.altMat).map(Number));
                        var s = (D.steps || []).filter(function (x) { return asmIds.indexOf(+x.mat_id) >= 0 && (x.state === 'done' || x.source === 'stock'); })[0];
                        /* ★ 지정 자재가 이 스텝에 없으면 하위 반제품 속 → 다른 스텝 순으로 찾는다
                             (예: 로터 Ass'y 안의 RT보드, 본체 조립에서 투입한 사이드 커버) */
                        var deep = D.deep_mats || [], lotMat0 = m.lotMat || {};
                        function findDeep(mid) {
                            var c = deep.filter(function (y) { return +y.mat_id === +mid && s && +y.step_id === +s.step_id; })[0]
                                || deep.filter(function (y) { return +y.mat_id === +mid; })[0];
                            return c;
                        }
                        if (!s) {
                            // 양식 줄에 어셈블리가 없거나 스텝이 없으면 — 지정 자재가 들어간 스텝으로 대신한다
                            var hit = null;
                            arr(m.lots).forEach(function (ref) {
                                var mid = lotMat0[ref]; if (!mid) return;
                                var c = findDeep(mid), pl = partLot(mid, 1);
                                if (pl) { put(ref, 'LOT ' + pl); return; }
                                if (!c) return;
                                put(ref, 'LOT ' + shortLots(c.lots));
                                hit = hit || (D.steps || []).filter(function (x) { return +x.step_id === +c.step_id; })[0];
                            });
                            if (hit) { put(m.date, hit.end_date); put(m.who, hit.actor_name); }
                            return;
                        }
                        put(m.date, s.end_date); put(m.who, s.actor_name);
                        var ids = {};
                        (s.mats || []).forEach(function (x) { ids[x.mat_id] = 1; });
                        /* \u2605 \uc8fc\uc694\uc790\uc7ac \ub9e4\uce6d(lotMat/snMat : \uce78 \u2192 \uc790\uc7ac) \uc774 \uc788\uc73c\uba74 \uadf8 \uc790\uc7ac\ub97c \uadf8 \uce78\uc5d0.
                             \ub9e4\uce6d \uc548 \ub41c \uce78\uc740 \ub0a8\uc740 \uc790\uc7ac\ub97c \uc21c\uc11c\ub300\ub85c \ucc44\uc6b4\ub2e4. */
                        var lotMat = m.lotMat || {}, snMat = m.snMat || {};
                        var mats = (s.mats || []).filter(function (x) { return !x.is_asm && x.lots; });
                        var usedL = {};
                        var plSeq = {};
                        arr(m.lots).forEach(function (ref) {
                            var mid = lotMat[ref]; if (!mid) return;
                            var pn = plSeq[mid] = (plSeq[mid] || 0) + 1, pl = partLot(mid, pn);
                            if (pl) { put(ref, 'LOT ' + pl); usedL[mid] = 1; return; }   // ★ 조립 때 입력한 부품 LOT 우선
                            var x = mats.filter(function (y) { return +y.mat_id === +mid; })[0];
                            if (x) { put(ref, 'LOT ' + shortLots(x.lots)); usedL[x.mat_id] = 1; return; }
                            var c = findDeep(mid);
                            if (c) put(ref, 'LOT ' + shortLots(c.lots));
                        });
                        /* \ub9e4\uce6d \uc548 \ub41c LOT \uce78 : \uc8fc\uc694\uc790\uc7ac(S/N \uad00\ub9ac \uc790\uc7ac)\ub9cc \uc21c\uc11c\ub300\ub85c. \ubcfc\ud2b8 \uac19\uc740 \uc77c\ubc18 \uc790\uc7ac\ub294 \ub123\uc9c0 \uc54a\ub294\ub2e4.
                           \uc815\ud655\ud788 \ud558\ub824\uba74 \uc591\uc2dd \ud654\uba74 \u00ab\uc8fc\uc694\uc790\uc7ac \ub9e4\uce6d\u00bb \uc5d0\uc11c \uce78\ub9c8\ub2e4 \uc790\uc7ac\ub97c \uace0\ub978\ub2e4. */
                        fillList(arr(m.lots).filter(function (ref) { return !lotMat[ref]; }),
                            mats.filter(function (x) { return !usedL[x.mat_id] && x.sn_yn === 'Y'; }).map(function (x) { return 'LOT ' + shortLots(x.lots); }), put);
                        var snList = (D.sns || []).filter(function (x) { return x.serial_no && (+x.step_id === +s.step_id || ids[x.mat_id]); });
                        var usedS = {}, seqOf = {};
                        arr(m.sns).forEach(function (ref) {
                            var mid = snMat[ref]; if (!mid) return;
                            var n = seqOf[mid] = (seqOf[mid] || 0) + 1;          // \uac19\uc740 \uc790\uc7ac \uce78\uc774 \uc5ec\ub7ec \uac1c\uba74 \uc21c\ubc88 1, 2 \u2026
                            var x = snList.filter(function (y) { return +y.mat_id === +mid && +y.seq_no === n; })[0]
                                || snList.filter(function (y) { return +y.mat_id === +mid && !usedS[y.mat_id + '/' + y.seq_no]; })[0];
                            if (x) { put(ref, 'S/N ' + x.serial_no); usedS[x.mat_id + '/' + x.seq_no] = 1; }
                        });
                        fillList(arr(m.sns).filter(function (ref) { return !snMat[ref]; }),
                            snList.filter(function (x) { return !usedS[x.mat_id + '/' + x.seq_no]; }).map(function (x) { return 'S/N ' + x.serial_no; }), put);
                    } else if (k === 'pinsp') {
                        put(m.ref, refTxt('\uacf5\uc815-', D));
                        var p = procForm(D, m.form);
                        if (p) { put(m.judge, judgeTxt(p.verdict)); put(m.date, p.end_date); put(m.who, p.actor_name); }
                    } else if (k === 'finsp') {
                        put(m.ref, refTxt('\uc644\uc81c\ud488-', D));
                        if (fis.length) {
                            put(m.judge, fis.every(function (x) { return x.verdict === 'pass'; }) ? '\ud569\uaca9' : '\ubd88\ud569\uaca9');
                            put(m.date, fis.map(function (x) { return x.end_date || ''; }).sort().pop()); put(m.who, fis[0].actor_name);
                        }
                    } else if (k === 'final') {
                        put(m.ref, refTxt('\ucd5c\uc885-', D));
                        var fc = packChecks(D, m.form || 'FP-ALL-FINAL');
                        if (fc.length) {
                            put(m.judge, fc.some(function (x) { return x.result === 'fail'; }) ? '\ubd88\ud569\uaca9' : '\ud569\uaca9');
                            put(m.date, D.pack.end_date); put(m.who, finWho(fc) || D.pack.actor_name);
                        }
                    } else if (k === 'pack') {
                        // \u2605 \uc7ac\uacf5\ud488 \uccb4\ud06c\ub294 \uc5d1\uc140\uc5d0 \uce78\uc774 \uc5c6\ub2e4(\u00ab-\u00bb) \u2014 \uc21c\uc11c\ub300\ub85c \uce78\uc744 \ucc44\uc6b8 \ub54c \ube7c\uc57c \ub4a4 \uce78\uc774 \ubc00\ub9ac\uc9c0 \uc54a\ub294\ub2e4
                        var pc = packChecks(D, m.form).filter(function (x) { return !/\uc7ac\uacf5\ud488/.test(x.item_name || ''); });
                        if (pc.length && D.pack) { put(m.date, D.pack.end_date); put(m.who, D.pack.actor_name); }
                        ptr[m.form] = ptr[m.form] || 0;
                        arr(m.eas).forEach(function (ref) {
                            var x = pc[ptr[m.form]++];
                            if (!x) return;
                            if (x.result === 'na') { put(ref, '\ud574\ub2f9\uc5c6\uc74c'); return; }
                            var isLot = /\ub77c\ubca8/.test(x.item_name) && /\uad6d\ubb38|\uc601\ubb38/.test(x.item_name) && !/2EA/.test(x.item_name);
                            put(ref, isLot ? 'Lot No: ' + (x.value || '') + (x.result === 'pass' ? ' \u2611' : '')
                                : (x.value || '') + ' EA' + (x.result === 'pass' ? ' \u2611' : ''));
                        });
                    }
                });
            }
        });
        return c;
    }

    /* \u2605 LAYOUT(\ub0b4\uc7a5 \uce78 \uc704\uce58)\uc740 \uc591\uc2dd \ud654\uba74 \ub9e4\ud551\uc73c\ub85c \uc62e\uaca8\uc838 \uc5c6\uc744 \uc218 \uc788\ub2e4 \u2014 \uc5c6\uc73c\uba74 \ube48 \uac1d\uccb4 */
    function layoutOf(model, doc) {
        var L = (typeof LAYOUT !== 'undefined' && LAYOUT) ? LAYOUT : null;
        return (L && L[model] && L[model][doc]) || {};
    }

    function cellsFor(doc, model, D) {
        var g = cellsFromForms(doc, model, D);
        if (g && Object.keys(g).length) return g;
        // \u2605 \ub0b4\uc7a5 \uce78 \uc704\uce58\uac00 \uc5c6\uc73c\uba74 \ube48 \uc591\uc2dd \uadf8\ub300\ub85c
        if (!layoutOf(model, doc).groups && !layoutOf(model, doc).lot) return g || {};
        var L = layoutOf(model, doc), lot = D.unit.lot_number || '', c = {};
        function put(ref, v) { if (ref && v != null && v !== '') c[ref] = String(v); }

        if (doc === 'F803-6') {
            put(L.lot, 'Lot No. : ' + lot); put(L.ref, refTxt('\uacf5\uc815-', D));
            L.groups.forEach(function (g) {
                var f = procForm(D, 'PI-' + model + '-' + g.code.replace('#', ''));
                if (!f) return;
                g.rows.forEach(function (row, k) {
                    var it = (f.items || []).filter(function (x) { return +x.seq_no === k + 1; })[0] || {};
                    put(L.cols.val + row, it.value);
                    put(L.cols.judge + row, judgeTxt(it.result));
                    put(L.cols.date + row, f.end_date);
                    put(L.cols.who + row, f.actor_name);
                });
            });
        }

        if (doc === 'F803-7') {
            put(L.lot, 'Lot No. : ' + lot); put(L.ref, refTxt('\uc644\uc81c\ud488-', D));
            L.groups.forEach(function (g) {
                var f = finalForm(D, g.code);
                if (!f) return;
                g.items.forEach(function (rows, k) {
                    rows.forEach(function (row, rep) {
                        var it = (f.items || []).filter(function (x) { return +x.seq_no === k + 1 && +x.repeat_no === rep + 1; })[0];
                        if (!it) return;
                        put(L.cols.val + row, it.value);
                        put(L.cols.judge + row, judgeTxt(it.result));
                        put(L.cols.date + row, f.end_date);
                        put(L.cols.who + row, it.actor_name || f.actor_name);
                    });
                });
            });
        }

        if (doc === 'F803-8') {
            put(L.lot, 'Lot No. : ' + lot); put(L.ref, refTxt('\ucd5c\uc885-', D));
            var ch = packChecks(D, 'FP-ALL-FINAL');
            L.rows.forEach(function (row, k) {
                var it = ch.filter(function (x) { return +x.seq_no === k + 1; })[0];
                if (!it) return;
                if (k < 5) put(L.cols.val + row, it.value);        // 6\u00b77\ud589(Check List)\uc740 \uc591\uc2dd\uc758 \uccb4\ud06c \ubb38\uad6c\ub97c \uc9c0\ud0a4\ub824\uace0 \ube44\uc6b4\ub2e4
                put(L.cols.judge + row, judgeTxt(it.result));
                put(L.cols.date + row, D.pack.end_date);
                put(L.cols.who + row, it.actor_name || D.pack.actor_name);
            });
        }

        if (doc === 'F705-33') {
            put(L.lot, lot);
            var used = {}, steps = (D.steps || []).filter(function (s) { return s.state === 'done' || s.source === 'stock'; });
            var packItems = packChecks(D, 'FP-' + model + '-PACK'), pk = 0;
            var allFinal = (D.final_insp || []), finalPass = allFinal.length && allFinal.every(function (x) { return x.verdict === 'pass'; });
            var fChecks = packChecks(D, 'FP-ALL-FINAL');
            L.blocks.forEach(function (b) {
                if (b.kind === 'asm') {
                    var s = matchStep(b.name, steps, used);
                    if (!s) return;
                    // \u2605 F\uc5f4(\uc644\uc131\ud488)\uc740 \uc591\uc2dd\uc758 \uae30\uc900 \uc0ac\uc9c4 \uc790\ub9ac\ub2e4 \u2014 \uac12\uc744 \uc4f0\uc9c0 \uc54a\ub294\ub2e4
                    put('G' + b.row, s.end_date);
                    put('I' + b.row, s.actor_name);
                    var mats = (s.mats || []).filter(function (m) { return !m.is_asm; });
                    var lots = mats.filter(function (m) { return m.lots && m.sn_yn === 'Y'; }).map(function (m) { return 'LOT ' + shortLots(m.lots); });   // \u2605 \uc591\uc2dd \uce78\uc5d0\ub294 \ubc88\ud638\ub9cc
                    var matIds = {};
                    (s.mats || []).forEach(function (m) { matIds[m.mat_id] = 1; });
                    var sns = (D.sns || []).filter(function (x) { return +x.step_id === +s.step_id || matIds[x.mat_id]; })
                        .map(function (x) { return 'S/N ' + x.serial_no; });
                    fillList(b.lots, lots, put);
                    fillList(b.sns, sns, put);
                } else if (b.kind === 'pinsp') {
                    var f = procForm(D, 'PI-' + model + '-' + b.code.replace('#', ''));
                    put('E' + b.row, refTxt('\uacf5\uc815-', D));
                    if (f) { put('F' + b.row, judgeTxt(f.verdict)); put('G' + b.row, f.end_date); put('I' + b.row, f.actor_name); }
                } else if (b.kind === 'finsp') {
                    put('E' + b.row, refTxt('\uc644\uc81c\ud488-', D));
                    if (allFinal.length) {
                        put('F' + b.row, finalPass ? '\ud569\uaca9' : '\ubd88\ud569\uaca9');
                        put('G' + b.row, allFinal.map(function (x) { return x.end_date || ''; }).sort().pop());
                        put('I' + b.row, allFinal[0].actor_name);
                    }
                } else if (b.kind === 'final') {
                    put('E' + b.row, refTxt('\ucd5c\uc885-', D));
                    if (fChecks.length) {
                        put('F' + b.row, fChecks.some(function (x) { return x.result === 'fail'; }) ? '\ubd88\ud569\uaca9' : '\ud569\uaca9');
                        put('G' + b.row, D.pack.end_date); put('I' + b.row, finWho(fChecks) || D.pack.actor_name);
                    }
                } else if (b.kind === 'pack') {
                    if (D.pack && packItems.length) { put('G' + b.row, D.pack.end_date); put('I' + b.row, D.pack.actor_name); }
                    b.eas.forEach(function (ref) {
                        var it = packItems[pk++];
                        if (!it) return;
                        if (it.result === 'na') { put(ref, '\ud574\ub2f9\uc5c6\uc74c'); return; }
                        var isLot = /\ub77c\ubca8/.test(it.item_name) && /\uad6d\ubb38|\uc601\ubb38/.test(it.item_name) && !/2EA/.test(it.item_name);
                        put(ref, isLot ? 'Lot No: ' + (it.value || '') + (it.result === 'pass' ? ' \u2611' : '')
                            : (it.value || '') + ' EA' + (it.result === 'pass' ? ' \u2611' : ''));
                    });
                }
            });
        }
        return c;
    }
    /** \uce78\uc774 \ubaa8\uc790\ub77c\uba74 \ub9c8\uc9c0\ub9c9 \uce78\uc5d0 \uc774\uc5b4 \ubd99\uc778\ub2e4 */
    /** \ud55c \uc790\uc7ac\uac00 \uc5ec\ub7ec \ub85c\ud2b8\uc5d0\uc11c \ube60\uc84c\uc73c\uba74 \uccab \ub85c\ud2b8 + \u00ab\uc678 n\u00bb */
    function shortLots(l) {
        var a = String(l || '').split(/\s*,\s*/).filter(Boolean);
        return a.length > 1 ? a[0] + ' \uc678 ' + (a.length - 1) : (a[0] || '');
    }
    function fillList(refs, vals, put) {
        if (!refs.length || !vals.length) return;
        // \u2605 \uce78 \ud558\ub098\uc5d0 \uac12 \ud558\ub098 \u2014 \ub118\uce58\ub294 \uac74 \ubc84\ub9b0\ub2e4(\uc608\uc804\uc5d4 \ub9c8\uc9c0\ub9c9 \uce78\uc5d0 \uc774\uc5b4 \ubd99\uc5ec \uae00\uc790\uac00 \uc798\ub838\ub2e4)
        refs.forEach(function (ref, i) { if (i < vals.length) put(ref, vals[i]); });
    }

    // \u2500\u2500 xlsx XML \uc9c1\uc811 \uc218\uc815 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    var NS_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
    function colNum(letters) {
        var n = 0;
        for (var i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) - 64);
        return n;
    }
    function splitRef(ref) {
        var m = /^([A-Z]+)(\d+)$/.exec(ref);
        return {col: m[1], row: +m[2], cn: colNum(m[1])};
    }
    /* \u2605 \uae00\uc790 \uc798\ub9bc \ubc29\uc9c0 \u2014 \uac12\uc744 \uc4f4 \uce78\uc758 \uc11c\uc2dd\uc744 \ubcf5\uc81c\ud574 \u00ab\uc140\uc5d0 \ub9de\ucda4(shrinkToFit)\u00bb \uc744 \ucf20\ub2e4.
         \uc6d0\ubcf8 \uc11c\uc2dd(\ud14c\ub450\ub9ac\u00b7\uae00\uaf34)\uc740 \uadf8\ub300\ub85c \ub450\uace0 \ub9de\ucda4\ub9cc \ubc14\uafbc \uc0c8 \uc11c\uc2dd\uc744 \ucd94\uac00\ud574 \uadf8 \uce78\uc5d0\ub9cc \uac74\ub2e4. */
    var STY = null;
    function shrinkStyle(sIdx) {
        if (!STY) return sIdx;
        var k = String(sIdx || 0);
        if (STY.map[k] != null) return STY.map[k];
        var xfs = STY.cellXfs.getElementsByTagNameNS(STY.ns, 'xf');
        var src = xfs[+k] || xfs[0];
        var nx = src.cloneNode(true);
        var al = nx.getElementsByTagNameNS(STY.ns, 'alignment')[0];
        if (!al) { al = STY.doc.createElementNS(STY.ns, 'alignment'); nx.appendChild(al); }
        al.setAttribute('shrinkToFit', '1');
        al.removeAttribute('wrapText');
        if (!al.getAttribute('vertical')) al.setAttribute('vertical', 'center');
        nx.setAttribute('applyAlignment', '1');
        STY.cellXfs.appendChild(nx);
        var idx = STY.cellXfs.getElementsByTagNameNS(STY.ns, 'xf').length - 1;
        STY.cellXfs.setAttribute('count', idx + 1);
        STY.map[k] = idx;
        return idx;
    }
    function setCell(xml, sheetData, ref, text) {
        var ns = sheetData.namespaceURI, p = splitRef(ref);
        var rows = sheetData.getElementsByTagNameNS(ns, 'row'), row = null, before = null;
        for (var i = 0; i < rows.length; i++) {
            var rn = +rows[i].getAttribute('r');
            if (rn === p.row) { row = rows[i]; break; }
            if (rn > p.row) { before = rows[i]; break; }
        }
        if (!row) {
            row = xml.createElementNS(ns, 'row');
            row.setAttribute('r', p.row);
            sheetData.insertBefore(row, before);
        }
        var cells = row.getElementsByTagNameNS(ns, 'c'), cell = null, cb = null;
        for (var j = 0; j < cells.length; j++) {
            var r2 = splitRef(cells[j].getAttribute('r'));
            if (r2.cn === p.cn) { cell = cells[j]; break; }
            if (r2.cn > p.cn) { cb = cells[j]; break; }
        }
        if (!cell) {
            cell = xml.createElementNS(ns, 'c');
            cell.setAttribute('r', ref);
            row.insertBefore(cell, cb);
        }
        while (cell.firstChild) cell.removeChild(cell.firstChild);   // \uc218\uc2dd\u00b7\uac12 \uc81c\uac70
        if (STY && String(text).length > 6) cell.setAttribute('s', shrinkStyle(cell.getAttribute('s')));
        cell.setAttribute('t', 'inlineStr');
        var is = xml.createElementNS(ns, 'is'), t = xml.createElementNS(ns, 't');
        t.setAttributeNS('http://www.w3.org/XML/1998/namespace', 'xml:space', 'preserve');
        t.textContent = text;
        is.appendChild(t); cell.appendChild(is);
    }
    function parse(s) { return new DOMParser().parseFromString(s, 'application/xml'); }

    /* ── 양식 시트의 원래 글자 {A1: '...'} ── */
    function readCells(sd, sst) {
        var ss = [];
        if (sst) {
            var si = sst.getElementsByTagNameNS(sst.documentElement.namespaceURI, 'si');
            for (var i = 0; i < si.length; i++) {
                var ts = si[i].getElementsByTagNameNS(sst.documentElement.namespaceURI, 't'), s = '';
                for (var j = 0; j < ts.length; j++) s += ts[j].textContent;
                ss.push(s);
            }
        }
        var out = {}, cs = sd.getElementsByTagNameNS(sd.namespaceURI, 'c');
        for (var k = 0; k < cs.length; k++) {
            var c = cs[k], t = c.getAttribute('t'), v = c.getElementsByTagNameNS(sd.namespaceURI, 'v')[0], txt = null;
            if (t === 's' && v) txt = ss[+v.textContent];
            else if (t === 'inlineStr') { var tt = c.getElementsByTagNameNS(sd.namespaceURI, 't'); txt = ''; for (var q = 0; q < tt.length; q++) txt += tt[q].textContent; }
            else if (v) txt = v.textContent;
            if (txt != null && String(txt).trim() !== '') out[c.getAttribute('r')] = String(txt);
        }
        return out;
    }

    /* ══ ★ 제조공정관리 주요자재 = 양식 D열의 R-품번으로 찾는다 ══
       D열에 R-품번(R2001…)이 있는 줄부터, 다음 공정번호(B열)나 다른 D열 글자가 나올 때까지가 한 묶음.
       묶음의 E열 칸 글자에 «LOT» 이 있으면 LOT, «S/N»(MR2·MBM2…) 이 있으면 S/N 을 넣는다.
       값은 품목의 R-품번이 같은 자재에서 — 조립 때 입력한 부품 LOT/S/N, 없으면 투입 로트(하위 반제품 속까지).
       같은 R-품번 칸이 여러 개면 1번째 칸 = 1번째 입력, 2번째 칸 = 2번째 입력.
       값이 없는 칸은 양식 원래 글자(LOT____)로 둔다 — 엉뚱한 자재가 들어가지 않는다. */
    function rcodeFill(T, D, cells) {
        var K = {};
        function key(rc) { return K[rc] = K[rc] || {pl: [], sn: [], lots: []}; }
        function codesOf(v) { return String(v || '').toUpperCase().match(/R\d{4}/g) || []; }   // 품목 하나에 R-품번 여러 개 가능 (R2035,R2036)
        (D.sns || []).slice().sort(function (a, b) { return (+a.mat_id - +b.mat_id) || (+a.seq_no - +b.seq_no); })
            .forEach(function (x) {
                codesOf(x.r_code).forEach(function (rc) {
                    var e = key(rc);
                    // 한 칸에 콤마로 여러 개 적었으면 양식의 같은 R-품번 칸에 차례로 나눈다
                    if (x.part_lot) String(x.part_lot).split(/\s*,\s*/).filter(Boolean).forEach(function (v) { e.pl.push(v); });
                    if (x.serial_no) String(x.serial_no).split(/\s*,\s*/).filter(Boolean).forEach(function (v) { e.sn.push(v); });
                });
            });
        // 투입 로트 — 가장 가까운 단계(유닛 자기 작업)의 로트만, 중복 없이
        var best = {};
        (D.deep_mats || []).forEach(function (x) {
            if (!x.lots) return;
            codesOf(x.r_code).forEach(function (rc) {
                var d = +x.depth || 0;
                if (best[rc] == null || d < best[rc]) { best[rc] = d; key(rc).lots = []; }
                if (d === best[rc]) String(x.lots).split(/\s*,\s*/).forEach(function (l) { if (l && key(rc).lots.indexOf(l) < 0) key(rc).lots.push(l); });
            });
        });
        if (!Object.keys(K).length) return;        // 품목에 R-품번이 아직 없으면 예전 방식 그대로
        var rows = {};
        Object.keys(T).forEach(function (ref) {
            var m = /^([A-Z]+)(\d+)$/.exec(ref); if (!m) return;
            (rows[+m[2]] = rows[+m[2]] || {})[m[1]] = T[ref];
        });
        var used = {}, cur = null;
        function lotOf(rc) {
            var e = K[rc], n = used[rc + 'L'] = (used[rc + 'L'] || 0) + 1;
            if (!e) return null;
            if (e.pl[n - 1]) return e.pl[n - 1];
            if (e.pl.length) return e.pl[e.pl.length - 1];        // 같은 R-품번 LOT 칸이 더 있으면 같은 로트
            // ★ 부품 LOT 을 비워 두었으면 실제 투입한 재고 로트를 찍는다
            return (n === 1 && e.lots.length) ? e.lots.join(', ') : null;     // «외 n» 으로 줄이지 않는다
        }
        function snOf(rc) {
            var e = K[rc], n = used[rc + 'S'] = (used[rc + 'S'] || 0) + 1;
            return e && e.sn[n - 1] ? e.sn[n - 1] : null;
        }
        Object.keys(rows).map(Number).sort(function (a, b) { return a - b; }).forEach(function (r) {
            var row = rows[r], B = String(row.B || '').trim(), Dv = String(row.D || '').trim(), E = row.E;
            var codes = Dv.toUpperCase().match(/R\d{4}/g);
            if (codes) cur = {codes: codes, li: 0, si: 0};
            else if (B || Dv) cur = null;
            if (!cur || E == null) return;
            var isSn = /S\s*\/\s*N|MR\d|MB[MS]\d/i.test(E), isLot = /LOT/i.test(E);
            if (!isSn && !isLot) return;
            var ref = 'E' + r, lot = null, sn = null;
            if (isLot) lot = lotOf(cur.codes[Math.min(cur.li++, cur.codes.length - 1)]);
            if (isSn) sn = snOf(cur.codes[Math.min(cur.si++, cur.codes.length - 1)]);
            if (lot == null && sn == null) { cells[ref] = E; return; }          // 값 없음 → 양식 글자 그대로
            if (isLot && isSn) cells[ref] = 'LOT ' + (lot || '______') + ' / S/N ' + (sn || '______');
            else cells[ref] = isLot ? 'LOT ' + lot : (/B\/T/i.test(E) ? 'B/T S/N ' : 'S/N ') + sn;
        });
    }
    function ser(d) { return new XMLSerializer().serializeToString(d); }

    function buildXlsx(doc, model, D, printOnly) {
        return loadScript(JSZIP_URL).then(function () {
            var ver = verOf(doc, model, D);
            return fetch(API + '/template?model=' + model + '&doc=' + doc + (ver ? '&ver_id=' + ver.ver_id : ''), {credentials: 'same-origin'});
        }).then(function (r) {
            if (!r.ok) throw new Error('\uc591\uc2dd \ud30c\uc77c\uc774 \uc5c6\uc2b5\ub2c8\ub2e4 (' + model + '/' + doc + '.xlsx)');
            return r.arrayBuffer();
        }).then(function (buf) {
            return global.JSZip.loadAsync(buf);
        }).then(function (zip) {
            return Promise.all([zip.file('xl/workbook.xml').async('string'), zip.file('xl/_rels/workbook.xml.rels').async('string')])
                .then(function (a) {
                    var wb = parse(a[0]), rels = parse(a[1]);
                    var sheets = wb.getElementsByTagName('sheet');
                    /* \u2605 \uc591\uc2dd \uc2dc\ud2b8 = \u00abRevision History\u00bb \uac00 \uc544\ub2cc \uccab \uc2dc\ud2b8. (\uc608\uc804\uc5d4 \ub0b4\uc7a5 LAYOUT \uc758 \ubc88\ud638\ub97c \uc37c\ub294\ub370 \uc5c6\uc5b4\uc9c0\uba74\uc11c 0\ubc88 = \uac1c\uc815\uc774\ub825\uc5d0 \uac12\uc744 \ucc44\uc6e0\ub2e4) */
                    var sh = null;
                    for (var q = 0; q < sheets.length; q++) {
                        if (!/revision/i.test(sheets[q].getAttribute('name') || '')) { sh = sheets[q]; break; }
                    }
                    if (!sh) sh = sheets[sheets.length - 1];
                    var rid = sh.getAttributeNS(NS_REL, 'id') || sh.getAttribute('r:id');
                    var target = null, rl = rels.getElementsByTagName('Relationship');
                    for (var i = 0; i < rl.length; i++) if (rl[i].getAttribute('Id') === rid) target = rl[i].getAttribute('Target');
                    var path = target.charAt(0) === '/' ? target.substring(1) : 'xl/' + target;
                    // \uc218\uc2dd \uce78\uc744 \uac12\uc73c\ub85c \ubc14\uafb8\ubbc0\ub85c \uacc4\uc0b0 \uccb4\uc778\uc744 \uc9c0\uc6b0\uace0 \uc5f4 \ub54c \ub2e4\uc2dc \uacc4\uc0b0\ud558\uac8c \ud55c\ub2e4
                    { // \u2605 \uc5d1\uc140 \ub2e4\uc6b4\ub85c\ub4dc\u00b7\uc778\uc1c4 \ubaa8\ub450 \u2014 Revision History \ub4f1 \ub2e4\ub978 \uc2dc\ud2b8\ub294 \uc228\uae34\ub2e4 (5\ucc28 #4\u00b78\u00b713 \u00ab\ubd88\ud544\uc694\u00bb)
                        //   \uc2dc\ud2b8\ub97c \uc544\uc608 \uc9c0\uc6b0\uba74 \uc778\uc1c4\uc601\uc5ed(definedName localSheetId) \ubc88\ud638\uac00 \ubc00\ub824 \uae68\uc9c4\ub2e4 \u2192 veryHidden (\uc5d1\uc140\uc5d0\uc11c \uc228\uae30\uae30 \ucde8\uc18c\ub85c\ub3c4 \uc548 \ubcf4\uc784)
                        for (var si = 0; si < sheets.length; si++) {
                            if (sheets[si] !== sh) sheets[si].setAttribute('state', 'veryHidden'); else sheets[si].removeAttribute('state');
                        }
                        var bv = wb.getElementsByTagName('workbookView')[0];
                        if (bv) { bv.setAttribute('activeTab', Array.prototype.indexOf.call(sheets, sh)); bv.setAttribute('firstSheet', Array.prototype.indexOf.call(sheets, sh)); }
                    }
                    var calc = wb.getElementsByTagName('calcPr')[0];
                    if (calc) calc.setAttribute('fullCalcOnLoad', '1');
                    for (var k = rl.length - 1; k >= 0; k--) {
                        if (/calcChain/.test(rl[k].getAttribute('Target'))) rl[k].parentNode.removeChild(rl[k]);
                    }
                    zip.remove('xl/calcChain.xml');
                    zip.file('xl/workbook.xml', ser(wb));
                    zip.file('xl/_rels/workbook.xml.rels', ser(rels));
                    return zip.file('[Content_Types].xml').async('string').then(function (ct) {
                        var c = parse(ct), ov = c.getElementsByTagName('Override');
                        for (var j = ov.length - 1; j >= 0; j--) if (/calcChain/.test(ov[j].getAttribute('PartName'))) ov[j].parentNode.removeChild(ov[j]);
                        zip.file('[Content_Types].xml', ser(c));
                        return Promise.all([zip.file(path).async('string'),
                            (doc === 'F705-33' && zip.file('xl/sharedStrings.xml')) ? zip.file('xl/sharedStrings.xml').async('string') : null]);
                    }).then(function (a2) {
                        var sx = a2[0], xml = parse(sx), sd = xml.getElementsByTagName('sheetData')[0];
                        var TPL = (doc === 'F705-33') ? readCells(sd, a2[1] ? parse(a2[1]) : null) : null;   // ★ 양식 원래 글자 (R-품번 읽기용)
                        // \uc591\uc2dd\uc5d0 \ub0a8\uc544 \uc788\ub358 \uc218\uc2dd \uc624\ub958(#VALUE!) \uce78\uc740 \ube44\uc6b4\ub2e4
                        Array.prototype.slice.call(sd.getElementsByTagNameNS(sd.namespaceURI, 'c')).forEach(function (c) {
                            if (c.getAttribute('t') === 'e') { while (c.firstChild) c.removeChild(c.firstChild); c.removeAttribute('t'); }
                        });
                        var cells = cellsFor(doc, model, D);
                        if (TPL) rcodeFill(TPL, D, cells);
                        return zip.file('xl/styles.xml').async('string').then(function (stx) {
                            var sdoc = parse(stx), cx = sdoc.getElementsByTagName('cellXfs')[0];
                            STY = cx ? {doc: sdoc, ns: cx.namespaceURI, cellXfs: cx, map: {}} : null;
                            Object.keys(cells).forEach(function (ref) { setCell(xml, sd, ref, cells[ref]); });
                            if (STY) zip.file('xl/styles.xml', ser(sdoc));
                            STY = null;
                            zip.file(path, ser(xml));
                            // \u2605 \ucd5c\uc885\uac80\uc0ac \u2014 \ucd9c\uace0\ub77c\ubca8 \uc0ac\uc9c4\uc744 \u00ab\ucd9c\uace0\ub77c\ubca8\ubd80\ucc29\u00bb \uce78(D9:F13)\uc5d0 \ub123\ub294\ub2e4
                            var ph = (doc === 'F803-8') ? D.label_photo : null;
                            return (ph && ph.url ? addPhoto(zip, path, photoRange(D), ph.url).catch(function (e) { console.warn('[mcell_doc] photo', e); }) : Promise.resolve())
                                .then(function () {
                                    return zip.generateAsync({type: 'blob', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});
                                });
                        });
                    });
                });
        });
    }
    /* \u2500\u2500 \u2605 \uc0ac\uc9c4 \ub123\uae30 (\uc591\uc2dd \uc2dc\ud2b8\uc758 \uae30\uc874 \uadf8\ub9bc\ud310\uc5d0 \ud55c \uc7a5 \ucd94\uac00) \u2500\u2500 */
    function photoRange(D) {
        // \uac80\uc0ac \uc591\uc2dd(FP-ALL-FINAL) \uba38\ub9ac\uce78\uc5d0 "photo":"D9:F13" \uc744 \ub123\uc73c\uba74 \uadf8 \uce78\uc744 \uc4f4\ub2e4
        var f = (D.doc_forms || []).filter(function (x) { return x.doc_code === 'F803-8'; })[0];
        var h = f ? pcm(f.cell_map) : {};
        return h.photo || 'D9:F13';
    }
    function colIdx(letters) { var n = 0; for (var i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) - 64); return n - 1; }
    function addPhoto(zip, sheetPath, range, url) {
        var m = /^([A-Z]+)(\d+):([A-Z]+)(\d+)$/.exec(range);
        if (!m) return Promise.resolve();
        var dir = sheetPath.substring(0, sheetPath.lastIndexOf('/'));
        var relsPath = dir + '/_rels/' + sheetPath.substring(sheetPath.lastIndexOf('/') + 1) + '.rels';
        var ext = 'jpeg', bytes;
        return fetch(url, {credentials: 'same-origin'}).then(function (r) {
            if (!r.ok) throw new Error('photo ' + r.status);
            if (/png/i.test(r.headers.get('Content-Type') || '')) ext = 'png';
            return r.arrayBuffer();
        }).then(function (b) {
            bytes = b;
            return Promise.all([zip.file(sheetPath).async('string'), zip.file(relsPath) ? zip.file(relsPath).async('string') : null]);
        }).then(function (a) {
            var sx = parse(a[0]);
            var dr = sx.getElementsByTagName('drawing')[0];
            if (!dr || !a[1]) throw new Error('\uc2dc\ud2b8\uc5d0 \uadf8\ub9bc\ud310\uc774 \uc5c6\uc74c');
            var rid = dr.getAttributeNS(NS_REL, 'id') || dr.getAttribute('r:id');
            var rels = parse(a[1]), rl = rels.getElementsByTagName('Relationship'), target = null;
            for (var i = 0; i < rl.length; i++) if (rl[i].getAttribute('Id') === rid) target = rl[i].getAttribute('Target');
            var dPath = target.charAt(0) === '/' ? target.substring(1) : normPath(dir + '/' + target);
            var dDir = dPath.substring(0, dPath.lastIndexOf('/'));
            var dRels = dDir + '/_rels/' + dPath.substring(dPath.lastIndexOf('/') + 1) + '.rels';
            var media = 'mcellphoto_' + Date.now() + '.' + (ext === 'png' ? 'png' : 'jpeg');
            zip.file('xl/media/' + media, bytes);
            return Promise.all([zip.file(dPath).async('string'),
                zip.file(dRels) ? zip.file(dRels).async('string') : Promise.resolve('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>'),
                zip.file('[Content_Types].xml').async('string')]).then(function (b) {
                var drel = parse(b[1]), root = drel.documentElement, newId = 'rIdMcellPhoto';
                var rel = drel.createElementNS(root.namespaceURI, 'Relationship');
                rel.setAttribute('Id', newId);
                rel.setAttribute('Type', 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/image');
                rel.setAttribute('Target', '../media/' + media);
                root.appendChild(rel);
                zip.file(dRels, ser(drel));

                var c1 = colIdx(m[1]), r1 = +m[2] - 1, c2 = colIdx(m[3]) + 1, r2 = +m[4];
                var X = 'http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing',
                    A = 'http://schemas.openxmlformats.org/drawingml/2006/main',
                    R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
                var frag = '<xdr:twoCellAnchor xmlns:xdr="' + X + '" xmlns:a="' + A + '" xmlns:r="' + R + '" editAs="oneCell">' +
                    '<xdr:from><xdr:col>' + c1 + '</xdr:col><xdr:colOff>38100</xdr:colOff><xdr:row>' + r1 + '</xdr:row><xdr:rowOff>38100</xdr:rowOff></xdr:from>' +
                    '<xdr:to><xdr:col>' + c2 + '</xdr:col><xdr:colOff>-38100</xdr:colOff><xdr:row>' + r2 + '</xdr:row><xdr:rowOff>-38100</xdr:rowOff></xdr:to>' +
                    '<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="9001" name="LabelPhoto"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>' +
                    '<xdr:blipFill><a:blip r:embed="' + newId + '"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>' +
                    '<xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>';
                var dd = parse(b[0]), node = parse(frag).documentElement;
                dd.documentElement.appendChild(dd.importNode(node, true));
                zip.file(dPath, ser(dd));

                var ct = parse(b[2]), types = ct.documentElement, ex = ext === 'png' ? 'png' : 'jpeg', has = false;
                var defs = ct.getElementsByTagName('Default');
                for (var j = 0; j < defs.length; j++) if ((defs[j].getAttribute('Extension') || '').toLowerCase() === ex) has = true;
                if (!has) {
                    var d = ct.createElementNS(types.namespaceURI, 'Default');
                    d.setAttribute('Extension', ex);
                    d.setAttribute('ContentType', ex === 'png' ? 'image/png' : 'image/jpeg');
                    types.insertBefore(d, types.firstChild);
                    zip.file('[Content_Types].xml', ser(ct));
                }
            });
        });
    }
    function normPath(p) {
        var out = [];
        p.split('/').forEach(function (s) { if (s === '..') out.pop(); else if (s && s !== '.') out.push(s); });
        return out.join('/');
    }

    function download(blob, name) {
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob); a.download = name;
        document.body.appendChild(a); a.click();
        setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1500);
    }

    // \u2500\u2500 \uc778\uc1c4 (\ub370\uc774\ud130 \uae30\uc900 \uac04\uc774 \uc591\uc2dd) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    function printHtml(doc, model, D) {
        var u = D.unit, h = '';
        var head = '<h1>' + esc(DOC_NAMES[doc]) + ' <small>' + doc + ' \u00b7 ' + esc(model) + '</small></h1>' +
            '<table class="hd"><tr><th>\ubaa8\ub378</th><td>' + esc(u.mat_name) + '</td><th>Lot No.</th><td>' + esc(u.lot_number) + '</td>' +
            '<th>\uc131\uc801\uc11c \ubc88\ud638</th><td>' + ({'F803-6': '\uacf5\uc815-', 'F803-7': '\uc644\uc81c\ud488-', 'F803-8': '\ucd5c\uc885-', 'F705-33': ''}[doc]) + esc(u.lot_number) + '</td></tr></table>';
        function tbl(cols, rows) {
            return '<table><tr>' + cols.map(function (c) { return '<th>' + c + '</th>'; }).join('') + '</tr>' +
                (rows.length ? rows.map(function (r) { return '<tr>' + r.map(function (v) { return '<td>' + esc(v) + '</td>'; }).join('') + '</tr>'; }).join('')
                    : '<tr><td colspan="' + cols.length + '" class="na">\uae30\ub85d \uc5c6\uc74c</td></tr>') + '</table>';
        }
        if (doc === 'F705-33') {
            h += tbl(['\uacf5\uc815(\uc5b4\uc148\ube14\ub9ac)', '\uc644\uc131\ud488 LOT', '\uc8fc\uc694\uc790\uc7ac LOT', 'S/N', '\uc791\uc5c5\uc77c\uc790', '\uc791\uc5c5\uc790'],
                (D.steps || []).filter(function (s) { return s.state === 'done'; }).map(function (s) {
                    var ids = {};
                    (s.mats || []).forEach(function (m) { ids[m.mat_id] = 1; });
                    return [s.mat_name, s.lot_number,
                        (s.mats || []).filter(function (m) { return !m.is_asm; }).map(function (m) { return m.mat_name + ' : ' + (m.lots || '-'); }).join('\n'),
                        (D.sns || []).filter(function (x) { return +x.step_id === +s.step_id || ids[x.mat_id]; }).map(function (x) { return x.mat_name + ' ' + x.serial_no; }).join('\n'),
                        s.end_date, s.actor_name];
                }));
            h += '<h2>\uac80\uc0ac</h2>' + tbl(['\uad6c\ubd84', '\uc591\uc2dd', '\ud310\uc815', '\uc77c\uc790', '\uac80\uc0ac\uc790'],
                (D.proc_insp || []).map(function (f) { return ['\uacf5\uc815\uac80\uc0ac', f.form_name, judgeTxt(f.verdict), f.end_date, f.actor_name]; })
                    .concat((D.final_insp || []).map(function (f) { return ['\uc644\uc81c\ud488\uac80\uc0ac', f.form_name, judgeTxt(f.verdict), f.end_date, f.actor_name]; })));
            h += '<h2>\ud3ec\uc7a5</h2>' + tbl(['\ud56d\ubaa9', '\uacb0\uacfc', '\uac12'], ((D.pack && D.pack.checks) || []).filter(function (x) { return !/FINAL/.test(x.form_code); })
                .map(function (x) { return [x.item_name, judgeTxt(x.result), x.value]; }));
        }
        if (doc === 'F803-6') {
            (D.proc_insp || []).filter(function (f) { return f.form_code.indexOf('PI-' + model) === 0; }).forEach(function (f) {
                h += '<h2>' + esc(f.form_name) + ' \u00b7 ' + judgeTxt(f.verdict) + ' (' + esc(f.end_date || '') + ' ' + esc(f.actor_name || '') + ')</h2>' +
                    tbl(['No', '\ud56d\ubaa9', '\uce21\uc815\uac12', '\ud310\uc815'], (f.items || []).map(function (i) { return [i.seq_no, i.item_name, i.value, judgeTxt(i.result)]; }));
            });
            if (!(D.proc_insp || []).length) h += '<p class="na">\uacf5\uc815\uac80\uc0ac \uae30\ub85d\uc774 \uc5c6\uc2b5\ub2c8\ub2e4.</p>';
        }
        if (doc === 'F803-7') {
            (D.final_insp || []).forEach(function (f) {
                h += '<h2>' + esc(f.form_name) + ' \u00b7 ' + judgeTxt(f.verdict) + ' (' + esc(f.end_date || '') + ' ' + esc(f.actor_name || '') + ')</h2>' +
                    tbl(['No', '\ud56d\ubaa9', '\ud68c\ucc28', '\uce21\uc815\uac12', '\ud310\uc815', '\uac80\uc0ac\uc790'], (f.items || []).map(function (i) {
                        return [i.seq_no, i.item_name, i.repeat_no, i.value, judgeTxt(i.result), i.actor_name];
                    }));
            });
            if (!(D.final_insp || []).length) h += '<p class="na">\uc644\uc81c\ud488\uac80\uc0ac \uae30\ub85d\uc774 \uc5c6\uc2b5\ub2c8\ub2e4.</p>';
        }
        if (doc === 'F803-8') {
            var ch = packChecks(D, 'FP-ALL-FINAL');
            h += '<p>\ucd9c\ud558 \uad6c\ubd84 : ' + esc((ch[0] && ch[0].dest) || '-') + ' \u00b7 \ubc15\uc2a4 \ub77c\ubca8 : ' + esc((D.pack && D.pack.box_label) || '-') +
                ' \u00b7 \uac80\uc0ac\uc77c ' + esc((D.pack && D.pack.end_date) || '-') + ' \u00b7 \uac80\uc0ac\uc790 ' + esc(finWho(ch) || (D.pack && D.pack.actor_name) || '-') + '</p>' +
                tbl(['No', '\ud56d\ubaa9', '\uacb0\uacfc', '\ud310\uc815'], ch.map(function (i) { return [i.seq_no, i.item_name, i.value, judgeTxt(i.result)]; }));
        }
        var w = window.open('', '_blank');
        if (!w) { toast('\ud31d\uc5c5\uc774 \ucc28\ub2e8\ub418\uc5c8\uc2b5\ub2c8\ub2e4. \ud31d\uc5c5\uc744 \ud5c8\uc6a9\ud574 \uc8fc\uc138\uc694.'); return; }
        w.document.write('<!doctype html><html><head><meta charset="utf-8"><title>' + esc(doc + ' ' + u.lot_number) + '</title><style>' +
            'body{font-family:"Malgun Gothic",sans-serif;margin:18mm 14mm;color:#111}h1{font-size:20px;margin:0 0 10px}h1 small{font-size:12px;color:#666;font-weight:400}' +
            'h2{font-size:14px;margin:16px 0 6px}table{width:100%;border-collapse:collapse;font-size:12px;margin-bottom:6px}' +
            'th,td{border:1px solid #888;padding:5px 6px;text-align:left;vertical-align:top;white-space:pre-line}th{background:#eee}' +
            '.hd th{width:10%}.na{color:#888;text-align:center}.sign{margin-top:24px;display:flex;justify-content:flex-end;gap:0}' +
            '.sign div{border:1px solid #888;width:90px;height:64px;font-size:11px;padding:4px;text-align:center}@media print{body{margin:10mm}}' +
            '</style></head><body>' + head + h +
            '<div class="sign"><div>\uc791\uc131</div><div>\uac80\ud1a0</div><div>\uc2b9\uc778</div></div>' +
            '<script>window.onload=function(){setTimeout(function(){window.print();},200);};<\/script></body></html>');
        w.document.close();
    }

    /* \u2605 \uc778\uc1c4 \u2014 \uc591\uc2dd\uc5d0 \uac12\uc744 \ucc44\uc6b4 \uc5d1\uc140\uc744 \uc11c\ubc84(LibreOffice)\uc5d0\uc11c PDF \ub85c \ubc14\uafd4 \ub744\uc6b4\ub2e4.
         \uc591\uc2dd \uadf8\ub300\ub85c \uc778\uc1c4\ub41c\ub2e4. \uc11c\ubc84 \ubcc0\ud658\uc774 \uc548 \ub418\uba74 \uac04\uc774 \uc778\uc1c4(\ud45c)\ub85c \ub300\uc2e0\ud55c\ub2e4. */
    function printDoc(doc, btn) {
        if (!S.model) { printHtml(doc, '-', S.D); return; }
        var w = window.open('', '_blank');           // \ud31d\uc5c5 \ucc28\ub2e8\uc744 \ud53c\ud558\ub824\uace0 \uba3c\uc800 \uc5f0\ub2e4
        if (w) w.document.write('<p style="font-family:sans-serif;padding:20px">\uc778\uc1c4\uc6a9 \ubb38\uc11c\ub97c \ub9cc\ub4dc\ub294 \uc911\uc785\ub2c8\ub2e4\u2026</p>');
        btn.disabled = true; btn.textContent = '\ub9cc\ub4dc\ub294 \uc911\u2026';
        buildXlsx(doc, S.model, S.D, true).then(function (blob) {
            var fd = new FormData();
            fd.append('file', blob, doc + '.xlsx');
            var tk = csrf();
            if (tk) fd.append('_csrf', tk);
            return fetch(API + '/to_pdf', {method: 'POST', credentials: 'same-origin', body: fd, headers: tk ? {'X-CSRF-TOKEN': tk} : {}});
        }).then(function (r) {
            if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'PDF \ubcc0\ud658 \uc2e4\ud328'); });
            return r.blob();
        }).then(function (pdf) {
            var url = URL.createObjectURL(new Blob([pdf], {type: 'application/pdf'}));
            if (w) w.location.href = url; else window.open(url, '_blank');
        }).catch(function (err) {
            if (w) w.close();
            toast((err.message || 'PDF \ubcc0\ud658 \uc2e4\ud328') + ' \u2014 \uac04\uc774 \uc778\uc1c4\ub85c \uc5fd\ub2c8\ub2e4');
            printHtml(doc, S.model, S.D);
        }).then(function () { render(); });
    }

    // \u2500\u2500 \ud654\uba74 (\uc2dc\ud2b8) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    var S = {D: null, model: null, docs: DOC_ORDER, files: {}, openDoc: null};

    function css() {
        if (document.getElementById('mdocCss')) return;
        var st = document.createElement('style');
        st.id = 'mdocCss';
        st.textContent =
            '.mdoc-mask{position:fixed;inset:0;background:rgba(15,23,42,.45);z-index:9000;display:flex;align-items:flex-end;justify-content:center}' +
            '.mdoc-sheet{background:#fff;width:min(760px,100%);max-height:88vh;overflow:auto;border-radius:18px 18px 0 0;padding:18px 18px 24px;box-shadow:0 -8px 30px rgba(0,0,0,.2)}' +
            '.mdoc-hd{display:flex;align-items:center;gap:10px;margin-bottom:12px}.mdoc-hd b{font-size:18px}.mdoc-hd span{color:#64748b;font-weight:700;font-size:13px}' +
            '.mdoc-x{margin-left:auto;height:40px;padding:0 16px;border-radius:10px;border:1.5px solid #e2e8f0;background:#fff;font-weight:800}' +
            '.mdoc-row{border:1.5px solid #e2e8f0;border-radius:14px;padding:12px 14px;margin-bottom:10px}' +
            '.mdoc-top{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.mdoc-nm{flex:1;min-width:180px}.mdoc-nm b{font-size:15.5px}.mdoc-nm div{font-size:12.5px;color:#64748b;font-weight:700}' +
            '.mdoc-btn{height:42px;padding:0 14px;border-radius:10px;border:1.5px solid #cbd5e1;background:#fff;font-weight:800;font-size:14px;cursor:pointer}' +
            '.mdoc-btn.pri{background:#0E7C86;border-color:#0E7C86;color:#fff}.mdoc-btn:disabled{opacity:.5}' +
            '.mdoc-files{margin-top:10px;border-top:1px dashed #e2e8f0;padding-top:8px}' +
            '.mdoc-f{display:flex;align-items:center;gap:8px;padding:6px 0;font-size:14px}.mdoc-f a{flex:1;color:#0E7C86;font-weight:700;word-break:break-all}' +
            '.mdoc-f small{color:#94a3b8}.mdoc-del{border:0;background:#fee2e2;color:#b91c1c;border-radius:8px;height:32px;width:36px;font-weight:900}' +
            '.mdoc-badge{font-size:11.5px;font-weight:900;border-radius:8px;padding:2px 8px;background:#e2e8f0;color:#334155}' +
            '.mdoc-badge.ok{background:#e3f5e9;color:#1e7a3c}.mdoc-badge.no{background:#fff4e5;color:#a15c00}' +
            '.mdoc-toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#111827;color:#fff;padding:10px 18px;border-radius:12px;font-weight:700;z-index:9100;opacity:0;transition:.2s;pointer-events:none}' +
            '.mdoc-toast.on{opacity:.95}' +
            '.mdoc-row.here{border-color:#0E7C86;background:#f3fbfb}.mdoc-badge.here{background:#0E7C86;color:#fff}.mdoc-badge.sign{background:#e8eefc;color:#2f55c4}' +
            '.mdoc-prog{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:4px}' +
            '.mdoc-prog .pc{font-size:12px;font-weight:800;border-radius:8px;padding:2px 8px;background:#f1f5f9;color:#64748b}' +
            '.mdoc-prog .pc.ok{background:#e3f5e9;color:#1e7a3c}.mdoc-prog .pc.part{background:#fff4e5;color:#a15c00}' +
            '.mdoc-note{font-size:12.5px;color:#64748b;font-weight:700;padding:4px 2px}';
        document.head.appendChild(st);
    }
    /* \u2605 \ubb38\uc11c\ubcc4 \uc9c4\ud589 \ud604\ud669 \u2014 \uc5b4\ub290 \uacf5\uc815\uc5d0\uc11c \ucc44\uc6cc\uc9c0\uace0, \uc5bc\ub9c8\ub098 \ucc44\uc6cc\uc84c\ub098 */
    function progress(doc) {
        var D = S.D, E = D.expected || {}, out = [];
        function chip(proc, txt, st) { out.push({proc: proc, txt: txt, st: st}); }   // st: ok | part | wait
        var pis = D.proc_insp || [], piPass = pis.filter(function (x) { return x.verdict === 'pass'; }).length;
        var fis = D.final_insp || [], fiPass = fis.filter(function (x) { return x.verdict === 'pass'; }).length;
        var stT = +E.step_total || 0, stD = +E.step_done || 0, prT = +E.proc_total || 0, fT = +E.final_total || 0;
        var packed = !!(D.pack && (D.pack.checks || []).length);
        if (doc === 'F705-33') {
            chip('\uc870\ub9bd', stT ? stD + '/' + stT : '-', stT && stD >= stT ? 'ok' : (stD ? 'part' : 'wait'));
            chip('\uac80\uc0ac', fT ? fiPass + '/' + fT : (fis.length ? fiPass + '' : '-'), fT && fiPass >= fT ? 'ok' : (fis.length ? 'part' : 'wait'));
            chip('\ud3ec\uc7a5', packed ? '\uc644\ub8cc' : '\ub300\uae30', packed ? 'ok' : 'wait');
        }
        if (doc === 'F803-6') chip('\uc870\ub9bd', prT ? piPass + '/' + prT : (pis.length ? piPass + '' : '\uc591\uc2dd \uc5c6\uc74c'), prT && piPass >= prT ? 'ok' : (pis.length ? 'part' : 'wait'));
        if (doc === 'F803-7') chip('\uac80\uc0ac', fT ? fiPass + '/' + fT : '\uc591\uc2dd \uc5c6\uc74c', fT && fiPass >= fT ? 'ok' : (fis.length ? 'part' : 'wait'));
        if (doc === 'F803-8') chip('\ud3ec\uc7a5', packed ? '\uc644\ub8cc' : '\ub300\uae30', packed ? 'ok' : 'wait');
        var ver = verOf(doc, S.model, D);
        if (ver) chip('\uc591\uc2dd', 'Rev.' + ver.rev_no + (ver.map_json ? ' (\uc608\uc804 \ubc84\uc804)' : ''), 'wait');
        return out;
    }
    var HOME = {assembly: ['F705-33', 'F803-6'], inspect: ['F803-7'], pack: ['F803-8', 'F705-33']};
    function render() {
        var D = S.D, u = D.unit, box = document.getElementById('mdocBody');
        var mine = HOME[S.from] || [];
        var docs = S.docs.slice().sort(function (a, b) {
            var x = mine.indexOf(a), y = mine.indexOf(b);
            return (x < 0 ? 9 : x) - (y < 0 ? 9 : y) || DOC_ORDER.indexOf(a) - DOC_ORDER.indexOf(b);
        });
        box.innerHTML = '<div class="mdoc-hd"><b>\uc81c\uc870 \ubb38\uc11c</b><span>' + esc(u.serial_no || u.lot_number) + ' \u00b7 ' + esc(u.mat_name) + ' \u00b7 ' + esc(S.model || '\ubaa8\ub378 \ubbf8\ud655\uc778') + '</span>' +
            '<button class="mdoc-x" data-x>\ub2eb\uae30</button></div>' +
            docs.map(function (doc) {
                var n = (D.attach_cnt && D.attach_cnt[doc]) || 0, open = S.openDoc === doc, here = mine.indexOf(doc) >= 0;
                var pr = progress(doc), done = pr.length && pr.every(function (c) { return c.st === 'ok'; });
                return '<div class="mdoc-row' + (here ? ' here' : '') + '"><div class="mdoc-top"><div class="mdoc-nm"><b>' + esc(DOC_NAMES[doc]) + '</b> ' +
                    (here ? '<span class="mdoc-badge here">\uc774 \uacf5\uc815</span> ' : '') +
                    '<span class="mdoc-badge ' + (done ? 'ok' : 'no') + '">' + (done ? '\uc791\uc131 \uc644\ub8cc' : '\uc791\uc131 \uc911') + '</span>' +
                    (n ? ' <span class="mdoc-badge sign">\uc11c\uba85\ubcf8 ' + n + '</span>' : '') +
                    '<div class="mdoc-prog">' + doc + ' \u00b7 ' + pr.map(function (c) {
                        return '<span class="pc ' + c.st + '">' + c.proc + ' ' + esc(c.txt) + (c.st === 'ok' ? ' \u2713' : '') + '</span>';
                    }).join('') + '</div></div>' +
                    '<button class="mdoc-btn pri" data-xl="' + doc + '"' + (S.model ? '' : ' disabled') + '>\uc5d1\uc140</button>' +
                    '<button class="mdoc-btn" data-pr="' + doc + '">\uc778\uc1c4</button>' +
                    '<button class="mdoc-btn" data-at="' + doc + '">\ucca8\ubd80 ' + n + '</button></div>' +
                    (open ? '<div class="mdoc-files" id="mdocFiles">' + filesHtml(doc) + '</div>' : '') + '</div>';
            }).join('') +
            '<div class="mdoc-note">\uac01 \ubb38\uc11c\ub294 \uacf5\uc815\uc5d0\uc11c \uc785\ub825\ud55c \uac12\uc73c\ub85c \uc790\ub3d9\uc73c\ub85c \ucc44\uc6cc\uc9d1\ub2c8\ub2e4. \ube44\uc5b4 \uc788\ub294 \uacf5\uc815\uc740 \ud574\ub2f9 \ud654\uba74\uc5d0\uc11c \uc791\uc5c5\uc744 \ub9c8\uce58\uba74 \ucc44\uc6cc\uc9d1\ub2c8\ub2e4.</div>';
    }
    function filesHtml(doc) {
        var L = S.files[doc];
        if (!L) return '<div class="mdoc-f">\ubd88\ub7ec\uc624\ub294 \uc911\u2026</div>';
        return L.map(function (f) {
                return '<div class="mdoc-f"><a href="' + API + '/file?file_id=' + f.file_id + '" target="_blank">' + esc(f.file_name) + '</a>' +
                    '<small>' + esc(f.created || '') + '</small><button class="mdoc-del" data-fd="' + f.file_id + '">\u2715</button></div>';
            }).join('') + '<label class="mdoc-btn" style="display:inline-flex;align-items:center;margin-top:6px">\uff0b \ud30c\uc77c \ucca8\ubd80' +
            '<input type="file" multiple data-fu="' + doc + '" style="display:none" accept="image/*,.pdf,.xlsx,.xls,.doc,.docx,.hwp"></label>';
    }
    function loadFiles(doc) {
        return getJson('/files', {unit_id: S.D.unit.unit_id, doc: doc}).then(function (L) {
            S.files[doc] = L || [];
            S.D.attach_cnt = S.D.attach_cnt || {};
            S.D.attach_cnt[doc] = S.files[doc].length;
            render();
        }).catch(function (e) { toast(e.message); });
    }
    function onClick(e) {
        if (e.target.id === 'mdocMask' || e.target.closest('[data-x]')) { close(); return; }
        var x = e.target.closest('[data-xl]');
        if (x) {
            var doc = x.dataset.xl; x.disabled = true; x.textContent = '\ub9cc\ub4dc\ub294 \uc911\u2026';
            buildXlsx(doc, S.model, S.D).then(function (blob) {
                download(blob, doc + '_' + DOC_NAMES[doc].replace(/\s/g, '') + '_' + S.D.unit.lot_number + '.xlsx');
            }).catch(function (err) { toast(err.message || '\uc5d1\uc140 \uc0dd\uc131 \uc2e4\ud328'); }).then(function () { render(); });
            return;
        }
        var p = e.target.closest('[data-pr]');
        if (p) { printDoc(p.dataset.pr, p); return; }
        var a = e.target.closest('[data-at]');
        if (a) {
            S.openDoc = S.openDoc === a.dataset.at ? null : a.dataset.at;
            render();
            if (S.openDoc) loadFiles(S.openDoc);
            return;
        }
        var d = e.target.closest('[data-fd]');
        if (d) {
            if (!confirm('\ucca8\ubd80\ub97c \uc0ad\uc81c\ud560\uae4c\uc694?')) return;
            postForm('/file_delete', {file_id: d.dataset.fd}).then(function () { toast('\uc0ad\uc81c\ud588\uc2b5\ub2c8\ub2e4'); loadFiles(S.openDoc); })
                .catch(function (err) { toast(err.message); });
        }
    }
    function onChange(e) {
        var inp = e.target.closest('[data-fu]');
        if (!inp || !inp.files.length) return;
        var doc = inp.dataset.fu, list = Array.prototype.slice.call(inp.files), ok = 0;
        (function next() {
            var f = list.shift();
            if (!f) { toast('\ucca8\ubd80 ' + ok + '\uac74 \uc644\ub8cc'); loadFiles(doc); return; }
            var fd = new FormData();
            fd.append('unit_id', S.D.unit.unit_id); fd.append('doc', doc); fd.append('file', f);
            fd.append('spjangcd', sessionStorage.getItem('spjangcd') || 'ZZ');
            postForm('/file_add', fd).then(function () { ok++; next(); }).catch(function (err) { toast(f.name + ' : ' + err.message); next(); });
        })();
    }
    function close() {
        var m = document.getElementById('mdocMask');
        if (m) m.remove();
    }
    function open(opt) {
        css();
        close();
        var m = document.createElement('div');
        m.id = 'mdocMask'; m.className = 'mdoc-mask';
        m.innerHTML = '<div class="mdoc-sheet" id="mdocBody"><div class="mdoc-hd"><b>\uc81c\uc870 \ubb38\uc11c</b><span>\ubd88\ub7ec\uc624\ub294 \uc911\u2026</span></div></div>';
        document.body.appendChild(m);
        m.addEventListener('click', onClick);
        m.addEventListener('change', onChange);
        S.docs = (opt && opt.docs) || DOC_ORDER; S.files = {}; S.openDoc = null; S.from = (opt && opt.from) || null;
        getJson('/data', {unit_id: opt && opt.unitId, lot_number: opt && opt.lot}).then(function (D) {
            if (!D || !D.unit) throw new Error('\uc720\ub2db \uc815\ubcf4\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.');
            S.D = D; S.model = modelOf(D.unit);
            if (!S.model) toast('\ubaa8\ub378(M2/M4/M4A)\uc744 \uc54c \uc218 \uc5c6\uc5b4 \uc5d1\uc140\uc740 \ub9cc\ub4e4 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4. \uc778\uc1c4\u00b7\ucca8\ubd80\ub294 \ub429\ub2c8\ub2e4.');
            render();
        }).catch(function (e) { toast(e.message || '\uc870\ud68c \uc2e4\ud328'); close(); });
    }

    global.McellDoc = {open: open, _build: buildXlsx, _cellsFor: cellsFor, _layout: function () { return (typeof LAYOUT !== 'undefined') ? LAYOUT : null; }};
})(window);