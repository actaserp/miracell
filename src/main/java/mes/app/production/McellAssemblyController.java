package mes.app.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.production.service.McellAssemblyService;
import mes.app.production.service.ProductionCreateService.BomInput;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M-CELL 조립 (2공장, mc01) 컨트롤러.
 *
 * 화면 흐름 : A 작업지시 큐 → B 유닛(1대=1로트) → C BOM 계층 스텝
 * 실적 생성은 전부 ProductionCreateService 로 내려간다(1공장과 동일 알맹이).
 */
@RestController
@RequestMapping("/api/production/mcell")
public class McellAssemblyController {

    @Autowired private McellAssemblyService mcellAssemblyService;
    private final ObjectMapper om = new ObjectMapper();

    // ── 조회 ─────────────────────────────────────────────

    @GetMapping("/context")
    public AjaxResult context(
            @RequestParam(value = "process_code", defaultValue = "mc01") String processCode,
            @RequestParam(value = "factory_id", defaultValue = "2") Integer factoryId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getContext(processCode, factoryId);
        return r;
    }

    /** A화면 — 작업지시(생산계획) 큐 */
    @GetMapping("/wo_queue")
    public AjaxResult woQueue(
            @RequestParam("process_id") Integer processId,
            @RequestParam(value = "date_from", required = false) String dateFrom,
            @RequestParam(value = "date_to", required = false) String dateTo,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getWoQueue(processId, spjangcd, dateFrom, dateTo);
        return r;
    }

    /** B화면 — 유닛 목록 */
    @GetMapping("/unit_list")
    public AjaxResult unitList(@RequestParam("job_res_id") Integer jobResId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getUnitList(jobResId);
        return r;
    }

    /** ★ 유닛 제품 번호 수정 (빈 값 = 임시번호 SN-001 로 복귀) */
    @PostMapping("/unit_serial")
    @Transactional
    public AjaxResult unitSerial(@RequestParam("unit_id") Integer unitId,
                                 @RequestParam(value = "serial_no", required = false) String serial,
                                 Authentication auth) {
        return this.mcellAssemblyService.setUnitSerial(unitId, serial, (User) auth.getPrincipal());
    }

    /** C화면 — 스텝 트리 (locked/skipped 포함) */
    @GetMapping("/step_list")
    public AjaxResult stepList(@RequestParam("unit_id") Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getStepList(unitId);
        return r;
    }

    /** 스텝 투입자재 기본값 (BOM 원자재 + 하위 어셈블리 표시) */
    @GetMapping("/step_materials")
    public AjaxResult stepMaterials(@RequestParam("step_id") Integer stepId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getStepMaterials(stepId);
        return r;
    }

    /** 재고 재공품 로트 (재고 투입 토글) */
    @GetMapping("/wip_lots")
    public AjaxResult wipLots(
            @RequestParam("material_id") Integer materialId,
            @RequestParam(value = "store_id", required = false) Integer storeId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getWipLots(materialId, storeId);
        return r;
    }

    /** 자재 추가 시트 — 생산창고 재고 */
    @GetMapping("/stock_list")
    public AjaxResult stockList(
            @RequestParam(value = "store_id", required = false) Integer storeId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getStockList(storeId, keyword);
        return r;
    }

    /** 작업자 목록 */
    @GetMapping("/workers")
    public AjaxResult workers() {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getWorkers();
        return r;
    }

    /** 조립 설비 목록 (워크센터 기준) */
    @GetMapping("/equipments")
    public AjaxResult equipments(
            @RequestParam(value = "workcenter_id", required = false) Integer workCenterId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getEquipments(workCenterId);
        return r;
    }

