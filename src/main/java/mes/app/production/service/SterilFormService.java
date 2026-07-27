package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 멸균일지 양식 마스터 (steril_form / _step / _field)
 *
 * 개정 규칙 (이 서비스가 강제하는 것):
 *   - active 양식은 수정 불가. [개정] 으로 Rev+1 draft 를 만들고 거기서 편집한다.
 *   - 코드당 draft 는 동시에 1개만.
 *   - apply 시 기존 active → obsolete, draft → active (한 트랜잭션).
 *   - 배치가 물고 있는 양식은 삭제 불가 (soft delete 도 막는다).
 *
 * ※ 기존 SterilService 를 건드리지 않도록 별도 서비스로 분리했다.
 */
@Service
@RequiredArgsConstructor
public class SterilFormService {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String FORM_CODE = "F710-3";

    /* ================================================================
     * 조회
     * ================================================================ */

    /** 양식 목록 + 사용 배치 수 */
    public List<Map<String, Object>> formList(String formCode) {
        String sql =
                "SELECT f.id, f.\"FormCode\" AS form_code, f.\"FormName\" AS form_name, " +
                        "       f.\"Revision\" AS revision, f.\"State\" AS state, " +
                        "       f.\"EffectiveFrom\" AS effective_from, f.\"Description\" AS description, " +
                        "       (SELECT count(*) FROM steril_batch b WHERE b.\"SterilForm_id\" = f.id) AS used_count " +
                        "  FROM steril_form f " +
                        " WHERE f.\"_status\" = 'a' " +
                        "   AND (CAST(:code AS varchar) IS NULL OR f.\"FormCode\" = :code) " +   // null 바인딩 시 타입 추론 실패 방지
                        " ORDER BY f.\"FormCode\", f.\"Revision\" DESC";
        return jdbc.queryForList(sql, new MapSqlParameterSource("code", formCode));
    }

    /** 현재 적용중 양식 id. 없으면 null */
    public Integer activeFormId(String formCode) {
        String sql = "SELECT id FROM steril_form " +
                " WHERE \"FormCode\" = :code AND \"State\" = 'active' AND \"_status\" = 'a' LIMIT 1";
        List<Map<String, Object>> r = jdbc.queryForList(sql,
                new MapSqlParameterSource("code", formCode == null ? FORM_CODE : formCode));
        return r.isEmpty() ? null : ((Number) r.get(0).get("id")).intValue();
    }

    /** 양식 상세 (단계 + 필드 중첩) */
    public Map<String, Object> formDetail(int formId) {
        String hSql =
                "SELECT id, \"FormCode\" AS form_code, \"FormName\" AS form_name, \"Revision\" AS revision, " +
                        "       \"State\" AS state, \"EffectiveFrom\" AS effective_from, \"Description\" AS description " +
                        "  FROM steril_form WHERE id = :id AND \"_status\" = 'a'";
        List<Map<String, Object>> hs = jdbc.queryForList(hSql, new MapSqlParameterSource("id", formId));
        if (hs.isEmpty()) throw new IllegalArgumentException("양식을 찾을 수 없습니다. id=" + formId);
        Map<String, Object> form = new LinkedHashMap<>(hs.get(0));

        String sSql =
                "SELECT id, \"StepNo\" AS step_no, \"StepName\" AS step_name, \"Criteria\" AS criteria " +
                        "  FROM steril_form_step WHERE \"SterilForm_id\" = :id AND \"_status\" = 'a' " +
                        " ORDER BY \"StepNo\"";
        List<Map<String, Object>> steps = jdbc.queryForList(sSql, new MapSqlParameterSource("id", formId));

        String fSql =
                "SELECT f.id, f.\"SterilFormStep_id\" AS step_id, f.\"FieldKey\" AS field_key, " +
                        "       f.\"Label\" AS label, f.\"Unit\" AS unit, f.\"DataType\" AS data_type, " +
                        "       f.\"SpecLower\" AS spec_lower, f.\"SpecUpper\" AS spec_upper, " +
                        "       f.\"SourceType\" AS source_type, f.\"SourceRole\" AS source_role, " +
                        "       f.\"SourceCol\" AS source_col, f.\"AggFunc\" AS agg_func, " +
                        "       f.\"SegmentKey\" AS segment_key, f.\"SortOrder\" AS sort_order " +
                        "  FROM steril_form_field f " +
                        "  JOIN steril_form_step s ON s.id = f.\"SterilFormStep_id\" " +
                        " WHERE s.\"SterilForm_id\" = :id AND f.\"_status\" = 'a' AND s.\"_status\" = 'a' " +
                        " ORDER BY s.\"StepNo\", f.\"SortOrder\"";
        List<Map<String, Object>> fields = jdbc.queryForList(fSql, new MapSqlParameterSource("id", formId));

        Map<Object, List<Map<String, Object>>> byStep = new HashMap<>();
        for (Map<String, Object> f : fields) {
            byStep.computeIfAbsent(f.get("step_id"), k -> new ArrayList<>()).add(f);
        }
        for (Map<String, Object> s : steps) {
            s.put("fields", byStep.getOrDefault(s.get("id"), new ArrayList<>()));
        }
        form.put("steps", steps);
        return form;
    }

