package mes.app.production.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

/**
 * 자동화 장비 실적 — 가동 구간 생성 + 차수 매칭.
 *
 * ─────────────────────────────────────────────────────────────
 *  흐름
 *    tag_data (OPC UA 서버가 1초마다 insert)
 *      → collect() 5초마다: 설비별로 새 행만 읽어 가동 구간(equ_auto_seg)을 열고 닫는다
 *      → 조립 화면: 작업자가 구간을 용기(차수, mat_produce)에 붙인다  (link)
 *      → 붙은 차수의 GoodQty·StartTime 은 구간 합계로 계속 따라간다 (완료 전까지)
 *      → 완료는 기존 item_finish 그대로 — BOM 차감·입고·작지 롤업은 거기서 한다
 *
 *  ★ 수집 경로를 바꿀 곳은 readRows() 하나다.
 *    나중에 Milo 구독으로 바꿔도 구간 로직은 그대로 쓴다.
 * ─────────────────────────────────────────────────────────────
 *
 *  구간 규칙 (AutoState 의미는 업체 확인 전 — run_value 로 바꿀 수 있게 뒀다)
 *    시작 : AutoState 가 run_value 로 바뀐 순간, 또는 카운트가 오른 순간
 *    종료 : AutoState 가 run_value 에서 벗어난 순간
 *           또는 idle_sec 동안 카운트가 안 오름 (켜놓고 안 만드는 경우)
 *    종료시각 = 마지막으로 카운트가 오른 시각 (켜놓고 놀던 시간은 빼낸다)
 *    수량 0 인 구간은 남기지 않는다.
 *
 *  ★ 카운터 리셋: 값이 줄면 0 에서 다시 센 것으로 본다(증가분 = 현재값).
 *  ★ 품질(quality) 192 가 아닌 값은 버린다.
 *  ★ ErrCount 는 불량인지 에러 횟수인지 확인 전이라 구간에 표시만 하고
 *    차수 DefectQty 에는 넣지 않는다.
 */
@Service
public class AutoEquipService {

	private static final Logger log = LoggerFactory.getLogger(AutoEquipService.class);
	private static final int LOCK_KEY = 860102;
	private static final int MAX_ROWS = 5000;          // 한 번에 읽는 최대 시각 수 (밀려 있으면 다음 회차에 이어서)
	private static final int FIRST_LOOKBACK_MIN = 10;  // 처음 켤 때 과거 몇 분부터 읽을지
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Autowired SqlRunner sqlRunner;
	@Autowired TransactionTemplate transactionTemplate;

	private MapSqlParameterSource p() { return new MapSqlParameterSource(); }

	private List<Map<String, Object>> nz(List<Map<String, Object>> r) { return r == null ? new ArrayList<>() : r; }

	// =================================================================
	// 1. 수집 — 구간 생성
	// =================================================================

	/** 열린 구간 하나의 작업 상태 */
	static class Seg {
		Integer id;
		LocalDateTime start, end, lastProd;
		double good, err;
		Integer mpId;
		boolean dirty;
	}

	@Scheduled(fixedDelay = 5000, initialDelay = 20000)
	public void collect() {
		List<Map<String, Object>> eqs = nz(sqlRunner.getRows(
				"SELECT * FROM equ_auto WHERE use_yn = 'Y'", p()));
		for (Map<String, Object> eq : eqs) {
			Integer eqId = toInt(eq.get("equipment_id"));
			try {
				// 설비마다 트랜잭션을 나눈다 — 한 설비 오류가 다른 설비를 막지 않게
				transactionTemplate.executeWithoutResult(st -> processEquip(eq));
			} catch (Exception e) {
				log.error("[auto] 설비 {} 구간 처리 실패", eqId, e);
			}
		}
	}

