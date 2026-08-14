package mes.app.shipment.service;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import mes.domain.repository.MatLotConsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentDoBService {

	@Autowired
	SqlRunner sqlRunner;
	@Autowired
	private MatLotConsRepository matLotConsRepository;
	@Autowired
	NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	// 출하지시헤더 조회
	public List<Map<String, Object>> getShipmentHeaderList(String date_from, String date_to, String state, Integer comp_pk, Integer mat_grp_pk, Integer mat_pk, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("date_from", Date.valueOf(date_from));
		paramMap.addValue("date_to", Date.valueOf(date_to));
		paramMap.addValue("state", state);
		paramMap.addValue("comp_pk", CommonUtil.tryIntNull(comp_pk));
		paramMap.addValue("mat_grp_pk", CommonUtil.tryIntNull(mat_grp_pk));
		paramMap.addValue("mat_pk", CommonUtil.tryIntNull(mat_pk));
		paramMap.addValue("keyword", keyword == null ? "" : "%" + keyword + "%");

		String sql = """
			with SH as
					    (
					    select sh.id
			                , sh."Company_id" as company_id
			                , c."Name" as company_name
			                , sh."ShipDate" as ship_date	                
			                , sh."TotalPrice" as total_price
			                , sh."TotalVat" as total_vat
			                , sh."State" as state
			                , fn_code_name('shipment_state', sh."State") as state_name
				            , sh."Description" as description
			                from shipment_head sh
				            left join company c on c.id = sh."Company_id"
				            where sh."ShipDate" between :date_from and :date_to		
        		     """;

		if (comp_pk != null) {
			sql += " and sh.\"Company_id\" = :comp_pk ";
		}

		if (StringUtils.isEmpty(state) == false) {
			sql += " and sh.\"State\" = :state ";
		}

		sql += """
        		 ), S as 
			    (
			    select s."ShipmentHead_id" as head_id
	            , sum(s."OrderQty") as tot_order_qty
	            , sum(s."Qty") as tot_ship_qty
	            , count(s.id) as item_count
			    from SH
			    inner join shipment s on s."ShipmentHead_id" = SH.id 
        	""";

		if(mat_grp_pk != null || mat_pk != null || !keyword.isEmpty()){
			sql += "inner join material m on m.id = s.\"Material_id\" ";
		}

		sql += " where 1 = 1 ";

		if (mat_pk != null) {
			sql += " and s.\"Material_id\" = :mat_pk ";
		} else if (mat_grp_pk != null) {
			sql += " and m.\"MaterialGroup_id\" = :mat_grp_pk ";
		}

		if(!keyword.isEmpty()){
			sql += " and m.\"Name\" like :keyword";
		}


		sql += """
        		group by s."ShipmentHead_id"
			    )
			    select SH.*
	            , S.tot_order_qty
	            , S.tot_ship_qty
	            , S.item_count
			    from SH 
			    inner join S on S.head_id = SH.id
	            where 1 = 1
        		""";
		sql += """
				order by SH.ship_date, SH.id desc
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	// 출하 항목 조회
	public List<Map<String, Object>> getShipmentList (Integer shipment_header_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("shipment_header_id", shipment_header_id);

		String sql = """
	       select
	        s."ShipmentHead_id" as sh_id
	        , s.id as shipment_id
	        , sh."State"
	        , s."Material_id"
	        , mg."Name" as mat_grp_name
	        , m."Name" as mat_name
	        , m."Code" as mat_code
	        , s."UnitPrice" as unit_price
	        , s."Price" as price
	        , s."Vat" as vat
	        , (s."Price" + s."Vat") as total_price
	        , m."VatExemptionYN" as vat_ex_yn
	        , COALESCE(suju."InVatYN", 'N') as invat_yn
	        , u."Name" as unit_name 
	        , s."OrderQty"
	        , s."Qty"
	        , COALESCE(suju."Standard", m."Standard1") as standard
	        , s."Description" as description
	        , (select coalesce(sum(mlc."OutputQty" ), 0) as lot_qty from mat_lot_cons mlc where mlc."SourceDataPk" = s.id and mlc."SourceTableName"='shipment') as lot_qty
	        from shipment s 
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	            inner join material m on m.id = s."Material_id" 
	            left join suju suju on suju.id = s."SourceDataPk" and s."SourceTableName" = 'rela_data'
	            left join mat_grp mg on mg.id = m."MaterialGroup_id" 
	            left join unit u on u.id = m."Unit_id" 
	        where sh.id = :shipment_header_id	
		    order by s.id desc
		        		 """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	// 출하 처리 LOT상세
	public List<Map<String, Object>> getShipmentLotList (Integer sh_id, Integer shipment_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("shipment_id", shipment_id);
		paramMap.addValue("sh_id", sh_id);

		String sql = """
			          select
		                  mlc.id as mlc_id
		                  , mlc."MaterialLot_id" as ml_id 
		                  , ml."LotNumber"
		                  , ml."CurrentStock"
		                  , ml."OutQtySum"
		                  , mlc."OutputQty"
		                  , ml."Material_id" 
		                  , mlc."SourceDataPk"
		                  , u."Name" as unit_name
		                  , to_char(ml."EffectiveDate", 'YYYY-MM-DD HH24:MI:SS') as "EffectiveDate"
		                  -- ★ 국가는 카톤이 아니라 배분(pack_alloc)에서 가져온다.
		                  --   로트가 이미 국가별로 갈려 있어서(P-…-KR) 박스를 안 찍고
		                  --   로트를 직접 골라도 국가는 보여야 한다.
		                  --   2공장(M-CELL)은 배분이 없어 빈칸이고, 그게 정상이다.
		                  , coalesce(pc."CountryCode", pa."CountryCode") as carton_country
		                  , pc."CartonLotNo" as carton_lot_no
		                  , pc."Qty"         as carton_qty
		               from shipment_head sh
				 	   inner join shipment s on s."ShipmentHead_id"=sh.id            
		               inner join mat_lot_cons mlc on mlc."SourceTableName"='shipment' and mlc."SourceDataPk" = s.id
		               inner join mat_lot ml on mlc."MaterialLot_id" = ml.id
		               inner join material m on m.id=ml."Material_id" 
		               left join unit u on u.id =m."Unit_id" 
		               left join pack_alloc pa on pa."MatLot_id" = ml.id
		                                      and coalesce(pa._status,'a') = 'a'
		               -- 이 출하 건으로 실제 찍은 박스. MatLot_id 까지 맞추지 않으면
		               -- 국가가 섞인 출하에서 남의 나라 박스가 붙는다
		               left join pack_carton pc on pc."Shipment_id" = s.id
		                                       and pc."MatLot_id"   = ml.id
		                                       and coalesce(pc._status,'a') = 'a'
		               where sh.id = :sh_id
		        		 """;
		if (shipment_id != null) {
			sql += " and s.id = :shipment_id ";
		}
		sql += " order by ml.\"LotNumber\" ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	// lot 검색
	/**
	 * LOT 지정 팝업 검색.
	 *
	 * ★ lot_number 는 「사내 로트번호」가 아니라 「스캔한 값」이다. 세 가지가 들어온다.
	 *     ① 사내 로트번호      P-20260810-0009-KR
	 *     ② 카톤 개체 바코드   C-20260810-0005-KR-01
	 *     ③ 카톤 대표번호      C-20260810-0010
	 *
	 * ★ ③이 현장의 실제 라벨이다.
	 *   포장이 카톤 라벨을 「동일수량 1바코드」로 뽑아서, 같은 차수의 박스가 국가·순번
	 *   구분 없이 같은 번호를 단다. 스캔만으로는 어느 나라 몫인지 못 가른다.
	 *   그래서 대표번호로 걸리면 국가별로 한 줄씩 후보를 내려 사람이 고르게 한다.
	 *
	 * ★ 국가별로 「아직 안 나간 박스 중 가장 빠른 순번」 하나만 내린다.
	 *   전부 나열하면 국가당 7줄씩 쌓여 고르기 어렵고, 순서대로 나가면 되므로
	 *   다음 박스를 정해 주는 편이 빠르다. 남은 개수는 carton_remain 으로 함께 낸다.
	 *
	 * ★ 국가(carton_country)는 카톤이 없어도 pack_alloc 에서 채운다.
	 *   로트가 이미 국가별로 갈려 있으므로(P-…-KR) 박스를 안 찍고 로트를 직접 골라도
	 *   국가는 보여야 한다. 2공장(M-CELL)은 배분이 없어 빈칸이고, 그게 정상이다.
	 */
	/**
	 * 출고에 담을 후보 목록 — «박스(카톤) 한 개당 한 줄» + 박스로 안 묶인 잔여.
	 *
	 * ═══ 왜 다시 썼나 (2026-08) ═══
	 *   ① unit_kind 를 안 내려줬다.
	 *      화면은 r.unit_kind === 'carton' 으로 박스 줄을 가르는데 그 컬럼이 없어
	 *      필터가 항상 0건이 됐다 — 카톤을 찍어도 «후보 없음» 으로 팝업이 열렸다.
	 *   ② distinct on ("MatLot_id") 로 박스를 «한 줄» 로 접었다.
	 *      로트 하나에 박스가 3개면 3줄이 나와야 한다. 접어 버리면
	 *      C-…-KR-01 만 보이고 -02 · -03 은 존재하지 않는 것처럼 된다.
	 *   ③ UDI(외부 라벨)로 못 찾았다.
	 *      mat_lot."MakerLotNo" 를 조건에 안 넣어 박스라벨 스캔이 빗나갔다.
	 *
	 * ═══ 돌려주는 줄 ═══
	 *   unit_kind='carton' 아직 안 나간 박스 하나 = 한 줄. ship_qty = 그 박스 수량
	 *   unit_kind='lot'    박스로 안 묶인 잔여 재고. ship_qty = 그 잔여
	 *     └ 박스 합계가 재고를 다 덮으면 이 줄은 «안 나온다» — 안 그러면
	 *       같은 물건이 박스 줄과 낱개 줄로 두 번 세어진다.
	 *
	 * @param lot_number 사내 로트 / 외부 UDI / 카톤 개체번호 / 카톤 대표번호.
	 *                   비면 material_id 로 전체를 훑는다(팝업 목록).
	 */
	public List<Map<String, Object>> getMatLotSearch (Integer sh_id, Integer material_id, String lot_number) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("material_id", material_id);
		paramMap.addValue("lot_number", StringUtils.isEmpty(lot_number) ? null : lot_number.trim());

		String sql = """
            with k as (
                select nullif(trim(cast(:lot_number as varchar)),'') as skey
            )
            /* 조회 대상 완제품 로트.
               스캔 키는 네 갈래로 들어온다 — 어느 쪽이든 같은 로트에 닿아야 한다. */
            , base as (
                select ml.*
                  from mat_lot ml cross join k
                 where coalesce(ml."CurrentStock",0) > 0
                   and (cast(:material_id as integer) is null
                        or ml."Material_id" = cast(:material_id as integer))
                   and (k.skey is null
                        or ml."LotNumber"  = k.skey          -- 사내 로트
                        or ml."MakerLotNo" = k.skey          -- ★ 외부 UDI(박스라벨)
                        or exists (                          -- 카톤 개체번호
                              select 1 from pack_carton pc0
                               where pc0."MatLot_id" = ml.id
                                 and coalesce(pc0._status,'a') = 'a'
                                 and pc0."CartonLotNo" = k.skey)
                        or exists (                          -- 카톤 대표번호(pack_label)
                              select 1
                                from pack_label pl
                                join pack_carton pc1 on pc1."MatProduce_id" = pl."MatProduce_id"
                                                    and pc1."MatLot_id"     = ml.id
                                                    and coalesce(pc1._status,'a') = 'a'
                               where pl."LabelKind" = 'carton'
                                 and coalesce(pl._status,'a') = 'a'
                                 and pl."LotNo" = k.skey)
                       )
            )
            -- 아직 안 나간 박스. 여기 있는 행 «하나하나» 가 화면의 한 줄이 된다
            , box as (
                select pc.*
                  from pack_carton pc
                  join base b on b.id = pc."MatLot_id"
                 cross join k
                 where coalesce(pc._status,'a') = 'a'
                   and coalesce(pc."ShipState",'') <> 'shipped'
                   /* ★ «박스 하나» 를 찍었으면 그 박스만 돌려준다.
                        안 그러면 C-…-KR-01 을 찍어도 같은 로트의 -02 · -03 이
                        함께 올라와 후보가 3건이 되고, 화면이 자동등록을 포기하고
                        팝업을 연다(자동 담기의 조건은 «후보 1건»). */
                   and (k.skey is null
                        or pc."CartonLotNo" = k.skey
                        or not exists (select 1 from pack_carton pc9
                                        where pc9."CartonLotNo" = k.skey
                                          and coalesce(pc9._status,'a') = 'a'))
            )
            , box_sum as (
                select "MatLot_id", count(*) as cnt, sum("Qty") as qty
                  from box group by "MatLot_id"
            )
            -- ① 박스 줄
            select 'carton'::text          as unit_kind
                 , ml.id                   as ml_id
                 , ml."LotNumber"
                 , ROUND(ml."InputQty"::numeric, 2)     as "InputQty"
                 , ROUND(ml."CurrentStock"::numeric, 2) as "CurrentStock"
                 , ml."MakerLotNo"         as maker_lot_no
                 , ml."Material_id"
                 , u."Name"  as unit_name
                 , m."Code"  as mat_code
                 , m."Name"  as mat_name
                 , mg."Name" as mat_grp_name
                 , to_char(ml."EffectiveDate",'YYYY-MM-DD HH24:MI:SS') as "EffectiveDate"
                 , to_char(ml."InputDateTime",'YYYY-MM-DD HH24:MI:SS') as "InputDateTime"
                 , bx.id            as carton_id
                 , bx."CartonLotNo" as carton_lot_no
                 , bx."CartonNo"    as carton_no
                 , bx."Qty"         as carton_qty
                 , coalesce(bx."CountryCode", pa."CountryCode") as carton_country
                 , bx."ShipState"   as carton_state
                 , bs.cnt           as carton_remain
                 , bx."Qty"         as ship_qty          -- 박스는 통째로 나간다
              from box bx
              join base ml   on ml.id = bx."MatLot_id"
              join material m on m.id = ml."Material_id"
              left join mat_grp mg on mg.id = m."MaterialGroup_id"
              left join unit u     on u.id  = m."Unit_id"
              left join box_sum bs on bs."MatLot_id" = ml.id
              left join pack_alloc pa on pa."MatLot_id" = ml.id
                                     and coalesce(pa._status,'a') = 'a'

            union all

            -- ② 박스로 안 묶인 잔여 (박스가 재고를 다 덮으면 이 줄은 안 나온다)
            select 'lot'::text
                 , ml.id
                 , ml."LotNumber"
                 , ROUND(ml."InputQty"::numeric, 2)
                 , ROUND(ml."CurrentStock"::numeric, 2)
                 , ml."MakerLotNo"
                 , ml."Material_id"
                 , u."Name", m."Code", m."Name", mg."Name"
                 , to_char(ml."EffectiveDate",'YYYY-MM-DD HH24:MI:SS')
                 , to_char(ml."InputDateTime",'YYYY-MM-DD HH24:MI:SS')
                 , null::integer, null::varchar, null::integer, null::float8
                 , pa."CountryCode", null::varchar, 0
                 , ROUND((ml."CurrentStock" - coalesce(bs.qty,0))::numeric, 2)
              from base ml
              join material m on m.id = ml."Material_id"
              left join mat_grp mg on mg.id = m."MaterialGroup_id"
              left join unit u     on u.id  = m."Unit_id"
              left join box_sum bs on bs."MatLot_id" = ml.id
              left join pack_alloc pa on pa."MatLot_id" = ml.id
                                     and coalesce(pa._status,'a') = 'a'
             where ml."CurrentStock" - coalesce(bs.qty,0) > 0

             order by 3, 16 nulls last          -- LotNumber, CartonNo
            """;

		return this.sqlRunner.getRows(sql, paramMap);
	}

	/**
	 * 카톤을 출고 처리로 표시한다.
	 *
	 * ★ ux_pack_carton_lot 유니크로 번호 중복은 막히지만, 같은 박스를 두 번 찍는 건
	 *   막지 못한다. ShipState 로 막는다 — 조건부 UPDATE 라 동시에 두 단말이 찍어도
	 *   한 쪽만 1을 돌려받는다. 화면 가드만으로는 이 경합을 못 막는다.
	 *
	 * @return 실제로 바뀐 행 수. 0 이면 이미 출고된 박스다.
	 */
	public int markCartonShipped(Integer cartonId, Integer shipmentId, Integer userId) {
		if (cartonId == null) return 0;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", cartonId);
		p.addValue("shipmentId", shipmentId);
		p.addValue("userId", userId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            UPDATE pack_carton
               SET "ShipState"   = 'shipped'
                 , "Shipment_id" = CAST(:shipmentId AS integer)
                 , _modified     = now()
                 , _modifier_id  = CAST(:userId AS integer)
             WHERE id = :id
               AND COALESCE("ShipState",'') <> 'shipped'
            RETURNING id
            """, p);
		return (r == null) ? 0 : 1;
	}

	/**
	 * 출고 취소 시 되돌린다.
	 *
	 * ★ pack_carton."Shipment_id" 는 FK 가 없다(느슨한 연결). DB 가 막아주지 않으므로
	 *   출고를 지우는 쪽에서 반드시 이걸 불러야 한다. 빠뜨리면 멀쩡한 박스가
	 *   영원히 'shipped' 로 남아 재출고가 안 된다.
	 */
	public int unmarkCartonShipped(Integer shipmentId) {
		if (shipmentId == null) return 0;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("shipmentId", shipmentId);
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            UPDATE pack_carton
               SET "ShipState" = NULL, "Shipment_id" = NULL, _modified = now()
             WHERE "Shipment_id" = :shipmentId
            RETURNING id
            """, p);
		return (rows == null) ? 0 : rows.size();
	}

	public void deleteMatLotCons(Integer mat_lot_cons_id){
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("mat_lot_cons_id", mat_lot_cons_id);

		String sql = """
				delete from mat_lot_cons where id = :mat_lot_cons_id
				""";
		this.sqlRunner.execute(sql, paramMap);
	}

	public void updateShipmentQantityByLotConsume (Integer sh_id, Integer shipment_id, String sourceData) {

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

		if(sourceData.equals("rela_data")){
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
	           , suju."InVatYN" as invat
	           from shipment s 
	           	
	           	inner join suju suju
				on suju.id = s."SourceDataPk"
				and s."SourceTableName" = 'rela_data'
				
	           	 inner join shipment_head sh2 on sh2.id = s."ShipmentHead_id"
	             inner join A on A.id = s.id             
	             inner join UPC on UPC.id = s.id
	             )
	        update shipment set 
	         "Qty" = B.qty 
	         , "UnitPrice" = B."UnitPrice"
	         , "Price" = CASE
	         		WHEN B.invat = 'Y' THEN ROUND((B."Price" / 1.1)::numeric, 2)
	         		ELSE B."Price"
	         		END
	         , "Vat" = CASE
			    WHEN B.invat = 'Y' THEN ROUND(((B."Price" / 1.1) * 0.1)::numeric, 2)
			    ELSE B."Vat"
			END
	        from B
	        where shipment.id = B.id
	        """;
		}else if(sourceData.equals("product")){
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
	         , "Price" = B."Price"
	         , "Vat" = B."Vat"
			from B
	        where shipment.id = B.id
					""";

		}else{
			throw new RuntimeException("SourceDataTable의 값이 올바르지 않습니다.");
		}

		this.sqlRunner.execute(sql, paramMap);
	}

	public void updateShipmentAndHeadByLotConsume(Integer sh_id, Integer shipment_id, String sourceData) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("sh_id", sh_id);
		paramMap.addValue("shipment_id", shipment_id);

		String sql = """
        with A as (
            select
                s.id,
                coalesce(sum(mlc."OutputQty"), 0) as qty
            from shipment s
            inner join shipment_head sh on sh.id = s."ShipmentHead_id"
            left join mat_lot_cons mlc 
                on mlc."SourceTableName" = 'shipment' 
                and mlc."SourceDataPk" = s.id
            where sh.id = :sh_id
    """;

		if (shipment_id != null) {
			sql += " and s.id = :shipment_id ";
		}

		sql += " group by s.id ), ";

		if (sourceData.equals("rela_data")) {

			sql += """
            UPC as (
                select
                    s.id,
                    s."Material_id",
                    sh."Company_id",
                    --mcu."UnitPrice",
                    coalesce(s."UnitPrice", mcu."UnitPrice") as "UnitPrice",
                    m."VatExemptionYN"
                from A
                inner join shipment s on s.id = A.id
                inner join shipment_head sh on sh.id = s."ShipmentHead_id"
                inner join material m on m.id = s."Material_id"
                left join mat_comp_uprice mcu
                    on mcu."Material_id" = s."Material_id"
                    and mcu."Company_id" = sh."Company_id"
                    and mcu."ApplyStartDate" <= now()
                    and mcu."ApplyEndDate" > now()
                where sh.id = :sh_id
            ),
            B as (
                select
                    s.id,
                    A.qty,
                    UPC."UnitPrice",
                    (A.qty * UPC."UnitPrice" * coalesce(nullif(regexp_replace(suju."Standard", '[^0-9\\.]', '', 'g'), '')::numeric,1)) as "Price",
                    case when UPC."VatExemptionYN" = 'Y' then 0 else (A.qty * UPC."UnitPrice" * 0.1 * coalesce(nullif(regexp_replace(suju."Standard", '[^0-9\\.]', '', 'g'), '')::numeric,1)) end as "Vat",
                    COALESCE(suju."InVatYN", 'N') as invat
                from shipment s
                inner join suju suju on suju.id = s."SourceDataPk" and s."SourceTableName" = 'rela_data'
                inner join A on A.id = s.id
                inner join UPC on UPC.id = s.id
            )
            update shipment s set
                "Qty" = B.qty,
                "UnitPrice" = B."UnitPrice",
                "Price" = case
                    when B.invat = 'Y' then ROUND((B."Price" / 1.1)::numeric, 2)
                    else B."Price"
                end,
                "Vat" = case
                    when B.invat = 'Y' then ROUND(((B."Price" / 1.1) * 0.1)::numeric, 2)
                    else B."Vat"
                end
            from B
            where s.id = B.id;

            update shipment_head sh set
                "TotalQty" = coalesce((select sum(s."Qty") from shipment s where s."ShipmentHead_id" = :sh_id), 0),
                "TotalPrice" = coalesce((select sum(s."Price") from shipment s where s."ShipmentHead_id" = :sh_id), 0),
                "TotalVat" = coalesce((select sum(s."Vat") from shipment s where s."ShipmentHead_id" = :sh_id), 0)
            where sh.id = :sh_id;
        """;

		} else if (sourceData.equals("product")) {

			sql += """
            UPC as (
                select
                    s.id,
                    s."Material_id",
                    sh."Company_id",
                    mcu."UnitPrice",
                    m."VatExemptionYN"
                from A
                inner join shipment s on s.id = A.id
                inner join shipment_head sh on sh.id = s."ShipmentHead_id"
                inner join material m on m.id = s."Material_id"
                left join mat_comp_uprice mcu 
                    on mcu."Material_id" = s."Material_id"
                    and mcu."Company_id" = sh."Company_id"
                    and mcu."ApplyStartDate" <= now()
                    and mcu."ApplyEndDate" > now()
                where sh.id = :sh_id
            ),
            B as (
                select
                    s.id,
                    A.qty,
                    UPC."UnitPrice",
                    (A.qty * UPC."UnitPrice") as "Price",
                    case when UPC."VatExemptionYN" = 'Y' then 0 else (A.qty * UPC."UnitPrice" * 0.1) end as "Vat"
                from shipment s
                inner join A on A.id = s.id
                inner join UPC on UPC.id = s.id
            )
            update shipment s set
                "Qty" = B.qty,
                "UnitPrice" = B."UnitPrice",
                "Price" = B."Price",
                "Vat" = B."Vat"
            from B
            where s.id = B.id;

            update shipment_head sh set
                "TotalQty" = coalesce((select sum(s."Qty") from shipment s where s."ShipmentHead_id" = :sh_id), 0),
                "TotalPrice" = coalesce((select sum(s."Price") from shipment s where s."ShipmentHead_id" = :sh_id), 0),
                "TotalVat" = coalesce((select sum(s."Vat") from shipment s where s."ShipmentHead_id" = :sh_id), 0)
            where sh.id = :sh_id;
        """;
		} else {
			throw new RuntimeException("SourceDataTable의 값이 올바르지 않습니다.");
		}

		this.sqlRunner.execute(sql, paramMap);
	}

	// 수주헤더 기준으로 출하항목(shipment) 금액합산 정리
	public void updateShipmentStateComplete (Integer sh_id, String description, String sourceData) {

		updateShipmentQantityByLotConsume(sh_id, null, sourceData);

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("sh_id", sh_id);
		paramMap.addValue("description", description);

		String sql = """
				with A as(
				select
		        sh.id as sh_id
		        , count(s.id) as s_count
		        , sum(s."Price") as "TotalPrice"
		        , sum(s."Vat") as "TotalVat"
		        , sum(s."Qty") as "TotalQty"
		        from shipment s
		        inner join shipment_head sh on sh.id=s."ShipmentHead_id"
		        where sh.id=:sh_id
		        group by sh.id
		        )
		        update
		        shipment_head
		        set
		        --이거 lot 추가할때마다 head도 합산해서 수정해줌 그래서 아래는 주석함.
		        --"TotalQty" = A."TotalQty"
		        --,"TotalVat" = A."TotalVat"
		        --, "TotalPrice" = A."TotalPrice"
		        "State" = 'shipped'
		        ,"Description" = :description
		        from A
		        where id=A.sh_id
				""";

		this.sqlRunner.execute(sql, paramMap);
	}

	// 관련 수주를 찾아서 수주의 출하 상태를 변경한다.
	public void updateSujuShipmentState (Integer sh_id) {

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
		        update suju set "ShipmentState" ='shipped'
		        from A where A.suju_id = id
				""";

		this.sqlRunner.execute(sql, paramMap);
	}


}