package mes.app.production.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mes.domain.entity.User;
import mes.domain.services.SqlRunner;

/**
 * 부적합 등록.
 *
 * 설계 전제 (2026-07 확정)
 *  - 생산 공정 화면에서는 부적합을 등록하지 않는다. 이 화면이 유일한 등록처.
 *  - 따라서 mat_produce."DefectQty" / job_res_defect / defect_type_result 는 건드리지 않는다.
 *  - 등록 = 재고 차감. 로트 FIFO 로 뺀다(생산 투입과 같은 규칙).
 *  - 차감만 하고 부적합창고로 입고하지 않는다 — 회수하지 않는 것이 곧 폐기 처리
 *    (2공장 수리 §5.5 와 같은 원칙). 격리 보관을 재고로 잡아야 하면 그때 in 을 더한다.
 *
 * 2026-08 추가 — 원자재불량
 *  - "DefectSource" 로 work(작업불량) / material(원자재불량) 을 가른다.
 *  - 원자재불량은 공정·작지·작업자가 없다. "Process_id" 는 NULL.
 *  - ★ 차감 창고를 계산하지 않고 **사용자가 고른다.**
 *    사오는 원자재 재고가 자재(3)·클린룸(5)·생산(17) 에 흩어져 있어
 *    어느 번호로 고정해도 대부분의 품목이 "재고 부족" 으로 막힌다(실측 확인).
 *    대신 서버가 그 창고에 실재고가 있는지 반드시 재검증한다 — 화면 값을 믿지 않는다.
 *  - "DiscoveryStage" 로 입고 시 발견인지 불출 후 발견인지 남긴다.
 *    화면 문구로만 두면 나중에 공급사 클레임 집계를 못 뽑는다.
 *
 *  ★ FIFO 차감 · mat_lot_cons · mat_inout · 삭제 롤백은 **바뀌지 않는다.**
 *    달라지는 것은 srcStore 를 어떻게 정하느냐 하나뿐이다.
 *
 * ★ 재고 3종 세트를 여기서만 만든다 (MCELL 기준문서 §6 함정)
 *    mat_lot_cons : 차감의 진실. 트리거가 mat_lot."CurrentStock" 를 재계산한다
 *    mat_inout    : 이력. out 은 반드시 "OutputQty" (InputQty 에 넣으면 가산된다)
 *    mat_consu    : 만들지 않는다. 작지가 없어 "JobResponse_id" 를 채울 수 없고,
 *                   투입 소비가 아니라 폐기이므로 투입이력에 섞이면 안 된다.
 */
@Service
public class DefectService {

	@Autowired
	SqlRunner sqlRunner;

	private static final int STORE_DEFECT = 2;   // 부적합창고 (현재는 미사용, 격리입고 확장용)
	private static final int STORE_PROD   = 17;  // 생산창고
	private static final int STORE_MAT    = 3;   // 자재창고 (입고 직후 자리)

	/** 부적합 구분 */
	public static final String SRC_WORK     = "work";       // 작업불량 — 공정에서 발생
	public static final String SRC_MATERIAL = "material";   // 원자재불량 — 입고 자재

	/**
	 * 화면에 뿌릴 공정 순서.
	 * process."Code" 사전순(bsc04 멸균 → bsc05 포장 → bsc06 융착)이 실제 흐름과 다르다.
	 * 코드를 재부여하면 라우팅·워크센터가 전부 딸려오므로 표시 순서만 여기서 잡는다.
	 */
	private static final String PROC_ORDER = """
            (CASE p."Code"
                WHEN 'bsc01' THEN 1   -- 세척
                WHEN 'bsc02' THEN 2   -- 조립
                WHEN 'bsc03' THEN 3   -- 블리스터 포장
                WHEN 'bsc04' THEN 4   -- 멸균
                WHEN 'bsc06' THEN 5   -- 고주파 융착
                WHEN 'bsc05' THEN 6   -- 포장
                WHEN 'mc01'  THEN 1   -- 조립(2공장)
                WHEN 'mc02'  THEN 2   -- 검사
                WHEN 'mc04'  THEN 3   -- 수리
                WHEN 'mc03'  THEN 4   -- 포장(2공장)
                ELSE 99
             END)
            """;

	/**
	 * 공정별 소스창고 override.
	 *
	 * ★ 소스창고가 품목만으로 정해지지 않는 경우가 있다.
	 *   세척(bsc01) : 생산창고(17) → 클린룸(5) 로 옮기는 공정이다.
	 *                 품목은 WashYN='Y' 라 resolveSourceStore 가 클린룸(5)로 보지만,
	 *                 그건 "세척이 끝난 뒤" 조립이 꺼내 쓰는 자리다.
	 *                 세척 공정에서 발견한 불량은 아직 옮기기 전이므로 생산창고에서 뺀다.
	 *   그 외        : resolveSourceStore 규칙 그대로.
	 */
	private static final String PROC_STORE_OVERRIDE = """
            (CASE (SELECT p3."Code" FROM process p3 WHERE p3.id = :procId)
                WHEN 'bsc01' THEN 17
                ELSE NULL
             END)
            """;

	// =================================================================
	// 조회
	// =================================================================

