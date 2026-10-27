package mes.app.dashboard.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import mes.domain.services.SqlRunner;

/**
 * 클린룸 이탈 이력 기록기.
 *
 * ★ 화면이 아니라 서버가 판정한다.
 *   화면에서 쌓으면 「화면을 열어 둔 동안」만 남는다 — 새벽 이탈이 기록에서 빠진다.
 *   통합·1공장·벽걸이 어느 화면도 열려 있지 않아도 1분마다 돈다.
 *
 * ★ 판정은 EnvSensorService.getEnv 를 그대로 쓴다(화면과 같은 값·같은 기준).
 * ★ 연속 2회 이탈부터 기록(경계값에서 깜빡이는 것을 막는다). 발생 시각은 첫 이탈 시각.
 * ★ 끊김·품질 불량은 판정하지 않는다 — 열린 이력은 그대로 둔다.
 *   멈춘 옛 값으로 「복귀」를 찍으면 실제로 언제 돌아왔는지가 거짓이 된다.
 * ★ 서버 재시작: 열린 이력(end_time NULL)은 DB 에 있으므로 이어서 닫힌다.
 *   연속 횟수만 메모리라 재시작 직후 새 이탈은 1분 늦게 잡힐 수 있다.
 */
@Configuration
@EnableScheduling
public class EnvAlarmRecorder {

	private static final int DEBOUNCE = 2;

	@Autowired
	private EnvSensorService envSensorService;

	@Autowired
	private SqlRunner sqlRunner;

	/** sensor_id → 연속 이탈 횟수 */
	private final Map<Integer, Integer> outCnt = new HashMap<>();
	/** sensor_id → 첫 이탈 시각·값 (기록 전 대기분) */
	private final Map<Integer, Object[]> firstOut = new HashMap<>();

	@Scheduled(fixedDelay = 60000, initialDelay = 30000)
	public void tick() {
		try {
			run();
		} catch (Exception e) {
			e.printStackTrace();   // 기록기가 죽어도 화면에는 영향이 없다
		}
	}

	private static Double d(Object o) {
		return o == null ? null : ((Number) o).doubleValue();
	}

	private void run() {
		List<Map<String, Object>> rows = envSensorService.getEnv(null);
		for (Map<String, Object> r : rows) {
			Integer sid = ((Number) r.get("id")).intValue();
			Double val = d(r.get("val"));
			Double min = d(r.get("min_val"));
			Double max = d(r.get("max_val"));
			Number age = (Number) r.get("age_sec");
			Number q = (Number) r.get("quality");

			boolean fresh = age != null && age.doubleValue() <= EnvSensorService.STALE_SEC;
			boolean good = q != null && q.intValue() == EnvSensorService.GOOD_QUALITY;
			if (!fresh || !good || val == null) continue;          // 판정 불가 — 상태 유지
			if (min == null && max == null) continue;              // 기준 없음

			boolean high = max != null && val > max;
			boolean low = min != null && val < min;
			MapSqlParameterSource p = new MapSqlParameterSource()
					.addValue("sid", sid).addValue("val", val);

			if (high || low) {
				int n = outCnt.getOrDefault(sid, 0) + 1;
				outCnt.put(sid, n);
				if (n == 1) firstOut.put(sid, new Object[] { val });

				List<Map<String, Object>> open = sqlRunner.getRows(
						"SELECT id FROM env_alarm_log WHERE sensor_id = :sid AND end_time IS NULL AND _status = 'a' LIMIT 1", p);
				if (open != null && !open.isEmpty()) {
					p.addValue("id", open.get(0).get("id"));
					sqlRunner.execute("""
							UPDATE env_alarm_log
							   SET peak_val = CASE WHEN dir = 'high' THEN GREATEST(peak_val, :val)
							                       ELSE LEAST(peak_val, :val) END
							 WHERE id = :id
							""", p);
				} else if (n >= DEBOUNCE) {
					Object[] f = firstOut.getOrDefault(sid, new Object[] { val });
					p.addValue("dir", high ? "high" : "low")
							.addValue("start_val", f[0])
							.addValue("back_sec", (n - 1) * 60);
					sqlRunner.execute("""
							INSERT INTO env_alarm_log (sensor_id, tag_name, group_code, group_name, kind, dir,
							                           min_val, max_val, start_time, start_val, peak_val, spjangcd)
							SELECT s.id, s.tag_name, s.group_code, s.group_name, s.kind, :dir
							     , s.min_val, s.max_val
							     , now()::timestamp - make_interval(secs => :back_sec)
							     , :start_val, :val, s.spjangcd
							  FROM env_sensor s WHERE s.id = :sid
							""", p);
				}
			} else {
				outCnt.put(sid, 0);
				firstOut.remove(sid);
				sqlRunner.execute("""
						UPDATE env_alarm_log SET end_time = now()::timestamp, end_val = :val
						 WHERE sensor_id = :sid AND end_time IS NULL AND _status = 'a'
						""", p);
			}
		}
	}
}