	private void processEquip(Map<String, Object> eq) {
		Integer eqId = toInt(eq.get("equipment_id"));

		// 서버가 여러 대여도 한 설비는 한 곳에서만 처리
		Map<String, Object> lk = sqlRunner.getRow(
				"SELECT pg_try_advisory_xact_lock(:k, :eq) AS ok",
				p().addValue("k", LOCK_KEY).addValue("eq", eqId));
		if (lk == null || !Boolean.TRUE.equals(lk.get("ok"))) return;

		String prefix = String.valueOf(eq.get("tag_prefix"));
		String tagC = prefix + eq.get("count_tag");
		String tagE = prefix + eq.get("err_tag");
		String tagS = prefix + eq.get("state_tag");
		double runValue = toD(eq.get("run_value"));
		long idleSec = toInt(eq.get("idle_sec"));

		LocalDateTime dbNow = toLdt(sqlRunner.getRow("SELECT now()::timestamp AS n", p()).get("n"));
		LocalDateTime lastTs = toLdt(eq.get("last_ts"));
		if (lastTs == null) lastTs = dbNow.minusMinutes(FIRST_LOOKBACK_MIN);

		Double prevPd = toDn(eq.get("last_pd"));
		Double prevErr = toDn(eq.get("last_err"));
		boolean prevRun = "Y".equals(eq.get("last_run"));

		Seg seg = loadOpenSeg(eqId);
		Set<Integer> touchedMp = new LinkedHashSet<>();
		if (seg != null && seg.mpId != null) touchedMp.add(seg.mpId);

		List<Map<String, Object>> rows = readRows(tagC, tagE, tagS, lastTs);

		for (Map<String, Object> r : rows) {
			LocalDateTime ts = toLdt(r.get("ts"));
			// 빠진 태그는 직전 값 유지
			Double pd = toDn(r.get("pd")); if (pd == null) pd = prevPd;
			Double er = toDn(r.get("er")); if (er == null) er = prevErr;
			Double sv = toDn(r.get("st"));
			boolean run = (sv != null) ? Math.abs(sv - runValue) < 1e-9 : prevRun;

			if (pd == null) { lastTs = ts; prevRun = run; continue; }   // 카운트를 아직 못 받음
			if (prevPd == null) {                                       // 첫 기준값
				prevPd = pd; prevErr = (er == null ? 0d : er);
				lastTs = ts; prevRun = run; continue;
			}

			double dPd = pd - prevPd;
			if (dPd < 0) dPd = pd;                       // 리셋
			double curErr = (er == null ? prevErr : er);
			double dEr = curErr - (prevErr == null ? 0 : prevErr);
			if (dEr < 0) dEr = curErr;

			if (seg == null) {
				if (dPd > 0 || (run && !prevRun)) {
					seg = new Seg();
					seg.start = ts;
					seg.dirty = true;
					if (dPd > 0) { seg.good = dPd; seg.lastProd = ts; }
					seg.err = dEr;
				}
			} else {
				if (dPd > 0) { seg.good += dPd; seg.lastProd = ts; seg.dirty = true; }
				if (dEr > 0) { seg.err += dEr; seg.dirty = true; }

				LocalDateTime base = (seg.lastProd != null) ? seg.lastProd : seg.start;
				boolean stopped = !run && prevRun;
				boolean idle = Duration.between(base, ts).getSeconds() >= idleSec;
				if (stopped || idle) {
					closeSeg(eqId, seg, ts);
					seg = null;
				}
			}

			prevPd = pd; prevErr = curErr; prevRun = run; lastTs = ts;
		}

		// 데이터가 아예 끊긴 경우에도 idle 이 지나면 닫는다
		if (seg != null) {
			LocalDateTime base = (seg.lastProd != null) ? seg.lastProd : seg.start;
			if (Duration.between(base, dbNow).getSeconds() >= idleSec + 30) {
				closeSeg(eqId, seg, dbNow);
				seg = null;
			}
		}
		if (seg != null && seg.dirty) saveSeg(eqId, seg, String.valueOf(eq.get("spjangcd")));

		sqlRunner.execute("""
                UPDATE equ_auto
                   SET last_ts = :ts, last_pd = :pd, last_err = :er, last_run = :run, _modified = now()
                 WHERE equipment_id = :eq
                """, p().addValue("ts", Timestamp.valueOf(lastTs))
				.addValue("pd", prevPd).addValue("er", prevErr)
				.addValue("run", prevRun ? "Y" : "N").addValue("eq", eqId));

		for (Integer mp : touchedMp) recalcMp(mp);
	}

