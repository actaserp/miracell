package mes.app.production.service;

import java.sql.Timestamp;
import java.util.*;

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

	/**
	 * 작업지시 수정 (하위 반제품 캐스케이드 + 수주량 동기화)
	 *
	 *  · 헤더(Parent_id IS NULL + Routing_id NOT NULL) 를 수정하면 자식 반제품 지시량도 재계산한다.
	 *  · 자식 행을 직접 수정하는 경우는 캐스케이드하지 않는다(그 행만).
	 *  · 수량 변경은 상태 게이트를 통과해야 한다:
	 *      - finished 가 하나라도 있으면      → 수량 잠금 (날짜/근무조/설비/비고만 수정)
	 *      - working 인 행의 새 수량 < 기생산 → 전체 거부 (부분 반영 금지)
	 *      - 자식 구성이 달라졌으면(BOM 변경) → 전체 거부
	 *  · syncSuju='Y' 이면 수주량(SujuQty/SujuQty2)도 함께 수정한다. 화면에서 컨펌받은 경우만.
	 */
	@Transactional
	public AjaxResult updateOrderCascade(Integer jobresId, String productionDate, String shiftCode,
										 Integer workCenterId, Integer equipmentId, Float orderQty,
										 String description, String syncSuju, User user) {

		AjaxResult result = new AjaxResult();

		if (jobresId == null) {
			result.success = false;
			result.message = "작업지시 정보가 없습니다.";
			return result;
		}

		// ── (1) 대상 행 ──
		MapSqlParameterSource p0 = new MapSqlParameterSource();
		p0.addValue("id", jobresId);
		Map<String, Object> target = sqlRunner.getRow("""
            SELECT jr.id, jr."Parent_id", jr."Material_id", jr."Routing_id",
                   jr."State", jr."OrderQty", jr."SourceDataPk", jr."SourceTableName"
              FROM job_res jr
             WHERE jr.id = :id
            """, p0);

		if (target == null) {
			result.success = false;
			result.message = "작업지시를 찾을 수 없습니다.";
			return result;
		}

		Integer parentId  = target.get("Parent_id")  != null ? ((Number) target.get("Parent_id")).intValue()  : null;
		Integer routingId = target.get("Routing_id") != null ? ((Number) target.get("Routing_id")).intValue() : null;
		Integer rootMatPk = ((Number) target.get("Material_id")).intValue();
		Float   oldQty    = target.get("OrderQty")   != null ? ((Number) target.get("OrderQty")).floatValue() : 0f;

		boolean isHeader   = (parentId == null);
		boolean cascade    = isHeader && (routingId != null);
		boolean qtyChanged = (orderQty != null) && (Math.abs(orderQty - oldQty) > 0.0001f);

		Timestamp prodDate = (productionDate != null && !productionDate.isEmpty())
				? Timestamp.valueOf(productionDate + " 00:00:00") : null;

		// ── (2) 영향 범위 + 기생산 ──
		MapSqlParameterSource ps = new MapSqlParameterSource();
		ps.addValue("id", jobresId);
		List<Map<String, Object>> scope = sqlRunner.getRows(cascade ? SCOPE_SQL_CASCADE : SCOPE_SQL_SINGLE, ps);
		if (scope == null || scope.isEmpty()) {          // ★ SqlRunner 는 오류 시 null
			result.success = false;
			result.message = "작업지시 구성을 읽지 못했습니다.";
			return result;
		}

		// ── (3) 수량 게이트 ──
		Map<Integer, Float> newQtyMap = new LinkedHashMap<>();

		if (qtyChanged) {

			List<String> finishedNames = new ArrayList<>();
			for (Map<String, Object> r : scope) {
				if ("finished".equals(r.get("State"))) finishedNames.add(str(r.get("mat_name")));
			}
			if (!finishedNames.isEmpty()) {
				result.success = false;
				result.message = "이미 완료된 공정이 있어 지시량을 변경할 수 없습니다. ("
						+ String.join(", ", finishedNames) + ")";
				return result;
			}

			if (cascade) {
				List<Map<String, Object>> steps = calcStepQty(rootMatPk, routingId, orderQty);
				if (steps == null || steps.isEmpty()) {
					result.success = false;
					result.message = "BOM 전개 결과가 없어 하위 지시량을 재계산할 수 없습니다.";
					return result;
				}
				Map<String, Float> calc = new LinkedHashMap<>();
				for (Map<String, Object> s : steps) {
					calc.put(key(s.get("mat_id"), s.get("process_order")),
							s.get("step_qty") != null ? Float.parseFloat(s.get("step_qty").toString()) : 0f);
				}
				List<String> missing = new ArrayList<>();
				for (Map<String, Object> r : scope) {
					Integer rid = ((Number) r.get("id")).intValue();
					if (rid.equals(jobresId)) { newQtyMap.put(rid, orderQty); continue; }
					Float q = calc.remove(key(r.get("Material_id"), r.get("WorkIndex")));
					if (q == null) { missing.add(str(r.get("mat_name"))); continue; }
					newQtyMap.put(rid, q);
				}
				if (!missing.isEmpty() || !calc.isEmpty()) {
					result.success = false;
					result.message = "작업지시 생성 이후 BOM/라우팅이 변경되었습니다. "
							+ "지시량 수정으로는 반영할 수 없으니 작업지시를 다시 생성하세요.";
					return result;
				}
			} else {
				newQtyMap.put(jobresId, orderQty);
			}

			List<String> violations = new ArrayList<>();
			for (Map<String, Object> r : scope) {
				Integer rid = ((Number) r.get("id")).intValue();
				Float produced = r.get("produced") != null ? ((Number) r.get("produced")).floatValue() : 0f;
				Float newQ = newQtyMap.get(rid);
				if (newQ != null && produced > 0 && newQ < produced - 0.0001f) {
					violations.add(String.format("%s: 기생산 %s → 지시량 %s 불가",
							str(r.get("mat_name")), trim(produced), trim(newQ)));
				}
			}
			if (!violations.isEmpty()) {
				result.success = false;
				result.message = "이미 생산된 수량보다 적게 지시할 수 없습니다.\n" + String.join("\n", violations);
				return result;
			}
		}

		// ── (4) 작지 반영 (DELETE 금지, UPDATE 만) ──
		for (Map<String, Object> r : scope) {
			Integer rid = ((Number) r.get("id")).intValue();
			boolean isTarget = rid.equals(jobresId);

			JobRes jr = jobResRepository.getJobResById(rid);
			if (jr == null) continue;

			if (prodDate  != null) { jr.setProductionDate(prodDate); jr.setProductionPlanDate(prodDate); }
			if (shiftCode != null) jr.setShiftCode(shiftCode);

			if (isTarget) {
				if (description != null) jr.setDescription(description);
				// 라우팅 모드 헤더는 공정을 갖지 않는다 → 워크센터/설비를 덮어쓰지 않는다
				if (!cascade) {
					if (workCenterId != null) jr.setWorkCenter_id(workCenterId);
					if (equipmentId  != null) jr.setEquipment_id(equipmentId);
				}
			}
			// 자식의 워크센터·창고는 품목이 결정하므로 건드리지 않는다

			Float newQ = newQtyMap.get(rid);
			if (newQ != null) jr.setOrderQty(newQ);

			jr.set_audit(user);
			jobResRepository.save(jr);
		}

		// ── (5) 수주량 동기화 (사용자가 컨펌한 경우만) ──
		Integer sujuId = resolveSujuId(jobresId, parentId, target);
		boolean sujuSynced = false;
		Float   otherQty   = null;
		Float   newSujuQty = null;
		Double  reserved   = null;

		if (qtyChanged && sujuId != null && isHeader) {

			// ★ 같은 수주의 다른 작지 합계 — 분할지시가 있을 수 있으므로
			//   이번 지시량만 그대로 수주량에 넣으면 다른 작지가 통째로 사라진다.
			otherQty = sumOtherOrderedQty(sujuId, jobresId);

			if ("Y".equals(syncSuju)) {
				newSujuQty = otherQty + orderQty;

				Suju suju = sujuRepository.getSujuById(sujuId);
				if (suju != null) {
					reserved = suju.getReservationStock() != null ? suju.getReservationStock() : 0d;
					suju.setSujuQty((double) (float) newSujuQty);
					// SujuQty2 = 수주량 - 예약량(필요량). 음수 방지.
					suju.setSujuQty2(Math.max(0d, newSujuQty - reserved));
					sujuRepository.save(suju);
					sujuSynced = true;
				}
			}
		}

		// ── (6) 화면 갱신용 재집계 ──
		Map<String, Object> data = new HashMap<>();
		data.put("jobResId", jobresId);
		data.put("cascaded", cascade && qtyChanged);
		data.put("affected", newQtyMap.size());
		data.put("sujuSynced", sujuSynced);

		if (sujuId != null) {
			Map<String, Object> info = calcSujuInfo(sujuId);

			// ★ calcSujuInfo 는 SqlRunner(순수 JDBC) 조회다.
			//   JPA save() 는 트랜잭션 커밋 시점에 flush 되므로 방금 바꾼 값이 아직 DB 에 없다.
			//   → 서버가 계산한 값으로 덮어쓴다. (수주량만 고치면 잔량이 옛 기준으로 남아 또 어긋난다)
			if (info != null && qtyChanged && isHeader) {

				double orderedQty = (otherQty != null ? otherQty : 0f) + orderQty;
				info.put("ordered_qty", round2(orderedQty));

				if (sujuSynced && newSujuQty != null) {
					double q2 = Math.max(0d, newSujuQty - (reserved != null ? reserved : 0d));
					info.put("SujuQty",    round2(newSujuQty));
					info.put("SujuQty2",   round2(q2));
					info.put("remain_qty", round2(Math.max(0d, q2 - orderedQty)));
				} else {
					// 수주량은 그대로. 잔량만 새 지시량 기준으로 다시 계산한다.
					// (감량 후 [취소] 했을 때 "잔량 50 남김" 안내와 그리드가 일치해야 한다)
					double q2 = info.get("SujuQty2") != null
							? ((Number) info.get("SujuQty2")).doubleValue() : 0d;
					info.put("remain_qty", round2(Math.max(0d, q2 - orderedQty)));
				}
			}

			if (info != null) data.put("info", info);
		}

		result.success = true;
		result.data = data;
		return result;
	}

	private static double round2(double v) {
		return Math.round(v * 100d) / 100d;
	}

	/** 같은 수주에 물린 다른 헤더 작지들의 지시량 합 (분할지시 대응) */
	private Float sumOtherOrderedQty(Integer sujuId, Integer excludeJobResId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("suju_id", sujuId);
		p.addValue("exclude_id", excludeJobResId);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT COALESCE(SUM(jr."OrderQty"), 0) AS qty
              FROM job_res jr
              JOIN suju s ON s.id = jr."SourceDataPk"
             WHERE jr."SourceTableName" = 'suju'
               AND jr."SourceDataPk"    = :suju_id
               AND jr."Material_id"     = s."Material_id"
               AND jr."State" <> 'canceled'
               AND jr.id <> :exclude_id
            """, p);

		return (row != null && row.get("qty") != null) ? ((Number) row.get("qty")).floatValue() : 0f;
	}

	/**
	 * 수정 대상 작지가 물고 있는 수주 PK.
	 * 자식 작지는 Parent_id 로만 연결되고 SourceDataPk 가 비어 있으므로 부모를 타고 올라간다.
	 */
	private Integer resolveSujuId(Integer jobresId, Integer parentId, Map<String, Object> target) {

		if (parentId == null) {
			return (target.get("SourceDataPk") != null && "suju".equals(target.get("SourceTableName")))
					? ((Number) target.get("SourceDataPk")).intValue() : null;
		}

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("pid", parentId);
		Map<String, Object> h = sqlRunner.getRow("""
            SELECT jr."SourceDataPk", jr."SourceTableName"
              FROM job_res jr WHERE jr.id = :pid
            """, p);

		if (h == null || h.get("SourceDataPk") == null)   return null;
		if (!"suju".equals(h.get("SourceTableName")))     return null;
		return ((Number) h.get("SourceDataPk")).intValue();
	}

	/**
	 * 수주 1건의 수주량/기지시량/잔량 재집계.
	 * ★ getSujuList 의 q CTE 와 같은 규칙이어야 한다 —
	 *   어긋나면 「저장 직후 화면」과 「다시 조회한 화면」의 숫자가 달라진다.
	 */
	private Map<String, Object> calcSujuInfo(Integer sujuId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("suju_id", sujuId);

		return sqlRunner.getRow("""
            SELECT s.id AS suju_id
                 , ROUND(s."SujuQty"::numeric, 2)  AS "SujuQty"
                 , ROUND(s."SujuQty2"::numeric, 2) AS "SujuQty2"
                 , ROUND(COALESCE(q.ordered_qty, 0)::numeric, 2) AS ordered_qty
                 , ROUND(GREATEST(0, s."SujuQty2" - COALESCE(q.ordered_qty, 0))::numeric, 2) AS remain_qty
              FROM suju s
              LEFT JOIN (
                    SELECT jr."SourceDataPk" AS suju_id, SUM(jr."OrderQty") AS ordered_qty
                      FROM job_res jr
                      JOIN suju s2 ON s2.id = jr."SourceDataPk"
                     WHERE jr."SourceTableName" = 'suju'
                       AND jr."Material_id"     = s2."Material_id"
                       AND jr."State" <> 'canceled'
                       AND jr."SourceDataPk"    = :suju_id
                     GROUP BY jr."SourceDataPk"
                   ) q ON q.suju_id = s.id
             WHERE s.id = :suju_id
            """, p);
	}

	private static final String SCOPE_SQL_CASCADE = """
            SELECT jr.id, jr."Material_id", jr."WorkIndex", jr."State",
                   jr."OrderQty", jr."Parent_id", m."Name" AS mat_name,
                   COALESCE(SUM(mp."GoodQty"), 0) AS produced
              FROM job_res jr
              JOIN material m ON m.id = jr."Material_id"
              LEFT JOIN mat_produce mp ON mp."JobResponse_id" = jr.id
             WHERE (jr.id = :id OR jr."Parent_id" = :id)
               AND jr."State" <> 'canceled'
             GROUP BY jr.id, jr."Material_id", jr."WorkIndex", jr."State",
                      jr."OrderQty", jr."Parent_id", m."Name"
            """;

	private static final String SCOPE_SQL_SINGLE = """
            SELECT jr.id, jr."Material_id", jr."WorkIndex", jr."State",
                   jr."OrderQty", jr."Parent_id", m."Name" AS mat_name,
                   COALESCE(SUM(mp."GoodQty"), 0) AS produced
              FROM job_res jr
              JOIN material m ON m.id = jr."Material_id"
              LEFT JOIN mat_produce mp ON mp."JobResponse_id" = jr.id
             WHERE jr.id = :id
             GROUP BY jr.id, jr."Material_id", jr."WorkIndex", jr."State",
                      jr."OrderQty", jr."Parent_id", m."Name"
            """;

	/**
	 * BOM 재귀 전개로 공정별 지시량 산출.
	 * explodeProcessRows 의 (2) 쿼리와 동일 — 생성/수정이 같은 계산을 쓰도록 분리했다.
	 * 생성 쪽도 이 메서드를 호출하도록 리팩터링하면 두 경로가 갈라지지 않는다.
	 */
	private List<Map<String, Object>> calcStepQty(Integer rootMatPk, Integer routingId, Float orderQty) {

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
		return sqlRunner.getRows(sql, p);
	}

	/**
	 * 수정 가능 여부 미리보기 — 화면이 모달을 열 때 호출한다.
	 * 「저장 눌렀더니 안 된다」 대신 「왜 안 되는지」를 먼저 보여주기 위한 것.
	 */
	public Map<String, Object> getEditGuard(Integer jobresId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", jobresId);

		Map<String, Object> target = sqlRunner.getRow("""
            SELECT jr.id, jr."Parent_id", jr."Routing_id",
                   jr."SourceDataPk", jr."SourceTableName"
              FROM job_res jr WHERE jr.id = :id
            """, p);
		if (target == null) return Map.of("editable", false, "reason", "작업지시를 찾을 수 없습니다.");

		boolean cascade = (target.get("Parent_id") == null) && (target.get("Routing_id") != null);

		List<Map<String, Object>> rows = sqlRunner.getRows(cascade ? """
            SELECT jr.id, jr."State", jr."OrderQty", jr."WorkIndex", m."Name" AS mat_name,
                   COALESCE(SUM(mp."GoodQty"),0) AS produced
              FROM job_res jr
              JOIN material m ON m.id = jr."Material_id"
              LEFT JOIN mat_produce mp ON mp."JobResponse_id" = jr.id
             WHERE (jr.id = :id OR jr."Parent_id" = :id) AND jr."State" <> 'canceled'
             GROUP BY jr.id, jr."State", jr."OrderQty", jr."WorkIndex", m."Name"
             ORDER BY jr."WorkIndex" NULLS FIRST, jr.id
            """ : """
            SELECT jr.id, jr."State", jr."OrderQty", jr."WorkIndex", m."Name" AS mat_name,
                   COALESCE(SUM(mp."GoodQty"),0) AS produced
              FROM job_res jr
              JOIN material m ON m.id = jr."Material_id"
              LEFT JOIN mat_produce mp ON mp."JobResponse_id" = jr.id
             WHERE jr.id = :id
             GROUP BY jr.id, jr."State", jr."OrderQty", jr."WorkIndex", m."Name"
            """, p);

		if (rows == null) return Map.of("editable", false, "reason", "구성을 읽지 못했습니다.");

		boolean hasFinished = false;
		float maxProduced = 0f;      // (기생산/현재지시량) 최대 비율 → 헤더 기준 하한 환산
		Float headerQty = null;

		for (Map<String, Object> r : rows) {
			if ("finished".equals(r.get("State"))) hasFinished = true;
			float produced = r.get("produced") != null ? ((Number) r.get("produced")).floatValue() : 0f;
			float oq       = r.get("OrderQty") != null ? ((Number) r.get("OrderQty")).floatValue() : 0f;
			if (((Number) r.get("id")).intValue() == jobresId) headerQty = oq;
			// 그 공정의 (기생산 / 현재지시량) 비율이 헤더 기준 하한을 결정한다
			if (produced > 0 && oq > 0) maxProduced = Math.max(maxProduced, produced / oq);
		}

		float minQty = (headerQty != null) ? headerQty * maxProduced : 0f;

		Map<String, Object> out = new HashMap<>();
		out.put("cascade", cascade);
		out.put("qtyEditable", !hasFinished);
		out.put("minQty", Math.ceil(minQty));
		out.put("hasProduction", maxProduced > 0);
		out.put("reason", hasFinished
				? "완료된 공정이 있어 지시량은 변경할 수 없습니다."
				: (maxProduced > 0
				? "이미 생산이 시작되어 " + (long) Math.ceil(minQty) + " 미만으로는 지시할 수 없습니다."
				: ""));
		// ── 수주량 동기화 컨펌 문구용 값 ──
		//   헤더 작지만 수주에 직접 물린다(자식은 SourceDataPk 가 없다).
		Integer gSujuId = (target.get("Parent_id") == null
				&& "suju".equals(target.get("SourceTableName"))
				&& target.get("SourceDataPk") != null)
				? ((Number) target.get("SourceDataPk")).intValue() : null;

		out.put("sujuLinked", gSujuId != null);
		if (gSujuId != null) {
			Map<String, Object> si = calcSujuInfo(gSujuId);
			out.put("sujuQty",      si != null ? si.get("SujuQty") : null);
			out.put("sujuOtherQty", sumOtherOrderedQty(gSujuId, jobresId));
		} else {
			out.put("sujuQty", null);
			out.put("sujuOtherQty", 0);
		}

		out.put("rows", rows);   // 상세는 내려주되 화면에 나열하지 않는다
		return out;
	}

	private static String key(Object matId, Object workIndex) {
		return String.valueOf(matId) + "#" + (workIndex == null ? "-" : String.valueOf(workIndex));
	}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	private static String trim(float f) {
		return (Math.abs(f - Math.round(f)) < 0.001f)
				? String.valueOf(Math.round(f))
				: String.format("%.2f", f);
	}

}