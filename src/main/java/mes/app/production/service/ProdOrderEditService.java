package mes.app.production.service;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mes.app.common.NotificationController_modal;
import mes.app.notification.BizEventTrigger;
import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

import javax.transaction.Transactional;

@Service
public class ProdOrderEditService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	RoutingProcRepository routingProcRepository;
	@Autowired
	WorkcenterRepository workcenterRepository;
	@Autowired
	JobResRepository jobResRepository;
	@Autowired
	SujuRepository sujuRepository;
	@Autowired
	NotificationController_modal notificationController_modal;

	// 수주 목록 조회
	public List<Map<String, Object>> getSujuList(String date_kind, String start, String end, Integer mat_group, String mat_name,
												 String not_flag, String spjangcd, Integer cboFactory, String company) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", Timestamp.valueOf(start + " 00:00:00"));
		dicParam.addValue("end", Timestamp.valueOf(end + " 23:59:59"));
		dicParam.addValue("mat_group", mat_group);
		dicParam.addValue("mat_name", mat_name);
		dicParam.addValue("cboFactory", cboFactory);
		dicParam.addValue("spjangcd", spjangcd);

		if (StringUtils.isEmpty(date_kind)) {
			date_kind = "sales";
		}

		// 수주에서 수주량-예약량 = 수주량2(필요량)
		String sql = """
        		with s as (
	                select s.id, s."JumunDate", s."DueDate", s."JumunNumber"
	                , s."CompanyName"
	                , s."Material_id"
	                , s."Standard"
	                , mg."Name" as "MaterialGroupName"
	                , mg.id as "MaterialGroup_id"
	                , m."Code" as mat_code
	                , m."WorkCenter_id" as workcenter_id
	                , m."Name" as mat_name
	                , u."Name" as unit_name
	                , s."SujuQty"
	                , s."SujuQty2"
	                , coalesce (s."ReservationStock",0) as "ReservationStock"
	                , fn_code_name('suju_state', s."State") as "StateName"
	                , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
	                , s."State"
	                , s."Description" as description
	                , m."Routing_id"
	                , f."Name" as fac_name
									, r."Name" as routing_nm
	                from suju s
	                inner join material m on m.id = s."Material_id"
	                inner join mat_grp mg on mg.id = m."MaterialGroup_id"
	                left join routing r on m."Routing_id" = r.id
	                left join unit u on m."Unit_id" = u.id
	                left join factory f on m."Factory_id" = f.id
	                where 1 = 1 and mg."MaterialType"!='sangpum'
	                and s.spjangcd = :spjangcd
        		""";