	/**
	 * 수집 경로 — 지금은 DB. 시각 하나 = 행 하나로 피벗한다.
	 * (tagname, timestamp) 인덱스와 (timestamp, tagname) PK 를 탄다.
	 */
	private List<Map<String, Object>> readRows(String tagC, String tagE, String tagS, LocalDateTime after) {
		return nz(sqlRunner.getRows("""
                SELECT d."timestamp" AS ts
                     , MAX(CASE WHEN d.tagname = :tc THEN d.value END) AS pd
                     , MAX(CASE WHEN d.tagname = :te THEN d.value END) AS er
                     , MAX(CASE WHEN d.tagname = :tst THEN d.value END) AS st
                  FROM equip_if.tag_data d
                 WHERE d.tagname IN (:tc, :te, :tst)
                   AND d.quality = 192
                   AND d."timestamp" > :after
                 GROUP BY d."timestamp"
                 ORDER BY d."timestamp"
                 LIMIT :lim
                """,
				p().addValue("tc", tagC).addValue("te", tagE).addValue("tst", tagS)
						.addValue("after", Timestamp.valueOf(after))
						.addValue("lim", MAX_ROWS)));
	}

	private Seg loadOpenSeg(Integer eqId) {
		Map<String, Object> r = sqlRunner.getRow("""
                SELECT id, start_time, last_prod_time, good_qty, err_qty, mat_produce_id
                  FROM equ_auto_seg WHERE equipment_id = :eq AND state = 'open'
                """, p().addValue("eq", eqId));
		if (r == null) return null;
		Seg s = new Seg();
		s.id = toInt(r.get("id"));
		s.start = toLdt(r.get("start_time"));
		s.lastProd = toLdt(r.get("last_prod_time"));
		s.good = toD(r.get("good_qty"));
		s.err = toD(r.get("err_qty"));
		s.mpId = r.get("mat_produce_id") == null ? null : toInt(r.get("mat_produce_id"));
		return s;
	}

	private void saveSeg(Integer eqId, Seg s, String spjangcd) {
		MapSqlParameterSource q = p()
				.addValue("eq", eqId)
				.addValue("st", Timestamp.valueOf(s.start))
				.addValue("lp", s.lastProd == null ? null : Timestamp.valueOf(s.lastProd))
				.addValue("g", s.good).addValue("e", s.err)
				.addValue("sp", spjangcd);
		if (s.id == null) {
			Map<String, Object> r = sqlRunner.getRow("""
                    INSERT INTO equ_auto_seg (equipment_id, start_time, last_prod_time, good_qty, err_qty, state, spjangcd)
                    VALUES (:eq, :st, :lp, :g, :e, 'open', :sp)
                    RETURNING id
                    """, q);
			s.id = toInt(r.get("id"));
		} else {
			sqlRunner.execute("""
                    UPDATE equ_auto_seg
                       SET last_prod_time = :lp, good_qty = :g, err_qty = :e, _modified = now()
                     WHERE id = :id
                    """, q.addValue("id", s.id));
		}
		s.dirty = false;
	}

	/** 구간 닫기. 수량이 없으면 남기지 않는다(매칭된 것은 지우지 않고 닫기만). */
	private void closeSeg(Integer eqId, Seg s, LocalDateTime at) {
		if (s.good <= 0 && s.err <= 0 && s.mpId == null) {
			if (s.id != null) sqlRunner.execute("DELETE FROM equ_auto_seg WHERE id = :id", p().addValue("id", s.id));
			return;
		}
		if (s.id == null) saveSeg(eqId, s, "ZZ");
		LocalDateTime end = (s.lastProd != null) ? s.lastProd : at;
		sqlRunner.execute("""
                UPDATE equ_auto_seg
                   SET state = 'closed', end_time = :end, last_prod_time = :lp
                     , good_qty = :g, err_qty = :e, _modified = now()
                 WHERE id = :id
                """, p().addValue("end", Timestamp.valueOf(end))
				.addValue("lp", s.lastProd == null ? null : Timestamp.valueOf(s.lastProd))
				.addValue("g", s.good).addValue("e", s.err).addValue("id", s.id));
	}

