package mes.app.dashboard.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 1공장 대시보드.
 *
 * ─────────────────────────────────────────────────────────────
 * 통합 대시보드(DashBoardMainService)와 따로 둔 이유
 *
 *   통합은 「공정」 축으로 1·2공장을 나란히 놓는다.
 *   1공장 요청은 「품목 구분」 축(용기 / PK / 혈액저장용기 / 완제품)에
 *   환경·작업인원·재고를 붙인 화면이라 축이 다르다.
 *   한 서비스에서 둘을 맞추면 한쪽을 고칠 때 다른 쪽이 조용히 흔들린다.
 *
 *   다만 같은 숫자를 두 화면이 다르게 말하면 안 되므로
 *   세척·멸균·부적합·완제품 KPI 는 MainService 를 그대로 부른다.
 * ─────────────────────────────────────────────────────────────
 *
 * ★ 실적일은 ProductionDate 가 아니다. COALESCE(EndTime, StartTime, ProductionDate).
 * ★ 진행중 차수의 GoodQty 는 시작 입력분(계획)이다. 화면에는 「진행중 N개」로만 쓴다.
 * ★ SqlRunner.getRows 는 오류 시 null. 반드시 nz().
 */
@Service
public class DashBoardF1Service {

	@Autowired
	private SqlRunner sqlRunner;

	@Autowired
	private DashBoardMainService mainService;

	@Autowired
	private EnvSensorService envSensorService;

