package mes.app.production;

import mes.app.production.service.McellRepairService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * M-CELL 수리 (2공장, mc04) — /api/production/repair/*
 *
 * 화면 흐름 : A 수리 작업지시 큐 → B 유닛(1대=원 로트) → C 자재 가감·수리
 * 접수 화면이 반품입고를 겸한다(별도 입고 화면 없음).
 */
@RestController
@RequestMapping("/api/production/repair")
public class McellRepairController {

    private static final Logger log = LoggerFactory.getLogger(McellRepairController.class);

    @Autowired private McellRepairService mcellRepairService;

    // ── 조회 ─────────────────────────────────────────────

    @GetMapping("/context")
    public AjaxResult context() {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getContext();
        return r;
    }

    /** A화면 — 수리 접수 큐 */
    @GetMapping("/wo_queue")
    public AjaxResult woQueue(
            @RequestParam(value = "cat", required = false) String cat,          // return | spec | all
            @RequestParam(value = "flag", required = false) String flag,        // reject | open | all
            @RequestParam(value = "date_from", required = false) String dateFrom,
            @RequestParam(value = "date_to", required = false) String dateTo,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getWoQueue(spjangcd, cat, flag, dateFrom, dateTo);
        return r;
    }

    /** C화면 — 유닛 상세(헤더 + 자재 가감 + 결과 로트 미리보기) */
    @GetMapping("/unit_detail")
    public AjaxResult unitDetail(@RequestParam("unit_id") Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getUnitDetail(unitId);
        return r;
    }

