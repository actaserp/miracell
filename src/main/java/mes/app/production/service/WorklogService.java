package mes.app.production.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

/**
 * 작업일보 — 작업 기록 CRUD + 실적 일부 수정.
 *
 * ★ 일보에서 실적(mat_produce 등)을 새로 만들지 않는다.
 *   실적 입력은 공정관리·POP 의 몫이다. 여기서 또 만들면
 *   BOM 차감(consumeBomForChasu)·로트 입고를 건너뛴 실적이 생겨
 *   공정 화면을 거친 것과 섞이고, 재고의 진실이 깨진다.
 *   일보는 하루치를 모아 결재받는 집계 문서다.
 *
 * ★ 그래서 CRUD 의 대상은 둘이다
 *   1) 작업 기록 — 실적 숫자로는 남지 않는 그날의 일. 자유롭게 추가·수정·삭제
 *   2) 실적의 작업자·설비·시각 — 잘못 들어간 사람 이름 같은 것만 고친다.
 *      수량·품목·로트는 잠근다. 그걸 바꾸려면 차감을 되돌리고 다시 빼야 하는데
 *      그건 삭제 후 재등록과 같다. 경로를 둘로 두면 롤백 로직이 두 벌이 되고
 *      어긋나면 재고가 틀어진다. (부적합 등록 화면에서 이미 쓴 규칙)
 */
@Service
public class WorklogService {

    @Autowired
    SqlRunner sqlRunner;

    /** SqlRunner.getRows 는 오류 시 null 을 반환한다 (빈 리스트 아님) */
    private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return (rows == null) ? new ArrayList<>() : rows;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    // =================================================================
    // 작업 기록
    // =================================================================

