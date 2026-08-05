package mes.app.production.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 생산불량현황(부적합 대시보드) 집계.
 *
 * ★ WorkStatusService 에 붙이지 않는다.
 *   저쪽은 「그 기간 실적일 축 칸반」, 이쪽은 「부적합 축 집계」다.
 *   같은 서비스에 넣으면 한쪽을 고칠 때 다른 쪽이 조용히 흔들린다.
 *
 * ★ 이 화면의 축은 defect_regist."DefectDate" (발생일) 하나다.
 *   분모(생산실적)만 mat_produce 의 실적일로 따로 잡아 맞춘다.
 *
 * ─────────────────────────────────────────────────────────────
 * 불량률에 대한 3가지 제약 (화면이 「—」를 뿌리는 이유)
 *
 *   1) 세척(bsc01)·멸균(bsc04) 은 mat_produce 를 만들지 않는다 → 분모 없음
 *   2) 품목 필터를 걸면 분모를 만들 수 없다
 *      defect_regist."Material_id" = 투입 자재
 *      mat_produce."Material_id"   = 산출 품목        ← 축이 다르다
 *      억지로 나누면 없는 숫자가 나온다. rate_valid=false 로 내린다
 *   3) 2공장 유닛 본체 불량은 defect_regist 에 없다 (insp_result 가 진실)
 *      → 합치면 이중계상. getUnitFail() 로 별도 블록에 내린다
 * ─────────────────────────────────────────────────────────────
 *
 * ★ 분모는 확정 실적만 센다.
 *   startProduction 이 시작 입력분을 GoodQty 에 미리 넣는다("계획 표시용").
 *   진행중 차수를 세면 아직 안 나온 수량이 분모로 들어가 불량률이 낮게 보인다.
 *
 * ★ SqlRunner.getRows 는 오류 시 null 을 반환한다. 반드시 nz() 로 감쌀 것.
 * ★ (:x IS NULL OR col = :x) 는 CAST(:x AS 타입) 없이는 「매개변수 자료형을 알 수 없음」.
 */
@Service
public class DefectDashService {

	@Autowired
	private SqlRunner sqlRunner;

