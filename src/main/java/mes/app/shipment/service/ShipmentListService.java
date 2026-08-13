package mes.app.shipment.service;

import java.util.List;
import java.util.Map;

import mes.domain.entity.ShipmentHead;
import mes.domain.repository.ShipmentHeadRepository;
import mes.domain.repository.SujuRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentListService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	mes.domain.repository.SujuRepository sujuRepository;

	public List<Map<String, Object>> getShipmentHeadList(String dateFrom, String dateTo, String compPk, String matGrpPk, String matPk, String keyword, String state, String company) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("dateFrom", dateFrom);
		paramMap.addValue("dateTo", dateTo);
		paramMap.addValue("compPk", compPk);
		paramMap.addValue("matGrpPk", matGrpPk);
		paramMap.addValue("matPk", matPk);
		paramMap.addValue("keyword", keyword);
		//String state = "shipped";
		paramMap.addValue("state", state);

		String sql = """
				select sh.id
		        , sh."Company_id" as company_id
                , c."Name" as company_name
		        , sh."ShipDate" as ship_date
		        , sh."TotalQty" as total_qty
	            , sh."TotalPrice" as total_price
	            , sh."TotalVat" as total_vat
	            , sh."Description" as description
                , sh."State" as state
                , fn_code_name('shipment_state', sh."State") as state_name
                , to_char(coalesce(sh."OrderDate",sh."_created") ,'yyyy-mm-dd') as order_date
                , sh."StatementIssuedYN" as issue_yn
                , sh."StatementNumber" as stmt_number
                , sh."IssueDate" as issue_date
                from shipment_head sh
                join company c on c.id = sh."Company_id"
                where sh."ShipDate"  between cast(:dateFrom as date) and cast(:dateTo as date)
				""";

		if (StringUtils.isEmpty(company) == false) {
			sql += " AND  c.\"Name\" LIKE :company ";
			paramMap.addValue("company", "%" + company + "%");
		}
		if (StringUtils.isEmpty(compPk)==false)  sql += " and sh.\"Company_id\" = cast(:compPk as Integer) ";
		if (StringUtils.isEmpty(state)==false)  sql += " and sh.\"State\" = :state ";
		if (StringUtils.isEmpty(matPk)==false || StringUtils.isEmpty(matGrpPk)==false || StringUtils.isEmpty(keyword)==false) {
			sql += """
					and exists ( select 1
        		    from shipment s
                    inner join material m on m.id = s."Material_id"
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    where s."ShipmentHead_id" = sh.id
					""";
			if (StringUtils.isEmpty(matPk)==false)  sql += " and s.\"Material_id\"  = cast(:matPk as Integer) ";
			if (StringUtils.isEmpty(matGrpPk)==false)  sql += " and mg.id  = cast(:matGrpPk as Integer) ";
			if (StringUtils.isEmpty(keyword)==false)  sql += " and ( m.\"Name\" ilike concat('%%',:keyword,'%%') or m.\"Code\" ilike concat('%%',:keyword,'%%')) ";

			sql += " )";
		}
		sql += """ 
		 		order by sh."ShipDate" desc, sh.id desc
		 		""";
		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}
	//단가는 기본적으로 수주면 수주때 지정단가 가져오고 (수주지정단가는 shipment에 저장되어있음. 수주가 수정되었다면 다를수있긴함.)
	//제품출하라면 품목의 최근단가를 가져온다.
	public List<Map<String, Object>> getShipmentItemList(String headId, Integer company_id) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("headId", headId);
		paramMap.addValue("companyId", company_id);

		String sql = """
				select s.id as ship_pk
				, s."Material_id" as mat_pk
				, mg."Name" as mat_grp_name
				, m."Code" as mat_code
				, m."Name" as mat_name
	            , m."UnitPrice" as mat_unit_price
	            , coalesce((s."OrderQty" * m."UnitPrice"), 0) as order_mat_price
				, u."Name" as unit_name
				, s."OrderQty" as order_qty
				, s."Qty" as ship_qty
				, s."Description" as description
				, sh."Company_id" as company_id
				, m.id as material_id
				--,case
				--	when s."SourceTableName" = 'product' then mcu."UnitPrice"
				--	else su."UnitPrice"
				--end as unit_price
				
				--단가
				,s."UnitPrice" as unit_price
				--공급가
				,s."Price" as price 
				-- 부가세
				,s."Vat" as vat
				
				,case
					when s."SourceTableName" = 'product' then 'N'
					else su."InVatYN"
				end as invatyn
			
				--,TRUNC((
				--                  CASE
				--                    WHEN s."SourceTableName" = 'product' THEN mcu."UnitPrice" * s."Qty"
				--                    WHEN su."InVatYN" = 'Y' THEN (su."UnitPrice" * (10.0 / 11)) * s."Qty"
				--                    ELSE su."UnitPrice" * s."Qty"
				--                  END
				--                )::numeric, 2) AS price
				--,case
				--	when s."SourceTableName" = 'product' then (mcu."UnitPrice" * s."Qty") * 0.1
				--	when su."InVatYN" = 'Y' then (su."UnitPrice" - (su."UnitPrice" * (10.0/11))) * s."Qty"
				--	else (su."UnitPrice" * s."Qty") * 0.1
				--end as vat
	            , m."VatExemptionYN" as vat_exempt_yn
	            , s."SourceDataPk" as src_data_pk
	            , s."SourceTableName" as src_table_name
	            , case when s."SourceTableName" = 'rela_data' 
	            		then '수주출하'
	            	   when s."SourceTableName" = 'product'	
						then '제품출하'
					   else '알수없음'
				end as shipment_flag
				, COALESCE(su."Standard", m."Standard1") as standard	 
				from shipment  s
				inner join material m on m.id = s."Material_id" 
				inner join mat_grp mg on mg.id = m."MaterialGroup_id"
				left join unit u on u.id = m."Unit_id" 
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id"  
	            left join (	
				             			select distinct on ("Material_id") "Material_id", "UnitPrice"
				             			from mat_comp_uprice
				         				WHERE "Type" = '02'
				             				AND "Company_id" = :companyId
				             				AND "ApplyEndDate" > CURRENT_DATE
				             			order by "Material_id", "ApplyStartDate" desc
				         				) mcu on mcu."Material_id" = s."Material_id" and s."SourceTableName" = 'product'
				left join suju su on su.id = s."SourceDataPk"	
				where s."ShipmentHead_id" = cast(:headId as Integer)
	            order by m."Code", m."Name"
				""";
		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

