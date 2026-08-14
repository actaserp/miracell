/* ==========================================================================
   미라셀 MES 사용 매뉴얼 — 공용 스크립트
   목차 자동 생성 / 현재 위치 표시 / 검색 / 인쇄 / 모바일 목차 토글
   본문(h2·h3)만 고치면 목차와 레일이 따라온다. 목차를 손으로 관리하지 말 것.
   ========================================================================== */
(function () {
  'use strict';

  var main = document.querySelector('main');
  var nav  = document.querySelector('.mnav ol');
  var rail = document.querySelector('.rail');
  if (!main || !nav) return;

  var heads = [].slice.call(main.querySelectorAll('h2, h3'));
  var links = [];

  /* ---- 목차 생성 ---------------------------------------------------- */
  var curSub = null;
  heads.forEach(function (h, i) {
    if (!h.id) h.id = 'sec-' + i;
    var text = (h.getAttribute('data-nav') || h.textContent).trim();

    var li = document.createElement('li');
    var a  = document.createElement('a');
    a.href = '#' + h.id;
    a.textContent = text;
    li.appendChild(a);
    links.push(a);

    if (h.tagName === 'H2') {
      nav.appendChild(li);
      curSub = null;
    } else {
      if (!curSub) {
        curSub = document.createElement('ol');
        (nav.lastElementChild || nav).appendChild(curSub);
      }
      curSub.appendChild(li);
    }
  });

  /* ---- 공정 레일 ---------------------------------------------------- */
  var railLinks = rail ? [].slice.call(rail.querySelectorAll('a')) : [];

  /* ---- 현재 위치 표시 ------------------------------------------------ */
  var offset = 150;
  var ticking = false;

  function mark() {
    ticking = false;
    var y = window.scrollY + offset;
    var cur = heads[0];
    for (var i = 0; i < heads.length; i++) {
      if (heads[i].offsetTop <= y) cur = heads[i]; else break;
    }
    if (!cur) return;

    links.forEach(function (a) {
      a.classList.toggle('on', a.getAttribute('href') === '#' + cur.id);
    });

    // 레일은 h2 기준. h3 안이면 그 위의 h2 를 찾는다.
    var top = cur;
    if (cur.tagName === 'H3') {
      var idx = heads.indexOf(cur);
      while (idx > 0 && heads[idx].tagName !== 'H2') idx--;
      top = heads[idx];
    }
    railLinks.forEach(function (a) {
      a.classList.toggle('on', a.getAttribute('href') === '#' + top.id);
    });

    var btn = document.querySelector('.toTop');
    if (btn) btn.style.display = window.scrollY > 500 ? 'block' : 'none';
  }

  window.addEventListener('scroll', function () {
    if (!ticking) { ticking = true; requestAnimationFrame(mark); }
  }, { passive: true });
  window.addEventListener('resize', mark);
  mark();

  /* ---- 검색 (목차 필터) ---------------------------------------------- */
  var box = document.querySelector('.mnav-search');
  if (box) {
    box.addEventListener('input', function () {
      var q = box.value.trim().toLowerCase();
      links.forEach(function (a) {
        var hit = !q || a.textContent.toLowerCase().indexOf(q) > -1;
        a.parentNode.style.display = hit ? '' : 'none';
      });
      // 자식이 전부 숨으면 빈 하위 목록도 접는다
      [].slice.call(nav.querySelectorAll('ol')).forEach(function (sub) {
        var any = [].slice.call(sub.children).some(function (li) {
          return li.style.display !== 'none';
        });
        sub.style.display = any ? '' : 'none';
      });
    });
    box.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { box.value = ''; box.dispatchEvent(new Event('input')); }
    });
  }

  /* ---- 인쇄 / 목차 토글 ---------------------------------------------- */
  var pb = document.querySelector('[data-act="print"]');
  if (pb) pb.addEventListener('click', function () { window.print(); });

  var tb = document.querySelector('.navToggle');
  if (tb) tb.addEventListener('click', function () {
    document.body.classList.toggle('nav-open');
  });
  nav.addEventListener('click', function (e) {
    if (e.target.tagName === 'A') document.body.classList.remove('nav-open');
  });

  var top = document.querySelector('.toTop');
  if (top) top.addEventListener('click', function () {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
})();
