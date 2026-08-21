package mes.app.definition.service;

import java.util.List;
import java.util.Map;

import org.apache.groovy.parser.antlr4.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

@Service
public class DefectTypeService {
	@Autowired
	SqlRunner sqlRunner;

	/*
	 * 부적합 유형 조회
	 *
	 * ★ "Coverage" 가 이 유형을 어느 등록 화면에서 쓸 수 있는지 정한다.
	 *     work     공정에서 발생한 불량에만
	 *     material 입고 자재 불량에만
	 *     all      양쪽 다 (이물·파손처럼 두 상황에 같은 말을 쓰는 것)
	 *   부적합 등록 화면(DefectService.getContext)이 이 값으로 목록을 가른다.
	 */
	// 부적합 유형 조회
	public List<Map<String, Object>> getDefectTypeList(String keyword) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("keyword", keyword);

		String sql = """
			select dt.id
		    , dt."Code" as defect_type_code
		    , dt."Name" as defect_type_name
		    , dt."Description" as description
		    , coalesce(dt."Coverage", 'all') as coverage
		    , case coalesce(dt."Coverage", 'all')
		           when 'work'     then '작업불량'
		           when 'material' then '원자재불량'
		           else '공통' end as coverage_name
	        from defect_type dt
	        where 1=1
			""";
		if (StringUtils.isEmpty(keyword)==false) sql+="and upper(dt.\"Name\") like concat('%%',upper(:keyword),'%%')";

		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}

	// 부적합 유형 상세 조회
	public Map<String, Object> getDefectTypeDetail(int id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);

		String sql = """
			select dt.id
			, dt."Code" as defect_type_code
			, dt."Name" as defect_type_name
			, dt."Description" as description
			, coalesce(dt."Coverage", 'all') as coverage
			from defect_type dt
			where 1=1
			and dt.id = :id
			""";

		Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

		return item;
	}
}