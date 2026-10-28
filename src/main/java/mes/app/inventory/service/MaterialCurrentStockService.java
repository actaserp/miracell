package mes.app.inventory.service;

import java.util.List;
import java.util.Map;

import org.apache.groovy.parser.antlr4.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

@Service
public class MaterialCurrentStockService {
	@Autowired
	SqlRunner sqlRunner;

	// 재고 현황 조회 
	/**
	 * @param factory_id 공장 필터. null 이면 전체 공장.
	 */
	public List<Map<String, Object>> getMaterialCurrentStockList(String mat_type, Integer mat_grp_pk, String mat_name, Integer store_house_id, Integer factory_id, String spjangcd) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("mat_type", mat_type);
		paramMap.addValue("mat_grp_pk", mat_grp_pk);
		paramMap.addValue("mat_name", mat_name);
		paramMap.addValue("store_house_id", store_house_id);
		paramMap.addValue("factory_id", factory_id);
		paramMap.addValue("spjangcd", spjangcd);

		String sql = """
			select m.id, fn_code_name('mat_type', mg."MaterialType") as mat_type_name
            , mg."Name" as mat_grp_name, m."Code" as mat_code, m."Name" as mat_name
            , m."UnitPrice" as unit_price 
            , u."Name" as unit_name
            , m."CurrentStock" * m."UnitPrice" as stock_price
            , case when m."PackingUnitQty" >0 then round((m."CurrentStock" / m."PackingUnitQty")::numeric, 0)
                else null end as box_qty
            , m."AvailableStock" as avail_stock
            , m."ReservationStock" as reserve_stock
            , m."CurrentStock" as cur_stock
            , sh."Name" as house_name, mh."CurrentStock" as house_stock
            , count(*) over (partition by m.id) as house_count
            , count(ml.id) as lot_count
            , coalesce(m."SafetyStock",0) as safety_stock
            , m."LotUseYN" as lot_use_yn
            /* ★ 국가별 재고 — 1공장 포장 때 국가마다 따로 생긴 완제품 로트(pack_alloc)로 센다.
                 포장 국가 배분이 한 번도 없는 품목(원자재·2공장 등)은 NULL → 빈칸.
                 국가 없이 들어온 예전 재고는 「미지정」으로 묶는다. */
            , case when exists (select 1 from mat_lot cl2
                                  join pack_alloc ca2 on ca2."MatLot_id" = cl2.id
                                 where cl2."Material_id" = m.id)
                   then (select string_agg(x.c || ' ' || x.q, ' · ' order by x.c = '미지정', x.c)
                           from (select coalesce(nullif(ca."CountryName",''), ca."CountryCode", '미지정') as c
                                      , round(sum(cl."CurrentStock")::numeric, 0)::text as q
                                   from mat_lot cl
                                   left join pack_alloc ca on ca."MatLot_id" = cl.id
                                                          and coalesce(ca._status,'a') = 'a'
                                                          and ca."CountryCode" <> '-'
                                  where cl."Material_id" = m.id
                                    and coalesce(cl."CurrentStock",0) > 0
                                  group by 1) x)
              end as country_stock
            from material m 
            inner join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            left join mat_in_house mh on mh."Material_id" = m.id
            left join store_house sh on sh.id = mh."StoreHouse_id"
            left join mat_lot ml on ml."StoreHouse_id"  = sh.id and m.id = ml."Material_id" and ml."CurrentStock" > 0
            where 1 = 1
            and m."Useyn" = '0'
            and m."spjangcd" =:spjangcd
			""";
		if (StringUtils.isEmpty(mat_type)==false) sql +=" and mg.\"MaterialType\" = :mat_type ";
		if (mat_grp_pk != null) sql +=" and mg.\"id\" = :mat_grp_pk ";
		if (StringUtils.isEmpty(mat_name)==false) sql +=" and (m.\"Name\" like concat('%%',:mat_name,'%%') or m.\"Code\" like concat('%%',:mat_name,'%%') ) ";
		if (store_house_id != null) sql +=" and mh.\"StoreHouse_id\" =:store_house_id ";
		if (factory_id != null) sql +=" and m.\"Factory_id\" = :factory_id ";

		sql += " group by m.id,mg.\"MaterialType\" ,mg.\"Name\" ,u.\"Name\" ,sh.\"Name\" , mh.\"CurrentStock\", m.\"SafetyStock\", m.\"LotUseYN\"  ";

		sql += " order by mg.\"MaterialType\", mg.\"Name\", m.\"Code\", m.\"Name\", m.id, sh.\"Name\" ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}
}