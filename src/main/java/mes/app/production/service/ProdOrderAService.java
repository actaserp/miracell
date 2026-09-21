package mes.app.production.service;

import java.util.List;
import java.util.Map;

import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class ProdOrderAService {

	@Autowired
	SqlRunner sqlRunner;

	/**
	 * 자체 재고 생산 지시 목록.
	 *
	 * @param factoryId 공장 필터. 빈 값/null 이면 전체 공장.
	 *                  공장은 job_res 에 직접 없고 워크센터(work_center)가 들고 있어 wc 로 건다.
	 */
	public List<Map<String, Object>> getProdOrderA(String dateFrom, String dateTo, String matGrpPk, String keyword,
												   String matType, String workcenterPk, String factoryId, String spjangcd) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("dateFrom", dateFrom);
		paramMap.addValue("dateTo", dateTo);
		paramMap.addValue("matGrpPk", matGrpPk);
		paramMap.addValue("matType", matType);
		paramMap.addValue("workcenterPk", workcenterPk);
		paramMap.addValue("keyword", keyword);
		paramMap.addValue("spjangcd", spjangcd);

		String sql = """
		        select jr.id
		        , jr."WorkOrderNumber" as workorder_number
                , to_char(jr."ProductionDate", 'yyyy-mm-dd') as production_date
	            , jr."ShiftCode" as shift_code, sh."Name" as shift_name
	            , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
	            , mg."Name" as mat_grp_name
	            , m."Code" as mat_code
	            , m."Name" as mat_name
	            , u."Name" as unit_name
                , case when m."PackingUnitQty" > 0 then round((jr."OrderQty" / m."PackingUnitQty")::numeric, 0)
                        else null end as box_qty
	            , jr."OrderQty" as order_qty
	            , coalesce(wc."Name", last_p.wc_name) as workcenter_name, e."Name" as equip_name
	            , jr."State" as state, fn_code_name('job_state', jr."State") as state_name
                , jr."Description" as description
                , up."Name" as creator
                , rp."ProcessOrder" as process_order
			    , coalesce(pr_rt."Code", pr_wc."Code", last_p.proc_code) as process_code
				, coalesce(pr_rt."Name", pr_wc."Name", last_p.proc_name) as process_name
			    , jr."ProcessCount" as process_count
			    , flow.process_flow
	            from job_res jr
	            left join material m on m.id = jr."Material_id"
	            left join mat_grp mg on mg.id = m."MaterialGroup_id"
	            left join unit u on u.id = m."Unit_id"
	            left join work_center wc on wc.id = jr."WorkCenter_id"
	            left join equ e on e.id = jr."Equipment_id"
	            left join shift sh on sh."Code" = jr."ShiftCode"
                left join user_profile up on up."User_id" = jr."_creater_id"
                
                -- (1) 라우팅 기반 공정 (헤더=마지막 공정: WorkIndex=ProcessOrder 매칭)
                left join routing_proc rp
					   on rp."Routing_id"   = jr."Routing_id"
					  and rp."ProcessOrder" = jr."WorkIndex"
				left join process pr_rt
					   on pr_rt.id = rp."Process_id"
				
				-- 라우팅이 없을 때 워크센터 기반 공정
				left join process pr_wc
					   on pr_wc.id = wc."Process_id"
                
                left join lateral (
					select string_agg(p2."Name", ' → ' order by rp2."ProcessOrder") as process_flow
					from routing_proc rp2
					left join process p2 on p2.id = rp2."Process_id"
					where rp2."Routing_id" = jr."Routing_id"
				) flow on true

				/* 라우팅 마지막 공정 — 헤더 표시용.
				   라우팅이 붙은 작지는 공정을 자식이 들고 있고 헤더의 WorkCenter_id 는 비어 있다.
				   목록의 워크센터·공정 칸이 통째로 비면 사용자가 읽을 것이 없으므로,
				   그 지시가 최종적으로 산출되는 공정(라우팅의 마지막)을 대신 보여준다.
				   ※ 표시만 그렇게 하는 것이고 job_res 에는 저장하지 않는다. */
				left join lateral (
					select p3."Code" as proc_code, p3."Name" as proc_name, wc3."Name" as wc_name
					from routing_proc rp3
					join process p3 on p3.id = rp3."Process_id"
					left join work_center wc3
						   on wc3."Process_id" = p3.id
						  and wc3."Factory_id" = coalesce(m."Factory_id", 1)
					where rp3."Routing_id" = jr."Routing_id"
					order by rp3."ProcessOrder" desc
					limit 1
				) last_p on jr."WorkCenter_id" is null
                where jr."ProductionDate" between cast(:dateFrom as date) and cast(:dateTo as date)
                and jr.spjangcd = :spjangcd
                and jr."Parent_id" is null
				""";
		/* 공장 필터 — 값이 있을 때만 조건을 붙인다.
		   라우팅이 붙은 지시는 헤더에 워크센터가 없다(공정은 자식이 갖는다).
		   wc 만 보면 그런 지시가 공장을 고른 순간 목록에서 통째로 빠지므로,
		   워크센터가 없으면 품목의 공장으로 판단한다. */
		if (StringUtils.isEmpty(factoryId) == false) {
			sql += " and coalesce(wc.\"Factory_id\", m.\"Factory_id\") = cast(:factoryId as Integer) ";
			paramMap.addValue("factoryId", factoryId);
		}
		if (StringUtils.isEmpty(workcenterPk) == false) sql += " and jr.\"WorkCenter_id\" = cast(:workcenterPk as Integer) ";
		if (StringUtils.isEmpty(matGrpPk) == false) {
			sql += " and mg.id = cast(:matGrpPk as Integer) ";
		} else if (StringUtils.isEmpty(matType) == false) {
			sql += " and mg.\"MaterialType\" = :matType ";
		}
		if (StringUtils.isEmpty(keyword) == false) sql += " and (m.\"Name\" like concat('%%', :keyword , '%%') or m.\"Code\" like concat('%%', :keyword , '%%') ) ";
		sql += " order by jr.\"WorkOrderNumber\" desc, jr.\"ProductionDate\" desc, jr.\"ShiftCode\", jr.id ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	public Map<String, Object> getMatInfo(String id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("matPk", id);

		if (id.isEmpty()) {
			return null;
		}
		String sql = """
				select e.id as equip_pk, e."Name" as equipment_name
	            , wc.id as workcenter_pk, wc."Name" as workcenter_name
	            , u."Name" as unit_name
	            , m."Routing_id" as routing_id
	            , flow.process_flow
	            from material m 
	            left join unit u on u.id = m."Unit_id"
	            left join work_center wc on wc.id = m."WorkCenter_id"
	            left join equ e on e.id = m."Equipment_id"
	            left join lateral (
	                select string_agg(p2."Name", ' → ' order by rp2."ProcessOrder") as process_flow
	                from routing_proc rp2
	                left join process p2 on p2.id = rp2."Process_id"
	                where rp2."Routing_id" = m."Routing_id"
	            ) flow on true
	            where m.id = cast(:matPk as Integer)
				""";

		Map<String, Object> items = this.sqlRunner.getRow(sql, paramMap);

		return items;
	}

	public Map<String, Object> getProdOrderADetail(String jrPk) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("jrPk", jrPk);

		String sql = """
	            select 
	            jr.id
	            , jr."WorkOrderNumber" as workorder_number
                , to_char(jr."ProductionDate", 'yyyy-mm-dd') as production_date
	            , jr."ShiftCode" as shift_code
	            , sh."Name" as shift_name
                , mg."MaterialType" as mat_type
	            , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
	            , mg.id as mat_grp_id
	            , mg."Name" as mat_grp_name
	            , m.id as mat_id
	            , m."Code" as mat_code
	            , m."Name" as mat_name
                , m."Unit_id" as unit_id
                ,  u."Name" as unit_name
	            , jr."OrderQty" as order_qty
                , case when m."PackingUnitQty" > 0 then round((jr."OrderQty" / m."PackingUnitQty")::numeric, 0)
                  else 
                  null 
                  end as box_order_qty
                , wc.id as workcenter_id
                , wc."Name" as workcenter_name
                , e.id as equip_id
                , e."Name" as equip_name
	            , jr."State" as state
	            , fn_code_name('job_state', jr."State") as state_name
                , jr."Description" as description
                , jr."Routing_id" as routing_id
                , flow.process_flow
	            from job_res jr 
	            left join material m on m.id = jr."Material_id"
	            left join mat_grp mg on mg.id = m."MaterialGroup_id"
	            left join unit u on u.id = m."Unit_id"
	            left join work_center wc on wc.id = jr."WorkCenter_id"
	            left join equ e on e.id = jr."Equipment_id"
	            left join shift sh on sh."Code" = jr."ShiftCode"
                left join lateral (
                    select string_agg(p2."Name", ' → ' order by rp2."ProcessOrder") as process_flow
                    from routing_proc rp2
                    left join process p2 on p2.id = rp2."Process_id"
                    where rp2."Routing_id" = jr."Routing_id"
                ) flow on true
                where jr.id = cast(:jrPk as Integer)
				""";

		Map<String, Object> items = this.sqlRunner.getRow(sql, paramMap);

		return items;
	}

	/**
	 * 작업지시 진행 현황 (더블클릭 모달용).
	 *
	 * 공장에 따라 실적이 남는 곳이 다르다.
	 *   1공장 : 공정 자식 작지 + 차수(mat_produce)
	 *   2공장 : 공정 자식 작지 + 유닛(mcell_unit) 1대=1로트
	 * 공통 축인 「공정별 진행」을 먼저 내리고, 2공장이면 유닛 목록을 덧붙인다.
	 */
	public Map<String, Object> getProgress(Integer jrPk) {

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", jrPk);

		Map<String, Object> head = sqlRunner.getRow("""
			select jr.id
			     , jr."WorkOrderNumber" as workorder_number
			     , to_char(jr."ProductionDate",'yyyy-mm-dd') as production_date
			     , m."Code" as mat_code, m."Name" as mat_name
			     , coalesce(jr."OrderQty",0) as order_qty
			     , u."Name" as unit_name
			     , fn_code_name('job_state', jr."State") as state_name
			     , coalesce(m."Factory_id", 1) as factory_id
			     , flow.process_flow
			  from job_res jr
			  left join material m on m.id = jr."Material_id"
			  left join unit u on u.id = m."Unit_id"
			  left join lateral (
					select string_agg(p2."Name", ' → ' order by rp2."ProcessOrder") as process_flow
					from routing_proc rp2
					left join process p2 on p2.id = rp2."Process_id"
					where rp2."Routing_id" = jr."Routing_id"
			  ) flow on true
			 where jr.id = :pid
		""", p);

		Map<String, Object> out = new java.util.HashMap<>();
		if (head == null) return out;
		out.put("head", head);

		int factoryId = head.get("factory_id") == null ? 1
				: ((Number) head.get("factory_id")).intValue();

		/* 공정별 진행 — 라우팅을 기준선으로 삼는다.
		   자식 작지만 나열하면 워크센터를 가진 품목이 없는 공정(2공장 검사)이 통째로 빠진다.
		   라우팅 공정을 먼저 깔고, 거기에 자식 작지 집계를 붙이는 방식으로 바꿨다. */
		List<Map<String, Object>> procs = sqlRunner.getRows("""
			select pr."Code" as process_code
			     , pr."Name" as process_name
			     , rp."ProcessOrder" as work_index
			     , coalesce(c.row_cnt, 0)     as row_cnt
			     , coalesce(c.order_qty, 0)   as order_qty
			     , coalesce(c.done_cnt, 0)    as done_cnt
			     , coalesce(c.working_cnt, 0) as working_cnt
			     , coalesce(c.produced_qty,0) as produced_qty
			  from routing_proc rp
			  join process pr on pr.id = rp."Process_id"
			  left join lateral (
					select count(*) as row_cnt
					     , sum(coalesce(jr2."OrderQty",0)) as order_qty
					     , count(*) filter (where jr2."State" = 'finished') as done_cnt
					     , count(*) filter (where jr2."State" = 'working')  as working_cnt
					     , coalesce(sum(pd.qty), 0) as produced_qty
					from job_res jr2
					join work_center wc2 on wc2.id = jr2."WorkCenter_id"
					left join lateral (
						/* 실적 = 양품 산출량(GoodQty).
						   mat_produce."DefectQty" 는 이 시스템에서 채워지지 않는다 —
						   자재 불량은 defect_regist, 유닛 불량은 insp_result 에 남는다. */
						select coalesce(sum(mp."GoodQty"),0) as qty
						from mat_produce mp
						where mp."JobResponse_id" = jr2.id
						  -- 분해(완료취소)된 실적은 논리삭제(_status='d')로 남는다
						  and coalesce(mp."_status",'a') = 'a' 
					) pd on true
					where jr2."Parent_id" = :pid
					  and wc2."Process_id" = pr.id
			  ) c on true
			 where rp."Routing_id" = (select "Routing_id" from job_res where id = :pid)
			 order by rp."ProcessOrder"
		""", p);
		out.put("procs", procs == null ? new java.util.ArrayList<>() : procs);

		// 2공장 — 유닛(1대 = 1로트 = 시리얼)
		if (factoryId == 2) {

			/* 공정별 실적을 유닛 상태로 읽는다.
			   2공장은 검사·포장에 자식 작지가 없어 job_res 로는 진행을 알 수 없다.
			   상태 전이 : wait → assembling → inspect_wait → pass/reject → packed */
			Map<String, Object> stat = sqlRunner.getRow("""
				select count(*) as total
				     , count(*) filter (where mu."State" = 'wait')         as wait_cnt
				     , count(*) filter (where mu."State" = 'assembling')   as assembling_cnt
				     , count(*) filter (where mu."State" = 'inspect_wait') as inspect_wait_cnt
				     , count(*) filter (where mu."State" = 'pass')         as pass_cnt
				     , count(*) filter (where mu."State" = 'reject')       as reject_cnt
				     , count(*) filter (where mu."State" = 'packed')       as packed_cnt
				  from mcell_unit mu
				  join job_res jr on jr.id = mu."JobResponse_id"
				 where (jr.id = :pid or jr."Parent_id" = :pid)
				   and coalesce(mu."_status",'a') = 'a'
			""", p);
			out.put("unit_stat", stat);

			List<Map<String, Object>> units = sqlRunner.getRows("""
				select mu.id
				     , mu."UnitNo" as unit_no
				     , mu."LotNumber" as lot_number
				     , mu."State" as state
				     , m."Code" as mat_code
				     , m."Name" as mat_name
				     , (select count(*) from mcell_unit_step st
				         where st."McellUnit_id" = mu.id) as step_cnt
				     , (select count(*) from mcell_unit_step st
				         where st."McellUnit_id" = mu.id and st."State" = 'done') as step_done
				     , to_char(mu."StartTime",'yyyy-mm-dd hh24:mi') as start_time
				  from mcell_unit mu
				  join job_res jr on jr.id = mu."JobResponse_id"
				  left join material m on m.id = mu."Material_id"
				 where (jr.id = :pid or jr."Parent_id" = :pid)
				   and coalesce(mu."_status",'a') = 'a'
				 order by mu."UnitNo"
			""", p);
			out.put("units", units == null ? new java.util.ArrayList<>() : units);
		}

		return out;
	}

	public Map<String, Object> getJopResRow(Integer id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("id", id);

		String sql = """
				select "State" as state
	            , "SourceDataPk" as src_pk, "SourceTableName" as src_table
	            from job_res 
	            where id = :id
				""";

		Map<String,Object> items = this.sqlRunner.getRow(sql, paramMap);

		return items;
	}

	/**
	 * 삭제를 막고 있는 작업지시들(부모 + 자식 중 '지시중'이 아닌 건).
	 * 없으면 빈 리스트. 화면 안내 문구를 만들기 위한 조회다.
	 *
	 * ※ 자식은 부모의 WorkOrderNumber 를 그대로 물려받으므로,
	 *   그냥 나열하면 같은 줄이 건수만큼 반복된다. 공정·상태로 묶어서 건수로 보여준다.
	 */
	public List<Map<String, Object>> getDeleteBlockers(Integer id) {

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", id);

		String sql = """
			select jr."WorkOrderNumber" as wo_no
				 , coalesce(p."Name", wc."Name") as process_name
				 , fn_code_name('job_state', jr."State") as state_name
				 , count(*) as cnt
			  from job_res jr
			  left join work_center wc on wc.id = jr."WorkCenter_id"
			  left join process p on p.id = wc."Process_id"
			 where (jr.id = :pid or jr."Parent_id" = :pid)
			   and jr."State" <> 'ordered'
			 group by jr."WorkOrderNumber", coalesce(p."Name", wc."Name"), jr."State"
			 order by count(*) desc, 2, 3
		""";

		return this.sqlRunner.getRows(sql, p);
	}

	/**
	 * 착수한 유닛 수(부모 + 자식).
	 * 스텝을 하나라도 손댔거나 실적이 붙은 유닛은 작지와 함께 지울 수 없다.
	 */
	/**
	 * 검사 판정이 남아 있는 유닛 수(부모 + 자식).
	 *
	 * ★ 분해는 확정된 판정(Verdict 있음)을 지우지 않고 이력으로 남긴다 —
	 *   「합격했다가 분해 → 재검사」 가 추적돼야 하기 때문이다.
	 *   그래서 전부 분해해도 유닛은 wait 인데 insp_result 가 유닛을 FK 로 물고 있어,
	 *   작지 삭제가 유닛을 지우는 순간 FK 위반으로 터졌다.
	 *   판정은 실적과 같은 무게의 기록이므로, 있으면 작지를 지우지 않는다.
	 */
	public int countInspected(Integer headerId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", headerId);
		return sqlRunner.queryForCount("""
			select count(distinct mu.id)
			  from mcell_unit mu
			  join job_res jr on jr.id = mu."JobResponse_id"
			 where (jr.id = :pid or jr."Parent_id" = :pid)
			   and exists (select 1 from insp_result ir where ir."McellUnit_id" = mu.id)
		""", p);
	}

	/**
	 * 작지(부모+자식)에 매달린 유닛들의 검사 판정을 지운다. 지운 건수를 돌려준다.
	 *
	 * ★ 여기까지 오는 유닛은 «전부 분해된» 것뿐이다(countBusyUnits 가 먼저 막는다).
	 *   분해하는 순간 그 판정은 효력을 잃었으므로, 지우는 것은 무효가 된 기록이다.
	 * ★ 그래도 흔적은 남긴다 — sys_log 에 «누가 · 어느 작지 · 어떤 판정» 을 한 줄로.
	 *   화면은 없어도 나중에 「그 검사 기록 어디 갔냐」 에 답할 수 있어야 한다.
	 */
	public int deleteInspections(Integer headerId, Integer userId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("pid", headerId).addValue("uid", userId);

		// 지우기 전에 요약을 남긴다
		Map<String, Object> sum = sqlRunner.getRow("""
			select count(*) as cnt
			     , string_agg(coalesce(mu."LotNumber", 'SN-' || mu."UnitNo")
			                  || ':' || coalesce(ir."Verdict", '-'), ', ' order by ir.id) as detail
			  from insp_result ir
			  join mcell_unit mu on mu.id = ir."McellUnit_id"
			  join job_res jr on jr.id = mu."JobResponse_id"
			 where jr.id = :pid or jr."Parent_id" = :pid
		""", p);
		int cnt = (sum == null || sum.get("cnt") == null) ? 0 : ((Number) sum.get("cnt")).intValue();
		if (cnt == 0) return 0;

		p.addValue("detail", CommonUtil.tryString(sum.get("detail")));
		p.addValue("cnt", cnt);
		sqlRunner.execute("""
			insert into sys_log("Type","Source","Message",_created)
			select 'info', 'prod_order_a/delete',
			       '작지 삭제로 검사 판정 ' || :cnt || '건 삭제 · 작지 ' || coalesce(jr."WorkOrderNumber",'?')
			       || ' · user#' || :uid || ' · ' || :detail,
			       now()
			  from job_res jr where jr.id = :pid
		""", p);

		// 항목 → 판정 순서(FK)
		sqlRunner.execute("""
			delete from insp_result_item
			 where "InspResult_id" in (
			       select ir.id from insp_result ir
			         join mcell_unit mu on mu.id = ir."McellUnit_id"
			         join job_res jr on jr.id = mu."JobResponse_id"
			        where jr.id = :pid or jr."Parent_id" = :pid)
		""", p);
		sqlRunner.execute("""
			delete from insp_result ir
			 using mcell_unit mu, job_res jr
			 where mu.id = ir."McellUnit_id"
			   and jr.id = mu."JobResponse_id"
			   and (jr.id = :pid or jr."Parent_id" = :pid)
		""", p);
		return cnt;
	}

	public int countBusyUnits(Integer headerId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", headerId);
		return sqlRunner.queryForCount("""
			select count(*)
			  from mcell_unit mu
			  join job_res jr on jr.id = mu."JobResponse_id"
			 where (jr.id = :pid or jr."Parent_id" = :pid)
			   and coalesce(mu."_status",'a') = 'a'
			   and ( mu."State" <> 'wait'
			         or exists (select 1 from mcell_unit_step st
			                     where st."McellUnit_id" = mu.id
			                       and (st."State" <> 'wait' or st."MatProduce_id" is not null)) )
		""", p);
	}

	/**
	 * 실적이 붙은 공정 목록(부모 + 자식). 삭제 거부 안내 문구용.
	 * 공정 단위로 접는다 — 2공장 조립처럼 한 공정에 자식이 여럿이면 같은 줄이 반복된다.
	 */
	public List<Map<String, Object>> getProducedBlockers(Integer headerId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", headerId);
		try {
			return sqlRunner.getRows("""
				select coalesce(pr."Name", wc."Name", '(공정 미지정)') as process_name
				     , count(*) as cnt
				     , coalesce(sum(mp."GoodQty"), 0) as good_qty
				  from mat_produce mp
				  join job_res jr on jr.id = mp."JobResponse_id"
				  left join work_center wc on wc.id = jr."WorkCenter_id"
				  left join process pr on pr.id = wc."Process_id"
				 where (jr.id = :pid or jr."Parent_id" = :pid)
				   -- 분해(완료취소)는 mat_produce 를 논리삭제(_status='d')한다.
				   -- 빼먹으면 이미 취소한 실적까지 세어 영영 삭제할 수 없게 된다.
				   and coalesce(mp."_status",'a') <> 'd' 
				 group by coalesce(pr."Name", wc."Name", '(공정 미지정)')
				 order by count(*) desc, 1
			""", p);
		} catch (Exception e) {
			return new java.util.ArrayList<>();
		}
	}

	/** 생산 실적 건수(부모 + 자식). 삭제 가드용. */
	public int countProduced(Integer headerId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", headerId);
		try {
			return sqlRunner.queryForCount("""
				select count(*)
				  from mat_produce mp
				 where mp."JobResponse_id" in (
				         select id from job_res where id = :pid or "Parent_id" = :pid)
				   -- 분해로 논리삭제된 실적(_status='d')은 제외한다
				   and coalesce(mp."_status",'a') <> 'd' 
			""", p);
		} catch (Exception e) {
			return 0;   // 컬럼 구성이 다른 환경에서도 삭제 자체는 막지 않는다
		}
	}

	/**
	 * 유닛(mcell_unit)이 매달린 작지들과 각자의 현재 지시량.
	 * 유닛은 헤더가 아니라 공정 자식(조립)에 붙으므로 헤더만 봐서는 알 수 없다.
	 */
	public List<Map<String, Object>> getUnitOwners(Integer headerId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", headerId);
		return sqlRunner.getRows("""
			select jr.id, coalesce(jr."OrderQty", 0) as order_qty
			  from job_res jr
			 where (jr.id = :pid or jr."Parent_id" = :pid)
			   and exists (
			         select 1 from mcell_unit mu
			          where mu."JobResponse_id" = jr.id
			            and coalesce(mu."_status",'a') = 'a'
			       )
			 order by jr.id
		""", p);
	}

	/**
	 * 전개 후 공정 수 갱신.
	 * 엔티티 재저장을 피하려고 SQL 로 직접 쓴다 — save 로 하면 트리거가 넣은
	 * WorkOrderNumber 가 null 로 덮여 헤더 번호가 사라진다.
	 */
	public void updateProcessCount(Integer jobResId, int count) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("id", jobResId).addValue("c", count);
		sqlRunner.execute("""
			update job_res set "ProcessCount" = :c where id = :id
		""", p);
	}

	/** 자식 작지 id 목록. 재전개 전 유닛 정리에 쓴다. */
	public List<Integer> getChildIds(Integer parentId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", parentId);
		List<Map<String, Object>> rows = sqlRunner.getRows("""
			select id from job_res where "Parent_id" = :pid order by id
		""", p);
		List<Integer> ids = new java.util.ArrayList<>();
		if (rows != null) {
			for (Map<String, Object> r : rows) {
				if (r.get("id") != null) ids.add(((Number) r.get("id")).intValue());
			}
		}
		return ids;
	}

	/** 착수(지시중이 아님)한 자식 수. 수정 전 가드용. */
	public int countBusyChildren(Integer parentId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", parentId);
		return sqlRunner.queryForCount("""
			select count(*) from job_res
			 where "Parent_id" = :pid and "State" <> 'ordered'
		""", p);
	}

	/** 자식 작지 일괄 삭제. 재전개 직전에만 쓴다(호출 전 countBusyChildren 확인 필수). */
	public int deleteChildren(Integer parentId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", parentId);
		return sqlRunner.execute("""
			delete from job_res where "Parent_id" = :pid
		""", p);
	}

	/**
	 * 삭제 가능 여부만 본다(지우지 않는다).
	 *   0  삭제 가능
	 *  -1  부모가 아님
	 *  -2  지시중이 아닌 공정이 있음
	 *
	 * ★ deleteById 와 같은 조건이지만 «검사만» 하는 이유 —
	 *   삭제 흐름이 「유닛 정리 → 작지 삭제」 순서인데, 작지 삭제가 -2 로 거부되면
	 *   유닛만 사라지고 작지는 남는다. -2 는 예외가 아니라 정상 반환이라
	 *   @Transactional 이 붙어 있어도 롤백되지 않기 때문이다.
	 *   그래서 유닛에 손대기 «전에» 이 검사를 먼저 통과시킨다.
	 */
	public int checkDeletable(Integer id) {

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", id);

		int isParent = sqlRunner.queryForCount("""
            select count(*) from job_res
            where id = :pid and "Parent_id" is null
        """, p);
		if (isParent == 0) return -1;

		int notOrdered = sqlRunner.queryForCount("""
            select count(*) from job_res
             where (id = :pid or "Parent_id" = :pid)
               and "State" <> 'ordered'
        """, p);
		if (notOrdered > 0) return -2;

		return 0;
	}

	public int deleteById(Integer id) {

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", id);

		// 1) 부모인지 확인 (UI상 부모만 오지만 서버에서도 가드)
		int isParent = sqlRunner.queryForCount("""
            select count(*) from job_res
            where id = :pid and "Parent_id" is null
        """, p);
		if (isParent == 0) {
			return -1; // 부모가 아님
		}

		// 2) not-ordered 존재 체크 (부모 + 자식)
		//    걸린 건의 상세는 컨트롤러가 getDeleteBlockers 로 따로 조회해 안내한다.
		int notOrdered = sqlRunner.queryForCount("""
            select count(*) from job_res
             where (id = :pid or "Parent_id" = :pid)
               and "State" <> 'ordered'
        """, p);
		if (notOrdered > 0) {
			return -2; // 삭제 거부
		}

		// 3) 일괄 삭제 (부모 + 자식)
		int deleted = sqlRunner.execute("""
            delete from job_res
             where (id = :pid or "Parent_id" = :pid)
        """, p);

		return deleted;
	}

	public void updateBySujuPk(Integer sujuPk) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("sujuPk", sujuPk);

		String sql = """
					update suju
	                set "State" = 'received'
	                where id = :sujuPk
	                and "State" = 'ordered'
				""";

		this.sqlRunner.execute(sql, paramMap);
	}

}