package mes.app.production.service;

import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * M-CELL 제조문서 (2공장) — 유닛 1대 기준 문서 데이터 + 첨부.
 *
 * 문서 4종 (모델 M2 / M4 / M4A 별 엑셀 양식)
 *   F705-33  제조공정관리     — 조립 스텝별 투입 로트 · S/N · 작업일자 · 작업자, 검사 성적서 번호, 포장 체크
 *   F803-6   공정검사 CHECK SHEET   — mcell_proc_insp (조립 중 공정검사)
 *   F803-7   완제품검사 CHECK SHEET — insp_result (검사 공정)
 *   F803-8   최종검사 CHECK SHEET   — mcell_pack_insp (포장 화면)
 *
 * 엑셀은 화면(mcell_doc.js)이 양식 파일에 값을 채워 만든다. 서버는 값만 준다.
 * 첨부 = attach_file (TableName='mcell_unit', DataPk=유닛 id, AttachName='MCELL_DOC_<문서>')
 */
@Service
public class McellDocService {

	@Autowired SqlRunner sqlRunner;

	public static final String ATT_TABLE = "mcell_unit";
	public static final String ATT_PREFIX = "MCELL_DOC_";
	public static final Set<String> DOCS = Set.of("F705-33", "F803-6", "F803-7", "F803-8");
	public static final int WC_PACK = 56;

