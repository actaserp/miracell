package mes.app.production.service;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * M-CELL 포장 (2공장, mc03 / 워크센터 56).
 *
 *   검사완료창고(19) 유닛 로트 + 생산창고(17) 포장자재
 *     → 완제품 로트(MRCN-*) → 제품창고(4)
 *
 * ── 이 서비스의 축 (기준문서 §0·§8) ───────────────────────────────
 *  1대 = 1로트 = 1박스 = mat_produce 1건(GoodQty=1).
 *  1공장 작업조형(세션 1건에 N개)과 달리 유닛형이다. mat_produce_member 를 쓰지 않는다.
 *  화면은 작업조형 골격을 쓰지만(작업자·설비 1조가 여러 박스 연속 포장),
 *  작업 세션은 별도 테이블 없이 (작업일자 · Actor_id · Equipment_id) 로 파생시킨다(v3 §5.1).
 *
 * ── 포장 후보는 '작지 소속' 으로 찾는다 (★2026-08 변경) ──────────
 *  이전에는 완제품 BOM 의 InspectYN='Y' 구성품 재고를 통째로 후보로 삼았다.
 *  그러면 창고 19 에 있는 모든 합격 유닛이 모든 포장 작지 카드에 똑같이 뜬다.
 *  실제로 수리(mc04)에서 나온 유닛이 무관한 포장 작지 화면에 섞여 보였다.
 *
 *  그래서 유닛의 작지(mcell_unit.JobResponse_id)가 그 포장 작지에 속하는지로 거른다.
 *
 *      ajr.id = jr.id                     완제품 작지에서 바로 조립한 경우
 *      ajr."Parent_id" = jr.id            전개로 생긴 자식 작지
 *      ajr."WorkOrderNumber" = jr.…       전개로 생긴 형제 작지 (번호를 공유한다)
 *
 *  세 갈래를 다 받는 이유는 전개 방식이 품목마다 다르기 때문이다. 한 가지만 걸면
 *  조용히 0건이 되고, 작업자는 "검사는 합격했는데 포장 목록에 없다"를 만난다.
 *
 * ── 수리 유닛은 원 유닛의 작지를 따라간다 (★2026-08) ─────────────
 *  수리(mc04)는 자기 작지(wc 60)를 갖는데 그건 완제품 작지의 자식도 형제도 아니다.
 *  그대로 두면 검사 합격 후에도 어느 포장 작지에도 안 붙어 포장할 자리가 없어진다.
 *  실제 데이터에서 같은 로트번호에 유닛이 두 대(조립 1 + 수리 1) 있었다 —
 *  수리가 keep 모드로 원 로트번호를 유지했기 때문이다(§5.6 이 지적한 그 문제).
 *
 *  그래서 ORIG_JR_LATERAL 이 SrcLotNumber 로 원 유닛을 찾아 그 작지를 대신 쓴다.
 *  실물이 한 대이므로 포장도 원래 그 대가 속한 작지에 달리는 것이 맞다.
 *  작지를 자동 발행하지 않는 이유이기도 하다 — 붙을 자리가 이미 있다.
 *
 * ── mat_lot.id 로 잡는다 (★함정) ─────────────────────────────────
 *  같은 LotNumber 로 mat_lot 행이 둘 이상 남을 수 있다(잔량 0 인 과거 행).
 *  LotNumber 로 조인하면 유닛이 중복으로 뜨고 엉뚱한 행을 소비한다.
 *
 * ── 완제품 로트는 유닛 로트를 승계한다 ───────────────────────────
 *  품목이 WIP-* → MRCN-* 으로 바뀌므로 수리 §5.6 의 keep 모드 문제
 *  (같은 품목 두 행)가 생기지 않는다. 라벨 로트와 완제품 로트가 어긋나지 않는 쪽이 낫다.
 *
 * ── 실적 생성은 start / consumeLot / finish 로 쪼갠다 (수리 §5.6 과 동일) ──
 *  유닛 로트는 InspectYN='Y' 라 resolveSourceStore 가 19 로 정판정하지만,
 *  '어느 로트인지'는 작업자가 스캔으로 지정하므로 FIFO 에 맡길 수 없다.
 *  포장자재는 원자재라 17 FIFO 정상.
 */
@Service
public class McellPackService {

	@Autowired SqlRunner sqlRunner;
	@Autowired ProductionCreateService productionCreateService;

	public static final int STORE_PROD    = 17;   // 생산창고 — 포장자재
	public static final int STORE_INSPECT = 19;   // 검사완료창고 — 유닛 로트
	public static final int STORE_PRODUCT = 4;    // 제품창고 — 완제품 (wc 56 ProcessStoreHouse_id)
	public static final int WC_PACK       = 56;   // 포장(2공장) 워크센터
	public static final int EQU_GROUP_PACK = 37;  // 포장 설비 그룹 (equ."EquipmentGroup_id")
	public static final String DEFAULT_EQU_CODE = "EQ-MPACK";  // 2공장 포장대

	// =====================================================================
	// 공통 SQL 조각
	// =====================================================================

	/** 완제품 작지인가 = 그 산출품목의 manufacturing BOM 에 InspectYN='Y' 구성품이 있는가 */
	private static final String IS_PACK_MATERIAL =
		"""
		EXISTS (SELECT 1
		          FROM bom b2
		          JOIN bom_comp bc2 ON bc2."BOM_id" = b2.id
		          JOIN material  cm2 ON cm2.id = bc2."Material_id"
		         WHERE b2."Material_id" = %s
		           AND b2."BOMType" = 'manufacturing'
		           AND COALESCE(cm2."InspectYN",'N') = 'Y')
		""";

	/** 유닛 작지(ajr)가 포장 작지(jr)에 속하는가 */
	private static final String BELONGS_TO_JR =
		"""
		(    ajr.id = jr.id
		  OR ajr."Parent_id" = jr.id
		  OR ajr."WorkOrderNumber" = jr."WorkOrderNumber" )
		""";

	/**
	 * 유닛이 실제로 매달릴 작지.
	 *
	 * ★ 수리 유닛은 자기 작지(mc04, wc 60)를 갖는다. 그 작지는 완제품 작지의 자식도
	 *   형제도 아니라 계보가 끊긴다. 그래서 수리 유닛이면 SrcLotNumber 로 원 유닛을 찾아
	 *   그쪽 작지를 대신 쓴다. 실물은 같은 한 대이므로 포장도 같은 작지에 달려야 한다.
	 *
	 *   수리가 keep 모드로 원 로트번호를 유지하면 같은 LotNumber 로 mcell_unit 이
	 *   두 행 생긴다(조립 1 + 수리 1). 원 유닛은 McellRepair_id 가 비어 있는 쪽이다.
	 */
	private static final String ORIG_JR_LATERAL =
		"""
		LEFT JOIN LATERAL (
		      SELECT j.* FROM job_res j
		       WHERE j.id = COALESCE(
		             (SELECT o."JobResponse_id" FROM mcell_unit o
		               WHERE o."LotNumber" = COALESCE(NULLIF(mu."SrcLotNumber",''), mu."LotNumber")
		                 AND o.id <> mu.id
		                 AND o."McellRepair_id" IS NULL
		                 AND COALESCE(o."_status",'a') = 'a'
		               ORDER BY o.id LIMIT 1),
		             mu."JobResponse_id")
		) ajr ON true
		""";

