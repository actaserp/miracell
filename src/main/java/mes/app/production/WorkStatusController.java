package mes.app.production;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.WorkStatusService;
import mes.domain.model.AjaxResult;

/**
 * 작업실적현황 API.
 *
 * ★ 기존 /api/production/prod_result_list 에 붙이지 않는다.
 *   저쪽은 작지 × 차수 집계(완료건만)라 축이 다르다. 손대면 지금 쓰는 화면이 흔들린다.
 *
 * ★ @ExceptionHandler 필수
 *   안 잡으면 SQL 오류에 스프링이 HTML 에러 페이지를 내리고
 *   AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
 */
@RestController
@RequestMapping("/api/production/work_status")
public class WorkStatusController {

    @Autowired
    private WorkStatusService workStatusService;

    // =================================================================
    // 1공장
    // =================================================================

    /**
     * A뷰·B뷰 한 방에. 부모 작지 + 공정별 자식 + 미전개 작지를 함께 내린다.
     *
     * 화면이 세 번 부르지 않게 묶은 이유 — 셋이 같은 기간을 보고
     * 따로 오면 그 사이 갱신으로 합계가 어긋날 수 있다.
     */
    @GetMapping("/f1_list")
    public AjaxResult f1List(
            @RequestParam(value = "date_from", required = false) String dateFrom,
            @RequestParam(value = "date_to", required = false) String dateTo,
            @RequestParam(value = "line", required = false) String line,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam("spjangcd") String spjangcd,
            HttpServletRequest request) {

        Map<String, Object> data = new HashMap<>();
        data.put("orders",  this.workStatusService.getF1Orders(dateFrom, dateTo, line, state, spjangcd));
        data.put("steps",   this.workStatusService.getF1Steps(dateFrom, dateTo, state, spjangcd));
        data.put("orphans", this.workStatusService.getF1Orphans(dateFrom, dateTo, spjangcd));
        // 멸균은 작지 자식이 아니라 로트 계보로 붙는다 (getSterilSteps 주석 참조)
        data.put("steril",  this.workStatusService.getSterilSteps(dateFrom, dateTo, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /**
     * B뷰 — 공정별 실적. 날짜는 실적일(mat_produce) 기준이고 필수다.
     * 생산형 차수 + 세척 + 멸균을 함께 내린다(세척·멸균은 mat_produce 가 없어 따로 온다).
     */
    @GetMapping("/f1_process")
    public AjaxResult f1Process(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "process_pk", required = false) Integer processPk,
            @RequestParam(value = "actor_pk", required = false) Integer actorPk,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("rows",   this.workStatusService.getProcessResults(dateFrom, dateTo, processPk, actorPk, 1, spjangcd));
        data.put("actors", this.workStatusService.getActorCombo(dateFrom, dateTo, 1, spjangcd));
        // 세척·멸균은 mat_produce 를 만들지 않는다. 공정 필터가 걸리면 해당될 때만 싣는다
        data.put("wash",   this.workStatusService.getWashList(dateFrom, dateTo, actorPk, spjangcd));
        data.put("steril", this.workStatusService.getSterilList(dateFrom, dateTo, spjangcd));
        data.put("defect", this.workStatusService.getDefectByMaterial(dateFrom, dateTo, 1, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 2공장 공정별 실적 */
    @GetMapping("/f2_process")
    public AjaxResult f2Process(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "process_pk", required = false) Integer processPk,
            @RequestParam(value = "actor_pk", required = false) Integer actorPk,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("rows",   this.workStatusService.getProcessResults(dateFrom, dateTo, processPk, actorPk, 2, spjangcd));
        data.put("actors", this.workStatusService.getActorCombo(dateFrom, dateTo, 2, spjangcd));
        data.put("defect", this.workStatusService.getDefectByMaterial(dateFrom, dateTo, 2, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 공정 콤보 */
    @GetMapping("/process_combo")
    public AjaxResult processCombo(@RequestParam(value = "factory_id", required = false) Integer factoryId) {
        AjaxResult result = new AjaxResult();
        result.data = this.workStatusService.getProcessCombo(factoryId);
        return result;
    }

    /**
     * 세척 — 작지 무관. 품목 집계(칸·카드용) + 상세 목록(모달용)을 함께 내린다.
     */
    @GetMapping("/f1_wash")
    public AjaxResult f1Wash(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "actor_pk", required = false) Integer actorPk,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("materials", this.workStatusService.getWashByMaterial(dateFrom, dateTo, spjangcd));
        data.put("rows", this.workStatusService.getWashList(dateFrom, dateTo, actorPk, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /**
     * 멸균 — 배치 나열 + 품목 집계(칸·카드용).
     */
    @GetMapping("/f1_steril")
    public AjaxResult f1Steril(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("materials", this.workStatusService.getSterilByMaterial(dateFrom, dateTo, spjangcd));
        data.put("batches", this.workStatusService.getSterilList(dateFrom, dateTo, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 멸균 배치에 담긴 로트 */
    @GetMapping("/f1_steril_items")
    public AjaxResult f1SterilItems(@RequestParam("batch_pk") Integer batchPk) {
        AjaxResult result = new AjaxResult();
        result.data = this.workStatusService.getSterilItems(batchPk);
        return result;
    }

    /** 셀 클릭 → 그 공정 작지의 차수(세션) + 연결된 부적합 */
    @GetMapping("/f1_session")
    public AjaxResult f1Session(@RequestParam("jr_pk") Integer jrPk) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessions", this.workStatusService.getF1Sessions(jrPk));
        data.put("defects", this.workStatusService.getF1SessionDefects(jrPk));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 부적합 — 품목 집계(카드·모달) + 작지 미연결 목록 */
    @GetMapping("/defect")
    public AjaxResult defect(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("materials", this.workStatusService.getDefectByMaterial(dateFrom, dateTo, factoryId, spjangcd));
        data.put("unlinked", this.workStatusService.getDefectUnlinked(dateFrom, dateTo, factoryId, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 작지 미연결 부적합만 (공정·일자 축) */
    @GetMapping("/defect_unlinked")
    public AjaxResult defectUnlinked(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = this.workStatusService.getDefectUnlinked(dateFrom, dateTo, factoryId, spjangcd);
        return result;
    }

    // =================================================================
    // 2공장
    // =================================================================

    @GetMapping("/f2_list")
    public AjaxResult f2List(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("orders", this.workStatusService.getF2Orders(dateFrom, dateTo, model, spjangcd));
        data.put("units", this.workStatusService.getF2Units(dateFrom, dateTo, model, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 유닛 1대의 검사 회차 */
    @GetMapping("/f2_inspects")
    public AjaxResult f2Inspects(@RequestParam("unit_pk") Integer unitPk) {
        AjaxResult result = new AjaxResult();
        result.data = this.workStatusService.getF2Inspects(unitPk);
        return result;
    }

    // =================================================================

    @ExceptionHandler(Exception.class)
    public AjaxResult onError(Exception e) {
        AjaxResult result = new AjaxResult();
        result.success = false;
        result.message = (e.getMessage() == null) ? "조회 중 오류가 발생했습니다" : e.getMessage();
        if (!(e instanceof IllegalArgumentException)) {
            e.printStackTrace();
        }
        return result;
    }
}