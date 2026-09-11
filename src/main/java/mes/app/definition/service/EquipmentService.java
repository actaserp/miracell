package mes.app.definition.service;

import java.util.List;
import java.util.Map;

import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import mes.domain.services.SqlRunner;

@Service
public class EquipmentService {

	@Autowired
	SqlRunner sqlRunner;

	// 설비 목록 조회
	public List<Map<String, Object>> getEquipmentList(Integer group, Integer workcenter, String keyword){

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("group_id", group);
		dicParam.addValue("workcenter_id", workcenter);
		dicParam.addValue("keyword", keyword);

		String sql = """
			select e.id
             , e."Code"
             , e."Name"
             , e."Description"
             , e."Maker"
             , e."Model"
             , e."ManageNumber"
             , e."SerialNumber"
             , e."SupplierName"
             , e."ProductionYear"
             , to_char(e."PurchaseDate",'yyyy-mm-dd') as "PurchaseDate"
             , e."Manager"
             , e."PurchaseCost" 
             , e."ServiceCharger"
             , e."InstallDate"
             , e."DisposalDate"
             , e."OperationRateYN"
             , e."Status"
             , e."EquipmentGroup_id"
             , eg."Name" as group_name
             , e."WorkCenter_id"
             , wc."Name" as workcenter_name
      		 , e."AttentionRemark"
             , e."PowerWatt"
             , e."Voltage"
             , e."Usage"
             , e."Inputdate" as "InputDate"
             , d."Name"  as "DepartName"
             , e."ASTelNumber"
             , to_char(e._created ,'yyyy-mm-dd hh24:mi') as _created 
            from equ e
            left join equ_grp eg on e."EquipmentGroup_id" =eg.id 
            left join work_center wc  on wc.id = e."WorkCenter_id" 
            left join depart d on d.id = e."Depart_id" 
            where 1 = 1
		    """;
		if (group != null) sql +=" and e.\"EquipmentGroup_id\"= :group_id ";
		if (workcenter != null) sql +=" and e.\"WorkCenter_id\"= :workcenter_id ";
		if (StringUtils.hasText(keyword)) sql +=" and upper(e.\"Name\") like concat('%%', :keyword,'%%') ";

		sql += " order by e.id desc ";
		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}

	/**
	 * 설비를 삭제할 수 없게 만드는 첫 번째 참조를 찾아 사람이 읽을 문구로 돌려준다.
	 * 삭제 가능하면 null.
	 *
	 * 순서는 사용자에게 의미 있는 쪽(작업 이력) → 기준정보 순.
	 * 여기서 못 거르는 참조가 있어도 컨트롤러의 DataIntegrityViolationException
	 * 처리가 최종 방어를 한다.
	 */
	public String findDeleteBlocker(Integer id) {

		if (id == null) return null;

		/* --- (A) DB 가 FK 로 막는 것들 --------------------------------------
		   equ 를 참조하는 FK 는 아래 4개다(2026-09 확인).
		     equip_history / haccp_test / haccp_diary / mcell_unit_step
		   equip_history 는 삭제 로직이 함께 지우므로 여기서 보지 않는다. */
		if (countRef("mcell_unit_step", "Equipment_id", id) > 0) {
			return "M-CELL 조립·수리 작업 이력";
		}
		if (countRef("haccp_test", "Equipment_id", id) > 0) {
			return "HACCP 점검 기록";
		}
		if (countRef("haccp_diary", "Equipment_id", id) > 0) {
			return "HACCP 일지";
		}

		/* --- (B) FK 가 없어 DB 가 막아주지 않는 것들 -------------------------
		   컬럼만 있고 제약이 없어, 지우면 조용히 미아 데이터가 된다.
		   DB 대신 여기서 막는다. 해당 컬럼이 없는 환경에서는 countRef 가 0 을
		   돌려주므로 그냥 통과한다. */
		if (countRef("mcell_unit", "Equipment_id", id) > 0) {
			return "M-CELL 유닛 작업 이력";
		}
		if (countRef("mat_produce", "Equipment_id", id) > 0) {
			return "생산 실적";
		}
		if (countRef("equ_run", "Equipment_id", id) > 0) {
			return "설비 가동 이력";
		}
		if (countRef("job_res", "Equipment_id", id) > 0) {
			return "작업지시";
		}
		if (countRef("material", "Equipment_id", id) > 0) {
			return "품목 기준정보";
		}
		if (countRef("equ_component", "equ_id", id) > 0) {
			return "설비 부품정보";
		}
		return null;
	}

	/**
	 * 참조 건수 조회.
	 * 테이블·컬럼이 없는 환경(1공장 전용 배포 등)에서도 죽지 않도록 예외를 삼킨다.
	 * 못 세면 0 으로 보고 넘어가되, 실제 FK 는 DB 가 막아 준다.
	 */
	private long countRef(String table, String column, Integer id) {
		try {
			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("id", id);

			String sql = "select count(*) as cnt from \"" + table + "\" where \"" + column + "\" = :id";

			Map<String, Object> row = this.sqlRunner.getRow(sql, p);
			if (row == null || row.get("cnt") == null) return 0L;   // SqlRunner 는 오류 시 null
			return ((Number) row.get("cnt")).longValue();
		} catch (Exception e) {
			return 0L;
		}
	}