	/** 같은 로트번호에 유닛이 여럿일 수 있다(재작업·분해). pass 1건만 고른다. */
	private static final String PASS_UNIT_LATERAL =
		"""
		JOIN LATERAL (
		      SELECT x.* FROM mcell_unit x
		       WHERE x."LotNumber"   = ml."LotNumber"
		         AND x."Material_id" = ml."Material_id"
		         AND COALESCE(x."_status",'a') = 'a'
		         AND x."State" = 'pass'
		       ORDER BY x.id DESC LIMIT 1
		) mu ON true
		""";

	/* 포장자재의 소스창고는 항상 생산창고(17)다 — '불출된 것' 을 쓴다(§8).
	 *
	 * ★ 여기에 resolveSourceStore 의 CASE 를 옮겨 적지 말 것.
	 *   그 CASE 의 MaterialType 은 material 이 아니라 mat_grp 컬럼이라
	 *   (`mg."MaterialType"`, 2공장 §1 패치4) 별칭을 바꿔 붙이면 「칼럼 없음」으로 터진다.
	 *   packUnit 이 req.cleanStore = STORE_PROD 로 소비 창고를 고정하므로
	 *   여기서 다시 판정할 이유도 없다. 같은 CASE 를 네 번째로 복사하는 것이 더 나쁘다.
	 *   (부적합 §10-1 : 지금도 ProductionCreateService / DefectService 두 곳에 복사돼 있다) */

	// =====================================================================
	// 조회
	// =====================================================================

