package mes.app.production.service;

import mes.domain.model.AjaxResult;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ①생산형 공용 조회 서비스 (조립·블리스터·융착·포장). 화면은 process_id 만 바꿔 공용.
 * <p>
 * 화면 골격: 날짜(A) → 작업조(B) → 용기(C)
 * - 작업조 = 별도 테이블 없음. (ProductionDate, ShiftCode, Actor_id) 로 mat_produce 를 group by 하여 파생.
 * - 용기   = mat_produce 차수 1건.
 * - 실적 생성/완료 = ProductionCreateService (ProductionWorkController 에서 호출).
 * <p>
 * process_id 만 바꿔 조립/블리스터/융착/포장이 동일 엔드포인트를 쓴다.
 */
@Service
public class ProductionWorkService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 공정 컨텍스트 — process_id 로 워크센터/산출창고 조회
     */
    public Map<String, Object> getContext(String processCode, Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processCode", processCode);
        p.addValue("factoryId", factoryId);
        String sql = """
                SELECT p.id AS process_id, p."Code" AS process_code, p."Name" AS process_name,
                       wc.id AS workcenter_id, wc."Name" AS workcenter_name,
                       wc."ProcessStoreHouse_id" AS out_store_id, wc."Factory_id" AS factory_id
                  FROM process p
                  LEFT JOIN work_center wc ON wc."Process_id" = p.id
                        AND (CAST(:factoryId AS INTEGER) IS NULL OR wc."Factory_id" = CAST(:factoryId AS INTEGER))
                 WHERE p."Code" = :processCode
                 ORDER BY wc.id
                 LIMIT 1
                """;
        return this.sqlRunner.getRow(sql, p);
    }

    /**
     * 날짜 카드 목록 — 해당 공정의 mat_produce 를 날짜로 집계
     */
    public List<Map<String, Object>> getDayList(Integer processId, String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo", LocalDate.parse(dateTo));
        p.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT mp."ProductionDate"::date                              AS work_date
                     , to_char(mp."ProductionDate", 'yyyy-mm-dd')             AS work_date_str
                     , COUNT(DISTINCT (mp."ShiftCode", mp."Actor_id"))        AS crew_cnt
                     , COUNT(*)                                               AS item_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'finished')        AS item_done_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'working')         AS item_working_cnt
                     , COALESCE(SUM(mp."GoodQty") FILTER (WHERE mp."State" = 'finished'), 0) AS good_qty
                     , CASE WHEN COUNT(*) = 0 THEN 'wait'
                            WHEN COUNT(*) = COUNT(*) FILTER (WHERE mp."State" = 'finished') THEN 'done'
                            ELSE 'working' END                                AS state
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND mp."ProductionDate"::date BETWEEN :dateFrom AND :dateTo
                 GROUP BY mp."ProductionDate"::date
                 ORDER BY mp."ProductionDate"::date DESC
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작업조 목록 — (날짜, ShiftCode, Actor_id) 파생
     */
    public List<Map<String, Object>> getCrewList(Integer processId, String date, String spjangcd) {
        return getCrewList(processId, date, spjangcd, null);
    }

    /**
     * 작업조 목록. jobResId 가 주어지면(WO-우선 공정: 블리스터 등) 그 작업지시에 귀속된
     * 차수만 대상으로 조를 파생한다(작지는 날짜를 넘길 수 있으므로 날짜 필터 제거).
     * jobResId 가 null 이면 기존 날짜 기반(조립 등).
     */
    public List<Map<String, Object>> getCrewList(Integer processId, String date, String spjangcd, Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", date == null ? null : LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);
        p.addValue("jobResId", jobResId);
        String sql = """
                SELECT mp."ShiftCode"                                   AS shift_code
                     , sh."Name"                                        AS shift_name
                     , mp."Actor_id"                                    AS actor_id
                     , pr."Name"                                        AS leader_name
                     , mp."Equipment_id"                                AS equipment_id
                     , e."Name"                                         AS equipment_name
                     , COUNT(*)                                         AS item_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'finished')  AS item_done_cnt
                     , COALESCE(SUM(mp."GoodQty") FILTER (WHERE mp."State"='finished'), 0) AS good_qty
                     , to_char(MIN(mp."StartTime"), 'yyyy-mm-dd hh24:mi') AS start_time
                     , CASE WHEN COUNT(*) > 0 AND COUNT(*) = COUNT(*) FILTER (WHERE mp."State"='finished')
                            THEN to_char(MAX(mp."EndTime"), 'yyyy-mm-dd hh24:mi') ELSE NULL END AS end_time
                     , CASE WHEN COUNT(*) = COUNT(*) FILTER (WHERE mp."State"='finished') THEN 'done'
                            WHEN COUNT(*) FILTER (WHERE mp."State"<>'finished') > 0
                                 AND COUNT(*) FILTER (WHERE mp."State"='finished') > 0 THEN 'working'
                            WHEN COUNT(*) FILTER (WHERE mp."State"='working') > 0 THEN 'working'
                            ELSE 'working' END                          AS state
                     , string_agg(DISTINCT mem_name, ', ')              AS members
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN shift sh ON sh."Code" = mp."ShiftCode"
                  LEFT JOIN person pr ON pr.id = mp."Actor_id"
                  LEFT JOIN equ e ON e.id = mp."Equipment_id"
                  LEFT JOIN LATERAL (
                        SELECT p2."Name" AS mem_name
                          FROM mat_produce_member mpm
                          JOIN person p2 ON p2.id = mpm."Person_id"
                         WHERE mpm."MatProduce_id" = mp.id AND mpm."_status"='a'
                  ) mm ON true
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND ( (CAST(:jobResId AS INTEGER) IS NOT NULL
                          AND mp."JobResponse_id" = CAST(:jobResId AS INTEGER))
                      OR (CAST(:jobResId AS INTEGER) IS NULL
                          AND mp."ProductionDate"::date = :date) )
                 GROUP BY mp."ShiftCode", sh."Name", mp."Actor_id", pr."Name",
                          mp."Equipment_id", e."Name"
                 ORDER BY MIN(mp."StartTime") NULLS LAST
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 용기(차수) 목록 — 한 작업조가 만든 mat_produce 들
     */
    public List<Map<String, Object>> getItemList(Integer processId, String date, String shiftCode,
                                                 Integer actorId, String spjangcd) {
        return getItemList(processId, date, shiftCode, actorId, spjangcd, null);
    }

    /**
     * 한 작업조가 만든 차수 목록. jobResId 가 주어지면 그 작업지시로 한정(WO-우선 공정).
     */
    public List<Map<String, Object>> getItemList(Integer processId, String date, String shiftCode,
                                                 Integer actorId, String spjangcd, Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", date == null ? null : LocalDate.parse(date));
        p.addValue("shiftCode", shiftCode);
        p.addValue("actorId", actorId);
        p.addValue("spjangcd", spjangcd);
        p.addValue("jobResId", jobResId);
        String sql = """
                SELECT mp.id                          AS mp_id
                     , mp."JobResponse_id"            AS job_res_id
                     , jr."WorkOrderNumber"           AS order_num
                     , mp."Material_id"               AS mat_id
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , u."Name"                       AS unit
                     , mp."LotNumber"                 AS lot_no
                     , mp."LotIndex"                  AS chasu
                     , COALESCE(mp."GoodQty",0)       AS good_qty
                     , COALESCE(mp."DefectQty",0)     AS defect_qty
                     , mp."State"                     AS state
                     , to_char(mp."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN material m ON m.id = mp."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND mp."ShiftCode" = :shiftCode
                   AND mp."Actor_id" = :actorId
                   AND ( (CAST(:jobResId AS INTEGER) IS NOT NULL
                          AND mp."JobResponse_id" = CAST(:jobResId AS INTEGER))
                      OR (CAST(:jobResId AS INTEGER) IS NULL
                          AND mp."ProductionDate"::date = :date) )
                 ORDER BY mp.id
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 대상 산출품목 후보 — 이 공정(process)에서 만들 수 있는 반제품.
     * 작지 있는 것(WO) + 작지 없이 직접 선택할 SKU(semi 반제품) 둘 다.
     */
    public List<Map<String, Object>> getTargetMaterials(Integer processId, String matTypeLike,
                                                        String keyword, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");
        // 이 공정에서 생산되는 반제품 = 품목의 WorkCenter 공정이 이 공정인 것.
        //  (반제품은 자기 Routing_id 가 없고, material.WorkCenter_id 로 공정이 정해짐 — 작지 유무와 무관)
        //  raw/sub_mat 은 MaterialType 으로 방어(워크센터가 나중에 생겨도 안 뜸).
        String sql = """
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       u."Name" AS unit, mg."MaterialType" AS mat_type
                  FROM material m
                  JOIN work_center wc ON wc.id = m."WorkCenter_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                 WHERE m."_status" = 'a'
                   AND mg."MaterialType" IN ('semi','product')
                   AND wc."Process_id" = :processId
                   AND (CAST(:keyword AS VARCHAR) IS NULL
                        OR m."Name" LIKE CAST(:keyword AS VARCHAR)
                        OR m."Code" LIKE CAST(:keyword AS VARCHAR))
                 ORDER BY m."Code"
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작지 있는 대상(WO) — 해당 공정 워크센터의 미완료 작지
     */
    public List<Map<String, Object>> getWorkOrders(Integer processId, String date, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT (array_agg(jr.id ORDER BY jr."WorkOrderNumber" DESC))[1] AS job_res_id
                     , MAX(jr."WorkOrderNumber")           AS order_num
                     , jr."Material_id"                    AS mat_id
                     , m."Code"                            AS mat_code
                     , m."Name"                            AS mat_name
                     , SUM(COALESCE(jr."OrderQty",0))      AS order_qty
                     , COUNT(*)                            AS wo_cnt
                  FROM job_res jr
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                 WHERE jr.spjangcd = :spjangcd
                   AND wc."Process_id" = :processId
                   AND jr."State" IN ('ordered','working')
                   AND jr."ProductionDate"::date <= :date
                 GROUP BY jr."Material_id", m."Code", m."Name"
                 ORDER BY m."Code"
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 투입자재 후보 — 클린룸(5) 재고 있는 자재 (화면 BOM 추가용)
     */
    public List<Map<String, Object>> getCleanStock(Integer storeId, String keyword, String filterMode) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("storeId", storeId);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");

        // 투입자재 후보 필터 모드
        //  'wash'  = 세척부품(WashYN='Y') — 조립 공정용. 재고 0도 노출(세척으로 곧 채워짐).
        //  'stock' = 소스창고 실재고 있는 자재(용기 반제품 + 부자재) — 블리스터/융착/포장 등
        //            반제품을 투입으로 쓰는 공정용. WashYN 무관, 물리적으로 창고에 있는 것만.
        //  기본값 'wash' (조립 기존 동작 보존).
        boolean stockMode = "stock".equalsIgnoreCase(filterMode);
        String matPred = stockMode
                ? "EXISTS (SELECT 1 FROM mat_lot ml2 WHERE ml2.\"Material_id\" = m.id "
                + "AND ml2.\"StoreHouse_id\" = :storeId AND ml2.\"CurrentStock\" > 0)"
                : "COALESCE(m.\"WashYN\",'N') = 'Y'";
        String sql = """
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       u."Name" AS unit, COALESCE(SUM(ml."CurrentStock"),0) AS stock
                  FROM material m
                  LEFT JOIN mat_lot ml ON ml."Material_id" = m.id
                                      AND ml."StoreHouse_id" = :storeId
                                      AND ml."CurrentStock" > 0
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                 WHERE m."_status" = 'a'
                   AND %s
                   AND (CAST(:keyword AS VARCHAR) IS NULL
                        OR m."Name" LIKE CAST(:keyword AS VARCHAR)
                        OR m."Code" LIKE CAST(:keyword AS VARCHAR))
                 GROUP BY m.id, m."Code", m."Name", u."Name"
                 ORDER BY m."Code"
                 LIMIT 100
                """.formatted(matPred);
        /**
         * 조립 BOM 기본값 — 산출품목(용기)의 manufacturing BOM 을 수량만큼 전개.
         * 클린룸(5) 현재고를 함께 붙여 화면에서 부족 여부 표시.
         * 작업자는 이 기본값에서 추가/해지/수량조절 후 완료 시 편집분을 bom_json 으로 전송.
         */
        return this.sqlRunner.getRows(sql, p);
    }

    public List<Map<String, Object>> getBomDefault(Integer materialId, Float qty,
                                                   Integer cleanStore, String prodDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("materialId", materialId);
        p.addValue("qty", qty == null ? 0f : qty);
        p.addValue("cleanStore", cleanStore == null ? 5 : cleanStore);
        p.addValue("prodDate", (prodDate == null || prodDate.isBlank())
                ? LocalDate.now().toString() : prodDate);
        String sql = """
                SELECT bc."Material_id"                        AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , bc."Amount" / b."OutputAmount"          AS per
                     , CEIL(bc."Amount" / b."OutputAmount" * :qty) AS default_qty
                     , COALESCE(src.stock, 0)                  AS stock
                     , COALESCE(m."LotUseYN",'N')              AS lot_use_yn
                     , mg."MaterialType"                       AS mat_type
                     , (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                             WHEN mg."MaterialType" IN ('semi','product') THEN 5
                             WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                             ELSE 17 END)                      AS src_store
                     , sh."Name"                               AS src_store_name
                  FROM bom b
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                  JOIN material m  ON m.id = bc."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = bc."Material_id"
                           AND ml."StoreHouse_id" = (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                                          WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                                          WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                                          ELSE 17 END)
                  ) src ON true
                  LEFT JOIN store_house sh ON sh.id = (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                                            WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                                            WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                                            ELSE 17 END)
                 WHERE b."Material_id" = :materialId
                   AND b."BOMType" = 'manufacturing'
                   AND CAST(:prodDate AS date)
                       BETWEEN COALESCE(b."StartDate", DATE '0001-01-01')
                           AND COALESCE(b."EndDate",   DATE '9999-12-31')
                 ORDER BY bc.id
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 완료된 차수(mp)가 실제 소비한 투입자재 — mat_lot_cons(로트별 차감) 를 자재별로 집계.
     * 완료 후 화면에서 '차감된 투입자재'를 보여주기 위한 조회(읽기전용).
     */
    public List<Map<String, Object>> getConsumedInputs(Integer mpId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
        String sql = """
                SELECT m.id                                   AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , mg."MaterialType"                       AS mat_type
                     , SUM(COALESCE(mlc."OutputQty",0))        AS consumed_qty
                     , string_agg(DISTINCT ml."LotNumber", ', ') AS lots
                     , (array_agg(sh."Name"))[1]               AS store
                  FROM mat_lot_cons mlc
                  JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                  JOIN material m ON m.id = ml."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                  LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                 WHERE mlc."SourceTableName" = 'mat_produce'
                   AND mlc."SourceDataPk" = :mpId
                 GROUP BY m.id, m."Code", m."Name", u."Name", mg."MaterialType"
                 ORDER BY m."Code"
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 특정 자재의 창고(클린룸) 재고 로트 — FIFO 순서(InputDateTime ASC, id ASC).
     * 용기 반제품 로트 선택/표시용. 소비 순서(reserveInput·consumeBomForChasu)와 동일 정렬.
     */
    public List<Map<String, Object>> getMaterialLots(Integer materialId, Integer storeId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("materialId", materialId);
        p.addValue("storeId", storeId == null ? 5 : storeId);
        String sql = """
                SELECT ml.id                                   AS lot_id
                     , ml."LotNumber"                          AS lot_no
                     , m."Name"                                AS mat_name
                     , m."Code"                                AS mat_code
                     , COALESCE(ml."CurrentStock",0)           AS avail
                     , sh."Name"                               AS warehouse
                     , to_char(ml."InputDateTime", 'yyyy-mm-dd') AS in_date
                  FROM mat_lot ml
                  JOIN material m ON m.id = ml."Material_id"
                  LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                 WHERE ml."Material_id" = :materialId
                   AND ml."StoreHouse_id" = :storeId
                   AND COALESCE(ml."CurrentStock",0) > 0
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작업지시 큐(WO-우선 공정 진입 화면용) — 작지 1건 = PK 1개.
     * 완제품 작지 전개로 생성된 PK 자식 작지들을 진행률과 함께 개별 카드로 내려준다.
     * getWorkOrders 는 품목별 집계였지만, 이건 job_res 단위(개별 WO) 로 편다.
     */
    public List<Map<String, Object>> getWorkOrderQueue(Integer processId, String spjangcd,
                                                       String dateFrom, String dateTo) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("spjangcd", spjangcd);
        p.addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom));
        p.addValue("dateTo",   (dateTo   == null || dateTo.isBlank())   ? null : LocalDate.parse(dateTo));
        String sql = """
                SELECT jr.id                                   AS job_res_id
                     , jr."WorkOrderNumber"                    AS order_num
                     , jr."Material_id"                        AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS plan_date
                     , COALESCE(jr."OrderQty",0)               AS plan_qty
                     , COALESCE(prod.good_qty, 0)              AS good_qty
                     , COALESCE(prod.chasu_cnt, 0)             AS chasu_cnt
                     , COALESCE(prod.crew_cnt, 0)              AS crew_cnt
                     , COALESCE(prod.done_cnt, 0)              AS done_cnt
                     , COALESCE(prod.working_cnt, 0)           AS working_cnt
                     , CASE WHEN COALESCE(prod.chasu_cnt,0) = 0 THEN 'wait'
                            WHEN COALESCE(prod.good_qty,0) >= COALESCE(jr."OrderQty",0)
                                 AND COALESCE(jr."OrderQty",0) > 0 THEN 'done'
                            ELSE 'working' END                 AS state
                  FROM job_res jr
                  JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(mp."GoodQty") FILTER (WHERE mp."State"='finished') AS good_qty
                             , COUNT(*)                                               AS chasu_cnt
                             , COUNT(*) FILTER (WHERE mp."State"='finished')          AS done_cnt
                             , COUNT(*) FILTER (WHERE mp."State"='working')           AS working_cnt
                             , COUNT(DISTINCT (mp."ShiftCode", mp."Actor_id"))        AS crew_cnt
                          FROM mat_produce mp
                         WHERE mp."JobResponse_id" = jr.id
                           AND COALESCE(mp."_status",'a') = 'a'
                  ) prod ON true
                 WHERE jr.spjangcd = :spjangcd
                   AND wc."Process_id" = :processId
                   AND jr."State" IN ('ordered','working','finished')
                   AND (CAST(:dateFrom AS date) IS NULL OR jr."ProductionDate"::date >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR jr."ProductionDate"::date <= CAST(:dateTo   AS date))
                 ORDER BY jr."ProductionDate" DESC, jr."WorkOrderNumber" DESC
                 LIMIT 200
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    // =====================================================================
    // 쓰기 — 용기(mat_produce) 상태 전이. (item_add·item_finish 는 ProductionCreateService)
    //   세척과 동일 저장 시점: 담기(wait) → 시작(working) → 완료(finished).
    // =====================================================================

    /** 수량 중간저장(완료 전). 세척 itemSave 대응. */
    @Transactional
    public AjaxResult itemSave(Integer mpId, Float qty, Float defectQty, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("qty", qty == null ? 0f : qty);
        p.addValue("defectQty", defectQty == null ? 0f : defectQty);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE mat_produce
               SET "GoodQty"=:qty, "DefectQty"=:defectQty,
                   "State"=CASE WHEN "State"='wait' AND :qty > 0 THEN 'working' ELSE "State" END,
                   "StartTime"=COALESCE("StartTime", CASE WHEN :qty > 0 THEN LOCALTIMESTAMP ELSE NULL END),
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:mpId AND "State"<>'finished'
            """, p);
        AjaxResult r = new AjaxResult(); r.success = true; return r;
    }

    /** 작업시작 = wait→working (수량 함께 저장). 재고 이동은 완료에서. 세척 itemStart 대응. */
    @Transactional
    public AjaxResult itemStart(Integer mpId, Float qty, Float defectQty, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("qty", qty);
        p.addValue("defectQty", defectQty);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE mat_produce
               SET "State"='working',
                   "StartTime"=COALESCE("StartTime", LOCALTIMESTAMP),
                   "GoodQty"=COALESCE(CAST(:qty AS DOUBLE PRECISION), "GoodQty"),
                   "DefectQty"=COALESCE(CAST(:defectQty AS DOUBLE PRECISION), "DefectQty"),
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:mpId AND "State"<>'finished'
            """, p);
        AjaxResult r = new AjaxResult(); r.success = true; return r;
    }

    /** 용기 삭제(완료 전만). member 도 함께 소프트삭제. 세척 itemDelete 대응. */
    @Transactional
    public AjaxResult itemDelete(Integer mpId, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("userId", user.getId());
        Map<String, Object> it = this.sqlRunner.getRow(
                "SELECT mp.\"State\" AS state, mp.\"JobResponse_id\" AS jr_id, mp.\"Material_id\" AS mat_id "
                        + "FROM mat_produce mp WHERE mp.id=:mpId", p);
        AjaxResult r = new AjaxResult(); r.success = true;
        if (it == null) { r.success = false; r.message = "용기를 찾을 수 없습니다."; return r; }
        // 완료(재고 실제 차감됨)만 차단 — wait/working 은 삭제 허용
        if ("finished".equals(it.get("state"))) {
            r.success = false; r.message = "완료된 용기는 삭제할 수 없습니다. 완료취소 후 삭제하세요."; return r;
        }

        // working 이면 작업시작 때 잡아둔 예약(mat_proc_input RequestQty)이 있을 수 있으므로 함께 정리.
        //   재고는 아직 안 움직였으니(완료 전) 예약 행만 지우면 됨.
        Integer jrId = (it.get("jr_id") != null) ? ((Number) it.get("jr_id")).intValue() : null;
        Integer matId = (it.get("mat_id") != null) ? ((Number) it.get("mat_id")).intValue() : null;
        if (jrId != null) {
            MapSqlParameterSource dp = new MapSqlParameterSource();
            dp.addValue("jrId", jrId);
            // 이 작지(차수)의 예약분 삭제. 한 작지에 한 차수만 있는 조립 특성상 작지 단위로 정리.
            this.sqlRunner.execute("""
                DELETE FROM mat_proc_input
                 WHERE "MaterialProcessInputRequest_id" = (
                        SELECT "MaterialProcessInputRequest_id" FROM job_res WHERE id=:jrId)
                """, dp);
        }

        this.sqlRunner.execute("DELETE FROM mat_produce_member WHERE \"MatProduce_id\"=:mpId", p);
        this.sqlRunner.execute("""
            UPDATE mat_produce SET "_status"='d', "_modified"=now(), "_modifier_id"=:userId WHERE id=:mpId
            """, p);
        return r;
    }
}