	// 설비 상세정보 조회
	public Map<String, Object> getEquipmentpDetail(int id){

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);

		String sql = """
			select e.id
             , e."Code"
             , e."Name"
             , e."Description"
             , e."Maker"
             , e."Model"
             , e."ManageNumber"
             , e."SerialNumber"
             , e."SupplierName"
             , e."ProductionYear"
             , to_char(e."PurchaseDate",'yyyy-mm-dd') as "PurchaseDate"
             , e."Manager"
             , e."PurchaseCost" 
             , e."ServiceCharger"
             , e."InstallDate"
             , e."DisposalDate"
             , e."OperationRateYN"
             , e."Status"
             , e."EquipmentGroup_id"
             , eg."Name" as group_name
             , e."WorkCenter_id"
             , wc."Name" as workcenter_name
             , e."AttentionRemark"
             , e."PowerWatt"
             , e."Voltage"
             , e."Usage"
             , e."Inputdate" as "InputDate"
             , e."Depart_id"
             , e."ASTelNumber"
             , to_char(e._created ,'yyyy-mm-dd hh24:mi') as _created 
            from equ e
              left join equ_grp eg on e."EquipmentGroup_id" =eg.id 
              left join work_center wc  on wc.id = e."WorkCenter_id"   
            where e.id = :id
		    """;

		Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