    /* ================================================================
     * 개정
     * ================================================================ */

    /** active 를 복사해 Rev+1 draft 생성 */
    @Transactional
    public int revise(int baseFormId, Integer userId) {
        Map<String, Object> base = formDetail(baseFormId);
        String code = (String) base.get("form_code");

        Integer draft = findDraft(code);
        if (draft != null) {
            throw new IllegalStateException("이미 편집중인 초안(Rev." + revisionOf(draft) + ")이 있습니다. " +
                    "먼저 적용하거나 삭제하세요.");
        }

        int nextRev = ((Number) base.get("revision")).intValue() + 1;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", base.get("form_name"))
                .addValue("rev", nextRev)
                .addValue("desc", base.get("description"))
                .addValue("uid", userId);
        Integer newId = jdbc.queryForObject(
                "INSERT INTO steril_form (\"FormCode\",\"FormName\",\"Revision\",\"State\",\"Description\",\"_creater_id\") " +
                        "VALUES (:code,:name,:rev,'draft',:desc,:uid) RETURNING id", p, Integer.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) base.get("steps");
        for (Map<String, Object> st : steps) {
            Integer stepId = insertStep(newId, st, userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fs = (List<Map<String, Object>>) st.get("fields");
            int order = 0;
            for (Map<String, Object> f : fs) insertField(stepId, f, ++order, userId);
        }
        return newId;
    }

    /** draft 전체 저장 (단계·필드 통째로 교체) */
    @Transactional
    public void save(int formId, Map<String, Object> payload, Integer userId) {
        assertDraft(formId);

        MapSqlParameterSource h = new MapSqlParameterSource()
                .addValue("id", formId)
                .addValue("name", payload.get("form_name"))
                .addValue("desc", payload.get("description"))
                .addValue("uid", userId);
        jdbc.update("UPDATE steril_form SET \"FormName\"=COALESCE(:name,\"FormName\"), " +
                "       \"Description\"=:desc, \"_modified\"=now(), \"_modifier_id\"=:uid " +
                " WHERE id=:id", h);

        // 통째로 교체 (draft 라 참조하는 실적이 없다)
        jdbc.update("DELETE FROM steril_form_field WHERE \"SterilFormStep_id\" IN " +
                        "(SELECT id FROM steril_form_step WHERE \"SterilForm_id\"=:id)",
                new MapSqlParameterSource("id", formId));
        jdbc.update("DELETE FROM steril_form_step WHERE \"SterilForm_id\"=:id",
                new MapSqlParameterSource("id", formId));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) payload.get("steps");
        if (steps == null) return;
        for (Map<String, Object> st : steps) {
            Integer stepId = insertStep(formId, st, userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fs = (List<Map<String, Object>>) st.get("fields");
            if (fs == null) continue;
            int order = 0;
            for (Map<String, Object> f : fs) insertField(stepId, f, ++order, userId);
        }
    }

    /** draft → active. 기존 active 는 obsolete */
    @Transactional
    public void apply(int formId, Integer userId) {
        assertDraft(formId);
        List<String> errors = validate(formId);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("적용할 수 없습니다.\n- " + String.join("\n- ", errors));
        }
        String code = codeOf(formId);

        jdbc.update("UPDATE steril_form SET \"State\"='obsolete', \"_modified\"=now(), \"_modifier_id\"=:uid " +
                        " WHERE \"FormCode\"=:code AND \"State\"='active' AND \"_status\"='a'",
                new MapSqlParameterSource().addValue("code", code).addValue("uid", userId));

        jdbc.update("UPDATE steril_form SET \"State\"='active', \"EffectiveFrom\"=CURRENT_DATE, " +
                        "       \"_modified\"=now(), \"_modifier_id\"=:uid WHERE id=:id",
                new MapSqlParameterSource().addValue("id", formId).addValue("uid", userId));
    }

    /** draft 삭제. 사용된 양식은 못 지운다 */
    @Transactional
    public void delete(int formId, Integer userId) {
        assertDraft(formId);
        Integer used = jdbc.queryForObject(
                "SELECT count(*) FROM steril_batch WHERE \"SterilForm_id\"=:id",
                new MapSqlParameterSource("id", formId), Integer.class);
        if (used != null && used > 0) {
            throw new IllegalStateException("이미 " + used + "건의 배치가 사용중인 양식은 삭제할 수 없습니다.");
        }
        // 초안은 적용된 적 없는 임시본 → 물리 삭제(자식부터). 자식 FK 가 ON DELETE CASCADE 가
        // 아닐 수 있어 명시적으로 지운다.
        MapSqlParameterSource ip = new MapSqlParameterSource("id", formId);
        jdbc.update("DELETE FROM steril_form_field WHERE \"SterilFormStep_id\" IN " +
                "(SELECT id FROM steril_form_step WHERE \"SterilForm_id\"=:id)", ip);
        jdbc.update("DELETE FROM steril_form_step  WHERE \"SterilForm_id\"=:id", ip);
        jdbc.update("DELETE FROM steril_form        WHERE id=:id", ip);
    }

