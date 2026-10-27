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
	 * 품목 구분.
	 *
	 * ★ mat_grp 로는 못 가른다 — 용기·PK·혈액저장용기가 전부 「가공품(semi)」이다.
	 *   이름으로 가른다. 순서가 중요하다:
	 *     저장용기를 용기보다 먼저 본다 ('혈액저장용기' 는 '%용기%' 에도 걸린다)
	 *     PK 를 용기보다 먼저 본다     (PK 이름에 '용기' 가 들어가는 품목이 생길 수 있다)
	 * ★ 이름 매칭은 임시다. 품목 마스터에 구분 컬럼이 생기면 그걸로 바꾼다.
	 */
	private static String catSql(String typeExpr, String nameExpr) {
		return "CASE WHEN COALESCE(" + typeExpr + ",'') = 'product' THEN 'product'"
				+ " WHEN " + nameExpr + " LIKE '%저장용기%' THEN 'storage'"
				+ " WHEN " + nameExpr + " LIKE '%PK-%' THEN 'pk'"
				+ " WHEN " + nameExpr + " LIKE '%용기%' THEN 'vessel'"
				+ " ELSE NULL END";
	}

	private static final String CAT_MP = catSql(
			"COALESCE(omg.\"MaterialType\", mg.\"MaterialType\")",
			"COALESCE(om.\"Name\", m.\"Name\")");

	// =================================================================
	// 환경 (온습도 · 차압)
	// =================================================================

	/** 센서 최신값 — 통합 대시보드와 같은 출처(EnvSensorService) */
	public List<Map<String, Object>> getEnv(String spjangcd) {
		return envSensorService.getEnv(spjangcd);
	}

	// =================================================================
	// 생산현황 (품목 구분별 진행중)
	// =================================================================

	/**
	 * 진행중 차수 — 산출 품목별.
	 *
	 * ★ 산출 품목은 mat_produce."Material_id" 우선(COALESCE(om, m)).
	 *   작지 품목을 쓰면 한 작지에 PK 생산과 키트 결합이 섞일 때 전부 하나로 보인다.
	 * ★ 1공장만: 공정의 Factory_id = 1.
	 * ★ qty 는 시작 입력분(계획)이다. 「진행중 N개」로만 읽는다.
	 */
	public List<Map<String, Object>> getWorking(String spjangcd) {
		String sql = """
				SELECT x.cat, x.mat_name, x.proc_code, x.proc_name
				     , SUM(x.qty)                                AS qty
				     , COUNT(*)                                  AS session_cnt
				     , STRING_AGG(DISTINCT x.actor_name, ', ')   AS workers
				  FROM (
				    SELECT %s                                    AS cat
				         , COALESCE(om."Name", m."Name")         AS mat_name
				         , pr."Code"                             AS proc_code
				         , pr."Name"                             AS proc_name
				         , COALESCE(mp."GoodQty", 0)             AS qty
				         , COALESCE((SELECT STRING_AGG(pm."Name", ', ' ORDER BY (mm."Person_id" = mp."Actor_id") DESC, pm."Name")
				                       FROM mat_produce_member mm JOIN person pm ON pm.id = mm."Person_id"
				                      WHERE mm."MatProduce_id" = mp.id AND COALESCE(mm._status,'a') = 'a'),
				                    pe."Name")                   AS actor_name
				      FROM mat_produce mp
				      JOIN job_res jr ON jr.id = mp."JobResponse_id"
				      LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				      LEFT JOIN process     pr ON pr.id = wc."Process_id"
				      LEFT JOIN material om  ON om.id  = mp."Material_id"
				      LEFT JOIN mat_grp  omg ON omg.id = om."MaterialGroup_id"
				      LEFT JOIN material m   ON m.id   = jr."Material_id"
				      LEFT JOIN mat_grp  mg  ON mg.id  = m."MaterialGroup_id"
				      LEFT JOIN person   pe  ON pe.id  = mp."Actor_id"
				     WHERE COALESCE(mp._status,'a') = 'a'
				       AND mp."State" <> 'finished' AND mp."EndTime" IS NULL
				       AND COALESCE(pr."Factory_id", 1) = 1
				       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				  ) x
				 WHERE x.cat IS NOT NULL
				 GROUP BY x.cat, x.mat_name, x.proc_code, x.proc_name
				 ORDER BY x.cat, SUM(x.qty) DESC
				""".formatted(CAT_MP);
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	/** 오늘 확정 양품 — 구분별 합계 (카드 아래 「오늘 완료」) */
	public List<Map<String, Object>> getTodayDone(String spjangcd) {
		String sql = """
				SELECT x.cat, SUM(x.qty) AS qty
				  FROM (
				    SELECT %s                                    AS cat
				         , COALESCE(mp."GoodQty", 0)             AS qty
				      FROM mat_produce mp
				      JOIN job_res jr ON jr.id = mp."JobResponse_id"
				      LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				      LEFT JOIN process     pr ON pr.id = wc."Process_id"
				      LEFT JOIN material om  ON om.id  = mp."Material_id"
				      LEFT JOIN mat_grp  omg ON omg.id = om."MaterialGroup_id"
				      LEFT JOIN material m   ON m.id   = jr."Material_id"
				      LEFT JOIN mat_grp  mg  ON mg.id  = m."MaterialGroup_id"
				     WHERE COALESCE(mp._status,'a') = 'a'
				       AND (mp."State" = 'finished' OR mp."EndTime" IS NOT NULL)
				       AND COALESCE(mp."EndTime", mp."StartTime", mp."ProductionDate"::timestamptz)::date = CURRENT_DATE
				       AND COALESCE(pr."Factory_id", 1) = 1
				       /* ★ 완제품은 마지막 공정(포장 bsc05)만 센다.
				            전 공정을 더하면 같은 키트를 공정 수만큼 센다(통합 KPI 와 같은 규칙). */
				       AND (COALESCE(omg."MaterialType", mg."MaterialType", '') <> 'product' OR pr."Code" = 'bsc05')
				       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				  ) x
				 WHERE x.cat IS NOT NULL
				 GROUP BY x.cat
				""".formatted(CAT_MP);
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}


	/** 진행중 세척 세션 수 — 생산현황 「세척」 행 초록불 (작업인원과 같은 판정) */
	public int getWashWorking(String spjangcd) {
		String sql = """
				SELECT COUNT(*) AS cnt
				  FROM wash_work w
				 WHERE COALESCE(w._status,'a') = 'a'
				   AND w."WashDate" >= CURRENT_DATE - 1
				   AND (NOT EXISTS (SELECT 1 FROM wash_work_item i
				                     WHERE i."WashWork_id" = w.id AND COALESCE(i._status,'a') = 'a')
				        OR EXISTS (SELECT 1 FROM wash_work_item i
				                    WHERE i."WashWork_id" = w.id AND COALESCE(i._status,'a') = 'a'
				                      AND i."EndTime" IS NULL))
				""";
		List<Map<String, Object>> r = nz(this.sqlRunner.getRows(sql, p(spjangcd)));
		return r.isEmpty() || r.get(0).get("cnt") == null ? 0 : ((Number) r.get(0).get("cnt")).intValue();
	}

	// =================================================================
	// 작업인원
	// =================================================================

	/**
	 * 1공장 인원 — 작업중 / 대기.
	 *
	 * ★ 작업중의 출처는 셋이다: 진행중 차수(mat_produce) · 멸균 배치 · 업무 시작(work_duty).
	 *   품질검사처럼 실적이 없는 사람은 work_duty 로만 「작업중」이 된다.
	 *
	 * ★ 근태(연차·지각)는 MES 에 없다. 「지금 차수나 멸균 배치를 붙잡고 있나」만 본다.
	 *   대기 = 출근했는지와 무관하다. 화면 문구도 「대기」로만 쓴다(「출근」이라 쓰지 않는다).
	 * ★ 공장 미지정(Factory_id NULL)은 뺀다. 양쪽에 다 뜨면 인원이 부풀어 보인다.
	 * ★ rtflag NULL 은 재직으로 본다(ComboService.person 과 같은 규칙).
	 */
	public List<Map<String, Object>> getPeople(String spjangcd) {
		String sql = """
				WITH busy AS (
				    SELECT ppl.pid, pr."Name" AS where_name
				      FROM mat_produce mp
				      JOIN job_res jr ON jr.id = mp."JobResponse_id"
				      LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				      LEFT JOIN process     pr ON pr.id = wc."Process_id"
				      /* ★ 대표(Actor_id)만 보면 조원이 「대기」로 남는다 — 조원(mat_produce_member)도 작업중 */
				      CROSS JOIN LATERAL (
				          SELECT mp."Actor_id" AS pid
				          UNION
				          SELECT mm."Person_id" FROM mat_produce_member mm
				           WHERE mm."MatProduce_id" = mp.id AND COALESCE(mm._status,'a') = 'a'
				      ) ppl
				     WHERE COALESCE(mp._status,'a') = 'a'
				       AND mp."State" <> 'finished' AND mp."EndTime" IS NULL
				       AND COALESCE(pr."Factory_id", 1) = 1
				    UNION ALL
				    /* ★ 세척은 mat_produce 를 만들지 않는다 — wash_work 진행중(종료 시각 없음)도 작업중 */
				    SELECT w."Actor_id", '세척'
				      FROM wash_work w
				     WHERE COALESCE(w._status,'a') = 'a'
				       AND w."WashDate" >= CURRENT_DATE - 1
				       /* 시각은 품목(wash_work_item)에 있다 — 품목이 아직 없거나 안 끝난 품목이 있으면 진행중 */
				       AND (NOT EXISTS (SELECT 1 FROM wash_work_item i
				                         WHERE i."WashWork_id" = w.id AND COALESCE(i._status,'a') = 'a')
				            OR EXISTS (SELECT 1 FROM wash_work_item i
				                        WHERE i."WashWork_id" = w.id AND COALESCE(i._status,'a') = 'a'
				                          AND i."EndTime" IS NULL))
				    UNION ALL
				    SELECT b."Actor_id", '멸균'
				      FROM steril_batch b
				     WHERE b."State" = 'working'
				    UNION ALL
				    /* ★ 업무 시작·종료(품질검사 등) — 실적 없이 「지금 업무 중」만 남는 사람.
				         오늘 시작해서 아직 종료하지 않은 것만. 어제 것은 자정에 닫힌 것으로 본다. */
				    SELECT COALESCE(u.personid, CASE WHEN u.username ~ '^[0-9]+$' THEN u.username::int END)
				         , COALESCE(g."Name", '업무')
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
				      FROM person pe
				      /* ★ 1공장 = 「1공장 계정이 있는 사람」.
				           person.Factory_id 만 보면 계정 없는 인원(테스트·미등록)까지 섞인다.
				           계정은 username = person.id 로 연결되어 있다. */
				      JOIN auth_user u     ON u.username = pe.id::text
				      JOIN user_profile up ON up."User_id" = u.id AND up."Factory_id" = 1
				      LEFT JOIN busy b ON b.pid = pe.id
				     WHERE pe."Factory_id" = 1
				       AND COALESCE(pe.rtflag,'0') <> '1'
				     GROUP BY pe.id, pe."Name"

				    UNION ALL

				    /* ★ 품질검사 그룹 중 사원(person)과 연결되지 않은 계정.
				         사원 등록 없이 계정만 만든 담당자도 이름이 나오게 한다.
				         id 는 음수(-auth_user.id) — 사원 id 와 겹치지 않게. */
				    SELECT -u.id
				         , COALESCE(NULLIF(up."Name",''), NULLIF(u.first_name,''), u.username)
				         , CASE WHEN d.id IS NOT NULL THEN 'working' ELSE 'idle' END
				         , CASE WHEN d.id IS NOT NULL THEN g."Name" END
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
		data.put("wash_today", mainService.getWashToday(spjangcd));
		data.put("wash_working", getWashWorking(spjangcd));
		data.put("steril",     mainService.getSterilNow(spjangcd));
		data.put("people",     getPeople(spjangcd));
		data.put("kpi",        mainService.getKpi(spjangcd));          // f1_today_good 만 쓴다
		data.put("defect",     mainService.getDefectToday(spjangcd));  // 화면에서 factory_id=1 만
		return data;
	}
}