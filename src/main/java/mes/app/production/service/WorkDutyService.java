package mes.app.production.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

/**
 * 검사 업무 관리 — 업무 시작 · 종료 (품질검사 그룹 — 수입검사·완제품검사 등).
 *
 * ★ 실적(수량)은 없다. 「무슨 업무를 언제부터 언제까지」만 남긴다.
 *   1공장 대시보드 작업인원 칸이 이 값을 읽어 「작업중 · 수입검사」로 띄운다.
 *
 * ★ 업무 목록은 work_duty_task 에 둔다. 그룹 전원이 같은 목록을 쓰고,
 *   화면의 「업무 목록 관리」에서 추가·수정·삭제한다.
 *   기록에는 업무 «이름» 을 글자로 남기므로, 목록에서 지우거나 이름을 바꿔도 지난 기록은 그대로다.
 *   목록에 없는 일회성 업무는 직접 입력으로 시작할 수 있다.
 *
 * ★ 종료를 안 누르고 퇴근하면 그날 23:59:59 로 닫는다(AutoClosed='Y').
 *   스케줄러 없이, 조회·시작·종료 때마다 지난 날짜의 열린 기록을 먼저 정리한다.
 */
@Service
public class WorkDutyService {


    @Autowired
    private SqlRunner sqlRunner;