	/**
	 * 매칭된 차수 수량·불량·시작시각을 구간 합계로 맞춘다.
	 * ★ 완료된 차수는 건드리지 않는다(완료 = 사람이 확정한 값).
	 * ★ EndTime 은 넣지 않는다. 대시보드가 EndTime 이 있으면 «확정»으로 센다.
	 *   종료시각은 화면이 들고 있다가 완료 때 보낸다.
	 * ★ 구간이 붙으면 대기(wait) → 작업중(working).
	 *   장비가 이미 만든 것이라 「작업시작」을 또 누르게 할 이유가 없다.
	 * ★ ErrCount = 불량 수량으로 본다 (DefectQty).
	 */
	private void recalcMp(Integer mpId) {
		if (mpId == null) return;
		sqlRunner.execute("""
                UPDATE mat_produce mp
                   SET "GoodQty"   = s.q
                     , "DefectQty" = s.e
                     , "StartTime" = s.st
                     , "State"     = CASE WHEN mp."State" = 'wait' THEN 'working' ELSE mp."State" END
                     , _modified   = now()
                  FROM (SELECT SUM(good_qty) AS q, SUM(err_qty) AS e, MIN(start_time) AS st
                          FROM equ_auto_seg WHERE mat_produce_id = :mp) s
                 WHERE mp.id = :mp
                   AND mp."State" <> 'finished'
                   AND s.q IS NOT NULL
                """, p().addValue("mp", mpId));
	}

	/**
	 * 설비별 미매칭 구간 요약 — 작업조를 열지 않아도 누락이 보이게 A·B 화면에 띄운다.
	 * 매칭됐던 차수가 삭제(_status='d')된 구간도 미매칭으로 센다.
	 */
	public List<Map<String, Object>> getFreeSummary(String spjangcd) {
		return nz(sqlRunner.getRows("""
                SELECT a.equipment_id
                     , e."Name"                       AS equ_name
                     , COUNT(s.id)                    AS free_cnt
                     , COALESCE(SUM(s.good_qty), 0)   AS free_qty
                     , COALESCE(SUM(s.err_qty), 0)    AS free_err
                     , to_char(MIN(s.start_time), 'MM-DD HH24:MI') AS oldest
                  FROM equ_auto a
                  JOIN equ e ON e.id = a.equipment_id
                  JOIN equ_auto_seg s ON s.equipment_id = a.equipment_id
                  LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                 WHERE a.use_yn = 'Y'
                   AND (CAST(:sp AS varchar) IS NULL OR a.spjangcd = CAST(:sp AS varchar))
                   AND (s.mat_produce_id IS NULL OR COALESCE(mp._status,'a') <> 'a')
                 GROUP BY a.equipment_id, e."Name"
                 ORDER BY e."Name"
                """, p().addValue("sp", spjangcd)));
	}

	// =================================================================
	// 2. 조회
	// =================================================================