	private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}

	private MapSqlParameterSource p(String spjangcd) {
		return new MapSqlParameterSource().addValue("spjangcd", spjangcd);
	}

	/*
	 * ═══ 지금 작업중 — 생산현황(2번)과 작업인원(3번)의 «단 하나의» 출처 ═══
	 *
	 *   예전에는 두 칸이 서로 다른 쿼리를 썼다.
	 *     · 생산현황은 「품목 이름」으로 구분(용기/PK/혈액저장용기)하고 날짜 제한이 없었고
	 *     · 작업인원은 「공정」으로 구분하고 오늘 시작한 것만 셌다
	 *   그래서 같은 블리스터 포장인데 위에는 3명, 아래는 1명이 나왔다.
	 *   세척은 mat_produce 가 아니라 wash_work 에 남는데 작업인원은 그걸 안 봐서 0명이었다.
	 *
	 *   이제 둘 다 아래 ACTIVE 한 덩어리를 쓴다. 구분도 공정(Code) 하나다.
	 *     sec       공정 코드 (bsc01 세척 · bsc02 조립 · bsc03 블리스터 포장 · bsc06 고주파 융착 · bsc04 멸균 · bsc05 포장)
	 *     item      화면에 적을 이름 (품목 / 세척기 / 멸균기)
	 *     qty,unit  진행중 수량과 단위 (개 · 건 · 로트)
	 *     pid       작업자 person.id
	 *
	 *   ★ 오늘 시작한 것만 (현장 요청 — 다음날이면 초기화).
	 *     종료를 안 누르고 퇴근한 어제 세션은 생산현황에서도, 작업인원에서도 빠진다.
	 *     두 칸이 같은 규칙이어야 서로 맞는다.
	 *   ★ 1공장만 (공정 Factory_id = 1).
	 */
	/** 센서 최신값 — 통합 대시보드와 같은 출처(EnvSensorService) */
	public List<Map<String, Object>> getEnv(String spjangcd) {
		return envSensorService.getEnv(spjangcd);
	}

	private static final String ACTIVE = """
			active AS (
			    -- 조립 · 블리스터 · 융착 · 포장 : 진행중 차수
			    SELECT pr."Code"                          AS sec
			         , COALESCE(om."Name", m."Name")      AS item
			         , COALESCE(mp."GoodQty", 0)          AS qty
			         , '개'                               AS unit
			         , mp."Actor_id"                      AS pid
			         , 'mat_produce'::text                AS src
			         , mp.id                              AS sid
			      FROM mat_produce mp
			      JOIN job_res jr ON jr.id = mp."JobResponse_id"
			      LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
			      LEFT JOIN process     pr ON pr.id = wc."Process_id"
			      LEFT JOIN material om  ON om.id  = mp."Material_id"
			      LEFT JOIN material m   ON m.id   = jr."Material_id"
			     WHERE COALESCE(mp._status,'a') = 'a'
			       AND mp."State" NOT IN ('finished','wait')   -- wait = 담아만 두고 시작 안 한 차수
			       AND mp."EndTime" IS NULL
			       AND COALESCE(pr."Factory_id", 1) = 1
			       AND pr."Code" IN ('bsc02','bsc03','bsc06','bsc05')
			       /* ★ 오늘 시작한 것만 — 단, 포장은 며칠씩 이어서 하는 공정이라 열려 있으면 다 센다
			            (포장 화면도 날짜 없이 열린 작업을 보여준다) */
			       AND (pr."Code" = 'bsc05'
			            OR COALESCE(mp."StartTime", mp."_created")::date = CURRENT_DATE)
			       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
			    UNION ALL
			    -- 세척 : wash_work 세션 (품목이 전부 완료되기 전까지 작업중)
			    SELECT 'bsc01'
			         , COALESCE(e."Name", '세척기')
			         , (SELECT COUNT(*) FROM wash_work_item wi
			             WHERE wi."WashWork_id" = ww.id AND wi."_status" = 'a')
			         , '건'
			         , ww."Actor_id"
			         , 'wash_work'
			         , ww.id
			      FROM wash_work ww
			      LEFT JOIN equ e ON e.id = ww."Equipment_id"
			     WHERE ww."_status" = 'a'
			       AND ww."WashDate" = CURRENT_DATE
			       AND NOT EXISTS (                       -- 품목이 있고 전부 done 이면 끝난 세션
			           SELECT 1 FROM wash_work_item wi
			            WHERE wi."WashWork_id" = ww.id AND wi."_status" = 'a'
			           HAVING COUNT(*) > 0 AND COUNT(*) = COUNT(*) FILTER (WHERE wi."State" = 'done'))
			    UNION ALL
			    -- 멸균 : 진행중 배치
			    SELECT 'bsc04'
			         , COALESCE(e."Name", '멸균기')
			         , (SELECT COUNT(*) FROM steril_batch_item bi WHERE bi."SterilBatch_id" = b.id)
			         , '로트'
			         , b."Actor_id"
			         , 'steril_batch'
			         , b.id
			      FROM steril_batch b
			      LEFT JOIN equ e ON e.id = b."Equipment_id"
			     WHERE b."State" = 'working'
			       AND COALESCE(b."StartTime"::date, b."SterilDate") = CURRENT_DATE
			)
			/* ★ 사람 = 대표 + 조원 전부 (현장 요청 — 「대표」 구분 없이 다 나온다).
			     조원은 mat_produce_member 한 곳에 있다. 차수는 예전 방식(MatProduce_id)과
			     새 방식(SourceTableName/SourceDataPk) 둘 다 본다. */
			, active_ppl AS (
			    SELECT a.sec, a.item, a.pid FROM active a WHERE a.pid IS NOT NULL
			    UNION
			    SELECT a.sec, a.item, m."Person_id"
			      FROM active a
			      JOIN mat_produce_member m
			        ON COALESCE(m._status,'a') = 'a'
			       AND (   (a.src = 'mat_produce' AND ((m."SourceTableName" = 'mat_produce' AND m."SourceDataPk" = a.sid)
			                                           OR m."MatProduce_id" = a.sid))
			            OR (a.src = 'wash_work'   AND m."SourceTableName" = 'wash_work' AND m."SourceDataPk" = a.sid))
			)
			""";

	// =================================================================
	// 생산현황 (공정별 진행중) — ACTIVE 그대로
	// =================================================================

	public List<Map<String, Object>> getWorking(String spjangcd) {
		String sql = "WITH " + ACTIVE + """
				SELECT a.sec AS proc_code, a.item AS mat_name, a.unit
				     , SUM(a.qty)                               AS qty
				     , COUNT(*)                                 AS session_cnt
				     , (SELECT STRING_AGG(DISTINCT pe."Name", ', ')
				          FROM active_ppl ap JOIN person pe ON pe.id = ap.pid
				         WHERE ap.sec = a.sec AND ap.item = a.item) AS workers
				  FROM active a
				 GROUP BY a.sec, a.item, a.unit
				 ORDER BY a.sec, SUM(a.qty) DESC
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	/**
	 * 오늘 완료 — 공정별.
	 *   조립·블리스터·융착 : 오늘 끝난 차수 양품
	 *   포장               : 완제품만 (CK 반제품은 빼야 같은 키트를 두 번 안 센다)
	 *   세척               : 오늘 끝난 세척 품목 수량
	 *   멸균               : 오늘 끝난 배치 수
	 */
	public List<Map<String, Object>> getTodayDone(String spjangcd) {
		String sql = """
				SELECT pr."Code" AS sec, SUM(COALESCE(mp."GoodQty",0)) AS qty, '개' AS unit
				  FROM mat_produce mp
				  JOIN job_res jr ON jr.id = mp."JobResponse_id"
				  LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				  LEFT JOIN process     pr ON pr.id = wc."Process_id"
				  LEFT JOIN material om  ON om.id  = mp."Material_id"
				  LEFT JOIN mat_grp  omg ON omg.id = om."MaterialGroup_id"
				 WHERE COALESCE(mp._status,'a') = 'a'
				   AND (mp."State" = 'finished' OR mp."EndTime" IS NOT NULL)
				   AND COALESCE(mp."EndTime", mp."StartTime", mp."ProductionDate"::timestamptz)::date = CURRENT_DATE
				   AND COALESCE(pr."Factory_id", 1) = 1
				   AND pr."Code" IN ('bsc02','bsc03','bsc06','bsc05')
				   AND (pr."Code" <> 'bsc05' OR COALESCE(omg."MaterialType",'') = 'product')
				   AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				 GROUP BY pr."Code"
				UNION ALL
				SELECT 'bsc01', SUM(COALESCE(wi."Qty",0)), '개'
				  FROM wash_work_item wi
				  JOIN wash_work ww ON ww.id = wi."WashWork_id" AND ww."_status" = 'a'
				 WHERE wi."_status" = 'a' AND wi."State" = 'done'
				   AND COALESCE(wi."EndTime"::date, ww."WashDate") = CURRENT_DATE
				UNION ALL
				SELECT 'bsc04', COUNT(*), '배치'
				  FROM steril_batch b
				 WHERE b."State" <> 'working' AND b."SterilDate" = CURRENT_DATE
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 작업인원 — ACTIVE + 업무 시작(품질검사)
	// =================================================================

	/**
	 * 1공장 인원 — 작업중 / 대기.
	 *
	 * ★ 공정 작업은 생산현황과 같은 ACTIVE 를 쓴다(두 칸의 사람이 항상 같다).
	 * ★ 품질검사처럼 실적이 없는 사람은 work_duty(업무 시작)로만 「작업중」이 된다.
	 * ★ 근태(연차·지각)는 MES 에 없다. 대기 = 오늘 시작한 작업이 없는 사람.
	 * ★ 1공장 계정(user_profile.Factory_id = 1)이 있는 사람만.
	 */
	public List<Map<String, Object>> getPeople(String spjangcd) {
		String sql = "WITH " + ACTIVE + """
				, busy AS (
				    SELECT a.pid, pr."Name" AS where_name, a.sec
				      FROM active_ppl a
				      LEFT JOIN process pr ON pr."Code" = a.sec
				    UNION ALL
				    SELECT COALESCE(u.personid, CASE WHEN u.username ~ '^[0-9]+$' THEN u.username::int END)
				         , COALESCE(d."TaskName", g."Name", '업무')
				         , 'quality'
				      FROM work_duty d
				      JOIN auth_user u ON u.id = d."User_id"
				      LEFT JOIN user_group g ON g.id = d."UserGroup_id"
				     WHERE d."EndTime" IS NULL AND d._status = 'a'
				       AND d."StartTime"::date = CURRENT_DATE
				)
				SELECT * FROM (
				    SELECT pe.id
				         , pe."Name"                                          AS name
				         , CASE WHEN COUNT(b.pid) > 0 THEN 'working' ELSE 'idle' END AS state
				         , STRING_AGG(DISTINCT b.where_name, ', ')            AS where_name
				         , STRING_AGG(DISTINCT b.sec, ',')                    AS sec_codes
				      FROM person pe
				      JOIN auth_user u     ON u.username = pe.id::text
				      JOIN user_profile up ON up."User_id" = u.id AND up."Factory_id" = 1
				      LEFT JOIN busy b ON b.pid = pe.id
				     WHERE pe."Factory_id" = 1
				       AND COALESCE(pe.rtflag,'0') <> '1'
				     GROUP BY pe.id, pe."Name"

				    UNION ALL

				    /* 품질검사 그룹 중 사원(person)과 연결되지 않은 계정 — id 는 음수 */
				    SELECT -u.id
				         , COALESCE(NULLIF(up."Name",''), NULLIF(u.first_name,''), u.username)
				         , CASE WHEN d.id IS NOT NULL THEN 'working' ELSE 'idle' END
				         , CASE WHEN d.id IS NOT NULL THEN COALESCE(d."TaskName", g."Name") END
				         , CASE WHEN d.id IS NOT NULL THEN 'quality' END
				      FROM auth_user u
				      JOIN user_profile up ON up."User_id" = u.id AND COALESCE(up."Factory_id", 1) = 1
				      JOIN user_group g    ON g.id = up."UserGroup_id" AND g."Code" = 'quality'
				      LEFT JOIN work_duty d ON d."User_id" = u.id AND d."EndTime" IS NULL AND d._status = 'a'
				                           AND d."StartTime"::date = CURRENT_DATE
				     WHERE COALESCE(u.is_active, true)
				       AND NOT EXISTS (SELECT 1 FROM person pe
				                        WHERE pe.id = COALESCE(u.personid,
				                                       CASE WHEN u.username ~ '^[0-9]+$' THEN u.username::int END))
				) x
				 ORDER BY (x.state = 'working') DESC, x.name
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 한 방에
	// =================================================================

	/**
	 * 30초 폴링용. 환경은 빠르게 따로 돈다(getEnv, 5초).
	 * 생산·인원은 한 번에 내려 서로 다른 시점을 말하지 않게 한다.
	 */
	public Map<String, Object> getDashboard(String spjangcd) {
		Map<String, Object> data = new HashMap<>();
		data.put("working",    getWorking(spjangcd));
		data.put("today_done", getTodayDone(spjangcd));
		data.put("people",     getPeople(spjangcd));
		data.put("kpi",        mainService.getKpi(spjangcd));          // f1_today_good 만 쓴다
		data.put("defect",     mainService.getDefectToday(spjangcd));  // 화면에서 factory_id=1 만
		return data;
	}
}