    private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }

    private MapSqlParameterSource p(Integer userId) {
        return new MapSqlParameterSource().addValue("uid", userId);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 지난 날짜에 열린 채 남은 기록을 그날 자정 직전으로 닫는다 */
    public void closeStale() {
        this.sqlRunner.execute("""
				UPDATE work_duty
				   SET "EndTime"   = date_trunc('day', "StartTime") + interval '1 day' - interval '1 second'
				     , "AutoClosed" = 'Y'
				     , _modified    = now()
				 WHERE "EndTime" IS NULL
				   AND _status = 'a'
				   AND "StartTime"::date < CURRENT_DATE
				""", new MapSqlParameterSource());
    }

    /**
     * 화면용 한 방 조회.
     *   me     : 이름 · 그룹
     *   open   : 지금 열린 업무 (없으면 null)
     *   list   : 고른 날짜의 기록
     *   tasks  : 업무 버튼 목록
     */
    public Map<String, Object> getStatus(Integer userId, String date) {
        closeStale();
        LocalDate day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date);
        Map<String, Object> data = new HashMap<>();

        data.put("me", this.sqlRunner.getRow("""
				SELECT u.id AS user_id
				     , COALESCE(pe."Name", NULLIF(up."Name",''), NULLIF(u.first_name,''), u.username) AS name
				     , g."Name" AS group_name
				  FROM auth_user u
				  LEFT JOIN user_profile up ON up."User_id" = u.id
				  LEFT JOIN user_group   g  ON g.id = up."UserGroup_id"
				  LEFT JOIN person pe ON pe.id = COALESCE(u.personid,
				                         CASE WHEN u.username ~ '^[0-9]+$' THEN u.username::int END)
				 WHERE u.id = :uid
				""", p(userId)));

        data.put("open", this.sqlRunner.getRow("""
				SELECT id, "TaskName" AS task_name, "Memo" AS memo
				     , to_char("StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
				     , ROUND(EXTRACT(epoch FROM now() - "StartTime") / 60) AS elapsed_min
				  FROM work_duty
				 WHERE "User_id" = :uid AND "EndTime" IS NULL AND _status = 'a'
				""", p(userId)));

        data.put("date", day.toString());
        data.put("list", nz(this.sqlRunner.getRows("""
				SELECT id, "TaskName" AS task_name, "Memo" AS memo, "AutoClosed" AS auto_closed
				     , to_char("StartTime", 'yyyy-mm-dd"T"hh24:mi') AS start_dt
				     , to_char("EndTime",   'yyyy-mm-dd"T"hh24:mi') AS end_dt
				     , to_char("StartTime", 'hh24:mi') AS start_time
				     , to_char("EndTime",   'hh24:mi') AS end_time
				     , ROUND(EXTRACT(epoch FROM COALESCE("EndTime", now()) - "StartTime") / 60) AS minutes
				  FROM work_duty
				 WHERE "User_id" = :uid AND _status = 'a'
				   AND "StartTime"::date = :day
				 ORDER BY "StartTime" DESC
				""", p(userId).addValue("day", day))));

        /* 업무 버튼 = 업무 목록(work_duty_task). 화면의 「업무 목록 관리」에서 바꾼다. */
        data.put("tasks", getTasks());
        return data;
    }

    @Transactional
    public AjaxResult start(Integer userId, String task, String memo, String spjangcd) {
        AjaxResult r = new AjaxResult();
        String t = trim(task, 100);
        if (t == null) {
            r.success = false;
            r.message = "업무를 선택하거나 입력하세요.";
            return r;
        }
        closeStale();
        Map<String, Object> open = this.sqlRunner.getRow("""
				SELECT id FROM work_duty WHERE "User_id" = :uid AND "EndTime" IS NULL AND _status = 'a'
				""", p(userId));
        if (open != null) {
            // 두 기기에서 동시에 눌렀거나 화면이 오래돼 상태가 어긋난 경우
            r.success = false;
            r.message = "이미 진행 중인 업무가 있습니다. 먼저 종료하세요.";
            return r;
        }
        this.sqlRunner.execute("""
				INSERT INTO work_duty ("User_id", "UserGroup_id", "TaskName", "Memo", "StartTime",
				                       spjangcd, _status, _created, _creater_id)
				SELECT :uid, up."UserGroup_id", :task, :memo, now(), :sp, 'a', now(), :uid
				  FROM (SELECT 1) x
				  LEFT JOIN user_profile up ON up."User_id" = :uid
				""", p(userId).addValue("task", t).addValue("memo", trim(memo, 500))
                .addValue("sp", spjangcd == null || spjangcd.isBlank() ? "ZZ" : spjangcd));
        r.success = true;
        r.message = t + " 시작";
        return r;
    }

    /** 종료 — 메모를 함께 받으면 덮어쓴다(작업하며 적은 내용을 종료 때 남기는 경우) */
    @Transactional
    public AjaxResult end(Integer userId, String memo) {
        AjaxResult r = new AjaxResult();
        closeStale();
        Map<String, Object> open = this.sqlRunner.getRow("""
				SELECT id, "TaskName" AS t FROM work_duty
				 WHERE "User_id" = :uid AND "EndTime" IS NULL AND _status = 'a'
				""", p(userId));
        r.success = true;
        if (open == null) {
            r.message = "진행 중인 업무가 없습니다.";
            return r;
        }
        this.sqlRunner.execute("""
				UPDATE work_duty
				   SET "EndTime" = now()
				     , "Memo" = COALESCE(:memo, "Memo")
				     , _modified = now(), _modifier_id = :uid
				 WHERE id = :id
				""", p(userId).addValue("id", open.get("id")).addValue("memo", trim(memo, 500)));
        r.message = (open.get("t") == null ? "업무" : open.get("t")) + " 종료";
        return r;
    }

    private static java.sql.Timestamp ts(String v) {
        if (v == null || v.isBlank()) return null;
        String x = v.trim().replace('T', ' ');
        if (x.length() == 16) x += ":00";
        return java.sql.Timestamp.valueOf(x);
    }

    /**
     * 기록 수정 — 업무 · 시작 · 종료 · 메모.
     *
     * ★ 종료를 비울 수 있는 건 «지금 진행 중인 기록» 뿐이다.
     *   지난 기록의 종료를 비우면 한 사람에게 열린 기록이 둘이 되어 대시보드가 틀어진다.
     * ★ 같은 사람의 다른 기록과 시간이 겹치면 막는다 — 겹치면 하루 합계가 부풀어진다.
     * ★ 본인 기록만 고친다.
     */
    @Transactional
    public AjaxResult update(Integer userId, Integer id, String task, String start, String end, String memo) {
        AjaxResult r = new AjaxResult();
        r.success = false;

        Map<String, Object> row = this.sqlRunner.getRow("""
				SELECT id, ("EndTime" IS NULL) AS is_open
				  FROM work_duty WHERE id = :id AND "User_id" = :uid AND _status = 'a'
				""", p(userId).addValue("id", id));
        if (row == null) { r.message = "기록을 찾을 수 없습니다."; return r; }

        String t = trim(task, 100);
        if (t == null) { r.message = "업무를 입력하세요."; return r; }

        java.sql.Timestamp st, en;
        try { st = ts(start); en = ts(end); }
        catch (Exception e) { r.message = "시각 형식이 올바르지 않습니다."; return r; }
        if (st == null) { r.message = "시작 시각을 입력하세요."; return r; }

        boolean isOpen = Boolean.TRUE.equals(row.get("is_open"));
        if (en == null && !isOpen) { r.message = "종료 시각을 입력하세요. (진행 중인 기록만 비워 둘 수 있습니다)"; return r; }
        if (en != null && !en.after(st)) { r.message = "종료 시각이 시작 시각보다 뒤여야 합니다."; return r; }
        if (st.after(new java.sql.Timestamp(System.currentTimeMillis()))) { r.message = "시작 시각이 지금보다 뒤입니다."; return r; }

        Map<String, Object> clash = this.sqlRunner.getRow("""
				SELECT "TaskName" AS t, to_char("StartTime",'mm-dd hh24:mi') AS s
				     , to_char(COALESCE("EndTime", now()),'hh24:mi') AS e
				  FROM work_duty
				 WHERE "User_id" = :uid AND _status = 'a' AND id <> :id
				   AND tstzrange("StartTime", COALESCE("EndTime", now()), '[)')
				       && tstzrange(CAST(:st AS timestamptz), COALESCE(CAST(:en AS timestamptz), now()), '[)')
				 LIMIT 1
				""", p(userId).addValue("id", id).addValue("st", st).addValue("en", en));
        if (clash != null) {
            r.message = "다른 기록과 시간이 겹칩니다: " + clash.get("t") + " (" + clash.get("s") + " ~ " + clash.get("e") + ")";
            return r;
        }

        this.sqlRunner.execute("""
				UPDATE work_duty
				   SET "TaskName" = :task, "StartTime" = :st, "EndTime" = :en, "Memo" = :memo
				     , "AutoClosed" = 'N'
				     , _modified = now(), _modifier_id = :uid
				 WHERE id = :id AND "User_id" = :uid
				""", p(userId).addValue("id", id).addValue("task", t)
                .addValue("st", st).addValue("en", en).addValue("memo", trim(memo, 500)));
        r.success = true;
        r.message = "수정했습니다.";
        return r;
    }

    /** 기록 삭제 — 숨김 처리(_status='d'). 본인 기록만. */
    @Transactional
    public AjaxResult delete(Integer userId, Integer id) {
        AjaxResult r = new AjaxResult();
        Map<String, Object> row = this.sqlRunner.getRow("""
				SELECT id FROM work_duty WHERE id = :id AND "User_id" = :uid AND _status = 'a'
				""", p(userId).addValue("id", id));
        if (row == null) {
            r.success = false;
            r.message = "기록을 찾을 수 없습니다.";
            return r;
        }
        this.sqlRunner.execute("""
				UPDATE work_duty SET _status = 'd', _modified = now(), _modifier_id = :uid
				 WHERE id = :id AND "User_id" = :uid
				""", p(userId).addValue("id", id));
        r.success = true;
        r.message = "삭제했습니다.";
        return r;
    }
    // =================================================================
    // 업무 목록 (work_duty_task)
    // =================================================================

    public List<Map<String, Object>> getTasks() {
        return nz(this.sqlRunner.getRows("""
				SELECT id, "Name" AS name, "SortNo" AS sort_no
				  FROM work_duty_task
				 WHERE _status = 'a'
				 ORDER BY "SortNo", "Name"
				""", new MapSqlParameterSource()));
    }

    /** 추가(id 없음) · 수정(id 있음). 같은 이름이 있으면 막는다. */
    @Transactional
    public AjaxResult saveTask(Integer userId, Integer id, String name, Integer sortNo) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        String n = trim(name, 100);
        if (n == null) { r.message = "업무 이름을 입력하세요."; return r; }

        Map<String, Object> dup = this.sqlRunner.getRow("""
				SELECT id FROM work_duty_task
				 WHERE _status = 'a' AND "Name" = :name AND (CAST(:id AS integer) IS NULL OR id <> :id)
				""", p(userId).addValue("name", n).addValue("id", id));
        if (dup != null) { r.message = "「" + n + "」은(는) 이미 목록에 있습니다."; return r; }

        MapSqlParameterSource prm = p(userId).addValue("id", id).addValue("name", n)
                .addValue("sort", sortNo == null ? 0 : sortNo);
        if (id == null) {
            this.sqlRunner.execute("""
					INSERT INTO work_duty_task ("Name", "SortNo", spjangcd, _status, _created, _creater_id)
					VALUES (:name,
					        CASE WHEN :sort = 0 THEN COALESCE((SELECT MAX("SortNo") FROM work_duty_task WHERE _status = 'a'), 0) + 10
					             ELSE :sort END,
					        'ZZ', 'a', now(), :uid)
					""", prm);
            r.message = "「" + n + "」을(를) 추가했습니다.";
        } else {
            this.sqlRunner.execute("""
					UPDATE work_duty_task SET "Name" = :name, "SortNo" = :sort, _modified = now(), _modifier_id = :uid
					 WHERE id = :id AND _status = 'a'
					""", prm);
            r.message = "수정했습니다.";
        }
        r.success = true;
        return r;
    }

    /** 숨김 처리. 지난 기록의 업무 이름은 그대로 남는다. */
    @Transactional
    public AjaxResult deleteTask(Integer userId, Integer id) {
        AjaxResult r = new AjaxResult();
        this.sqlRunner.execute("""
				UPDATE work_duty_task SET _status = 'd', _modified = now(), _modifier_id = :uid WHERE id = :id
				""", p(userId).addValue("id", id));
        r.success = true;
        r.message = "삭제했습니다. (지난 기록은 그대로 남습니다)";
        return r;
    }
}