	private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}

	/** 조회 조건 공통 바인딩 */
	private MapSqlParameterSource base(String dateFrom, String dateTo,
									   Integer factoryId, Integer processId, Integer materialId, String spjangcd) {
		return new MapSqlParameterSource()
				.addValue("date_from", dateFrom)
				.addValue("date_to", dateTo)
				.addValue("factory_id", factoryId)
				.addValue("process_id", processId)
				.addValue("material_id", materialId)
				.addValue("spjangcd", spjangcd);
	}

	// =================================================================
	// 콤보
	// =================================================================

	/**
	 * 공정 콤보.
	 *
	 * 그 공장의 공정을 전부 내린다 — 부적합이 있는 공정만 내리면
	 * 「이 공정은 0건」인지 「필터에 없는 공정」인지 구분되지 않는다.
	 * 정렬은 Code 사전순이 아니라 흐름 순(SeqNo → Code)이다.
	 */
	public List<Map<String, Object>> getProcCombo(Integer factoryId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("factory_id", factoryId);

		String sql = """
				SELECT p.id           AS pk
				     , p."Code"       AS code
				     , p."Name"       AS name
				  FROM process p
				 WHERE COALESCE(p._status,'a') = 'a'
				   AND (CAST(:factory_id AS integer) IS NULL OR p."Factory_id" = CAST(:factory_id AS integer))
				 ORDER BY p."Code"
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/**
	 * 품목 콤보 — 그 기간에 실제로 부적합이 등록된 자재만.
	 *
	 * 전체 자재를 내리면 대부분이 빈 결과가 된다(작업자 콤보와 같은 원칙).
	 */
	public List<Map<String, Object>> getItemCombo(String dateFrom, String dateTo, Integer factoryId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("date_from", dateFrom)
				.addValue("date_to", dateTo)
				.addValue("factory_id", factoryId);

		String sql = """
				SELECT DISTINCT m.id      AS pk
				     , m."Code"           AS code
				     , m."Name"           AS name
				  FROM defect_regist d
				  JOIN material m ON m.id = d."Material_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer) IS NULL OR d."Factory_id" = CAST(:factory_id AS integer))
				 ORDER BY m."Name"
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 일자별 추이
	// =================================================================

	/** 일자별 불량 (분자) */
	public List<Map<String, Object>> getDailyDefect(String dateFrom, String dateTo,
													Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT to_char(d."DefectDate", 'yyyy-mm-dd')  AS dt
				     , SUM(COALESCE(d."DefectQty",0))         AS defect_qty
				     , COUNT(*)                               AS defect_cnt
				  FROM defect_regist d
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 GROUP BY 1
				 ORDER BY 1
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	/**
	 * 일자별 생산실적 (분모).
	 *
	 * ★ 실적일은 ProductionDate 가 아니다. 그건 작지 지시일을 물고 들어온다.
	 *   7/28 작지로 8/4 에 작업해도 ProductionDate 는 7/28 이라
	 *   그걸로 기간을 걸면 오늘 한 작업이 오늘 조회에서 빠진다.
	 *
	 * ★ 확정 실적만. 진행중 차수의 GoodQty 는 시작 시 입력분(계획)이다.
	 *
	 * 품목 필터는 여기 걸지 않는다 — 축이 다르다(클래스 주석 2번).
	 * 화면이 품목을 고르면 아예 이 메서드를 부르지 않는다.
	 *
	 * ★ 날짜 × 공정으로 내린다.
	 *   날짜별 합계만 내리면 화면에서 「그날 그 공정의 불량률」을 만들 수 없다.
	 *   막대를 눌러 하루를 고르면 모든 패널이 그날로 좁혀져야 하는데,
	 *   합계만 있으면 공정별 패널이 기간 전체로 남아 화면끼리 다른 말을 한다.
	 */
	public List<Map<String, Object>> getDailyOutput(String dateFrom, String dateTo,
													Integer factoryId, Integer processId, String spjangcd) {

		MapSqlParameterSource p = base(dateFrom, dateTo, factoryId, processId, null, spjangcd);

		String sql = """
				SELECT to_char(COALESCE(mp."EndTime", mp."StartTime",
				                        mp."ProductionDate"::timestamptz), 'yyyy-mm-dd') AS dt
				     , pr.id                           AS proc_id
				     , SUM(COALESCE(mp."GoodQty",0))   AS good_qty
				  FROM mat_produce mp
				  JOIN job_res     jr ON jr.id = mp."JobResponse_id"
				  LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				  LEFT JOIN process     pr ON pr.id = wc."Process_id"
				 WHERE COALESCE(mp._status,'a') = 'a'
				   AND (mp."State" = 'finished' OR mp."EndTime" IS NOT NULL)
				   AND COALESCE(mp."EndTime", mp."StartTime", mp."ProductionDate"::timestamptz)::date
				       BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:spjangcd AS varchar)   IS NULL OR jr.spjangcd    = CAST(:spjangcd AS varchar))
				   AND (CAST(:factory_id AS integer) IS NULL OR COALESCE(pr."Factory_id",1) = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer) IS NULL OR pr.id = CAST(:process_id AS integer))
				 GROUP BY 1, 2
				 ORDER BY 1, 2
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 공정별
	// =================================================================

	/**
	 * 공정별 불량 + 분모.
	 *
	 * 공정을 축으로 분자·분모를 한 쿼리에서 만든다.
	 * 따로 뽑아 화면에서 합치면 공정 매칭 규칙이 두 벌이 되고, 어긋나면
	 * 「불량은 있는데 분모가 없는 공정」이 조용히 생긴다.
	 *
	 * has_output = 'N' 인 공정(세척·멸균)은 화면이 불량률 자리에 「—」를 찍는다.
	 */
	public List<Map<String, Object>> getByProcess(String dateFrom, String dateTo,
												  Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		MapSqlParameterSource p = base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd);

		String sql = """
				WITH def AS (
				    SELECT d."Process_id" AS proc_id
				         , SUM(COALESCE(d."DefectQty",0)) AS defect_qty
				         , COUNT(*)                       AS defect_cnt
				      FROM defect_regist d
				     WHERE d."State" = 'confirmed'
				       AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				       AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				       AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				       AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				     GROUP BY 1
				), outp AS (
				    SELECT pr.id AS proc_id
				         , SUM(COALESCE(mp."GoodQty",0)) AS good_qty
				      FROM mat_produce mp
				      JOIN job_res     jr ON jr.id = mp."JobResponse_id"
				      LEFT JOIN work_center wc ON wc.id = COALESCE(mp."WorkCenter_id", jr."WorkCenter_id")
				      JOIN process     pr ON pr.id = wc."Process_id"
				     WHERE COALESCE(mp._status,'a') = 'a'
				       AND (mp."State" = 'finished' OR mp."EndTime" IS NOT NULL)
				       AND COALESCE(mp."EndTime", mp."StartTime", mp."ProductionDate"::timestamptz)::date
				           BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				       AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
				     GROUP BY 1
				)
				SELECT p.id                              AS pk
				     , p."Code"                          AS code
				     , p."Name"                          AS name
				     , COALESCE(def.defect_qty,0)        AS defect_qty
				     , COALESCE(def.defect_cnt,0)        AS defect_cnt
				     , COALESCE(outp.good_qty,0)         AS good_qty
				     , CASE WHEN outp.good_qty IS NULL THEN 'N' ELSE 'Y' END AS has_output
				  FROM process p
				  LEFT JOIN def  ON def.proc_id  = p.id
				  LEFT JOIN outp ON outp.proc_id = p.id
				 WHERE COALESCE(p._status,'a') = 'a'
				   AND (CAST(:factory_id AS integer) IS NULL OR p."Factory_id" = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer) IS NULL OR p.id = CAST(:process_id AS integer))
				   AND (def.defect_qty IS NOT NULL OR outp.good_qty IS NOT NULL)
				 ORDER BY p."Code"
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 유형별 / 작업자별
	// =================================================================

	/**
	 * 불량 유형 TOP.
	 *
	 * ★ '기타' 는 DefectType_id 가 비고 DefectTypeEtc 에 직접 입력값이 들어온다.
	 *   COALESCE 로 합치지 않으면 「기타」가 통째로 사라진다.
	 */
	public List<Map<String, Object>> getByType(String dateFrom, String dateTo,
											   Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT COALESCE(t."Name", NULLIF(d."DefectTypeEtc",''), '미지정') AS name
				     , SUM(COALESCE(d."DefectQty",0)) AS defect_qty
				     , COUNT(*)                       AS defect_cnt
				  FROM defect_regist d
				  LEFT JOIN defect_type t ON t.id = d."DefectType_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 GROUP BY 1
				 ORDER BY 2 DESC, 1
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	/**
	 * 품목별.
	 *
	 * 여기서 말하는 품목은 부적합 등록 시 고른 <b>투입 자재</b>다
	 * (예: 상부 용기 · 블리스터 파우치). 생산 실적의 산출 품목과 다른 축이라
	 * 이 패널에는 불량률을 붙이지 않는다 — 개수만 센다.
	 *
	 * 코드를 함께 내린다. 「상부 용기」처럼 이름이 비슷한 자재가 여럿이면
	 * 이름만으로는 어느 것인지 현장에서 가려낼 수 없다.
	 */
	public List<Map<String, Object>> getByMaterial(String dateFrom, String dateTo,
												   Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT m."Name"                      AS name
				     , m."Code"                      AS code
				     , SUM(COALESCE(d."DefectQty",0)) AS defect_qty
				     , COUNT(*)                       AS defect_cnt
				  FROM defect_regist d
				  JOIN material m ON m.id = d."Material_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 GROUP BY 1, 2
				 ORDER BY 3 DESC, 1
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	/**
	 * 작업자별.
	 *
	 * ★ Actor_id 가 빈 건을 버리지 않는다. 「미지정」 한 줄로 모은다.
	 *   등록했는데 어느 집계에도 안 나오면 작업자는 등록이 안 된 줄 안다.
	 */
	public List<Map<String, Object>> getByWorker(String dateFrom, String dateTo,
												 Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT COALESCE(pe."Name", '미지정')  AS name
				     , SUM(COALESCE(d."DefectQty",0)) AS defect_qty
				     , COUNT(*)                       AS defect_cnt
				  FROM defect_regist d
				  LEFT JOIN person pe ON pe.id = d."Actor_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 GROUP BY 1
				 ORDER BY 2 DESC, 1
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	// =================================================================
	// 상세
	// =================================================================

	/**
	 * 불량 상세.
	 *
	 * 로트번호는 defect_regist_lot 에 FIFO 로 여러 건이 걸린다.
	 * 한 건만 뽑으면 「어느 로트에서 몇 개」라는 재고 대사의 단서가 사라지므로
	 * string_agg 로 전부 붙인다.
	 *
	 * 작지 미지정(JobResponse_id IS NULL) 건도 그대로 내린다.
	 * 미연결 비율 자체가 데이터 품질 지표다.
	 */
	public List<Map<String, Object>> getDetail(String dateFrom, String dateTo,
											   Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT d.id                                        AS pk
				     , to_char(d."DefectDate", 'yyyy-mm-dd')       AS defect_date
				     , p.id                                       AS proc_id
				     , p."Code"                                    AS proc_code
				     , COALESCE(p."Name", '미지정')                 AS proc_name
				     , m."Code"                                    AS mat_code
				     , m."Name"                                    AS mat_name
				     , COALESCE(t."Name", NULLIF(d."DefectTypeEtc",''), '미지정') AS type_name
				     , COALESCE(d."DefectQty",0)                   AS defect_qty
				     , COALESCE(pe."Name", '미지정')                AS worker_name
				     , jr."WorkOrderNumber"                        AS work_order
				     , COALESCE(d."Description",'')                AS description
				     , COALESCE(lot.lot_numbers, '')               AS lot_numbers
				  FROM defect_regist d
				  LEFT JOIN process     p  ON p.id  = d."Process_id"
				  LEFT JOIN material    m  ON m.id  = d."Material_id"
				  LEFT JOIN defect_type t  ON t.id  = d."DefectType_id"
				  LEFT JOIN person      pe ON pe.id = d."Actor_id"
				  LEFT JOIN job_res     jr ON jr.id = d."JobResponse_id"
				  LEFT JOIN LATERAL (
				        SELECT string_agg(dl."LotNumber" || '(' || COALESCE(dl."Qty",0) || ')', ', '
				                          ORDER BY dl.id) AS lot_numbers
				          FROM defect_regist_lot dl
				         WHERE dl."DefectRegist_id" = d.id
				  ) lot ON true
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 ORDER BY d."DefectDate" DESC, d.id DESC
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	/**
	 * 작지 연결률 — 데이터 품질 지표.
	 *
	 * 특정 공정만 계속 미연결이면 그 공정의 작지 후보 조회 범위를 손봐야 한다는 신호다.
	 */
	public List<Map<String, Object>> getUnlinked(String dateFrom, String dateTo,
												 Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		String sql = """
				SELECT COALESCE(p."Name", '미지정') AS proc_name
				     , COUNT(*)                                                     AS total_cnt
				     , COUNT(*) FILTER (WHERE d."JobResponse_id" IS NULL)            AS unlinked_cnt
				  FROM defect_regist d
				  LEFT JOIN process p ON p.id = d."Process_id"
				 WHERE d."State" = 'confirmed'
				   AND d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				   AND (CAST(:factory_id AS integer)  IS NULL OR d."Factory_id"  = CAST(:factory_id AS integer))
				   AND (CAST(:process_id AS integer)  IS NULL OR d."Process_id"  = CAST(:process_id AS integer))
				   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
				 GROUP BY 1
				 HAVING COUNT(*) FILTER (WHERE d."JobResponse_id" IS NULL) > 0
				 ORDER BY 3 DESC
				""";

		return nz(this.sqlRunner.getRows(sql,
				base(dateFrom, dateTo, factoryId, processId, materialId, spjangcd)));
	}

	// =================================================================
	// 2공장 전용 — 유닛 검사 불합격
	// =================================================================

	/**
	 * 2공장 유닛 불합격 (별도 블록).
	 *
	 * ★ defect_regist 와 합치지 않는다. 부적합 등록 화면이 InspectYN='Y' 를
	 *   자재 후보에서 빼기 때문에 유닛 본체 불량은 defect_regist 에 아예 없고,
	 *   진실은 insp_result."FailReason" 이다. 합치면 이중계상이 아니라
	 *   「축이 다른 두 숫자를 더한 값」이 된다.
	 *
	 * ★ 판정은 양식별 마지막 회차. 불합격 회차가 하나라도 있으면 불합격으로 치면
	 *   재검사로 통과한 대가 영원히 불합격으로 남는다.
	 */
	public List<Map<String, Object>> getUnitFail(String dateFrom, String dateTo, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("date_from", dateFrom)
				.addValue("date_to", dateTo)
				.addValue("spjangcd", spjangcd);

		String sql = """
				WITH last_try AS (
				    SELECT r."McellUnit_id"                                  AS unit_id
				         , r."InspForm_id"                                   AS form_id
				         , (ARRAY_AGG(r."Verdict"    ORDER BY r."TryNo" DESC, r.id DESC))[1] AS verdict
				         , (ARRAY_AGG(r."FailReason" ORDER BY r."TryNo" DESC, r.id DESC))[1] AS fail_reason
				         , MAX(r."_created")                                 AS last_at
				      FROM insp_result r
				     WHERE r."_created"::date BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
				     GROUP BY 1, 2
				)
				SELECT COALESCE(NULLIF(lt.fail_reason,''), '사유 미기재') AS name
				     , COUNT(DISTINCT lt.unit_id)                        AS unit_cnt
				  FROM last_try lt
				 WHERE lt.verdict = 'reject'
				 GROUP BY 1
				 ORDER BY 2 DESC, 1
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 한 방에 (화면은 이것만 부른다)
	// =================================================================

	/**
	 * 화면이 여러 번 부르지 않게 묶는다.
	 * 따로 오면 그 사이 갱신으로 KPI 와 상세의 합계가 어긋날 수 있다.
	 *
	 * rate_valid : 품목 필터가 걸리면 false.
	 *              분모(mat_produce)에는 투입 자재 축이 없어 불량률을 만들 수 없다.
	 */
	public Map<String, Object> getDashboard(String dateFrom, String dateTo,
											Integer factoryId, Integer processId, Integer materialId, String spjangcd) {

		boolean rateValid = (materialId == null);

		Map<String, Object> data = new HashMap<>();
		data.put("rate_valid", rateValid);
		data.put("daily_defect", getDailyDefect(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("daily_output", rateValid
				? getDailyOutput(dateFrom, dateTo, factoryId, processId, spjangcd)
				: new ArrayList<>());
		data.put("by_process", getByProcess(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("by_type",    getByType(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("by_material", getByMaterial(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("by_worker",  getByWorker(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("detail",     getDetail(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("unlinked",   getUnlinked(dateFrom, dateTo, factoryId, processId, materialId, spjangcd));
		data.put("unit_fail",  (factoryId != null && factoryId == 2)
				? getUnitFail(dateFrom, dateTo, spjangcd)
				: new ArrayList<>());
		return data;
	}
}