	/** 화면 진입 컨텍스트 — 공장의 공정 목록 + 공정별 부적합 유형 */
	public Map<String, Object> getContext(int factoryId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("fid", factoryId);

		String procSql = """
                SELECT p.id, p."Code" AS code, p."Name" AS name
                  FROM process p
                 WHERE COALESCE(p."Factory_id", 1) = :fid
                   AND COALESCE(p._status, 'a') = 'a'
                   AND p."Code" ~ '^(bsc|mc)[0-9]+$'
                 ORDER BY %s, p."Code"
                """.formatted(PROC_ORDER);
		List<Map<String, Object>> procs = this.sqlRunner.getRows(procSql, p);

		// 공정별 유형을 한 번에 내려 화면이 공정 클릭 때마다 호출하지 않게 한다
		String typeSql = """
                SELECT pdt."Process_id" AS process_id
                     , dt.id            AS defect_type_id
                     , dt."Name"        AS defect_type_name
                  FROM proc_defect_type pdt
                  JOIN defect_type dt ON dt.id = pdt."DefectType_id"
                  JOIN process p      ON p.id = pdt."Process_id"
                 WHERE COALESCE(p."Factory_id", 1) = :fid
                   AND COALESCE(dt._status, 'a') = 'a'
                   -- ★ 원자재 전용 유형이 공정 목록에 섞이지 않게 한다
                   AND COALESCE(dt."Coverage", 'all') IN ('work', 'all')
                 ORDER BY pdt."Process_id"
                        , CASE WHEN dt."Name" = '기타' THEN 1 ELSE 0 END   -- 기타는 항상 맨 뒤
                        , dt.id
                """;
		List<Map<String, Object>> types = this.sqlRunner.getRows(typeSql, p);

		// ★ 원자재불량 유형은 proc_defect_type 을 타지 않는다.
		//   공정이 없으니 매핑할 자리가 없다. Coverage 로만 거른다.
		String matTypeSql = """
                SELECT dt.id     AS defect_type_id
                     , dt."Name" AS defect_type_name
                  FROM defect_type dt
                 WHERE COALESCE(dt._status, 'a') = 'a'
                   AND COALESCE(dt."Coverage", 'all') IN ('material', 'all')
                 ORDER BY CASE WHEN dt."Name" = '기타' THEN 1 ELSE 0 END, dt.id
                """;
		List<Map<String, Object>> matTypes = this.sqlRunner.getRows(matTypeSql, p);

		Map<String, Object> ctx = new HashMap<>();
		ctx.put("factory_id", factoryId);
		ctx.put("processes", procs);
		ctx.put("defect_types", types);
		ctx.put("mat_defect_types", matTypes == null ? new ArrayList<>() : matTypes);
		return ctx;
	}

