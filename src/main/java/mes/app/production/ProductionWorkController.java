package mes.app.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.production.service.ProductionWorkService;
import mes.app.production.service.ProductionCreateService;
import mes.app.production.service.ProductionCreateService.CreateReq;
import mes.app.production.service.ProductionCreateService.BomInput;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import mes.domain.services.SqlRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 조립·블리스터·융착·포장 공용 컨트롤러 (①생산형).
 * 화면은 process_code / 산출품목만 바꿔 동일 엔드포인트를 쓴다.
 * 실적 생성 알맹이 = ProductionCreateService (사람·OPC-UA 공용).
 */
@RestController
@RequestMapping("/api/production/work")
public class ProductionWorkController {

    @Autowired private ProductionWorkService productionWorkService;
    @Autowired private ProductionCreateService productionCreateService;
    @Autowired private SqlRunner sqlRunner;
    private final ObjectMapper om = new ObjectMapper();

    // ── 조회 ─────────────────────────────────────────────

    @GetMapping("/context")
    public AjaxResult context(
            @RequestParam("process_code") String processCode,
            @RequestParam(value = "factory_id", required = false) Integer factoryId) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getContext(processCode, factoryId);
        return r;
    }

    @GetMapping("/day_list")
    public AjaxResult dayList(
            @RequestParam("process_id") Integer processId,
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getDayList(processId, dateFrom, dateTo, spjangcd);
        return r;
    }

    @GetMapping("/crew_list")
    public AjaxResult crewList(
            @RequestParam("process_id") Integer processId,
            @RequestParam("date") String date,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getCrewList(processId, date, spjangcd);
        return r;
    }

    @GetMapping("/item_list")
    public AjaxResult itemList(
            @RequestParam("process_id") Integer processId,
            @RequestParam("date") String date,
            @RequestParam("shift_code") String shiftCode,
            @RequestParam("actor_id") Integer actorId,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getItemList(processId, date, shiftCode, actorId, spjangcd);
        return r;
    }

    /** 작지 없이 직접 선택할 반제품(semi) SKU */
    @GetMapping("/target_materials")
    public AjaxResult targetMaterials(
            @RequestParam("process_id") Integer processId,
            @RequestParam(value = "mat_type_like", required = false) String matTypeLike,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getTargetMaterials(processId, matTypeLike, keyword, spjangcd);
        return r;
    }

    /** 작지 있는 대상(WO) */
    @GetMapping("/work_orders")
    public AjaxResult workOrders(
            @RequestParam("process_id") Integer processId,
            @RequestParam("date") String date,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getWorkOrders(processId, date, spjangcd);
        return r;
    }

    /** 투입자재 후보 (클린룸 재고) */
    @GetMapping("/clean_stock")
    public AjaxResult cleanStock(
            @RequestParam(value = "store_id", defaultValue = "5") Integer storeId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getCleanStock(storeId, keyword);
        return r;
    }

    /** BOM 기본값 — 용기 선택+수량 입력 시 서버가 소요자재 목록을 내려줌 */
    @GetMapping("/bom_default")
    public AjaxResult bomDefault(
            @RequestParam("material_id") Integer materialId,
            @RequestParam("qty") Float qty,
            @RequestParam(value = "clean_store", defaultValue = "5") Integer cleanStore,
            @RequestParam(value = "production_date", required = false) String productionDate) {
        AjaxResult r = new AjaxResult();
        r.data = this.productionWorkService.getBomDefault(materialId, qty, cleanStore, productionDate);
        return r;
    }

    // ── 용기 행 (세척 item_add/item_save/item_start/item_delete 대응) ──

    /**
     * 용기 담기 = mat_produce 'wait' 생성 (첫 저장 시점). 조원도 여기서 mat_produce_member 에 저장.
     * 작업조는 별도 테이블 없이 (날짜·shift·actor) 로 파생 → 화면이 편성 정보를 함께 보낸다.
     * 작지 있으면 job_res_id, 없으면 material_id 로 작지 자동생성.
     */
    @PostMapping("/item_add")
    @Transactional
    public AjaxResult itemAdd(
            @RequestParam(value = "job_res_id", required = false) Integer jobResId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            @RequestParam("work_center_id") Integer workCenterId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam("actor_id") Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam("shift_code") String shiftCode,
            @RequestParam("production_date") String productionDate,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();

        CreateReq req = new CreateReq();
        req.jobResId = jobResId;
        req.materialId = materialId;
        req.workCenterId = workCenterId;
        req.equipmentId = equipmentId;
        req.actorId = actorId;
        req.memberIds = parseIds(memberIds);
        req.shiftCode = shiftCode;
        req.productionDate = productionDate;
        req.goodQty = 0f;
        req.defectQty = 0f;
        req.spjangcd = spjangcd;

        return this.productionCreateService.addWaitItem(req, user);
    }

    /** 수량 중간저장(완료 전). */
    @PostMapping("/item_save")
    @Transactional
    public AjaxResult itemSave(
            @RequestParam("mp_id") Integer mpId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.productionWorkService.itemSave(mpId, qty, defectQty, user);
    }

    /** 작업시작 = wait→working (수량 저장) + BOM 예약(mat_proc_input RequestQty). 재고 차감은 완료에서. */
    @PostMapping("/item_start")
    @Transactional
    public AjaxResult itemStart(
            @RequestParam("mp_id") Integer mpId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            @RequestParam(value = "bom_json", required = false) String bomJson,
            @RequestParam(value = "clean_store", defaultValue = "5") Integer cleanStore,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        AjaxResult flip = this.productionWorkService.itemStart(mpId, qty, defectQty, user);
        if (flip == null || flip.success == false) return flip;
        // BOM 예약 (실패 시 예외 → 트랜잭션 롤백)
        return this.productionCreateService.reserveInput(mpId, parseBom(bomJson), cleanStore, user);
    }

    /** 용기 삭제(완료 전만). */
    @PostMapping("/item_delete")
    @Transactional
    public AjaxResult itemDelete(
            @RequestParam("mp_id") Integer mpId, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.productionWorkService.itemDelete(mpId, user);
    }

    /**
     * 용기(차수) 완료 — 시작된 차수(mp_id, working)에 수량 확정 + BOM 클린룸 차감 + 반제품 입고.
     * bom_json = [{"matId":123,"qty":100}, ...] (화면 편집 투입자재)
     * mp_id 없이 호출되면(장비 원샷) 작지/품목으로 시작+완료를 한 번에 처리.
     */
    @PostMapping("/item_finish")
    @Transactional
    public AjaxResult itemFinish(
            @RequestParam(value = "mp_id", required = false) Integer mpId,
            @RequestParam(value = "job_res_id", required = false) Integer jobResId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            @RequestParam(value = "work_center_id", required = false) Integer workCenterId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam(value = "shift_code", required = false) String shiftCode,
            @RequestParam("good_qty") Float goodQty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            @RequestParam(value = "production_date", required = false) String productionDate,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            @RequestParam(value = "bom_json", required = false) String bomJson,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {

        User user = (User) auth.getPrincipal();

        CreateReq req = new CreateReq();
        req.jobResId = jobResId;
        req.materialId = materialId;
        req.workCenterId = workCenterId;
        req.equipmentId = equipmentId;
        req.actorId = actorId;
        req.memberIds = parseIds(memberIds);
        req.shiftCode = shiftCode;
        req.goodQty = goodQty != null ? goodQty : 0f;
        req.defectQty = defectQty != null ? defectQty : 0f;
        req.productionDate = productionDate;
        req.startTime = startTime;
        req.endTime = endTime;
        req.bomList = parseBom(bomJson);
        req.spjangcd = spjangcd;

        // 시작된 차수가 있으면 그걸 완료(세척 패턴), 없으면 장비 원샷
        if (mpId != null) {
            return this.productionCreateService.finishProduction(mpId, req, user);
        }
        return this.productionCreateService.createProduction(req, user);
    }

    /**
     * 용기 완료취소 — 이 차수(mat_produce)가 만든 산출/투입/입출고/실적을 롤백.
     * 산출 반제품이 후속 공정에서 이미 소진됐으면 차단.
     */
    @PostMapping("/item_cancel")
    @Transactional
    public AjaxResult itemCancel(
            @RequestParam("mp_id") Integer mpId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        AjaxResult r = new AjaxResult();
        r.success = true;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);

        // 이 차수가 만든 산출 로트가 이미 소진됐는지 (후속 공정 사용)
        Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS c FROM mat_lot ml
             WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=:mpId
               AND COALESCE(ml."CurrentStock",0) < COALESCE(ml."InputQty",0)
            """, p);
        if (used != null && ((Number) used.get("c")).intValue() > 0) {
            r.success = false; r.message = "산출 반제품이 후속 공정에서 사용되어 취소할 수 없습니다."; return r;
        }

        // 산출 로트/입고 삭제 (트리거가 재고 정리)
        this.sqlRunner.execute("""
            DELETE FROM mat_lot WHERE "SourceTableName"='mat_produce' AND "SourceDataPk"=:mpId
            """, p);
        this.sqlRunner.execute("""
            DELETE FROM mat_inout WHERE "SourceTableName"='mat_produce' AND "SourceDataPk"=:mpId
            """, p);
        // 투입 차감 롤백: 이 차수의 mat_lot_cons + mat_consu + 그 out 이력
        this.sqlRunner.execute("""
            DELETE FROM mat_inout
             WHERE "SourceTableName"='mat_consu'
               AND "SourceDataPk" IN (SELECT id FROM mat_consu
                    WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                      AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId))
            """, p);
        this.sqlRunner.execute("""
            DELETE FROM mat_lot_cons WHERE "SourceTableName"='mat_produce' AND "SourceDataPk"=:mpId
            """, p);
        this.sqlRunner.execute("""
            DELETE FROM mat_consu
             WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
               AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId)
            """, p);
        // member + 차수 삭제
        this.sqlRunner.execute("DELETE FROM mat_produce_member WHERE \"MatProduce_id\"=:mpId", p);
        this.sqlRunner.execute("UPDATE mat_produce SET \"_status\"='d' WHERE id=:mpId", p);

        return r;
    }

    // ── 유틸 ─────────────────────────────────────────────

    private List<Integer> parseIds(String csv) {
        List<Integer> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) return ids;
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) try { ids.add(Integer.parseInt(s)); } catch (NumberFormatException ignore) {}
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<BomInput> parseBom(String json) {
        List<BomInput> list = new ArrayList<>();
        if (json == null || json.isBlank()) return list;
        try {
            List<Map<String, Object>> arr = om.readValue(json, List.class);
            for (Map<String, Object> m : arr) {
                Object mid = m.get("matId");
                Object q = m.get("qty");
                if (mid == null) continue;
                BomInput bi = new BomInput();
                bi.matId = ((Number) mid).intValue();
                bi.qty = (q == null) ? 0f : Float.parseFloat(String.valueOf(q));
                if (bi.qty > 0) list.add(bi);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("투입자재(bom_json) 형식 오류: " + e.getMessage());
        }
        return list;
    }
}