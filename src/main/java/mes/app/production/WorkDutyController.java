package mes.app.production;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.WorkDutyService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/**
 * 검사 업무 관리 API — 로그인한 본인 것만 다룬다(사람을 고르지 않는다).
 * ★ AjaxUtil 은 form-urlencoded. @RequestBody 쓰면 415.
 */
@RestController
@RequestMapping("/api/work_duty")
public class WorkDutyController {

    @Autowired
    private WorkDutyService workDutyService;

    private Integer uid(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        throw new IllegalStateException("로그인 정보가 없습니다. 다시 로그인하세요.");
    }

    @GetMapping("/status")
    public AjaxResult status(@RequestParam(value = "date", required = false) String date,
                             Authentication auth) {
        AjaxResult r = new AjaxResult();
        r.data = this.workDutyService.getStatus(uid(auth), date);
        return r;
    }

    @PostMapping("/start")
    public AjaxResult start(@RequestParam(value = "task", required = false) String task,
                            @RequestParam(value = "memo", required = false) String memo,
                            @RequestParam(value = "spjangcd", required = false) String spjangcd,
                            Authentication auth) {
        return this.workDutyService.start(uid(auth), task, memo, spjangcd);
    }

    @PostMapping("/end")
    public AjaxResult end(@RequestParam(value = "memo", required = false) String memo,
                          Authentication auth) {
        return this.workDutyService.end(uid(auth), memo);
    }

    @PostMapping("/update")
    public AjaxResult update(@RequestParam("id") Integer id,
                             @RequestParam(value = "task", required = false) String task,
                             @RequestParam(value = "start", required = false) String start,
                             @RequestParam(value = "end", required = false) String end,
                             @RequestParam(value = "memo", required = false) String memo,
                             Authentication auth) {
        return this.workDutyService.update(uid(auth), id, task, start, end, memo);
    }

    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam("id") Integer id, Authentication auth) {
        return this.workDutyService.delete(uid(auth), id);
    }

    @PostMapping("/task_save")
    public AjaxResult taskSave(@RequestParam(value = "id", required = false) Integer id,
                               @RequestParam(value = "name", required = false) String name,
                               @RequestParam(value = "sort_no", required = false) Integer sortNo,
                               Authentication auth) {
        return this.workDutyService.saveTask(uid(auth), id, name, sortNo);
    }

    @PostMapping("/task_delete")
    public AjaxResult taskDelete(@RequestParam("id") Integer id, Authentication auth) {
        return this.workDutyService.deleteTask(uid(auth), id);
    }

    @ExceptionHandler(Exception.class)
    public AjaxResult handle(Exception e) {
        e.printStackTrace();
        AjaxResult r = new AjaxResult();
        r.success = false;
        r.message = "업무 처리 중 오류가 발생했습니다: " + e.getMessage();
        return r;
    }
}