	/**
	 * 조립 화면 패널용.
	 *   enabled  : 이 설비가 자동화 설비로 등록돼 있나
	 *   status   : 가동 여부 · 마지막 수신
	 *   segs     : 최근 2일 구간 + 날짜 무관 미매칭/미완료 구간
	 *   mp_sum   : 차수별 합계 (화면이 용기 카드 수량을 맞춘다)
	 */
	public Map<String, Object> getSegList(Integer equipmentId) {
		Map<String, Object> data = new HashMap<>();
		Map<String, Object> eq = sqlRunner.getRow("""
                SELECT equipment_id, last_run
                     , to_char(last_ts, 'HH24:MI:SS') AS last_ts
                     , ROUND(EXTRACT(epoch FROM (now()::timestamp - last_ts))::numeric, 0) AS age_sec
                  FROM equ_auto WHERE equipment_id = :eq AND use_yn = 'Y'
                """, p().addValue("eq", equipmentId));
		data.put("enabled", eq != null);
		if (eq == null) return data;
		data.put("status", eq);

		data.put("segs", nz(sqlRunner.getRows("""
                SELECT s.id, s.state
                     , to_char(s.start_time, 'MM-DD HH24:MI:SS')                                AS start_disp
                     , to_char(COALESCE(s.end_time, s.last_prod_time, s.start_time), 'HH24:MI:SS') AS end_disp
                     , to_char(s.start_time, 'YYYY-MM-DD HH24:MI:SS')                           AS start_full
                     , to_char(COALESCE(s.end_time, s.last_prod_time, s.start_time), 'YYYY-MM-DD HH24:MI:SS') AS end_full
                     , s.good_qty, s.err_qty
                     , CASE WHEN mp.id IS NOT NULL AND COALESCE(mp._status,'a') = 'a' THEN mp.id END AS mp_id
                     , CASE WHEN COALESCE(mp._status,'a') = 'a' THEN mp."State" END               AS mp_state
                     , CASE WHEN COALESCE(mp._status,'a') = 'a' THEN mp."LotNumber" END           AS lot_no
                     , CASE WHEN COALESCE(mp._status,'a') = 'a' THEN m."Name" END                 AS mat_name
                  FROM equ_auto_seg s
                  LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                  LEFT JOIN material    m  ON m.id  = mp."Material_id"
                 WHERE s.equipment_id = :eq
                   AND (  s.start_time >= now()::date - 1
                       OR s.mat_produce_id IS NULL
                       OR COALESCE(mp._status,'a') <> 'a'
                       OR mp."State" <> 'finished')
                 ORDER BY s.start_time DESC
                 LIMIT 100
                """, p().addValue("eq", equipmentId))));

		data.put("mp_sum", nz(sqlRunner.getRows("""
                SELECT s.mat_produce_id AS mp_id
                     , SUM(s.good_qty)  AS qty
                     , SUM(s.err_qty)   AS def_qty
                     , to_char(MIN(s.start_time), 'YYYY-MM-DD HH24:MI') AS start_str
                     , to_char(MAX(COALESCE(s.end_time, s.last_prod_time, s.start_time)), 'YYYY-MM-DD HH24:MI') AS end_str
                     , COUNT(*) FILTER (WHERE s.state = 'open') AS open_cnt
                  FROM equ_auto_seg s
                  JOIN mat_produce mp ON mp.id = s.mat_produce_id
                 WHERE s.equipment_id = :eq
                   AND COALESCE(mp._status,'a') = 'a'
                   AND mp."State" <> 'finished'
                 GROUP BY s.mat_produce_id
                """, p().addValue("eq", equipmentId))));
		return data;
	}

	private Map<String, Object> mpSummary(Integer mpId) {
		Map<String, Object> r = sqlRunner.getRow("""
                SELECT :mp AS mp_id
                     , COALESCE(SUM(good_qty), 0) AS qty
                     , COALESCE(SUM(err_qty), 0)  AS def_qty
                     , to_char(MIN(start_time), 'YYYY-MM-DD HH24:MI') AS start_str
                     , to_char(MAX(COALESCE(end_time, last_prod_time, start_time)), 'YYYY-MM-DD HH24:MI') AS end_str
                     , COUNT(*) FILTER (WHERE state = 'open') AS open_cnt
                  FROM equ_auto_seg WHERE mat_produce_id = :mp
                """, p().addValue("mp", mpId));
		return r == null ? new HashMap<>() : r;
	}

	// =================================================================
	// 3. 매칭 · 해제 · 분할
	// =================================================================

