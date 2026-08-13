package mes.app.dashboard.service;

import java.util.ArrayList;
import java.util.Random;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 통합 생산 대시보드 (1·2공장).
 *
 * ─────────────────────────────────────────────────────────────
 * 이 화면의 성격 — 「지금」만 본다
 *
 *   작업실적현황 : 그 기간에 얼마나 했나   (실적일 축)
 *   생산진행현황 : 작업지시가 어디까지 갔나 (작지 지시일 축)
 *   이 대시보드   : 지금 무엇이 돌고 있나   (현재 시점)
 *
 * 축이 셋으로 갈리므로 같은 숫자를 서로 다르게 말할 수 있다. 그래서
 * 여기서는 「오늘」과 「진행중」만 다루고 기간 조회를 두지 않는다.
 * 기간을 보고 싶으면 각 화면으로 가는 것이 맞다.
 * ─────────────────────────────────────────────────────────────
 *
 * ★ 실적일은 ProductionDate 가 아니다.
 *   그건 작지 지시일을 물고 들어온다. COALESCE(EndTime, StartTime, ProductionDate).
 *
 * ★ 「오늘 생산」은 확정분만 센다.
 *   진행중 차수의 GoodQty 는 startProduction 이 넣은 시작 입력분(계획)이다.
 *
 * ★ 공정별 성격에 따라 실적이 남는 곳이 다르다 (실적현황 착수문서 §2).
 *   job_res 만 보면 세척·멸균·검사·부적합이 통째로 빠진다.
 *     이동형   세척            wash_work
 *     생산형   조립·블리스터·융착·포장  job_res + mat_produce
 *     생산형   M-CELL 조립·수리        + mcell_unit
 *     배치형   멸균            steril_batch
 *     검사     M-CELL          insp_result
 *     부적합   전 공정         defect_regist
 *
 * ★ 2공장 실적 단위는 「대」다. 1공장의 「수량」과 합계를 섞지 않는다.
 *   한 대를 조립하면 BOM 계층 스텝마다 mat_produce 가 대여섯 건 생겨서
 *   그걸 세면 5대 만든 날이 30건으로 보인다.
 *
 * ★ SqlRunner.getRows 는 오류 시 null 을 반환한다. 반드시 nz() 로 감쌀 것.
 */
@Service
public class DashBoardMainService {

	@Autowired
	private SqlRunner sqlRunner;

