package mes.app.production.service;

import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 작업 조원(여러 명) 저장·조회 공용 서비스.
 *
 * 테이블은 {@code mat_produce_member} 를 그대로 쓰되 소스 축을 넓혔다.
 * (work_member_migration.sql 참고)
 *
 * <pre>
 *   SourceTableName   SourceDataPk        공정
 *   ───────────────   ─────────────────   ──────────────────────────────
 *   mat_produce       mat_produce.id      조립·블리스터·융착·포장 (1공장)
 *   wash_work         wash_work.id        세척            (1공장)
 *   mcell_unit_step   mcell_unit_step.id  조립            (2공장 M-CELL)
 *   insp_result       insp_result.id      검사            (2공장 M-CELL)
 *   mcell_unit        mcell_unit.id       수리            (2공장 M-CELL)
 * </pre>
 *
 * 규약 (조립 BSC 와 동일)
 * <ul>
 *   <li>대표(첫 선택자) = 각 테이블의 {@code Actor_id} 에 그대로 저장 — 기존 집계 무손상</li>
 *   <li>대표도 여기에 {@code IsLeader='Y'} 로 1행 들어간다 — 명단을 한 곳에서 다 뽑기 위해</li>
 *   <li>조원 없이 1인 작업이면 대표 1행만 남는다</li>
 * </ul>
 */
@Service
public class WorkMemberService {

    public static final String SRC_MAT_PRODUCE = "mat_produce";
    public static final String SRC_WASH_WORK   = "wash_work";
    public static final String SRC_MCELL_STEP  = "mcell_unit_step";
    public static final String SRC_INSP_RESULT = "insp_result";
    public static final String SRC_MCELL_UNIT  = "mcell_unit";

    @Autowired
    private SqlRunner sqlRunner;

    // =====================================================================
    // 저장 — 전량 교체(delete-then-insert). 재배정이 흔해서 부분 갱신보다 안전하다.
    // =====================================================================

    /**
     * @param table      소스 테이블명 (위 SRC_* 상수)
     * @param pk         소스 PK
     * @param actorId    대표(person.id). null 이면 아무것도 저장하지 않는다.
     * @param memberIds  조원 person.id — 콤마 문자열. 대표가 섞여 있어도 무방(중복 제거).
     */
    @Transactional
    public void save(String table, Integer pk, Integer actorId, String memberIds,
                     User user, String spjangcd) {
        save(table, pk, actorId, parseIds(memberIds), user, spjangcd);
    }

