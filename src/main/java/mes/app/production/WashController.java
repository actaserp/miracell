package mes.app.production;

import mes.app.production.service.WashService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 세척(bsc01) 컨트롤러.
 * 기존 /api/production/prod_result 의 wash_* 엔드포인트는 작지 기반이라 폐기 대상.
 */
@RestController
@RequestMapping("/api/production/wash")
public class WashController {

    @Autowired
    private WashService washService;

    // ── 조회 ─────────────────────────────────────────────

    /**
     * 세척 공정 컨텍스트 (워크센터 id 등).
     * 화면이 설비 콤보를 부를 때 cond3 로 쓸 WorkCenter_id 를 여기서 받는다.
     */
    @GetMapping("/context")
    public AjaxResult context(
            @RequestParam(value = "factory_id", required = false) Integer factoryId) {
        AjaxResult r = new AjaxResult();
        r.data = this.washService.getContext(factoryId);
        return r;
    }

    /** 날짜 카드 목록 */
    @GetMapping("/day_list")
    public AjaxResult dayList(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.washService.getDayList(dateFrom, dateTo, spjangcd);
        return r;
    }

    /** 특정 날짜의 작업(작업자 카드) 목록 */
    @GetMapping("/work_list")
    public AjaxResult workList(
            @RequestParam("date") String date,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.washService.getWorkList(date, spjangcd);
        return r;
    }

    /** 작업의 품목 행 목록 */
    @GetMapping("/item_list")
    public AjaxResult itemList(@RequestParam("work_id") Integer workId) {
        AjaxResult r = new AjaxResult();
        r.data = this.washService.getItemList(workId);
        return r;
    }

    /** 세척 대상 품목 (WashYN='Y' + 생산창고 재고>0) */
    @GetMapping("/target_materials")
    public AjaxResult targetMaterials(
            @RequestParam(value = "work_id", required = false) Integer workId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.washService.getTargetMaterials(workId, keyword, spjangcd);
        return r;
    }

    // ── 작업 ─────────────────────────────────────────────

    @PostMapping("/work_start")
    public AjaxResult workStart(
            @RequestParam("date") String date,
            @RequestParam("actor_id") Integer actorId,
            @RequestParam(value = "member_ids", required = false) String memberIds,
            @RequestParam("equipment_id") Integer equipmentId,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.workStart(date, actorId, memberIds, equipmentId, spjangcd, user);
    }

    @PostMapping("/work_delete")
    public AjaxResult workDelete(
            @RequestParam("work_id") Integer workId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.workDelete(workId, user);
    }

    // ── 품목 행 ──────────────────────────────────────────

    @PostMapping("/item_add")
    public AjaxResult itemAdd(
            @RequestParam("work_id") Integer workId,
            @RequestParam("mat_id") Integer matId,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemAdd(workId, matId, spjangcd, user);
    }

    @PostMapping("/item_save")
    public AjaxResult itemSave(
            @RequestParam("item_id") Integer itemId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            @RequestParam(value = "description", required = false) String description,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemSave(itemId, qty, defectQty, description, user);
    }

    /** 시작 — 화면이 들고 있던 수량을 같이 저장한다 */
    @PostMapping("/item_start")
    public AjaxResult itemStart(
            @RequestParam("item_id") Integer itemId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemStart(itemId, qty, defectQty, user);
    }

    /** ★ 완료 = 재고 이동(생산창고→클린룸) + equ_run */
    @PostMapping("/item_finish")
    public AjaxResult itemFinish(
            @RequestParam("item_id") Integer itemId,
            @RequestParam(value = "qty", required = false) Float qty,
            @RequestParam(value = "defect_qty", required = false) Float defectQty,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            @RequestParam("spjangcd") String spjangcd,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemFinish(itemId, qty, defectQty, startTime, endTime, user, spjangcd);
    }

    @PostMapping("/item_cancel")
    public AjaxResult itemCancel(
            @RequestParam("item_id") Integer itemId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemCancel(itemId, user);
    }

    @PostMapping("/item_delete")
    public AjaxResult itemDelete(
            @RequestParam("item_id") Integer itemId,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemDelete(itemId, user);
    }

    /** 시간 수정 (PC 전용) */
    @PostMapping("/item_update_time")
    public AjaxResult itemUpdateTime(
            @RequestParam("item_id") Integer itemId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return this.washService.itemUpdateTime(itemId, startTime, endTime, user);
    }
}