/* =====================================================================
 *  MIRACELL MES — Service Worker (푸시 수신 전용)
 *
 *  ★ 반드시 static 최상위(/sw.js)에 둔다.
 *    /js/sw.js 에 두면 scope 가 '/js/' 로 잡혀 아무것도 못 받는다.
 *
 *  ★ 캐싱(fetch 핸들러)은 넣지 않는다.
 *    오프라인 캐시를 하면 태블릿이 옛날 재고 화면을 보게 된다.
 *    이 SW 의 역할은 "앱이 꺼져 있을 때 알림을 받는 것" 하나뿐이다.
 * ===================================================================== */

var ICON  = '/images/logo/miracell_log_a_192.png';
var BADGE = '/images/logo/miracell_log_a_192.png';

self.addEventListener('install', function (e) {
    self.skipWaiting();
});

self.addEventListener('activate', function (e) {
    e.waitUntil(self.clients.claim());
});

self.addEventListener('push', function (e) {
    var d = {};
    try {
        d = e.data ? e.data.json() : {};
    } catch (err) {
        d = { title: 'MIRACELL MES', body: e.data ? e.data.text() : '' };
    }

    var opts = {
        body:      d.body || '',
        icon:      ICON,
        badge:     BADGE,
        tag:       d.tag || ('noti-' + Date.now()),
        renotify:  true,
        timestamp: Date.now(),
        data:      { url: d.url || '/', notiId: d.notiId || null }
    };

    e.waitUntil(self.registration.showNotification(d.title || 'MIRACELL MES', opts));
});

self.addEventListener('notificationclick', function (e) {
    e.notification.close();

    var url = (e.notification.data && e.notification.data.url) || '/';

    e.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
            // 이미 떠 있는 창이 있으면 새 창을 열지 않고 그 창을 앞으로
            for (var i = 0; i < list.length; i++) {
                if ('focus' in list[i]) {
                    if (list[i].navigate && url !== '/') {
                        return list[i].focus().then(function (c) {
                            try { return c.navigate(url); } catch (err) { return c; }
                        });
                    }
                    return list[i].focus();
                }
            }
            return clients.openWindow(url);
        })
    );
});

/* 브라우저가 구독을 자동 갱신했을 때(만료·재발급).
   서버에 새 endpoint 를 다시 등록해 준다. */
self.addEventListener('pushsubscriptionchange', function (e) {
    e.waitUntil(
        self.registration.pushManager.getSubscription().then(function (sub) {
            if (!sub) return;
            var raw = function (name) {
                var k = sub.getKey(name);
                if (!k) return '';
                return btoa(String.fromCharCode.apply(null, new Uint8Array(k)))
                        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
            };
            return fetch('/api/push/resubscribe', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'endpoint=' + encodeURIComponent(sub.endpoint)
                    + '&p256dh=' + encodeURIComponent(raw('p256dh'))
                    + '&auth='   + encodeURIComponent(raw('auth'))
            });
        })
    );
});
