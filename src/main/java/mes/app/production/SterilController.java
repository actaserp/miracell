package mes.app.production;

import mes.app.production.service.SterilService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 멸균(EO, bsc04) 컨트롤러 — 세척과 동일하게 별도 컨트롤러/서비스.
 * 2단 배치: steril_batch > steril_batch_item + 멸균일지(steril_batch_log).
 */
@RestController
@RequestMapping("/api/production/steril")
public class SterilController {

    @Autowired
    private SterilService sterilService;

    // ── 조회 ─────────────────────────────────────────────

    @GetMapping("/context")
    public AjaxResult context(@RequestParam(value = "factory_id", required = false) Integer factoryId) {
        AjaxResult r = new AjaxResult();
        r.data = this.sterilService.getContext(factoryId);
        return r;
    }

    /** 배치 큐 (날짜범위 + 상태) */
    @GetMapping("/batch_list")
    public AjaxResult batchList(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.sterilService.getBatchList(dateFrom, dateTo, state, spjangcd);
        return r;
    }

    /** 멸균 대기 = 클린룸 SterilizationYN='Y' 로트 (type: pk|bag 필터 옵션) */
    @GetMapping("/target_lots")
    public AjaxResult targetLots(
            @RequestParam(value = "item_type", required = false) String itemType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.sterilService.getTargetLots(itemType, keyword, spjangcd);
        return r;
    }

    /** 배치 상세 = 배치 + 담긴 로트 + 멸균일지 */
    @GetMapping("/batch_detail")
    public AjaxResult batchDetail(@RequestParam("batch_id") Integer batchId) {
        AjaxResult r = new AjaxResult();
        r.data = this.sterilService.getBatchDetail(batchId);
        return r;
    }

    // ── 배치/로트 ────────────────────────────────────────

    @PostMapping("/batch_start")
    public AjaxResult batchStart(
            @RequestParam("date") String date,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        // 담당자 미지정 시 로그인 사용자로 자동 지정
        Integer actor = (actorId != null) ? actorId : user.getId();
        return this.sterilService.batchStart(date, actor, equipmentId, spjangcd, user);
    }

    @PostMapping("/lot_add")
    public AjaxResult lotAdd(
            @RequestParam("batch_id") Integer batchId,
            @RequestParam("mat_lot_id") Integer matLotId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.lotAdd(batchId, matLotId, qty, spjangcd, user);
    }

    @PostMapping("/lot_remove")
    public AjaxResult lotRemove(
            @RequestParam("item_id") Integer itemId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.lotRemove(itemId, user);
    }

    /** 멸균일지 단계 저장(upsert) */
    @PostMapping("/log_save")
    public AjaxResult logSave(
            @RequestParam("batch_id") Integer batchId,
            @RequestParam("step_no") Integer stepNo,
            @RequestParam(value = "step_name", required = false) String stepName,
            @RequestParam(value = "data", required = false) String data,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.logSave(batchId, stepNo, stepName, data, user);
    }

    /** BI 판정 — pass(멸균필+이동, 업체 로트번호 부여) / fail(전량 폐기) */
    @PostMapping("/bi_judge")
    public AjaxResult biJudge(
            @RequestParam("batch_id") Integer batchId,
            @RequestParam("result") String result,
            @RequestParam(value = "maker_lot_no", required = false) String makerLotNo,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.biJudge(batchId, result, makerLotNo, spjangcd, user);
    }

    /** 판정 취소 — 재고 롤백 + 배치 '작업중' 복귀 (업체 로트번호 비움) */
    @PostMapping("/bi_cancel")
    public AjaxResult biCancel(
            @RequestParam("batch_id") Integer batchId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.biCancel(batchId, user);
    }

    /** 배치 기본정보 저장 — 시작/종료 시간 + 멸균기 */
    @PostMapping("/batch_time")
    public AjaxResult batchTime(
            @RequestParam("batch_id") Integer batchId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.setBatchTime(batchId, startTime, endTime, equipmentId, user);
    }

    @PostMapping("/batch_delete")
    public AjaxResult batchDelete(
            @RequestParam("batch_id") Integer batchId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.sterilService.batchDelete(batchId, user);
    }
}