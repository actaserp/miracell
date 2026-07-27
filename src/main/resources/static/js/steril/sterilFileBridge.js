/* =====================================================================
 * sterilFileBridge.js — 공용 첨부(ax5FileUploadClass) ↔ 멸균일지 엔진 연결
 *
 *  ax5 업로더의 upload_callback 은 File 객체를 주지 않고 file_id 만 준다.
 *  → 업로드된 파일을 /api/files/download 로 되받아서 파싱한다.
 *  → 이 경로가 "배치 재진입 시 복원"과 완전히 동일하므로 코드 한 벌로 끝난다.
 *
 *  ★ 이 프로젝트의 jQuery 는 3.0 미만이라
 *      - Deferred 에 .catch() 가 없고
 *      - .then() 이 네이티브 Promise 를 언래핑하지 못한다(Promise 객체 그대로 전달)
 *    → jQuery Deferred 를 체인에 절대 섞지 않는다. 모든 비동기는 네이티브 Promise 로 감싼다.
 *
 *  전제: sterilLogEngine.js 선로딩
 * ===================================================================== */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) module.exports = factory();
    else root.SterilFiles = factory();
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    var CFG = {
        tableName:   'STERIL_BATCH',
        attachName:  'log',
        listUrl:     '/api/common/attach_file/detailFiles',
        downloadUrl: '/api/files/download',
        metaListUrl: '/api/production/steril/file_list',
        metaSaveUrl: '/api/production/steril/file_save',
        imageExt:    ['jpg', 'jpeg', 'png', 'gif', 'bmp']
    };

    /* -----------------------------------------------------------------
     * 통신 — 프로젝트 공용 AjaxUtil 을 쓴다.
     *   · 메타 조회 : AjaxUtil.getSyncData / 저장 : AjaxUtil.postJsonData
     *   · 파일 본문 다운로드 : XHR
     *       AjaxUtil.downloadFile 은 브라우저 저장 대화상자를 띄우는 용도라
     *       "내용을 읽어서 파싱" 하는 목적에는 쓸 수 없다.
     *   ※ jQuery 3 미만이라 Deferred 를 체인에 섞지 않는다. 밖으로는 네이티브 Promise 만.
     * ----------------------------------------------------------------- */

    /** 공용 GET(동기). 응답 형태가 화면마다 달라 {data:[...]} / [...] 둘 다 받는다 */
    function getList(url, params) {
        var r;
        try { r = AjaxUtil.getSyncData(url, params || {}); }
        catch (e) { return []; }
        if (!r) return [];
        if (Array.isArray(r)) return r;
        if (r.success === false) return [];
        return r.data || [];
    }

    /** 공용 JSON POST. CSRF·contentType·stringify·spjangcd 는 유틸이 처리한다 */
    function postBody(url, body) {
        return new Promise(function (resolve, reject) {
            try {
                AjaxUtil.postJsonData(url, body, function (r) {
                    r = r || {};
                    if (r.success === false) reject(new Error(r.message || '저장에 실패했습니다.'));
                    else resolve(r);
                });
            } catch (e) { reject(e); }
        });
    }

    /** 파일 본문을 텍스트로. EasyLog 의 ℃ 기호 때문에 ISO-8859-1 로 디코드한다
     *  (컬럼명 매칭이 ASCII 부분일치라 이걸로 충분) */
    function fetchText(fileId) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', CFG.downloadUrl + '?file_id=' + encodeURIComponent(fileId), true);
            xhr.responseType = 'arraybuffer';
            xhr.withCredentials = true;
            xhr.onload = function () {
                if (xhr.status < 200 || xhr.status >= 300) {
                    reject(new Error('다운로드 실패 (' + xhr.status + ')')); return;
                }
                try {
                    var buf = new Uint8Array(xhr.response);
                    if (typeof TextDecoder !== 'undefined') {
                        resolve(new TextDecoder('iso-8859-1').decode(buf));
                    } else {
                        var out = '';
                        for (var i = 0; i < buf.length; i++) out += String.fromCharCode(buf[i]);
                        resolve(out);
                    }
                } catch (e) { reject(e); }
            };
            xhr.onerror = function () { reject(new Error('다운로드 중 네트워크 오류')); };
            xhr.send();
        });
    }

    function listAttached(batchId) {
        return getList(CFG.listUrl, {
            TableName: CFG.tableName, DataPk: batchId, attachName: CFG.attachName
        });
    }

    function isImage(name) {
        var m = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
        return !!m && CFG.imageExt.indexOf(m[1]) >= 0;
    }

    /* -----------------------------------------------------------------
     * 배치의 첨부 전체를 읽어 파싱 + role 배정
     *   저장된 메타(role/useForCalc)가 있으면 자동배정을 덮어쓴다
     *   — 담당자가 손으로 고친 배정을 자동배정이 되돌리면 안 되므로.
     * ----------------------------------------------------------------- */
    function loadBatch(batchId) {
        var savedMeta = {};
        getList(CFG.metaListUrl, { batch_id: batchId })
            .forEach(function (m) { savedMeta[m.attach_file_id] = m; });

        var attached = listAttached(batchId);

        return Promise.all(attached.map(function (f) {
            if (isImage(f.fileNm)) {
                return Promise.resolve({ fileName: f.fileNm, kind: 'image', rows: [], warn: [] });
            }
            return fetchText(f.fileId)
                .then(function (txt) { return SterilLog.parse(f.fileNm, txt); })
                .catch(function (e) {
                    return { fileName: f.fileNm, kind: 'unknown', rows: [], warn: [e.message] };
                });
        })).then(function (parsed) {
            parsed.forEach(function (p, i) { p.fileId = attached[i].fileId; });

            var dat = parsed.filter(function (p) { return p.kind !== 'image'; });
            var img = parsed.filter(function (p) { return p.kind === 'image'; });

            var assigned = SterilLog.assignRoles(dat).concat(img.map(function (p) {
                return { parsed: p, role: 'bi_photo', useForCalc: 'N', detectedBy: 'auto', warn: [] };
            }));

            // 저장된 배정이 자동배정을 이긴다 (담당자가 고친 걸 되돌리면 안 되므로)
            assigned.forEach(function (a) {
                var m = savedMeta[a.parsed.fileId];
                if (!m) return;
                if (m.file_role)    a.role       = m.file_role;
                if (m.use_for_calc) a.useForCalc = m.use_for_calc;
                a.detectedBy  = m.detected_by || a.detectedBy;
                a.loggerLabel = m.logger_label || '';
            });
            return assigned;
        });
    }

    /* 배정 결과를 steril_batch_file 로 저장 */
    function saveMeta(batchId, assigned) {
        var rows = (assigned || []).map(function (a) {
            var p = a.parsed;
            return {
                attach_file_id: p.fileId,
                file_name:      p.fileName,
                file_role:      a.role,
                use_for_calc:   a.useForCalc,
                logger_label:   a.loggerLabel || null,
                serial_no:      p.serial || null,
                data_from:      p.from ? p.from.toISOString() : null,
                data_to:        p.to   ? p.to.toISOString()   : null,
                detected_by:    a.detectedBy
            };
        });
        return postBody(CFG.metaSaveUrl, { batch_id: batchId, files: rows });
    }

    /* 업로더 생성 헬퍼. onReload(assigned) 는 업로드/삭제 후마다 호출된다 */
    function makeUploader(divId, batchId, onReload) {
        if (!batchId) throw new Error('배치를 먼저 저장해야 파일을 첨부할 수 있습니다.');

        var reload = function () {
            loadBatch(batchId).then(function (assigned) {
                saveMeta(batchId, assigned).catch(function (e) { console.warn(e); });
                onReload(assigned);
            }).catch(function (e) { console.warn(e); });
        };

        // 권한 조회가 없는 화면도 있으므로 방어적으로
        var canWrite = true;
        try { if (typeof userinfo !== 'undefined' && userinfo.can_write) canWrite = !!userinfo.can_write(); }
        catch (e) { canWrite = true; }

        return new ax5FileUploadClass(divId, batchId, {
            table_name:   CFG.tableName,
            attach_name:  CFG.attachName,
            // ★ ax5 기본값에 csv 가 없다. 빠지면 멸균기 파일이 아예 안 올라간다.
            accept_files: 'csv,txt,jpg,jpeg,png,pdf',
            multiple:     true,
            max_count:    15,      // 로거 다수 + 멸균기 2 + BI 사진
            file_size:    100,     // MB
            can_write:    canWrite,
            // 다른 화면의 업로더와 element id 가 겹치지 않게 고유 접두어
            divFileUpload:      'stlUploadDiv',
            btnAddFile:         'stlAddFile',
            btnRemoveAllFile:   'stlRemoveAll',
            btnDownloadAllFile: 'stlDownloadAll',
            fileUploadBox:      'stlUploadBox',
            inputFileId:        'stlFileIds',
            inputDataPk:        'stlDataPk',
            // 콜백은 마지막 file_id 하나만 준다 → 인자를 쓰지 말고 전체를 다시 읽는다
            upload_callback:     function () { reload(); },
            delete_callback:     function () { reload(); },
            file_click_callback: function () { }
        });
    }

    return {
        CFG: CFG,
        loadBatch: loadBatch,
        saveMeta: saveMeta,
        fetchText: fetchText,
        listAttached: listAttached,
        makeUploader: makeUploader
    };
}));