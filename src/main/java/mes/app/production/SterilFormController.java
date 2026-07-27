package mes.app.production;

import lombok.RequiredArgsConstructor;
import mes.app.production.service.SterilFileService;
import mes.app.production.service.SterilFormService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

/**
 * /api/production/steril/*  — 양식 / 첨부메타 / 일지
 *
 * 기존 SterilController(배치·로트·판정)와 별도 파일.
 * 매핑이 겹치지 않으므로 같은 prefix 를 써도 충돌하지 않는다.
 *
 * 응답 규약: {success, data, message}  (AjaxUtil 이 기대하는 형태)
 */
@RestController
@RequestMapping("/api/production/steril")
@RequiredArgsConstructor
public class SterilFormController {

    private final SterilFormService formService;
    private final SterilFileService fileService;

    /* 화면은 공용 AjaxUtil.postJsonData 로 호출한다.
     * → CSRF 헤더 · application/json · JSON.stringify · spjangcd · 로딩표시를 유틸이 처리하므로
     *   여기서는 평범하게 @RequestBody 로 받으면 된다. */

    /* ================================================================
     * 양식
     * ================================================================ */

    @GetMapping("/form_list")
    public Map<String, Object> formList(@RequestParam(required = false) String form_code) {
        return ok(formService.formList(form_code));
    }

    @GetMapping("/form_detail")
    public Map<String, Object> formDetail(@RequestParam int id) {
        return ok(formService.formDetail(id));
    }

    /** 배치 생성 시 박아넣을 현행 양식 */
    @GetMapping("/form_active")
    public Map<String, Object> formActive(@RequestParam(required = false) String form_code) {
        Integer id = formService.activeFormId(form_code);
        if (id == null) return fail("적용중인 양식이 없습니다. 양식 탭에서 먼저 적용하세요.");
        return ok(formService.formDetail(id));
    }

    @PostMapping(value = "/form_revise", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> formRevise(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            int newId = formService.revise(asInt(b.get("base_id")), userId(session));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", newId);
            return ok(d);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    @PostMapping(value = "/form_save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> formSave(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            formService.save(asInt(b.get("id")), b, userId(session));
            return ok(null);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    @PostMapping(value = "/form_apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> formApply(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            formService.apply(asInt(b.get("id")), userId(session));
            return ok(null);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    @PostMapping(value = "/form_delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> formDelete(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            formService.delete(asInt(b.get("id")), userId(session));
            return ok(null);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    @GetMapping("/form_validate")
    public Map<String, Object> formValidate(@RequestParam int id) {
        return ok(formService.validate(id));
    }

    /* ================================================================
     * 첨부 메타
     * ================================================================ */

    @GetMapping("/file_list")
    public Map<String, Object> fileList(@RequestParam int batch_id) {
        return ok(fileService.fileList(batch_id));
    }

    @PostMapping(value = "/file_save", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> fileSave(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            fileService.fileSave(asInt(b.get("batch_id")),
                    (List<Map<String, Object>>) b.get("files"), userId(session));
            return ok(null);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    /* ================================================================
     * 일지
     * ================================================================ */

    @GetMapping("/log_list")
    public Map<String, Object> logList(@RequestParam int batch_id) {
        return ok(fileService.logList(batch_id));
    }

    @PostMapping(value = "/log_save", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> logSave(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            fileService.logSave(asInt(b.get("batch_id")),
                    (List<Map<String, Object>>) b.get("steps"),
                    (Map<String, Object>) b.get("segments"),
                    userId(session));
            return ok(null);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    /** 대조군·BI로트 저장. 대조군이 양성이 아니면 warn 을 실어 보낸다(차단 아님) */
    @PostMapping(value = "/bi_info", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> biInfo(@RequestBody Map<String, Object> b, HttpSession session) {
        try {
            Map<String, Object> r = fileService.saveBiInfo(
                    asInt(b.get("batch_id")),
                    (String) b.get("control_result"),
                    (String) b.get("bi_lot_no"),
                    userId(session));
            return ok(r);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    /* ================================================================
     * 공통
     * ================================================================ */

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        m.put("message", null);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("data", null);
        m.put("message", msg);
        return m;
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return Integer.parseInt(String.valueOf(o).trim());
    }

    /** 프로젝트의 로그인 사용자 조회 방식에 맞춰 교체할 것 */
    private static Integer userId(HttpSession session) {
        Object u = session.getAttribute("userid");
        if (u == null) u = session.getAttribute("user_id");
        if (u == null) return null;
        try { return Integer.parseInt(String.valueOf(u)); } catch (Exception e) { return null; }
    }
}