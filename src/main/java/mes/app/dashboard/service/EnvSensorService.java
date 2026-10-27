package mes.app.dashboard.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 클린룸 환경 센서 (온습도 · 차압) — 통합 대시보드와 1공장 대시보드가 같이 쓴다.
 *
 * ★ 출처는 여기 하나다.
 *   두 화면이 각자 읽으면 같은 센서를 서로 다른 기준으로 판정하게 된다.
 *
 * ★ 수집 경로를 바꿀 곳도 여기 하나다.
 *   지금은 OPC UA 서버가 equip_if.tag_data 에 넣은 것을 읽는다.
 *   Spring 에서 Milo 로 구독하게 되면 getEnv 가 캐시를 읽도록 바꾸면 된다.
 *
 * ★ 기준·위치는 env_sensor (태그 1개 = 1행). 예전 cleanroom_limit 은 더 쓰지 않는다.
 *   존 이름(세척실/조립실/검사실/내포장실)이 실제 계측기 배치와 맞지 않았고,
 *   차압계 3대를 담을 자리가 없었다.
 */
@Service
public class EnvSensorService {

	/** 이보다 오래 수신이 없으면 끊김. 수집이 7~8초 간격이라 넉넉히 잡는다. */
	public static final int STALE_SEC = 60;
	public static final int GOOD_QUALITY = 192;

	@Autowired
	private SqlRunner sqlRunner;

