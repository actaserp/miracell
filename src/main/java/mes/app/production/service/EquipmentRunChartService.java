package mes.app.production.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 설비 가동현황.
 *
 * ─────────────────────────────────────────────────────────────
 * ★ 이 화면의 전제가 바뀌었다 (2026-08 확정)
 *
 * 1) 겹침은 정상이다. 막지 않는다.
 *    세척 세션 1건에 품목 10종을 같이 넣으면 equ_run 이 10건 생기고
 *    그 10건은 전부 겹친다(실데이터 2028~2037 = 세척기 1호 1분 49초).
 *    세척기 한 대에 부품 열 종을 함께 넣는 건 현장에서 맞는 일이므로
 *    「설비는 동시에 하나만 돈다」는 규칙 자체가 성립하지 않는다.
 *    → 가동시간은 단순 합이 아니라 구간 합집합(union)으로 센다.
 *      단순 합이면 위 사례가 1분 49초가 아니라 약 15분으로 보고된다.
 *
 * 2) 생산에 「중지」 개념이 없다. 비가동을 만들어내지 않는다.
 *    stop_cause 마스터에 「고장」 1건뿐이고 사유를 넣을 사람이 없다.
 *    구간 사이의 빈틈은 비가동이 아니라 그냥 「그 설비를 안 쓴 시간」이다.
 *    점심시간·퇴근 후·그날 안 돌린 것이 전부 같은 빈칸이라 사유를 물을 대상이 아니다.
 *    → 컨트롤러가 빈틈마다 stop 행을 만들던 로직을 걷어냈다.
 *      stop_cause 테이블과 컬럼은 남겨둔다(나중에 고장 기록을 열 수 있게).
 *
 * 3) equ_run 은 시스템이 만들고, 사람은 시각만 고친다.
 *    생산 시작 시 행이 생기고 완료 시 닫힌다. 화면에서 신규로 만들지 않는다.
 *    → 출처 컬럼을 두지 않는다. 전부 자동 생성분이고 일부는 시각이 교정된 것뿐이다.
 *
 * 4) 열린 구간(EndDate IS NULL)은 화면에 보이되 합계에서 뺀다.
 *    지금 돌고 있는 설비야말로 제일 보고 싶은 것이라 감추면 안 되고,
 *    now() 까지로 쳐서 합계에 넣으면 유령 한 건이 그 설비 가동률을 100% 로 만든다.
 *    (실제로 취소 후 안 지워진 행 2057 이 5시간짜리 유령으로 남아 있었다)
 * ─────────────────────────────────────────────────────────────
 */
@Service
public class EquipmentRunChartService {

	@Autowired
	SqlRunner sqlRunner;

