/* =====================================================================
 *  MIRACELL MES — 웹 푸시 클라이언트
 *
 *  index.html 의 SSE(sseConnect) 와 짝이 되는 두 번째 출구.
 *    SSE  : 화면이 켜져 있을 때  → 토스트 + 알림 뱃지
 *    PUSH : 화면이 꺼져 있을 때  → OS 알림
 *  둘 다 같은 notification 행에서 나온다.
 *
 *  ★ AjaxUtil 은 window 프로퍼티가 아니다(let 선언). typeof 로 확인할 것.
 * ===================================================================== */
var PushClient = (function () {

    var VAPID = null;   // 레이아웃에서 window.PUSH_PUBLIC_KEY 로 주입

    function urlB64ToUint8(base64) {
        var pad = '='.repeat((4 - base64.length % 4) % 4);
        var b64 = (base64 + pad).replace(/-/g, '+').replace(/_/g, '/');
        var raw = window.atob(b64);
        var arr = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) arr[i] = raw.charCodeAt(i);
        return arr;
    }

    function keyOf(sub, name) {
        var k = sub.getKey(name);
        if (!k) return '';
        return btoa(String.fromCharCode.apply(null, new Uint8Array(k)))
                .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
    }

    function post(url, data, ok) {
        if (typeof AjaxUtil !== 'undefined' && AjaxUtil.postAsyncData) {
            // AjaxUtil 이 _csrf 와 spjangcd 를 자동으로 붙인다
            AjaxUtil.postAsyncData(url, data, ok || function () {}, function () {});
        } else {
            console.warn('[push] AjaxUtil 미로딩 — 구독 등록 생략');
        }
    }

    return {

        supported: function () {
            return ('serviceWorker' in navigator)
                && ('PushManager' in window)
                && ('Notification' in window);
        },

        /** 현재 상태 : 'unsupported' | 'denied' | 'default' | 'granted' */
        status: function () {
            if (!this.supported()) return 'unsupported';
            return Notification.permission;
        },

        /** 페이지 로드 시 — 이미 허용된 경우에만 조용히 재등록 */
        boot: function () {
            VAPID = window.PUSH_PUBLIC_KEY || null;
            if (!VAPID) return;                                  // 서버에 키 미설정
            if (!this.supported()) return;
            if (Notification.permission !== 'granted') return;   // ★ 자동 요청 금지
            this._subscribe();
        },

        /**
         * 「기기 알림 켜기」 버튼에서만 호출.
         * ★ 반드시 사용자 클릭 안에서 호출해야 한다.
         *   로드 직후 자동 호출하면 작업자가 반사적으로 거부하고,
         *   한 번 거부하면 Chrome 사이트 설정에서 수동으로 풀어야 한다.
         */
        enable: function (cb) {
            var self = this;
            VAPID = window.PUSH_PUBLIC_KEY || VAPID;

            if (!this.supported()) {
                self._msg('이 기기·브라우저는 푸시 알림을 지원하지 않습니다.');
                if (cb) cb(false);
                return;
            }
            if (!VAPID) {
                self._msg('서버에 푸시 키가 설정되지 않았습니다.');
                if (cb) cb(false);
                return;
            }
            if (Notification.permission === 'denied') {
                self._msg('알림이 차단되어 있습니다. 주소창 왼쪽 자물쇠 → 사이트 설정 → 알림을 «허용»으로 바꿔 주세요.');
                if (cb) cb(false);
                return;
            }

            Notification.requestPermission().then(function (p) {
                if (p !== 'granted') {
                    self._msg('알림 권한이 허용되지 않았습니다.');
                    if (cb) cb(false);
                    return;
                }
                self._subscribe(function (okFlag) {
                    if (okFlag) self._msg('이 기기에서 알림을 받습니다.');
                    if (cb) cb(okFlag);
                });
            });
        },

        /** 이 기기만 알림 끄기 */
        disable: function (cb) {
            var self = this;
            if (!this.supported()) { if (cb) cb(false); return; }

            navigator.serviceWorker.getRegistration('/sw.js').then(function (reg) {
                if (!reg) { if (cb) cb(false); return; }
                return reg.pushManager.getSubscription().then(function (sub) {
                    if (!sub) { if (cb) cb(false); return; }
                    var ep = sub.endpoint;
                    return sub.unsubscribe().then(function () {
                        post('/api/push/unsubscribe', { endpoint: ep }, function () {
                            self._msg('이 기기의 알림을 껐습니다.');
                            if (cb) cb(true);
                        });
                    });
                });
            }).catch(function (e) {
                console.warn('[push] disable', e);
                if (cb) cb(false);
            });
        },

        /** 내 계정에 등록된 기기 수 (버튼 라벨 갱신용) */
        isSubscribed: function (cb) {
            if (!this.supported() || Notification.permission !== 'granted') { cb(false); return; }
            navigator.serviceWorker.getRegistration('/sw.js').then(function (reg) {
                if (!reg) { cb(false); return; }
                reg.pushManager.getSubscription().then(function (s) { cb(!!s); });
            }).catch(function () { cb(false); });
        },

        _subscribe: function (cb) {
            navigator.serviceWorker.register('/sw.js')
                .then(function (reg) {
                    return navigator.serviceWorker.ready.then(function () { return reg; });
                })
                .then(function (reg) {
                    return reg.pushManager.getSubscription().then(function (s) {
                        if (s) return s;
                        return reg.pushManager.subscribe({
                            userVisibleOnly: true,
                            applicationServerKey: urlB64ToUint8(VAPID)
                        });
                    });
                })
                .then(function (sub) {
                    post('/api/push/subscribe', {
                        endpoint:   sub.endpoint,
                        p256dh:     keyOf(sub, 'p256dh'),
                        auth:       keyOf(sub, 'auth'),
                        user_agent: navigator.userAgent.substring(0, 300)
                    }, function () { if (cb) cb(true); });
                })
                .catch(function (e) {
                    console.warn('[push] subscribe 실패', e);
                    if (cb) cb(false);
                });
        },

        _msg: function (t) {
            // 프로젝트 공용 토스트가 있으면 그걸 쓴다
            try { if (typeof Notify !== 'undefined' && Notify.success) { Notify.success(t); return; } } catch (e) {}
            try { if (typeof flash === 'function') { flash(t); return; } } catch (e) {}
            console.log('[push]', t);
        }
    };
})();

document.addEventListener('DOMContentLoaded', function () {
    try { PushClient.boot(); } catch (e) { console.warn('[push] boot', e); }
});
