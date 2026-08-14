package mes.app.production.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 멸균 배치의 첨부 메타(steril_batch_file) + 멸균일지(steril_batch_log)
 *
 * 파일 실체는 공용 첨부(attach_file / /api/files/*)가 관리한다.
 * 여기서 다루는 것은 "그 파일이 멸균 도메인에서 무엇인가" 뿐이다.
 *   - FileRole    : 멸균기 / 전조절 / 통기 / BI사진 / 기타
 *   - UseForCalc  : 자동산출에 포함할지
 *   - SerialNo, DataFrom/To : 화면 파싱 결과를 받아 기록 (추적·검증용)
 */
@Service
@RequiredArgsConstructor
public class SterilFileService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    private static final Set<String> ROLES =
            new HashSet<>(Arrays.asList("sterilizer", "precondition", "aeration", "bi_photo", "etc"));

    /* ================================================================
     * 첨부 메타
     * ================================================================ */

    public List<Map<String, Object>> fileList(int batchId) {
        String sql =
                "SELECT id, \"AttachFile_id\" AS attach_file_id, \"FileName\" AS file_name, " +
                        "       \"FileRole\" AS file_role, \"UseForCalc\" AS use_for_calc, " +
                        "       \"LoggerLabel\" AS logger_label, \"SerialNo\" AS serial_no, " +
                        "       \"DataFrom\" AS data_from, \"DataTo\" AS data_to, \"DetectedBy\" AS detected_by " +
                        "  FROM steril_batch_file " +
                        " WHERE \"SterilBatch_id\" = :bid " +
                        " ORDER BY \"FileRole\", id";
        return jdbc.queryForList(sql, new MapSqlParameterSource("bid", batchId));
    }

    /**
     * 화면의 배정 결과를 통째로 반영한다.
     * 첨부에서 사라진 파일의 메타는 정리하고, 새로 생긴 것은 INSERT, 기존은 UPDATE.
     */
    @Transactional
    public void fileSave(int batchId, List<Map<String, Object>> files, Integer userId) {
        assertEditable(batchId);
        if (files == null) files = Collections.emptyList();

        Set<Integer> incoming = new HashSet<>();
        for (Map<String, Object> f : files) {
            Integer afId = intOf(f.get("attach_file_id"));
            if (afId == null) continue;          // 첨부와 연결 안 된 행은 무시
            incoming.add(afId);

            String role = str(f.get("file_role"));
            if (!ROLES.contains(role)) role = "etc";
            String calc = "Y".equals(str(f.get("use_for_calc"))) ? "Y" : "N";
            if ("bi_photo".equals(role) || "etc".equals(role)) calc = "N";   // 계산 대상 아님

            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("bid", batchId)
                    .addValue("afid", afId)
                    .addValue("name", str(f.get("file_name")))
                    .addValue("role", role)
                    .addValue("calc", calc)
                    .addValue("label", emptyToNull(f.get("logger_label")))
                    .addValue("serial", emptyToNull(f.get("serial_no")))
                    .addValue("from", ts(f.get("data_from")))
                    .addValue("to", ts(f.get("data_to")))
                    .addValue("det", str(f.get("detected_by")).isEmpty() ? "auto" : str(f.get("detected_by")))
                    .addValue("uid", userId);

            jdbc.update(
                    "INSERT INTO steril_batch_file (\"SterilBatch_id\",\"AttachFile_id\",\"FileName\"," +
                            " \"FileRole\",\"UseForCalc\",\"LoggerLabel\",\"SerialNo\",\"DataFrom\",\"DataTo\"," +
                            " \"DetectedBy\",\"_creater_id\") " +
                            "VALUES (:bid,:afid,:name,:role,:calc,:label,:serial,:from,:to,:det,:uid) " +
                            "ON CONFLICT (\"SterilBatch_id\",\"AttachFile_id\") WHERE \"_status\"='a' DO UPDATE SET " +
                            "  \"FileName\"=EXCLUDED.\"FileName\", \"FileRole\"=EXCLUDED.\"FileRole\", " +
                            "  \"UseForCalc\"=EXCLUDED.\"UseForCalc\", \"LoggerLabel\"=EXCLUDED.\"LoggerLabel\", " +
                            "  \"SerialNo\"=EXCLUDED.\"SerialNo\", \"DataFrom\"=EXCLUDED.\"DataFrom\", " +
                            "  \"DataTo\"=EXCLUDED.\"DataTo\", \"DetectedBy\"=EXCLUDED.\"DetectedBy\", " +
                            "  \"_modified\"=now(), \"_modifier_id\"=EXCLUDED.\"_creater_id\"", p);
        }

        /* 첨부에서 사라진 파일의 메타는 지운다.
           남길 이유가 없다 — 이 행은 「그 파일이 멸균 도메인에서 무엇인가」를 적은 꼬리표일 뿐이고,
           파일 실체와 그 이력은 공용 첨부(attach_file)가 들고 있다.
           꼬리표만 _status='d' 로 남기면 조회하는 쪽마다 조건을 붙여야 하는데,
           실제로 logList 는 그 조건이 없어 지운 것이 다시 보인다. */
        List<Map<String, Object>> existing = fileList(batchId);
        for (Map<String, Object> e : existing) {
            Integer afId = intOf(e.get("attach_file_id"));
            if (afId != null && !incoming.contains(afId)) {
                jdbc.update("DELETE FROM steril_batch_file WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", e.get("id")));
            }
        }
    }

    /* ================================================================
     * 멸균일지
     * ================================================================ */

    /** 단계별 값 조회 (재진입 시 복원용) */
    public List<Map<String, Object>> logList(int batchId) {
        String sql =
                "SELECT \"StepNo\" AS step_no, \"StepName\" AS step_name, \"Data\" AS data, " +
                        "       \"AutoData\" AS auto_data, \"SegmentJson\" AS segment_json " +
                        "  FROM steril_batch_log WHERE \"SterilBatch_id\" = :bid ORDER BY \"StepNo\"";
        return jdbc.queryForList(sql, new MapSqlParameterSource("bid", batchId));
    }

    /**
     * 일지 저장 (단계별 upsert).
     * segments 는 배치 단위 값이라 모든 단계 행에 같은 값을 넣는다 — 어느 단계를 열어도 구간이 복원되게.
     */
    @Transactional
    public void logSave(int batchId, List<Map<String, Object>> steps, Map<String, Object> segments,
                        Integer userId) {
        assertEditable(batchId);
        assertLogSchema();
        String segJson = toJson(segments);
        if (steps == null) return;

        // UPDATE 후 실패 시 INSERT 하는 방식은 첫 실패를 25P02(트랜잭션 중지)로 가려버린다.
        // → 단일 upsert 로 바꿔 원래 오류가 그대로 드러나게 한다.
        String sql =
                "INSERT INTO steril_batch_log " +
                        " (\"SterilBatch_id\",\"StepNo\",\"StepName\",\"Data\",\"AutoData\",\"SegmentJson\",\"_creater_id\") " +
                        "VALUES (:bid,:no,:name,:data,:auto,:seg,:uid) " +
                        "ON CONFLICT (\"SterilBatch_id\",\"StepNo\") DO UPDATE SET " +
                        "  \"StepName\"   = EXCLUDED.\"StepName\", " +
                        "  \"Data\"       = EXCLUDED.\"Data\", " +
                        "  \"AutoData\"   = EXCLUDED.\"AutoData\", " +
                        "  \"SegmentJson\"= EXCLUDED.\"SegmentJson\"";

        for (Map<String, Object> st : steps) {
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("bid",  batchId)
                    .addValue("no",   st.get("step_no"))
                    .addValue("name", str(st.get("step_name")))
                    .addValue("data", str(st.get("data")))
                    .addValue("auto", str(st.get("auto_data")))
                    .addValue("seg",  segJson)
                    .addValue("uid",  userId));
        }
    }

    /**
     * 스키마 선검사.
     * 컬럼이 없으면 첫 INSERT 가 실패하고 그 뒤 모든 문장이 25P02 로 덮여
     * 진짜 원인이 안 보인다. 그래서 실행 전에 명확한 메시지로 끊는다.
     */
    private void assertLogSchema() {
        if (logSchemaOk) return;
        List<String> need = Arrays.asList("AutoData", "SegmentJson", "StepName");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'steril_batch_log'",
                new MapSqlParameterSource());
        Set<String> have = new HashSet<>();
        for (Map<String, Object> r : rows) have.add(String.valueOf(r.get("column_name")));

        List<String> miss = new ArrayList<>();
        for (String c : need) if (!have.contains(c)) miss.add(c);
        if (!miss.isEmpty()) {
            throw new IllegalStateException(
                    "steril_batch_log 에 컬럼이 없습니다: " + String.join(", ", miss) +
                            "\nsteril_form_ddl.sql (또는 steril_diag.sql) 을 먼저 실행하세요.");
        }

        Integer uq = jdbc.queryForObject(
                "SELECT count(*) FROM pg_index ix JOIN pg_class t ON t.oid = ix.indrelid " +
                        " WHERE t.relname = 'steril_batch_log' AND ix.indisunique",
                new MapSqlParameterSource(), Integer.class);
        if (uq == null || uq == 0) {
            throw new IllegalStateException(
                    "steril_batch_log 에 (SterilBatch_id, StepNo) 유니크 제약이 없습니다.\n" +
                            "CREATE UNIQUE INDEX ux_sbl_batch_step ON steril_batch_log (\"SterilBatch_id\",\"StepNo\");");
        }
        logSchemaOk = true;
    }
    private volatile boolean logSchemaOk = false;

    /* ================================================================
     * 대조군 / BI 로트
     * ================================================================ */

    /**
     * BI 판정 보조 정보 저장.
     * 대조군이 양성이 아니면 경고만 반환한다 — 판정을 막지 않는다(현장 결정).
     * 실제 pass/fail 처리는 기존 SterilService.biJudge 가 담당.
     */
    @Transactional
    public Map<String, Object> saveBiInfo(int batchId, String controlResult, String biLotNo, Integer userId) {
        jdbc.update("UPDATE steril_batch SET \"ControlResult\"=:cr, \"BiLotNo\"=:lot WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", batchId)
                        .addValue("cr", emptyToNull(controlResult))
                        .addValue("lot", emptyToNull(biLotNo)));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("warn", null);
        if (!"positive".equals(str(controlResult))) {
            res.put("warn",
                    "대조군이 양성으로 기록되지 않았습니다. " +
                            "대조군 양성 확인 없이는 BI 판정의 유효성이 보장되지 않습니다.");
        }
        return res;
    }

    /* ================================================================
     * 내부
     * ================================================================ */

    /**
     * 배치 존재 확인.
     *
     * ※ 판정(done/scrapped) 이후에도 첨부·일지 수정을 허용한다.
     *    통기 로그는 멸균 후 며칠에 걸쳐 기록되고 BI 판독 사진도 판정 시점 이후에 나오므로,
     *    "판정 완료 = 기록 종료" 가 아니다. 현장 운영이 그렇다.
     */
    private void assertEditable(int batchId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM steril_batch WHERE id=:id",
                new MapSqlParameterSource("id", batchId), Integer.class);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("배치를 찾을 수 없습니다. id=" + batchId);
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try { return om.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    private static Timestamp ts(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return Timestamp.from(OffsetDateTime.parse(s).toInstant()); }
        catch (Exception ignore) { }
        try { return Timestamp.valueOf(s.replace('T', ' ').replaceAll("Z$", "")); }
        catch (Exception ignore) { }
        return null;
    }

    private static Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (Exception e) { return null; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static Object emptyToNull(Object o) {
        return (o == null || String.valueOf(o).trim().isEmpty()) ? null : o;
    }
}