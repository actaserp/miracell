package mes.app.production.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * M-CELL 검사 (2공장, mc02 / 워크센터 53).
 *
 * 양식(기준정보)과 실적을 한 서비스에서 처리한다.
 * 별도 기준정보 화면 없이, 검사 태블릿 화면 상단의 «양식» 버튼(PC 접속 시)에서 관리.
 *
 * 단순화 설계
 *   · 양식에 '유형' 없음. 판정방식은 항목별 JudgeType(ox=합/불, num=수치).
 *   · 반복검사는 항목의 RepeatCount 숫자 하나 (전원 3회, BASKET 50회).
 *   · 품목↔양식 N:M → 유닛 품목으로 탭 구성.
 *   · 재검사 = TryNo+1 로 새 회차. 이전 회차는 이력으로 보존.
 *
 * 판정 연동
 *   · 유닛의 모든 양식이 pass  → mcell_unit.State='pass'  + 로트 생산창고(17) → 검사완료창고(19)
 *   · 하나라도 fail            → mcell_unit.State='reject' (조립 복귀, 로트 이동 없음)
 */
@Service
public class McellInspectService {

    @Autowired SqlRunner sqlRunner;
    private final ObjectMapper om = new ObjectMapper();

    public static final int STORE_PROD    = 17;   // 생산창고 (조립 산출 = 검사 대기)
    public static final int STORE_INSPECT = 19;   // 검사완료창고
    public static final int PROCESS_INSPECT = 54; // mc02 검사 공정 id

    // =====================================================================
    // 양식 (기준정보)
    // =====================================================================

    public List<Map<String, Object>> getFormList(String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
        return this.sqlRunner.getRows("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."Name" AS form_name,
                       f."UseYN" AS use_yn, f."SeqNo" AS seq_no, f."Description" AS description,
                       COALESCE(i.cnt,0) AS item_cnt, COALESCE(m.names,'') AS mat_names
                  FROM insp_form f
                  LEFT JOIN LATERAL (SELECT COUNT(*) AS cnt FROM insp_form_item x
                                      WHERE x."InspForm_id"=f.id AND COALESCE(x."_status",'a')='a') i ON true
                  LEFT JOIN LATERAL (SELECT string_agg(mt."Code", ', ' ORDER BY mt."Code") AS names
                                       FROM insp_form_mat fm JOIN material mt ON mt.id=fm."Material_id"
                                      WHERE fm."InspForm_id"=f.id) m ON true
                 WHERE COALESCE(f."_status",'a')='a' AND f.spjangcd = :spjangcd
                 ORDER BY f."SeqNo", f.id
                """, p);
    }

    public Map<String, Object> getFormDetail(Integer formId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("formId", formId);
        Map<String, Object> form = this.sqlRunner.getRow("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."Name" AS form_name,
                       f."UseYN" AS use_yn, f."SeqNo" AS seq_no, f."Description" AS description
                  FROM insp_form f WHERE f.id = :formId
                """, p);
        if (form == null) return null;
        form.put("items", getFormItems(formId));
        form.put("materials", this.sqlRunner.getRows("""
                SELECT fm."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                  FROM insp_form_mat fm JOIN material m ON m.id=fm."Material_id"
                 WHERE fm."InspForm_id" = :formId ORDER BY m."Code"
                """, p));
        return form;
    }

    private List<Map<String, Object>> getFormItems(Integer formId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("formId", formId);
        return this.sqlRunner.getRows("""
                SELECT i.id AS item_id, i."SeqNo" AS seq_no, i."ItemName" AS item_name,
                       i."Criteria" AS criteria, i."Method" AS method, i."JudgeType" AS judge_type,
                       i."Unit" AS unit, i."LowerLimit" AS lower_limit, i."UpperLimit" AS upper_limit,
                       COALESCE(i."RepeatCount",1) AS repeat_count,
                       i."SpecOptions" AS spec_options
                  FROM insp_form_item i
                 WHERE i."InspForm_id" = :formId AND COALESCE(i."_status",'a')='a'
                 ORDER BY i."SeqNo", i.id
                """, p);
    }

    /** 검사 대상 품목 (InspectYN='Y') */
    public List<Map<String, Object>> getInspectMaterials() {
        return this.sqlRunner.getRows("""
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                  FROM material m
                 WHERE COALESCE(m."InspectYN",'N')='Y' AND COALESCE(m."_status",'a')='a'
                 ORDER BY m."Code"
                """, new MapSqlParameterSource());
    }