	/**
	 * 원자재불량 자재 후보.
	 *
	 * 공정이 없으므로 라우팅·플래그로 좁힐 수 없다. 그 공장 자재 중
	 * **어느 창고든 실재고가 있는 것**을 위로 올려 전부 내린다.
	 *
	 * ★ 창고를 여기서 확정하지 않는다. 한 자재가 여러 창고에 있을 수 있고,
	 *   어느 창고에서 뺄지는 사용자가 고른다(getStoreList).
	 *   그래서 stock_qty 는 **전 창고 합계**다.
	 *
	 * ★ InspectYN='Y' 제외는 작업불량과 같다 — 유닛 불량은 insp_result 가 진실이다.
	 */
	public List<Map<String, Object>> getMaterialListForSource(int factoryId, String keyword) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("fid", factoryId);
		p.addValue("kw", keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%");

		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT m.id                        AS mat_id
                     , m."Code"                    AS mat_code
                     , m."Name"                    AS mat_name
                     , u."Name"                    AS unit
                     , COALESCE(st.qty, 0)         AS stock_qty
                     , COALESCE(st.store_cnt, 0)   AS store_cnt
                     , st.store_names              AS store_names
                  FROM material m
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock")              AS qty
                             , COUNT(DISTINCT ml."StoreHouse_id")  AS store_cnt
                             , string_agg(DISTINCT sh."Name", ', ') AS store_names
                          FROM mat_lot ml
                          JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                         WHERE ml."Material_id"  = m.id
                           AND ml."CurrentStock" > 0
                           AND COALESCE(ml._status, 'a') = 'a'
                  ) st ON true
                 WHERE COALESCE(m._status, 'a') = 'a'
                   AND COALESCE(m."InspectYN", 'N') <> 'Y'
                   AND COALESCE(m."Factory_id", 1) = :fid
                   AND (CAST(:kw AS varchar) IS NULL
                        OR m."Name" LIKE CAST(:kw AS varchar)
                        OR m."Code" LIKE CAST(:kw AS varchar))
                 ORDER BY COALESCE(st.qty, 0) > 0 DESC, m."Code"
                """, p);
		return rows == null ? new ArrayList<>() : rows;
	}

	/**
	 * 그 자재의 재고가 있는 창고 목록.
	 *
	 * 원자재불량에서 「어느 창고에서 뺄까」를 사용자가 고르게 하기 위한 것.
	 * 화면은 창고명 + 재고 + 안내문구를 버튼에 붙이고, 1건이면 자동 선택한다.
	 *
	 * ★ hint 를 서버가 내리는 이유 —
	 *   등록하는 사람이 「입고 후 아직 안 쓴 것」인지 「불출해서 쓰다 발견한 것」인지
	 *   창고 이름만 보고는 모른다. 자재창고면 입고 직후, 그 외면 불출 후다.
	 *   stage 는 그 판단을 DiscoveryStage 기본값으로 화면에 제안하는 용도이고,
	 *   최종 값은 사람이 바꿀 수 있다 — 창고 위치가 발견 시점을 100% 결정하지는 않는다.
	 */
	public List<Map<String, Object>> getStoreList(int matId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("matId", matId).addValue("matStore", STORE_MAT);
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT ml."StoreHouse_id"                     AS store_id
                     , sh."Name"                              AS store_name
                     , SUM(ml."CurrentStock")                 AS stock_qty
                     , COUNT(*)                               AS lot_cnt
                     , CASE WHEN ml."StoreHouse_id" = :matStore
                            THEN 'incoming' ELSE 'issued' END AS stage
                     , CASE WHEN ml."StoreHouse_id" = :matStore
                            THEN '입고 후 미불출 — 수입 시점 부적합'
                            ELSE '불출 후 — 사용 중 확인된 부적합' END AS hint
                  FROM mat_lot ml
                  JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                 WHERE ml."Material_id"  = :matId
                   AND ml."CurrentStock" > 0
                   AND COALESCE(ml._status, 'a') = 'a'
                 GROUP BY ml."StoreHouse_id", sh."Name"
                 ORDER BY ml."StoreHouse_id" = :matStore DESC, SUM(ml."CurrentStock") DESC
                """, p);
		return rows == null ? new ArrayList<>() : rows;
	}

	/**
	 * 공정에서 다루는 자재 후보 = 그 공정 라우팅의 BOM 투입자재 + 산출 반제품.
	 * 재고가 0 이어도 목록에는 띄우고(오등록 방지를 위해 재고를 함께 보여준다),
	 * 저장 시점에 서버가 막는다.
	 *
	 * ★ InspectYN='Y' (M-CELL 유닛 본체) 는 제외한다.
	 *   유닛 불량은 mcell_unit."State"='reject' + insp_result."FailReason" 이 진실이라
	 *   여기서 또 받으면 이중 계상이 된다. (2공장 수리 getStockList 와 같은 규칙)
	 */
	public List<Map<String, Object>> getMaterialList(int factoryId, Integer processId,
													 String keyword, boolean all) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("fid", factoryId);
		p.addValue("procId", processId);
		p.addValue("kw", keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%");

		if (!all) {
			// ★ SqlRunner.getRows 는 오류 시 null 을 돌려준다. isEmpty() 로 바로 못 부른다.
			List<Map<String, Object>> rows = this.sqlRunner.getRows(buildMaterialSql(false), p);
			if (rows != null && !rows.isEmpty()) return rows;
			// 공정 범위로 0건 → 창고 재고 기준으로 자동 폴백.
			// 세척처럼 작지·BOM 이 없는 공정에서 목록이 비면 등록 자체를 못 한다.
		}
		List<Map<String, Object>> allRows = this.sqlRunner.getRows(buildMaterialSql(true), p);
		return allRows == null ? new ArrayList<>() : allRows;
	}

	/**
	 * 자재 후보 SQL.
	 *
	 * ★ 공정마다 판별 축이 다르다. 작지+BOM 역추적 하나로는 안 된다.
	 *     세척  bsc01 : 작지 개념이 없다(wash_work 재고이동). BOM 도 없다  → WashYN='Y'
	 *     멸균  bsc04 : 상태변경 공정이라 산출 BOM 이 없다              → SterilizationYN='Y'
	 *     융착  bsc06 : 플래그 없음(WeldingYN 폐기)                    → 라우팅
	 *     그 외        : 라우팅(작지 산출품목 + 그 BOM 투입자재)
	 *
	 * @param all true 면 공정 범위를 무시하고 그 공장 자재 중 소스창고에 재고가 있는 것 전부.
	 *            (「전체 자재」 토글 / 공정 범위 0건일 때의 폴백)
	 */
	private String buildMaterialSql(boolean all) {
		String srcCte = all
				? """
                WITH src AS (
                    -- 전체 자재: 소스창고에 실재고가 있는 것만.
                    -- 재고 0 을 다 띄우면 수천 건이 되고 어차피 차감도 안 된다.
                    SELECT m2.id AS mat_id
                      FROM material m2
                     WHERE COALESCE(m2._status, 'a') = 'a'
                )
              """
				: """
                WITH flag AS (
                    -- 이 공정이 품목 플래그로 대상을 판별하는 공정인가
                    SELECT p2."Code" AS pcode FROM process p2 WHERE p2.id = :procId
                ),
                src AS (
                    -- (1) 플래그 공정 — 세척 / 멸균 / 융착
                    SELECT m2.id AS mat_id
                      FROM material m2 CROSS JOIN flag f
                     WHERE (f.pcode = 'bsc01' AND COALESCE(m2."WashYN", 'N') = 'Y')
                        OR (f.pcode = 'bsc04' AND COALESCE(m2."SterilizationYN", 'N') = 'Y')
                    UNION
                    -- (2) 라우팅 공정 — 그 공정 작지의 산출품목
                    SELECT DISTINCT jr."Material_id" AS mat_id
                      FROM work_center wc
                      JOIN job_res jr ON jr."WorkCenter_id" = wc.id
                     WHERE wc."Process_id" = :procId
                    UNION
                    -- (3) 라우팅 공정 — 산출품목 BOM 의 투입자재
                    SELECT DISTINCT bc."Material_id" AS mat_id
                      FROM work_center wc
                      JOIN job_res jr  ON jr."WorkCenter_id" = wc.id
                      JOIN bom b       ON b."Material_id" = jr."Material_id"
                                      AND b."BOMType" = 'manufacturing'
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                     WHERE wc."Process_id" = :procId
                )
              """;

		// 「전체 자재」일 때만 재고 0 을 잘라낸다. 공정 범위에서는 재고 0 도 띄운다 —
		// 목록에서 사라지면 작업자가 "왜 없지" 로 헤매고, 재고가 0 이라는 사실 자체가 정보다.
		String stockFilter = all ? "                   AND COALESCE(st.qty, 0) > 0\n" : "";

		return srcCte + """
                SELECT m.id                      AS mat_id
                     , m."Code"                  AS mat_code
                     , m."Name"                  AS mat_name
                     , u."Name"                  AS unit
                     , dfs.store_id              AS src_store_id
                     , sh."Name"                 AS src_store_name
                     , COALESCE(st.qty, 0)       AS stock_qty
                  FROM src
                  JOIN material m      ON m.id = src.mat_id
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                  LEFT JOIN unit u     ON u.id = m."Unit_id"
                  CROSS JOIN LATERAL (SELECT COALESCE(%s, %s) AS store_id) dfs
                  LEFT JOIN store_house sh ON sh.id = dfs.store_id
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS qty
                          FROM mat_lot ml
                         WHERE ml."Material_id"   = m.id
                           AND ml."StoreHouse_id" = dfs.store_id
                           AND ml."CurrentStock"  > 0
                  ) st ON true
                 WHERE COALESCE(m._status, 'a') = 'a'
                   AND COALESCE(m."InspectYN", 'N') <> 'Y'
                   AND COALESCE(m."Factory_id", 1) = :fid
                   AND (CAST(:kw AS varchar) IS NULL
                        OR m."Name" LIKE CAST(:kw AS varchar)
                        OR m."Code" LIKE CAST(:kw AS varchar))
                """.formatted(PROC_STORE_OVERRIDE, SRC_STORE_CASE)
				+ stockFilter
				+ "                 ORDER BY COALESCE(st.qty,0) > 0 DESC, m.\"Code\"\n";
	}

	/**
	 * 관련 작업지시 후보.
	 * 그 공정의 작지 중 발생일자 기준 ±7일 것을 최신순으로 내린다.
	 *
	 * 선택 항목이므로 없으면 빈 배열을 돌려주고, 화면은 「작지 없음」으로 저장한다.
	 * 강제하면 현장이 아무거나 찍게 되어 오히려 집계를 오염시킨다.
	 */
	public List<Map<String, Object>> getWorkOrderList(int processId, String defectDate) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("procId", processId);
		p.addValue("ddate", defectDate);

		String sql = """
                SELECT jr.id                                        AS wo_id
                     , jr."WorkOrderNumber"                         AS order_num
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd')   AS prod_date
                     , jr."State"                                   AS state
                     , fn_code_name('job_state', jr."State")        AS state_name
                     , m."Code"                                     AS mat_code
                     , m."Name"                                     AS mat_name
                     , ROUND(COALESCE(jr."OrderQty", 0)::numeric, 2) AS order_qty
                     , u."Name"                                     AS unit
                     , ABS(jr."ProductionDate"::date - CAST(:ddate AS date)) AS day_gap
                  FROM job_res jr
                  JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN unit     u ON u.id = m."Unit_id"
                 WHERE wc."Process_id" = :procId
                   AND COALESCE(jr._status, 'a') = 'a'
                   AND jr."ProductionDate"::date
                       BETWEEN CAST(:ddate AS date) - 7 AND CAST(:ddate AS date) + 7
                 ORDER BY day_gap ASC, jr."ProductionDate" DESC, jr.id DESC
                """;
		List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, p);
		return rows == null ? new ArrayList<>() : rows;
	}

	// 융착(bsc06)은 품목 플래그로 판별하지 않는다.
	//   WeldingYN 컬럼은 만들지 않기로 확정(2026-07). 라우팅(작지 산출품목 + 그 BOM)으로
	//   이미 좁혀지고, 플래그를 하나 더 두면 둘이 어긋났을 때 화면에서 조용히 사라진다.
	//   라우팅으로도 0건이면 「전체 자재」 폴백이 받는다.

	/** 목록 (KPI 는 화면에서 이 결과로 집계) */
	public List<Map<String, Object>> getList(int factoryId, String dateFrom, String dateTo,
											 Integer processId, String defectSource,
											 String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("fid", factoryId);
		p.addValue("df", dateFrom);
		p.addValue("dt", dateTo);
		p.addValue("procId", processId);
		p.addValue("src", (defectSource == null || defectSource.isBlank()) ? null : defectSource);
		p.addValue("spjangcd", spjangcd);

		String sql = """
                SELECT d.id
                     , to_char(d."DefectDate", 'yyyy-mm-dd')          AS defect_date
                     , d."Process_id"                                  AS process_id
                     , p."Name"                                        AS process_name
                     , COALESCE(d."DefectSource", 'work')              AS defect_source
                     , d."DiscoveryStage"                              AS discovery_stage
                     , d."Material_id"                                 AS mat_id
                     , m."Code"                                        AS mat_code
                     , m."Name"                                        AS mat_name
                     , u."Name"                                        AS unit
                     , COALESCE(dt."Name", d."DefectTypeEtc")          AS defect_type_name
                     , d."DefectQty"                                   AS defect_qty
                     , d."Actor_id"                                    AS actor_id
                     , pe."Name"                                       AS actor_name
                     , d."Description"                                 AS description
                     , sh."Name"                                       AS src_store_name
                     , d."JobResponse_id"                              AS wo_id
                     , jr."WorkOrderNumber"                            AS order_num
                     , COALESCE(f.cnt, 0)                              AS file_cnt
                     , f.first_path                                    AS thumb_path
                     , l.lot_numbers                                   AS lot_numbers
                  FROM defect_regist d
                  LEFT JOIN process     p  ON p.id  = d."Process_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                  LEFT JOIN unit        u  ON u.id  = m."Unit_id"
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN person      pe ON pe.id = d."Actor_id"
                  LEFT JOIN store_house sh ON sh.id = d."SourceStoreHouse_id"
                  LEFT JOIN job_res     jr ON jr.id = d."JobResponse_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS cnt, MIN("FilePath") AS first_path
                          FROM defect_regist_file rf
                         WHERE rf."DefectRegist_id" = d.id AND COALESCE(rf._status,'a')='a'
                  ) f ON true
                  LEFT JOIN LATERAL (
                        SELECT string_agg(rl."LotNumber", ', ' ORDER BY rl.id) AS lot_numbers
                          FROM defect_regist_lot rl
                         WHERE rl."DefectRegist_id" = d.id
                  ) l ON true
                 WHERE d."Factory_id" = :fid
                   AND d."State" = 'confirmed'
                   AND COALESCE(d._status, 'a') = 'a'
                   AND d."DefectDate" BETWEEN CAST(:df AS date) AND CAST(:dt AS date)
                   -- ★ CAST 필수: `? IS NULL` 만 있는 파라미터는 PostgreSQL 이 타입을
                   --   추론하지 못해 "매개 변수의 자료형을 알 수가 없습니다" 로 터진다.
                   --   NamedParameterJdbcTemplate 이 같은 이름도 각각 별개 ? 로 펼치기 때문.
                   -- 공정 필터를 걸면 원자재불량(Process_id IS NULL)은 자연히 빠진다.
                   -- 그게 맞다 — 공정을 고른 사람은 그 공정 건만 보려는 것이다.
                   AND (CAST(:procId AS integer) IS NULL OR d."Process_id" = CAST(:procId AS integer))
                   AND (CAST(:src AS varchar) IS NULL
                        OR COALESCE(d."DefectSource",'work') = CAST(:src AS varchar))
                   AND (CAST(:spjangcd AS varchar) IS NULL OR d.spjangcd = CAST(:spjangcd AS varchar))
                 ORDER BY d."DefectDate" DESC, d.id DESC
                """;
		return this.sqlRunner.getRows(sql, p);
	}

	/**
	 * 상세.
	 * 카드에 이미 있는 값(공정·유형·자재·수량)은 요약으로만 두고,
	 * **카드에서 볼 수 없는 것**을 담는다 — 로트별 차감 수량과 사진 전체.
	 * FIFO 로 여러 로트에 걸치면 "어느 로트에서 몇 개"가 재고 대사의 유일한 단서다.
	 */
	public Map<String, Object> getDetail(int defectId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", defectId);

		Map<String, Object> head = this.sqlRunner.getRow("""
                SELECT d.id
                     , to_char(d."DefectDate", 'yyyy-mm-dd')          AS defect_date
                     , p."Name"                                       AS process_name
                     , m."Code"                                       AS mat_code
                     , m."Name"                                       AS mat_name
                     , u."Name"                                       AS unit
                     , COALESCE(dt."Name", d."DefectTypeEtc")         AS defect_type_name
                     , d."DefectQty"                                  AS defect_qty
                     , pe."Name"                                      AS actor_name
                     , d."Description"                                AS description
                     , sh."Name"                                      AS src_store_name
                     , jr."WorkOrderNumber"                           AS order_num
                     , to_char(d._created, 'yyyy-mm-dd hh24:mi')      AS created_at
                     , d."Process_id"                                  AS process_id
                     , COALESCE(d."DefectSource", 'work')              AS defect_source
                     , d."DiscoveryStage"                              AS discovery_stage
                     , d."DefectType_id"                               AS defect_type_id
                     , d."DefectTypeEtc"                               AS defect_type_etc
                     , d."Actor_id"                                    AS actor_id
                     , d."JobResponse_id"                              AS job_res_id
                  FROM defect_regist d
                  LEFT JOIN process     p  ON p.id  = d."Process_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                  LEFT JOIN unit        u  ON u.id  = m."Unit_id"
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN person      pe ON pe.id = d."Actor_id"
                  LEFT JOIN store_house sh ON sh.id = d."SourceStoreHouse_id"
                  LEFT JOIN job_res     jr ON jr.id = d."JobResponse_id"
                 WHERE d.id = :id
                """, p);

		List<Map<String, Object>> lots = this.sqlRunner.getRows("""
                SELECT rl."LotNumber"                                  AS lot_number
                     , rl."Qty"                                        AS qty
                     , ml."MakerLotNo"                                 AS maker_lot_no
                     , to_char(ml."InputDateTime", 'yyyy-mm-dd')       AS input_date
                  FROM defect_regist_lot rl
                  LEFT JOIN mat_lot ml ON ml.id = rl."MaterialLot_id"
                 WHERE rl."DefectRegist_id" = :id
                 ORDER BY rl.id
                """, p);

		Map<String, Object> r = new HashMap<>();
		r.put("head",  head);
		r.put("lots",  lots == null ? new ArrayList<>() : lots);
		r.put("files", getFileList(defectId));
		return r;
	}

	public List<Map<String, Object>> getFileList(int defectId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", defectId);
		return this.sqlRunner.getRows("""
                SELECT id AS file_id, "FileName" AS file_name, "FilePath" AS file_path
                  FROM defect_regist_file
                 WHERE "DefectRegist_id" = :id AND COALESCE(_status,'a')='a'
                 ORDER BY id
                """, p);
	}

	// =================================================================
	// 등록
	// =================================================================

	/**
	 * 부적합 등록 + 로트 FIFO 차감.
	 *
	 * ★ 작업불량과 원자재불량이 갈리는 곳은 **srcStore 를 정하는 한 줄뿐이다.**
	 *   그 아래 FIFO 차감·재고 3종 세트는 완전히 같다. 경로를 둘로 만들지 않는다 —
	 *   둘로 나뉘면 한쪽만 고쳐서 재고가 어긋나는 사고가 난다.
	 *
	 * @param defectSource   work | material
	 * @param processId      원자재불량이면 null
	 * @param srcStoreId     원자재불량이면 사용자가 고른 창고. 작업불량이면 무시(계산한다)
	 * @param discoveryStage 원자재불량만. incoming | issued
	 * @return 생성된 defect_regist.id
	 */
	@Transactional
	public int regist(int factoryId, String defectSource, String defectDate,
					  Integer processId, int matId,
					  Integer defectTypeId, String defectTypeEtc, double qty,
					  Integer srcStoreId, String discoveryStage,
					  Integer jobResId, Integer actorId, String description,
					  User user, String spjangcd) {

		boolean isMaterial = SRC_MATERIAL.equals(defectSource);

		if (qty <= 0) throw new IllegalArgumentException("불량 수량을 확인해주세요.");
		if (defectTypeId == null && (defectTypeEtc == null || defectTypeEtc.isBlank()))
			throw new IllegalArgumentException("불량 유형을 선택하거나 직접 입력해주세요.");

		// 창고 판정 — 여기가 두 구분의 유일한 분기점이다.
		int srcStore;
		if (isMaterial) {
			// ★ 계산하지 않는다. 사오는 원자재 재고가 자재(3)·클린룸(5)·생산(17) 에
			//   흩어져 있어 어느 규칙으로도 한 창고로 좁혀지지 않는다.
			//   대신 **화면 값을 믿지 않고** 실재고를 여기서 재검증한다.
			if (srcStoreId == null || srcStoreId == 0)
				throw new IllegalArgumentException("차감할 창고를 선택해주세요.");
			assertStoreHasStock(matId, srcStoreId);
			srcStore = srcStoreId;
			processId = null;   // 원자재불량은 공정을 갖지 않는다
		} else {
			if (processId == null || processId == 0)
				throw new IllegalArgumentException("공정을 선택해주세요.");
			srcStore = resolveSourceStore(matId, processId);
		}

		// 1) 재고 확인 — 담는 시점에 막는다. 실적 껍데기를 만든 뒤 터지면 원인을 늦게 안다
		List<Map<String, Object>> lots = findFifoLots(matId, srcStore);
		double avail = 0d;
		for (Map<String, Object> l : lots) avail += toD(l.get("current_stock"));
		if (avail < qty) {
			throw new IllegalArgumentException(
					"재고가 부족합니다. (창고 재고 %s / 요청 %s)".formatted(fmt(avail), fmt(qty)));
		}

		// 2) 헤더
		MapSqlParameterSource h = new MapSqlParameterSource();
		h.addValue("fid", factoryId);
		h.addValue("ddate", defectDate);
		h.addValue("procId", processId);
		h.addValue("matId", matId);
		h.addValue("dtId", defectTypeId);
		h.addValue("dtEtc", defectTypeEtc);
		h.addValue("qty", qty);
		h.addValue("srcStore", srcStore);
		// 원자재불량은 작지·작업자를 받지 않는다. 화면이 보내더라도 여기서 잘라낸다 —
		// 공정 없는 건에 작지가 붙으면 목록·집계에서 계보가 꼬인다.
		h.addValue("woId",   isMaterial ? null : jobResId);
		h.addValue("actorId", isMaterial ? null : actorId);
		h.addValue("desc", description);
		h.addValue("src", isMaterial ? SRC_MATERIAL : SRC_WORK);
		h.addValue("stage", isMaterial ? normalizeStage(discoveryStage) : null);
		h.addValue("uid", user == null ? null : user.getId());
		h.addValue("spjangcd", spjangcd);

		Map<String, Object> row = this.sqlRunner.getRow("""
                INSERT INTO defect_regist
                    ("Factory_id","DefectDate","Process_id","Material_id","DefectType_id",
                     "DefectTypeEtc","DefectQty","SourceStoreHouse_id","JobResponse_id",
                     "Actor_id","Description","DefectSource","DiscoveryStage",
                     "State",_status,_created,_creater_id,spjangcd)
                VALUES
                    (:fid, CAST(:ddate AS date), :procId, :matId, :dtId,
                     :dtEtc, :qty, :srcStore, :woId,
                     :actorId, :desc, :src, :stage,
                     'confirmed','a',now(),:uid,:spjangcd)
                RETURNING id
                """, h);
		int defectId = ((Number) row.get("id")).intValue();

		// 3) FIFO 차감
		double remain = qty;
		for (Map<String, Object> lot : lots) {
			if (remain <= 0) break;
			int mlId          = ((Number) lot.get("ml_id")).intValue();
			String lotNumber  = (String) lot.get("lot_number");
			double lotStock   = toD(lot.get("current_stock"));
			double takeQty    = Math.min(lotStock, remain);

			int consId  = insertLotCons(mlId, lotStock, takeQty, defectId, spjangcd, user);
			int inoutId = insertInoutOut(matId, srcStore, lotNumber, takeQty, defectId,
					defectTypeLabel(defectTypeId, defectTypeEtc), spjangcd, user);

			MapSqlParameterSource lp = new MapSqlParameterSource();
			lp.addValue("head", defectId);
			lp.addValue("mlId", mlId);
			lp.addValue("lotNo", lotNumber);
			lp.addValue("qty", takeQty);
			lp.addValue("consId", consId);
			lp.addValue("inoutId", inoutId);
			lp.addValue("spjangcd", spjangcd);
			this.sqlRunner.execute("""
                    INSERT INTO defect_regist_lot
                        ("DefectRegist_id","MaterialLot_id","LotNumber","Qty",
                         "MatLotCons_id","MatInout_id",_status,_created,spjangcd)
                    VALUES (:head,:mlId,:lotNo,:qty,:consId,:inoutId,'a',now(),:spjangcd)
                    """, lp);

			remain -= takeQty;
		}

		if (remain > 0.0000001) {
			// 1) 에서 총량을 확인했으므로 정상적으로는 오지 않는다. 동시 등록 방어.
			throw new IllegalStateException("차감 중 재고가 변경되었습니다. 다시 시도해주세요.");
		}
		return defectId;
	}

	/**
	 * 내역 수정.
	 *
	 * ★ 재고에 영향을 주는 것과 아닌 것을 나눈다.
	 *   수량·자재·공정을 바꾸면 차감을 되돌리고 다시 빼야 하는데, 그건 삭제 후 재등록과 같다.
	 *   경로를 둘로 두면 롤백 로직이 두 벌이 되고 어긋나면 재고가 틀어진다.
	 *   → 여기서는 **재고와 무관한 항목만** 고친다.
	 *     유형 / 발생일자 / 작업자 / 작업지시 / 비고
	 *   수량·자재를 바꾸려면 삭제하고 다시 등록한다(화면이 그렇게 안내한다).
	 */
	@Transactional
	public void update(int defectId, Integer defectTypeId, String defectTypeEtc,
					   String defectDate, Integer jobResId, Integer actorId,
					   String description, String discoveryStage, User user) {
		if (defectTypeId == null && (defectTypeEtc == null || defectTypeEtc.isBlank()))
			throw new IllegalArgumentException("불량 유형을 선택하거나 직접 입력해주세요.");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", defectId);
		p.addValue("stage", normalizeStage(discoveryStage));
		p.addValue("dtId", defectTypeId);
		p.addValue("dtEtc", defectTypeEtc);
		p.addValue("ddate", defectDate);
		p.addValue("woId", jobResId);
		p.addValue("actorId", actorId);
		p.addValue("desc", description);
		p.addValue("uid", user == null ? null : user.getId());

		this.sqlRunner.execute("""
                UPDATE defect_regist
                   SET "DefectType_id"  = :dtId
                     , "DefectTypeEtc"  = :dtEtc
                     , "DefectDate"     = CAST(:ddate AS date)
                     -- 원자재불량은 작지·작업자가 없다. 그 건에는 NULL 이 그대로 들어간다
                     , "JobResponse_id" = :woId
                     , "Actor_id"       = :actorId
                     , "Description"    = :desc
                     -- 발견 시점은 원자재불량에서만 의미가 있다.
                     -- 작업불량 건이면 화면이 null 을 보내고 기존 null 이 유지된다.
                     , "DiscoveryStage" = COALESCE(CAST(:stage AS varchar), "DiscoveryStage")
                     , _modified        = now()
                     , _modifier_id     = :uid
                 WHERE id = :id
                """, p);
	}

	/** 사진 1건 삭제 (메타만. 파일 실체는 컨트롤러가 지운다) */
	@Transactional
	public Map<String, Object> deleteFile(int fileId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", fileId);
		Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT id, "DefectRegist_id" AS defect_id, "FilePath" AS file_path
                  FROM defect_regist_file WHERE id = :id
                """, p);
		if (row == null) throw new IllegalArgumentException("사진을 찾을 수 없습니다.");
		this.sqlRunner.execute("DELETE FROM defect_regist_file WHERE id = :id", p);
		return row;
	}

	/**
	 * 삭제 = 차감 되돌리기.
	 * mat_lot_cons 와 mat_inout 을 **양쪽 다** 지운다.
	 * 한쪽만 지우면 유령 재고가 남는다 (MCELL 기준문서 §6).
	 */
	@Transactional
	public void delete(int defectId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", defectId);

		// 이력 순서: inout → cons → lot → head
		this.sqlRunner.execute("""
                DELETE FROM mat_inout
                 WHERE "SourceTableName" = 'defect_regist'
                   AND "SourceDataPk" = :id
                """, p);

		this.sqlRunner.execute("""
                DELETE FROM mat_lot_cons
                 WHERE "SourceTableName" = 'defect_regist'
                   AND "SourceDataPk" = :id
                """, p);

		// mat_lot_cons 삭제로 트리거가 mat_lot."CurrentStock" 를 되살린다.
		// 트리거가 out 합계만 보는 구조이므로 여기서 mat_lot 을 직접 UPDATE 하면 안 된다.

		this.sqlRunner.execute("DELETE FROM defect_regist_lot WHERE \"DefectRegist_id\" = :id", p);
		this.sqlRunner.execute("DELETE FROM defect_regist_file WHERE \"DefectRegist_id\" = :id", p);
		this.sqlRunner.execute("DELETE FROM defect_regist WHERE id = :id", p);
	}

	@Transactional
	public void addFile(int defectId, String fileName, String filePath, Integer size,
						User user, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("head", defectId);
		p.addValue("fn", fileName);
		p.addValue("fp", filePath);
		p.addValue("sz", size);
		p.addValue("uid", user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);
		this.sqlRunner.execute("""
                INSERT INTO defect_regist_file
                    ("DefectRegist_id","FileName","FilePath","FileSize",_status,_created,_creater_id,spjangcd)
                VALUES (:head,:fn,:fp,:sz,'a',now(),:uid,:spjangcd)
                """, p);
	}

	// =================================================================
	// 재고 프리미티브
	// =================================================================

	/**
	 * 소스창고 판정.
	 *
	 * ★ ProductionCreateService.resolveSourceStore() 와 **같은 규칙이어야 한다.**
	 *   생산은 클린룸에서 빼는데 부적합은 생산창고에서 빼면 두 화면의 재고가 갈린다.
	 *   저쪽이 private 이라 지금은 같은 CASE 를 여기 두었다.
	 *   → 저쪽을 public 으로 열면 이 메서드를 지우고 그것을 호출할 것.
	 *
	 *   ★ 2026-07 실제 코드(ProductionCreateService 649행)와 대조해 아래 두 줄을 고쳤다.
	 *     - WashYN='Y' → 5 가 빠져 있었음 (세척 부품을 자재창고에서 찾고 있었다)
	 *     - 기본값이 3(자재) 이었음 → 17(생산) 이 맞다
	 *   이런 어긋남이 바로 "생산은 클린룸에서 빼는데 부적합은 딴 데서 뺀다" 를 만든다.
	 */
	private static final String SRC_STORE_CASE = """
            (CASE
                 -- 2공장(M-CELL) : 클린룸/멸균 개념 없음
                 WHEN COALESCE(m."Factory_id", 1) = 2 THEN
                      (CASE WHEN COALESCE(m."InspectYN", 'N') = 'Y' THEN 19   -- 검사완료창고
                            ELSE 17 END)                                      -- 생산창고
                 -- 1공장(키트)
                 WHEN COALESCE(m."SterilizationYN", 'N') = 'Y'   THEN 18      -- 멸균창고
                 WHEN mg."MaterialType" IN ('semi', 'product')   THEN 5       -- 클린룸(반제품)
                 WHEN COALESCE(m."WashYN", 'N') = 'Y'            THEN 5       -- 클린룸(세척 부품)
                 ELSE 17 END)                                                 -- 생산창고
            """;

	private int resolveSourceStore(int matId, int processId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("matId", matId).addValue("procId", processId);
		String sql = """
                SELECT COALESCE(%s, %s) AS store_id
                  FROM material m
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                 WHERE m.id = :matId
                """.formatted(PROC_STORE_OVERRIDE, SRC_STORE_CASE);
		Map<String, Object> r = this.sqlRunner.getRow(sql, p);
		if (r == null || r.get("store_id") == null)
			throw new IllegalArgumentException("품목의 소스창고를 판정할 수 없습니다.");
		return ((Number) r.get("store_id")).intValue();
	}

	/**
	 * 고른 창고에 실재고가 있는지 확인.
	 *
	 * ★ 화면이 보낸 창고를 그대로 믿지 않는다.
	 *   자재를 고른 뒤 창고를 고르고 저장하기까지 사이에 재고가 빠질 수 있고,
	 *   무엇보다 위조된 요청이 엉뚱한 창고를 차감하는 것을 막아야 한다.
	 *   여기서 막지 않으면 findFifoLots 가 빈 목록을 돌려주고
	 *   "재고가 부족합니다 (창고 재고 0 / 요청 n)" 이라는 헷갈리는 메시지가 나간다.
	 */
	private void assertStoreHasStock(int matId, int storeId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("matId", matId).addValue("storeId", storeId);
		Map<String, Object> r = this.sqlRunner.getRow("""
                SELECT COALESCE(SUM(ml."CurrentStock"), 0) AS qty
                     , (SELECT sh."Name" FROM store_house sh WHERE sh.id = :storeId) AS store_name
                  FROM mat_lot ml
                 WHERE ml."Material_id"   = :matId
                   AND ml."StoreHouse_id" = :storeId
                   AND ml."CurrentStock"  > 0
                   AND COALESCE(ml._status, 'a') = 'a'
                """, p);
		if (r == null || toD(r.get("qty")) <= 0) {
			String nm = (r == null || r.get("store_name") == null)
					? "선택한 창고" : String.valueOf(r.get("store_name"));
			throw new IllegalArgumentException(
					"%s 에 이 자재의 재고가 없습니다. 창고를 다시 선택해주세요.".formatted(nm));
		}
	}

	/** 발견 시점 값 정규화. 모르는 값이 들어오면 CHECK 제약에 걸리므로 여기서 버린다 */
	private static String normalizeStage(String stage) {
		if (stage == null || stage.isBlank()) return null;
		String v = stage.trim();
		return ("incoming".equals(v) || "issued".equals(v)) ? v : null;
	}

	/** 선입선출 로트. 생산 투입과 같은 정렬(InputDateTime) */
	private List<Map<String, Object>> findFifoLots(int matId, int storeId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("storeId", storeId);
		return this.sqlRunner.getRows("""
                SELECT ml.id              AS ml_id
                     , ml."LotNumber"     AS lot_number
                     , ml."CurrentStock"  AS current_stock
                  FROM mat_lot ml
                 WHERE ml."Material_id"   = :matId
                   AND ml."StoreHouse_id" = :storeId
                   AND ml."CurrentStock"  > 0
                   AND COALESCE(ml._status, 'a') = 'a'
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                """, p);
	}

	private int insertLotCons(int matLotId, double lotStock, double outQty,
							  int defectId, String spjangcd, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mlId", matLotId);
		p.addValue("cur", lotStock);
		p.addValue("out", outQty);
		p.addValue("head", defectId);
		p.addValue("uid", user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);
		Map<String, Object> r = this.sqlRunner.getRow("""
                INSERT INTO mat_lot_cons
                    ("MaterialLot_id","OutputDateTime","CurrentStock","OutputQty",
                     "SourceTableName","SourceDataPk",_status,_created,_creater_id,spjangcd)
                VALUES (:mlId, now(), :cur, :out,
                        'defect_regist', :head, 'a', now(), :uid, :spjangcd)
                RETURNING id
                """, p);
		return ((Number) r.get("id")).intValue();
	}

	/** ★ out 은 "OutputQty". "InputQty" 에 넣으면 차감이 아니라 가산된다 */
	private int insertInoutOut(int matId, int storeId, String lotNumber, double qty,
							   int defectId, String label, String spjangcd, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("storeId", storeId);
		p.addValue("lotNo", lotNumber);
		p.addValue("qty", qty);
		p.addValue("head", defectId);
		p.addValue("desc", "부적합 " + (label == null ? "" : label));
		p.addValue("uid", user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);
		Map<String, Object> r = this.sqlRunner.getRow("""
                INSERT INTO mat_inout
                    ("Material_id","StoreHouse_id","LotNumber","InoutDate","InoutTime",
                     "InOut","OutputType","OutputQty","Description",
                     "SourceDataPk","SourceTableName","State",_status,_created,_creater_id,spjangcd)
                VALUES (:matId,:storeId,:lotNo, now()::date, now()::time,
                        'out','defect_out',:qty,:desc,
                        :head,'defect_regist','confirmed','a',now(),:uid,:spjangcd)
                RETURNING id
                """, p);
		return ((Number) r.get("id")).intValue();
	}

	private String defectTypeLabel(Integer dtId, String etc) {
		if (dtId == null) return etc;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", dtId);
		Map<String, Object> r = this.sqlRunner.getRow(
				"SELECT \"Name\" AS nm FROM defect_type WHERE id = :id", p);
		return r == null ? etc : String.valueOf(r.get("nm"));
	}

	private static double toD(Object o) {
		return o == null ? 0d : Double.parseDouble(String.valueOf(o));
	}

	private static String fmt(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
	}
}