		return item;
	}

	public List<Map<String, Object>> getEquipmentStopList(String dateFrom, String dateTo, String equipment) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("dateFrom", dateFrom + " 00:00:00");
		paramMap.addValue("dateTo", dateTo + " 23:59:59");
		paramMap.addValue("equipment", equipment);

		String sql = """
            select  er.id
            , to_char(er."StartDate", 'yyyy-mm-dd') as start_date
            , to_char(er."EndDate", 'yyyy-mm-dd') as end_date           
	        , e."Name"
	        , e."Code"
	        , er."StartDate"
	        , to_char(er."StartDate",'hh24:mi') as "StartTime"
	        , er."EndDate"
	        , to_char(er."EndDate",'hh24:mi') as "EndTime"
	        , EXTRACT(day from (er."EndDate" - er."StartDate")) * 60 * 24
	            + EXTRACT(hour from (er."EndDate" - er."StartDate")) * 60 
	            + EXTRACT(min from ("EndDate" - "StartDate")) as "GapTime"
            , er."WorkOrderNumber" 
	        , er."Equipment_id" 
	        , er."RunState" 
            , sc."StopCauseName" 
            , er."Description" 
            from equ e 
            inner join equ_run er on e.id = er."Equipment_id"
            left join stop_cause sc on sc.id = er."StopCause_id"
            where er."StartDate" >= cast(:dateFrom as timestamp) and er."EndDate" <= cast(:dateTo as timestamp)
			""";

		if (StringUtils.hasText(equipment)) {
			sql += "  and er.\"Equipment_id\" = :equipment::INTEGER ";
		}


		sql += """
		        and er."RunState" = 'X'
		        order by e."Name", er."StartDate", er."EndDate"
        	   """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	public Map<String, Object> getEquipmentStopInfo(Integer id, String runType) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("id", id);

		String sql = """
	                select er.id
	                , to_char(er."StartDate", 'yyyy-mm-dd') as start_date
	                , to_char(er."EndDate", 'yyyy-mm-dd') as end_date   
		            , e."Name"
		            , e."Code"
		            , er."StartDate"
		            , to_char(er."StartDate",'hh24:mi') as "StartTime"
		            , er."EndDate"
		            , to_char(er."EndDate",'hh24:mi') as "EndTime"
		            , EXTRACT(day from (er."EndDate" - er."StartDate")) * 60 * 24
		                + EXTRACT(hour from (er."EndDate" - er."StartDate")) * 60 
		                + EXTRACT(min from ("EndDate" - "StartDate")) as "GapTime"
	                , er."WorkOrderNumber" 
		            , er."Equipment_id" 
		            , er."Description" 
		            , er."RunState" 
	                , er."StopCause_id"
	                , sc."StopCauseName" 
	                from equ e
	                inner join equ_run er on e.id = er."Equipment_id"         
	                left join stop_cause sc on sc.id = er."StopCause_id"
	                where er.id = :id
	                and er."RunState" = 'X'
					""";

		Map<String, Object> items = this.sqlRunner.getRow(sql, paramMap);

		return items;
	}

	// 작지번호로 설비 상태 찾기
	public List<Map<String, Object>> getEquipmentOrderNum(String orderNum) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();

		dicParam.addValue("WorkOrderNumber", orderNum);

		String sql = """
        		select er.id
        		, to_char(er."StartDate", 'yyyy-mm-dd') as start_date_only
                , to_char(er."EndDate", 'yyyy-mm-dd') as end_date_only
                , to_char(er."StartDate", 'yyyy-mm-dd hh24:mi') as start_date
			    , to_char(er."EndDate", 'yyyy-mm-dd hh24:mi') as end_date
	            , e."Name"
	            , e."Code"
	            , er."StartDate"
	            , to_char(er."StartDate",'HH24:MI') as "StartTime"
	            , er."EndDate"
	            , to_char(er."EndDate",'HH24:MI') as "EndTime"
                , er."WorkOrderNumber" 
	            , er."Equipment_id" 
	            , er."RunState" 
                , sc."StopCauseName" 
                , er."Description" 
                , er."StopCause_id"
                from equ_grp eg
                inner join equ e on eg.id = e."EquipmentGroup_id"
                left join equ_run er on e.id = er."Equipment_id"
                left join stop_cause sc on sc.id = er."StopCause_id"
                where 1=1
                and er."WorkOrderNumber" = :WorkOrderNumber
	            --and er."RunState" = :runType
        		""";

		sql += " order by id, start_date desc, end_date DESC";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}
	public int saveComponent(MultiValueMap<String, Object> data) {
		Integer id = CommonUtil.tryIntNull(data.getFirst("id"));

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);
		dicParam.addValue("cname", CommonUtil.tryString(data.getFirst("cname")));
		dicParam.addValue("component", CommonUtil.tryString(data.getFirst("component")));
		dicParam.addValue("ctype", CommonUtil.tryString(data.getFirst("ctype")));
		dicParam.addValue("cmodel", CommonUtil.tryString(data.getFirst("cmodel")));
		dicParam.addValue("cmake", CommonUtil.tryString(data.getFirst("cmake")));

		dicParam.addValue("camaunt", CommonUtil.tryString(data.getFirst("camaunt")));
		dicParam.addValue("cunit", CommonUtil.tryString(data.getFirst("cunit")));
		dicParam.addValue("cdate", CommonUtil.tryString(data.getFirst("cdate")));
		dicParam.addValue("cycle", CommonUtil.tryString(data.getFirst("cycle")));
		dicParam.addValue("state", CommonUtil.tryString(data.getFirst("state")));
		dicParam.addValue("description", CommonUtil.tryString(data.getFirst("description")));
		dicParam.addValue("equ_id", CommonUtil.tryIntNull(data.getFirst("equipmentId")));
		dicParam.addValue("user_id", CommonUtil.tryIntNull(data.getFirst("user_id").toString()));

		String sql = "";

		if(id == null) {
			sql = """
    		INSERT INTO public.equ_component
    		("_created", "_creater_id", "cname", "component", "ctype",
     			"cmodel", "cmake", "camaunt", "cunit", "cdate",
    		 "cycle", "state", "description", "equ_id")
    		VALUES
    		(now(), :user_id, :cname, :component, :ctype,
     			:cmodel, :cmake, :camaunt, :cunit, :cdate,
     				:cycle, :state, :description, :equ_id)
			""";
		}else {
			sql = """
					UPDATE public.equ_component
					SET "_modified" = now()
					, "_modifier_id" = :user_id
					, "cname" = :cname
					, "component"  = :component
					, "ctype"  = :ctype
					, "cmodel" = :cmodel
					, "cmake" = :cmake
					, "camaunt" = :camaunt
					, "cunit" = :cunit
					, "cdate" = :cdate
					, "cycle" = :cycle
					, "state" = :state
					, "description" = :description
					, "equ_id" = :equ_id
					WHERE id = :id
					""";
		}
		return this.sqlRunner.execute(sql, dicParam);
	}


	public Map<String, Object> getComponent(int equPk){
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("equ_pk", equPk);
		String sql = """
		select ec.id
		, ec.cname
		, ec.component
		, ec.ctype
		, ec.cmodel
		, ec.cmake
		, ec.camaunt
		, ec.cunit
		, ec.cdate
		, ec.cycle
		, ec.state
		, ec.description
		, ec.equ_id
		from equ_component ec
		inner join equ e on e.id = ec.equ_id
		 where ec.id = :equ_pk
		""";


		Map<String, Object> item = this.sqlRunner.getRow(sql, paramMap);
		return item;

	}


	public List<Map<String, Object>> getComponentListByEqu(int equPk) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("equ_pk", equPk);

		String sql = """
		with A as
		(
		select ec.id
		, ec.cname
		, ec.component
		, ec.ctype
		, ec.cmodel
		, ec.cmake
		, ec.camaunt
		, ec.cunit
		, ec.cdate
		, ec.cycle
		, ec.state
		, ec.description
		, ec.equ_id
		from equ_component ec
		where ec.equ_id = :equ_pk
		)
		select A.id,
		A.cname,
		A.component,
		A.ctype,
		A.cmodel,
		A.cmake,
		A.camaunt,
		A.cunit,
		A.cdate,
		A.cycle,
		A.state,
		A.description,
		A.equ_id
		from A
		inner join equ e on e.id = A.equ_id
		""";
		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

}