//        and s.confirm = '1'
		if ("suju_date".equals(date_kind)) {
			sql += " and s.\"JumunDate\" between :start and :end ";
		} else {
			sql += " and s.\"DueDate\" between :start and :end ";
		}

		if (StringUtils.isEmpty(company) == false) {
			sql += " and s.\"CompanyName\" like :company ";
			dicParam.addValue("company", "%" + company + "%");
		}

		if (cboFactory != null) {
			sql += " and m.\"Factory_id\" = :cboFactory ";
		}

		if (mat_group != null) {
			sql += " and mg.id = :mat_group ";
		}

		if (StringUtils.isEmpty(mat_name) == false) {
			sql += """
        			and ( upper(m."Name") like concat('%%',upper(:mat_name),'%%')
	                or upper(m."Code") = upper(:mat_name)
	                )
        			""";
		}

		sql += """
        		)
	            , q as (
	                select s.id as suju_id
	                , sum(jr."OrderQty") as ordered_qty
	                , jr."Description" as memo
	                from job_res jr 
	                inner join s on s.id = jr."SourceDataPk" 
	                and jr."SourceTableName"='suju' 
	                and jr."Material_id" = s."Material_id"
	                where jr."State" <>'canceled'
	                group by s.id, jr."Description"
	            )
	            select s.id
	            , s."JumunNumber"
	            , to_char(s."JumunDate", 'yyyy-mm-dd') as "JumunDate"
	            , to_char(s."DueDate", 'yyyy-mm-dd') as "DueDate"
	            , s."CompanyName"
	            , s."Standard"
	            , s.mat_type_name
	            , s."MaterialGroupName"
	            , s.mat_code
	            , s.workcenter_id
	            , s.mat_name
	            , s.unit_name
	            , s."Material_id" as mat_pk
	            , s."SujuQty" as "SujuQty"
	            , s."SujuQty2" as "SujuQty2"
	            , s."ReservationStock" as "ReservationStock"
	            , coalesce(q.ordered_qty,0) as ordered_qty
	            , round(greatest(0, s."SujuQty2" - coalesce(q.ordered_qty, 0))::numeric, 2) as remain_qty
	            , 0 as "AdditionalQty"
	            , s.description
	            , s."StateName", s."State"
	            , s.routing_nm
	            , s.fac_name
	            , q.memo
	            from s 
	            left join q on q.suju_id = s.id
	            where 1 = 1
        		""";

		if (StringUtils.isEmpty(not_flag) == false) {
			sql += "  and (s.\"SujuQty2\"- coalesce (q.ordered_qty,0)) > 0 ";
		}

		if ("suju_date".equals(date_kind)) {
			sql += " order by s.\"DueDate\" desc, s.\"JumunNumber\" desc ";
		} else {
			sql += " order by s.\"JumunDate\" desc, s.\"JumunNumber\" desc ";
		}

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}

	// 제품 지시내역 조회
	public List<Map<String, Object>> getJobOrderList(Integer suju_id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("suju_id", suju_id);

		String sql = """
			select jr.id
			, jr."WorkOrderNumber"
			, jr."ProductionDate"
			, jr."ShiftCode" 
			, s."Name" as "ShiftName"
			, m."Code" as mat_code
			, m."Name" as mat_name
			, u."Name" as unit_name
			, ROUND(jr."OrderQty"::numeric, 2) as "OrderQty"
			, jr."WorkCenter_id" 
			, wc."Name" as "WorkcenterName"
			, jr."Equipment_id"
			, e."Name" as "EquipmentName"
			, jr."State" 
			, fn_code_name('job_state', jr."State") as "StateName"
			, sju."Standard" as standard
			, sju.id as suju_id
			, jr."Description"
			, sju."DueDate" 
			from job_res jr 
			inner join material m on m.id = jr."Material_id" 
			inner join mat_grp mg on mg.id = m."MaterialGroup_id" 
			left join unit u on u.id = m."Unit_id" 
			left join shift s on s."Code" = jr."ShiftCode" 
			left join work_center wc on wc.id = jr."WorkCenter_id"
			left join equ e on e.id = jr."Equipment_id"
			LEFT JOIN suju sju ON sju.id = jr."SourceDataPk"
			where jr."SourceDataPk"=:suju_id
			and jr."SourceTableName" ='suju'
			order by jr."WorkOrderNumber" desc, jr.id
			""";

		List<Map<String, Object>> job_res = this.sqlRunner.getRows(sql, dicParam);

		String sql_suju_detail = """
			SELECT
				sd.id,
				sd."suju_id",
				sd."Standard",
				sd."Qty"
			FROM suju_detail sd
			WHERE sd."suju_id" = :suju_id
			ORDER BY sd.id
		""";

		List<Map<String, Object>> suju_detail = this.sqlRunner.getRows(sql_suju_detail, dicParam);

		for (Map<String, Object> job : job_res) {
			job.put("items", suju_detail);
		}

		return job_res;
	}


	// 제품 지시내역 상세조회
	public Map<String, Object> getJobOrderDetail(Integer jobres_id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jobres_id", jobres_id);

		String sql = """
				select jr.id
	            , jr."WorkOrderNumber"
	            , to_char(jr."ProductionDate", 'yyyy-mm-dd') as "ProductionDate"
	            , jr."Material_id"
	            , jr."ShiftCode" 
	            , s."Name" as "ShiftName"
	            , m."Name" as mat_name
	            , u."Name" as unit_name
	            , ROUND(jr."OrderQty"::numeric, 2) as "OrderQty"
	            , jr."WorkCenter_id" 
	            , jr."Equipment_id"
	            , jr."State" 
	            , fn_code_name('job_state', jr."State") as "StateName"
	            , jr."Description"
	            from job_res jr 
	            inner join material m on m.id = jr."Material_id" 
	            left join unit u on u.id = m."Unit_id" 
	            left join shift s on s."Code" = jr."ShiftCode" 
	            left join work_center wc on wc.id = jr."WorkCenter_id"
	            where jr.id = :jobres_id
			""";

		Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

		return item;
	}

	// 반제품 작업지시 조회
	public List<Map<String, Object>> getSemiList(String data_date, Integer mat_pk, Double suju_qty, Integer suju_pk) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("data_date", data_date);
		dicParam.addValue("mat_pk", mat_pk.toString());
		dicParam.addValue("mat_order_qty", suju_qty);
		dicParam.addValue("suju_pk", suju_pk);

		String sql = """
				with A as (
	                select m.id as mat_pk
	                , mg."Name" as group_name
	                , m."Name" as mat_name
	                , m."Code" as mat_code
	                , m."WorkCenter_id" as workcenter_id
	                , m."StoreHouse_id" as storehouse_id
	                , u."Name" as unit_name
	                , fn_unit_ceiling( bom.bom_ratio * :mat_order_qty, u."PieceYN" ) as bom_qty
	                , fn_unit_ceiling( bom.bom_ratio * :mat_order_qty, u."PieceYN" ) as order_qty
	                from tbl_bom_detail(:mat_pk, :data_date) as bom
	                inner join material m on m.id = bom.mat_pk
	                left join unit u on u.id = m."Unit_id"
	                inner join mat_grp mg on mg.id = m."MaterialGroup_id" 
	                where mg."MaterialType" in ('semi')
	                ), 
	                sq as (                
					select 
					 s.id as suju_pk
					 ,jr."Material_id" as mat_pk
					 , sum(jr."OrderQty") as ordered_qty
					from job_res jr 
					 inner join suju s on s.id=jr."SourceDataPk" and jr."SourceTableName" ='suju'
					 inner join material m on m.id=jr."Material_id" 
					 inner join mat_grp mg on mg.id=m."MaterialGroup_id"  
					where 
					s.id = :suju_pk
					and mg."MaterialType" ='semi'
					group by s.id, jr."Material_id" 
	                )
	                select A.*, sq.ordered_qty
	                from A
	                left join sq on sq.mat_pk = A.mat_pk
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}

	// 반제품 지시내역 조회 — 완제품 작지(헤더)의 반제품 자식들
	//   자식은 SourceDataPk 가 없고 Parent_id 로만 연결되므로, 헤더(수주 연결)를 통해 조회한다.
	public List<Map<String, Object>> getSemiJoborderList(Integer suju_id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("suju_id", suju_id);

		String sql = """
				select jr.id
	            , jr."WorkOrderNumber"
	            , jr."ProductionDate"
	            , jr."ShiftCode" 
	            , s."Name" as "ShiftName"
	            , m."Code" as mat_code
	            , m."Name" as mat_name
	            , u."Name" as unit_name
	            , jr."OrderQty" as "OrderQty"
	            , jr."WorkIndex"
	            , jr."WorkCenter_id" 
	            , wc."Name" as "WorkcenterName"
	            , jr."Equipment_id"
	            , e."Name" as "EquipmentName"
	            , jr."State" 
	            , fn_code_name('job_state', jr."State") as "StateName"
	            from job_res jr 
	            inner join material m on m.id = jr."Material_id" 
	            inner join mat_grp mg on mg.id = m."MaterialGroup_id" 
	            left join unit u on u.id = m."Unit_id" 
	            left join shift s on s."Code" = jr."ShiftCode" 
	            left join work_center wc on wc.id = jr."WorkCenter_id"
	            left join equ e on e.id = jr."Equipment_id"
	            where jr."Parent_id" in (
	                    select h.id from job_res h
	                     where h."SourceDataPk" = :suju_id
	                       and h."SourceTableName" = 'suju'
	                       and h."Parent_id" is null)
	            and mg."MaterialType" in ('semi')
	            order by jr."WorkIndex", jr."WorkOrderNumber" desc, jr.id
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}

	@Transactional
	@BizEventTrigger(domain = "wm_prod_order_edit", action = "SAVE")
	public AjaxResult makeProdOrder(Integer sujuId, String productionDate, Integer cboMaterial,
									String cboShiftCode, Integer cboWorcenter, Integer cboEquipment,
									Float txtOrderQty, String spjangcd, User user) {

		AjaxResult result = new AjaxResult();
		Material m = materialRepository.getMaterialById(cboMaterial);
		Integer routingPk = m.getRoutingId();
		Timestamp prodDate = CommonUtil.tryTimestamp(productionDate);
		boolean hasRouting = (routingPk != null);

		JobRes header = new JobRes();
		header.set_audit(user);
		header.setProductionDate(prodDate);
		header.setProductionPlanDate(prodDate);
		header.setMaterialId(cboMaterial);
		header.setOrderQty(txtOrderQty);
		header.setStoreHouse_id(m.getStoreHouseId());
		header.setLotCount(1);
		header.setState("ordered");
		header.setSourceDataPk(sujuId);
		header.setSourceTableName("suju");
		header.setShiftCode(cboShiftCode);
		header.setSpjangcd(spjangcd);

		if (!hasRouting) {
			// ── 심플 모드: 단일 공정 (기존 동작 그대로) ──
			header.setRouting_id(null);
			header.setProcessCount(1);
			header.setWorkCenter_id(cboWorcenter);
			header.setFirstWorkCenter_id(cboWorcenter);
			header.setEquipment_id(cboEquipment);
			header = jobResRepository.save(header);
			confirmSuju(sujuId);
			result.success = true;
			result.data = Map.of("jobResId", header.getId(), "childCount", 0);
			return result;
		}

		// ── 라우팅 모드: 헤더=작지(공정X) + 공정 전개 ──
		header.setRouting_id(routingPk);
		header.setWorkCenter_id(null);
		header.setFirstWorkCenter_id(null);
		header = jobResRepository.save(header);

		int childCount = explodeProcessRows(header, cboMaterial, txtOrderQty, prodDate, cboShiftCode, spjangcd, user);
		MapSqlParameterSource pc = new MapSqlParameterSource();
		pc.addValue("c", childCount);
		pc.addValue("id", header.getId());
		sqlRunner.execute("UPDATE job_res SET \"ProcessCount\" = :c WHERE id = :id", pc);

		confirmSuju(sujuId);
		result.success = true;
		result.data = Map.of("jobResId", header.getId(), "childCount", childCount);
		return result;
	}

	private void confirmSuju(Integer sujuId) {
		if (sujuId == null) return;
		Suju suju = sujuRepository.getSujuById(sujuId);
		if (suju != null) { suju.setConfirm("1"); suju.setState("ordered"); sujuRepository.save(suju); }
	}

	private int explodeProcessRows(JobRes header, Integer rootMatPk, Float orderQty,
								   Timestamp prodDate, String shiftCode, String spjangcd, User user) {

		Material rootMat = materialRepository.getMaterialById(rootMatPk);
		Integer routingId = rootMat.getRoutingId();
		if (routingId == null) return 0;

		Integer factoryId = rootMat.getFactory_id();

		// ── (1) 라우팅 스텝: 공정 순서(WorkIndex) 참조 + 첫 공정/공정수 산출 ──
		//   라우팅은 '공정 순서 틀'로만 쓴다. 스텝에 반제품을 지정하지 않는다(routing_proc.Material_id 미사용).
		MapSqlParameterSource sp = new MapSqlParameterSource();
		sp.addValue("routingId", routingId);
		List<Map<String, Object>> steps = sqlRunner.getRows("""
            SELECT "ProcessOrder", "Process_id"
              FROM routing_proc
             WHERE "Routing_id" = :routingId
             ORDER BY "ProcessOrder"
            """, sp);
		int procCount = (steps != null) ? steps.size() : 0;
		Integer firstWcId = null;
		if (procCount > 0) {
			Integer firstProcessId = ((Number) steps.get(0).get("Process_id")).intValue();
			Workcenter firstWc = workcenterRepository.findByProcessIdAndFactoryId(firstProcessId, factoryId);
			firstWcId = (firstWc != null ? firstWc.getId() : null);
		}

		// ── (2) 완제품 BOM 트리(재귀) 전개 → 생산품(semi/product) + 워크센터 있는 것만 자식 대상 ──
		//   · 자식 대상 판정: MaterialType IN ('semi','product') AND WorkCenter_id IS NOT NULL
		//     (raw/sub_mat 은 워크센터가 나중에 생겨도 MaterialType 으로 방어 → 작지로 새지 않음)
		//   · 공정/워크센터/산출창고 = 그 품목의 material.WorkCenter_id → work_center
		//   · WorkIndex        = 그 공정의 routing_proc.ProcessOrder
		//   · 수량             = 트리 누적 ratio(∏ Amount/OutputAmount) × 지시량, 완제품 자신은 ratio=1
		//   · 완제품(root) 자신도 포함 (포장 공정 자식). 공정:품목 1:N 허용.
		String sql = """
            WITH RECURSIVE tree AS (
                SELECT CAST(:rootMatPk AS integer)      AS mat_id,
                       CAST(1 AS double precision)      AS ratio,
                       0                                AS lvl
                UNION ALL
                SELECT bc."Material_id",
                       t.ratio * (bc."Amount" / NULLIF(b."OutputAmount", 0)),
                       t.lvl + 1
                  FROM tree t
                  JOIN bom b       ON b."Material_id" = t.mat_id AND b."BOMType" = 'manufacturing'
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                 WHERE t.lvl < 20
            )
            SELECT tr.mat_id                                    AS mat_id,
                   fn_unit_ceiling(SUM(tr.ratio) * CAST(:orderQty AS double precision), u."PieceYN") AS step_qty,
                   m."WorkCenter_id"                            AS wc_id,
                   wc."Process_id"                              AS process_id,
                   wc."ProcessStoreHouse_id"                    AS store_id,
                   rp."ProcessOrder"                            AS process_order
              FROM tree tr
              JOIN material m   ON m.id = tr.mat_id
              JOIN mat_grp mg   ON mg.id = m."MaterialGroup_id"
              LEFT JOIN unit u  ON u.id = m."Unit_id"
              JOIN work_center wc ON wc.id = m."WorkCenter_id"
              LEFT JOIN routing_proc rp ON rp."Routing_id" = :routingId AND rp."Process_id" = wc."Process_id"
             WHERE mg."MaterialType" IN ('semi','product')
               AND m."WorkCenter_id" IS NOT NULL
             GROUP BY tr.mat_id, m."WorkCenter_id", wc."Process_id", wc."ProcessStoreHouse_id",
                      u."PieceYN", rp."ProcessOrder"
             ORDER BY rp."ProcessOrder" NULLS LAST, tr.mat_id
            """;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("rootMatPk", rootMatPk);
		p.addValue("orderQty", orderQty != null ? orderQty : 0f);
		List<Map<String, Object>> mats = sqlRunner.getRows(sql, p);
		if (mats == null || mats.isEmpty()) return 0;

		int count = 0;
		for (Map<String, Object> row : mats) {
			Integer matId       = ((Number) row.get("mat_id")).intValue();
			Float   stepQty     = row.get("step_qty") != null
					? Float.parseFloat(row.get("step_qty").toString())
					: (orderQty != null ? orderQty : 0f);
			Integer wcId        = row.get("wc_id")        != null ? ((Number) row.get("wc_id")).intValue()        : null;
			Integer stepStoreId = row.get("store_id")     != null ? ((Number) row.get("store_id")).intValue()     : null;
			Integer processOrder = row.get("process_order") != null ? ((Number) row.get("process_order")).intValue() : null;

			JobRes child = new JobRes();
			child.set_audit(user);
			child.setParentId(header.getId());
			child.setMaterialId(matId);
			child.setOrderQty(stepQty);
			child.setProductionDate(prodDate);
			child.setProductionPlanDate(prodDate);
			child.setRouting_id(routingId);
			child.setProcessCount(procCount);
			child.setWorkIndex(processOrder);
			child.setWorkCenter_id(wcId);
			child.setFirstWorkCenter_id(firstWcId != null ? firstWcId : wcId);
			child.setShiftCode(shiftCode);
			child.setStoreHouse_id(stepStoreId);
			child.setState("ordered");
			child.setSpjangcd(spjangcd);
			jobResRepository.save(child);
			count++;
		}
		return count;
	}
}