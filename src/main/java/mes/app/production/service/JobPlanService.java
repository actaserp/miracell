package mes.app.production.service;

import mes.domain.entity.Suju;
import mes.domain.repository.JobPlanHeadRepository;
import mes.domain.repository.JobPlanRepository;
import mes.domain.repository.SujuRepository;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

@Service
public class JobPlanService {

	@Autowired
	SqlRunner sqlRunner;
	
	@Autowired
	JobPlanRepository jobPlanRepository;

	@Autowired
	JobPlanHeadRepository jobPlanHeadRepository;
	
	
	// 수주 내역 조회 
	public List<Map<String, Object>> getList(String date_kind, String start, String end, String spjangcd) {
		
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("date_kind", date_kind);
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);
		
		String sql = """
				SELECT
				     h.id AS head_id,
				     h.stdate,
				     h.eddate,
				     h.actnm,
				     fn_code_name('comunication_type', h.cmcode) as cmcode,
				     h.description,
				    
				     p.id AS plan_id,
				     p.material_id,
				     p.qty,
				     m."Code" AS mat_code,
				     m."Name" AS mat_name,
				     p.remark
				 
				 FROM job_plan_head h
				 
				 LEFT JOIN job_plan p
				     ON p.head_id = h.id
				 
				 LEFT JOIN material m
				     ON p.material_id = m.id
				 
				 WHERE h.spjangcd = :spjangcd
				   AND h.stdate <= :end
				   AND h.eddate >= :start
				 
				 ORDER BY h.id desc, p.id desc;
			""";

		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);
		
		return itmes;
	}
	
	// 수주 상세정보 조회
	public Map<String, Object> getDetail(int head_id) {
		
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("head_id", head_id);

		String sql = """ 
			SELECT
				 h.id AS head_id,
				 h.actnm,
				 TO_DATE(h.stdate, 'YYYYMMDD') AS stdate,
				 TO_DATE(h.eddate, 'YYYYMMDD') AS eddate,
				 h.datetype,
				 h.cmcode,
				 h.spjangcd,
				 h.description,
				 TO_CHAR(TO_DATE(h.stdate, 'YYYYMMDD'), 'IYYY') || '-W' ||
			     LPAD(TO_CHAR(TO_DATE(h.stdate, 'YYYYMMDD'), 'IW'), 2, '0') AS "popupWeekSelector"
			 FROM job_plan_head h
			 WHERE h.id = :head_id
		""";

		String detailSql = """ 
			 SELECT
				 p.id,
				 p.material_id,
				 m."Code" AS product_code,
				 m."Name" AS "txtProductName",
				 p.qty,
				 p.remark
			 FROM job_plan p
			 LEFT JOIN material m ON p.material_id = m.id
			 WHERE p.head_id = :head_id
			 ORDER BY p.id
		""";

		Map<String, Object> head = this.sqlRunner.getRow(sql, paramMap);
		List<Map<String, Object>> planList = this.sqlRunner.getRows(detailSql, paramMap);

		head.put("planList", planList);
		
		return head;
	}


}
