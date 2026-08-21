package mes.app.production;

import mes.app.production.service.McellInspectService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * M-CELL 검사 (2공장, mc02) — /api/production/inspect/*
 *
 * 양식(기준정보)과 실적을 한 컨트롤러에서 처리한다.
 * 별도 기준정보 화면 없이 검사 화면 상단 «양식» 버튼(PC 접속)에서 관리.
 */
@RestController
@RequestMapping("/api/production/inspect")
public class McellInspectController {

    @Autowired private McellInspectService mcellInspectService;

    // ── 양식 (기준정보) ──────────────────────────────────

    @GetMapping("/form_list")
    public AjaxResult formList(@RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getFormList(spjangcd);
        return r;
    }

    @GetMapping("/form_detail")
    public AjaxResult formDetail(@RequestParam("form_id") Integer formId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getFormDetail(formId);
        return r;
    }

    /** 적용 가능 품목 (InspectYN='Y') */
    @GetMapping("/form_materials")
    public AjaxResult formMaterials() {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getInspectMaterials();
        return r;
    }

    @PostMapping("/form_save")
    @Transactional
    public AjaxResult formSave(
            @RequestParam(value = "form_id", required = false) Integer formId,
            @RequestParam(value = "form_code", required = false) String code,
            @RequestParam("form_name") String name,
            @RequestParam(value = "use_yn", defaultValue = "Y") String useYn,
            @RequestParam(value = "seq_no", required = false) Integer seqNo,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "items_json", required = false) String itemsJson,
            @RequestParam(value = "mat_ids_json", required = false) String matIdsJson,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.saveForm(formId, code, name, useYn, seqNo, description,
                itemsJson, matIdsJson, spjangcd, (User) auth.getPrincipal());
    }

    @PostMapping("/form_delete")
    @Transactional
    public AjaxResult formDelete(@RequestParam("form_id") Integer formId, Authentication auth) {
        return this.mcellInspectService.deleteForm(formId, (User) auth.getPrincipal());
    }

    // ── 실적 조회 ────────────────────────────────────────

    /** A화면 — 검사 대상 작지 */
    @GetMapping("/wo_queue")
    public AjaxResult woQueue(
            @RequestParam(value = "date_from", required = false) String dateFrom,
            @RequestParam(value = "date_to", required = false) String dateTo,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getWoQueue(spjangcd, dateFrom, dateTo);
        return r;
    }

    /** B화면 — 유닛 목록 (양식별 상태 포함) */
    @GetMapping("/unit_list")
    public AjaxResult unitList(@RequestParam("job_res_id") Integer jobResId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getUnitList(jobResId);
        return r;
    }

    /** C화면 — 양식 탭 하나의 현재 회차 상세 (양식 + 결과 + 입력값 + 이력) */
    @GetMapping("/result_detail")
    public AjaxResult resultDetail(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("form_id") Integer formId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getResultDetail(unitId, formId);
        return r;
    }

    /** 이력 회차의 항목 결과 (읽기전용 상세) */
    @GetMapping("/result_items")
    public AjaxResult resultItems(@RequestParam("result_id") Integer resultId) {
        AjaxResult r = new AjaxResult();
        r.data = this.mcellInspectService.getResultItems(resultId);
        return r;
    }

    // ── 실적 쓰기 ────────────────────────────────────────

    /** 검사 시작 / 검사자 배정 */
    @PostMapping("/result_start")
    @Transactional
    public AjaxResult resultStart(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("form_id") Integer formId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.startResult(unitId, formId, actorId, memberIds, spjangcd,
                (User) auth.getPrincipal());
    }

    /** 항목 결과 저장 (합/불 · 측정값) */
    @PostMapping("/item_save")
    @Transactional
    public AjaxResult itemSave(
            @RequestParam("result_id") Integer resultId,
            @RequestParam("form_item_id") Integer formItemId,
            @RequestParam(value = "seq_no", required = false) Integer seqNo,
            @RequestParam(value = "repeat_no", defaultValue = "1") Integer repeatNo,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "value", required = false) String value,
            @RequestParam(value = "spec_label", required = false) String specLabel,
            @RequestParam(value = "spec_tool", required = false) String specTool,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.saveItem(resultId, formItemId, seqNo, repeatNo,
                result, value, specLabel, specTool, actorId, spjangcd, (User) auth.getPrincipal());
    }

    /** 항목 결과 삭제 (회차 기록 취소) */
    @PostMapping("/item_delete")
    @Transactional
    public AjaxResult itemDelete(
            @RequestParam("result_id") Integer resultId,
            @RequestParam("form_item_id") Integer formItemId,
            @RequestParam(value = "repeat_no", defaultValue = "1") Integer repeatNo) {
        return this.mcellInspectService.deleteItem(resultId, formItemId, repeatNo);
    }

    /** 양식 확정 → 유닛 최종 판정 재계산 (합격 시 검사완료창고 이동) */
    @PostMapping("/judge")
    @Transactional
    public AjaxResult judge(
            @RequestParam("result_id") Integer resultId,
            @RequestParam(value = "fail_reason", required = false) String failReason,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.judgeForm(resultId, failReason, spjangcd,
                (User) auth.getPrincipal());
    }

    /** 검사 회차(이력) 삭제 → 유닛 판정 재계산 */
    @PostMapping("/result_delete")
    @Transactional
    public AjaxResult resultDelete(
            @RequestParam("result_id") Integer resultId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.deleteResult(resultId, spjangcd, (User) auth.getPrincipal());
    }

    /** 재검사 — 새 회차 (이전 회차는 이력 보존) */
    @PostMapping("/recheck")
    @Transactional
    public AjaxResult recheck(
            @RequestParam("unit_id") Integer unitId,
            @RequestParam("form_id") Integer formId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {
        return this.mcellInspectService.recheck(unitId, formId, actorId, memberIds, spjangcd,
                (User) auth.getPrincipal());
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public AjaxResult handleBusinessError(Exception e) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        r.message = (e.getMessage() == null || e.getMessage().isBlank())
                ? "처리 중 오류가 발생했습니다." : e.getMessage();
        return r;
    }
}