    /** 접수 모달 — 스캔값으로 원 M-CELL 찾기 (사내 로트 + 외부 라벨 동시 조회) */
    @GetMapping("/src_lot_search")
    public AjaxResult srcLotSearch(@RequestParam("key") String key) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.searchSrcLot(key);
        return r;
    }

    /** 자재 시트 — 생산창고(17) 재고 */
    @GetMapping("/stock_list")
    public AjaxResult stockList(@RequestParam(value = "keyword", required = false) String keyword) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getStockList(keyword);
        return r;
    }

    /** −회수 후보 — 원 로트에 실제 투입됐던 자재 */
    @GetMapping("/consumed_list")
    public AjaxResult consumedList(@RequestParam("unit_id") Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getConsumedList(unitId);
        return r;
    }

    /** 사양변경 대상 품목 후보 */
    @GetMapping("/target_materials")
    public AjaxResult targetMaterials(@RequestParam(value = "keyword", required = false) String keyword) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getTargetMaterials(keyword);
        return r;
    }

    @GetMapping("/workers")
    public AjaxResult workers() {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getWorkers();
        return r;
    }

    /** 수리 설비 목록 (수리 워크센터에 등록된 것만) */
    @GetMapping("/equipments")
    public AjaxResult equipments() {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellRepairService.getEquipments();
        return r;
    }

    // ── 접수 ─────────────────────────────────────────────

    /**
     * 수리 접수 — 작지 + 유닛 + 수리창고 재고 확보를 한 번에.
     *
     * mat_lot_id 가 있으면 그 로트로 확정(재고>0 이면 이동, 0 이면 신규 입고).
     * 없으면 미등록 취급 → material_id 필수.
     */
    @PostMapping("/regist")
    @Transactional
    public AjaxResult regist(
            @RequestParam("cat") String cat,                                         // return | spec
            @RequestParam("scan_key") String scanKey,
            @RequestParam(value = "mat_lot_id", required = false) Integer matLotId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            @RequestParam(value = "target_material_id", required = false) Integer targetMaterialId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "receipt_date", required = false) String receiptDate,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellRepairService.regist(cat, scanKey, matLotId, materialId, targetMaterialId,
                reason, receiptDate, spjangcd, (User) auth.getPrincipal());
    }

    /** 접수 취소 (수리 시작 전) — 확보한 재고도 되돌린다 */
    @PostMapping("/regist_cancel")
    @Transactional
    public AjaxResult registCancel(
            @RequestParam("repair_id") Integer repairId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellRepairService.registCancel(repairId, spjangcd, (User) auth.getPrincipal());
    }

    /** 사양변경 대상 품목 지정 (job_res.Material_id 도 같이 바뀐다) */
    @PostMapping("/target_set")
    @Transactional
    public AjaxResult targetSet(
            @RequestParam("repair_id") Integer repairId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            Authentication auth) {
        return this.mcellRepairService.setTargetMaterial(repairId, materialId, (User) auth.getPrincipal());
    }

    // ── 유닛 설정 / 자재 ─────────────────────────────────

    /** 결과 로트 방식 — keep(원 로트 유지) | new(새 로트 발번) */
    @PostMapping("/lot_mode")
    @Transactional
    public AjaxResult lotMode(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("mode") String mode,
            Authentication auth) {
        return this.mcellRepairService.setLotMode(unitId, mode, (User) auth.getPrincipal());
    }

    /** 자재 담기 — dir '+' 추가투입 / '-' 차감회수. −는 원 부품로트까지 함께 기록한다 */
    @PostMapping("/mat_add")
    @Transactional
    public AjaxResult matAdd(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("material_id") Integer materialId,
            @RequestParam("dir") String dir,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "src_mat_lot_id", required = false) Integer srcMatLotId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellRepairService.matAdd(unitId, materialId, dir, qty, srcMatLotId, spjangcd,
                (User) auth.getPrincipal());
    }

    @PostMapping("/mat_qty")
    @Transactional
    public AjaxResult matQty(
            @RequestParam("rmat_id") Integer rmatId,
            @RequestParam("qty") Float qty,
            Authentication auth) {
        return this.mcellRepairService.matQty(rmatId, qty, (User) auth.getPrincipal());
    }

    @PostMapping("/mat_del")
    @Transactional
    public AjaxResult matDel(
            @RequestParam("rmat_id") Integer rmatId,
            Authentication auth) {
        return this.mcellRepairService.matDel(rmatId, (User) auth.getPrincipal());
    }

    // ── 수리 실적 ────────────────────────────────────────

    /** 수리 시작 — 작업자·설비 배정. 수리중 재배정에도 같은 엔드포인트를 쓴다. */
    @PostMapping("/unit_start")
    @Transactional
    public AjaxResult unitStart(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "start_time", required = false) String startTime,
            Authentication auth) {
        return this.mcellRepairService.unitStart(unitId, actorId, equipmentId, startTime,
                (User) auth.getPrincipal());
    }

    /** 시작취소 (실적 생성 전) */
    @PostMapping("/unit_cancel")
    @Transactional
    public AjaxResult unitCancel(
            @RequestParam("unit_id") Integer unitId,
            Authentication auth) {
        return this.mcellRepairService.unitCancel(unitId, (User) auth.getPrincipal());
    }

    /** 수리 완료 · 검사 전달 — 원로트 소비 + ＋자재 소비 + 결과로트 입고 + −자재 회수 */
    @PostMapping("/unit_finish")
    @Transactional
    public AjaxResult unitFinish(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellRepairService.unitFinish(unitId, startTime, endTime, spjangcd,
                (User) auth.getPrincipal());
    }

    /** 시작/완료 시각 수정 — 'yyyy-MM-dd HH:mm' */
    @PostMapping("/unit_time")
    @Transactional
    public AjaxResult unitTime(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("which") String which,          // start | end
            @RequestParam("value") String value,
            Authentication auth) {
        return this.mcellRepairService.setUnitTime(unitId, which, value, (User) auth.getPrincipal());
    }

    /** 검사취소 · 수리 복귀 — 실적 롤백 후 repairing 으로 되돌림 */
    @PostMapping("/unit_reopen")
    @Transactional
    public AjaxResult unitReopen(
            @RequestParam("unit_id") Integer unitId,
            Authentication auth) {
        return this.mcellRepairService.unitReopen(unitId, (User) auth.getPrincipal());
    }

    // ── 예외 처리 ────────────────────────────────────────
    /**
     * 업무 예외뿐 아니라 SQL 오류까지 전부 AjaxResult 로 변환한다.
     *
     * 여기서 안 잡으면 스프링이 에러 페이지(HTML)를 내려주고,
     * AjaxUtil 이 그걸 파싱하지 못해 «페이지를 찾을 수 없습니다» 네이티브 alert 를 띄운다.
     * 화면 토스트로 보이게 하려면 어떤 예외든 AjaxResult 로 돌려줘야 한다.
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleBusinessError(Exception e) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        boolean business = (e instanceof IllegalStateException) || (e instanceof IllegalArgumentException);
        if (!business) {
            // 업무 예외가 아니면 원인을 서버 로그에 남긴다 (화면엔 요약만)
            log.error("[repair] 처리 중 오류", e);
        }
        String msg = e.getMessage();
        r.message = business
                ? ((msg == null || msg.isBlank()) ? "처리 중 오류가 발생했습니다." : msg)
                : ("서버 오류 : " + e.getClass().getSimpleName()
                + ((msg == null || msg.isBlank()) ? "" : " · " + msg.split("\n")[0]));
        return r;
    }
}