	/**
	 * 구간(여러 개 가능)을 차수 하나에 붙인다.
	 * 같은 용기를 여러 구간에 걸쳐 만들었으면 구간을 하나씩 계속 붙이면 합산된다.
	 */
	@Transactional
	public AjaxResult link(List<Integer> segIds, Integer mpId, Integer userId) {
		AjaxResult r = new AjaxResult();
		r.success = false;

		Map<String, Object> mp = sqlRunner.getRow("""
                SELECT id, "State" AS st, COALESCE(_status,'a') AS s2, "Equipment_id" AS eq
                  FROM mat_produce WHERE id = :mp
                """, p().addValue("mp", mpId));
		if (mp == null || !"a".equals(mp.get("s2"))) { r.message = "용기(차수)를 찾을 수 없습니다."; return r; }
		if ("finished".equals(mp.get("st"))) { r.message = "완료된 용기에는 붙일 수 없습니다. 완료취소 후 붙이세요."; return r; }

		for (Integer sid : segIds) {
			Map<String, Object> s = sqlRunner.getRow("""
                    SELECT s.id, s.equipment_id, s.mat_produce_id
                         , (mp.id IS NOT NULL AND COALESCE(mp._status,'a') = 'a') AS linked_alive
                      FROM equ_auto_seg s
                      LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                     WHERE s.id = :id
                    """, p().addValue("id", sid));
			if (s == null) { r.message = "구간을 찾을 수 없습니다. (" + sid + ")"; return r; }
			if (mp.get("eq") != null && !toInt(mp.get("eq")).equals(toInt(s.get("equipment_id")))) {
				r.message = "다른 설비의 구간입니다. 작업조 설비를 확인하세요."; return r;
			}
			Object cur = s.get("mat_produce_id");
			if (cur != null && Boolean.TRUE.equals(s.get("linked_alive")) && !toInt(cur).equals(mpId)) {
				r.message = "이미 다른 용기에 붙은 구간입니다. 먼저 해제하세요."; return r;
			}
		}

		sqlRunner.execute("""
                UPDATE equ_auto_seg
                   SET mat_produce_id = :mp, linked_at = now(), linker_id = :u, _modified = now()
                 WHERE id IN (:ids)
                """, p().addValue("mp", mpId).addValue("u", userId).addValue("ids", segIds));
		recalcMp(mpId);

		r.success = true;
		r.data = mpSummary(mpId);
		return r;
	}

	/**
	 * 구간 일부만 붙이기 — 「이 용기에 N개」.
	 * 사람이 시각을 입력하지 않는다. 장비 카운트를 처음부터 더해 가다가 N 에 닿은
	 * 시각에서 구간을 자르고, 앞부분만 붙인다. 나머지는 미매칭 구간으로 남는다.
	 * ★ 카운트가 한 번에 2 이상 오르면 정확히 N 이 아니라 N 을 넘는 첫 지점에서 잘린다.
	 * ★ 가동중(open) 구간은 아직 세는 중이라 전체로만 붙인다.
	 */
	@Transactional
	public AjaxResult linkQty(Integer segId, Integer mpId, Double qty, Integer userId) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		Map<String, Object> s = sqlRunner.getRow("""
                SELECT s.*, a.tag_prefix, a.count_tag
                     , (mp.id IS NOT NULL AND COALESCE(mp._status,'a') = 'a') AS linked_alive
                  FROM equ_auto_seg s JOIN equ_auto a ON a.equipment_id = s.equipment_id
                  LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                 WHERE s.id = :id
                """, p().addValue("id", segId));
		if (s == null) { r.message = "구간을 찾을 수 없습니다."; return r; }

		double good = toD(s.get("good_qty"));
		if (qty == null || qty >= good) return link(List.of(segId), mpId, userId);
		if (qty <= 0) { r.message = "붙일 수량을 입력하세요."; return r; }
		if ("open".equals(s.get("state"))) { r.message = "가동중인 구간은 끝난 뒤 나눠 붙일 수 있습니다."; return r; }
        /* ★ 붙어 있던 용기가 삭제(_status='d')됐으면 미매칭으로 본다.
             화면도 그렇게 보여 주는데 여기서만 막으면 「미매칭인데 이미 붙었다」가 된다. */
		if (Boolean.TRUE.equals(s.get("linked_alive"))) { r.message = "이미 붙은 구간입니다. 먼저 해제하세요."; return r; }