//	public void updateSujuShipmentCancel(Integer shId) {
//		sujuRepository.updateShipmentStateByShipmentId(shId, "cancelled");
//	}

	public void updateShipmentQantityByLotConsume (Integer sh_id, Integer shipment_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("sh_id", sh_id);
		paramMap.addValue("shipment_id", shipment_id);

		String sql = """
				with A as(
	            select
	            s.id, coalesce(sum(mlc."OutputQty"),0) as qty  
	            from shipment s  
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	            left join mat_lot_cons mlc on mlc."SourceTableName" ='shipment' and mlc."SourceDataPk" = s.id
	            where 1=1 
	            and sh.id = :sh_id
				""";

		if (shipment_id != null) {
			sql += " and s.id = :shipment_id ";
		}

		sql += """
				group by s.id),
				UPC as (
	            select
	            s.id
	            , s."Material_id"
	            , sh."Company_id"
	            , mcu."UnitPrice"
	            , m."VatExemptionYN"
	            from A
	            inner join shipment s on s.id = A.id
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	            inner join material m on m.id = s."Material_id" 
	            left join mat_comp_uprice mcu on mcu."Material_id"=s."Material_id" and mcu."Company_id"=sh."Company_id" and mcu."ApplyStartDate" <=now() and mcu."ApplyEndDate" > now()
	            where sh.id = :sh_id 
	        ), B as(        
	           select 
	           s.id
	           , A.qty
	           , UPC."UnitPrice" 
	           , (A.qty * UPC."UnitPrice") as "Price"
	           , case when UPC."VatExemptionYN"='Y' then 0 else (A.qty * UPC."UnitPrice"*0.1) end  as "Vat" 
	           , s."Material_id"
	           , UPC."Company_id"
	           from shipment s 
	             inner join shipment_head sh2 on sh2.id = s."ShipmentHead_id"
	             inner join A on A.id = s.id             
	             inner join UPC on UPC.id = s.id
	        )
	        update shipment set 
	         "Qty" = B.qty 
	         , "UnitPrice" = B."UnitPrice"
	         , "Price" =  B."Price"
	         , "Vat" = B."Vat"
	        from B
	        where shipment.id = B.id
				""";

		this.sqlRunner.execute(sql, paramMap);
	}

	// =========================================================================
	// 출고 취소 — 되돌릴 수 있는지 확인하고, 되돌린다
	// =========================================================================

	/**
	 * 취소 가능 여부. 막아야 할 사유가 있으면 «문구» 를, 없으면 null 을 돌려준다.
	 *
	 * ★ 두 가지를 본다.
	 *   ① 수리 접수 — 나간 물건이 반품·수리로 돌아왔으면 그 출고는 이미 후속 이력이 붙었다.
	 *      여기서 취소하면 재고가 되살아나는데 실물은 수리창고(20)에 있어
	 *      «같은 물건이 두 곳에» 잡힌다.
	 *   ② 로트 상태 — 출고 뒤 그 로트가 «다시 소비» 됐으면 되돌릴 수 없다.
	 *      순서를 거꾸로 풀면 중간 이력이 매달릴 곳을 잃는다.
	 *      로트 행 자체가 사라진 경우도 마찬가지다.
	 */
	public String findCancelBlockReason(Integer shId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("shId", shId);

		// ① 수리 접수
		Map<String, Object> rep = this.sqlRunner.getRow("""
            SELECT mr."RepairNo" AS repair_no, ml."LotNumber" AS lot_number
              FROM shipment s
              JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'shipment'
                                   AND mlc."SourceDataPk"    = s.id
              JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
              JOIN mcell_repair mr ON (mr."SrcMatLot_id" = ml.id
                                    OR mr."SrcLotNumber" = ml."LotNumber")
             WHERE s."ShipmentHead_id" = :shId
               AND COALESCE(mr._status,'a') <> 'd'
             LIMIT 1
            """, p);
		if (rep != null)
			return "수리 접수(" + rep.get("repair_no") + ")가 걸린 로트가 있어 취소할 수 없습니다. — "
							 + rep.get("lot_number");

		// ② 출고 뒤 그 로트가 다시 소비됐는가
		Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT ml."LotNumber" AS lot_number, mlc2."SourceTableName" AS src
              FROM shipment s
              JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'shipment'
                                   AND mlc."SourceDataPk"    = s.id
              JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
              JOIN mat_lot_cons mlc2 ON mlc2."MaterialLot_id" = ml.id
                                    AND mlc2.id > mlc.id
                                    AND mlc2."SourceTableName" <> 'shipment'
             WHERE s."ShipmentHead_id" = :shId
             LIMIT 1
            """, p);
		if (used != null)
			return "출고 이후 다시 투입된 로트가 있어 취소할 수 없습니다. — "
							 + used.get("lot_number") + " (" + used.get("src") + ")";

		// ③ 되돌릴 로트 행이 남아 있는가 (지워졌으면 되살릴 대상이 없다)
		Map<String, Object> gone = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS cnt
              FROM shipment s
              JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'shipment'
                                   AND mlc."SourceDataPk"    = s.id
              LEFT JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
             WHERE s."ShipmentHead_id" = :shId AND ml.id IS NULL
            """, p);
		if (gone != null && toLong(gone.get("cnt")) > 0)
			return "출고에 물린 로트가 이미 삭제되어 재고를 되돌릴 수 없습니다.";

		return null;
	}

	/**
	 * 출고로 나간 재고를 되돌린다.
	 *
	 * ★★ 예전에는 이 단계가 «아예 없었다». shipment."Qty" 를 0 으로 쓰고 _status 를 't' 로
	 *   바꾸는 것이 전부라, mat_lot_cons 가 그대로 남아 재고는 차감된 채였다.
	 *   게다가 바로 뒤에 도는 updateShipmentQantityByLotConsume 이 그 남은 소비이력에서
	 *   수량을 다시 계산해 "Qty" 를 원래대로 «복원» 했다 — 취소가 사실상 무효였다.
	 *
	 * ★ mat_lot."CurrentStock" 을 직접 UPDATE 하지 않는다. 트리거 소관이다
	 *   (부적합·포장과 같은 규칙). mat_lot_cons 를 지우면 알아서 되살아난다.
	 * ★ mat_inout(out) 도 함께 지운다. 이력만 남으면 집계와 실제가 갈린다.
	 * ★ 카톤 출고표시도 푼다. 안 풀면 그 박스는 다시 찍을 수 없다.
	 */
	public void rollbackShipmentLots(Integer shId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("shId", shId);

		// 카톤 출고표시 해제 — mat_lot_cons 를 지우기 «전» 에 한다(조인이 살아 있을 때)
		this.sqlRunner.execute("""
            UPDATE pack_carton
               SET "ShipState" = NULL, "Shipment_id" = NULL, _modified = now()
             WHERE "Shipment_id" IN (SELECT id FROM shipment WHERE "ShipmentHead_id" = :shId)
            """, p);

		// 소비이력(out) 제거
		this.sqlRunner.execute("""
            DELETE FROM mat_inout
             WHERE "SourceTableName" = 'shipment' AND "InOut" = 'out'
               AND "SourceDataPk" IN (SELECT id FROM shipment WHERE "ShipmentHead_id" = :shId)
            """, p);

		// 차감 되돌리기 — 트리거가 mat_lot."CurrentStock" 을 복원한다
		this.sqlRunner.execute("""
            DELETE FROM mat_lot_cons
             WHERE "SourceTableName" = 'shipment'
               AND "SourceDataPk" IN (SELECT id FROM shipment WHERE "ShipmentHead_id" = :shId)
            """, p);
	}

	private static long toLong(Object o) {
		if (o == null) return 0L;
		if (o instanceof Number) return ((Number) o).longValue();
		try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
	}

	// 출고헤더 기준으로 상태값 (출고상태)변경
	public void updateShipmentStateCancel (Integer searchId) {

		updateShipmentQantityByLotConsume(searchId, null);

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("searchId", searchId);

		String sql = """
				with A as(
				select 
		        sh.id as sh_id
		        , count(s.id) as s_count
		        , sum(s."Price") as "TotalPrice"
		        , sum(s."Vat") as "TotalVat"
		        from shipment s 
		        inner join shipment_head sh on sh.id=s."ShipmentHead_id"
		        where sh.id=:searchId
		        group by sh.id 
		        )
		        update 
		        shipment_head 
		        set "State" = 'ordered'
		        from A 
		        where id=A.sh_id
				""";

		this.sqlRunner.execute(sql, paramMap);
	}

	// 출고 취소 관련 수주를 찾아서 수주의 출하 상태 출고 전으로 를 변경한다.
	public void updateSujuShipmentStateCancel (Integer sh_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("sh_id", sh_id);

		String sql = """
		        with A as(
		        select
		        s.id as shipment_id
		        ,sh.id as sh_id
		        , rd."DataPk1" as suju_id
		        , sj."State"
		        , sj."ShipmentState"
		        from shipment s 
		        inner join shipment_head sh on sh.id=s."ShipmentHead_id"
		        inner join rela_data rd on rd."TableName1" ='suju' and rd."TableName2" ='shipment' and rd."DataPk2" =s.id
		        inner join suju sj on sj.id = rd."DataPk1" 
		        where sh.id = :sh_id
		        )
		        update suju set "ShipmentState" ='inpec'
		        from A where A.suju_id = id
				""";

		this.sqlRunner.execute(sql, paramMap);
	}

}