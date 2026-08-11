package mes.app.spc.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPC 통계분석 서비스 (멸균 로그 CSV 기반).
 *
 * ─────────────────────────────────────────────────────────────────────
 * ★ 데이터 소스: 멸균 배치에 첨부된 로그 CSV
 *
 *   기존에는 하드코딩 폴더(C:\temp\mes21\Reflow\PV)를 통째로 스캔했으나(다른 회사 리플로우 데이터),
 *   미라셀은 멸균 로그를 화면(prod_process_steril)에서 업로드하고
 *   그 경로가 attach_file 에 저장된다. 따라서 폴더스캔을 없애고
 *   조회일자에 해당하는 멸균 배치의 첨부 파일을 DB 에서 찾아 그 경로의 파일만 읽는다.
 *
 *   조회 흐름:
 *     조회일자(from~to)
 *       -> steril_batch (SterilDate BETWEEN from AND to)
 *       -> steril_batch_file (FileRole='sterilizer' AND UseForCalc='Y')  = 계산대상 로그
 *       -> attach_file (FilePath + PhysicFileName 으로 실제 파일)
 *       -> CSV 파싱 -> measure_code 컬럼 값 추출 -> 통계
 *
 * ★ 멸균 로그 CSV 포맷 (FileRole='sterilizer', UseForCalc='Y' = DATA.CSV 소수형)
 *     헤더:  Date,Time,PS,HUMI,CHAMBER,CHAMBER LL,CHAMBER BS,CHAMBER LH
 *     예:    2026-06-24,16:13:29,103.8,27.5,54.5,55.4,54.0,55.8
 *     - 콤마 구분(CSV), Date/Time 이 별도 컬럼
 *     - PS=압력, HUMI=습도, CHAMBER 4지점=챔버 온도 센서
 *
 * ★ measure_code -> CSV 컬럼 매핑
 *     PRESSURE -> PS,  HUMIDITY -> HUMI
 *     TEMP_CH  -> CHAMBER,  TEMP_LL -> CHAMBER LL,  TEMP_BS -> CHAMBER BS,  TEMP_LH -> CHAMBER LH
 * ─────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class SpcStatisticsService {

	@Autowired
	SqlRunner sqlRunner;

	// measure_code -> CSV 헤더 컬럼명(정규화 비교용은 normalize() 통과값과 매칭)
	private static final Map<String, String> MEASURE_TO_CSV = Map.of(
		"PRESSURE", "PS",
		"HUMIDITY", "HUMI",
		"TEMP_CH",  "CHAMBER",
		"TEMP_LL",  "CHAMBER LL",
		"TEMP_BS",  "CHAMBER BS",
		"TEMP_LH",  "CHAMBER LH"
	);

	// =====================================================================
	// 측정항목 콤보 / 관리기준 (기존 유지)
	// =====================================================================

	public List<Map<String, Object>> getMeasureCodes(String processCode) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("processCode", processCode);
		String sql = """
			SELECT DISTINCT
			    measure_code AS value,
			    measure_name AS text
			FROM tb_spc_std01
			WHERE process_code = :processCode
			  AND COALESCE(use_yn, 'Y') = 'Y'
			ORDER BY text
			""";
		return sqlRunner.getRows(sql, dicParam);
	}

	/**
	 * SPC 관리기준(tb_spc_std01)에 스펙이 등록된 공정 목록.
	 * 통계 화면의 공정 콤보를 이걸로 채우면, 스펙이 있는 공정만 조회 대상이 되고
	 * 나중에 다른 공정 SPC 를 등록하면 자동으로 콤보에 나타난다.
	 * process 테이블과 조인해 공정명을 함께 준다.
	 */
	public List<Map<String, Object>> getSpcProcesses() {
		String sql = """
			SELECT DISTINCT
			       s.process_code                       AS value,
			       COALESCE(p."Name", s.process_name,
			                s.process_code)              AS text
			  FROM tb_spc_std01 s
			  LEFT JOIN process p ON CAST(p.id AS varchar) = s.process_code
			 WHERE COALESCE(s.use_yn, 'Y') = 'Y'
			 ORDER BY text
			""";
		return sqlRunner.getRows(sql, new MapSqlParameterSource());
	}

	public Map<String, Object> findSpec(String processCd, String metricCd, String recipe, String itemName) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("process_cd", processCd);
		param.addValue("metric_cd", metricCd);
		param.addValue("recipe", recipe);
		param.addValue("item_name", itemName);

		String sql = """
        select spc.*,
               s."Value" as measure_cycle_unit_name
        from tb_spc_std01 spc
        left join sys_code s
               on s."Code" = spc.measure_cycle_unit
              and s."CodeType" = 'measure_cycle_unit'
        where spc.process_code = :process_cd
          and spc.measure_code = :metric_cd
          and coalesce(spc.use_yn, 'Y') = 'Y'
          and (coalesce(:recipe,'') = '' or spc.recipe = :recipe)
          and (coalesce(:item_name,'') = '' or spc.item_name = :item_name)
        order by
          case when coalesce(:recipe,'') <> '' and spc.recipe = :recipe then 0 else 1 end,
          case when coalesce(:item_name,'') <> '' and spc.item_name = :item_name then 0 else 1 end,
          spc.updated_at desc,
          spc.id desc
        limit 1
        """;

		return sqlRunner.getRow(sql, param);
	}

	// =====================================================================
	// spcList 메인
	// =====================================================================
	public Object getSpcListResult(
		String spjangcd,
		String dateFrom, String dateTo,
		String itemName, String processCode,
		String measureCode, String recipe
	) {
		// (A) 관리기준
		Map<String, Object> specRow = findSpec(processCode, measureCode, recipe, itemName);
		if (specRow == null || specRow.isEmpty()) {
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("message", "관리기준(스펙)이 등록되지 않았습니다.");
			empty.put("rows", List.of());
			empty.put("spec", Map.of());
			empty.put("kpi", emptyKpi());
			return empty;
		}

		Double target = toDouble(specRow.get("target_value"));
		Double usl = toDouble(specRow.get("usl"));
		Double lsl = toDouble(specRow.get("lsl"));
		Double ucl = toDouble(specRow.get("ucl"));
		Double lcl = toDouble(specRow.get("lcl"));

		Integer sampleSize = toInt(specRow.get("sample_size"), 1);
		Integer cycleValue = toInt(specRow.get("measure_cycle_value"), 1);
		String cycleUnit = toStr(specRow.get("measure_cycle_unit"));
		String cycleUnitName = toStr(specRow.get("measure_cycle_unit_name"));
		String unitName = toStr(specRow.get("unit_name"));
		String measureName = toStr(specRow.get("measure_name"));

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("target_value", target);
		spec.put("usl", usl);
		spec.put("lsl", lsl);
		spec.put("ucl", ucl);
		spec.put("lcl", lcl);
		spec.put("sample_size", sampleSize);
		spec.put("measure_cycle_value", cycleValue);
		spec.put("measure_cycle_unit", cycleUnit);
		spec.put("measure_cycle_unit_name", cycleUnitName);
		spec.put("unit_name", unitName);
		spec.put("measure_name", measureName);
		spec.put("recipe", toStr(specRow.get("recipe")));
		spec.put("item_name", toStr(specRow.get("item_name")));

		// (B) 기간 파싱 (datetime-local: yyyy-MM-dd'T'HH:mm)
		LocalDateTime from = parseDateTimeLocal(dateFrom);
		LocalDateTime to = parseDateTimeLocal(dateTo);
		if (from == null || to == null) throw new IllegalArgumentException("조회 일자 형식이 올바르지 않습니다.");
		if (to.isBefore(from)) throw new IllegalArgumentException("종료일이 시작일보다 빠릅니다.");

		// (C) 멸균 로그 파일 조회 + CSV 파싱 -> time/value rows
		//   기간은 steril_batch.SterilDate 로 이미 걸렀다(scanSterilLogs).
		//   찾은 배치의 CSV 는 통째로 읽는다 — CSV 내부 시각으로 다시 자르지 않는다.
		//   (테스트 데이터는 배치일과 CSV 로그일이 다를 수 있고, 실제로도 한 배치의
		//    멸균 사이클 데이터는 전부 봐야 한다.)
		List<Map<String, Object>> rows = scanSterilLogs(from, to, measureCode);

		if (rows.isEmpty()) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("spec", spec);
			result.put("unit", unitName);
			result.put("rows", List.of());
			result.put("kpi", emptyKpi());
			result.put("recipe", recipe);
			result.put("item_name", itemName);
			result.put("process_code", processCode);
			result.put("measure_code", measureCode);
			result.put("measure_name", measureName);
			return result;
		}

		// (D) KPI
		List<Double> values = rows.stream()
			.map(r -> (Double) r.get("value"))
			.filter(Objects::nonNull)
			.toList();

		Stats stats = calcStats(values);
		int limitOver = countOutOfLimit(values, ucl, lcl);
		double cpk = calcCpk(usl, lsl, stats.mean, stats.sigma);

		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("mean", stats.mean);
		kpi.put("std", stats.sigma);
		kpi.put("min", stats.min);
		kpi.put("max", stats.max);
		kpi.put("n", stats.n);
		kpi.put("limitOverCount", limitOver);
		kpi.put("cpk", Double.isNaN(cpk) ? "" : cpk);

		// (E) judge
		List<Map<String, Object>> tableRows = attachJudge(rows, ucl, lcl);

		// (F) 결과
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("spec", spec);
		result.put("unit", unitName.isBlank() ? resolveUnit(measureCode) : unitName);
		result.put("rows", tableRows);
		result.put("kpi", kpi);
		result.put("recipe", recipe);
		result.put("item_name", itemName);
		result.put("measure_name", measureName);
		return result;
	}

	// =====================================================================
	// 멸균 로그 조회 + CSV 파싱
	// =====================================================================

	/**
	 * 조회기간의 멸균 배치에서 계산대상(sterilizer + UseForCalc='Y') CSV 를 찾아
	 * measure_code 에 해당하는 컬럼값을 time/value 로 뽑는다.
	 */
	private List<Map<String, Object>> scanSterilLogs(LocalDateTime from, LocalDateTime to, String measureCode) {

		String csvCol = MEASURE_TO_CSV.get(normCode(measureCode));
		if (csvCol == null) {
			throw new IllegalArgumentException("측정항목(measure_code) 매핑이 없습니다: " + measureCode);
		}

		// 날짜 범위: SterilDate 는 date 타입 -> from/to 의 날짜부분으로 비교
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", from.toLocalDate().toString());
		p.addValue("date_to", to.toLocalDate().toString());

		String sql = """
			SELECT b.id            AS batch_id,
			       b."SterilDate"  AS steril_date,
			       b."BatchNo"     AS batch_no,
			       af."FilePath"        AS file_path,
			       af."PhysicFileName"  AS physic_name,
			       af."FileName"        AS file_name,
			       af."ExtName"         AS ext_name
			  FROM steril_batch b
			  JOIN steril_batch_file sbf ON sbf."SterilBatch_id" = b.id
			  JOIN attach_file af        ON af.id = sbf."AttachFile_id"
			 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
			   AND COALESCE(b._status, 'a') = 'a'
			   AND COALESCE(sbf."_status", 'a') = 'a'
			   AND sbf."FileRole" = 'sterilizer'
			   AND sbf."UseForCalc" = 'Y'
			 ORDER BY b."SterilDate", b.id
			""";

		List<Map<String, Object>> files = sqlRunner.getRows(sql, p);
		if (files == null || files.isEmpty()) return new ArrayList<>();

		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> f : files) {
			String filePath = toStr(f.get("file_path"));
			String physic = toStr(f.get("physic_name"));
			String origName = toStr(f.get("file_name"));

			Path path = resolveFilePath(filePath, physic, origName);
			if (path == null || !Files.exists(path)) {
				log.warn("멸균 로그 파일 없음: batch={} path={}", f.get("batch_no"), path);
				continue;
			}
			try {
				parseSterilCsv(path, from, to, csvCol, out);
			} catch (Exception ex) {
				log.warn("멸균 로그 파싱 실패: {} / {}", path, ex.getMessage());
			}
		}

		// 시간순 정렬
		out.sort(Comparator.comparing(r -> toLocalDateTimeSafe(r.get("time"))));
		return out;
	}

	/**
	 * 실제 파일 경로 조립.
	 * 저장 파일명은 PhysicFileName(UUID.csv). FilePath 는 디렉토리.
	 * 윈도우 경로(\) / 리눅스 경로(/) 모두 대응.
	 */
	private Path resolveFilePath(String dir, String physicName, String origName) {
		if (dir == null || dir.isBlank()) return null;
		String fileName = (physicName != null && !physicName.isBlank()) ? physicName : origName;
		if (fileName == null || fileName.isBlank()) return null;

		// 디렉토리 끝 구분자 정리
		String d = dir.replace('\\', '/');
		if (d.endsWith("/")) d = d.substring(0, d.length() - 1);
		try {
			return Paths.get(d, fileName);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 멸균 CSV 파싱.
	 *   헤더: Date,Time,PS,HUMI,CHAMBER,CHAMBER LL,CHAMBER BS,CHAMBER LH
	 *   Date+Time 결합 -> LocalDateTime, from~to 범위 내만.
	 *   csvCol 컬럼값 -> value(Double)
	 */
	private void parseSterilCsv(Path file, LocalDateTime from, LocalDateTime to,
								String csvCol, List<Map<String, Object>> out) throws IOException {

		// 인코딩: 대부분 ASCII/latin. UTF-8 로 열되 실패해도 계속.
		Charset cs = StandardCharsets.UTF_8;

		try (BufferedReader br = Files.newBufferedReader(file, cs)) {
			String headerLine = null;
			String line;
			// 첫 비어있지 않은 줄 = 헤더
			while ((line = br.readLine()) != null) {
				String t = line.strip();
				if (!t.isEmpty()) { headerLine = t; break; }
			}
			if (headerLine == null) return;

			String[] headers = headerLine.split(",", -1);
			Map<String, Integer> idx = new HashMap<>();
			for (int i = 0; i < headers.length; i++) {
				idx.put(normalize(headers[i]), i);
			}

			Integer dateIdx = idx.get(normalize("Date"));
			Integer timeIdx = idx.get(normalize("Time"));
			Integer valIdx  = idx.get(normalize(csvCol));
			if (dateIdx == null || timeIdx == null || valIdx == null) {
				// 헤더 매칭 실패 -> 이 파일은 포맷이 다르거나 해당 컬럼 없음
				log.warn("CSV 헤더 매칭 실패: {} (need Date,Time,{})", file.getFileName(), csvCol);
				return;
			}

			while ((line = br.readLine()) != null) {
				String t = line.strip();
				if (t.isEmpty()) continue;
				String[] cols = t.split(",", -1);
				if (dateIdx >= cols.length || timeIdx >= cols.length || valIdx >= cols.length) continue;

				LocalDateTime ts = parseCsvDateTime(cols[dateIdx], cols[timeIdx]);
				if (ts == null) continue;
				// ★ CSV 내부 시각으로는 필터하지 않는다.
				//   기간 필터는 이미 steril_batch.SterilDate 로 적용됐고,
				//   찾은 배치의 사이클 데이터는 전부 통계 대상이다.

				Double v = toDouble(cols[valIdx]);
				if (v == null) continue;

				Map<String, Object> row = new LinkedHashMap<>();
				// 화면 표시/정렬 위해 "yyyy-MM-dd HH:mm:ss" 형태로 저장
				row.put("time", ts.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
				row.put("value", v);
				out.add(row);
			}
		}
	}

	/** "2026-06-24" + "16:13:29" -> LocalDateTime */
	private LocalDateTime parseCsvDateTime(String date, String time) {
		if (date == null || time == null) return null;
		String d = date.strip();
		String tm = time.strip();
		if (d.isEmpty() || tm.isEmpty()) return null;
		// 초가 없을 수도 있으니 보정
		String[] tp = tm.split(":");
		if (tp.length == 2) tm = tm + ":00";
		try {
			return LocalDateTime.parse(d + "T" + tm);
		} catch (Exception e) {
			// yyyy-MM-dd HH:mm:ss 시도
			try {
				return LocalDateTime.parse(d + " " + tm,
					DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			} catch (Exception ex) {
				return null;
			}
		}
	}

	// =====================================================================
	// Judge / 통계 (기존 유지)
	// =====================================================================
	private List<Map<String, Object>> attachJudge(List<Map<String, Object>> rows, Double ucl, Double lcl) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Double v = (Double) r.get("value");
			String judge = "정상";
			if (v != null) {
				if (ucl != null && v > ucl) judge = "이상";
				else if (lcl != null && v < lcl) judge = "이상";
			}
			Map<String, Object> m = new LinkedHashMap<>(r);
			m.put("judge", judge);
			m.put("memo", "");
			out.add(m);
		}
		return out;
	}

	private static class Stats {
		int n;
		double mean;
		double sigma;
		double min;
		double max;
	}

	private Stats calcStats(List<Double> values) {
		Stats s = new Stats();
		s.n = values.size();
		if (s.n == 0) { s.mean = 0; s.sigma = 0; s.min = 0; s.max = 0; return s; }
		double sum = 0;
		s.min = Double.POSITIVE_INFINITY;
		s.max = Double.NEGATIVE_INFINITY;
		for (double v : values) {
			sum += v;
			if (v < s.min) s.min = v;
			if (v > s.max) s.max = v;
		}
		s.mean = sum / s.n;
		if (s.n < 2) { s.sigma = 0.0; return s; }
		double ss = 0;
		for (double v : values) { double d = v - s.mean; ss += d * d; }
		s.sigma = Math.sqrt(ss / (s.n - 1));
		return s;
	}

	private int countOutOfLimit(List<Double> values, Double ucl, Double lcl) {
		if (ucl == null && lcl == null) return 0;
		int c = 0;
		for (double v : values) {
			if (ucl != null && v > ucl) c++;
			else if (lcl != null && v < lcl) c++;
		}
		return c;
	}

	private double calcCpk(Double usl, Double lsl, double mean, double sigma) {
		if (sigma <= 0) return Double.NaN;
		Double cpu = (usl == null) ? null : (usl - mean) / (3.0 * sigma);
		Double cpl = (lsl == null) ? null : (mean - lsl) / (3.0 * sigma);
		if (cpu == null && cpl == null) return Double.NaN;
		if (cpu == null) return cpl;
		if (cpl == null) return cpu;
		return Math.min(cpu, cpl);
	}

	private Map<String, Object> emptyKpi() {
		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("mean", "");
		kpi.put("std", "");
		kpi.put("min", "");
		kpi.put("max", "");
		kpi.put("n", 0);
		kpi.put("limitOverCount", 0);
		kpi.put("cpk", "");
		return kpi;
	}

	// =====================================================================
	// util
	// =====================================================================
	private LocalDateTime parseDateTimeLocal(String s) {
		if (s == null || s.isBlank()) return null;
		try { return LocalDateTime.parse(s); } catch (Exception ignore) {}
		try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); } catch (Exception ignore) {}
		try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); } catch (Exception ignore) {}
		return null;
	}

	private String normCode(String measureCode) {
		if (measureCode == null || measureCode.isBlank())
			throw new IllegalArgumentException("측정항목(measure_code)이 비었습니다.");
		return measureCode.trim().toUpperCase();
	}

	private String normalize(String s) {
		if (s == null) return "";
		return s.replace("\uFEFF", "")
			.trim()
			.replaceAll("\\s+", "")
			.toLowerCase();
	}

	private LocalDateTime toLocalDateTimeSafe(Object timeStr) {
		if (timeStr == null) return LocalDateTime.MIN;
		try {
			return LocalDateTime.parse(String.valueOf(timeStr),
				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		} catch (Exception e) {
			return LocalDateTime.MIN;
		}
	}

	private static String toStr(Object o) { return o == null ? "" : String.valueOf(o); }
	private static Integer toInt(Object o, int def) {
		if (o == null) return def;
		try {
			if (o instanceof Number) return ((Number) o).intValue();
			return Integer.parseInt(String.valueOf(o));
		} catch (Exception e) { return def; }
	}
	private static Double toDouble(Object o) {
		if (o == null) return null;
		try {
			if (o instanceof Number) return ((Number) o).doubleValue();
			String s = String.valueOf(o).trim();
			if (s.isEmpty()) return null;
			s = s.replaceAll(",", "");
			Matcher m = Pattern.compile("(-?\\d+(\\.\\d+)?)").matcher(s);
			if (m.find()) return Double.parseDouble(m.group(1));
			return null;
		} catch (Exception e) { return null; }
	}

	private String resolveUnit(String measureCode) {
		return switch (normCode(measureCode)) {
			case "TEMP_CH", "TEMP_LL", "TEMP_BS", "TEMP_LH" -> "℃";
			case "PRESSURE" -> "N/m²";
			case "HUMIDITY" -> "%";
			default -> "";
		};
	}
}