	/*
	 * ★ PostgreSQL 의 ROUND(값, 자릿수) 는 numeric 만 받는다.
	 *   EXTRACT(epoch ...) 는 double precision 이라 그대로 넘기면
	 *   「round(double precision, integer) 이름의 함수가 없음」 이 난다.
	 *   나눗셈 결과에 ::numeric 을 붙일 것.
	 */
	private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}

	private LocalDateTime startOf(String date) {
		return LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(0, 0, 0);
	}

	private LocalDateTime endOf(String date) {
		return LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(23, 59, 59);
	}

	// =================================================================
	// 목록
	// =================================================================

	/**
	 * 가동 구간 목록.
	 *
	 * RunState 를 있는 그대로 내린다 — 이전에는 컨트롤러가 전부 'run' 으로
	 * 덮어써서 상태 칼럼이 아무 의미가 없었다.
	 *
	 *   run       시작만 됨 (EndDate 없음)   → 진행중
	 *   complete  끝남                       → 가동 구간
	 *
	 * runtime_min 은 닫힌 구간만 계산한다. 열린 구간에 now() 를 넣으면
	 * 화면이 그 값을 합계에 쓰고 싶어지고, 그 순간 유령이 지표를 먹는다.
	 * 열린 구간의 경과시간은 open_min 으로 따로 내린다(표시 전용).
	 */
	public List<Map<String, Object>> getEquipmentRunChart(String dateFrom, String dateTo,
														  Integer equipmentId, String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("date_from", startOf(dateFrom))
				.addValue("date_to", endOf(dateTo))
				.addValue("equipment_id", equipmentId)
				.addValue("spjangcd", spjangcd);

		String sql = """
				SELECT er.id                                        AS id
				     , er."Equipment_id"                            AS "Equipment_id"
				     , e."Name"                                     AS "Name"
				     , er."RunState"                                AS "RunState"
				     , er."StartDate"                               AS "StartDate"
				     , er."EndDate"                                 AS "EndDate"
				     , to_char(er."StartDate", 'yyyy-mm-dd')        AS start_date
				     , to_char(er."EndDate",   'yyyy-mm-dd')        AS end_date
				     , to_char(er."StartDate", 'HH24:MI')           AS "StartTime"
				     , to_char(er."EndDate",   'HH24:MI')           AS "EndTime"
				     , CASE WHEN er."EndDate" IS NULL THEN 'Y' ELSE 'N' END AS is_open
				     , CASE WHEN er."EndDate" IS NULL THEN NULL
				            ELSE ROUND((EXTRACT(epoch FROM (er."EndDate" - er."StartDate")) / 60.0)::numeric, 1)
				       END                                          AS runtime_min
				     , CASE WHEN er."EndDate" IS NULL
				            THEN ROUND((EXTRACT(epoch FROM (now() - er."StartDate")) / 60.0)::numeric, 1)
				            ELSE NULL
				       END                                          AS open_min
				     , er."Description"                             AS "Description"
				  FROM equ_run er
				  LEFT JOIN equ e ON e.id = er."Equipment_id"
				 WHERE (CAST(:spjangcd AS varchar) IS NULL OR er.spjangcd = CAST(:spjangcd AS varchar))
				   AND er."StartDate" <= :date_to
				   AND (er."EndDate" IS NULL OR er."EndDate" >= :date_from)
				   AND (CAST(:equipment_id AS integer) IS NULL
				        OR er."Equipment_id" = CAST(:equipment_id AS integer))
				 ORDER BY e."Name", er."StartDate", er.id
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 설비별 요약 (union 가동시간)
	// =================================================================

	/**
	 * 설비별 가동시간 — 겹친 구간을 한 번만 센다.
	 *
	 * 표준적인 「구간 병합」 패턴이다.
	 *   ① 이전 행들의 종료 최대값(prev_max)을 창 함수로 구한다
	 *   ② 시작이 그보다 뒤면 새 그룹 시작 → 누적합으로 그룹번호를 만든다
	 *   ③ 그룹별 MIN(시작)~MAX(종료) 가 병합된 구간이다
	 *
	 * ★ 조회 기간 밖으로 삐져나간 부분은 잘라낸다(GREATEST/LEAST).
	 *   안 자르면 어제 시작해 오늘 끝난 구간이 오늘 하루 가동률을 100% 넘게 만든다.
	 *
	 * ★ 열린 구간은 아예 제외한다. 대신 open_cnt 로 몇 건인지 알린다.
	 *   화면이 「미종료 N건 — 합계 제외」로 표시해야 사람이 고칠 수 있다.
	 */
	public List<Map<String, Object>> getEquipmentSummary(String dateFrom, String dateTo,
														 Integer equipmentId, String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("date_from", startOf(dateFrom))
				.addValue("date_to", endOf(dateTo))
				.addValue("equipment_id", equipmentId)
				.addValue("spjangcd", spjangcd);

		String sql = """
				WITH src AS (
				    SELECT er."Equipment_id" AS eq
				         , GREATEST(er."StartDate", :date_from) AS s
				         , LEAST(er."EndDate",      :date_to)   AS e
				      FROM equ_run er
				     WHERE (CAST(:spjangcd AS varchar) IS NULL OR er.spjangcd = CAST(:spjangcd AS varchar))
				       AND er."EndDate" IS NOT NULL
				       AND er."StartDate" <= :date_to
				       AND er."EndDate"   >= :date_from
				       AND (CAST(:equipment_id AS integer) IS NULL
				            OR er."Equipment_id" = CAST(:equipment_id AS integer))
				), ord AS (
				    SELECT eq, s, e
				         , MAX(e) OVER (PARTITION BY eq ORDER BY s, e
				                        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_max
				      FROM src
				     WHERE e > s
				), grp AS (
				    SELECT eq, s, e
				         , SUM(CASE WHEN prev_max IS NULL OR s > prev_max THEN 1 ELSE 0 END)
				           OVER (PARTITION BY eq ORDER BY s, e ROWS UNBOUNDED PRECEDING) AS g
				      FROM ord
				), merged AS (
				    SELECT eq, g, MIN(s) AS s, MAX(e) AS e
				      FROM grp
				     GROUP BY eq, g
				), agg AS (
				    SELECT eq
				         , SUM(EXTRACT(epoch FROM (e - s))) AS run_sec
				         , COUNT(*)                          AS merged_cnt
				      FROM merged
				     GROUP BY eq
				), raw AS (
				    SELECT er."Equipment_id" AS eq
				         , COUNT(*)                                                AS row_cnt
				         , COUNT(*) FILTER (WHERE er."EndDate" IS NULL)             AS open_cnt
				      FROM equ_run er
				     WHERE (CAST(:spjangcd AS varchar) IS NULL OR er.spjangcd = CAST(:spjangcd AS varchar))
				       AND er."StartDate" <= :date_to
				       AND (er."EndDate" IS NULL OR er."EndDate" >= :date_from)
				       AND (CAST(:equipment_id AS integer) IS NULL
				            OR er."Equipment_id" = CAST(:equipment_id AS integer))
				     GROUP BY 1
				)
				SELECT e.id                                          AS "Equipment_id"
				     , e."Name"                                      AS "Name"
				     , COALESCE(raw.row_cnt, 0)                      AS row_cnt
				     , COALESCE(raw.open_cnt, 0)                     AS open_cnt
				     , COALESCE(agg.merged_cnt, 0)                   AS merged_cnt
				     , ROUND((COALESCE(agg.run_sec,0) / 60.0)::numeric, 1)   AS run_min
				     , ROUND((COALESCE(agg.run_sec,0) / 3600.0)::numeric, 2) AS run_hour
				     , ROUND((EXTRACT(epoch FROM (:date_to::timestamp - :date_from::timestamp)) / 3600.0)::numeric, 2)
				                                                     AS period_hour
				     , CASE WHEN EXTRACT(epoch FROM (:date_to::timestamp - :date_from::timestamp)) > 0
				            THEN ROUND((COALESCE(agg.run_sec,0)
				                 / EXTRACT(epoch FROM (:date_to::timestamp - :date_from::timestamp)) * 100)::numeric, 1)
				            ELSE 0
				       END                                           AS run_rate
				  FROM raw
				  JOIN equ e ON e.id = raw.eq
				  LEFT JOIN agg ON agg.eq = raw.eq
				 ORDER BY e."Name"
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}

	// =================================================================
	// 수정 지원
	// =================================================================

	/** 한 건 조회 (수정 팝업 · 저장 전 검증) */
	public Map<String, Object> getRunById(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id);

		String sql = """
				SELECT er.id, er."Equipment_id", e."Name", er."RunState"
				     , er."StartDate", er."EndDate", er."Description"
				  FROM equ_run er
				  LEFT JOIN equ e ON e.id = er."Equipment_id"
				 WHERE er.id = :id
				""";

		List<Map<String, Object>> rows = nz(this.sqlRunner.getRows(sql, p));
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 같은 설비의 겹치는 구간.
	 *
	 * ★ 이제 차단이 아니라 「경고」용이다. 겹침은 정상일 수 있으므로 막지 않는다.
	 *
	 * 이전 구현의 버그 둘을 함께 고쳤다.
	 *   ① spjangcd 를 파라미터로 받아놓고 SQL 에 'ZZ' 를 하드코딩했다
	 *   ② 자기 자신을 제외하지 않았다 → 수정하려 하면 항상 자기와 겹쳐서
	 *      「이미 가동 중인 기록이 있습니다」로 무조건 막혔다.
	 *      겹침 검사가 통째로 풀린 이유가 이것으로 보인다.
	 */
	public List<Map<String, Object>> getOverlaps(Timestamp startDate, Timestamp endDate,
												 Integer equipmentId, Integer excludeId, String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("start_date", startDate)
				.addValue("end_date", endDate)
				.addValue("equipment_id", equipmentId)
				.addValue("exclude_id", excludeId)
				.addValue("spjangcd", spjangcd);

		String sql = """
				SELECT er.id
				     , to_char(er."StartDate", 'yyyy-mm-dd HH24:MI') AS start_at
				     , to_char(er."EndDate",   'yyyy-mm-dd HH24:MI') AS end_at
				     , er."Description"
				  FROM equ_run er
				 WHERE (CAST(:spjangcd AS varchar) IS NULL OR er.spjangcd = CAST(:spjangcd AS varchar))
				   AND er."Equipment_id" = :equipment_id
				   AND (CAST(:exclude_id AS integer) IS NULL OR er.id <> CAST(:exclude_id AS integer))
				   AND :start_date < COALESCE(er."EndDate", now())
				   AND er."StartDate" < :end_date
				 ORDER BY er."StartDate"
				""";

		return nz(this.sqlRunner.getRows(sql, p));
	}
}