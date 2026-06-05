package mes.app.definition.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;


import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class TagGroupService {

	@Autowired
	SqlRunner sqlRunner;
	
	//창고 목록 조회
	public List<Map<String,Object>> getTagGroupResult(String tag_group_code,String tag_group_name) {
		
		MapSqlParameterSource tgParam = new MapSqlParameterSource();
		tgParam.addValue("tag_group_code", tag_group_code);
		tgParam.addValue("tag_group_name",tag_group_name);
		
		String sql = """
			 select tag.id 
	        , tag."Name" as tag_group_name
	        , tag."Code" as tag_group_code
	        , tag."Description" as description
            from tag_grp tag 
            where 1=1
			""";
		if (StringUtils.isEmpty(tag_group_code)==false) sql +="and upper(tag.\"Code\") like concat('%%',upper(:tag_group_code),'%%')";
		
		if (StringUtils.isEmpty(tag_group_name)==false) sql +="and upper(tag.\"Name\") like concat('%%',upper(:tag_group_name),'%%')";
		
		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, tgParam);
		
		return items;
	}
	
	//창고 상세정보 조회
	public Map<String,Object> getTagGroupResultDetail(int id){
		
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);
		
		String sql = """
			select tag.id 
	        , tag."Name" as tag_group_name
	        , tag."Code" as tag_group_code
	        , tag."Description" as description
            from tag_grp tag 
            where 1=1
            and tag.id = :id
			""";
		
		Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
		
		return item;
	}
	
}
