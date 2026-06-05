package mes.app.summary.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ProductionDefectService {
	
	@Autowired
	SqlRunner sqlRunner;


	public List<Map<String, Object>> getList(String date_from, String date_to, Integer cboWorkCenter) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("date_from", date_from);
		paramMap.addValue("date_to", date_to);
		paramMap.addValue("cboWorkCenter", cboWorkCenter);

		String sql = """
        with jr_day as (
            select
                jr."ProductionDate",
                jr."WorkCenter_id",
                sum(coalesce(jr."GoodQty", 0) + coalesce(jr."DefectQty", 0)) as total_prod_qty
            from job_res jr
            where jr."ProductionDate" between cast(:date_from as date) and cast(:date_to as date)
              and jr."State" = 'finished'
               %s
            group by jr."ProductionDate", jr."WorkCenter_id"
        )
        select
            dt.id as defect_pk,
            dt."Name" as defect_type,
            jr."ProductionDate",
            sum(jrd."DefectQty")::decimal as defect_qty,
            jr."WorkCenter_id",
            jd.total_prod_qty
        from job_res jr
            inner join job_res_defect jrd on jrd."JobResponse_id" = jr.id
            inner join defect_type dt on dt.id = jrd."DefectType_id"
            inner join jr_day jd
                on jd."ProductionDate" = jr."ProductionDate"
               and jd."WorkCenter_id"  = jr."WorkCenter_id"
        where jr."ProductionDate" between cast(:date_from as date) and cast(:date_to as date)
          and jr."State" = 'finished'
          %s
        group by dt.id, dt."Name", jr."ProductionDate", jr."WorkCenter_id", jd.total_prod_qty
    """;

		String wcFilter = (cboWorkCenter != null) ? "and jr.\"WorkCenter_id\" = :cboWorkCenter" : "";
		sql = String.format(sql, wcFilter, wcFilter);

		return this.sqlRunner.getRows(sql, paramMap);
	}



}
