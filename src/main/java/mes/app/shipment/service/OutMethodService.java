package mes.app.shipment.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 품목별 출고방법 지정.
 *
 *   fifo    선입선출  ORDER BY "InputDateTime" ASC
 *   lifo    후입선출  ORDER BY "InputDateTime" DESC
 *   manual  지정 LOT 우선 — 자동 정렬 없이 작업자가 직접 고른다
 *   NULL    미지정 — DEFAULT_METHOD 를 따른다
 *
 * ★ 이 서비스는 "설정을 저장"만 한다. 실제 출고 순서를 바꾸는 곳은 두 군데다.
 *     PackService.consumeFifoMulti      (자재 투입 FIFO 고정)
 *     ShipmentDoBService.getMatLotSearch (출하 LOT 후보 — 로트번호순)
 *   그 둘이 resolveOrderBy 를 쓰도록 고치지 않으면 화면만 바뀌고 동작은 그대로다.
 */
@Service
public class OutMethodService {

	/** DB CHECK 제약과 같은 집합 */
	public static final Set<String> METHODS = Set.of("fifo", "lifo", "manual");

	/** 미지정 품목이 따르는 값 */
	public static final String DEFAULT_METHOD = "fifo";

	@Autowired
	SqlRunner sqlRunner;

	// =================================================================
	// 조회
	// =================================================================

	/**
	 * 품목 목록 + 현재 출고방법.
	 *
	 * @param matType   품목구분(mat_grp."MaterialType") — null 이면 전체
	 * @param matGrpPk  품목그룹 id — null 이면 전체
	 * @param keyword   품목코드/품목명
	 * @param unsetOnly true 면 출고방법 미지정 품목만
	 * @param lotOnly   true 면 LOT 관리 품목만 (출고방법이 실제로 의미를 갖는 대상)
	 * @param factoryId 공장 — null 이면 전체
	 */
	public List<Map<String, Object>> getList(String matType, Integer matGrpPk, String keyword,
											 boolean unsetOnly, boolean lotOnly,
											 Integer factoryId, String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matType", blankToNull(matType));
		p.addValue("matGrpPk", matGrpPk);
		p.addValue("keyword", blankToNull(keyword) == null ? null : "%" + keyword.trim() + "%");
		p.addValue("unsetOnly", unsetOnly);
		p.addValue("lotOnly", lotOnly);
		p.addValue("factoryId", factoryId);
		p.addValue("spjangcd", blankToNull(spjangcd));

		return this.sqlRunner.getRows("""
            SELECT m.id
                 , m."Code"  AS mat_code
                 , m."Name"  AS mat_name
                 , mg."Name" AS mat_grp_name
                 , fn_code_name('mat_type', mg."MaterialType") AS mat_type_name
                 , u."Name"  AS unit_name
                 , sh."Name" AS store_name
                 , COALESCE(m."LotUseYN",'N') AS lot_use_yn
                 , m."OutMethod"                             AS own_method
                 , COALESCE(m."OutMethod", 'fifo')           AS out_method
                 , (m."OutMethod" IS NULL)                   AS is_default
                 , (SELECT count(*) FROM mat_lot ml
                     WHERE ml."Material_id" = m.id
                       AND COALESCE(ml."CurrentStock",0) > 0) AS lot_cnt
              FROM material m
              JOIN mat_grp mg      ON mg.id = m."MaterialGroup_id"
              LEFT JOIN unit u     ON u.id  = m."Unit_id"
              LEFT JOIN store_house sh ON sh.id = m."StoreHouse_id"
             WHERE (CAST(:matType AS varchar) IS NULL
                    OR mg."MaterialType" = CAST(:matType AS varchar))
               AND (CAST(:matGrpPk AS integer) IS NULL
                    OR mg.id = CAST(:matGrpPk AS integer))
               AND (CAST(:keyword AS varchar) IS NULL
                    OR m."Code" ILIKE CAST(:keyword AS varchar)
                    OR m."Name" ILIKE CAST(:keyword AS varchar))
               AND (CAST(:unsetOnly AS boolean) IS NOT TRUE
                    OR m."OutMethod" IS NULL)
               AND (CAST(:lotOnly AS boolean) IS NOT TRUE
                    OR COALESCE(m."LotUseYN",'N') = 'Y')
               AND (CAST(:factoryId AS integer) IS NULL
                    OR m."Factory_id" = CAST(:factoryId AS integer))
               AND (CAST(:spjangcd AS varchar) IS NULL
                    OR m.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY mg."MaterialType", m."Code", m."Name"
            """, p);
	}