		LocalDateTime start = toLdt(s.get("start_time"));
		LocalDateTime end = toLdt(s.get("end_time"));
		List<Map<String, Object>> rows = nz(sqlRunner.getRows("""
                SELECT d."timestamp" AS ts, d.value AS pd
                  FROM equip_if.tag_data d
                 WHERE d.tagname = :tc AND d.quality = 192
                   AND d."timestamp" BETWEEN :from AND :to
                 ORDER BY d."timestamp"
                """, p().addValue("tc", s.get("tag_prefix") + String.valueOf(s.get("count_tag")))
				.addValue("from", Timestamp.valueOf(start.minusSeconds(30)))
				.addValue("to", Timestamp.valueOf(end))));

		double cum = 0;
		Double pp = null;
		LocalDateTime at = null;
		for (Map<String, Object> x : rows) {
			LocalDateTime ts = toLdt(x.get("ts"));
			Double pd = toDn(x.get("pd"));
			if (pd == null) continue;
			if (pp != null && !ts.isBefore(start)) {
				double d = pd - pp; if (d < 0) d = pd;
				cum += d;
				if (cum >= qty) { at = ts; break; }
			}
			pp = pd;
		}
		if (at == null || !at.isBefore(end)) {
			r.message = "장비 기록에서 자를 지점을 찾지 못했습니다. 전체로 붙여 주세요.";
			return r;
		}

