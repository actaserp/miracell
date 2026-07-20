package mes.app.production;

import mes.app.production.service.WorkDailyReportService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 작업일보 — 날짜별 · 공정별 작업량/작업자.
 * 생산공정(mat_produce) + 세척(v_wash_result) 통합 조회.
 */
@RestController
@RequestMapping("/api/production/work_daily")
public class WorkDailyReportController {

    @Autowired
    private WorkDailyReportService workDailyReportService;

    /** 요약 — 날짜 × 공정 */
    @GetMapping("/summary")
    public AjaxResult summary(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "process_pk", required = false) Integer processPk,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.workDailyReportService.getSummary(dateFrom, dateTo, processPk, spjangcd);
        return r;
    }

    /** 상세 — 날짜 × 공정 × 작업자 × 품목 (인쇄 본문) */
    @GetMapping("/detail")
    public AjaxResult detail(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "process_pk", required = false) Integer processPk,
            @RequestParam(value = "actor_pk", required = false) Integer actorPk,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.workDailyReportService.getDetail(dateFrom, dateTo, processPk, actorPk, spjangcd);
        return r;
    }

    /** 작업자별 집계 */
    @GetMapping("/by_worker")
    public AjaxResult byWorker(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam("spjangcd") String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.data = this.workDailyReportService.getByWorker(dateFrom, dateTo, spjangcd);
        return r;
    }
}
