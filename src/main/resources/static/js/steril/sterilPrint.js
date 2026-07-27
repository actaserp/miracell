/* =====================================================================
 * sterilPrint.js — F710-3 멸균일지 인쇄
 *
 *  설계: "값만 데이터, 레이아웃은 코드"(B안)
 *    - 셀 병합/서식은 아래 LAYOUT 에 고정.
 *    - 값은 FieldKey 로만 꽂는다.  → 규격값·산출규칙 개정은 코드 수정 0.
 *    - 칸 자체가 늘거나 줄면(양식 개정) LAYOUT 도 같이 손봐야 한다. 그 전제로 만든 것.
 *
 *  사용:
 *    SterilPrint.print({ form, values, meta, charts })
 *      form   : {FormCode, Revision, ...}
 *      values : {FieldKey: 값}
 *      meta   : {sterilDate, equipNo, actor, approver, modelName, qty}
 *      charts : [{title, dataUrl}]   Chart.js toBase64Image() 결과
 * ===================================================================== */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) module.exports = factory();
    else root.SterilPrint = factory();
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    function esc(s) {
        return String(s === null || s === undefined ? '' : s)
            .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }
    // 밑줄 기입란
    function U(v, w) {
        return '<span class="u" style="min-width:' + (w || 60) + 'px">' + esc(v) + '</span>';
    }
    // '2026-06-24 10:30' → '2026.06.24 . 10:30'
    function dt(v) {
        var m = String(v || '').match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
        if (!m) return esc(v);
        return m[1] + '.' + m[2] + '.' + m[3] + '. ' + m[4] + ':' + m[5];
    }
    function chk(on) { return on ? '☑' : '☐'; }

    /* -----------------------------------------------------------------
     * F710-3 레이아웃
     *   records: 기록칸 1행. render(v) 로 셀 내용 생성.
     * ----------------------------------------------------------------- */
    var LAYOUT = [
        { no:1, name:'챔버 적재', criteria:'적재패턴을 따른다.', records:[
                function(v){ return '수량 : ' + U(v.load_qty) + ' EA (블리스터 수량)'; }]},

        { no:2, name:'BI 투입', criteria:'ISO11135를 따른다.', records:[
                function(v){ return 'BI : ' + U(v.bi_count) + ' EA'; }]},

        { no:3, name:'전조절',
            criteria:'온도 : (50±10)℃<br>습도 : (55±25)%RH<br>시간 : 4시간 이상', records:[
                function(v){ return '최저온도 : ' + U(v.precond_temp_min) + ' ℃ ~ 최고온도 : ' + U(v.precond_temp_max) + ' ℃'; },
                function(v){ return '최저습도 : ' + U(v.precond_humi_min) + ' % ~ 최고습도 : ' + U(v.precond_humi_max) + ' %'; },
                function(v){ return '시간 : ' + U(dt(v.precond_from),140) + ' ~<br>' + U(dt(v.precond_to),140); }]},

        { no:4, name:'진공', criteria:'최저 압력 : (20±10)kPa', records:[
                function(v){ return '최저 압력 : ' + U(v.vac_press_min) + ' kPa'; }]},

        { no:5, name:'조절', criteria:'온도 : (55±10)℃<br>습도 : (55±25)%RH', records:[
                function(v){ return '온도 : ' + U(v.cond_temp_min) + ' ℃ ~ ' + U(v.cond_temp_max) + ' ℃<br>' +
                    '습도 : ' + U(v.cond_humi_min) + ' % ~ ' + U(v.cond_humi_max) + ' %'; }]},

        { no:6, name:'멸균제<br>투입', criteria:'사용량<br>(6.0±1.0)kg', records:[
                function(v){ return 'EO가스 사용량 : ' + U(v.eo_from) + ' kg ~ ' + U(v.eo_to) + ' kg<br>' +
                    '<span style="padding-left:60px">총 ' + U(v.eo_total) + ' kg</span><br>' +
                    'EO가스 로트번호 : ' + U(v.eo_lot, 140); }]},

        { no:7, name:'노출',
            criteria:'압력 : (155±10)kPa<br>온도 : (55±10)℃<br>습도 : (55±25)%RH<br>시간 : 6시간', records:[
                function(v){ return '압력 : ' + U(v.exp_press) + ' kPa / 온도 : ' + U(v.exp_temp) + ' ℃<br>' +
                    '습도 : ' + U(v.exp_humi) + ' % / 노출시간 : ' + U(v.exp_hours) + ' 시간'; }]},

        { no:8, name:'공기정화', criteria:'라운드 : 5 회', records:[
                function(v){ return '플러싱 횟수 : ' + U(v.flush_count) + ' 회'; }]},

        { no:9, name:'통기', criteria:'온도 : 10℃ 이상<br>시간 : 48시간 이상', records:[
                function(v){ return '최저온도 : ' + U(v.aer_temp_min) + ' ℃<br>' +
                    '시간 : ' + U(dt(v.aer_from),140) + ' ~<br>' + U(dt(v.aer_to),140); }]},

        { no:10, name:'BI 확인', criteriaHtml:
                '<table class="sub"><tr><td colspan="2" style="border:0;padding:6px 0">BI 색상 변화</td></tr>' +
                '<tr><td>녹색, 투명<br>PASS</td><td>황색, 불투명<br>FAIL</td></tr></table>',
            records:[
                function(v){
                    var p = String(v.bi_result||'').toUpperCase() === 'PASS';
                    var f = String(v.bi_result||'').toUpperCase() === 'FAIL';
                    return '<div style="padding:4px 0">' + chk(p) + ' PASS' +
                        '<span style="padding-left:70px">' + chk(f) + ' FAIL</span></div>' +
                        'BI 로트번호 : ' + U(v.bi_lot, 150) +
                        (v.control_result ? '<div style="font-size:10pt;margin-top:3px">대조군 : ' +
                            U(v.control_result, 80) + '</div>' : '');
                }]}
    ];

    function buildBody(values) {
        var v = values || {}, html = '';
        LAYOUT.forEach(function (st) {
            var n = st.records.length;
            st.records.forEach(function (rec, i) {
                html += '<tr>';
                if (i === 0) {
                    html += '<td rowspan="'+n+'" class="c mid">'+st.no+'</td>' +
                        '<td rowspan="'+n+'" class="c mid">'+st.name+'</td>' +
                        '<td rowspan="'+n+'" class="c mid crit">'+(st.criteriaHtml || st.criteria)+'</td>';
                }
                html += '<td class="rec">' + rec(v) + '</td><td class="sign"></td></tr>';
            });
        });
        return html;
    }

    function buildHtml(o) {
        var m = o.meta || {}, f = o.form || {};
        var charts = o.charts || [];
        var head =
            '<div class="hdr">' +
            '<table class="top"><tr>' +
            '<td class="title">멸 균 일 지</td>' +
            '<td class="apx"><div class="lab">담당자</div><div class="sig"></div></td>' +
            '<td class="apx"><div class="lab">승 인</div><div class="sig"></div></td>' +
            '</tr></table>' +
            '<table class="info"><tr>' +
            '<td class="th">멸균일</td><td>'+esc(m.sterilDate||'')+'</td>' +
            '<td class="th">멸균기 관리번호</td><td>'+esc(m.equipNo||'')+'</td>' +
            '<td class="th">담당자</td><td>'+esc(m.actor||'')+'</td>' +
            '</tr></table>' +
            '<div class="sec">멸균품목</div>' +
            '<table class="info"><tr>' +
            '<td class="th">모델명</td><td style="width:38%">'+esc(m.modelName||'')+'</td>' +
            '<td class="th">멸균 수량(블리스터 단위)</td><td>'+esc(m.qty||'')+' EA</td>' +
            '</tr></table>' +
            '<div class="note">멸균일지의 압력은 게이지압력임<br>게이지 압력 = 절대압력 - 대기압(1bar)</div>' +
            '<div class="sec">공정절차 및 기록</div>' +
            '</div>';

        var table =
            '<table class="main"><thead><tr>' +
            '<th style="width:6%">번호</th><th style="width:12%">공정 단계</th>' +
            '<th style="width:24%">작업절차 및 기준</th><th>기록</th><th style="width:16%">작업자 서명</th>' +
            '</tr></thead><tbody>' + buildBody(o.values) + '</tbody></table>';

        var foot = '<div class="foot"><span>'+esc(f.FormCode||'F710-3')+'</span>' +
            '<span>Rev.'+esc(f.Revision||1)+'</span><span>미라셀(주)</span></div>';

        var pages = charts.map(function (c) {
            return '<div class="page land"><div class="gtitle">'+esc(c.title)+'</div>' +
                '<img class="gimg" src="'+c.dataUrl+'">' + foot + '</div>';
        }).join('');

        return '<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8">' +
            '<title>멸균일지 '+esc(m.sterilDate||'')+'</title><style>'+CSS+'</style></head><body>' +
            '<div class="page">' + head + table + foot + '</div>' + pages +
            '</body></html>';
    }

    var CSS = [
        '@page{size:A4 portrait;margin:12mm 10mm}',
        '@page land{size:A4 landscape;margin:10mm 12mm}',
        '.page.land{page:land;min-height:auto}',
        '*{box-sizing:border-box}',
        'body{font-family:"Malgun Gothic","맑은 고딕",sans-serif;font-size:11pt;color:#000;margin:0}',
        '@media screen{body{background:#e9edf1;padding:16px}',
        '  .page{background:#fff;width:210mm;margin:0 auto 16px;box-shadow:0 2px 10px rgba(0,0,0,.18)}',
        '  .page.land{width:297mm}}',
        '.page{page-break-after:always;position:relative;min-height:262mm;padding-bottom:14mm}',
        '.page:last-child{page-break-after:auto}',
        'table{border-collapse:collapse;width:100%}',
        '.top{margin-bottom:0}',
        '.top td{border:1px solid #000;padding:0}',
        '.top .title{border:none;text-align:center;font-size:20pt;font-weight:700;letter-spacing:8px;padding:10px 0}',
        '.top .apx{width:14%;vertical-align:top}',
        '.top .lab{border-bottom:1px solid #000;text-align:center;font-size:10pt;padding:2px}',
        '.top .sig{height:34px}',
        '.info{margin-top:-1px}',
        '.info td{border:1px solid #000;padding:5px 7px;height:26px}',
        '.info .th{background:#fff;font-weight:600;text-align:center;width:14%;white-space:nowrap}',
        '.sec{margin:9px 0 3px;font-weight:600;font-size:10.5pt}',
        '.note{text-align:right;font-size:9pt;line-height:1.35;margin-top:4px}',
        '.main{margin-top:2px}',
        '.main th{border:1px solid #000;background:#e8e8e8;padding:6px 4px;font-size:10.5pt}',
        '.main td{border:1px solid #000;padding:6px 8px;font-size:10.5pt;line-height:1.7}',
        '.c{text-align:center}.mid{vertical-align:middle}',
        '.crit{font-size:10pt;line-height:1.5}',
        '.rec{vertical-align:middle}',
        '.sign{height:44px}',
        '.sub{width:100%;border-collapse:collapse}',
        '.sub td{border:1px solid #000;text-align:center;font-size:9.5pt;padding:3px;line-height:1.3}',
        '.u{display:inline-block;border-bottom:1px solid #000;text-align:center;padding:0 4px;min-height:1em}',
        '.foot{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:space-between;font-size:10pt}',
        '.gtitle{text-align:center;font-size:15pt;font-weight:700;margin:6px 0 12px}',
        '.gimg{width:100%;height:auto;border:1px solid #666}',
        '.page.land .gimg{width:100%;max-height:170mm;object-fit:contain}'
    ].join('');

    function print(o) {
        var w = window.open('', '_blank');
        if (!w) { alert('팝업이 차단되었습니다. 팝업을 허용해주세요.'); return; }
        w.document.write(buildHtml(o));
        w.document.close();
        // 이미지 로딩 후 인쇄
        w.onload = function () { setTimeout(function () { w.focus(); w.print(); }, 250); };
    }

    return { buildHtml: buildHtml, print: print, LAYOUT: LAYOUT };
}));