	private List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}

	/**
	 * 태그별 최신값.
	 *
	 * ★ LATERAL + (tagname, timestamp DESC) 인덱스로 태그당 1건만 찾는다.
	 *   범위 조건을 걸지 않는다 — 수집이 멈추면 「값 없음」이 아니라
	 *   「마지막 값 + N분 전」이 보여야 멈춘 걸 안다.
	 * ★ tag_data.timestamp 는 timestamp(타임존 없음), KST 로 들어온다.
	 * ★ value 는 문자열. 숫자가 아니면 null (캐스트 오류로 화면 전체가 죽지 않게).
	 */
	public List<Map<String, Object>> getEnv(String spjangcd) {
		String sql = """
				SELECT s.id, s.tag_name, s.kind, s.group_code, s.group_name, s.unit
				     , s.min_val, s.max_val, s.sort_no
				     , to_char(t."timestamp", 'HH24:MI:SS')                        AS ts
				     , t.quality
				     /* ★ 정확히 0 = 꺼짐(공조·장비 정지 시 수집기가 0 을 정상 품질로 넣는다). 값 없음으로 본다 */
				     , NULLIF(CASE WHEN t.value ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN t.value::numeric END, 0) AS val
				     , ROUND(EXTRACT(epoch FROM (now()::timestamp - t."timestamp"))::numeric, 0) AS age_sec
				  FROM env_sensor s
				  LEFT JOIN LATERAL (
				       SELECT d."timestamp", d.quality, d.value
				         FROM equip_if.tag_data d
				        WHERE d.tagname = s.tag_name
				        ORDER BY d."timestamp" DESC
				        LIMIT 1) t ON true
				 WHERE COALESCE(s._status,'a') = 'a'
				   AND s.use_yn = 'Y'
				   AND (CAST(:spjangcd AS varchar) IS NULL OR s.spjangcd = CAST(:spjangcd AS varchar))
				 ORDER BY s.sort_no, s.tag_name
				""";
		return nz(this.sqlRunner.getRows(sql, new MapSqlParameterSource().addValue("spjangcd", spjangcd)));
	}

	/**
	 * 통합 대시보드용 — 계측기(group_code) 하나를 존 한 칸으로 접는다.
	 *
	 *   { code, name, label, temp, humi, press, temp_min … press_max,
	 *     stale, age_sec, ts }
	 *
	 * ★ 끊긴 값·품질 불량 값은 null 로 내린다.
	 *   통합 화면은 값이 있으면 그대로 기준 판정을 한다. 멈춘 옛날 값을 넘기면
	 *   진짜 이탈과 구분되지 않는 알람이 뜬다. null 이면 「--」로 표시되고 판정하지 않는다.
	 * ★ 마지막 값 자체는 last_* 로 따로 싣는다(화면이 흐리게 보여줄 때 쓴다).
	 */
	public List<Map<String, Object>> getEnvZones(String spjangcd) {
		Map<String, Map<String, Object>> zones = new LinkedHashMap<>();
		for (Map<String, Object> r : getEnv(spjangcd)) {
			String code = String.valueOf(r.get("group_code"));
			Map<String, Object> z = zones.computeIfAbsent(code, k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("code", code);
				m.put("name", r.get("group_name"));
				m.put("label", "");
				m.put("temp", null); m.put("humi", null); m.put("press", null);
				m.put("temp_min", null); m.put("temp_max", null);
				m.put("humi_min", null); m.put("humi_max", null);
				m.put("press_min", null); m.put("press_max", null);
				m.put("stale", Boolean.TRUE);
				m.put("age_sec", null);
				m.put("ts", null);
				return m;
			});

			String kind = String.valueOf(r.get("kind"));
			z.put(kind + "_min", r.get("min_val"));
			z.put(kind + "_max", r.get("max_val"));
			z.put("last_" + kind, r.get("val"));

			Number age = (Number) r.get("age_sec");
			Number q = (Number) r.get("quality");
			boolean fresh = age != null && age.doubleValue() <= STALE_SEC;
			boolean good = q != null && q.intValue() == GOOD_QUALITY;

			if (fresh && good) {
				z.put(kind, r.get("val"));
				z.put("stale", Boolean.FALSE);
			}
			if (age != null) {
				Number cur = (Number) z.get("age_sec");
				if (cur == null || age.doubleValue() < cur.doubleValue()) {
					z.put("age_sec", age);
					z.put("ts", r.get("ts"));
				}
			}
		}
		return new ArrayList<>(zones.values());
	}

	/** 하나라도 최근 값이 있으면 true — 통합 화면의 「센서 미연동」 배지 판단용 */
	public boolean isReady(List<Map<String, Object>> zones) {
		for (Map<String, Object> z : zones) {
			if (Boolean.FALSE.equals(z.get("stale"))) return true;
		}
		return false;
	}

	/** 같은 계측기의 위치 이름은 함께 바뀐다(온도·습도 두 행) */
	public void renameGroup(String spjangcd, String groupCode, String groupName, Integer userId) {
		if (groupName == null || groupName.isBlank()) return;
		this.sqlRunner.execute("""
				UPDATE env_sensor SET group_name = :group_name, _modified = now(), _modifier_id = :user_id
				 WHERE spjangcd = :spjangcd AND group_code = :group_code
				""", new MapSqlParameterSource()
				.addValue("spjangcd", spjangcd).addValue("group_code", groupCode)
				.addValue("group_name", groupName.trim()).addValue("user_id", userId));
	}

	/**
	 * 설정 모달 저장 — 계측기 하나의 이름 + 온도·습도·차압 기준.
	 * ★ 통합·1공장 대시보드가 같은 API(/api/dashboard/main/env_limit/save)로 여기를 부른다.
	 *   한쪽에서 바꾸면 다른 쪽에도 그대로 보인다(저장소가 env_sensor 하나다).
	 *
	 * ★ 계측기에 없는 항목은 갱신할 행이 없어 자연히 무시된다
	 *   (온습도계에 차압 기준을 넣어도, 차압계에 온도 기준을 넣어도 아무 일도 안 생긴다).
	 */
	public String saveGroupLimit(String spjangcd, String groupCode, String groupName,
								 Double tMin, Double tMax, Double hMin, Double hMax,
								 Double pMin, Double pMax, Integer userId) {
		if (tMin != null && tMax != null && tMin >= tMax) return "온도 하한이 상한보다 크거나 같습니다.";
		if (hMin != null && hMax != null && hMin >= hMax) return "습도 하한이 상한보다 크거나 같습니다.";
		if (pMin != null && pMax != null && pMin >= pMax) return "차압 하한이 상한보다 크거나 같습니다.";

		/* ★ 이름은 계측기(group) 단위로 한 번만 바꾼다.
		     예전 1공장 모달은 센서 행마다 이름을 보내서, 온도 행에서 고친 이름을
		     뒤이어 저장되는 습도 행이 옛 이름으로 다시 덮어썼다(저장이 안 되는 것처럼 보였다). */
		renameGroup(spjangcd, groupCode, groupName, userId);

		Object[][] kinds = { {"temp", tMin, tMax}, {"humi", hMin, hMax}, {"press", pMin, pMax} };
		for (Object[] k : kinds) {
			this.sqlRunner.execute("""
					UPDATE env_sensor
					   SET min_val = :min_val, max_val = :max_val
					     , _modified = now(), _modifier_id = :user_id
					 WHERE spjangcd = :spjangcd AND group_code = :group_code AND kind = :kind
					""", new MapSqlParameterSource()
					.addValue("spjangcd", spjangcd).addValue("group_code", groupCode)
					.addValue("kind", k[0]).addValue("min_val", k[1]).addValue("max_val", k[2])
					.addValue("user_id", userId));
		}
		return null;
	}
	// =================================================================
	// 이탈 이력 (env_alarm_log — EnvAlarmRecorder 가 1분마다 남긴다)
	// =================================================================

	/**
	 * 기간 이력 + 진행중(기간과 무관하게 항상).
	 * ★ 조치자 이름은 계정에서 찾는다(사원 등록 없는 품질 계정도 있다).
	 */
	public List<Map<String, Object>> getAlarmLog(String spjangcd, String from, String to) {
		String sql = """
				SELECT a.id, a.group_code, a.group_name, a.kind, a.dir, a.min_val, a.max_val
				     , to_char(a.start_time, 'MM-DD HH24:MI')  AS start_at
				     , to_char(a.end_time,   'MM-DD HH24:MI')  AS end_at
				     , ROUND(EXTRACT(epoch FROM (COALESCE(a.end_time, now()::timestamp) - a.start_time)) / 60.0) AS dur_min
				     , a.start_val, a.peak_val, a.end_val
				     , a.action_text
				     , to_char(a.action_time, 'MM-DD HH24:MI') AS action_at
				     , COALESCE(NULLIF(up."Name",''), NULLIF(u.first_name,''), u.username) AS action_name
				  FROM env_alarm_log a
				  LEFT JOIN auth_user u     ON u.id = a.action_user_id
				  LEFT JOIN user_profile up ON up."User_id" = u.id
				 WHERE a._status = 'a'
				   AND (CAST(:spjangcd AS varchar) IS NULL OR a.spjangcd = CAST(:spjangcd AS varchar))
				   AND (a.end_time IS NULL
				        OR a.start_time::date BETWEEN CAST(:from AS date) AND CAST(:to AS date))
				 ORDER BY (a.end_time IS NULL) DESC, a.start_time DESC
				 LIMIT 500
				""";
		return nz(this.sqlRunner.getRows(sql, new MapSqlParameterSource()
				.addValue("spjangcd", spjangcd).addValue("from", from).addValue("to", to)));
	}

	/** 조치 내용 등록·수정 (비우면 지운다) */
	public String saveAlarmAction(Integer id, String text, Integer userId) {
		if (id == null) return "대상이 없습니다.";
		String t = text == null ? "" : text.trim();
		if (t.length() > 500) return "조치 내용은 500자 이내로 입력하세요.";
		this.sqlRunner.execute("""
				UPDATE env_alarm_log
				   SET action_text = NULLIF(:t, ''), action_user_id = :uid
				     , action_time = CASE WHEN :t = '' THEN NULL ELSE now()::timestamp END
				 WHERE id = :id
				""", new MapSqlParameterSource().addValue("id", id).addValue("t", t).addValue("uid", userId));
		return null;
	}
	// =================================================================
	// 추이 / 이력 (계측기 하나의 센서들)
	// =================================================================

	/** 위치(계측기) 목록 — 이력 화면 선택 상자 */
	public List<Map<String, Object>> getGroups(String spjangcd) {
		return nz(this.sqlRunner.getRows("""
				SELECT s.group_code, MAX(s.group_name) AS group_name
				     , STRING_AGG(s.kind, ',' ORDER BY s.sort_no) AS kinds
				  FROM env_sensor s
				 WHERE COALESCE(s._status,'a') = 'a' AND s.use_yn = 'Y'
				   AND (CAST(:spjangcd AS varchar) IS NULL OR s.spjangcd = CAST(:spjangcd AS varchar))
				 GROUP BY s.group_code
				 ORDER BY MIN(s.sort_no), s.group_code
				""", new MapSqlParameterSource().addValue("spjangcd", spjangcd)));
	}

	/** 대시보드 팝업 — 지금부터 거꾸로 N시간. 구간은 기간에 맞춰 자동. */
	public Map<String, Object> getTrend(String spjangcd, String groupCode, int hours) {
		int h = Math.max(1, Math.min(hours, 24 * 90));
		int sec = h <= 6 ? 60 : h <= 24 ? 300 : h <= 24 * 7 ? 600 : 3600;
		Map<String, Object> out = getSeries(spjangcd, groupCode, null, null, h, sec);
		out.put("hours", h);
		return out;
	}

	/**
	 * 이력 화면 — 기간(from~to, 'yyyy-MM-dd') + 간격(초).
	 * ★ 점이 센서당 5천 개를 넘으면 간격을 자동으로 늘린다(화면·엑셀이 멈추지 않게). 실제 간격은 bucket_sec 로 내린다.
	 */
	public Map<String, Object> getHistory(String spjangcd, String groupCode, String from, String to, int sec) {
		java.time.LocalDate f = java.time.LocalDate.parse(from), t = java.time.LocalDate.parse(to);
		if (t.isBefore(f)) { java.time.LocalDate x = f; f = t; t = x; }
		long rangeSec = (java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1) * 86400L;
		int s = Math.max(60, sec);
		if (rangeSec / s > 5000) s = (int) Math.ceil(rangeSec / 5000.0 / 60.0) * 60;
		return getSeries(spjangcd, groupCode, f.toString(), t.plusDays(1).toString(), 0, s);
	}

	/**
	 * 공통 — 원본(equip_if.tag_data) + 1분 요약(env_tag_summary).
	 * ★ 요약이 끝난 구간은 요약, 그 뒤(오늘 등)는 원본. 원본은 30일 뒤 지워지므로 오래된 기간은 요약이 유일한 출처다.
	 * ★ quality 192 만. 끊긴 구간은 점이 없다(화면이 선을 잇지 않는다).
	 * ★ t 는 KST 를 UTC 처럼 계산한 epoch ms — 화면은 getUTC* 로 읽는다.
	 * from/to 가 null 이면 now - hours ~ now.
	 */
	private Map<String, Object> getSeries(String spjangcd, String groupCode, String from, String to, int hours, int sec) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("spjangcd", spjangcd).addValue("g", groupCode)
				.addValue("from", from).addValue("to", to)
				.addValue("h", hours).addValue("sec", sec);

		List<Map<String, Object>> sensors = nz(this.sqlRunner.getRows("""
				SELECT s.tag_name, s.kind, s.unit, s.min_val, s.max_val, s.group_name
				  FROM env_sensor s
				 WHERE COALESCE(s._status,'a') = 'a' AND s.use_yn = 'Y'
				   AND s.group_code = :g
				   AND (CAST(:spjangcd AS varchar) IS NULL OR s.spjangcd = CAST(:spjangcd AS varchar))
				 ORDER BY s.sort_no, s.tag_name
				""", p));

		List<Map<String, Object>> points = nz(this.sqlRunner.getRows("""
				WITH s AS (
				    SELECT s.tag_name FROM env_sensor s
				     WHERE COALESCE(s._status,'a') = 'a' AND s.use_yn = 'Y' AND s.group_code = :g
				       AND (CAST(:spjangcd AS varchar) IS NULL OR s.spjangcd = CAST(:spjangcd AS varchar))
				), lim AS (
				    SELECT COALESCE(CAST(:from AS timestamp), now()::timestamp - make_interval(hours => :h)) AS t0
				         , COALESCE(CAST(:to   AS timestamp), now()::timestamp)                            AS t1
				         , COALESCE((SELECT MAX(bucket) + interval '1 minute' FROM env_tag_summary),
				                    '-infinity'::timestamp)                                                AS sum_end
				), src AS (
				    SELECT d.tagname, d."timestamp" AS ts
				         , d.value::numeric AS a, d.value::numeric AS mn, d.value::numeric AS mx
				      FROM equip_if.tag_data d, lim
				     WHERE d.tagname IN (SELECT tag_name FROM s)
				       AND d."timestamp" >= GREATEST(lim.t0, lim.sum_end) AND d."timestamp" < lim.t1
				       AND d.quality = 192
				       AND CASE WHEN d.value ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN d.value::numeric <> 0 ELSE false END   -- 0 = 꺼짐
				    UNION ALL
				    SELECT e.tagname, e.bucket, e.avg_val, e.min_val, e.max_val
				      FROM env_tag_summary e, lim
				     WHERE e.tagname IN (SELECT tag_name FROM s)
				       AND e.bucket >= lim.t0 AND e.bucket < LEAST(lim.sum_end, lim.t1)
				       AND e.avg_val IS NOT NULL
				)
				SELECT tagname
				     , (floor(extract(epoch FROM ts) / :sec) * :sec * 1000)::bigint AS t
				     , ROUND(AVG(a), 3) AS v, MIN(mn) AS lo, MAX(mx) AS hi
				  FROM src
				 GROUP BY 1, 2
				 ORDER BY 1, 2
				""", p));

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sensors", sensors);
		out.put("points", points);
		out.put("bucket_sec", sec);
		return out;
	}
}