    /** 양식 저장 — 항목·연결품목 전량 교체 (실적이 참조하는 항목은 소프트삭제로 보존) */
    @Transactional
    public AjaxResult saveForm(Integer formId, String code, String name, String useYn, Integer seqNo,
                               String description, String itemsJson, String matIdsJson,
                               String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (name == null || name.isBlank()) { r.success = false; r.message = "검사명을 입력하세요."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("formId", formId)
                .addValue("code", (code == null || code.isBlank()) ? null : code.trim())
                .addValue("name", name.trim())
                .addValue("useYn", (useYn == null || useYn.isBlank()) ? "Y" : useYn)
                .addValue("seqNo", seqNo == null ? 0 : seqNo)
                .addValue("desc", description)
                .addValue("userId", user.getId())
                .addValue("spjangcd", spjangcd);

        Integer id = formId;
        if (id == null) {
            Map<String, Object> ins = this.sqlRunner.getRow("""
                    INSERT INTO insp_form ("Code","Name","UseYN","SeqNo","Description",
                                           "_status","_created","_creater_id",spjangcd)
                    VALUES (:code,:name,:useYn,:seqNo,:desc,'a',now(),:userId,:spjangcd)
                    RETURNING id
                    """, p);
            id = ((Number) ins.get("id")).intValue();
        } else {
            this.sqlRunner.execute("""
                    UPDATE insp_form SET "Code"=:code,"Name"=:name,"UseYN"=:useYn,"SeqNo"=:seqNo,
                           "Description"=:desc,"_modified"=now(),"_modifier_id"=:userId
                     WHERE id=:formId
                    """, p);
        }

        MapSqlParameterSource fp = new MapSqlParameterSource()
                .addValue("formId", id).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                DELETE FROM insp_form_item
                 WHERE "InspForm_id"=:formId
                   AND NOT EXISTS (SELECT 1 FROM insp_result_item ri
                                    WHERE ri."InspFormItem_id" = insp_form_item.id)
                """, fp);
        this.sqlRunner.execute("""
                UPDATE insp_form_item SET "_status"='d',"_modified"=now(),"_modifier_id"=:userId
                 WHERE "InspForm_id"=:formId AND COALESCE("_status",'a')='a'
                """, fp);

        try {
            List<Map<String, Object>> items = om.readValue(itemsJson == null ? "[]" : itemsJson, List.class);
            int seq = 1;
            for (Map<String, Object> it : items) {
                String nm = str(it.get("item_name"));
                if (nm == null || nm.isBlank()) continue;
                MapSqlParameterSource ip = new MapSqlParameterSource()
                        .addValue("formId", id).addValue("seq", seq++)
                        .addValue("nm", nm.trim())
                        .addValue("cr", str(it.get("criteria")))
                        .addValue("mt", str(it.get("method")))
                        .addValue("jt", "num".equals(str(it.get("judge_type"))) ? "num" : "ox")
                        .addValue("un", str(it.get("unit")))
                        .addValue("lo", numOrNull(it.get("lower_limit")))
                        .addValue("up", numOrNull(it.get("upper_limit")))
                        .addValue("rp", intOr(it.get("repeat_count"), 1))
                        .addValue("so", str(it.get("spec_options")))
                        .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
                this.sqlRunner.execute("""
                        INSERT INTO insp_form_item
                            ("InspForm_id","SeqNo","ItemName","Criteria","Method","JudgeType",
                             "Unit","LowerLimit","UpperLimit","RepeatCount","SpecOptions",
                             "_status","_created","_creater_id",spjangcd)
                        VALUES (:formId,:seq,:nm,:cr,:mt,:jt,:un,
                                CAST(:lo AS numeric),CAST(:up AS numeric),:rp,:so,
                                'a',now(),:userId,:spjangcd)
                        """, ip);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("검사 항목 형식 오류: " + e.getMessage());
        }

        this.sqlRunner.execute("DELETE FROM insp_form_mat WHERE \"InspForm_id\"=:formId", fp);
        try {
            List<Object> matIds = om.readValue(matIdsJson == null ? "[]" : matIdsJson, List.class);
            for (Object mid : matIds) {
                if (mid == null) continue;
                MapSqlParameterSource mp = new MapSqlParameterSource()
                        .addValue("formId", id).addValue("matId", ((Number) mid).intValue())
                        .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
                this.sqlRunner.execute("""
                        INSERT INTO insp_form_mat ("InspForm_id","Material_id","_status","_created","_creater_id",spjangcd)
                        VALUES (:formId,:matId,'a',now(),:userId,:spjangcd) ON CONFLICT DO NOTHING
                        """, mp);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("적용 품목 형식 오류: " + e.getMessage());
        }

        r.data = Map.of("form_id", id);
        r.message = "저장되었습니다.";
        return r;
    }

    @Transactional
    public AjaxResult deleteForm(Integer formId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("formId", formId);
        Map<String, Object> used = this.sqlRunner.getRow(
                "SELECT COUNT(*) AS c FROM insp_result WHERE \"InspForm_id\"=:formId", p);
        if (used != null && ((Number) used.get("c")).intValue() > 0) {
            r.success = false;
            r.message = "이 양식으로 검사한 실적이 있어 삭제할 수 없습니다. '사용안함'으로 변경하세요.";
            return r;
        }
        this.sqlRunner.execute("DELETE FROM insp_form_mat  WHERE \"InspForm_id\"=:formId", p);
        this.sqlRunner.execute("DELETE FROM insp_form_item WHERE \"InspForm_id\"=:formId", p);
        this.sqlRunner.execute("DELETE FROM insp_form      WHERE id=:formId", p);
        r.message = "삭제되었습니다.";
        return r;
    }

    // =====================================================================
    // 실적 — A/B 화면
    // =====================================================================

    /** A화면 — 검사 대상 작지 (조립에서 유닛이 생성된 작지) */
    public List<Map<String, Object>> getWoQueue(String spjangcd, String dateFrom, String dateTo) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("spjangcd", spjangcd)
                .addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom))
                .addValue("dateTo", (dateTo == null || dateTo.isBlank()) ? null : LocalDate.parse(dateTo));
        return this.sqlRunner.getRows("""
                SELECT jr.id AS job_res_id, jr."WorkOrderNumber" AS order_num
                     , m."Code" AS mat_code, m."Name" AS mat_name
                     , to_char(jr."ProductionDate",'yyyy-mm-dd') AS plan_date
                     , COALESCE(jr."OrderQty",0) AS plan_qty
                     , COALESCE(u.total,0)   AS unit_cnt      -- 검사에 도달한 유닛만
                     , COALESCE(u.waiting,0) AS wait_cnt
                     , COALESCE(u.passed,0)  AS pass_cnt
                     , COALESCE(u.rejected,0) AS reject_cnt
                     , COALESCE(u.pending,0) AS pending_cnt   -- 아직 조립·수리중이라 안 넘어온 대수
                     , COALESCE(u.repair_cnt,0) AS repair_cnt -- 수리(mc04)에서 넘어온 대수
                     , CASE WHEN COALESCE(u.waiting,0)=0 AND COALESCE(u.total,0)>0 THEN 'done'
                            WHEN COALESCE(u.passed,0)+COALESCE(u.rejected,0)>0     THEN 'working'
                            ELSE 'wait' END AS state
                  FROM job_res jr
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  JOIN LATERAL (
                        -- ★ total 은 '검사에 도달한' 유닛만 센다.
                        --   예전엔 유닛 전체를 세서, 작지에 유닛만 깔리고 아직 조립/수리도 안 끝난 건이
                        --   waiting=0 → 'done' 으로 계산되어 검사 큐에 완료 카드로 떴다.
                        --   (수리는 접수 즉시 유닛이 생기므로 이 버그가 바로 드러났다)
                        SELECT COUNT(*) FILTER (WHERE mu."State" IN ('inspect_wait','pass','reject','packed')) AS total
                             , COUNT(*) FILTER (WHERE mu."State" = 'inspect_wait')        AS waiting
                             , COUNT(*) FILTER (WHERE mu."State" IN ('pass','packed'))    AS passed
                             , COUNT(*) FILTER (WHERE mu."State" = 'reject')              AS rejected
                             , COUNT(*) FILTER (WHERE mu."State" NOT IN ('inspect_wait','pass','reject','packed')) AS pending
                             , COUNT(*) FILTER (WHERE mu."McellRepair_id" IS NOT NULL
                                                  AND mu."State" IN ('inspect_wait','pass','reject','packed')) AS repair_cnt
                          FROM mcell_unit mu
                         WHERE mu."JobResponse_id"=jr.id AND COALESCE(mu."_status",'a')='a'
                  ) u ON u.total > 0
                 WHERE jr.spjangcd = :spjangcd
                   AND (CAST(:dateFrom AS date) IS NULL OR jr."ProductionDate"::date >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR jr."ProductionDate"::date <= CAST(:dateTo   AS date))
                 ORDER BY jr."ProductionDate" DESC, jr.id DESC
                 LIMIT 200
                """, p);
    }

    /** B화면 — 유닛 목록 + 양식별 진행 상태 */
    public List<Map<String, Object>> getUnitList(Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jobResId);
        List<Map<String, Object>> units = this.sqlRunner.getRows("""
                SELECT mu.id AS unit_id, mu."UnitNo" AS unit_no, mu."LotNumber" AS lot_number,
                       mu."Material_id" AS mat_id, mu."State" AS state,
                       mu."RejectReason" AS reject_reason,
                       to_char(mu."EndTime",'yyyy-mm-dd hh24:mi') AS assy_end
                     , COALESCE(rw.cnt, 0)   AS rework_cnt      -- 지금까지 불합격 횟수
                     , rw.mods               AS rework_mods     -- 다시 조립한 모듈
                     -- 수리(mc04)에서 넘어온 유닛 구분. 조립 유닛이면 전부 null.
                     , mu."McellRepair_id"   AS repair_id
                     , rp."Cat"              AS repair_cat      -- return | spec
                     , rp."RepairNo"         AS repair_no
                     , rp."Reason"           AS repair_reason
                     , mu."SrcLotNumber"     AS src_lot         -- 수리 전 원 로트
                  FROM mcell_unit mu
                  LEFT JOIN mcell_repair rp ON rp.id = mu."McellRepair_id"
                  LEFT JOIN LATERAL (
                        SELECT (SELECT COUNT(*) FROM insp_result ir
                                 WHERE ir."McellUnit_id" = mu.id AND ir."Verdict" = 'fail') AS cnt
                             , (SELECT string_agg(m2."Code", ', ' ORDER BY st."Depth" DESC)
                                  FROM mcell_unit_step st JOIN material m2 ON m2.id = st."Material_id"
                                 WHERE st."McellUnit_id" = mu.id AND st."ReworkYN" = 'Y') AS mods
                  ) rw ON true
                 WHERE mu."JobResponse_id" = :jrId AND COALESCE(mu."_status",'a')='a'
                   AND mu."State" IN ('inspect_wait','pass','reject','packed')
                 ORDER BY mu."UnitNo"
                """, p);
        for (Map<String, Object> u : units) {
            u.put("forms", getUnitFormStates(asInt(u.get("unit_id")), asInt(u.get("mat_id"))));
        }
        return units;
    }

    /** 유닛의 양식 탭 + 각 양식의 최신 회차 상태 */
    public List<Map<String, Object>> getUnitFormStates(Integer unitId, Integer matId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("matId", matId);
        return this.sqlRunner.getRows("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."Name" AS form_name, f."SeqNo" AS seq_no
                     , r.id AS result_id, r."TryNo" AS try_no, r."Verdict" AS verdict, r."State" AS state
                     , r."FailReason" AS fail_reason
                     , r."Actor_id" AS actor_id, pr."Name" AS actor_name
                     , to_char(r."EndTime",'yyyy-mm-dd hh24:mi') AS judged_at
                     , COALESCE(h.tries,0) AS try_cnt
                  FROM insp_form f
                  JOIN insp_form_mat fm ON fm."InspForm_id"=f.id AND fm."Material_id"=:matId
                  LEFT JOIN LATERAL (
                        SELECT x.* FROM insp_result x
                         WHERE x."McellUnit_id"=:unitId AND x."InspForm_id"=f.id
                         ORDER BY x."TryNo" DESC LIMIT 1
                  ) r ON true
                  LEFT JOIN person pr ON pr.id = r."Actor_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS tries FROM insp_result y
                         WHERE y."McellUnit_id"=:unitId AND y."InspForm_id"=f.id
                  ) h ON true
                 WHERE COALESCE(f."UseYN",'Y')='Y' AND COALESCE(f."_status",'a')='a'
                 ORDER BY f."SeqNo", f.id
                """, p);
    }