    /** 적용 전 검증 — 화면 검증과 동일 규칙을 서버에서도 건다 */
    public List<String> validate(int formId) {
        List<String> errs = new ArrayList<>();
        Map<String, Object> form = formDetail(formId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) form.get("steps");

        Set<String> keys = new HashSet<>();
        for (Map<String, Object> st : steps) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fs = (List<Map<String, Object>>) st.get("fields");
            for (Map<String, Object> f : fs) {
                String key = str(f.get("field_key"));
                String src = str(f.get("source_type"));
                if (key.isEmpty()) { errs.add(st.get("step_no") + "단계: FieldKey 누락"); continue; }
                if (!keys.add(key)) errs.add("FieldKey 중복: " + key);
                if (!"manual".equals(src)) {
                    if (str(f.get("agg_func")).isEmpty())    errs.add(key + ": 집계함수 미지정");
                    if (str(f.get("segment_key")).isEmpty()) errs.add(key + ": 구간 미지정");
                }
                Object lo = f.get("spec_lower"), hi = f.get("spec_upper");
                if (lo != null && hi != null &&
                        ((Number) lo).doubleValue() > ((Number) hi).doubleValue()) {
                    errs.add(key + ": 하한이 상한보다 큽니다");
                }
            }
        }
        if (keys.isEmpty()) errs.add("항목이 하나도 없습니다");
        return errs;
    }

    /* ================================================================
     * 내부
     * ================================================================ */

    private Integer insertStep(int formId, Map<String, Object> st, Integer userId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("fid", formId)
                .addValue("no", st.get("step_no"))
                .addValue("name", st.get("step_name"))
                .addValue("crit", st.get("criteria"))
                .addValue("uid", userId);
        return jdbc.queryForObject(
                "INSERT INTO steril_form_step (\"SterilForm_id\",\"StepNo\",\"StepName\",\"Criteria\",\"SortOrder\",\"_creater_id\") " +
                        "VALUES (:fid,:no,:name,:crit,:no,:uid) RETURNING id", p, Integer.class);
    }

    private void insertField(int stepId, Map<String, Object> f, int order, Integer userId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sid", stepId)
                .addValue("key", f.get("field_key"))
                .addValue("label", f.get("label"))
                .addValue("unit", f.get("unit"))
                .addValue("dtype", f.get("data_type") == null ? "num" : f.get("data_type"))
                .addValue("lo", f.get("spec_lower"))
                .addValue("hi", f.get("spec_upper"))
                .addValue("stype", f.get("source_type") == null ? "manual" : f.get("source_type"))
                .addValue("srole", emptyToNull(f.get("source_role")))
                .addValue("scol", emptyToNull(f.get("source_col")))
                .addValue("agg", emptyToNull(f.get("agg_func")))
                .addValue("seg", emptyToNull(f.get("segment_key")))
                .addValue("ord", order)
                .addValue("uid", userId);
        jdbc.update(
                "INSERT INTO steril_form_field (\"SterilFormStep_id\",\"FieldKey\",\"Label\",\"Unit\",\"DataType\"," +
                        " \"SpecLower\",\"SpecUpper\",\"SourceType\",\"SourceRole\",\"SourceCol\",\"AggFunc\",\"SegmentKey\"," +
                        " \"SortOrder\",\"_creater_id\") " +
                        "VALUES (:sid,:key,:label,:unit,:dtype,:lo,:hi,:stype,:srole,:scol,:agg,:seg,:ord,:uid)", p);
    }

    private Integer findDraft(String code) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT id FROM steril_form WHERE \"FormCode\"=:code AND \"State\"='draft' AND \"_status\"='a'",
                new MapSqlParameterSource("code", code));
        return r.isEmpty() ? null : ((Number) r.get(0).get("id")).intValue();
    }

    private void assertDraft(int formId) {
        String state = jdbc.queryForObject(
                "SELECT \"State\" FROM steril_form WHERE id=:id", new MapSqlParameterSource("id", formId), String.class);
        if (!"draft".equals(state)) {
            throw new IllegalStateException("초안(draft) 상태에서만 수정할 수 있습니다. 현재: " + state);
        }
    }

    private String codeOf(int formId) {
        return jdbc.queryForObject("SELECT \"FormCode\" FROM steril_form WHERE id=:id",
                new MapSqlParameterSource("id", formId), String.class);
    }

    private int revisionOf(int formId) {
        Integer r = jdbc.queryForObject("SELECT \"Revision\" FROM steril_form WHERE id=:id",
                new MapSqlParameterSource("id", formId), Integer.class);
        return r == null ? 0 : r;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static Object emptyToNull(Object o) {
        return (o == null || String.valueOf(o).trim().isEmpty()) ? null : o;
    }
}