    /**
     * 기간의 작업 기록.
     *
     * factoryId 가 null 이면 두 공장을 모두 — 통합 일보가 쓴다.
     * dateTo 를 비우면 dateFrom 하루치 — 기본은 그날치를 보는 문서다.
     */
    public List<Map<String, Object>> getNotes(String dateFrom, String dateTo,
                                              Integer factoryId, String spjangcd) {
        if (blank(dateTo)) dateTo = dateFrom;
        // 뒤집어 들어오면 빈 목록이 되어 "기록이 없다"로 잘못 읽힌다
        if (!blank(dateFrom) && dateFrom.compareTo(dateTo) > 0) {
            String t = dateFrom; dateFrom = dateTo; dateTo = t;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date_from", dateFrom);
        p.addValue("date_to", dateTo);
        p.addValue("factory_id", factoryId);
        p.addValue("spjangcd", blank(spjangcd) ? "ZZ" : spjangcd);

        String sql = """
                SELECT n.id                           AS pk
                     , to_char(n."NoteDate", 'yyyy-mm-dd') AS note_date
                     , n."Factory_id"                 AS factory_id
                     , n."Category"                   AS category
                     -- 구분 표시명은 코드 마스터에서. 화면이 코드→이름 맵을 들고 있으면
                     -- 일보 화면 3개에 같은 맵이 복사되어 하나만 고쳤을 때 어긋난다.
                     , COALESCE(sc."Value", n."Category") AS category_name
                     , n."Content"                    AS content
                     , n."Process_id"                 AS process_id
                     , p."Name"                       AS process_name
                     , n."Equipment_id"               AS equipment_id
                     , e."Name"                       AS equipment_name
                     , n."Actor_id"                   AS actor_id
                     , pe."Name"                      AS actor_name
                     , cr."Name"                      AS creater_name
                     , to_char(n."_created", 'yyyy-mm-dd hh24:mi') AS created
                  FROM worklog_note n
                  LEFT JOIN sys_code sc ON sc."CodeType" = 'worklog_cat'
                                       AND sc."Code"     = n."Category"
                  LEFT JOIN process p  ON p.id  = n."Process_id"
                  LEFT JOIN equ     e  ON e.id  = n."Equipment_id"
                  LEFT JOIN person  pe ON pe.id = n."Actor_id"
                  LEFT JOIN person  cr ON cr.id = n."_creater_id"
                 WHERE n."NoteDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(n._status, 'a') = 'a'
                   AND n.spjangcd = :spjangcd
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR n."Factory_id" = CAST(:factory_id AS integer))
                 ORDER BY n."NoteDate", n."Factory_id", n.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 등록 · 수정 (pk 가 있으면 수정) */
    public AjaxResult saveNote(Integer pk, String date, Integer factoryId, String category,
                               String content, Integer processId, Integer equipmentId,
                               Integer actorId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (blank(date))    { r.success = false; r.message = "일자를 선택해 주세요."; return r; }
        if (blank(content)) { r.success = false; r.message = "내용을 입력해 주세요."; return r; }
        if (content.length() > 500) {
            r.success = false; r.message = "내용은 500자까지 입력할 수 있습니다."; return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("pk", pk);
        p.addValue("date", date);
        p.addValue("factory_id", (factoryId == null) ? 1 : factoryId);
        p.addValue("category", blank(category) ? "etc" : category);
        p.addValue("content", content.trim());
        p.addValue("process_id", processId);
        p.addValue("equipment_id", equipmentId);
        p.addValue("actor_id", actorId);
        p.addValue("spjangcd", blank(spjangcd) ? "ZZ" : spjangcd);
        p.addValue("userId", user.getId());

        Map<String, Object> row;
        if (pk == null) {
            row = this.sqlRunner.getRow("""
                    INSERT INTO worklog_note
                           ("NoteDate","Factory_id","Category","Content",
                            "Process_id","Equipment_id","Actor_id",
                            _status,_created,_creater_id,spjangcd)
                    VALUES (CAST(:date AS date), :factory_id, :category, :content,
                            CAST(:process_id AS integer), CAST(:equipment_id AS integer),
                            CAST(:actor_id AS integer),
                            'a', now(), :userId, :spjangcd)
                    RETURNING id AS pk
                    """, p);
            r.message = "작업 기록을 등록했습니다.";
        } else {
            row = this.sqlRunner.getRow("""
                    UPDATE worklog_note
                       SET "NoteDate"     = CAST(:date AS date)
                         , "Factory_id"   = :factory_id
                         , "Category"     = :category
                         , "Content"      = :content
                         , "Process_id"   = CAST(:process_id AS integer)
                         , "Equipment_id" = CAST(:equipment_id AS integer)
                         , "Actor_id"     = CAST(:actor_id AS integer)
                         , _modified      = now()
                         , _modifier_id   = :userId
                     WHERE id = :pk AND COALESCE(_status,'a') = 'a'
                    RETURNING id AS pk
                    """, p);
            if (row == null) { r.success = false; r.message = "대상을 찾을 수 없습니다."; return r; }
            r.message = "작업 기록을 수정했습니다.";
        }
        r.data = row;
        return r;
    }

    /**
     * 삭제.
     *
     * ★ 실제로 지운다(하드 삭제). 작업 기록은 재고·실적에 아무 영향이 없어서
     *   흔적을 남길 이유가 없다. 잘못 적은 메모가 회색으로 계속 남으면
     *   일보 인쇄물만 지저분해진다.
     */
    public AjaxResult deleteNote(Integer pk, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (pk == null) { r.success = false; r.message = "대상이 없습니다."; return r; }

        Map<String, Object> row = this.sqlRunner.getRow(
                "DELETE FROM worklog_note WHERE id = :pk RETURNING id AS pk",
                new MapSqlParameterSource().addValue("pk", pk));
        if (row == null) { r.success = false; r.message = "대상을 찾을 수 없습니다."; return r; }
        r.message = "삭제했습니다.";
        r.data = row;
        return r;
    }

    // =================================================================
    // 실적 일부 수정
    // =================================================================

    /**
     * 생산 차수(mat_produce)의 작업자·설비·시각만 수정.
     *
     * ★ 수량·품목·로트는 받지 않는다.
     *   수량을 바꾸면 BOM 차감량과 산출 로트 재고가 함께 움직여야 한다.
     *   그건 롤백 후 재등록과 같은 일이고, 경로를 둘로 두면 롤백 로직이 두 벌이 되어
     *   어긋나는 순간 재고가 틀어진다. 화면에도 그렇게 안내한다.
     *
     * ★ equ_run 은 건드리지 않는다.
     *   설비 가동 구간은 실제 가동 이력이라 사후에 사람이 고칠 값이 아니다.
     *   (M-CELL 조립·수리의 setUnitTime 도 같은 원칙)
     */
    public AjaxResult updateProduce(Integer mpPk, Integer actorId, Integer equipmentId,
                                    String startTime, String endTime, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (mpPk == null) { r.success = false; r.message = "대상 차수가 없습니다."; return r; }

        Map<String, Object> cur = this.sqlRunner.getRow("""
                SELECT mp.id, mp."JobResponse_id" AS jr_pk, jr."State" AS jr_state
                  FROM mat_produce mp
                  LEFT JOIN job_res jr ON jr.id = mp."JobResponse_id"
                 WHERE mp.id = :pk AND COALESCE(mp._status,'a') = 'a'
                """, new MapSqlParameterSource().addValue("pk", mpPk));
        if (cur == null) { r.success = false; r.message = "차수를 찾을 수 없습니다."; return r; }

        if (!blank(startTime) && !blank(endTime) && endTime.compareTo(startTime) < 0) {
            r.success = false; r.message = "종료 시각이 시작보다 빠릅니다."; return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("pk", mpPk);
        p.addValue("actor_id", actorId);
        p.addValue("equipment_id", equipmentId);
        p.addValue("st", blank(startTime) ? null : Timestamp.valueOf(normalize(startTime)));
        p.addValue("et", blank(endTime)   ? null : Timestamp.valueOf(normalize(endTime)));
        p.addValue("userId", user.getId());

        this.sqlRunner.execute("""
                UPDATE mat_produce
                   SET "Actor_id"     = COALESCE(CAST(:actor_id AS integer), "Actor_id")
                     , "Equipment_id" = COALESCE(CAST(:equipment_id AS integer), "Equipment_id")
                     , "StartTime"    = COALESCE(CAST(:st AS timestamp), "StartTime")
                     , "EndTime"      = COALESCE(CAST(:et AS timestamp), "EndTime")
                     , _modified      = now()
                     , _modifier_id   = :userId
                 WHERE id = :pk
                """, p);

        r.message = "수정했습니다.";
        return r;
    }

    /** 세척 항목(wash_work_item)의 작업자·설비는 헤더(wash_work)에 있다 */
    public AjaxResult updateWash(Integer itemPk, Integer actorId, Integer equipmentId,
                                 String startTime, String endTime, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (itemPk == null) { r.success = false; r.message = "대상이 없습니다."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("pk", itemPk);
        p.addValue("actor_id", actorId);
        p.addValue("equipment_id", equipmentId);
        p.addValue("st", blank(startTime) ? null : Timestamp.valueOf(normalize(startTime)));
        p.addValue("et", blank(endTime)   ? null : Timestamp.valueOf(normalize(endTime)));
        p.addValue("userId", user.getId());

        this.sqlRunner.execute("""
                UPDATE wash_work_item
                   SET "StartTime"  = COALESCE(CAST(:st AS timestamp), "StartTime")
                     , "EndTime"    = COALESCE(CAST(:et AS timestamp), "EndTime")
                     , _modified    = now()
                     , _modifier_id = :userId
                 WHERE id = :pk
                """, p);

        // 작업자·설비는 세척 작업(헤더) 단위다. 같은 헤더의 다른 품목도 함께 바뀐다.
        this.sqlRunner.execute("""
                UPDATE wash_work w
                   SET "Actor_id"     = COALESCE(CAST(:actor_id AS integer), w."Actor_id")
                     , "Equipment_id" = COALESCE(CAST(:equipment_id AS integer), w."Equipment_id")
                     , _modified      = now()
                     , _modifier_id   = :userId
                 WHERE w.id = (SELECT wi."WashWork_id" FROM wash_work_item wi WHERE wi.id = :pk)
                """, p);

        r.message = "수정했습니다. 작업자·설비는 같은 세척 작업 전체에 적용됩니다.";
        return r;
    }

    /** M-CELL 유닛의 작업자·설비·시각 */
    public AjaxResult updateUnit(Integer unitPk, Integer actorId, Integer equipmentId,
                                 String startTime, String endTime, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (unitPk == null) { r.success = false; r.message = "대상이 없습니다."; return r; }

        Map<String, Object> cur = this.sqlRunner.getRow("""
                SELECT "State" AS state FROM mcell_unit WHERE id = :pk
                """, new MapSqlParameterSource().addValue("pk", unitPk));
        if (cur == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        // 검사 합격 이후에는 수정 불가 (조립·수리 화면과 같은 규칙)
        String st = String.valueOf(cur.get("state"));
        if ("pass".equals(st) || "packed".equals(st)) {
            r.success = false;
            r.message = "검사 합격 이후에는 수정할 수 없습니다.";
            return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("pk", unitPk);
        p.addValue("actor_id", actorId);
        p.addValue("equipment_id", equipmentId);
        p.addValue("st", blank(startTime) ? null : Timestamp.valueOf(normalize(startTime)));
        p.addValue("et", blank(endTime)   ? null : Timestamp.valueOf(normalize(endTime)));
        p.addValue("userId", user.getId());

        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "Actor_id"     = COALESCE(CAST(:actor_id AS integer), "Actor_id")
                     , "Equipment_id" = COALESCE(CAST(:equipment_id AS integer), "Equipment_id")
                     , "StartTime"    = COALESCE(CAST(:st AS timestamp), "StartTime")
                     , _modified      = now()
                     , _modifier_id   = :userId
                 WHERE id = :pk
                """, p);

        // mat_produce 시각도 함께 맞춘다 — 안 맞추면 작업일보와 실적이 어긋난다
        this.sqlRunner.execute("""
                UPDATE mat_produce mp
                   SET "StartTime"  = COALESCE(CAST(:st AS timestamp), mp."StartTime")
                     , "EndTime"    = COALESCE(CAST(:et AS timestamp), mp."EndTime")
                     , _modified    = now()
                     , _modifier_id = :userId
                 WHERE mp.id = (SELECT mu."MatProduce_id" FROM mcell_unit mu WHERE mu.id = :pk)
                """, p);

        r.message = "수정했습니다.";
        return r;
    }