    /** C화면 — 특정 양식의 현재 회차 상세(항목 + 입력값) */
    public Map<String, Object> getResultDetail(Integer unitId, Integer formId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("formId", formId);
        Map<String, Object> res = this.sqlRunner.getRow("""
                SELECT r.id AS result_id, r."TryNo" AS try_no, r."Verdict" AS verdict,
                       r."State" AS state, r."Actor_id" AS actor_id, pr."Name" AS actor_name,
                       r."FailReason" AS fail_reason,
                       to_char(r."StartTime",'yyyy-mm-dd hh24:mi') AS start_time,
                       to_char(r."EndTime",'yyyy-mm-dd hh24:mi') AS end_time
                  FROM insp_result r LEFT JOIN person pr ON pr.id=r."Actor_id"
                 WHERE r."McellUnit_id"=:unitId AND r."InspForm_id"=:formId
                 ORDER BY r."TryNo" DESC LIMIT 1
                """, p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("form", getFormDetail(formId));
        out.put("result", res);
        out.put("items", res == null ? List.of() : this.sqlRunner.getRows("""
                SELECT ri."InspFormItem_id" AS item_id, ri."SeqNo" AS seq_no, ri."RepeatNo" AS repeat_no,
                       ri."Result" AS result, ri."Value" AS value,
                       ri."SpecLabel" AS spec_label, ri."SpecTool" AS spec_tool,
                       ri."Actor_id" AS actor_id, pr."Name" AS actor_name, ri."Description" AS description
                  FROM insp_result_item ri LEFT JOIN person pr ON pr.id=ri."Actor_id"
                 WHERE ri."InspResult_id" = :resId
                 ORDER BY ri."SeqNo", ri."RepeatNo"
                """, new MapSqlParameterSource().addValue("resId", asInt(res.get("result_id")))));
        out.put("history", this.sqlRunner.getRows("""
                SELECT r.id AS result_id, r."TryNo" AS try_no, r."Verdict" AS verdict,
                       pr."Name" AS actor_name, r."FailReason" AS fail_reason,
                       to_char(r."EndTime",'yyyy-mm-dd hh24:mi') AS judged_at
                  FROM insp_result r LEFT JOIN person pr ON pr.id=r."Actor_id"
                 WHERE r."McellUnit_id"=:unitId AND r."InspForm_id"=:formId AND r."Verdict" IS NOT NULL
                 ORDER BY r."TryNo" DESC
                """, p));
        return out;
    }

    /** 특정 회차의 항목 결과 (이력 상세 보기) */
    public List<Map<String, Object>> getResultItems(Integer resultId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("resId", resultId);
        return this.sqlRunner.getRows("""
                SELECT ri."InspFormItem_id" AS item_id, fi."ItemName" AS item_name,
                       ri."SeqNo" AS seq_no, ri."RepeatNo" AS repeat_no,
                       ri."Result" AS result, ri."Value" AS value,
                       ri."SpecLabel" AS spec_label, ri."SpecTool" AS spec_tool,
                       pr."Name" AS actor_name
                  FROM insp_result_item ri
                  LEFT JOIN insp_form_item fi ON fi.id = ri."InspFormItem_id"
                  LEFT JOIN person pr ON pr.id = ri."Actor_id"
                 WHERE ri."InspResult_id" = :resId
                 ORDER BY ri."SeqNo", ri."RepeatNo"
                """, p);
    }

    // =====================================================================
    // 실적 — 쓰기
    // =====================================================================

    /** 검사 시작 / 검사자 배정 — 진행중 회차가 없으면 새 회차 생성 */
    @Transactional
    public AjaxResult startResult(Integer unitId, Integer formId, Integer actorId,
                                  String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("formId", formId)
                .addValue("actorId", actorId).addValue("userId", user.getId())
                .addValue("spjangcd", spjangcd);

        Map<String, Object> cur = this.sqlRunner.getRow("""
                SELECT id, "Verdict" AS verdict FROM insp_result
                 WHERE "McellUnit_id"=:unitId AND "InspForm_id"=:formId
                 ORDER BY "TryNo" DESC LIMIT 1
                """, p);

        Integer resId;
        if (cur != null && cur.get("verdict") == null) {
            resId = asInt(cur.get("id"));
            this.sqlRunner.execute("""
                    UPDATE insp_result SET "Actor_id"=:actorId,"_modified"=now(),"_modifier_id"=:userId
                     WHERE id = """ + resId, p);
        } else {
            Map<String, Object> ins = this.sqlRunner.getRow("""
                    INSERT INTO insp_result
                        ("McellUnit_id","InspForm_id","TryNo","Actor_id","InspectDate","StartTime",
                         "State","_status","_created","_creater_id",spjangcd)
                    SELECT :unitId,:formId,
                           COALESCE((SELECT MAX("TryNo") FROM insp_result
                                      WHERE "McellUnit_id"=:unitId AND "InspForm_id"=:formId),0)+1,
                           :actorId, CURRENT_DATE, LOCALTIMESTAMP,
                           'working','a',now(),:userId,:spjangcd
                    RETURNING id
                    """, p);
            resId = asInt(ins.get("id"));
        }
        r.data = Map.of("result_id", resId);
        return r;
    }

    /**
     * 항목 결과 저장 (합/불 · 측정값). 반복 회차 단위로 1행.
     * 같은 (항목, 반복회차)가 이미 있으면 갱신.
     */
    @Transactional
    public AjaxResult saveItem(Integer resultId, Integer formItemId, Integer seqNo, Integer repeatNo,
                               String result, String value, String specLabel, String specTool,
                               Integer actorId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("resId", resultId).addValue("itemId", formItemId)
                .addValue("seq", seqNo == null ? 1 : seqNo)
                .addValue("rep", repeatNo == null ? 1 : repeatNo)
                .addValue("res", result).addValue("val", value)
                .addValue("sl", specLabel).addValue("st", specTool)
                .addValue("actorId", actorId).addValue("userId", user.getId())
                .addValue("spjangcd", spjangcd);

        Map<String, Object> ex = this.sqlRunner.getRow("""
                SELECT id FROM insp_result_item
                 WHERE "InspResult_id"=:resId AND "InspFormItem_id"=:itemId AND "RepeatNo"=:rep
                """, p);
        if (ex == null) {
            this.sqlRunner.execute("""
                    INSERT INTO insp_result_item
                        ("InspResult_id","InspFormItem_id","SeqNo","RepeatNo","Result","Value",
                         "SpecLabel","SpecTool","Actor_id","_status","_created","_creater_id",spjangcd)
                    VALUES (:resId,:itemId,:seq,:rep,:res,:val,:sl,:st,:actorId,'a',now(),:userId,:spjangcd)
                    """, p);
        } else {
            this.sqlRunner.execute("""
                    UPDATE insp_result_item
                       SET "Result"=:res,"Value"=:val,"SpecLabel"=:sl,"SpecTool"=:st,"Actor_id"=:actorId
                     WHERE "InspResult_id"=:resId AND "InspFormItem_id"=:itemId AND "RepeatNo"=:rep
                    """, p);
        }
        return r;
    }

    /** 항목 결과 삭제 (회차 기록 취소) */
    @Transactional
    public AjaxResult deleteItem(Integer resultId, Integer formItemId, Integer repeatNo) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("resId", resultId).addValue("itemId", formItemId).addValue("rep", repeatNo);
        this.sqlRunner.execute("""
                DELETE FROM insp_result_item
                 WHERE "InspResult_id"=:resId AND "InspFormItem_id"=:itemId AND "RepeatNo"=:rep
                """, p);
        return r;
    }