    @Transactional
    public void save(String table, Integer pk, Integer actorId, List<Integer> memberIds,
                     User user, String spjangcd) {
        if (table == null || pk == null || actorId == null) return;

        clear(table, pk);

        // 대표를 항상 맨 앞에. LinkedHashSet 으로 중복 제거하면서 순서를 지킨다.
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        ids.add(actorId);
        if (memberIds != null) {
            for (Integer id : memberIds) if (id != null) ids.add(id);
        }

        for (Integer pid : ids) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("table", table);
            p.addValue("pk", pk);
            p.addValue("pid", pid);
            p.addValue("leader", pid.equals(actorId) ? "Y" : "N");
            p.addValue("userId", user == null ? null : user.getId());
            p.addValue("spjangcd", spjangcd == null ? "ZZ" : spjangcd);
            this.sqlRunner.execute("""
                    INSERT INTO mat_produce_member
                        ("SourceTableName","SourceDataPk","MatProduce_id","Person_id","IsLeader",
                         "_status","_created","_creater_id",spjangcd)
                    VALUES (:table,:pk,NULL,:pid,:leader,'a',now(),:userId,:spjangcd)
                    """, p);
        }
    }

    /** 소스 1건의 조원 전부 삭제 (하드 삭제 — 재고·실적에 영향 없음) */
    @Transactional
    public void clear(String table, Integer pk) {
        if (table == null || pk == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("table", table).addValue("pk", pk);
        this.sqlRunner.execute("""
                DELETE FROM mat_produce_member
                 WHERE "SourceTableName" = :table AND "SourceDataPk" = :pk
                """, p);
    }

    // =====================================================================
    // 조회
    // =====================================================================

    /** 이름 콤마 문자열 (대표 먼저). 없으면 null */
    public String namesOf(String table, Integer pk) {
        if (table == null || pk == null) return null;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("table", table).addValue("pk", pk);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT string_agg(pr."Name", ', '
                                  ORDER BY (mpm."IsLeader" = 'Y') DESC, pr."Name") AS names
                  FROM mat_produce_member mpm
                  JOIN person pr ON pr.id = mpm."Person_id"
                 WHERE mpm."SourceTableName" = :table
                   AND mpm."SourceDataPk"    = :pk
                   AND COALESCE(mpm."_status",'a') = 'a'
                """, p);
        return (row == null) ? null : (String) row.get("names");
    }

    /** person.id 콤마 문자열 (대표 먼저). 화면 재편성 시 선택 상태 복원용 */
    public String idsOf(String table, Integer pk) {
        if (table == null || pk == null) return null;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("table", table).addValue("pk", pk);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT string_agg(mpm."Person_id"::text, ','
                                  ORDER BY (mpm."IsLeader" = 'Y') DESC, mpm."Person_id") AS ids
                  FROM mat_produce_member mpm
                 WHERE mpm."SourceTableName" = :table
                   AND mpm."SourceDataPk"    = :pk
                   AND COALESCE(mpm."_status",'a') = 'a'
                """, p);
        return (row == null) ? null : (String) row.get("ids");
    }

    public List<Map<String, Object>> listOf(String table, Integer pk) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("table", table).addValue("pk", pk);
        return this.sqlRunner.getRows("""
                SELECT mpm."Person_id" AS person_id
                     , pr."Name"       AS person_name
                     , mpm."IsLeader"  AS is_leader
                  FROM mat_produce_member mpm
                  JOIN person pr ON pr.id = mpm."Person_id"
                 WHERE mpm."SourceTableName" = :table
                   AND mpm."SourceDataPk"    = :pk
                   AND COALESCE(mpm."_status",'a') = 'a'
                 ORDER BY (mpm."IsLeader" = 'Y') DESC, pr."Name"
                """, p);
    }

    /**
     * 목록 SQL 안에 그대로 끼워 넣는 스칼라 서브쿼리 조각.
     *
     * <p>★ LATERAL 조인으로 붙이면 조원 수만큼 행이 복제되어
     * 같은 그룹의 COUNT/SUM 이 부풀어 오른다. 반드시 스칼라 서브쿼리로 쓸 것.
     *
     * <pre>
     *   String sql = "SELECT ww.id, " + WorkMemberService.namesSql("wash_work", "ww.id") + " AS members ...";
     * </pre>
     */
    public static String namesSql(String table, String pkExpr) {
        return "(SELECT string_agg(p2.\"Name\", ', ' ORDER BY (m2.\"IsLeader\"='Y') DESC, p2.\"Name\") "
                + "   FROM mat_produce_member m2 JOIN person p2 ON p2.id = m2.\"Person_id\" "
                + "  WHERE m2.\"SourceTableName\" = '" + table + "' AND m2.\"SourceDataPk\" = " + pkExpr
                + "    AND COALESCE(m2.\"_status\",'a')='a')";
    }

    public static String idsSql(String table, String pkExpr) {
        return "(SELECT string_agg(m2.\"Person_id\"::text, ',' ORDER BY (m2.\"IsLeader\"='Y') DESC, m2.\"Person_id\") "
                + "   FROM mat_produce_member m2 "
                + "  WHERE m2.\"SourceTableName\" = '" + table + "' AND m2.\"SourceDataPk\" = " + pkExpr
                + "    AND COALESCE(m2.\"_status\",'a')='a')";
    }

    public static String countSql(String table, String pkExpr) {
        return "(SELECT COUNT(*) FROM mat_produce_member m2 "
                + "  WHERE m2.\"SourceTableName\" = '" + table + "' AND m2.\"SourceDataPk\" = " + pkExpr
                + "    AND COALESCE(m2.\"_status\",'a')='a')";
    }

    // =====================================================================

    public static List<Integer> parseIds(String csv) {
        List<Integer> list = new ArrayList<>();
        if (csv == null || csv.isBlank()) return list;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try { list.add(Integer.valueOf(t)); } catch (NumberFormatException ignore) { }
        }
        return list;
    }
}