package mes.app.production.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국가 마스터 CRUD — 포장 화면의 [🌐 국가 관리] 백엔드.
 *
 * 왜 PackService 가 아니라 별도 서비스인가:
 *   국가는 포장 전용 데이터가 아니라 마스터다. 지금은 포장 화면만 쓰지만
 *   나중에 다른 화면이 쓰게 되면 컨트롤러 매핑만 옮기면 되도록 로직을 분리해 둔다.
 *
 * ★ "Code" 는 업무 키다.
 *   화면의 배분(allocations[].country)·CK 투입수량 키('KR|자재코드')·CK 로트 접두가
 *   전부 이 값을 참조한다. 따라서 수정 시 Code 는 무시하고 Name/Flag 등만 반영한다.
 *   (화면에서도 코드 입력란을 잠그지만, 서버에서 한 번 더 막는다)
 *
 * ★ DML 을 getRow 로 실행하는 이유:
 *   PostgreSQL 의 RETURNING 을 써서 INSERT/UPDATE/DELETE 를 조회처럼 실행한다.
 *   SqlRunner 에 execute 계열이 있으면 그쪽으로 바꿔도 무방하다.
 */
@Service
public class CountryService {

	/** 코드 형식 — 화면 검증과 동일 규칙 */
	private static final String CODE_PATTERN = "^[A-Z0-9]{2,6}$";

	@Autowired
	SqlRunner sqlRunner;

	// =================================================================
	// 조회
	// =================================================================