	/** 공정 컨텍스트 — mc03 → 워크센터 56 / 산출창고 4 / 기본 설비 */
	public Map<String, Object> getContext(String processCode, Integer factoryId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("processCode", processCode)
																.addValue("factoryId", factoryId);
		Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT p.id AS process_id, p."Code" AS process_code, p."Name" AS process_name,
                       wc.id AS workcenter_id, wc."Name" AS workcenter_name,
                       wc."ProcessStoreHouse_id" AS out_store_id, wc."Factory_id" AS factory_id
                  FROM process p
                  LEFT JOIN work_center wc ON wc."Process_id" = p.id
                        AND (CAST(:factoryId AS INTEGER) IS NULL
                             OR wc."Factory_id" = CAST(:factoryId AS INTEGER))
                 WHERE p."Code" = :processCode
                 ORDER BY wc.id
                 LIMIT 1
                """, p);
		if (row != null) {
			row.put("store_prod", STORE_PROD);
			row.put("store_inspect", STORE_INSPECT);
			row.put("store_product", STORE_PRODUCT);
			// 워크센터 56 에 설비가 둘(포장대·외포장기) 붙어 있어 기본값을 코드로 집는다.
			// id 를 박으면 서버 이전·재등록 때 조용히 엉뚱한 설비가 잡힌다.
			Map<String, Object> equ = this.sqlRunner.getRow(
				"SELECT id FROM equ WHERE \"Code\" = :code AND COALESCE(\"_status\",'a')='a' LIMIT 1",
				new MapSqlParameterSource().addValue("code", DEFAULT_EQU_CODE));
			row.put("default_equipment_id", equ == null ? null : asInt(equ.get("id")));
		}
		return row;
	}

	/**
	 * A화면 — 포장 작업지시 큐.
	 *
	 * ★ processId 는 받아두되 필터에 쓰지 않는다. 워크센터 세팅이 라우팅에 좌우되기 때문.
	 *   판별은 BOM 구조로 한다.
	 */
	public List<Map<String, Object>> getWoQueue(Integer processId, String spjangcd,
																							String dateFrom, String dateTo) {
		// ★ SqlRunner.getRows 는 오류 시 null 을 반환한다(빈 리스트가 아니다).
		return safe(getOrderCards(spjangcd, dateFrom, dateTo));
	}

	private static List<Map<String, Object>> safe(List<Map<String, Object>> rows) {
		return (rows == null) ? Collections.emptyList() : rows;
	}

	/** 포장 작지 카드 */
	private List<Map<String, Object>> getOrderCards(String spjangcd, String dateFrom, String dateTo) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("spjangcd", spjangcd)
																.addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom))
																.addValue("dateTo",   (dateTo   == null || dateTo.isBlank())   ? null : LocalDate.parse(dateTo));
		return this.sqlRunner.getRows("""
                SELECT jr.id                                      AS job_res_id
                     , jr."WorkOrderNumber"                       AS order_num
                     , jr."Material_id"                           AS mat_id
                     , jr."Material_id"                           AS pack_mat_id
                     , m."Code"                                   AS mat_code
                     , m."Name"                                   AS mat_name
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS plan_date
                     , COALESCE(jr."OrderQty", 0)                 AS plan_qty
                     , COALESCE(d.done_qty, 0)                    AS done_qty
                     , COALESCE(rd.ready_qty, 0)                  AS ready_qty
                     , CASE WHEN COALESCE(d.done_qty,0) >= COALESCE(jr."OrderQty",0)
                                 AND COALESCE(jr."OrderQty",0) > 0  THEN 'done'
                            WHEN COALESCE(d.done_qty,0) > 0         THEN 'working'
                            ELSE 'wait' END                       AS state
                  FROM job_res jr
                  JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS done_qty
                          FROM mat_produce mp
                         WHERE mp."JobResponse_id" = jr.id
                           AND COALESCE(mp."_status",'a') = 'a'
                           AND mp."State" = 'finished'
                  ) d ON true
                  -- ★ 포장 대기 수는 B화면 목록과 같은 조건이어야 한다.
                  --   여기만 BOM 기준으로 세면 카드엔 5, 목록엔 1 이 되어 작업자가 헤맨다.
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS ready_qty
                          FROM mat_lot ml
                          JOIN material um ON um.id = ml."Material_id"
                                          AND COALESCE(um."InspectYN",'N') = 'Y'
                          %s
                          %s
                         WHERE ml."StoreHouse_id" = 19
                           AND COALESCE(ml."CurrentStock",0) > 0
                           AND ajr.id IS NOT NULL
                           AND %s
                           AND ml."Material_id" IN (
                                 SELECT bc."Material_id"
                                   FROM bom b
                                   JOIN bom_comp bc ON bc."BOM_id" = b.id
                                  WHERE b."Material_id" = jr."Material_id"
                                    AND b."BOMType" = 'manufacturing')
                  ) rd ON true
                 WHERE jr.spjangcd = :spjangcd
                   AND COALESCE(jr."_status",'a') = 'a'
                   AND jr."State" IN ('ordered','working','finished')
                   -- 워크센터로 거르지 않는다 (검사 wo_queue 와 같은 방식).
                   -- 라우팅이 완제품 공정까지 조립 워크센터로 깔아버리는 경우가 있어
                   -- wc 로 거르면 포장 작지가 통째로 사라진다.
                   AND %s
                   -- 같은 품목의 부모(수주 루트)와 말단 작지가 둘 다 뜨는 것을 막는다.
                   AND NOT EXISTS (SELECT 1 FROM job_res c
                                    WHERE c."Parent_id" = jr.id
                                      AND COALESCE(c."_status",'a') = 'a')
                   AND (CAST(:dateFrom AS date) IS NULL OR jr."ProductionDate"::date >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR jr."ProductionDate"::date <= CAST(:dateTo   AS date))
                 ORDER BY jr."ProductionDate" DESC, jr."WorkOrderNumber" DESC
                 LIMIT 200
                """.formatted(PASS_UNIT_LATERAL, ORIG_JR_LATERAL, BELONGS_TO_JR,
			String.format(IS_PACK_MATERIAL, "jr.\"Material_id\"")), p);
	}

	/** 이 작지(완제품)가 포장 대상으로 삼는 유닛 품목 id 목록 */
	public List<Map<String, Object>> getUnitMaterials(Integer jobResId, Integer packMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("matId", resolvePackMatId(jobResId, packMatId));
		return this.sqlRunner.getRows("""
                SELECT bc."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                  FROM bom b
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                  JOIN material m  ON m.id = bc."Material_id"
                 WHERE b."Material_id" = :matId
                   AND b."BOMType" = 'manufacturing'
                   AND COALESCE(m."InspectYN",'N') = 'Y'
                 ORDER BY m."Code"
                """, p);
	}

	/**
	 * B화면 — 포장 대기 유닛.
	 *
	 * jobResId 가 있으면 그 작지에 속한 유닛만,
	 * 없으면(「작지 없음」 카드) 어느 포장 작지에도 안 붙는 유닛만 내린다.
	 * 두 경로가 서로 배타적이라 한 로트가 두 카드에 동시에 뜨지 않는다.
	 *
	 * ★ mat_lot.id 를 함께 내린다. 포장 시 이 id 로 consumeLot 한다.
	 *   같은 LotNumber 로 잔량 0 인 과거 행이 남아 있을 수 있어 LotNumber 로 잡으면 안 된다.
	 */
	public List<Map<String, Object>> getReadyUnits(Integer jobResId, Integer packMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("jrId", jobResId)
																.addValue("matId", resolvePackMatId(jobResId, packMatId));

		return this.sqlRunner.getRows(("""
                SELECT ml.id                                       AS mat_lot_id
                     , ml."LotNumber"                              AS lot_number
                     , ml."MakerLotNo"                             AS maker_lot_no
                     , ml."Material_id"                            AS unit_mat_id
                     , um."Code"                                   AS unit_mat_code
                     , um."Name"                                   AS unit_mat_name
                     , COALESCE(ml."CurrentStock",0)               AS stock
                     , mu.id                                       AS unit_id
                     , mu."UnitNo"                                 AS unit_no
                     , mu."State"                                  AS unit_state
                     , mu."JobResponse_id"                         AS assy_job_res_id
                     , ajr."WorkOrderNumber"                       AS assy_order_num
                     -- 수리(mc04)에서 온 유닛이면 배지. 재포장이라는 뜻이다(§8)
                     , mu."McellRepair_id"                         AS repair_id
                     , rp."Cat"                                    AS repair_cat
                     , rp."RepairNo"                               AS repair_no
                     , mu."SrcLotNumber"                           AS src_lot
                     , to_char(ml."InputDateTime",'yyyy-mm-dd hh24:mi') AS input_time
                  FROM mat_lot ml
                  JOIN material um ON um.id = ml."Material_id"
                                  AND COALESCE(um."InspectYN",'N') = 'Y'
                  %s
                  %s
                  LEFT JOIN mcell_repair rp  ON rp.id  = mu."McellRepair_id"
                  JOIN job_res jr ON jr.id = :jrId
                 WHERE ml."StoreHouse_id" = 19
                   AND COALESCE(ml."CurrentStock",0) > 0
                   -- 이 카드의 완제품 BOM 에 든 유닛 품목만 (국내/해외가 섞이지 않게)
                   AND ml."Material_id" IN (
                         SELECT bc."Material_id" FROM bom b
                          JOIN bom_comp bc ON bc."BOM_id" = b.id
                         WHERE b."Material_id" = :matId AND b."BOMType" = 'manufacturing')
                   AND ajr.id IS NOT NULL
                   AND %s
                 ORDER BY ml."InputDateTime", ml.id
                """).formatted(PASS_UNIT_LATERAL, ORIG_JR_LATERAL, BELONGS_TO_JR), p);
	}

	/** B화면 — 이 카드에서 이미 포장한 것 (완제품 로트) */
	public List<Map<String, Object>> getPackedList(Integer jobResId, Integer packMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jobResId);
		return this.sqlRunner.getRows("""
                SELECT mp.id                                       AS mp_id
                     , mp."JobResponse_id"                         AS job_res_id
                     , mp."LotIndex"                               AS chasu
                     , mp."LotNumber"                              AS lot_number
                     , mp."GoodQty"                                AS good_qty
                     , mp."Actor_id"                               AS actor_id
                     , pr."Name"                                   AS actor_name
                     , mp."Equipment_id"                           AS equipment_id
                     , eq."Name"                                   AS equipment_name
                     , to_char(mp."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",  'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(mp."StartTime",'yyyy-mm-dd')         AS work_date
                     , ml."MakerLotNo"                             AS maker_lot_no
                     , mu.id                                       AS unit_id
                     , mu."UnitNo"                                 AS unit_no
                     , mu."State"                                  AS unit_state
                  FROM mat_produce mp
                  LEFT JOIN person pr ON pr.id = mp."Actor_id"
                  LEFT JOIN equ    eq ON eq.id = mp."Equipment_id"
                  LEFT JOIN mat_lot ml ON ml."SourceTableName" = 'mat_produce'
                                      AND ml."SourceDataPk"    = mp.id
                  -- 완제품 로트는 유닛 로트를 승계하므로 같은 번호로 유닛을 되짚을 수 있다
                  LEFT JOIN LATERAL (
                        SELECT x.id, x."UnitNo", x."State" FROM mcell_unit x
                         WHERE x."LotNumber" = mp."LotNumber"
                           AND COALESCE(x."_status",'a') = 'a'
                         ORDER BY (CASE WHEN x."State" = 'packed' THEN 0 ELSE 1 END), x.id DESC
                         LIMIT 1
                  ) mu ON true
                 WHERE mp."JobResponse_id" = :jrId
                   AND COALESCE(mp."_status",'a') = 'a'
                 ORDER BY mp."StartTime" DESC, mp."LotIndex" DESC
                """, p);
	}

	/**
	 * 포장 자재 — 완제품 BOM 에서 유닛품목(InspectYN='Y')을 뺀 나머지.
	 *
	 * per       : 1대당 소요량
	 * store_id  : resolveSourceStore 와 같은 CASE 로 판정한 소스창고
	 * stock     : 그 창고 실재고
	 *
	 * ★ 포장자재는 '불출된 것' = 생산창고(17) 에서만 빠져야 한다(§8).
	 *   판정 결과를 그대로 내려 화면이 17 이 아닌 자재를 빨갛게 띄우고 완료를 막는다.
	 *   17 이 아니게 나온다면 그 품목의 Factory_id / MaterialType 세팅이 틀린 것이다.
	 *
	 * 재고 0 도 목록에 띄운다(부적합 §4 와 같은 이유 — 사라지면 작업자가 헤맨다).
	 */
	public List<Map<String, Object>> getPackMaterials(Integer jobResId, Integer packMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("matId", resolvePackMatId(jobResId, packMatId))
																.addValue("store", STORE_PROD);
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT bc."Material_id"                            AS mat_id
                     , cm."Code"                                   AS mat_code
                     , cm."Name"                                   AS mat_name
                     , un."Name"                                   AS unit
                     , bc."Amount" / NULLIF(b."OutputAmount",0)     AS per
                     , CEIL(bc."Amount" / NULLIF(b."OutputAmount",0)) AS default_qty
                     , CAST(:store AS INTEGER)                     AS store_id
                     , sh."Name"                                   AS store_name
                     , COALESCE(stk.stock, 0)                      AS stock
                     , 'N'                                         AS fallback
                  FROM bom b
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                  JOIN material cm ON cm.id = bc."Material_id"
                  LEFT JOIN unit un ON un.id = cm."Unit_id"
                  LEFT JOIN store_house sh ON sh.id = CAST(:store AS INTEGER)
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock
                          FROM mat_lot ml
                         WHERE ml."Material_id" = bc."Material_id"
                           AND ml."StoreHouse_id" = CAST(:store AS INTEGER)
                  ) stk ON true
                 WHERE b."Material_id" = :matId
                   AND b."BOMType" = 'manufacturing'
                   -- 유닛 본체는 자재 목록에서 뺀다. 안 빼면 포장 대상이 자재로 한 번 더 뜨고
                   -- FIFO 로 다른 유닛이 소비된다(수리 getStockList §5.3 과 같은 이유)
                   AND COALESCE(cm."InspectYN",'N') <> 'Y'
                 ORDER BY cm."Code"
                """, p);

		// ★ BOM 에 포장자재가 없으면 「전체 자재」로 폴백한다 (부적합 §4 와 같은 원칙).
		//   빈 화면을 보여주면 작업자는 포장 자체를 못 한다고 생각한다.
		//   BOM 정비는 나중 일이고, 지금 담아야 할 박스는 지금 담아야 한다.
		//   기본수량은 0 — BOM 근거가 없으므로 작업자가 직접 세어 넣는다.
		if (rows == null || rows.isEmpty()) return getFallbackMaterials();
		return rows;
	}

	/** 전체 자재 폴백 — 생산창고(17)에 실재고가 있는 2공장 자재 */
	private List<Map<String, Object>> getFallbackMaterials() {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("store", STORE_PROD)
																.addValue("factoryId", 2);
		return this.sqlRunner.getRows("""
                SELECT m.id                                        AS mat_id
                     , m."Code"                                    AS mat_code
                     , m."Name"                                    AS mat_name
                     , un."Name"                                   AS unit
                     , 0                                           AS per
                     , 0                                           AS default_qty
                     , CAST(:store AS INTEGER)                     AS store_id
                     , sh."Name"                                   AS store_name
                     , COALESCE(stk.stock, 0)                      AS stock
                     , 'Y'                                         AS fallback
                  FROM material m
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN store_house sh ON sh.id = CAST(:store AS INTEGER)
                  JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock
                          FROM mat_lot ml
                         WHERE ml."Material_id" = m.id
                           AND ml."StoreHouse_id" = CAST(:store AS INTEGER)
                  ) stk ON COALESCE(stk.stock,0) > 0
                 WHERE COALESCE(m."_status",'a') = 'a'
                   AND COALESCE(m."Factory_id",1) = CAST(:factoryId AS INTEGER)
                   AND COALESCE(m."InspectYN",'N') <> 'Y'
                 ORDER BY m."Code"
                """, p);
	}

	/**
	 * 박스 라벨 중복 검사 — 화면이 「이 라벨로 확정」 전에 부른다.
	 * 라벨은 필수이고 한 번 쓰면 다시 못 쓴다. 화면 목록만으로는 다른 작지의 라벨을 못 막는다.
	 */
	public Map<String, Object> checkLabel(String key) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (key == null || key.isBlank()) {
			out.put("dup", false);
			return out;
		}
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("key", key.trim());
		Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT ml."LotNumber" AS lot_number, ml."StoreHouse_id" AS store_id,
                       m."Code" AS mat_code
                  FROM mat_lot ml
                  JOIN material m ON m.id = ml."Material_id"
                 WHERE ml."MakerLotNo" = :key
                   AND COALESCE(m."InspectYN",'N') <> 'Y'   -- 유닛 로트에 붙은 라벨은 중복이 아니다
                 ORDER BY ml.id DESC LIMIT 1
                """, p);
		out.put("dup", row != null);
		if (row != null) {
			out.put("note", "이미 사용된 라벨입니다 — " + row.get("lot_number") + " (" + row.get("mat_code") + ")");
			out.putAll(row);
		}
		return out;
	}

	/**
	 * 박스 라벨 스캔 조회 (유닛 로트 찾기).
	 * 화면 흐름이 「유닛 선택 → 라벨 스캔」으로 바뀌어 진입용으로는 더 쓰지 않지만,
	 * 로트를 직접 찍어 찾고 싶을 때를 위해 남겨 둔다.
	 */
	public Map<String, Object> searchUnitLot(Integer jobResId, Integer packMatId, String key) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (key == null || key.isBlank()) {
			out.put("found", false);
			out.put("note", "스캔 값이 비어 있습니다.");
			return out;
		}
		String k = key.trim();
		Integer matId = resolvePackMatId(jobResId, packMatId);

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("key", k);
		Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT ml.id AS mat_lot_id, ml."LotNumber" AS lot_number,
                       ml."MakerLotNo" AS maker_lot_no, ml."Material_id" AS unit_mat_id,
                       ml."StoreHouse_id" AS store_id, COALESCE(ml."CurrentStock",0) AS stock,
                       mu.id AS unit_id, mu."UnitNo" AS unit_no, mu."State" AS unit_state,
                       um."Code" AS unit_mat_code, um."Name" AS unit_mat_name
                  FROM mat_lot ml
                  JOIN material um ON um.id = ml."Material_id"
                  LEFT JOIN LATERAL (
                        SELECT x.* FROM mcell_unit x
                         WHERE x."LotNumber" = ml."LotNumber"
                           AND x."Material_id" = ml."Material_id"
                           AND COALESCE(x."_status",'a') = 'a'
                         ORDER BY (CASE WHEN x."State" = 'pass' THEN 0 ELSE 1 END), x.id DESC
                         LIMIT 1
                  ) mu ON true
                 WHERE (ml."LotNumber" = :key OR ml."MakerLotNo" = :key)
                   AND COALESCE(um."InspectYN",'N') = 'Y'
                 ORDER BY (CASE WHEN COALESCE(ml."CurrentStock",0) > 0 THEN 0 ELSE 1 END),
                          ml."InputDateTime" DESC, ml.id DESC
                 LIMIT 1
                """, p);

		if (row == null) {
			out.put("found", false);
			out.put("note", "등록되지 않은 라벨입니다. (" + k + ")");
			return out;
		}

		MapSqlParameterSource bp = new MapSqlParameterSource()
																 .addValue("matId", matId).addValue("unitMatId", asInt(row.get("unit_mat_id")));
		Map<String, Object> belong = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c
                  FROM bom b JOIN bom_comp bc ON bc."BOM_id" = b.id
                 WHERE b."Material_id" = :matId AND b."BOMType"='manufacturing'
                   AND bc."Material_id" = :unitMatId
                """, bp);
		boolean inBom = belong != null && asInt(belong.get("c")) != null && asInt(belong.get("c")) > 0;

		String note = null;
		boolean ok = true;
		if (!inBom) {
			ok = false;
			note = "이 완제품의 구성품이 아닙니다. (" + row.get("unit_mat_code") + ")";
		} else if (!"pass".equals(row.get("unit_state"))) {
			ok = false;
			note = "검사 합격 상태가 아닙니다. (현재 " + row.get("unit_state") + ")";
		} else if (asInt(row.get("store_id")) == null || asInt(row.get("store_id")) != STORE_INSPECT) {
			ok = false;
			note = "검사완료창고에 없습니다. (현재 창고 " + row.get("store_id") + ")";
		} else if (toD(row.get("stock")) <= 0) {
			ok = false;
			note = "이미 포장되었거나 재고가 없는 로트입니다.";
		}

		out.putAll(row);
		out.put("found", true);
		out.put("packable", ok);
		out.put("note", note);
		return out;
	}

	/** 작업자 목록 */
	public List<Map<String, Object>> getWorkers() {
		return this.sqlRunner.getRows("""
                SELECT p.id AS actor_id, p."Name" AS actor_name
                  FROM person p
                 WHERE COALESCE(p."_status",'a') = 'a'
                 ORDER BY p."Name"
                """, new MapSqlParameterSource());
	}

	/**
	 * 포장 설비 목록 — 워크센터 + 설비그룹 기준 (조립 mc01 과 같은 방식).
	 * workCenterId 가 null 이면 전체(디버그용).
	 *
	 * ★ EquipmentGroup_id 를 함께 거는 이유는 워크센터 56 에 포장과 무관한 설비가
	 *   섞여 있기 때문이다. 그룹이 재편되면 목록이 조용히 비니 그때 이 상수를 고친다.
	 */
	public List<Map<String, Object>> getEquipments(Integer workCenterId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("wcId", workCenterId)
																.addValue("grpId", EQU_GROUP_PACK);
		return this.sqlRunner.getRows("""
                SELECT e.id AS equipment_id, e."Code" AS equipment_code, e."Name" AS equipment_name,
                       e."WorkCenter_id" AS workcenter_id
                  FROM equ e
                 WHERE COALESCE(e."_status",'a') = 'a'
                   AND (CAST(:wcId AS INTEGER) IS NULL
                        OR e."WorkCenter_id" = CAST(:wcId AS INTEGER))
                   AND e."EquipmentGroup_id" = CAST(:grpId AS INTEGER)
                 ORDER BY e."Code"
                """, p);
	}

	/**
	 * 2공장 박스 라벨의 pack_label."LabelKind".
	 *
	 * ★ 1공장은 ckpk / inbox / carton 세 종류를 쓰지만 2공장은 박스 하나에 라벨 하나다.
	 *   같은 테이블에 담되 종류를 갈라 둬야 조회에서 섞이지 않는다.
	 * ⚠ varchar(10) 에 딱 10자다. 더 긴 이름으로 바꾸면 그대로 저장 오류가 난다.
	 * ⚠ pack_label_kind_chk CHECK 제약에 이 값을 «먼저» 추가해야 한다
	 *   (patch_pack_label_mcell.sql).
	 */
	private static final String LABEL_KIND_MCELL = "m-cell_box";

	// =====================================================================
	// 쓰기 — 포장
	// =====================================================================

	/**
	 * 박스 1개 포장 = 유닛 1대.
	 *
	 *   (작지 없으면) 작지 자동 발행
	 *   createPackProduce  mat_produce 생성 (재고 안 움직임) ★전용 — startProduction 미사용
	 *   consumeLot         검사완료창고의 유닛 로트 1대 소비   ← 패치5, 지정 로트
	 *   finishProduction   포장자재 FIFO 소비 + 완제품 로트 제품창고(4) 입고
	 *   mcell_unit         pass → packed
	 *
	 * @param jobResId  포장 작지. null 이면 packMatId 로 자동 발행한다
	 * @param matLotId  포장할 유닛의 mat_lot.id
	 * @param makerLotNo 박스 실물 라벨. ★필수 — 비면 여기서 막는다
	 * @param bomList   포장자재. null 이면 BOM 기본값을 그대로 쓴다
	 */
	@Transactional
	public AjaxResult packUnit(Integer jobResId, Integer packMatId, Integer matLotId, String makerLotNo,
														 Integer actorId, Integer equipmentId,
														 String startTime, String endTime,
														 List<ProductionCreateService.BomInput> bomList,
														 String labelRaw, String spjangcd, User user) {
		AjaxResult r = new AjaxResult();
		r.success = true;

		if (matLotId == null) {
			r.success = false; r.message = "포장 대상 로트가 필요합니다."; return r;
		}
		if (jobResId == null) {
			r.success = false; r.message = "포장 작업지시가 필요합니다."; return r;
		}
		// 라벨은 필수. 화면에서도 막지만 서버가 최종 관문이다.
		if (makerLotNo == null || makerLotNo.isBlank()) {
			r.success = false; r.message = "박스 라벨을 스캔해야 포장을 완료할 수 있습니다."; return r;
		}
		String label = makerLotNo.trim();

		Map<String, Object> dup = checkLabel(label);
		if (Boolean.TRUE.equals(dup.get("dup"))) {
			r.success = false; r.message = str(dup.get("note")); return r;
		}

		// ── 1. 대상 로트·유닛 확인 ──
		MapSqlParameterSource lp = new MapSqlParameterSource().addValue("id", matLotId);
		Map<String, Object> lot = this.sqlRunner.getRow("""
                SELECT ml.id, ml."LotNumber" AS lot_number, ml."Material_id" AS unit_mat_id,
                       ml."StoreHouse_id" AS store_id, COALESCE(ml."CurrentStock",0) AS stock,
                       mu.id AS unit_id, mu."State" AS unit_state
                  FROM mat_lot ml
                  -- ★ pass 유닛을 우선해 1건만. 같은 로트번호에 reject 유닛이 함께 있으면
                  --    그쪽을 집어 "합격 유닛만 포장 가능"으로 잘못 막힌다.
                  LEFT JOIN LATERAL (
                        SELECT x.* FROM mcell_unit x
                         WHERE x."LotNumber" = ml."LotNumber"
                           AND x."Material_id" = ml."Material_id"
                           AND COALESCE(x."_status",'a') = 'a'
                         ORDER BY (CASE WHEN x."State" = 'pass' THEN 0 ELSE 1 END), x.id DESC
                         LIMIT 1
                  ) mu ON true
                 WHERE ml.id = :id
                """, lp);
		if (lot == null) { r.success = false; r.message = "포장 대상 로트를 찾을 수 없습니다."; return r; }
		if (asInt(lot.get("store_id")) == null || asInt(lot.get("store_id")) != STORE_INSPECT) {
			r.success = false;
			r.message = "검사완료창고(19)의 로트만 포장할 수 있습니다. (현재 창고 " + lot.get("store_id") + ")";
			return r;
		}
		if (toD(lot.get("stock")) < 1) {
			r.success = false; r.message = "이미 포장되었거나 재고가 없는 로트입니다."; return r;
		}
		Integer unitId = asInt(lot.get("unit_id"));
		if (unitId == null) {
			r.success = false;
			r.message = "이 로트에 대응하는 유닛 이력이 없습니다. 검사 공정을 거친 로트만 포장할 수 있습니다.";
			return r;
		}
		if (!"pass".equals(lot.get("unit_state"))) {
			r.success = false;
			r.message = "검사 합격 유닛만 포장할 수 있습니다. (현재 " + lot.get("unit_state") + ")";
			return r;
		}

		Integer jrId = jobResId;

		// ── 2. 포장자재 결정 (미지정이면 BOM 기본값) ──
		List<ProductionCreateService.BomInput> inputs = new ArrayList<>();
		if (bomList != null && !bomList.isEmpty()) {
			inputs.addAll(bomList);
		} else {
			for (Map<String, Object> m : getPackMaterials(jrId, packMatId)) {
				float q = (float) toD(m.get("default_qty"));
				if (q > 0) inputs.add(new ProductionCreateService.BomInput(asInt(m.get("mat_id")), q));
			}
		}
		// ★ 유닛 품목이 섞여 들어오면 제거한다. 그건 consumeLot 이 지정 로트로 처리한다.
		//   여기 남으면 FIFO 로 한 번 더 소비되어 다른 유닛이 사라진다.
		Integer unitMatId = asInt(lot.get("unit_mat_id"));
		inputs.removeIf(b -> b.matId == null || b.matId.equals(unitMatId) || b.qty <= 0);

		// ── 3. 실적 생성 ──
		//   완제품 로트 = 유닛 로트 승계. 품목이 달라(WIP-*→MRCN-*) 재고 목록에서 겹치지 않는다.
		String lotNumber = str(lot.get("lot_number"));

		ProductionCreateService.CreateReq req = new ProductionCreateService.CreateReq();
		req.jobResId     = jrId;
		req.workCenterId = WC_PACK;
		req.equipmentId  = equipmentId;
		req.actorId      = actorId;
		req.memberIds    = null;              // 2공장은 1인 작업
		req.goodQty      = 1f;                // 1대 = 1박스 = 1로트
		req.defectQty    = 0f;
		req.startTime    = startTime;
		req.endTime      = endTime;
		req.bomList      = inputs;
		req.cleanStore   = STORE_PROD;        // 포장자재 소스 = 생산창고(불출된 것)
		req.lotNumber    = lotNumber;
		req.spjangcd     = spjangcd;

		// ★ startProduction 을 쓰지 않는다 (§포장 차수는 직접 만든다)
		Integer mpId = createPackProduce(jrId, actorId, equipmentId, lotNumber,
			startTime, spjangcd, user);

		// 유닛 로트 1대 지정 소비 (FIFO 아님)
		AjaxResult cons = this.productionCreateService.consumeLot(mpId, matLotId, 1f, user, spjangcd);
		if (!cons.success) throw new IllegalStateException(cons.message);   // 롤백

		AjaxResult fin = this.productionCreateService.finishProduction(mpId, req, user);
		if (!fin.success) throw new IllegalStateException(fin.message);     // 롤백

		// ── 4. 외부 라벨(MakerLotNo) 기록 ──
		//   완제품 로트 행에 남긴다. 유닛 로트 쪽에도 비어 있으면 함께 채운다.
		MapSqlParameterSource mk = new MapSqlParameterSource()
																 .addValue("mk", label).addValue("mpId", mpId).addValue("srcId", matLotId);
		this.sqlRunner.execute("""
                UPDATE mat_lot SET "MakerLotNo"=:mk
                 WHERE "SourceTableName"='mat_produce' AND "SourceDataPk"=:mpId
                """, mk);
		this.sqlRunner.execute("""
                UPDATE mat_lot SET "MakerLotNo"=:mk
                 WHERE id=:srcId AND ("MakerLotNo" IS NULL OR "MakerLotNo"='')
                """, mk);

		/* ★ 완제품 로트에 출처를 남긴다.
		     finishProduction 이 붙이는 기본 문구는 「2차수생산」처럼 공정과 무관해서,
		     mat_lot 을 훑을 때 조립·검사 산출물과 구분되지 않는다.
		     2공장은 완제품 로트번호까지 유닛에서 승계하므로(같은 LotNumber 두 행)
		     이 문구가 없으면 어느 쪽이 포장분인지 판별할 근거가 창고밖에 없다.
		   ★ 1공장(「포장 완제품 입고」·「CK 생산 입고(1공장)」)과 같은 규칙이다.
		   ★ 박스 라벨을 함께 적는다 — 실물과 대조할 때 이 한 줄이면 끝난다. */
		MapSqlParameterSource ds = new MapSqlParameterSource()
																 .addValue("mpId", mpId)
																 .addValue("memo", "포장 완제품 입고(2공장) · 유닛 " + lotNumber
																										 + ((label == null || label.isBlank()) ? "" : " · 박스라벨 " + label));
		this.sqlRunner.execute("""
                UPDATE mat_lot SET "Description" = :memo
                 WHERE "SourceTableName"='mat_produce' AND "SourceDataPk"=:mpId
                """, ds);

		/* ── 4-2. pack_label 기록 ──
		 *
		 *   MakerLotNo 만으로는 «언제 무엇을 찍었는지» 가 남지 않는다. 값 하나가
		 *   덮어써지면 끝이고, 스캔 원문·GTIN 같은 부속 정보도 담을 자리가 없다.
		 *   1공장이 pack_label 에 남기는 것과 같은 이유다.
		 *
		 *   ★ 차수당 1행. ux_pack_label(MatProduce_id, LabelKind) 유니크가 그것을 강제한다.
		 *     2공장은 「1대 = 1박스 = 1차수」라 이 제약과 정확히 맞는다.
		 *     그래도 UPSERT 로 둔다 — 재시도·중복 호출에 INSERT 가 터지면
		 *     그 위의 소비·입고까지 통째로 롤백되기 때문이다.
		 *   ★ Qty 는 1 고정(박스 1개). LotNo 에 박스 라벨이 들어간다.
		 *   ★ RawData 는 스캔 «원문». 화면이 GS1 에서 (10)만 뽑아 보내는 경우
		 *     label 과 값이 달라진다 — 오인식을 되짚을 근거라 따로 남긴다.
		 */
		MapSqlParameterSource pl = new MapSqlParameterSource()
																 .addValue("mpId", mpId)
																 .addValue("kind", LABEL_KIND_MCELL)
																 .addValue("lot", label)
																 .addValue("raw", (labelRaw == null || labelRaw.isBlank()) ? label : labelRaw.trim())
																 .addValue("spjangcd", spjangcd)
																 .addValue("userId", user == null ? null : user.getId());
		this.sqlRunner.execute("""
                INSERT INTO pack_label
                       ("MatProduce_id","LabelKind","LotNo","Qty","RawData",
                        _status,_created,_creater_id,spjangcd)
                VALUES (:mpId, :kind, :lot, 1, :raw,
                        'a', now(), CAST(:userId AS integer), CAST(:spjangcd AS varchar))
                ON CONFLICT ("MatProduce_id","LabelKind") DO UPDATE
                   SET "LotNo"   = EXCLUDED."LotNo"
                     , "Qty"     = EXCLUDED."Qty"
                     , "RawData" = EXCLUDED."RawData"
                """, pl);

		// ── 5. 유닛 packed ──
		MapSqlParameterSource up = new MapSqlParameterSource()
																 .addValue("unitId", unitId).addValue("userId", user.getId());
		this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"='packed', "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:unitId
                """, up);

		// 조립 작지 쪽 롤업도 다시 돌린다 — 완료 기준이 packed 라 여기서 비로소 닫힌다.
		Integer assyJrId = getUnitJobRes(unitId);
		if (assyJrId != null && !assyJrId.equals(jrId)) {
			this.productionCreateService.recalcJobRes(assyJrId, user);
		}

		r.data = Map.of("mat_produce_id", mpId, "lot_number", lotNumber,
			"unit_id", unitId, "job_res_id", jrId);
		r.message = "포장 완료 · 제품창고 입고";
		return r;
	}

	/**
	 * 포장 차수 1건 생성 — `ProductionCreateService.startProduction` 을 쓰지 않는다.
	 *
	 * ★ 왜 떼어냈나 (2026-08)
	 *   1) 산출창고가 틀렸다. startProduction 은 `req.workCenterId` 를 무시하고
	 *      **작지의 워크센터**를 쓴다. 포장 작지가 조립 워크센터(52)로 발행돼 있어
	 *      산출창고도 그쪽(생산 17)을 따라갔고, 완제품이 제품창고(4)가 아니라
	 *      생산창고에 쌓였다. 뒤에서 UPDATE 로 되돌리고 있었지만 그건
	 *      '남의 로직을 부르고 결과를 덮어쓰는' 모양이라 언제든 다시 어긋난다.
	 *   2) 차수 번호가 계속 늘었다. LotIndex 를 `findByJobResponseId().size() + 1` 로
	 *      매기는데 `_status='d'`(취소분)까지 세기 때문이다. 취소·재포장을 반복하면
	 *      1대만 포장해도 「5차수」가 된다.
	 *   3) 나머지 기능이 포장에 해당사항이 없다. 작지 자동생성(포장은 작지 필수),
	 *      조원 저장(2공장은 1인 작업), 산출창고 판정(포장은 항상 4).
	 *
	 * ★ 소비·입고는 그대로 공용 로직을 쓴다.
	 *   consumeLot / finishProduction 은 `mat_lot_cons`·`mat_consu`·`mat_inout`
	 *   3종 세트를 한 곳에서만 쓰게 해 주는 자리다(2공장 §6). 여기까지 복제하면
	 *   InOut 과 수량 컬럼 짝을 틀릴 자리가 하나 더 늘어난다.
	 *
	 * @return 생성된 mat_produce.id
	 */
	private Integer createPackProduce(Integer jrId, Integer actorId, Integer equipmentId,
																		String lotNumber, String startTime,
																		String spjangcd, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("jrId", jrId)
																.addValue("wc", WC_PACK)
																.addValue("store", STORE_PRODUCT)
																.addValue("lot", lotNumber)
																.addValue("actorId", actorId)
																.addValue("equipId", equipmentId)
																.addValue("st", (startTime == null || startTime.isBlank()) ? null : startTime)
																.addValue("spjangcd", spjangcd)
																.addValue("userId", user.getId());

		Map<String, Object> ins = this.sqlRunner.getRow("""
                INSERT INTO mat_produce
                       ("JobResponse_id","Material_id","Process_id","ProcessOrder","LotIndex",
                        "LotNumber","LastProcessYN","StoreHouse_id","ProductionDate","ShiftCode",
                        "WorkCenter_id","Equipment_id","Actor_id",
                        "InputQty","GoodQty","DefectQty","LossQty","ScrapQty",
                        "State","StartTime","Description",
                        _status,_created,_creater_id,spjangcd)
                SELECT jr.id
                     , jr."Material_id"
                     , wc."Process_id"
                     , COALESCE(jr."WorkIndex", 1)
                     -- ★ MAX+1 이지 COUNT+1 이 아니다.
                     --   취소분(_status='d')을 세면 번호가 계속 늘고,
                     --   하드 삭제가 섞이면 COUNT 방식은 번호가 겹친다.
                     , COALESCE((SELECT MAX(mp2."LotIndex") FROM mat_produce mp2
                                  WHERE mp2."JobResponse_id" = jr.id
                                    AND COALESCE(mp2._status,'a') = 'a'), 0) + 1
                     , :lot
                     , 'Y'                                  -- 포장이 마지막 공정
                     , CAST(:store AS integer)              -- 제품창고(4) 고정
                     , jr."ProductionDate"
                     , jr."ShiftCode"
                     , CAST(:wc AS integer)                 -- 포장 워크센터(56) 고정
                     , CAST(:equipId AS integer)
                     , CAST(:actorId AS integer)
                     , 0, 1, 0, 0, 0                        -- 1대 = 1박스
                     , 'working'
                     , COALESCE(CAST(:st AS timestamptz), now())
                     , '포장'
                     , 'a', now(), CAST(:userId AS integer), CAST(:spjangcd AS varchar)
                  FROM job_res jr
                  JOIN work_center wc ON wc.id = CAST(:wc AS integer)
                 WHERE jr.id = :jrId
                RETURNING id
                """, p);
		if (ins == null) throw new IllegalStateException("포장 차수를 생성하지 못했습니다.");

		// 작지 ordered → working (「작업 추가」를 건너뛰고 바로 포장한 경우 대비)
		this.sqlRunner.execute("""
                UPDATE job_res SET "State"='working', "_modified"=now(), "_modifier_id"=:userId
                 WHERE id = :jrId AND COALESCE("State",'ordered') = 'ordered'
                """, p);

		return asInt(ins.get("id"));
	}

	/**
	 * 「작업 추가」 = 포장 작업 시작. 작지를 working 으로 올린다.
	 *
	 *   State       ordered → working  (이미 working/finished 면 건드리지 않는다)
	 *   StartTime   비어 있을 때만 채운다 — 두 번째 작업조가 원래 시작시각을 덮으면 안 된다
	 *   Manager_id  최근 작업자. 실적 귀속은 mat_produce."Actor_id" 가 하고
	 *               (v3 §5.1 : Actor_id = person.id 는 시스템 전체 약속)
	 *               여기는 '지금 이 작지를 누가 잡고 있나' 를 보는 용도다
	 *
	 * ★ 일시정지 상태는 두지 않는다. State 값을 늘리면 그 값을 모르는 쿼리에서
	 *   작지가 조용히 사라지고(포장 큐는 ordered/working/finished 만 본다),
	 *   recalcJobRes 롤업이 어차피 덮어쓴다. 포장은 1대=1박스라 중단해도
	 *   보존할 중간 상태가 없다 — 안 한 박스는 그냥 안 한 것이다.
	 */
	@Transactional
	public AjaxResult startWork(Integer jobResId, Integer actorId, String startTime, User user) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		if (jobResId == null) { r.success = false; r.message = "작업지시가 필요합니다."; return r; }

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("jrId", jobResId)
																.addValue("actorId", actorId)
																.addValue("st", (startTime == null || startTime.isBlank()) ? null : startTime)
																.addValue("userId", user.getId());
		this.sqlRunner.execute("""
                UPDATE job_res
                   SET "State"      = CASE WHEN COALESCE("State",'ordered') = 'ordered'
                                           THEN 'working' ELSE "State" END,
                       -- ★ 컬럼이 timestamptz 다. timestamp 로 캐스트하면 서버 타임존으로
                       --   해석돼 들어가고, 인스턴스가 UTC 인 클라우드에서 9시간 어긋난다.
                       "StartTime"  = COALESCE("StartTime", CAST(:st AS timestamptz), now()),
                       "Manager_id" = COALESCE(CAST(:actorId AS integer), "Manager_id"),
                       "_modified"  = now(), "_modifier_id" = :userId
                 WHERE id = :jrId
                """, p);
		return r;
	}

	/**
	 * 「작업 종료」 — 한 박스도 안 했으면 작지를 원래대로 되돌린다.
	 *
	 * 실적 0건인 working 작지를 남기면 생산실적현황(finished 만 본다)에도 안 잡히고
	 * 큐에는 「작업중」으로 떠서, 아무도 안 하고 있는데 하는 중인 것처럼 보인다.
	 * 차수가 하나라도 있으면 그게 곧 진행 중이므로 손대지 않는다.
	 */
	@Transactional
	public AjaxResult endWork(Integer jobResId, User user) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		if (jobResId == null) { r.success = false; r.message = "작업지시가 필요합니다."; return r; }

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("jrId", jobResId).addValue("userId", user.getId());
		this.sqlRunner.execute("""
                UPDATE job_res
                   SET "State"='ordered', "StartTime"=NULL, "Manager_id"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id = :jrId
                   AND COALESCE("State",'ordered') = 'working'
                   AND NOT EXISTS (SELECT 1 FROM mat_produce mp
                                    WHERE mp."JobResponse_id" = :jrId
                                      AND COALESCE(mp."_status",'a') = 'a')
                """, p);
		return r;
	}

	/**
	 * 포장 취소 — 차수 롤백 + 유닛 packed → pass 복귀.
	 *
	 * consumeLot 이 SourceDataPk=mpId 로 남겼으므로 조립의 rollbackProduce 와 같은 절차로 정리된다.
	 * 유닛 로트는 mat_lot_cons 가 지워지면 트리거가 CurrentStock 을 되살린다.
	 * (창고는 19 그대로다 — 소비만 했지 옮기지 않았다)
	 */
	@Transactional
	public AjaxResult cancelPack(Integer mpId, User user) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		if (mpId == null) { r.success = false; r.message = "취소할 차수가 없습니다."; return r; }

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("mpId", mpId).addValue("userId", user.getId());

		Map<String, Object> mp = this.sqlRunner.getRow("""
                SELECT id, "JobResponse_id" AS jr_id, "LotNumber" AS lot_number, "State" AS state
                  FROM mat_produce WHERE id = :mpId
                """, p);
		if (mp == null) { r.success = false; r.message = "차수를 찾을 수 없습니다."; return r; }

		// 완제품 로트가 이미 출고·소진됐으면 막는다
		Map<String, Object> used = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mat_lot ml
                 WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=:mpId
                   AND COALESCE(ml."CurrentStock",0) < COALESCE(ml."InputQty",0)
                """, p);
		if (used != null && asInt(used.get("c")) != null && asInt(used.get("c")) > 0) {
			r.success = false;
			r.message = "이 완제품 로트가 이미 출고·사용되어 취소할 수 없습니다.";
			return r;
		}

		// ── 라벨 정리 ──
		// packUnit 이 유닛 로트가 비어 있을 때만 라벨을 채웠으므로, 취소 때도
		// '이 포장이 붙인 값과 같을 때만' 비운다. 다른 경로로 들어온 라벨은 건드리지 않는다.
		// 안 비우면 같은 유닛을 다른 박스로 다시 포장했을 때 예전 라벨이 남아 실물과 어긋난다.
		// ★ 아래 DELETE 들이 돌기 전에 해야 한다. 완제품 로트 행과 mat_lot_cons 를 지우고 나면
		//   '어느 라벨이었는지' 도 '어느 유닛 로트였는지' 도 알 방법이 없다.
		Map<String, Object> mk = this.sqlRunner.getRow("""
                SELECT ml."MakerLotNo" AS mk FROM mat_lot ml
                 WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=:mpId
                   AND ml."MakerLotNo" IS NOT NULL AND ml."MakerLotNo" <> ''
                 LIMIT 1
                """, p);
		if (mk != null && mk.get("mk") != null) {
			this.sqlRunner.execute("""
                    UPDATE mat_lot SET "MakerLotNo" = NULL
                     WHERE "MakerLotNo" = :mk
                       AND id IN (SELECT lc."MaterialLot_id" FROM mat_lot_cons lc
                                   WHERE lc."SourceTableName"='mat_produce'
                                     AND lc."SourceDataPk"=:mpId)
                    """, new MapSqlParameterSource()
																												.addValue("mpId", mpId).addValue("mk", str(mk.get("mk"))));
		}

		/* ★ 라벨 행도 지운다. 안 지우면 pack_label."MatProduce_id" 가
		     아래에서 _status='d' 로 닫히는 차수를 계속 물고 있어
		     같은 유닛을 다시 포장할 때 유니크에 걸리거나 옛 라벨이 조회에 남는다.
		     (mat_produce 행 자체는 지우지 않으므로 FK 위반은 아니다) */
		this.sqlRunner.execute(
			"DELETE FROM pack_label WHERE \"MatProduce_id\"=:mpId AND \"LabelKind\"='" + LABEL_KIND_MCELL + "'", p);

		// 산출 정리
		this.sqlRunner.execute("DELETE FROM mat_lot   WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
		this.sqlRunner.execute("DELETE FROM mat_inout WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
		// 투입 정리 (mat_consu 출처 이력 → mat_consu → mat_lot_cons)
		this.sqlRunner.execute("""
                DELETE FROM mat_inout
                 WHERE "SourceTableName"='mat_consu'
                   AND "SourceDataPk" IN (SELECT id FROM mat_consu
                        WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                          AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId))
                """, p);
		this.sqlRunner.execute("DELETE FROM mat_lot_cons WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
		this.sqlRunner.execute("""
                DELETE FROM mat_consu
                 WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                   AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId)
                """, p);
		this.sqlRunner.execute("""
                UPDATE mat_produce SET "_status"='d', "State"='wait',
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:mpId
                """, p);

		// 유닛 복귀
		MapSqlParameterSource lp = new MapSqlParameterSource()
																 .addValue("lot", str(mp.get("lot_number"))).addValue("userId", user.getId());
		Map<String, Object> unit = this.sqlRunner.getRow("""
                SELECT id FROM mcell_unit
                 WHERE "LotNumber"=:lot AND "State"='packed' AND COALESCE("_status",'a')='a'
                 ORDER BY id LIMIT 1
                """, lp);
		if (unit != null) {
			this.sqlRunner.execute("""
                    UPDATE mcell_unit SET "State"='pass', "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=""" + asInt(unit.get("id")), lp);
			Integer assyJrId = getUnitJobRes(asInt(unit.get("id")));
			if (assyJrId != null) this.productionCreateService.recalcJobRes(assyJrId, user);
		}

		// 포장 작지 롤업
		this.productionCreateService.recalcJobRes(asInt(mp.get("jr_id")), user);

		r.message = "포장 취소 · 유닛이 포장 대기로 돌아갔습니다.";
		return r;
	}

	/** 시작/완료 시각 수정 — mat_produce 만 손댄다(2공장 조립·수리와 동일)
	 *  ★ 컬럼이 timestamptz 다. timestamp 로 캐스트하면 UTC 인스턴스에서 9시간 어긋난다. */
	@Transactional
	public AjaxResult setPackTime(Integer mpId, String which, String value, User user) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		String col = "end".equals(which) ? "EndTime" : "StartTime";
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("mpId", mpId).addValue("val", value).addValue("userId", user.getId());
		this.sqlRunner.execute("UPDATE mat_produce SET \"" + col + "\"=CAST(:val AS timestamptz), "
														 + "\"_modified\"=now(), \"_modifier_id\"=:userId WHERE id=:mpId", p);
		return r;
	}

	// =====================================================================
	// 내부 유틸
	// =====================================================================

	/** 작지가 있으면 그 산출품목이 완제품, 없으면 카드가 들고 온 품목 */
	private Integer resolvePackMatId(Integer jobResId, Integer packMatId) {
		if (jobResId == null) {
			if (packMatId == null) throw new IllegalArgumentException("완제품 품목이 필요합니다.");
			return packMatId;
		}
		Map<String, Object> row = this.sqlRunner.getRow(
			"SELECT \"Material_id\" AS mat_id FROM job_res WHERE id = :id",
			new MapSqlParameterSource().addValue("id", jobResId));
		if (row == null) throw new IllegalArgumentException("작업지시를 찾을 수 없습니다.");
		return asInt(row.get("mat_id"));
	}

	private Integer getUnitJobRes(Integer unitId) {
		if (unitId == null) return null;
		Map<String, Object> row = this.sqlRunner.getRow(
			"SELECT \"JobResponse_id\" AS jr_id FROM mcell_unit WHERE id=:id",
			new MapSqlParameterSource().addValue("id", unitId));
		return (row == null) ? null : asInt(row.get("jr_id"));
	}

	private static Integer asInt(Object o) { return (o == null) ? null : ((Number) o).intValue(); }
	private static double toD(Object o) { return (o == null) ? 0d : Double.parseDouble(String.valueOf(o)); }
	private static String str(Object o) { return (o == null) ? null : String.valueOf(o); }
}