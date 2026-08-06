package mes.app.production;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.ProdResultService;
import mes.domain.model.AjaxResult;

/**
 * 생산실적 — 화면 4개 공용.
 *
 *   /period    기간별생산실적
 *   /material  제품별생산실적
 *   /summary   생산실적집계표
 *   /process   공정별작업현황
 *
 * ★ base path 가 prod_result_v2 인 이유
 *   /api/production/prod_result 는 ProductionResultController 가 이미 쓰고 있다.
 *   같은 경로로 올리면 기동 시 Ambiguous mapping 으로 애플리케이션이 뜨지 않는다.
 *   그쪽 매핑을 옮기면 그걸 보는 화면들이 조용히 깨지므로 이쪽이 비켜난다.
 *
 * 기존 ProdResultListController(/api/production/prod_result_list) 도 그대로 둔다.
 * 폴더 52 의 구버전 화면들이 아직 그쪽을 본다.
 */
@RestController
@RequestMapping("/api/production/prod_result_v2")
public class ProdResultController {

    @Autowired
    private ProdResultService prodResultService;

    /** 기간별생산실적 — 행 = 실적 1건 */
    @GetMapping("/period")
    public AjaxResult getPeriodList(
            @RequestParam(value = "date_from") String dateFrom,
            @RequestParam(value = "date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "proc_code", required = false) String procCode,
            @RequestParam(value = "mat_grp_id", required = false) Integer matGrpId,
            @RequestParam(value = "mat_type", required = false) String matType,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getPeriodList(
                dateFrom, dateTo, factoryId, procCode, matGrpId, matType, spjangcd);
        return result;
    }

    /** 공정별작업현황 — 공정으로 접음 (세척·멸균·2공장 포함) */
    @GetMapping("/process")
    public AjaxResult getProcessList(
            @RequestParam(value = "date_from") String dateFrom,
            @RequestParam(value = "date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "proc_code", required = false) String procCode,
            @RequestParam(value = "mat_grp_id", required = false) Integer matGrpId,
            @RequestParam(value = "mat_type", required = false) String matType,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getProcessList(
                dateFrom, dateTo, factoryId, procCode, matGrpId, matType, spjangcd);
        return result;
    }

    /** 제품별생산실적 — 품목으로 접음 */
    @GetMapping("/material")
    public AjaxResult getMaterialList(
            @RequestParam(value = "date_from") String dateFrom,
            @RequestParam(value = "date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "proc_code", required = false) String procCode,
            @RequestParam(value = "mat_grp_id", required = false) Integer matGrpId,
            @RequestParam(value = "mat_type", required = false) String matType,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getMaterialList(
                dateFrom, dateTo, factoryId, procCode, matGrpId, matType, spjangcd);
        return result;
    }

    /** 생산실적집계표 — 품목 × 12개월. data_div: qty | money */
    @GetMapping("/summary")
    public AjaxResult getMonthSummary(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "mat_grp_id", required = false) Integer matGrpId,
            @RequestParam(value = "mat_type", required = false) String matType,
            @RequestParam(value = "data_div", required = false) String dataDiv,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getMonthSummary(
                year, factoryId, matGrpId, matType, dataDiv, spjangcd);
        return result;
    }

    /** 투입 자재 — produce / mcpack 행의 상세 */
    @GetMapping("/consumed_list")
    public AjaxResult getConsumedList(
            @RequestParam("mp_pk") int mpPk,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getConsumedList(mpPk);
        return result;
    }

    /** 부적합 내역 — 공정/품목으로 좁혀서 */
    @GetMapping("/defect_list")
    public AjaxResult getDefectList(
            @RequestParam(value = "date_from") String dateFrom,
            @RequestParam(value = "date_to") String dateTo,
            @RequestParam(value = "proc_code", required = false) String procCode,
            @RequestParam(value = "mat_id", required = false) Integer matId,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getDefectList(dateFrom, dateTo, procCode, matId, spjangcd);
        return result;
    }

    /** 공정 콤보 */
    @GetMapping("/process_combo")
    public AjaxResult getProcessCombo(
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = this.prodResultService.getProcessCombo(factoryId);
        return result;
    }

    /**
     * SQL 오류까지 전부 AjaxResult 로 변환한다.
     * 안 잡으면 스프링이 HTML 에러 페이지를 내리고
     * AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handle(Exception e) {
        AjaxResult result = new AjaxResult();
        result.success = false;
        result.message = e.getMessage();
        e.printStackTrace();
        return result;
    }
}