    // =================================================================
    // 콤보
    // =================================================================

    /*
     * 구분(Category) 콤보는 여기서 만들지 않는다.
     * 공용 콤보가 이미 sys_code 를 CodeType 으로 조회한다 —
     *   AjaxUtil.fillSelectOptions($('#mCat'), 'system_code', false, false, 'worklog_cat')
     * 같은 값을 두 곳에서 내려주면 어느 쪽이 진실인지 알 수 없게 된다.
     */
    public List<Map<String, Object>> getProcessCombo(Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("factory_id", factoryId);
        return nz(this.sqlRunner.getRows("""
                SELECT p.id AS pk, p."Code" AS code, p."Name" AS name,
                       COALESCE(p."Factory_id", 1) AS factory_id
                  FROM process p
                 WHERE COALESCE(p._status, 'a') = 'a'
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR COALESCE(p."Factory_id", 1) = CAST(:factory_id AS integer))
                 ORDER BY COALESCE(p."Factory_id",1), p."Code"
                """, p));
    }

    public List<Map<String, Object>> getWorkerCombo() {
        return nz(this.sqlRunner.getRows("""
                SELECT pe.id AS pk, pe."Name" AS name
                  FROM person pe
                 WHERE COALESCE(pe._status, 'a') = 'a'
                 ORDER BY pe."Name"
                """, new MapSqlParameterSource()));
    }

    public List<Map<String, Object>> getEquipmentCombo() {
        return nz(this.sqlRunner.getRows("""
                SELECT e.id AS pk, e."Name" AS name
                  FROM equ e
                 WHERE COALESCE(e._status, 'a') = 'a'
                 ORDER BY e."Name"
                """, new MapSqlParameterSource()));
    }

    // =================================================================

    /** 'yyyy-MM-dd HH:mm' → 'yyyy-MM-dd HH:mm:00' (Timestamp.valueOf 요구) */
    private static String normalize(String s) {
        String v = s.trim().replace('T', ' ');
        if (v.length() == 16) v = v + ":00";
        return v;
    }
}