	/**
	 * 국가 목록.
	 *
	 * @param includeHidden true 면 UseYN='N' 도 포함(관리 시트용).
	 *                      false 면 사용중인 국가만(배분 드롭다운용).
	 */
	public List<Map<String, Object>> getList(String spjangcd, boolean includeHidden) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);
		p.addValue("all", includeHidden);

		return this.sqlRunner.getRows("""
            SELECT c.id
                 , c."Code"   AS code
                 , c."Name"   AS name
                 , c."Flag"   AS flag
                 , c."Iso3"   AS iso3
                 , c."SortNo" AS sort_no
                 , COALESCE(c."UseYN",'Y') AS use_yn
              FROM country c
             WHERE (CAST(:spjangcd AS varchar) IS NULL OR c.spjangcd = CAST(:spjangcd AS varchar))
               AND (CAST(:all AS boolean) IS TRUE OR COALESCE(c."UseYN",'Y') = 'Y')
             ORDER BY c."SortNo", c."Code"
            """, p);
	}

	/** 1건 조회 */
	public Map<String, Object> get(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		return this.sqlRunner.getRow("""
            SELECT c.id
                 , c."Code"   AS code
                 , c."Name"   AS name
                 , c."Flag"   AS flag
                 , c."Iso3"   AS iso3
                 , c."SortNo" AS sort_no
                 , COALESCE(c."UseYN",'Y') AS use_yn
              FROM country c
             WHERE c.id = :id
            """, p);
	}

	// =================================================================
	// 저장 (신규 = Code 필요 / 수정 = Code 무시)
	// =================================================================

	/**
	 * 신규 등록. 사업장 내 Code 중복이면 예외.
	 *
	 * @return 저장된 행(id 포함)
	 */
	public Map<String, Object> insert(String code, String name, String flag, String iso3,
																		Integer sortNo, String spjangcd, Integer userId) {

		String cd = normalizeCode(code);
		String nm = (name == null) ? "" : name.trim();
		String sp = (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd;

		if (!cd.matches(CODE_PATTERN))
			throw new IllegalArgumentException("코드는 영문·숫자 2~6자입니다");
		if (nm.isEmpty())
			throw new IllegalArgumentException("국가명을 입력하세요");
		if (existsCode(cd, sp))
			throw new IllegalArgumentException("이미 등록된 코드입니다 · " + cd);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("code", cd);
		p.addValue("name", nm);
		p.addValue("flag", blankToNull(flag));
		p.addValue("iso3", blankToNull(iso3 == null ? null : iso3.toUpperCase()));
		p.addValue("sortNo", sortNo);
		p.addValue("spjangcd", sp);
		p.addValue("userId", userId);

		// 정렬순서 미지정 시 맨 뒤로 (기존 최대 + 10)
		return this.sqlRunner.getRow("""
            INSERT INTO country
                   ("Code","Name","Flag","Iso3","SortNo","UseYN",
                    _status,_created,_creater_id,spjangcd)
            VALUES (:code, :name, :flag, :iso3,
                    COALESCE(CAST(:sortNo AS integer),
                             (SELECT COALESCE(MAX("SortNo"),0)+10 FROM country
                               WHERE spjangcd = CAST(:spjangcd AS varchar))),
                    'Y', 'a', now(), CAST(:userId AS integer), CAST(:spjangcd AS varchar))
            RETURNING id
                    , "Code"   AS code
                    , "Name"   AS name
                    , "Flag"   AS flag
                    , "Iso3"   AS iso3
                    , "SortNo" AS sort_no
                    , "UseYN"  AS use_yn
            """, p);
	}

	/**
	 * 수정 — Name / Flag / Iso3 / SortNo / UseYN 만.
	 *
	 * ★ Code 는 받지 않는다. 배분·CK 투입 데이터가 참조하는 업무 키라서,
	 *   바꾸면 기존 실적의 국가 연결이 조용히 끊어진다. 변경이 필요하면 삭제 후 재등록.
	 */
	public Map<String, Object> update(Integer id, String name, String flag, String iso3,
																		Integer sortNo, String useYn, Integer userId) {

		if (id == null) throw new IllegalArgumentException("대상 id 가 없습니다");
		String nm = (name == null) ? "" : name.trim();
		if (nm.isEmpty()) throw new IllegalArgumentException("국가명을 입력하세요");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		p.addValue("name", nm);
		p.addValue("flag", blankToNull(flag));
		p.addValue("iso3", blankToNull(iso3 == null ? null : iso3.toUpperCase()));
		p.addValue("sortNo", sortNo);
		p.addValue("useYn", ("N".equalsIgnoreCase(useYn)) ? "N" : "Y");
		p.addValue("userId", userId);

		Map<String, Object> row = this.sqlRunner.getRow("""
            UPDATE country
               SET "Name"   = :name
                 , "Flag"   = :flag
                 , "Iso3"   = :iso3
                 , "SortNo" = COALESCE(CAST(:sortNo AS integer), "SortNo")
                 , "UseYN"  = :useYn
                 , _modified    = now()
                 , _modifier_id = CAST(:userId AS integer)
             WHERE id = :id
            RETURNING id
                    , "Code"   AS code
                    , "Name"   AS name
                    , "Flag"   AS flag
                    , "Iso3"   AS iso3
                    , "SortNo" AS sort_no
                    , "UseYN"  AS use_yn
            """, p);

		if (row == null) throw new IllegalArgumentException("대상 국가를 찾을 수 없습니다");
		return row;
	}

	// =================================================================
	// 삭제
	// =================================================================

	/**
	 * 하드 삭제. 사용 중이면 거부한다.
	 *
	 * ⚠ 현재 country 를 참조하는 테이블이 없어 countUsage 가 항상 0 이다.
	 *   포장 실적 테이블이 생기면 countUsage 를 채워야 실질적인 방어가 된다.
	 *   그 전까지는 화면 가드(countryUsage)가 유일한 방어선이다.
	 */
	public Map<String, Object> delete(Integer id) {
		if (id == null) throw new IllegalArgumentException("대상 id 가 없습니다");

		int used = countUsage(id);
		if (used > 0)
			throw new IllegalArgumentException("배분에 사용 중입니다 (" + used + "건) — 삭제할 수 없습니다");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		Map<String, Object> row = this.sqlRunner.getRow("""
            DELETE FROM country WHERE id = :id
            RETURNING id, "Code" AS code, "Name" AS name
            """, p);

		if (row == null) throw new IllegalArgumentException("대상 국가를 찾을 수 없습니다");
		return row;
	}

	/**
	 * 이 국가를 참조하는 실적 건수.
	 *
	 * TODO(포장 실적 테이블 확정 후):
	 *   SELECT count(*) FROM pack_alloc WHERE "Country_id" = :id AND _status='a'
	 *   → 배분 행에 "Country_id"(FK) + "CountryCode"(스냅샷)를 함께 남겨둘 것.
	 *     FK 는 삭제 방어용, 스냅샷은 마스터가 바뀌어도 과거 실적 표시가 흔들리지 않게.
	 */
	public int countUsage(Integer id) {
		return 0;
	}

	// =================================================================
	// 내부
	// =================================================================

	private boolean existsCode(String code, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("code", code);
		p.addValue("spjangcd", spjangcd);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT id FROM country
             WHERE "Code" = :code AND spjangcd = CAST(:spjangcd AS varchar)
             LIMIT 1
            """, p);
		return r != null;
	}

	private static String normalizeCode(String code) {
		return (code == null) ? "" : code.trim().toUpperCase();
	}

	private static String blankToNull(String s) {
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}

	/** 화면이 그대로 쓰는 응답 봉투 */
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