    /** BOM 트리 미리보기 (디버그/검증용) */
    @GetMapping("/bom_tree")
    public AjaxResult bomTree(
            @RequestParam("material_id") Integer materialId,
            @RequestParam(value = "prod_date", required = false) String prodDate) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.buildStepTree(materialId, prodDate);
        return r;
    }

    // ── 쓰기 ─────────────────────────────────────────────

    /** 작지 진입 시 유닛 N개 + 스텝 생성 (멱등) */
    @PostMapping("/unit_init")
    @Transactional
    public AjaxResult unitInit(
            @RequestParam("job_res_id") Integer jobResId,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        return this.mcellAssemblyService.initUnits(jobResId, spjangcd, (User) auth.getPrincipal());
    }

    /** 투입자재 편집분 즉시 저장 (수량 가감 · 삭제 · 품목 추가) */
    @PostMapping("/step_mat_save")
    @Transactional
    public AjaxResult stepMatSave(
            @RequestParam("step_id") Integer stepId,
            @RequestParam(value = "bom_json", required = false) String bomJson,
            Authentication auth) {
        return this.mcellAssemblyService.saveStepMaterials(stepId, bomJson, (User) auth.getPrincipal());
    }

    /** 조립 ↔ 재고 투입 전환 */
    @PostMapping("/step_source")
    @Transactional
    public AjaxResult stepSource(
            @RequestParam("step_id") Integer stepId,
            @RequestParam("source") String source,                       // build | stock
            @RequestParam(value = "mat_lot_id", required = false) Integer matLotId,
            Authentication auth) {
        return this.mcellAssemblyService.setStepSource(stepId, source, matLotId, (User) auth.getPrincipal());
    }

    /** 작업 시작 */
    @PostMapping("/step_start")
    @Transactional
    public AjaxResult stepStart(
            @RequestParam("step_id") Integer stepId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        return this.mcellAssemblyService.startStep(stepId, actorId, memberIds, equipmentId, startTime,
                spjangcd, (User) auth.getPrincipal());
    }

    /** 모듈/최종 완료 — 실적 생성 + BOM 차감 + 로트 입고 */
    @PostMapping("/step_finish")
    @Transactional
    public AjaxResult stepFinish(
            @RequestParam("step_id") Integer stepId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            @RequestParam(value = "bom_json", required = false) String bomJson,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        return this.mcellAssemblyService.finishStep(stepId, parseBom(bomJson), actorId, equipmentId,
                startTime, endTime, spjangcd, (User) auth.getPrincipal());
    }

    /** 완료취소 — 상위 스텝까지 캐스케이드 롤백 */
    @PostMapping("/step_cancel")
    @Transactional
    public AjaxResult stepCancel(
            @RequestParam("step_id") Integer stepId,
            Authentication auth) {
        return this.mcellAssemblyService.cancelStep(stepId, (User) auth.getPrincipal());
    }

    /** 작업 삭제 (working → wait) */
    @PostMapping("/step_delete")
    @Transactional
    public AjaxResult stepDelete(
            @RequestParam("step_id") Integer stepId,
            Authentication auth) {
        return this.mcellAssemblyService.deleteStepWork(stepId, (User) auth.getPrincipal());
    }

    /** 시작/완료 시각 수정 */
    @PostMapping("/step_time")
    @Transactional
    public AjaxResult stepTime(
            @RequestParam("step_id") Integer stepId,
            @RequestParam("which") String which,          // start | end
            @RequestParam("value") String value,          // 'yyyy-MM-dd HH:mm'
            Authentication auth) {
        return this.mcellAssemblyService.setStepTime(stepId, which, value, (User) auth.getPrincipal());
    }

    // ── ★ 주요자재 S/N (유닛 단위 — 다운로드와 어셈블리가 같은 기록을 쓴다) ──

    @GetMapping("/sn_list")
    public AjaxResult snList(@RequestParam(value = "unit_id", required = false) Integer unitId,
                             @RequestParam(value = "step_id", required = false) Integer stepId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getSnList(unitId, stepId);
        return r;
    }

    /** S/N 1칸 저장 (serial_no 가 비면 삭제). 어셈블리에서 입력하면 step_id 도 보낸다 */
    @PostMapping("/sn_save")
    @Transactional
    public AjaxResult snSave(
            @RequestParam(value = "unit_id", required = false) Integer unitId,
            @RequestParam(value = "step_id", required = false) Integer stepId,
            @RequestParam("material_id") Integer materialId,
            @RequestParam(value = "seq_no", defaultValue = "1") Integer seqNo,
            @RequestParam(value = "serial_no", required = false) String serialNo,
            @RequestParam(value = "mat_lot_id", required = false) Integer matLotId,
            @RequestParam(value = "part_lot", required = false) String partLot,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellAssemblyService.saveSn(unitId, stepId, materialId, seqNo, serialNo, matLotId, partLot, spjangcd,
                (User) auth.getPrincipal());
    }

    // ── ★ 프로그램 다운로드 (모든 조립의 첫 작업) ─────────

    // ── ★ 공정검사 (조립 중간 검사) ─────────────────────────
    @GetMapping("/pi_detail")
    public AjaxResult piDetail(@RequestParam("step_id") Integer stepId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getProcInsp(stepId);
        return r;
    }

    @PostMapping("/pi_start")
    @Transactional
    public AjaxResult piStart(@RequestParam("step_id") Integer stepId,
                              @RequestParam(value = "actor_id", required = false) Integer actorId,
                              @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
                              Authentication auth) {
        return this.mcellAssemblyService.startProcInsp(stepId, actorId, spjangcd, (User) auth.getPrincipal());
    }

    @PostMapping("/pi_item")
    @Transactional
    public AjaxResult piItem(@RequestParam("result_id") Integer resultId,
                             @RequestParam("item_id") Integer itemId,
                             @RequestParam(value = "result", required = false) String result,
                             @RequestParam(value = "value", required = false) String value,
                             Authentication auth) {
        return this.mcellAssemblyService.saveProcInspItem(resultId, itemId, result, value, (User) auth.getPrincipal());
    }

    @PostMapping("/pi_judge")
    @Transactional
    public AjaxResult piJudge(@RequestParam("result_id") Integer resultId,
                              @RequestParam(value = "fail_reason", required = false) String failReason,
                              Authentication auth) {
        return this.mcellAssemblyService.judgeProcInsp(resultId, failReason, (User) auth.getPrincipal());
    }

    @PostMapping("/pi_revise")
    @Transactional
    public AjaxResult piRevise(@RequestParam("result_id") Integer resultId, Authentication auth) {
        return this.mcellAssemblyService.reviseProcInsp(resultId, (User) auth.getPrincipal());
    }

    @GetMapping("/dl_detail")
    public AjaxResult dlDetail(@RequestParam("unit_id") Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getDownload(unitId);
        return r;
    }

    @PostMapping("/dl_start")
    @Transactional
    public AjaxResult dlStart(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellAssemblyService.startDownload(unitId, actorId, memberIds, equipmentId, startTime,
                spjangcd, (User) auth.getPrincipal());
    }

    @PostMapping("/dl_finish")
    @Transactional
    public AjaxResult dlFinish(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam(value = "end_time", required = false) String endTime,
            Authentication auth) {
        return this.mcellAssemblyService.finishDownload(unitId, endTime, (User) auth.getPrincipal());
    }

    @PostMapping("/dl_cancel")
    @Transactional
    public AjaxResult dlCancel(@RequestParam("unit_id") Integer unitId, Authentication auth) {
        return this.mcellAssemblyService.cancelDownload(unitId, (User) auth.getPrincipal());
    }

    /** ★ 보드 칸 «변경» 후보 = 이 제품 BOM 전체 자재 */
    @GetMapping("/dl_slot_cands")
    public AjaxResult dlSlotCands(@RequestParam("unit_id") Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellAssemblyService.getDlSlotCands(unitId);
        return r;
    }

    /** ★ 보드 칸 지정 (모델 단위) — mat_id 비우면 자동(품명) */
    @PostMapping("/dl_slot_save")
    @Transactional
    public AjaxResult dlSlotSave(@RequestParam("unit_id") Integer unitId, @RequestParam("slot") Integer slot,
                                 @RequestParam(value = "mat_id", required = false) Integer matId,
                                 Authentication auth) {
        return this.mcellAssemblyService.saveDlSlot(unitId, slot, matId, (User) auth.getPrincipal());
    }

    /** 카드에 올릴 보드 목록 (쉼표로 이은 자재 id) */
    @PostMapping("/dl_boards")
    @Transactional
    public AjaxResult dlBoards(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam(value = "mat_ids", required = false) String matIds,
            Authentication auth) {
        return this.mcellAssemblyService.saveDownloadBoards(unitId, matIds, (User) auth.getPrincipal());
    }

    @PostMapping("/dl_time")
    @Transactional
    public AjaxResult dlTime(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("which") String which,
            @RequestParam("value") String value,
            Authentication auth) {
        return this.mcellAssemblyService.setDownloadTime(unitId, which, value, (User) auth.getPrincipal());
    }

    // ── 예외 처리 ────────────────────────────────────────
    /**
     * 재고 부족 등 업무 예외를 500 스택트레이스 대신 화면 메시지로 내려준다.
     * @ExceptionHandler 는 트랜잭션 프록시 바깥(MVC 계층)에서 동작하므로
     * 롤백은 정상적으로 끝난 뒤 이 메서드가 실행된다.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public AjaxResult handleBusinessError(Exception e) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        r.message = (e.getMessage() == null || e.getMessage().isBlank())
                ? "처리 중 오류가 발생했습니다." : e.getMessage();
        return r;
    }

    // ── 유틸 ─────────────────────────────────────────────

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
                /* ★ 작업자가 고른 투입 로트. 그 로트에서 먼저 빼고, 모자라면 FIFO 가 이어 채운다
                     (ProductionCreateService 가 lots 를 지원한다). 없으면 기존처럼 FIFO. */
                Object lid = m.get("lotId");
                if (lid != null && !String.valueOf(lid).isBlank() && bi.qty > 0) {
                    bi.lots = new ArrayList<>();
                    bi.lots.add(new mes.app.production.service.ProductionCreateService.LotInput(
                            Integer.valueOf(String.valueOf(lid).replaceAll("\\.0$", "")), bi.qty));
                }
                if (bi.qty > 0) list.add(bi);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("투입자재(bom_json) 형식 오류: " + e.getMessage());
        }
        return list;
    }
}