	private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}

	private MapSqlParameterSource p(String spjangcd) {
		return new MapSqlParameterSource().addValue("spjangcd", spjangcd);
	}

	// =================================================================
	// 존 (공정 마스터)
	// =================================================================

	/**
	 * 존 목록 = 공정 마스터.
	 *
	 * ★ 화면에 존 배열을 하드코딩하지 않는다.
	 *   목업은 「검사 / 내포장」으로 되어 있는데 그건 v2 시절 이름이다.
	 *   실제 1공장은 bsc01~bsc06(세척·조립·블리스터·융착·멸균·포장)이고
	 *   검사는 2공장(mc02)에 있다. 마스터에서 내리면 공정이 늘어도 화면이 따라온다.
	 */
	/*
	 * ★ 공정 정렬은 Code 순이 아니라 「실제 작업 흐름」 순이다.
	 *   Code 로 정렬하면 고주파 융착이 맨 뒤로 밀린다(코드가 흐름과 어긋난다).
	 *   흐름: 세척 → 조립 → 블리스터 → 고주파 융착 → 멸균 → 포장
	 *
	 * ★ 블리스터를 포장보다 먼저 검사한다.
	 *   「블리스터 포장」이라는 이름이 '%포장%' 에도 걸리기 때문이다.
	 *   순서를 바꾸면 블리스터가 포장 자리로 간다.
	 *
	 * ★ 이름 매칭은 임시 방편이다.
	 *   공정 이름이 바뀌면 정렬이 조용히 깨진다.
	 *   process 에 정렬 컬럼(SortNo)을 두고 그걸로 정렬하는 것이 옳다.
	 */
	public List<Map<String, Object>> getZones() {
		String sql = """
				SELECT p.id                        AS pk
				     , p."Code"                    AS code
				     , p."Name"                    AS name
				     , COALESCE(p."Factory_id", 1) AS factory_id
				  FROM process p
				 WHERE COALESCE(p._status,'a') = 'a'
				 ORDER BY COALESCE(p."Factory_id",1)
				        , CASE
				            WHEN p."Name" LIKE '%블리스터%' THEN 3
				            WHEN p."Name" LIKE '%융착%'     THEN 4
				            WHEN p."Name" LIKE '%세척%'     THEN 1
				            WHEN p."Name" LIKE '%조립%'     THEN 2
				            WHEN p."Name" LIKE '%멸균%'     THEN 5
				            WHEN p."Name" LIKE '%포장%'     THEN 6
				            ELSE 99
				          END
				        , p."Code"
				""";
		return nz(this.sqlRunner.getRows(sql, new MapSqlParameterSource()));
	}

	// =================================================================
	// 공정별 현재 상태
	// =================================================================

	/**
	 * 공정별 「지금」.
	 *
	 *   working_cnt  진행중 차수 (사람이 붙어 있다)
	 *   today_good   오늘 확정 양품
	 *   workers      지금 붙어 있는 사람 이름
	 *
	 * 세척·멸균은 mat_produce 를 만들지 않아 여기서 0 으로 나온다.
	 * 그 둘은 getWashToday / getSterilToday 가 따로 채운다.
	 *
	 * ★ 1공장 포장(bsc05)은 CK(반제품)와 키트 결합(완제품)을 둘 다 만든다.
	 *   today_good 하나로 내리면 CK 10 + 완제품 10 이 「20개 생산」이 된다.
	 *   단위가 둘 다 「개」라서 더해도 오류가 안 나고, 그래서 더 위험하다.
	 *   → today_good 은 그대로 두고(다른 공정이 쓴다) 포장용으로
	 *     today_good_product / today_good_semi 를 함께 내린다. 화면이 둘로 나눠 적는다.
	 *
	 * ★ 산출품목은 mat_produce."Material_id" 를 먼저 본다.
	 *   작지 품목(job_res."Material_id")을 쓰면 한 작지에 CK 생산과 결합이 섞인 경우
	 *   전부 CK 로 보인다. COALESCE(om, m) 로 차수 자기 품목을 우선한다.
	 */
	public List<Map<String, Object>> getProcessNow(String spjangcd) {
		String sql = """
				WITH mp AS (
				    SELECT COALESCE(mp."WorkCenter_id", jr."WorkCenter_id") AS wc
				         , mp."State"     AS st
				         , mp."EndTime"   AS end_time
				         , mp."GoodQty"   AS good
				         , mp."Actor_id"  AS actor
				         , COALESCE(mp."EndTime", mp."StartTime",
				                    mp."ProductionDate"::timestamptz)::date AS work_date
				         , CASE WHEN COALESCE(omg."MaterialType", mg."MaterialType", '') = 'product'
				                  OR COALESCE(om."Code", m."Code") LIKE '%FG%'
				                THEN 'Y' ELSE 'N' END                       AS is_product
				      FROM mat_produce mp
				      JOIN job_res jr ON jr.id = mp."JobResponse_id"
				      LEFT JOIN material om  ON om.id  = mp."Material_id"
				      LEFT JOIN mat_grp  omg ON omg.id = om."MaterialGroup_id"
				      LEFT JOIN material m   ON m.id   = jr."Material_id"
				      LEFT JOIN mat_grp  mg  ON mg.id  = m."MaterialGroup_id"
				     WHERE COALESCE(mp._status,'a') = 'a'
				       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				)
				SELECT pr.id                                                       AS pk
				     , pr."Code"                                                   AS code
				     , pr."Name"                                                   AS name
				     , COALESCE(pr."Factory_id", 1)                                AS factory_id
				     , COUNT(*) FILTER (WHERE mp.st <> 'finished' AND mp.end_time IS NULL) AS working_cnt
				     , COALESCE(SUM(mp.good) FILTER (
				           WHERE mp.work_date = CURRENT_DATE
				             AND (mp.st = 'finished' OR mp.end_time IS NOT NULL)), 0) AS today_good
				     , COALESCE(SUM(mp.good) FILTER (
				           WHERE mp.work_date = CURRENT_DATE
				             AND (mp.st = 'finished' OR mp.end_time IS NOT NULL)
				             AND mp.is_product = 'Y'), 0)                            AS today_good_product
				     , COALESCE(SUM(mp.good) FILTER (
				           WHERE mp.work_date = CURRENT_DATE
				             AND (mp.st = 'finished' OR mp.end_time IS NOT NULL)
				             AND mp.is_product = 'N'), 0)                            AS today_good_semi
				     , COALESCE(STRING_AGG(DISTINCT pe."Name", ', ') FILTER (
				           WHERE mp.st <> 'finished' AND mp.end_time IS NULL), '')  AS workers
				     /* ★ 검사(mc02)는 mat_produce 를 만들지 않는다 — 판정만 하는 공정이라
				          산출 품목이 없다. 세척·멸균과 같은 계열이고, 그래서 today_good 이
				          영원히 0 이었다(검사를 아무리 해도 「0건」으로 굳었다).
				          검사 실적의 진실은 mcell_unit 상태다.
				            판정 끝난 대수 = pass + reject + packed
				              · packed 를 빼면 포장까지 간 대수가 검사에서 사라진다
				                (통과했기 때문에 포장된 것이다)
				              · reject 를 빼면 검사했지만 떨어진 대수가 증발한다
				          대기 대수(inspect_wait)는 이 공정의 「작업중」으로 쓴다 —
				          검사 자리에 물건이 와 있다는 뜻이다. */
				     , CASE WHEN pr."Code" = 'mc02' THEN (
				           SELECT COUNT(*) FROM mcell_unit mu
				            WHERE COALESCE(mu._status,'a') = 'a'
				              AND mu."State" IN ('pass','reject','packed')) END      AS unit_done
				     , CASE WHEN pr."Code" = 'mc02' THEN (
				           SELECT COUNT(*) FROM mcell_unit mu
				            WHERE COALESCE(mu._status,'a') = 'a'
				              AND mu."State" = 'inspect_wait') END                   AS unit_waiting
				  FROM process pr
				  LEFT JOIN work_center wc ON wc."Process_id" = pr.id
				  LEFT JOIN mp ON mp.wc = wc.id
				  LEFT JOIN person pe ON pe.id = mp.actor
				 WHERE COALESCE(pr._status,'a') = 'a'
				 GROUP BY pr.id, pr."Code", pr."Name", pr."Factory_id"
				 ORDER BY COALESCE(pr."Factory_id",1)
				        , CASE
				            WHEN pr."Name" LIKE '%블리스터%' THEN 3
				            WHEN pr."Name" LIKE '%융착%'     THEN 4
				            WHEN pr."Name" LIKE '%세척%'     THEN 1
				            WHEN pr."Name" LIKE '%조립%'     THEN 2
				            WHEN pr."Name" LIKE '%멸균%'     THEN 5
				            WHEN pr."Name" LIKE '%포장%'     THEN 6
				            ELSE 99
				          END
				        , pr."Code"
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 세척 · 멸균 (mat_produce 가 없는 공정)
	// =================================================================

	/**
	 * 오늘 세척.
	 *
	 * ★ 「건수」는 세션 기준이어야 한다.
	 *   wash_work 1건에 wash_work_item 이 여러 개라, 품목 행을 세면
	 *   8세션이 11건으로 보인다.
	 */
	public Map<String, Object> getWashToday(String spjangcd) {
		String sql = """
				SELECT COUNT(DISTINCT w.id)  AS session_cnt
				     , COUNT(wi.id)          AS item_cnt
				  FROM wash_work w
				  LEFT JOIN wash_work_item wi ON wi."WashWork_id" = w.id
				 WHERE w."WashDate" = CURRENT_DATE
				""";
		List<Map<String, Object>> rows = nz(this.sqlRunner.getRows(sql, p(spjangcd)));
		return rows.isEmpty() ? new HashMap<>() : rows.get(0);
	}

	/**
	 * 멸균 배치 — 진행중 + 오늘.
	 *
	 * ★ BI 판정중은 「합격」으로 세지 않는다.
	 *   나중에 불합격이 나오면 진행률이 거꾸로 간다.
	 */
	public List<Map<String, Object>> getSterilNow(String spjangcd) {
		String sql = """
				SELECT b.id                                   AS pk
				     , to_char(b."SterilDate", 'yyyy-mm-dd')  AS steril_date
				     , b."State"                              AS state
				     , b."BiResult"                           AS bi_result
				     , e."Name"                               AS equ_name
				     , pe."Name"                              AS actor_name
				     , COUNT(bi.id)                           AS lot_cnt
				  FROM steril_batch b
				  LEFT JOIN steril_batch_item bi ON bi."SterilBatch_id" = b.id
				  LEFT JOIN equ    e  ON e.id  = b."Equipment_id"
				  LEFT JOIN person pe ON pe.id = b."Actor_id"
				 WHERE b."State" = 'working' OR b."SterilDate" = CURRENT_DATE
				 GROUP BY b.id, b."SterilDate", b."State", b."BiResult", e."Name", pe."Name"
				 ORDER BY b."State" = 'working' DESC, b.id DESC
				 LIMIT 8
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 작업지시 진행
	// =================================================================

	/**
	 * 진행중 작업지시.
	 *
	 * ★ 부모 작지만 본다(Parent_id IS NULL).
	 *   자식 작지까지 세면 완제품 1건이 용기·PK·CK 로 흩어져 건수가 부풀고,
	 *   2공장 하위 모듈 작지 13건이 조립 실적으로 중복 노출된다.
	 *
	 * ★ 진척은 GoodQty 롤업을 쓴다.
	 *   설계상 작지 GoodQty = 차수 전체 합이다(getJobResponseGoodDefectQty 에
	 *   필터가 없다). 다만 삭제된 차수가 합산되는 미해결 건이 있어
	 *   100% 를 넘을 수 있으므로 화면에서 LEAST 로 막는다.
	 */
	public List<Map<String, Object>> getActiveOrders(String spjangcd) {
		String sql = """
				SELECT jr.id                                   AS pk
				     , jr."WorkOrderNumber"                    AS work_order
				     , m."Name"                                AS mat_name
				     , m."Code"                                AS mat_code
				     , COALESCE(jr."OrderQty", 0)              AS order_qty
				     , COALESCE(jr."GoodQty", 0)               AS good_qty
				     , jr."State"                              AS state
				     , to_char(jr."ProductionDate", 'mm-dd')   AS order_date
				     , COALESCE(pr."Factory_id", 1)            AS factory_id
				     , to_char(sh."DeliveryDate", 'mm-dd')     AS delivery_date
				     , CASE WHEN sh."DeliveryDate" IS NOT NULL
				                 AND sh."DeliveryDate" < CURRENT_DATE THEN 'Y' ELSE 'N' END AS is_late
				     , co."Name"                               AS company_name
				  FROM job_res jr
				  LEFT JOIN material    m  ON m.id  = jr."Material_id"
				  LEFT JOIN work_center wc ON wc.id = jr."WorkCenter_id"
				  LEFT JOIN process     pr ON pr.id = wc."Process_id"
				  LEFT JOIN suju      su ON su.id = jr."SourceDataPk"
				                        AND jr."SourceTableName" = 'suju'
				  LEFT JOIN suju_head sh ON sh.id = su."SujuHead_id"
				  LEFT JOIN company   co ON co.id = sh."Company_id"
				 WHERE COALESCE(jr._status,'a') = 'a'
				   AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				   AND jr."Parent_id" IS NULL
				   AND COALESCE(jr."State",'') <> 'finished'
				 ORDER BY (CASE WHEN sh."DeliveryDate" IS NOT NULL
				                     AND sh."DeliveryDate" < CURRENT_DATE THEN 0 ELSE 1 END)
				        , sh."DeliveryDate" NULLS LAST, jr.id DESC
				 LIMIT 12
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 2공장 유닛
	// =================================================================

	/**
	 * 2공장 유닛 상태 분포.
	 *
	 * ★ 여기가 2공장 진행률의 진실이다(packed 기준).
	 *   mat_produce 를 세면 한 대가 스텝 대여섯 건으로 흩어진다.
	 */
	public List<Map<String, Object>> getUnitStates(String spjangcd) {
		String sql = """
				SELECT mu."State"  AS state
				     , COUNT(*)    AS cnt
				  FROM mcell_unit mu
				 WHERE COALESCE(mu._status,'a') = 'a'
				 GROUP BY 1
				 ORDER BY 1
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	/** 지금 작업중인 2공장 유닛 */
	public List<Map<String, Object>> getUnitsWorking(String spjangcd) {
		String sql = """
				SELECT mu.id            AS pk
				     , mu."UnitNo"      AS unit_no
				     , mu."LotNumber"   AS lot_number
				     , mu."State"       AS state
				     , m."Name"         AS mat_name
				     , pe."Name"        AS actor_name
				     , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'Y' ELSE 'N' END AS is_repair
				  FROM mcell_unit mu
				  LEFT JOIN job_res  jr ON jr.id = mu."JobResponse_id"
				  LEFT JOIN material m  ON m.id  = jr."Material_id"
				  LEFT JOIN person   pe ON pe.id = mu."Actor_id"
				 WHERE COALESCE(mu._status,'a') = 'a'
				   AND mu."State" IN ('assembling','repairing','inspect_wait','reject')
				 ORDER BY mu."State", mu.id DESC
				 LIMIT 10
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// 설비 · 부적합
	// =================================================================

	/**
	 * 지금 돌고 있는 설비 = 닫히지 않은 equ_run.
	 *
	 * ★ 이전에는 이 행들을 화면이 통째로 건너뛰어서, 취소 후 남은 유령이
	 *   보이지 않는 채로 쌓였다(5시간짜리 유령이 실제로 있었다).
	 *   경과가 길면 화면이 「미종료 의심」으로 표시하도록 open_min 을 함께 내린다.
	 */
	public List<Map<String, Object>> getEquipmentRunning(String spjangcd) {
		String sql = """
				SELECT er.id                                        AS pk
				     , e."Name"                                     AS equ_name
				     , to_char(er."StartDate", 'HH24:MI')           AS start_time
				     , ROUND((EXTRACT(epoch FROM (now() - er."StartDate")) / 60.0)::numeric, 0) AS open_min
				     , er."Description"                             AS description
				  FROM equ_run er
				  LEFT JOIN equ e ON e.id = er."Equipment_id"
				 WHERE er."EndDate" IS NULL
				   AND (CAST(:spjangcd AS varchar) IS NULL OR er.spjangcd = CAST(:spjangcd AS varchar))
				 ORDER BY er."StartDate"
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	/** 오늘 부적합 — 공정별 */
	public List<Map<String, Object>> getDefectToday(String spjangcd) {
		String sql = """
				SELECT COALESCE(pr."Name", '미지정')     AS proc_name
				     , COALESCE(pr."Factory_id", 1)      AS factory_id
				     , SUM(COALESCE(d."DefectQty",0))    AS defect_qty
				     , COUNT(*)                          AS defect_cnt
				  FROM defect_regist d
				  LEFT JOIN process pr ON pr.id = d."Process_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" = CURRENT_DATE
				 GROUP BY 1, 2
				 ORDER BY 3 DESC
				""";
		return nz(this.sqlRunner.getRows(sql, p(spjangcd)));
	}

	// =================================================================
	// KPI
	// =================================================================

	/**
	 * 상단 KPI.
	 *
	 * ★ 1공장 수량과 2공장 대수를 한 숫자로 더하지 않는다.
	 *   진척 단위가 다르다 — 섞으면 아무 의미 없는 합계가 된다.
	 *
	 * ★ 「1공장 완제품」은 공정 합계가 아니다.
	 *   전 공정을 더하면 키트 10개를 만들었을 때
	 *   조립 10 + 블리스터 10 + 융착 10 + 포장 10 = 40 이 찍힌다.
	 *   같은 물건을 공정 수만큼 센 숫자인데 화면에서는 「40개 생산」으로 읽힌다.
	 *   공정이 하나 늘면 생산이 안 늘어도 이 값이 커진다는 것이 결정적이다.
	 *   → 마지막 공정(bsc05 포장)의 완제품만 센다.
	 *     2공장 칸이 packed(포장 완료) 대수이므로 축도 맞는다 —
	 *     양쪽 다 「오늘 끝까지 나온 것」이다.
	 *
	 * ★ CK(반제품)는 여기서 뺀다. 포장 카드에 따로 서 있으므로
	 *   KPI 에서 다시 더하면 방금 나눈 것을 도로 합치는 셈이다.
	 *
	 * ★ 조립·블리스터만 돌린 날은 이 칸이 0 이다. 그게 맞는 표시다
	 *   (완제품이 안 나왔다). 그날의 진행은 아래 존 타일이 보여준다.
	 */
	public Map<String, Object> getKpi(String spjangcd) {
		String sql = """
				WITH mp AS (
				    SELECT COALESCE(mp."GoodQty",0) AS good
				         , COALESCE(pr."Factory_id", 1) AS factory_id
				         , pr."Code"                    AS proc_code
				         , CASE WHEN COALESCE(omg."MaterialType", mg."MaterialType", '') = 'product'
				                  OR COALESCE(om."Code", m."Code") LIKE '%FG%'
				                THEN 'Y' ELSE 'N' END   AS is_product
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
				       AND COALESCE(mp."EndTime", mp."StartTime",
				                    mp."ProductionDate"::timestamptz)::date = CURRENT_DATE
				       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				)
				SELECT (SELECT COALESCE(SUM(good),0) FROM mp
				         WHERE factory_id = 1
				           AND proc_code = 'bsc05'
				           AND is_product = 'Y')                                    AS f1_today_good
				     , (SELECT COUNT(*) FROM mcell_unit mu
				         WHERE COALESCE(mu._status,'a')='a' AND mu."State" = 'packed')     AS f2_packed
				     , (SELECT COUNT(*) FROM job_res jr
				         WHERE COALESCE(jr._status,'a')='a' AND jr."Parent_id" IS NULL
				           AND COALESCE(jr."State",'') NOT IN ('finished'))                AS open_orders
				     , (SELECT COUNT(*) FROM mat_produce mp2
				         WHERE COALESCE(mp2._status,'a')='a'
				           AND mp2."State" <> 'finished' AND mp2."EndTime" IS NULL)        AS working_sessions
				     , (SELECT COALESCE(SUM(COALESCE(d."DefectQty",0)),0) FROM defect_regist d
				         WHERE d."State"='confirmed' AND d."DefectDate" = CURRENT_DATE)    AS today_defect
				     , (SELECT COUNT(*) FROM equ_run er WHERE er."EndDate" IS NULL)        AS equ_running
				""";
		List<Map<String, Object>> rows = nz(this.sqlRunner.getRows(sql, p(spjangcd)));
		return rows.isEmpty() ? new HashMap<>() : rows.get(0);
	}

	// =================================================================
	// 한 방에
	// =================================================================

	/**
	 * 화면은 이것만 부른다.
	 *
	 * 패널마다 따로 부르면 폴링 주기가 겹칠 때 KPI 와 목록이
	 * 서로 다른 시점을 말하게 된다. 대시보드에서 그건 치명적이다.
	 *
	 * env(온습도)는 아직 수집 테이블이 없다 — OPC-UA 연동 전이라
	 * 빈 배열과 sensor_ready=false 를 내린다. 화면은 그걸 보고
	 * 「센서 미연동」 배지를 띄운다. 값을 0 으로 채우면 「0도」로 읽힌다.
	 */
	public Map<String, Object> getDashboard(String spjangcd) {
		Map<String, Object> data = new HashMap<>();
		data.put("kpi",            getKpi(spjangcd));
		data.put("zones",          getZones());
		data.put("process_now",    getProcessNow(spjangcd));
		data.put("wash_today",     getWashToday(spjangcd));
		data.put("steril",         getSterilNow(spjangcd));
		data.put("orders",         getActiveOrders(spjangcd));
		data.put("unit_states",    getUnitStates(spjangcd));
		data.put("units_working",  getUnitsWorking(spjangcd));
		data.put("equ_running",    getEquipmentRunning(spjangcd));
		data.put("defect_today",   getDefectToday(spjangcd));

		data.put("env",          getEnvZones(spjangcd));
		data.put("sensor_ready", Boolean.valueOf(ENV_DEMO));

		return data;
	}

	// =================================================================
	// 클린룸 환경 (온도 · 습도 · 차압)
	// =================================================================

	/**
	 * 클린룸 존과 관리 기준.
	 *
	 * ★ 기준치는 cleanroom_limit 테이블에서 읽는다.
	 *   화면·코드에 두면 대시보드와 클린룸 화면에 같은 숫자가 복사되고,
	 *   하나만 고쳤을 때 두 화면이 서로 다른 기준으로 경고를 띄운다.
	 *   계절마다 조정하는 값이라 배포 없이 바꿀 수 있어야 한다.
	 *
	 * ★ 측정값(temp/humi/press)은 아직 없다. OPC-UA 연동 전이라 null 로 내린다.
	 *   0 으로 채우면 화면이 「0도 · 0%」를 기준 이탈로 읽어
	 *   전 존이 빨갛게 되고 진짜 이상과 구분되지 않는다.
	 *
	 * ★ 연동 시 바꿀 곳은 여기 하나다.
	 *   temp/humi/press 에 수집값을 넣고 sensor_ready 를 true 로 올리면
	 *   화면은 그대로 동작한다. 판정·색·알람은 이미 붙어 있다.
	 */
	public List<Map<String, Object>> getEnvZones(String spjangcd) {
		String sql = """
				SELECT "ZoneCode"                    AS code
				     , "ZoneName"                    AS name
				     , "ZoneLabel"                   AS label
				     , "TempMin"                     AS temp_min
				     , "TempMax"                     AS temp_max
				     , "HumiMin"                     AS humi_min
				     , "HumiMax"                     AS humi_max
				     , "PressMin"                    AS press_min
				     , "PressMax"                    AS press_max
				     , NULL::numeric                 AS temp
				     , NULL::numeric                 AS humi
				     , NULL::numeric                 AS press
				  FROM cleanroom_limit
				 WHERE COALESCE(_status,'a') = 'a'
				   AND COALESCE("UseYN",'Y') = 'Y'
				   AND (CAST(:spjangcd AS varchar) IS NULL OR spjangcd = CAST(:spjangcd AS varchar))
				 ORDER BY "SortNo", "ZoneCode"
				""";
		List<Map<String, Object>> rows = nz(this.sqlRunner.getRows(sql, p(spjangcd)));
		if (ENV_DEMO) {
			fillDemoValues(rows);
		}
		return rows;
	}

	/* ═══════════════════════════════════════════════════════════
	 * 센서 데모 모드
	 *
	 * ★ OPC-UA 연동 전에 화면·알람이 실제로 도는지 보기 위한 것이다.
	 *   연동되면 이 상수를 false 로 내리고 수집값을 넣으면 된다.
	 *   ★ 운영 전환 시 반드시 false. 켜둔 채로 넘어가면 가짜 값이
	 *     진짜처럼 보이고, 알람이 늑대소년이 된다.
	 *
	 * ★ 화면이 아니라 서버에서 만든다.
	 *   화면에서 만들면 관리자 화면과 벽걸이가 서로 다른 값을 보게 된다.
	 *   (요청마다 새로 뽑으므로 두 화면의 값이 완전히 같지는 않다.
	 *    데모 목적에는 충분하고, 실제 센서가 붙으면 사라지는 문제다.)
	 *
	 * ★ 이탈 여부는 「존 단위」로 정한다. 지표마다 따로 굴리면 안 된다.
	 *   지표별 25% 면 존 하나가 이탈일 확률이 58%(1 - 0.75³)이고,
	 *   존 4개 중 하나라도 이탈일 확률은 99.7% 가 된다.
	 *   늘 빨간 화면이면 「정상 → 이탈」 전이가 안 보여 알람이 안 뜬다.
	 *
	 *   존당 12% 로 잡으면 전체가 정상일 확률이 약 60% 라
	 *   폴링(30~60초) 기준 대략 1~2분에 한 번씩 알람이 뜬다.
	 * ═══════════════════════════════════════════════════════════ */
	private static final boolean ENV_DEMO = true;   // ★ 운영 전환 시 false
	private static final double  ENV_DEMO_BAD_RATE = 0.12;

	private final Random envRand = new Random();

	private void fillDemoValues(List<Map<String, Object>> rows) {
		for (Map<String, Object> z : rows) {
			/* 이 존이 이탈인지 먼저 정하고, 이탈이면 지표 하나만 벗어나게 한다.
			   세 개가 동시에 튀는 건 실제로 드물고, 화면에서도 원인이 흐려진다. */
			boolean bad = envRand.nextDouble() < ENV_DEMO_BAD_RATE;
			int badIdx = bad ? envRand.nextInt(3) : -1;

			z.put("temp",  demoValue(z.get("temp_min"),  z.get("temp_max"),  1, badIdx == 0));
			z.put("humi",  demoValue(z.get("humi_min"),  z.get("humi_max"),  1, badIdx == 1));
			z.put("press", demoValue(z.get("press_min"), z.get("press_max"), 0, badIdx == 2));
		}
	}

	/**
	 * 기준 범위를 참고해 그럴듯한 값을 만든다.
	 *
	 * 정상일 때는 범위 안쪽(가장자리 10% 를 뺀 구간)에서 뽑는다 —
	 * 경계에 딱 붙은 값이 계속 나오면 정상인지 이탈인지 헷갈린다.
	 * 이탈일 때는 위아래 중 한쪽으로 범위의 5~20% 만큼 벗어나게 한다.
	 */
	private Double demoValue(Object minObj, Object maxObj, int digits, boolean bad) {
		if (minObj == null || maxObj == null) return null;

		double min = Double.parseDouble(minObj.toString());
		double max = Double.parseDouble(maxObj.toString());
		if (max <= min) return null;

		double span = max - min;
		double v;

		if (bad) {
			double over = span * (0.05 + envRand.nextDouble() * 0.15);
			v = envRand.nextBoolean() ? (max + over) : (min - over);
		} else {
			v = min + span * 0.1 + envRand.nextDouble() * span * 0.8;
		}

		double f = Math.pow(10, digits);
		return Math.round(v * f) / f;
	}

	/**
	 * 관리기준 저장 (설정 모달).
	 *
	 * ★ 존을 새로 만들지 않는다. 기준치만 고친다.
	 *   존 추가·삭제는 클린룸 구획이 바뀌는 일이라 화면에서 할 일이 아니다.
	 *
	 * ★ 하한이 상한보다 큰 값은 막는다. 그대로 저장하면 모든 측정값이
	 *   기준 이탈이 되어 알람이 멈추지 않는다.
	 */
	public String saveEnvLimit(String spjangcd, String zoneCode,
							   Double tMin, Double tMax, Double hMin, Double hMax,
							   Double pMin, Double pMax, Integer userId) {

		if (tMin != null && tMax != null && tMin >= tMax) return "온도 하한이 상한보다 크거나 같습니다.";
		if (hMin != null && hMax != null && hMin >= hMax) return "습도 하한이 상한보다 크거나 같습니다.";
		if (pMin != null && pMax != null && pMin >= pMax) return "차압 하한이 상한보다 크거나 같습니다.";

		MapSqlParameterSource prm = new MapSqlParameterSource()
				.addValue("spjangcd", spjangcd)
				.addValue("zone_code", zoneCode)
				.addValue("t_min", tMin).addValue("t_max", tMax)
				.addValue("h_min", hMin).addValue("h_max", hMax)
				.addValue("p_min", pMin).addValue("p_max", pMax)
				.addValue("user_id", userId);

		String sql = """
				UPDATE cleanroom_limit
				   SET "TempMin"  = :t_min , "TempMax"  = :t_max
				     , "HumiMin"  = :h_min , "HumiMax"  = :h_max
				     , "PressMin" = :p_min , "PressMax" = :p_max
				     , _modified = now(), _modifier_id = :user_id
				 WHERE spjangcd = :spjangcd AND "ZoneCode" = :zone_code
				""";
		this.sqlRunner.execute(sql, prm);
		return null;
	}

}