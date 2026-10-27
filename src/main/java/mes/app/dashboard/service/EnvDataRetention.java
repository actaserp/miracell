package mes.app.dashboard.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import mes.domain.services.SqlRunner;

/**
 * 센서 원본(equip_if.tag_data) 정리.
 *
 * ★ 수집기(OPC UA)가 3~4초마다 태그별로 저장한다 — 하루 약 33만 건.
 *   수집 주기는 우리가 못 바꾼다. 그래서 「요약해 두고 지운다」.
 *
 *   1) 어제까지의 원본을 1분 구간 평균·최소·최대로 env_tag_summary 에 남긴다
 *      (센서 14개 기준 하루 2만 줄 · 1년 약 740만 줄 — 영구 보관해도 부담 없다)
 *   2) KEEP_DAYS 보다 오래된 원본을 지운다 (요약이 끝난 구간만)
 *
 * ★ 품질 기록의 진실은 env_alarm_log(이탈·복귀·조치)와 이 요약이다.
 *   원본 전체를 영구 보관할 필요는 없다.
 * ★ 한 번에 지우지 않는다 — 5만 건씩 끊어서 지워 수집기 INSERT 를 오래 막지 않는다.
 * ★ 새벽 3시 10분. @EnableScheduling 은 EnvAlarmRecorder 에 있다.
 */
@Component
public class EnvDataRetention {

	/** 원본 보관 일수 */
	public static final int KEEP_DAYS = 30;
	private static final int BATCH = 50000;

	@Autowired
	private SqlRunner sqlRunner;

	/** ★ 요약은 매시 5분 — 지난 정시까지. 그래프가 원본을 훑는 구간이 늘 1시간 이내가 되어 빠르다. */
	@Scheduled(cron = "0 5 * * * *")
	public void hourly() {
		try { summarize(); } catch (Exception e) { e.printStackTrace(); }
	}

	/** 서버가 뜨고 1분 뒤 한 번 — 재시작으로 놓친 요약을 바로 채운다 */
	@Scheduled(initialDelay = 60000, fixedDelay = Long.MAX_VALUE)
	public void onStart() {
		try { summarize(); } catch (Exception e) { e.printStackTrace(); }
	}

	/** 원본 정리는 새벽 3시 10분 */
	@Scheduled(cron = "0 10 3 * * *")
	public void run() {
		try {
			summarize();
			purge();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/** 마지막 요약 다음 구간 ~ 지난 정시까지 요약 (첫 실행은 원본 전체) */
	public void summarize() {
		sqlRunner.execute("""
				INSERT INTO env_tag_summary (tagname, bucket, avg_val, min_val, max_val, cnt, bad_cnt)
				SELECT d.tagname
				     , date_trunc('minute', d."timestamp") AS bucket
				     , ROUND(AVG(v) FILTER (WHERE d.quality = 192), 3)
				     , MIN(v) FILTER (WHERE d.quality = 192)
				     , MAX(v) FILTER (WHERE d.quality = 192)
				     , COUNT(*)
				     , COUNT(*) FILTER (WHERE d.quality <> 192)
				  FROM equip_if.tag_data d
				  /* ★ 정확히 0 = 꺼짐 — 평균에 넣지 않는다(1분이 전부 0 이면 avg 가 NULL, 그래프가 끊긴다) */
				  CROSS JOIN LATERAL (SELECT NULLIF(CASE WHEN d.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
				                                  THEN d.value::numeric END, 0) AS v) x
				 WHERE d."timestamp" >= COALESCE((SELECT MAX(bucket) + interval '1 minute' FROM env_tag_summary),
				                                 '-infinity'::timestamp)
				   AND d."timestamp" <  date_trunc('hour', now()::timestamp)
				 GROUP BY 1, 2
				ON CONFLICT (tagname, bucket) DO NOTHING
				""", new MapSqlParameterSource());
	}

	/** 보관 기간이 지났고 요약이 끝난 원본만 지운다 */
	public void purge() {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("keep", KEEP_DAYS).addValue("batch", BATCH);
		for (int i = 0; i < 2000; i++) {   // 안전장치: 최대 1억 건
			List<Map<String, Object>> r = sqlRunner.getRows("""
					WITH lim AS (
					    SELECT LEAST(date_trunc('day', now()::timestamp) - make_interval(days => :keep),
					                 COALESCE((SELECT MAX(bucket) FROM env_tag_summary), '-infinity'::timestamp)) AS t
					), d AS (
					    DELETE FROM equip_if.tag_data
					     WHERE ctid IN (SELECT ctid FROM equip_if.tag_data, lim
					                     WHERE "timestamp" < lim.t LIMIT :batch)
					    RETURNING 1
					)
					SELECT COUNT(*) AS cnt FROM d
					""", p);
			if (r == null || r.isEmpty()) return;          // 오류(권한 등) — 다음 날 다시
			if (((Number) r.get(0).get("cnt")).intValue() < BATCH) return;
		}
	}
}