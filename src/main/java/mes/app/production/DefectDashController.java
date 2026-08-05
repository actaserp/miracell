package mes.app.production;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.DefectDashService;
import mes.domain.model.AjaxResult;

/**
 * 생산불량현황(부적합 대시보드) API.
 *
 * ★ /api/production/defect/* (부적합 등록) 와 분리한다.
 *   저쪽은 등록·차감(쓰기), 이쪽은 읽기 전용 집계다.
 *
 * ★ @ExceptionHandler 필수 — 안 잡으면 SQL 오류에 스프링이 HTML 에러 페이지를
 *   내리고 AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
 *
 * ★ AjaxUtil 은 form-urlencoded 로 보낸다. @RequestBody(JSON) 쓰면 415.
 */
@RestController
@RequestMapping("/api/production/defect_dash")
public class DefectDashController {

    @Autowired
    private DefectDashService defectDashService;

    /** 필터 콤보 (공정 + 그 기간 부적합이 있는 품목) */
    @GetMapping("/combo")
    public AjaxResult combo(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId) {

        Map<String, Object> data = new HashMap<>();
        data.put("procs", this.defectDashService.getProcCombo(factoryId));
        data.put("items", this.defectDashService.getItemCombo(dateFrom, dateTo, factoryId));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /**
     * 대시보드 전체.
     *
     * 화면이 한 번만 부른다 — 패널마다 따로 부르면 그 사이 갱신으로
     * KPI 와 상세의 합계가 어긋난다.
     */
    @GetMapping("/list")
    public AjaxResult list(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "process_id", required = false) Integer processId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = this.defectDashService.getDashboard(
                dateFrom, dateTo, factoryId, processId, materialId, spjangcd);
        return result;
    }

    @ExceptionHandler(Exception.class)
    public AjaxResult handle(Exception e) {
        e.printStackTrace();
        AjaxResult result = new AjaxResult();
        result.success = false;
        result.message = "조회 중 오류가 발생했습니다: " + e.getMessage();
        return result;
    }
}