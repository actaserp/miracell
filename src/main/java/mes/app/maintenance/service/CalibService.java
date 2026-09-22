package mes.app.maintenance.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;

/**
 * 교정계획점검표 (F706-3)
 *
 *  ● (예정) 는 서버가 저장하지 않는다 — 화면이 기준월 + 주기로 계산한다.
 *  여기서 다루는 것은 «계측기» 와 «월별 결과» 뿐이다.
 *
 *  성적서 파일은 공용 첨부(attach_file)에 TableName='CALIB_RECORD' 로 붙는다.
 *  (멸균 배치와 같은 방식 — 파일 실체·저장 경로는 공용 쪽이 맡는다)
 */
@Service
public class CalibService {

	/**
	 * 공용 첨부 키 — 화면의 업로더와 같아야 한다.
	 *   TableName = CALIB, DataPk = 계측기 id, AttachName = cert_YYYYMM(대상월)
	 * ★ 결과(calib_record) id 가 아니라 «계측기 + 대상월» 로 붙인다.
	 *   결과 id 를 키로 쓰면 결과를 먼저 저장해야만 첨부할 수 있었다.
	 *   성적서만 먼저 받아 두는 경우가 흔하고, 결과를 지웠다 다시 넣어도 파일이 그 칸에 그대로 남는다.
	 */
	public static final String ATT_TABLE = "CALIB";
	public static final String ATT_PREFIX = "cert_";

	@Autowired
	SqlRunner sqlRunner;

	// ─────────────────────────────────────────────────────────────
	// 조회
	// ─────────────────────────────────────────────────────────────