    /**
     * 양식 확정 — 미입력 항목이 있으면 막고, 하나라도 fail 이면 불합격.
     * 확정 후 유닛 최종 판정을 재계산한다.
     */
    @Transactional
    public AjaxResult judgeForm(Integer resultId, String failReason, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("resId", resultId).addValue("userId", user.getId());

        Map<String, Object> res = this.sqlRunner.getRow("""
                SELECT "McellUnit_id" AS unit_id, "InspForm_id" AS form_id, "Verdict" AS verdict
                  FROM insp_result WHERE id=:resId
                """, p);
        if (res == null) { r.success = false; r.message = "검사 회차를 찾을 수 없습니다."; return r; }
        if (res.get("verdict") != null) { r.success = false; r.message = "이미 확정된 회차입니다."; return r; }

        // 필요 입력 수 = SUM(RepeatCount), 실제 입력 수와 대조
        Map<String, Object> chk = this.sqlRunner.getRow("""
                SELECT (SELECT COALESCE(SUM(COALESCE(i."RepeatCount",1)),0)
                          FROM insp_form_item i
                         WHERE i."InspForm_id"=:formId AND COALESCE(i."_status",'a')='a') AS need
                     , (SELECT COUNT(*) FROM insp_result_item ri
                         WHERE ri."InspResult_id"=:resId AND ri."Result" IS NOT NULL)      AS got
                     , (SELECT COUNT(*) FROM insp_result_item ri
                         WHERE ri."InspResult_id"=:resId AND ri."Result"='fail')           AS bad
                """, new MapSqlParameterSource().addValue("resId", resultId)
                .addValue("formId", asInt(res.get("form_id"))));

        int need = asInt(chk.get("need")), got = asInt(chk.get("got")), bad = asInt(chk.get("bad"));
        if (got < need) {
            r.success = false;
            r.message = "미입력 항목이 " + (need - got) + "건 남았습니다. (" + got + "/" + need + ")";
            return r;
        }

        String verdict = bad > 0 ? "fail" : "pass";
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("resId", resultId).addValue("v", verdict)
                .addValue("reason", failReason).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE insp_result SET "Verdict"=:v, "State"='done', "EndTime"=LOCALTIMESTAMP,
                       "FailReason"=:reason, "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:resId
                """, up);

        AjaxResult fin = recalcUnit(asInt(res.get("unit_id")), spjangcd, user);
        r.data = Map.of("verdict", verdict, "unit", fin.data == null ? Map.of() : fin.data);
        r.message = "pass".equals(verdict) ? "합격 확정" : "불합격 확정";
        return r;
    }

    /**
     * 검사 회차 삭제 — 이력에서 특정 회차를 지운다.
     * 지운 뒤 유닛 판정을 다시 계산한다(최신 회차가 바뀌면 합격/복귀도 따라 바뀜).
     */
    @Transactional
    public AjaxResult deleteResult(Integer resultId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("resId", resultId);
        Map<String, Object> res = this.sqlRunner.getRow(
                "SELECT \"McellUnit_id\" AS unit_id FROM insp_result WHERE id=:resId", p);
        if (res == null) { r.success = false; r.message = "검사 회차를 찾을 수 없습니다."; return r; }

        this.sqlRunner.execute("DELETE FROM insp_result_item WHERE \"InspResult_id\"=:resId", p);
        this.sqlRunner.execute("DELETE FROM insp_result WHERE id=:resId", p);

        AjaxResult fin = recalcUnit(asInt(res.get("unit_id")), spjangcd, user);
        r.data = fin.data;
        r.message = "검사 이력을 삭제했습니다.";
        return r;
    }

    /** 재검사 — 새 회차를 연다 (이전 회차는 이력으로 보존) */
    @Transactional
    public AjaxResult recheck(Integer unitId, Integer formId, Integer actorId, String spjangcd, User user) {
        // 유닛을 검사대기로 되돌린 뒤 새 회차 시작
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit SET "State"='inspect_wait',
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:unitId AND "State" IN ('pass','reject')
                """, p);
        return startResult(unitId, formId, actorId, spjangcd, user);
    }

    /**
     * 유닛 최종 판정 재계산.
     *   · 모든 양식 최신 회차가 pass → 유닛 pass + 로트 17 → 19 이동
     *   · 하나라도 fail            → 유닛 reject (조립 복귀, 이동 없음)
     *   · 아직 미확정 양식이 있으면 그대로 검사대기
     */
    @Transactional
    public AjaxResult recalcUnit(Integer unitId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> unit = this.sqlRunner.getRow("""
                SELECT mu.id, mu."Material_id" AS mat_id, mu."LotNumber" AS lot_number, mu."State" AS state
                  FROM mcell_unit mu WHERE mu.id = :unitId
                """, new MapSqlParameterSource().addValue("unitId", unitId));
        if (unit == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }

        List<Map<String, Object>> forms = getUnitFormStates(unitId, asInt(unit.get("mat_id")));
        if (forms.isEmpty()) {
            r.success = false;
            r.message = "이 품목에 연결된 검사 양식이 없습니다. «양식»에서 적용 품목을 지정하세요.";
            return r;
        }

        boolean anyFail = false, allPass = true;
        for (Map<String, Object> f : forms) {
            Object v = f.get("verdict");
            if ("fail".equals(v)) anyFail = true;
            if (!"pass".equals(v)) allPass = false;
        }

        String newState = anyFail ? "reject" : (allPass ? "pass" : "inspect_wait");

        // 불합격 사유를 유닛에 옮겨 적는다. 조립 화면이 이 값을 읽어 재작업 안내로 띄운다.
        String reason = null, inspNo = null;
        if (anyFail) {
            List<String> lines = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            for (Map<String, Object> f : forms) {
                if (!"fail".equals(f.get("verdict"))) continue;
                String fn = str(f.get("form_name"));
                String fr = str(f.get("fail_reason"));
                lines.add(fn + " : " + ((fr == null || fr.isBlank()) ? "불합격" : fr));
                tags.add(fn + " " + str(f.get("try_no")) + "차");
            }
            reason = String.join(" / ", lines);
            inspNo = String.join(", ", tags);
        }

        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("st", newState)
                .addValue("reason", reason).addValue("inspNo", inspNo)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"=:st,
                       "RejectAt"     = CASE WHEN :st='reject' THEN LOCALTIMESTAMP ELSE NULL END,
                       "RejectReason" = CASE WHEN :st='reject' THEN :reason ELSE NULL END,
                       "RejectInspNo" = CASE WHEN :st='reject' THEN :inspNo ELSE NULL END,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:unitId
                """, up);

        // 합격했으면 재조립 표시를 정리한다 (다음 라운드에서 헷갈리지 않게)
        if ("pass".equals(newState)) {
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_step SET "ReworkYN"='N'
                     WHERE "McellUnit_id"=:unitId AND "ReworkYN"='Y'
                    """, new MapSqlParameterSource().addValue("unitId", unitId));
        }

        // 합격이면 검사완료창고로, 합격이 풀렸으면 생산창고로 되돌린다(이력 삭제·재검사 대응)
        boolean moved = "pass".equals(newState)
                ? moveLot(str(unit.get("lot_number")), asInt(unit.get("mat_id")), unitId,
                STORE_PROD, STORE_INSPECT, "검사합격 · 검사완료창고 이동", spjangcd, user)
                : moveLot(str(unit.get("lot_number")), asInt(unit.get("mat_id")), unitId,
                STORE_INSPECT, STORE_PROD, "검사판정 해제 · 생산창고 복귀", spjangcd, user);
        r.data = Map.of("state", newState, "moved", moved);
        return r;
    }

    /**
     * 로트를 창고 사이로 옮긴다. 로트는 유지하고 창고만 바꾼다(생산이 아니므로 mat_produce 없음).
     *   합격      : 생산창고(17) → 검사완료창고(19)   포장은 19에서만 꺼내므로 이게 '포장 가능' 표시
     *   판정 해제 : 검사완료창고(19) → 생산창고(17)   재검사·이력삭제로 합격이 풀린 경우
     * 옮길 재고가 소스 창고에 없으면 아무 것도 하지 않는다(이미 이동됨 = 정상).
     */
    private boolean moveLot(String lotNumber, Integer matId, Integer unitId,
                            int from, int to, String memo, String spjangcd, User user) {
        if (lotNumber == null || lotNumber.isBlank()) return false;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("lot", lotNumber).addValue("matId", matId).addValue("src", from);

        Map<String, Object> ml = this.sqlRunner.getRow("""
                SELECT id, COALESCE("CurrentStock",0) AS qty FROM mat_lot
                 WHERE "LotNumber"=:lot AND "Material_id"=:matId AND "StoreHouse_id"=:src
                 ORDER BY id LIMIT 1
                """, p);
        if (ml == null) return false;

        MapSqlParameterSource mp = new MapSqlParameterSource()
                .addValue("id", asInt(ml.get("id"))).addValue("dst", to);
        this.sqlRunner.execute("UPDATE mat_lot SET \"StoreHouse_id\"=:dst WHERE id=:id", mp);

        MapSqlParameterSource io = new MapSqlParameterSource()
                .addValue("matId", matId).addValue("lot", lotNumber)
                .addValue("qty", ((Number) ml.get("qty")).doubleValue())
                .addValue("from", from).addValue("to", to).addValue("memo", memo)
                .addValue("unitId", unitId).addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
        // ★ 출고 행은 OutputQty, 입고 행은 InputQty.
        //    matinout_tri 가 두 컬럼을 보고 mat_in_house / material.CurrentStock 를 집계한다.
        //    out 에 InputQty 를 넣으면 차감이 아니라 가산되어 재고가 부푼다.
        this.sqlRunner.execute("""
                INSERT INTO mat_inout ("Material_id","StoreHouse_id","LotNumber","InoutDate","InoutTime",
                                       "InOut","InputQty","OutputQty","InputType","SourceTableName","SourceDataPk",
                                       "State","Description","_status","_created","_creater_id",spjangcd)
                VALUES (:matId,:from,:lot,CURRENT_DATE,LOCALTIME,'out',NULL,:qty,'move',
                        'mcell_unit',:unitId,'confirmed',:memo,'a',now(),:userId,:spjangcd),
                       (:matId,:to,:lot,CURRENT_DATE,LOCALTIME,'in',:qty,NULL,'move',
                        'mcell_unit',:unitId,'confirmed',:memo,'a',now(),:userId,:spjangcd)
                """, io);
        return true;
    }

    // ── 유틸 ──
    private static Integer asInt(Object o) { return o == null ? null : ((Number) o).intValue(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Double numOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private static int intOr(Object o, int def) {
        if (o == null) return def;
        try {
            int v = (int) Double.parseDouble(String.valueOf(o).trim());
            return v < 1 ? def : v;
        } catch (Exception e) { return def; }
    }
}