	// =================================================================
	// 저장
	// =================================================================

	/**
	 * 품목 출고방법 일괄 저장.
	 *
	 * @param method null/빈 값이면 미지정(기본값 따름)으로 되돌린다.
	 * @return 실제로 바뀐 건수
	 */
	public int save(List<Integer> matIds, String method, Integer userId) {
		if (matIds == null || matIds.isEmpty())
			throw new IllegalArgumentException("품목을 선택하세요");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("ids", matIds);
		p.addValue("method", normalize(method));
		p.addValue("userId", userId);

		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            UPDATE material
               SET "OutMethod"    = CAST(:method AS varchar)
                 , _modified      = now()
                 , _modifier_id   = CAST(:userId AS integer)
             WHERE id IN (:ids)
            RETURNING id
            """, p);

		return rows == null ? 0 : rows.size();
	}

	// =================================================================
	// 출고 순서 — 소비처가 쓰는 곳
	// =================================================================

	/**
	 * 품목의 출고방법을 읽는다. 미지정이면 DEFAULT_METHOD.
	 *
	 * 로트 여러 건을 한 번에 정렬할 때는 품목마다 이걸 부르지 말고,
	 * 조회 SQL 에 material."OutMethod" 를 조인해서 CASE 로 푸는 편이 낫다.
	 * (아래 ORDER_BY_SQL 참고)
	 */
	public String resolveMethod(Integer matId) {
		if (matId == null) return DEFAULT_METHOD;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		Map<String, Object> r = this.sqlRunner.getRow(
				"SELECT COALESCE(\"OutMethod\", 'fifo') AS m FROM material WHERE id = :matId", p);

		return (r == null) ? DEFAULT_METHOD : String.valueOf(r.get("m"));
	}

	/**
	 * 로트 후보 정렬 절. mat_lot 별칭이 ml, material 별칭이 m 인 쿼리에 그대로 붙인다.
	 *
	 *   manual 은 정렬 기준이 없으므로 화면에서 찾기 쉬운 로트번호순으로 둔다.
	 *   이건 "자동으로 고르지 않는다"는 뜻이지 "아무 순서"라는 뜻이 아니다.
	 */
	public static final String ORDER_BY_SQL = """
            ORDER BY CASE COALESCE(m."OutMethod",'fifo')
                       WHEN 'lifo' THEN 0 ELSE 1 END
                   , CASE WHEN COALESCE(m."OutMethod",'fifo') = 'lifo'
                          THEN ml."InputDateTime" END DESC NULLS LAST
                   , CASE WHEN COALESCE(m."OutMethod",'fifo') = 'fifo'
                          THEN ml."InputDateTime" END ASC  NULLS LAST
                   , ml."LotNumber"
                   , ml.id
            """;

	/** 자바에서 정렬을 고를 때 (단일 품목 조회처럼 품목이 하나로 고정된 경우) */
	public static String orderByFor(String method) {
		if ("lifo".equals(method))
			return " ORDER BY ml.\"InputDateTime\" DESC NULLS LAST, ml.id DESC ";
		if ("manual".equals(method))
			return " ORDER BY ml.\"LotNumber\", ml.id ";
		return " ORDER BY ml.\"InputDateTime\" ASC NULLS LAST, ml.id ASC ";
	}

	// =================================================================
	// 내부
	// =================================================================

	private static String normalize(String method) {
		if (method == null || method.trim().isEmpty()) return null;
		String m = method.trim().toLowerCase();
		if (!METHODS.contains(m))
			throw new IllegalArgumentException("출고방법 값이 올바르지 않습니다 · " + method);
		return m;
	}

	private static String blankToNull(String s) {
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}

	public static Map<String, Object> ok(Object data) {
		Map<String, Object> m = new HashMap<>();
		m.put("ok", true);
		m.put("row", data);
		return m;
	}

	public static Map<String, Object> fail(String message) {
		Map<String, Object> m = new HashMap<>();
		m.put("ok", false);
		m.put("message", message);
		return m;
	}
}