	/**
	 * 한 해의 점검표 — 계측기 목록 + 그 해의 결과.
	 * 화면은 계측기마다 기준월·주기로 ● 를 계산하고, 결과가 있는 달은 ✓ 로 바꾼다.
	 */
	public Map<String, Object> getPlan(int year, Integer factoryId, String keyword,
									   boolean includeUnused, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("fid", factoryId)
				.addValue("kw", (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim().toUpperCase() + "%")
				.addValue("from", year + "-01")
				.addValue("to", year + "-12")
				.addValue("tb", ATT_TABLE)
				.addValue("spjangcd", spjangcd);

		String sql = """
				select i.id
				     , i."MgmtNo"      as mgmt_no
				     , i."DeviceNo"    as device_no
				     , i."Name"        as name
				     , i."CycleMonth"  as cycle_month
				     , i."BaseYm"      as base_ym
				     , i."Factory_id"  as factory_id
				     , f."Name"        as factory_name
				     , i."UseYN"       as use_yn
				     , i."SortNo"      as sort_no
				     , i."Description" as description
				  from calib_instrument i
				  left join factory f on f.id = i."Factory_id"
				 where coalesce(i."_status",'a') = 'a'
				   and (cast(:fid as integer) is null or i."Factory_id" = cast(:fid as integer))
				   and (cast(:kw as varchar) is null
				        or upper(coalesce(i."Name",''))     like cast(:kw as varchar)
				        or upper(coalesce(i."MgmtNo",''))   like cast(:kw as varchar)
				        or upper(coalesce(i."DeviceNo",'')) like cast(:kw as varchar))
				""";
		if (!includeUnused) sql += "   and coalesce(i.\"UseYN\",'Y') = 'Y'\n";
		sql += " order by coalesce(i.\"SortNo\", 999999), i.\"MgmtNo\", i.\"DeviceNo\", i.id";

		List<Map<String, Object>> instruments = this.sqlRunner.getRows(sql, p);

		List<Map<String, Object>> records = this.sqlRunner.getRows("""
				select r.id
				     , r."CalibInstrument_id" as instrument_id
				     , r."PlanYm"             as plan_ym
				     , to_char(r."DoneDate",'yyyy-mm-dd') as done_date
				     , r."Result"             as result
				     , r."Agency"             as agency
				     , r."CertNo"             as cert_no
				     , r."Description"        as description
				  from calib_record r
				 where coalesce(r."_status",'a') = 'a'
				   and r."PlanYm" between :from and :to
				""", p);

		// 칸별 첨부 수 — 결과가 없는 칸에도 파일이 있을 수 있다
		p.addValue("an_from", ATT_PREFIX + year + "01").addValue("an_to", ATT_PREFIX + year + "12");
		List<Map<String, Object>> files = this.sqlRunner.getRows("""
				select af."DataPk"                      as instrument_id
				     , substr(af."AttachName", 6, 4) || '-' || substr(af."AttachName", 10, 2) as plan_ym
				     , count(*)                         as file_cnt
				  from attach_file af
				 where af."TableName" = :tb
				   and af."AttachName" between :an_from and :an_to
				 group by af."DataPk", af."AttachName"
				""", p);

		Map<String, Object> out = new HashMap<>();
		out.put("instruments", instruments);
		out.put("records", records);
		out.put("files", files);
		return out;
	}

	// ─────────────────────────────────────────────────────────────
	// 계측기
	// ─────────────────────────────────────────────────────────────

	public AjaxResult saveInstrument(Integer id, String mgmtNo, String deviceNo, String name,
									 Integer cycleMonth, String baseYm, Integer factoryId,
									 String useYn, Integer sortNo, String description,
									 User user, String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.success = false;

		if (name == null || name.isBlank())       { r.message = "계측기명을 입력하세요."; return r; }
		if (cycleMonth == null || cycleMonth <= 0) { r.message = "교정 주기(개월)를 1 이상으로 입력하세요."; return r; }
		if (baseYm == null || !baseYm.matches("\\d{4}-\\d{2}")) {
			r.message = "기준월을 YYYY-MM 형식으로 입력하세요. (예: 2026-01)"; return r;
		}

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("mgmt", trim(mgmtNo))
				.addValue("dev", trim(deviceNo))
				.addValue("name", name.trim())
				.addValue("cyc", cycleMonth)
				.addValue("base", baseYm)
				.addValue("fid", factoryId)
				.addValue("use", (useYn == null || useYn.isBlank()) ? "Y" : useYn)
				.addValue("sort", sortNo)
				.addValue("desc", trim(description))
				.addValue("uid", user.getId())
				.addValue("spjangcd", spjangcd);

		if (id == null) {
			Map<String, Object> row = this.sqlRunner.getRow("""
					insert into calib_instrument
					  ("MgmtNo","DeviceNo","Name","CycleMonth","BaseYm","Factory_id",
					   "UseYN","SortNo","Description","_creater_id",spjangcd)
					values (:mgmt, :dev, :name, :cyc, :base, :fid,
					        :use, :sort, :desc, :uid, :spjangcd)
					returning id
					""", p);
			r.data = row;
		} else {
			this.sqlRunner.execute("""
					update calib_instrument
					   set "MgmtNo" = :mgmt, "DeviceNo" = :dev, "Name" = :name,
					       "CycleMonth" = :cyc, "BaseYm" = :base, "Factory_id" = :fid,
					       "UseYN" = :use, "SortNo" = :sort, "Description" = :desc,
					       "_modified" = now(), "_modifier_id" = :uid
					 where id = :id
					""", p);
			r.data = Map.of("id", id);
		}
		r.success = true;
		r.message = "저장했습니다.";
		return r;
	}

	/**
	 * 계측기 삭제 — 논리삭제.
	 * ★ 결과가 있으면 지우지 않는다. 교정 이력은 심사 때 보여줘야 하는 기록이라,
	 *   더 안 쓰는 계측기는 「사용 안 함」 으로 돌리게 한다.
	 */
	public AjaxResult deleteInstrument(int id, User user) {
		AjaxResult r = new AjaxResult();
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("uid", user.getId());

		int recs = this.sqlRunner.queryForCount("""
				select count(*) from calib_record
				 where "CalibInstrument_id" = :id and coalesce("_status",'a') = 'a'
				""", p);
		if (recs > 0) {
			r.success = false;
			r.message = "점검 결과가 " + recs + "건 있어 삭제할 수 없습니다.\n"
					+ "더 이상 쓰지 않는 계측기라면 「사용 안 함」으로 바꿔 주세요.";
			return r;
		}
		this.sqlRunner.execute("""
				update calib_instrument set "_status" = 'd', "_modified" = now(), "_modifier_id" = :uid
				 where id = :id
				""", p);
		r.success = true;
		r.message = "삭제했습니다.";
		return r;
	}

	// ─────────────────────────────────────────────────────────────
	// 월별 결과
	// ─────────────────────────────────────────────────────────────

	/**
	 * 결과 저장 — (계측기, 대상월) 당 하나. 이미 있으면 덮어쓴다.
	 * 저장한 id 를 돌려준다 — 화면은 이 id 로 성적서를 첨부한다(첨부는 결과가 먼저 있어야 한다).
	 */
	@Transactional
	public AjaxResult saveRecord(int instrumentId, String planYm, String doneDate, String result,
								 String agency, String certNo, String description,
								 User user, String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.success = false;

		if (planYm == null || !planYm.matches("\\d{4}-\\d{2}")) { r.message = "대상월이 올바르지 않습니다."; return r; }
		if (doneDate == null || doneDate.isBlank())              { r.message = "점검일을 입력하세요."; return r; }
		if (!"pass".equals(result) && !"fail".equals(result))    { r.message = "결과(적합/부적합)를 선택하세요."; return r; }
		// 부적합이면 조치 내용이 있어야 한다 — 비어 있으면 심사 지적 사항이 된다
		if ("fail".equals(result) && (description == null || description.isBlank())) {
			r.message = "부적합이면 조치 내용을 입력하세요."; return r;
		}

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("iid", instrumentId)
				.addValue("ym", planYm)
				.addValue("dd", doneDate)
				.addValue("res", result)
				.addValue("ag", trim(agency))
				.addValue("cert", trim(certNo))
				.addValue("desc", trim(description))
				.addValue("uid", user.getId())
				.addValue("spjangcd", spjangcd);

		Map<String, Object> cur = this.sqlRunner.getRow("""
				select id from calib_record
				 where "CalibInstrument_id" = :iid and "PlanYm" = :ym and coalesce("_status",'a') = 'a'
				""", p);

		Integer recId;
		if (cur == null) {
			Map<String, Object> row = this.sqlRunner.getRow("""
					insert into calib_record
					  ("CalibInstrument_id","PlanYm","DoneDate","Result","Agency","CertNo",
					   "Description","_creater_id",spjangcd)
					values (:iid, :ym, cast(:dd as date), :res, :ag, :cert, :desc, :uid, :spjangcd)
					returning id
					""", p);
			recId = CommonUtil.tryIntNull(row.get("id"));
		} else {
			recId = CommonUtil.tryIntNull(cur.get("id"));
			p.addValue("id", recId);
			this.sqlRunner.execute("""
					update calib_record
					   set "DoneDate" = cast(:dd as date), "Result" = :res, "Agency" = :ag,
					       "CertNo" = :cert, "Description" = :desc,
					       "_modified" = now(), "_modifier_id" = :uid
					 where id = :id
					""", p);
		}

		r.success = true;
		r.data = Map.of("id", recId);
		r.message = "저장했습니다.";
		return r;
	}

	/**
	 * 결과 삭제 — 논리삭제. 첨부된 성적서는 공용 첨부에 남는다
	 * (같은 달을 다시 저장하면 새 id 로 붙으므로 옛 파일과 섞이지 않는다).
	 */
	public AjaxResult deleteRecord(int id, User user) {
		AjaxResult r = new AjaxResult();
		this.sqlRunner.execute("""
				update calib_record set "_status" = 'd', "_modified" = now(), "_modifier_id" = :uid
				 where id = :id
				""", new MapSqlParameterSource().addValue("id", id).addValue("uid", user.getId()));
		r.success = true;
		r.message = "삭제했습니다.";
		return r;
	}

	private static String trim(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}
}