package mes.app.production;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.WorklogService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/**
 * 작업일보 — 작업 기록 CRUD + 실적 일부 수정.
 *
 * ★ 실적을 새로 만들거나 지우는 엔드포인트는 두지 않는다.
 *   실적 입력은 공정관리(세척·조립·검사·포장)와 POP 의 몫이다.
 *   여기서 또 만들면 BOM 차감·로트 입고를 건너뛴 실적이 생겨 재고가 어긋난다.
 *
 *   나중에 "일보에서도 실적을 등록해야 한다"로 확정되면
 *   각 공정의 정식 생성 서비스(ProductionCreateService 등)를 호출하는
 *   엔드포인트를 여기에 덧붙인다 — 지금 구조를 뒤집을 필요는 없다.
 */
@RestController
@RequestMapping("/api/production/worklog")
public class WorklogController {

    @Autowired
    private WorklogService svc;

    // ── 작업 기록 ─────────────────────────────────────────

    /** 그날의 작업 기록. factory_id 를 비우면 두 공장 모두(통합 일보) */
    @GetMapping("/notes")
    public AjaxResult notes(
            @RequestParam("date") String date,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {

        AjaxResult r = new AjaxResult();
        r.data = this.svc.getNotes(date, factoryId, spjangcd);
        return r;
    }

    /** 등록 · 수정 (pk 가 있으면 수정) */
    @PostMapping("/note_save")
    @Transactional
    public AjaxResult noteSave(
            @RequestParam(value = "pk", required = false) Integer pk,
            @RequestParam("date") String date,
            @RequestParam(value = "factory_id", required = false) Integer factoryId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("content") String content,
            @RequestParam(value = "process_id", required = false) Integer processId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
            Authentication auth) {

        return this.svc.saveNote(pk, date, factoryId, category, content,
                processId, equipmentId, actorId, spjangcd, (User) auth.getPrincipal());
    }

    @PostMapping("/note_delete")
    @Transactional
    public AjaxResult noteDelete(
            @RequestParam("pk") Integer pk,
            Authentication auth) {
        return this.svc.deleteNote(pk, (User) auth.getPrincipal());
    }

    // ── 실적 일부 수정 ───────────────────────────────────

    /**
     * 실적 수정 — 작업자·설비·시각만.
     *
     * kind 로 대상 테이블을 가른다. 공정마다 실적이 남는 곳이 달라서다.
     *   produce  mat_produce      조립·블리스터·융착·포장
     *   wash     wash_work_item   세척
     *   unit     mcell_unit       M-CELL 조립·수리
     *
     * ★ 수량·품목·로트는 받지 않는다.
     *   바꾸려면 차감을 되돌리고 다시 빼야 하는데 그건 삭제 후 재등록과 같다.
     *   경로를 둘로 두면 롤백 로직이 두 벌이 되고, 어긋나면 재고가 틀어진다.
     */
    @PostMapping("/result_update")
    @Transactional
    public AjaxResult resultUpdate(
            @RequestParam("kind") String kind,
            @RequestParam("pk") Integer pk,
            @RequestParam(value = "actor_id", required = false) Integer actorId,
            @RequestParam(value = "equipment_id", required = false) Integer equipmentId,
            @RequestParam(value = "start_time", required = false) String startTime,
            @RequestParam(value = "end_time", required = false) String endTime,
            Authentication auth) {

        User user = (User) auth.getPrincipal();
        switch (kind == null ? "" : kind) {
            case "produce": return this.svc.updateProduce(pk, actorId, equipmentId, startTime, endTime, user);
            case "wash":    return this.svc.updateWash(pk, actorId, equipmentId, startTime, endTime, user);
            case "unit":    return this.svc.updateUnit(pk, actorId, equipmentId, startTime, endTime, user);
            default:
                AjaxResult r = new AjaxResult();
                r.success = false;
                r.message = "수정할 수 없는 대상입니다.";
                return r;
        }
    }

    // ── 콤보 ─────────────────────────────────────────────

    @GetMapping("/combo")
    public AjaxResult combo(@RequestParam(value = "factory_id", required = false) Integer factoryId) {
        Map<String, Object> data = new HashMap<>();
        data.put("processes",  this.svc.getProcessCombo(factoryId));
        data.put("workers",    this.svc.getWorkerCombo());
        data.put("equipments", this.svc.getEquipmentCombo());

        AjaxResult r = new AjaxResult();
        r.data = data;
        return r;
    }

    // =================================================================

    @ExceptionHandler(Exception.class)
    public AjaxResult onError(Exception e) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        boolean business = (e instanceof IllegalStateException) || (e instanceof IllegalArgumentException);
        if (!business) e.printStackTrace();
        String msg = e.getMessage();
        r.message = business
                ? ((msg == null || msg.isBlank()) ? "처리 중 오류가 발생했습니다." : msg)
                : ("서버 오류 : " + e.getClass().getSimpleName());
        return r;
    }
}