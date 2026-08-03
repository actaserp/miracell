package mes.app.production;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.McellWorkStatusService;
import mes.domain.model.AjaxResult;

/**
 * 2공장 M-CELL 작업실적현황 API.
 *
 * ★ 1공장(/api/production/work_status)과 분리한 이유
 *   실적의 단위가 다르다 — 1공장은 차수(mat_produce), 2공장은 유닛(mcell_unit).
 *   한 엔드포인트에 factory_id 로 분기하면 응답 모양이 공장마다 달라져
 *   화면이 if 문 범벅이 된다. 아예 나눈다.
 */
@RestController
@RequestMapping("/api/production/mcell_status")
public class McellWorkStatusController {

    @Autowired
    private McellWorkStatusService svc;

    /**
     * 공정별 실적 한 번에.
     *
     * 공정마다 실적이 남는 곳이 달라 네 덩어리로 내려간다.
     *   assembly 조립  mcell_unit
     *   inspect  검사  insp_result → 유닛 단위로 접음 (양식·회차는 상세에서)
     *   repair   수리  mcell_unit(McellRepair_id)
     *   pack     포장  mcell_unit.State='packed'  ※ 공정 미구현
     */
    @GetMapping("/process")
    public AjaxResult process(
            @RequestParam("date_from") String dateFrom,
            @RequestParam("date_to") String dateTo,
            @RequestParam(value = "actor_pk", required = false) Integer actorPk,
            @RequestParam("spjangcd") String spjangcd) {

        Map<String, Object> data = new HashMap<>();
        data.put("assembly", this.svc.getAssemblyUnits(dateFrom, dateTo, actorPk, spjangcd));
        data.put("inspect",  this.svc.getInspectUnits(dateFrom, dateTo, actorPk, spjangcd));
        data.put("repair",   this.svc.getRepairUnits(dateFrom, dateTo, actorPk, spjangcd));
        data.put("pack",     this.svc.getPackUnits(dateFrom, dateTo, actorPk, spjangcd));
        data.put("defect",   this.svc.getDefectByMaterial(dateFrom, dateTo, spjangcd));
        data.put("actors",   this.svc.getActorCombo(dateFrom, dateTo, spjangcd));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 조립·수리 유닛 상세 — 스텝 + 투입자재 + 검사 회차 */
    @GetMapping("/unit_detail")
    public AjaxResult unitDetail(@RequestParam("unit_pk") Integer unitPk) {
        Map<String, Object> data = new HashMap<>();
        data.put("steps",    this.svc.getUnitSteps(unitPk));
        data.put("consumed", this.svc.getUnitConsumed(unitPk));
        data.put("inspects", this.svc.getUnitInspects(unitPk));
        data.put("mats",     this.svc.getRepairMats(unitPk));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 검사 유닛 상세 — 양식별(ROTOR/BASKET) 묶음 + 회차 */
    @GetMapping("/unit_inspect")
    public AjaxResult unitInspect(@RequestParam("unit_pk") Integer unitPk) {
        Map<String, Object> data = new HashMap<>();
        data.put("forms",  this.svc.getUnitInspectForms(unitPk));
        data.put("tries",  this.svc.getUnitInspects(unitPk));

        AjaxResult result = new AjaxResult();
        result.data = data;
        return result;
    }

    /** 검사 회차 상세 — 항목별 결과 */
    @GetMapping("/inspect_items")
    public AjaxResult inspectItems(@RequestParam("result_pk") Integer resultPk) {
        AjaxResult result = new AjaxResult();
        result.data = this.svc.getInspectItems(resultPk);
        return result;
    }

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