	/** 유닛 찾기 — unit_id 또는 로트번호 */
	public Integer resolveUnit(Integer unitId, String lotNumber) {
		if (unitId != null) return unitId;
		if (lotNumber == null || lotNumber.isBlank()) return null;
		Map<String, Object> r = this.sqlRunner.getRow("""
                SELECT id FROM mcell_unit WHERE "LotNumber" = :lot AND COALESCE("_status",'a')='a'
                 ORDER BY (CASE WHEN "State" IN ('packed','pass') THEN 0 ELSE 1 END), id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("lot", lotNumber.trim()));
		return r == null ? null : asInt(r.get("id"));
	}

	/** 문서 4종에 들어갈 값 전부 */
	public Map<String, Object> getUnitDoc(Integer unitId) {
		Map<String, Object> out = new LinkedHashMap<>();
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("uid", unitId);

		Map<String, Object> unit = this.sqlRunner.getRow("""
                SELECT mu.id AS unit_id, mu."UnitNo" AS unit_no, mu."SerialNo" AS serial_no, mu."LotNumber" AS lot_number,
                       mu."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name, mu."State" AS state,
                       to_char(mu."EndTime",'yyyy-mm-dd') AS end_date
                  FROM mcell_unit mu JOIN material m ON m.id = mu."Material_id"
                 WHERE mu.id = :uid
                """, p);
		if (unit == null) return out;
		out.put("unit", unit);

		// ── 조립 스텝 + 투입 로트 ──
		List<Map<String, Object>> steps = this.sqlRunner.getRows("""
                SELECT st.id AS step_id, st."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       st."Depth" AS depth, st."State" AS state, st."Source" AS source, st."LotNumber" AS lot_number,
                       st."MatProduce_id" AS mp_id,
                       to_char(st."StartTime",'yyyy-mm-dd') AS start_date,
                       to_char(st."EndTime",'yyyy-mm-dd')   AS end_date,
                       pr."Name" AS actor_name,
                       sl."LotNumber" AS stock_lot_no
                  FROM mcell_unit_step st
                  JOIN material m ON m.id = st."Material_id"
                  LEFT JOIN person pr ON pr.id = st."Actor_id"
                  LEFT JOIN mat_lot sl ON sl.id = st."StockMatLot_id"
                 WHERE st."McellUnit_id" = :uid AND COALESCE(st."_status",'a') = 'a'
                 ORDER BY st."Depth" DESC, st."SeqNo", st.id
                """, p);
		for (Map<String, Object> s : steps) {
			Integer mpId = asInt(s.get("mp_id"));
			s.put("mats", mpId == null ? List.of() : this.sqlRunner.getRows("""
                    SELECT ml."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                           COALESCE(m."SnYN",'N') AS sn_yn,
                           EXISTS (SELECT 1 FROM bom b WHERE b."Material_id" = m.id AND b."BOMType"='manufacturing') AS is_asm,
                           string_agg(DISTINCT ml."LotNumber", ', ') AS lots,
                           SUM(c."OutputQty") AS qty
                      FROM mat_lot_cons c
                      JOIN mat_lot ml ON ml.id = c."MaterialLot_id"
                      JOIN material m ON m.id = ml."Material_id"
                     WHERE c."SourceDataPk" = :mp AND COALESCE(c."SourceTableName",'mat_produce') = 'mat_produce'
                       AND COALESCE(c."_status",'a') = 'a'
                     GROUP BY ml."Material_id", m."Code", m."Name", m."SnYN", m.id
                     ORDER BY (CASE WHEN COALESCE(m."SnYN",'N')='Y' THEN 0 ELSE 1 END), m."Name"
                    """, new MapSqlParameterSource().addValue("mp", mpId)));
		}
		out.put("steps", steps);

		// ── S/N (유닛 단위) ──
		out.put("sns", this.sqlRunner.getRows("""
                SELECT s."Material_id" AS mat_id, m."Name" AS mat_name, s."SeqNo" AS seq_no, s."SerialNo" AS serial_no,
                       s."McellUnitStep_id" AS step_id, s."LotNumber" AS lot_number
                  FROM mcell_unit_sn s JOIN material m ON m.id = s."Material_id"
                 WHERE s."McellUnit_id" = :uid AND COALESCE(s."_status",'a') = 'a'
                 ORDER BY m."Name", s."SeqNo"
                """, p));

		// ── 공정검사 (스텝별 최신 유효 회차) ──
		List<Map<String, Object>> pis = this.sqlRunner.getRows("""
                SELECT DISTINCT ON (x."McellUnitStep_id", x."InspForm_id")
                       x.id AS result_id, x."McellUnitStep_id" AS step_id, f."Code" AS form_code, f."Name" AS form_name,
                       x."TryNo" AS try_no, x."Verdict" AS verdict, x."FailReason" AS fail_reason,
                       to_char(x."EndTime",'yyyy-mm-dd') AS end_date, pr."Name" AS actor_name
                  FROM mcell_proc_insp x
                  JOIN insp_form f ON f.id = x."InspForm_id"
                  LEFT JOIN person pr ON pr.id = x."Actor_id"
                 WHERE x."McellUnit_id" = :uid AND x."State" <> 'void' AND COALESCE(x."_status",'a') = 'a'
                 ORDER BY x."McellUnitStep_id", x."InspForm_id", x."TryNo" DESC
                """, p);
		for (Map<String, Object> r : pis) {
			r.put("items", this.sqlRunner.getRows("""
                    SELECT i."SeqNo" AS seq_no, i."ItemName" AS item_name, v."Result" AS result, v."Value" AS value
                      FROM insp_form_item i
                      JOIN mcell_proc_insp x ON x."InspForm_id" = i."InspForm_id" AND x.id = :rid
                      LEFT JOIN mcell_proc_insp_item v ON v."ProcInsp_id" = x.id AND v."InspFormItem_id" = i.id
                     WHERE COALESCE(i."_status",'a') = 'a'
                     ORDER BY i."SeqNo", i.id
                    """, new MapSqlParameterSource().addValue("rid", asInt(r.get("result_id")))));
		}
		out.put("proc_insp", pis);

		// ── 완제품검사 (양식별 최신 회차) ──
		List<Map<String, Object>> fis = this.sqlRunner.getRows("""
                SELECT DISTINCT ON (r."InspForm_id")
                       r.id AS result_id, f."Code" AS form_code, f."Name" AS form_name, f."SeqNo" AS form_seq,
                       r."TryNo" AS try_no, r."Verdict" AS verdict,
                       to_char(r."EndTime",'yyyy-mm-dd') AS end_date, pr."Name" AS actor_name
                  FROM insp_result r
                  JOIN insp_form f ON f.id = r."InspForm_id"
                  LEFT JOIN person pr ON pr.id = r."Actor_id"
                 WHERE r."McellUnit_id" = :uid AND COALESCE(f."InspType",'final') = 'final'
                 ORDER BY r."InspForm_id", r."TryNo" DESC
                """, p);
		for (Map<String, Object> r : fis) {
			r.put("items", this.sqlRunner.getRows("""
                    SELECT i."SeqNo" AS seq_no, i."ItemName" AS item_name, ri."RepeatNo" AS repeat_no,
                           ri."Result" AS result, ri."Value" AS value, pr."Name" AS actor_name
                      FROM insp_result_item ri
                      JOIN insp_form_item i ON i.id = ri."InspFormItem_id"
                      LEFT JOIN person pr ON pr.id = ri."Actor_id"
                     WHERE ri."InspResult_id" = :rid
                     ORDER BY i."SeqNo", ri."RepeatNo"
                    """, new MapSqlParameterSource().addValue("rid", asInt(r.get("result_id")))));
		}
		out.put("final_insp", fis);

		// ── 포장 (박스) + 포장 체크 · 최종검사 ──
		Map<String, Object> pack = this.sqlRunner.getRow("""
                SELECT mp.id AS mp_id, to_char(mp."EndTime",'yyyy-mm-dd') AS end_date, pr."Name" AS actor_name,
                       eq."Name" AS equipment_name,
                       (SELECT ml."MakerLotNo" FROM mat_lot ml
                         WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=mp.id LIMIT 1) AS box_label
                  FROM mat_produce mp
                  LEFT JOIN person pr ON pr.id = mp."Actor_id"
                  LEFT JOIN equ eq ON eq.id = mp."Equipment_id"
                 WHERE mp."LotNumber" = :lot AND mp."WorkCenter_id" = :wc AND COALESCE(mp."_status",'a') = 'a'
                 ORDER BY mp.id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("lot", str(unit.get("lot_number"))).addValue("wc", WC_PACK));
		if (pack != null) {
			pack.put("checks", this.sqlRunner.getRows("""
                    SELECT f."Code" AS form_code, i."SeqNo" AS seq_no, i."ItemName" AS item_name,
                           x."Result" AS result, x."Value" AS value, x."Dest" AS dest
                      FROM mcell_pack_insp x
                      JOIN insp_form f ON f.id = x."InspForm_id"
                      JOIN insp_form_item i ON i.id = x."InspFormItem_id"
                     WHERE x."MatProduce_id" = :mp
                     ORDER BY f."SeqNo", i."SeqNo"
                    """, new MapSqlParameterSource().addValue("mp", asInt(pack.get("mp_id")))));
		}
		out.put("pack", pack);

		// ── ★ 엑셀 매핑 (검사 양식 화면에서 관리) — 문서가 연결된 양식 전부 ──
		//   화면이 유닛 모델(M2/M4/M4A)에 맞는 것만 골라 칸을 채운다. 없으면 내장 칸 위치를 쓴다.
		List<Map<String, Object>> dforms = this.sqlRunner.getRows("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."DocCode" AS doc_code, f."Model" AS model,
                       COALESCE(f."InspType",'final') AS insp_type, f."CellMap" AS cell_map
                  FROM insp_form f
                 WHERE f."DocCode" IS NOT NULL AND COALESCE(f."UseYN",'Y')='Y' AND COALESCE(f."_status",'a')='a'
                 ORDER BY f."DocCode", f."SeqNo", f.id
                """, new MapSqlParameterSource());
		for (Map<String, Object> f : dforms) {
			f.put("items", this.sqlRunner.getRows("""
                    SELECT i."SeqNo" AS seq_no, i."ItemName" AS item_name, i."CellMap" AS cell_map,
                           i."Material_id" AS material_id
                      FROM insp_form_item i
                     WHERE i."InspForm_id" = :fid AND COALESCE(i."_status",'a')='a'
                     ORDER BY i."SeqNo", i.id
                    """, new MapSqlParameterSource().addValue("fid", asInt(f.get("form_id")))));
		}
		out.put("doc_forms", dforms);

		// ── ★ 양식 버전 — 조립 시작일 기준으로 유효했던 버전 (모델|문서 → 버전) ──
		out.put("doc_ver", getUnitVersions(unitId));

		// ── ★ 진행 현황용 «해야 할 개수» ──
		Map<String, Object> exp = this.sqlRunner.getRow("""
                SELECT (SELECT COUNT(*) FROM mcell_unit_step st
                         WHERE st."McellUnit_id" = :uid AND COALESCE(st."_status",'a')='a'
                           AND COALESCE(st."Source",'build') <> 'stock')                        AS step_total,
                       (SELECT COUNT(*) FROM mcell_unit_step st
                         WHERE st."McellUnit_id" = :uid AND COALESCE(st."_status",'a')='a'
                           AND st."State" = 'done')                                             AS step_done,
                       (SELECT COUNT(*) FROM mcell_unit_step st
                         WHERE st."McellUnit_id" = :uid AND COALESCE(st."_status",'a')='a'
                           AND EXISTS (SELECT 1 FROM insp_form f JOIN insp_form_mat fm ON fm."InspForm_id" = f.id
                                        WHERE fm."Material_id" = st."Material_id" AND f."InspType" = 'process'
                                          AND COALESCE(f."UseYN",'Y')='Y' AND COALESCE(f."_status",'a')='a'))  AS proc_total,
                       (SELECT COUNT(*) FROM insp_form f JOIN insp_form_mat fm ON fm."InspForm_id" = f.id
                         JOIN mcell_unit mu ON mu.id = :uid AND fm."Material_id" = mu."Material_id"
                         WHERE COALESCE(f."InspType",'final') = 'final'
                           AND COALESCE(f."UseYN",'Y')='Y' AND COALESCE(f."_status",'a')='a')    AS final_total
                """, p);
		out.put("expected", exp);

		// ── 첨부 개수 ──
		Map<String, Object> att = new LinkedHashMap<>();
		for (Map<String, Object> a : this.sqlRunner.getRows("""
                SELECT "AttachName" AS an, COUNT(*) AS c FROM attach_file
                 WHERE "TableName" = :tb AND "DataPk" = :uid GROUP BY "AttachName"
                """, new MapSqlParameterSource().addValue("tb", ATT_TABLE).addValue("uid", unitId))) {
			att.put(str(a.get("an")).replace(ATT_PREFIX, ""), a.get("c"));
		}
		out.put("attach_cnt", att);
		return out;
	}

	// =====================================================================
	// ★ 양식 버전 관리
	//   버전은 쌓이기만 한다. 유닛은 «조립 시작일에 유효했던 버전»을 쓴다.
	//   최신 버전 = MapJson NULL(지금 매핑) · 예전 버전 = 그 때 매핑을 얼려 둔 JSON + 따로 보관한 양식 파일
	// =====================================================================
	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	public Map<String, Object> getUnitVersions(Integer unitId) {
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			for (Map<String, Object> v : this.sqlRunner.getRows("""
                    WITH u AS (
                        SELECT COALESCE((SELECT MIN(st."StartTime") FROM mcell_unit_step st
                                          WHERE st."McellUnit_id" = mu.id AND COALESCE(st."_status",'a')='a'),
                                        mu."_created")::date AS d
                          FROM mcell_unit mu WHERE mu.id = :uid)
                    SELECT DISTINCT ON (v."Model", v."DocCode")
                           v.id AS ver_id, v."Model" AS model, v."DocCode" AS doc_code, v."RevNo" AS rev_no,
                           to_char(v."EffectiveFrom",'yyyy-mm-dd') AS effective_from, v."MapJson" AS map_json
                      FROM mcell_doc_ver v CROSS JOIN u
                     WHERE v."UseYN" = 'Y'
                     ORDER BY v."Model", v."DocCode", (v."EffectiveFrom" <= u.d) DESC,
                              (CASE WHEN v."EffectiveFrom" <= u.d THEN u.d - v."EffectiveFrom" ELSE v."EffectiveFrom" - u.d END),
                              v."RevNo" DESC
                    """, new MapSqlParameterSource().addValue("uid", unitId))) {
				out.put(str(v.get("model")) + "|" + str(v.get("doc_code")), v);
			}
		} catch (Exception e) { /* 버전 표 패치 전 — 버전 없이 지금 매핑으로 */ }
		return out;
	}

	public List<Map<String, Object>> listVersions(String model, String doc) {
		return this.sqlRunner.getRows("""
                SELECT v.id AS ver_id, v."RevNo" AS rev_no, to_char(v."EffectiveFrom",'yyyy-mm-dd') AS effective_from,
                       v."UseYN" AS use_yn, v."Note" AS note, (v."MapJson" IS NULL) AS live,
                       to_char(v."_created",'yyyy-mm-dd hh24:mi') AS created
                  FROM mcell_doc_ver v
                 WHERE v."Model" = :m AND v."DocCode" = :d
                 ORDER BY v."RevNo" DESC
                """, new MapSqlParameterSource().addValue("m", model).addValue("d", doc));
	}

	public Map<String, Object> getVersion(Integer verId) {
		return this.sqlRunner.getRow("""
                SELECT id AS ver_id, "Model" AS model, "DocCode" AS doc_code, "RevNo" AS rev_no,
                       "FilePath" AS file_path, "MapJson" AS map_json, "UseYN" AS use_yn
                  FROM mcell_doc_ver WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", verId));
	}

	private Map<String, Object> latestVersion(String model, String doc) {
		return this.sqlRunner.getRow("""
                SELECT id AS ver_id, "RevNo" AS rev_no, to_char("EffectiveFrom",'yyyy-mm-dd') AS eff, "FilePath" AS file_path
                  FROM mcell_doc_ver WHERE "Model" = :m AND "DocCode" = :d AND "UseYN" = 'Y'
                 ORDER BY "EffectiveFrom" DESC, "RevNo" DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("m", model).addValue("d", doc));
	}

	/** 지금(검사 양식 화면) 매핑을 doc_forms 모양 그대로 JSON 으로 얼린다 */
	public String freezeMap(String model, String doc) {
		List<Map<String, Object>> forms = this.sqlRunner.getRows("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."DocCode" AS doc_code, f."Model" AS model,
                       COALESCE(f."InspType",'final') AS insp_type, f."CellMap" AS cell_map
                  FROM insp_form f
                 WHERE f."DocCode" = :d AND (f."Model" IS NULL OR f."Model" = :m)
                   AND COALESCE(f."UseYN",'Y')='Y' AND COALESCE(f."_status",'a')='a'
                 ORDER BY f."SeqNo", f.id
                """, new MapSqlParameterSource().addValue("d", doc).addValue("m", model));
		for (Map<String, Object> f : forms) {
			f.put("items", this.sqlRunner.getRows("""
                    SELECT i."SeqNo" AS seq_no, i."ItemName" AS item_name, i."CellMap" AS cell_map,
                           i."Material_id" AS material_id
                      FROM insp_form_item i
                     WHERE i."InspForm_id" = :fid AND COALESCE(i."_status",'a')='a'
                     ORDER BY i."SeqNo", i.id
                    """, new MapSqlParameterSource().addValue("fid", asInt(f.get("form_id")))));
		}
		try { return JSON.writeValueAsString(forms); }
		catch (Exception e) { throw new IllegalStateException("매핑 저장 실패: " + e.getMessage()); }
	}

	/** 최신 버전 id (없으면 Rev.1 을 만든다) */
	@Transactional
	public Integer ensureLatest(String model, String doc) {
		Map<String, Object> l = latestVersion(model, doc);
		if (l != null) return asInt(l.get("ver_id"));
		Map<String, Object> r = this.sqlRunner.getRow("""
                INSERT INTO mcell_doc_ver ("DocCode","Model","RevNo","EffectiveFrom","Note")
                VALUES (:d, :m, COALESCE((SELECT MAX("RevNo") FROM mcell_doc_ver WHERE "Model"=:m AND "DocCode"=:d),0)+1,
                        DATE '2000-01-01', '최초 양식') RETURNING id
                """, new MapSqlParameterSource().addValue("d", doc).addValue("m", model));
		return asInt(r.get("id"));
	}

	/**
	 * 새 버전 등록 — 지금 최신 버전에 «지금 매핑»과 «보관한 이전 파일 경로»를 얼려 두고, 새 버전을 최신으로 넣는다.
	 * @param oldFileRel 이전 양식 파일을 보관한 경로 (mcell_doc_tpl 기준 상대경로)
	 */
	@Transactional
	public Map<String, Object> addVersion(String model, String doc, java.time.LocalDate eff, String note,
										  Integer prevVerId, String oldFileRel, Integer userId) {
		Map<String, Object> l = latestVersion(model, doc);
		if (l != null && eff.isBefore(java.time.LocalDate.parse(str(l.get("eff")))))
			throw new IllegalArgumentException("적용일은 지금 버전(Rev." + l.get("rev_no") + ") 적용일 "
					+ l.get("eff") + " 보다 앞설 수 없습니다.");
		this.sqlRunner.execute("""
                UPDATE mcell_doc_ver SET "MapJson" = :mj, "FilePath" = COALESCE(:fp, "FilePath") WHERE id = :id
                """, new MapSqlParameterSource().addValue("mj", freezeMap(model, doc)).addValue("fp", oldFileRel)
				.addValue("id", prevVerId));
		return this.sqlRunner.getRow("""
                INSERT INTO mcell_doc_ver ("DocCode","Model","RevNo","EffectiveFrom","Note","_creater_id")
                VALUES (:d, :m, (SELECT MAX("RevNo") FROM mcell_doc_ver WHERE "Model"=:m AND "DocCode"=:d)+1, :eff, :note, :uid)
                RETURNING id AS ver_id, "RevNo" AS rev_no
                """, new MapSqlParameterSource().addValue("d", doc).addValue("m", model).addValue("eff", eff)
				.addValue("note", note).addValue("uid", userId));
	}

	/**
	 * 최신 버전 «사용 안 함» — 잘못 올린 경우. 바로 앞 버전이 다시 최신이 되고,
	 * 그 버전에 얼려 둔 매핑을 검사 양식에 되돌린다. 돌려주는 값 = 되살릴 양식 파일 경로(없으면 null).
	 */
	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> disableLatest(Integer verId) {
		Map<String, Object> v = getVersion(verId);
		if (v == null) throw new IllegalArgumentException("버전을 찾을 수 없습니다.");
		String model = str(v.get("model")), doc = str(v.get("doc_code"));
		Map<String, Object> l = latestVersion(model, doc);
		if (l == null || !verId.equals(asInt(l.get("ver_id"))))
			throw new IllegalArgumentException("가장 최신 버전만 «사용 안 함» 할 수 있습니다. 예전 버전을 고치려면 새 버전으로 올리세요.");
		this.sqlRunner.execute("UPDATE mcell_doc_ver SET \"UseYN\"='N' WHERE id = :id",
				new MapSqlParameterSource().addValue("id", verId));
		Map<String, Object> prev = latestVersion(model, doc);
		if (prev == null) {   // 되돌릴 버전이 없으면 취소
			this.sqlRunner.execute("UPDATE mcell_doc_ver SET \"UseYN\"='Y' WHERE id = :id",
					new MapSqlParameterSource().addValue("id", verId));
			throw new IllegalArgumentException("이전 버전이 없어 «사용 안 함» 할 수 없습니다.");
		}
		Map<String, Object> pv = getVersion(asInt(prev.get("ver_id")));
		String mj = str(pv.get("map_json"));
		if (mj != null) {
			try {
				List<Map<String, Object>> forms = JSON.readValue(mj, List.class);
				for (Map<String, Object> f : forms) {
					Integer fid = asInt(f.get("form_id"));
					this.sqlRunner.execute("UPDATE insp_form SET \"CellMap\" = :cm WHERE id = :id",
							new MapSqlParameterSource().addValue("cm", str(f.get("cell_map"))).addValue("id", fid));
					for (Map<String, Object> it : (List<Map<String, Object>>) f.getOrDefault("items", List.of())) {
						this.sqlRunner.execute("""
                                UPDATE insp_form_item SET "CellMap" = :cm, "Material_id" = :mat
                                 WHERE "InspForm_id" = :fid AND "SeqNo" = :seq AND COALESCE("_status",'a')='a'
                                """, new MapSqlParameterSource().addValue("cm", str(it.get("cell_map")))
								.addValue("mat", asInt(it.get("material_id"))).addValue("fid", fid)
								.addValue("seq", asInt(it.get("seq_no"))));
					}
				}
			} catch (IllegalArgumentException e) { throw e; }
			catch (Exception e) { throw new IllegalStateException("이전 매핑 복원 실패: " + e.getMessage()); }
		}
		this.sqlRunner.execute("UPDATE mcell_doc_ver SET \"MapJson\" = NULL WHERE id = :id",
				new MapSqlParameterSource().addValue("id", asInt(pv.get("ver_id"))));
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("model", model); res.put("doc", doc);
		res.put("rev_no", pv.get("rev_no")); res.put("file_path", pv.get("file_path"));
		return res;
	}

	// =====================================================================
	// 첨부 (문서별)
	// =====================================================================

	public List<Map<String, Object>> getFiles(Integer unitId, String doc) {
		return this.sqlRunner.getRows("""
                SELECT id AS file_id, "FileIndex" AS file_index, "FileName" AS file_name, "ExtName" AS ext_name,
                       "FileSize" AS file_size, to_char("_created",'yyyy-mm-dd hh24:mi') AS created
                  FROM attach_file
                 WHERE "TableName" = :tb AND "DataPk" = :uid AND "AttachName" = :an
                 ORDER BY "FileIndex", id
                """, new MapSqlParameterSource().addValue("tb", ATT_TABLE).addValue("uid", unitId)
				.addValue("an", ATT_PREFIX + doc));
	}

	public Map<String, Object> getFile(Integer fileId) {
		return this.sqlRunner.getRow("""
                SELECT id AS file_id, "DataPk" AS unit_id, "AttachName" AS an, "FileName" AS file_name,
                       "FilePath" AS file_path, "ExtName" AS ext_name
                  FROM attach_file WHERE id = :id AND "TableName" = :tb AND "AttachName" LIKE 'MCELL_DOC_%'
                """, new MapSqlParameterSource().addValue("id", fileId).addValue("tb", ATT_TABLE));
	}

	public int nextIndex(Integer unitId, String doc) {
		Map<String, Object> r = this.sqlRunner.getRow("""
                SELECT COALESCE(MAX("FileIndex"),0)+1 AS nx FROM attach_file
                 WHERE "TableName" = :tb AND "DataPk" = :uid AND "AttachName" = :an
                """, new MapSqlParameterSource().addValue("tb", ATT_TABLE).addValue("uid", unitId)
				.addValue("an", ATT_PREFIX + doc));
		Integer n = r == null ? null : asInt(r.get("nx"));
		return n == null ? 1 : n;
	}

	@Transactional
	public Integer addFile(Integer unitId, String doc, int idx, String fileName, String physic, String path,
						   String ext, long size, User user, String spjangcd) {
		Map<String, Object> r = this.sqlRunner.getRow("""
                INSERT INTO attach_file ("TableName","DataPk","AttachName","FileIndex","FileName","PhysicFileName",
                                         "ExtName","FilePath","FileSize",_status,_created,_creater_id,spjangcd)
                VALUES (:tb,:uid,:an,:fi,:fn,:pn,:ex,:fp,:sz,'a',now(),:userId,:spjangcd)
                RETURNING id
                """, new MapSqlParameterSource().addValue("tb", ATT_TABLE).addValue("uid", unitId)
				.addValue("an", ATT_PREFIX + doc).addValue("fi", idx).addValue("fn", fileName).addValue("pn", physic)
				.addValue("ex", ext).addValue("fp", path).addValue("sz", (int) size)
				.addValue("userId", user == null ? null : user.getId())
				.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd));
		return r == null ? null : asInt(r.get("id"));
	}

	@Transactional
	public Map<String, Object> deleteFile(Integer fileId) {
		Map<String, Object> row = getFile(fileId);
		if (row == null) throw new IllegalArgumentException("첨부를 찾을 수 없습니다.");
		this.sqlRunner.execute("DELETE FROM attach_file WHERE id = :id",
				new MapSqlParameterSource().addValue("id", fileId));
		return row;
	}

	private static Integer asInt(Object o) { return (o == null) ? null : ((Number) o).intValue(); }
	private static String str(Object o) { return (o == null) ? null : String.valueOf(o); }
}