		AjaxResult sp = split(segId, at.format(TS));
		if (!sp.success) return sp;
		return link(List.of(segId), mpId, userId);
	}

	@Transactional
	public AjaxResult unlink(Integer segId) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		Map<String, Object> s = sqlRunner.getRow("""
                SELECT s.mat_produce_id AS mp, mp."State" AS st
                  FROM equ_auto_seg s LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                 WHERE s.id = :id
                """, p().addValue("id", segId));
		if (s == null || s.get("mp") == null) { r.message = "매칭된 구간이 아닙니다."; return r; }
		if ("finished".equals(s.get("st"))) { r.message = "완료된 실적의 구간입니다. 완료취소 후 해제하세요."; return r; }

		Integer mpId = toInt(s.get("mp"));
		sqlRunner.execute("""
                UPDATE equ_auto_seg SET mat_produce_id = NULL, linked_at = NULL, linker_id = NULL, _modified = now()
                 WHERE id = :id
                """, p().addValue("id", segId));
		recalcMp(mpId);

		r.success = true;
		r.data = mpSummary(mpId);
		return r;
	}

	/**
	 * 구간을 시각 기준으로 둘로 나눈다 — 장비를 안 멈추고 품목을 바꾼 경우.
	 * 앞쪽 수량은 tag_data 에서 다시 세고, 뒤쪽은 「전체 - 앞쪽」으로 둬서 합계가 어긋나지 않게 한다.
	 * 닫힌·미매칭 구간만.
	 */
	@Transactional
	public AjaxResult split(Integer segId, String atStr) {
		AjaxResult r = new AjaxResult();
		r.success = false;

		Map<String, Object> s = sqlRunner.getRow("""
                SELECT s.*, a.tag_prefix, a.count_tag, a.err_tag
                     , (mp.id IS NOT NULL AND COALESCE(mp._status,'a') = 'a') AS linked_alive
                  FROM equ_auto_seg s JOIN equ_auto a ON a.equipment_id = s.equipment_id
                  LEFT JOIN mat_produce mp ON mp.id = s.mat_produce_id
                 WHERE s.id = :id
                """, p().addValue("id", segId));
		if (s == null) { r.message = "구간을 찾을 수 없습니다."; return r; }
		if (!"closed".equals(s.get("state"))) { r.message = "가동이 끝난 구간만 나눌 수 있습니다."; return r; }
		if (Boolean.TRUE.equals(s.get("linked_alive"))) { r.message = "매칭을 해제한 뒤 나누세요."; return r; }

		LocalDateTime start = toLdt(s.get("start_time"));
		LocalDateTime end = toLdt(s.get("end_time"));
		LocalDateTime at;
		try { at = LocalDateTime.parse(atStr.trim(), TS); }
		catch (Exception e) { r.message = "시각 형식이 올바르지 않습니다. (yyyy-MM-dd HH:mm:ss)"; return r; }
		if (!at.isAfter(start) || !at.isBefore(end)) { r.message = "구간 안의 시각을 고르세요."; return r; }

		String prefix = String.valueOf(s.get("tag_prefix"));
		List<Map<String, Object>> rows = nz(sqlRunner.getRows("""
                SELECT d."timestamp" AS ts
                     , MAX(CASE WHEN d.tagname = :tc THEN d.value END) AS pd
                     , MAX(CASE WHEN d.tagname = :te THEN d.value END) AS er
                  FROM equip_if.tag_data d
                 WHERE d.tagname IN (:tc, :te) AND d.quality = 192
                   AND d."timestamp" BETWEEN :from AND :at
                 GROUP BY d."timestamp" ORDER BY d."timestamp"
                """, p().addValue("tc", prefix + s.get("count_tag")).addValue("te", prefix + s.get("err_tag"))
				.addValue("from", Timestamp.valueOf(start.minusSeconds(30)))
				.addValue("at", Timestamp.valueOf(at))));

		double g1 = 0, e1 = 0;
		Double pp = null, pe = null;
		LocalDateTime lp1 = null;
		for (Map<String, Object> x : rows) {
			LocalDateTime ts = toLdt(x.get("ts"));
			Double pd = toDn(x.get("pd")), er = toDn(x.get("er"));
			if (pd != null) {
				if (pp != null && !ts.isBefore(start)) {
					double d = pd - pp; if (d < 0) d = pd;
					if (d > 0) { g1 += d; lp1 = ts; }
				}
				pp = pd;
			}
			if (er != null) {
				if (pe != null && !ts.isBefore(start)) { double d = er - pe; if (d < 0) d = er; e1 += d; }
				pe = er;
			}
		}
		double gAll = toD(s.get("good_qty")), eAll = toD(s.get("err_qty"));
		g1 = Math.min(g1, gAll); e1 = Math.min(e1, eAll);

		sqlRunner.execute("""
                UPDATE equ_auto_seg
                   SET end_time = :at, last_prod_time = :lp, good_qty = :g, err_qty = :e
                     , mat_produce_id = NULL, linked_at = NULL, linker_id = NULL, _modified = now()
                 WHERE id = :id
                """, p().addValue("at", Timestamp.valueOf(at))
				.addValue("lp", lp1 == null ? null : Timestamp.valueOf(lp1))
				.addValue("g", g1).addValue("e", e1).addValue("id", segId));
		sqlRunner.execute("""
                INSERT INTO equ_auto_seg (equipment_id, start_time, end_time, last_prod_time, good_qty, err_qty, state, spjangcd)
                VALUES (:eq, :at, :end, :lp, :g, :e, 'closed', :sp)
                """, p().addValue("eq", toInt(s.get("equipment_id")))
				.addValue("at", Timestamp.valueOf(at)).addValue("end", Timestamp.valueOf(end))
				.addValue("lp", s.get("last_prod_time"))
				.addValue("g", gAll - g1).addValue("e", eAll - e1)
				.addValue("sp", s.get("spjangcd")));

		r.success = true;
		r.message = "구간을 나눴습니다. (" + fmt(g1) + " / " + fmt(gAll - g1) + ")";
		return r;
	}

	// =================================================================
	// 유틸
	// =================================================================

	private static String fmt(double v) { return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v); }

	private static Integer toInt(Object o) { return (o == null) ? null : ((Number) o).intValue(); }

	private static double toD(Object o) { Double d = toDn(o); return d == null ? 0d : d; }

	private static Double toDn(Object o) {
		if (o == null) return null;
		if (o instanceof Number) return ((Number) o).doubleValue();
		try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return null; }
	}

	private static LocalDateTime toLdt(Object o) {
		if (o == null) return null;
		if (o instanceof Timestamp) return ((Timestamp) o).toLocalDateTime();
		if (o instanceof LocalDateTime) return (LocalDateTime) o;
		String s = String.valueOf(o).replace('T', ' ');
		if (s.length() > 19) s = s.substring(0, 19);
		return LocalDateTime.parse(s, TS);
	}
}