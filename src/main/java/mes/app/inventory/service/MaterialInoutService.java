package mes.app.inventory.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class MaterialInoutService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getMaterialInout(String srchStartDt, String srchEndDt, String housePk,
																										String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    --left join mat_order mo on mi."MaterialOrder_id" = mo.id 
                    --and m.id = mo."Material_id" 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    --and sh."HouseType" = 'material'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	/**
	 * @param factoryId 공장 필터. 빈 값/null 이면 전체 공장.
	 *                  수불에는 공장이 없어 품목(material)의 공장으로 건다.
	 */
	public List<Map<String, Object>> getMaterialInoutReceipt(String srchStartDt, String srchEndDt, String housePk,
																													 String matType, String matGrpPk, String keyword,
																													 String factoryId, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    --left join mat_order mo on mi."MaterialOrder_id" = mo.id 
                    --and m.id = mo."Material_id" 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    AND mi."InOut" IN ('in', 'return')
                    --and sh."HouseType" = 'material'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if (StringUtils.isEmpty(factoryId)==false) {
			sql +=" and m.\"Factory_id\" = cast(:factoryId as Integer) ";
			param.addValue("factoryId", factoryId);
		}

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	/**
	 * @param factoryId 공장 필터. 빈 값/null 이면 전체 공장.
	 *                  수불에는 공장이 없어 품목(material)의 공장으로 건다.
	 */
	public List<Map<String, Object>> getMaterialInoutIssue(String srchStartDt, String srchEndDt, String housePk,
																												 String matType, String matGrpPk, String keyword,
																												 String factoryId, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    AND mi."InOut" IN ('out', 'recall')
                    and mi."OutputType" != 'disposal_out'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if (StringUtils.isEmpty(factoryId)==false) {
			sql +=" and m.\"Factory_id\" = cast(:factoryId as Integer) ";
			param.addValue("factoryId", factoryId);
		}

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	/**
	 * @param factoryId 공장 필터. 빈 값/null 이면 전체 공장.
	 *                  수불에는 공장이 없어 품목(material)의 공장으로 건다.
	 */
	public List<Map<String, Object>> getMaterialInoutDisposal(String srchStartDt, String srchEndDt, String housePk,
																														String matType, String matGrpPk, String keyword,
																														String factoryId, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    and mi."OutputType" = 'disposal_out'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if (StringUtils.isEmpty(factoryId)==false) {
			sql +=" and m.\"Factory_id\" = cast(:factoryId as Integer) ";
			param.addValue("factoryId", factoryId);
		}

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> getMaterialInoutDetail(Integer mio_pk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mio_pk", mio_pk);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."InOut" as "inoutSelect"
					, mg."Name" as "cboMaterialGroupName"
					, mg."id" as "cboMaterialGroup"
					, COALESCE(NULLIF(mi."InputType", ''), NULLIF(mi."OutputType", '')) AS "InoutType"
					, to_char(mi."InoutDate", 'yyyy-mm-dd') || 'T' || to_char(mi."InoutTime", 'hh24:mi') as "inoutDate"
					,COALESCE(
						   NULLIF(mi."InputQty", 0),
						   NULLIF(mi."OutputQty", 0),
						   NULLIF(mi."PotentialInputQty", 0),
						   0
						 ) AS "InoutQty"
					, mg."MaterialType" as "cboMaterialType"
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "Material_code"
                    , m."Name" as "Material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as "cboMaterialTypeName"
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    , mi."Company_id" as "cboCompany"
                    , c."Name" as "CompanyName"
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    left join company c on c.id= mi."Company_id"
                    where 1 = 1
                    and m."Useyn" = '0'
					and mi.id = :mio_pk
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> mioLotList(String mioId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
            select 
            mi.id as mio_id
            , ml.id as ml_id
            , ml."LotNumber" 
            , m."Name" as "MaterialName"
            , m."Code" as "MaterialCode" 
            , mg."Name" as "MaterialGroupName" 
            , m."MaterialGroup_id" 
            , m."Unit_id" 
            , m."ValidDays" 
            , u."Name" as "UnitName"
            , ml."InputQty"
            , m."Thickness"
            , m."Width"
            , m."Length"
            , to_char(ml."InputDateTime",'yyyy-MM-dd hh24:mi:ss') as "InputDateTime"
            , to_char(ml."EffectiveDate",'yyyy-MM-dd') as "EffectiveDate"
            , ml."Description"
            , ml."StoreHouse_id" as store_house_id
            , ml."MakerLotNo"
            , ml."CurrentStock"
            /* ★ 이 로트가 «쓰였는가».
                 화면(rp_input)이 로트 재발번을 막을 때 이 값을 본다.
                 예전에는 CurrentStock 과 InputQty 를 견주어 «추정» 했는데,
                 부분 소비 뒤 되돌린 경우처럼 두 값이 같아도 이력이 남는 일이 있어
                 mat_lot_cons 를 직접 세는 편이 정확하다. */
            , coalesce(mlc.cons_cnt, 0) as cons_cnt
            , coalesce(mlc.used_qty, 0) as used_qty
            from mat_lot ml  
                left join lateral (
                    select count(*) as cons_cnt, coalesce(sum(c."OutputQty"),0) as used_qty
                      from mat_lot_cons c
                     where c."MaterialLot_id" = ml.id
                ) mlc on true
                left join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id" 
                left join unit u on u.id = m."Unit_id" 
                left join mat_inout mi on ml."SourceDataPk" = mi.id and ml."SourceTableName" ='mat_inout'
            where mi.id = cast(:mioId as Integer) 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);
		return items;
	}

	/**
	 * 이 입고건의 로트 중 «이미 쓰인» 것들. 재발번 전에 확인한다.
	 *
	 * ★ 소비 이력(mat_lot_cons)이 있으면 무조건 사용으로 본다.
	 *   재고가 그대로여도(부분 소비 후 되돌린 경우) 이력은 남아 있고,
	 *   그 로트를 지우면 이력이 매달릴 곳을 잃는다.
	 * ★ 다른 곳에서 이 로트를 산출 근거로 물고 있는 경우도 함께 본다 —
	 *   포장·생산이 이 로트를 투입해 만든 로트가 있으면 손대면 안 된다.
	 */
	public List<Map<String, Object>> findUsedLotsByMio(Integer mioId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);
		return this.sqlRunner.getRows("""
            select ml.id as ml_id, ml."LotNumber"
                 , coalesce(c.cnt,0) as cons_cnt
                 , coalesce(ml."InputQty",0) - coalesce(ml."CurrentStock",0) as diff_qty
              from mat_lot ml
              left join lateral (
                  select count(*) as cnt from mat_lot_cons mlc
                   where mlc."MaterialLot_id" = ml.id
              ) c on true
             where ml."SourceTableName" = 'mat_inout'
               and ml."SourceDataPk"    = :mioId
               and ( coalesce(c.cnt,0) > 0
                     or coalesce(ml."InputQty",0) <> coalesce(ml."CurrentStock",0) )
             order by ml.id
            """, param);
	}

	/**
	 * 이 입고건이 만든 로트를 지운다. 재발번 직전에만 부른다.
	 *
	 * ★ findUsedLotsByMio 로 «쓰인 것이 없음» 을 확인한 뒤에 부를 것.
	 *   그 검사 없이 부르면 소비 이력이 있는 로트를 지워 FK 가 깨진다.
	 * ★ mat_inout(입고) 행은 건드리지 않는다. 입고 자체는 그대로 두고
	 *   그 아래 로트만 다시 나누는 것이 이 기능의 목적이다.
	 */
	public int deleteLotsByMio(Integer mioId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);
		return this.sqlRunner.execute("""
            delete from mat_lot
             where "SourceTableName" = 'mat_inout'
               and "SourceDataPk"    = :mioId
               and not exists (select 1 from mat_lot_cons mlc
                                where mlc."MaterialLot_id" = mat_lot.id)
            """, param);
	}

	public List<Map<String, Object>> mioTestList(Integer mioId, Integer testResultId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);
		param.addValue("testResultId", testResultId);

		String sql = """
				select ti.id, up."Name" as "CheckName", ti."ResultType" as "resultType", to_char(tir."TestDateTime", 'YYYY-MM-DD') as "testDate"
				, tir."JudgeCode", tir."CharResult" , ti."Name" as name ,tir."Char1" as result1
				, tr.id as "testResultId", tr."TestMaster_id" as "testMasterId"
				from test_item_result tir
				inner join test_result tr on tr.id = tir."TestResult_id"
				inner join test_item ti on tir."TestItem_id"  = ti.id 
				inner join user_profile up on tir."_creater_id"  = up."User_id" 
				where tr."SourceTableName" = 'mat_inout' and tr."SourceDataPk" = :mioId
				and tr.id= :testResultId
				order by ti.id
				""";



		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public Integer getTestMasterByItem(Integer mioId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
                    SELECT tmm."TestMaster_id" AS testMasterId
                            FROM mat_inout mi
                            INNER JOIN test_mast_mat tmm ON mi."Material_id" = tmm."Material_id"
                            WHERE mi.id = :mioId
                            LIMIT 1
                """;

		List<Map<String, Object>> result = this.sqlRunner.getRows(sql, param);
		return result.isEmpty() ? null : (Integer) result.get(0).get("testMasterId");
	}

	public List<Map<String, Object>> prodTestListByTestMaster(Integer testMasterId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("testMasterId", testMasterId);

		String sql = """
                    SELECT tm.id AS testMasterId, ti.id, ti."Name" AS name, ti."ResultType" AS "resultType",
                           tim."SpecText" AS "specText", '' AS result1
                    FROM test_item_mast tim
                    INNER JOIN test_mast tm ON tim."TestMaster_id" = tm.id
                    INNER JOIN test_item ti ON tim."TestItem_id" = ti.id
                    WHERE tm.id = :testMasterId
                """;

		return this.sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> mioTestDefaultList() {

		String sql = """
				select ti.id,ti."Name" as name, ti."ResultType" as "resultType", '' as result1
				from test_item ti
				inner join test_method tm on ti."TestMethod_id"  = tm.id 
				where tm."Code"  = 'inout_test'
				order by ti.id
			    """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, null);

		return items;
	}

	/**
	 * 입고검사 내역 목록 (조회 화면 rp_input_test_list 용).
	 *
	 * ★ 「미검사」는 판정을 «안 한» 가입고 건이다. 부적합이 아니다.
	 *   검사결과가 있는 건만 뽑으면 «검사해야 하는데 안 한 건» 이 화면에서
	 *   사라진다. 그래서 가입고로 남아 있는 건도 같이 내린다.
	 *
	 * ★ 판정/사진 필터는 화면이 «받아온 것 안에서» 건다. 서버를 다시 부르면
	 *   요약(총 N건 중 부적합 M건)의 모집단이 필터마다 흔들린다.
	 *
	 * ★ 검사자 이름은 user_profile."Name" 에 있다 — mioTestList 와 같은 규칙이다.
	 *   auth_user 에는 계정만 있고 한글 이름이 없다.
	 *   («user» 라는 테이블은 이 DB 에 없다. 그렇게 적었다가 조회가 통째로 깨졌다)
	 *   mioTestList 는 inner join 이지만 여기서는 left join 이다 —
	 *   프로필이 없는 계정 때문에 «검사 내역 자체가 사라지면» 안 된다.
	 *
	 * ★ (:x IS NULL OR col = :x) 는 첫 자리에 타입 단서가 없어
	 *   「매개변수의 자료형을 알 수 없습니다」가 난다. CAST 를 붙인다.
	 *
	 * @return 오류 시 null (SqlRunner 규약). 호출부가 «자료 없음» 과 구분해야 한다.
	 */
	public List<Map<String, Object>> getTestResultList(String srchStartDt, String srchEndDt,
																										 String housePk, String keyword, String factoryId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt",   srchEndDt);
		param.addValue("housePk",     housePk);
		param.addValue("factoryId",   factoryId);
		param.addValue("keyword",     keyword);

		/* ★ to_char(..., 'HH24:MI') 의 콜론은 «작은따옴표 안» 이라
		     명명 파라미터로 잡히지 않는다. 따옴표를 벗기지 말 것. */
		String sql = """
				select mi.id                                     as mio_pk
				     , m."Code"                                  as material_code
				     , m."Name"                                  as material_name
				     , u2."Name"                                 as unit_name
				     , sh."Name"                                 as store_house_name
				     -- ★ 로트번호는 두 곳에 있을 수 있다.
				     --   · mat_inout."LotNumber"  — 로트를 입고 건에 직접 적는 경로
				     --   · mat_lot                — 스캐너 입고(lot_save_by_po)가 만드는 로트.
				     --                              이 경로는 mat_inout 쪽을 안 채운다.
				     --   앞쪽만 읽어서, 스캐너로 들어온 건은 로트가 «있는데» 화면에서만
				     --   비어 보였다. 로트가 여럿이면 먼저 만들어진 것을 대표로 쓴다.
				     , coalesce(nullif(mi."LotNumber", ''), ml.lot_no) as lot_number
				     , ml.maker_lot_no                           as maker_lot_no
				     , to_char(mi."InoutDate", 'yyyy-mm-dd')     as inout_date
				     , to_char(mi."InoutTime", 'hh24:mi')        as inout_time
				     , coalesce(mi."InputQty", 0)                as input_qty
				     , coalesce(mi."PotentialInputQty", 0)       as potential_qty
				     , fn_code_name('inout_state', mi."State")   as inout_state
				     , tr.id                                     as test_result_id
				     , to_char(tr."TestDateTime", 'yyyy-mm-dd')  as test_date
				     -- ★ 종합판정은 test_result 가 «제자리» 다. 항목이 0건인 검사는
				     --    test_item_result 만 보면 통째로 사라진다.
				     --    옛 데이터는 항목 행에만 있으므로 coalesce 로 함께 받는다.
				     , coalesce(tr."JudgeCode",  tir."JudgeCode")  as judge_code
				     , coalesce(tr."TestRemark", tir."CharResult") as remark
				     , coalesce(ts.nm, cu.nm)                    as check_name
				     , coalesce(pf.cnt, 0)                       as photo_cnt
				  from mat_inout mi
				  inner join material m    on m.id  = mi."Material_id"
				  inner join store_house sh on sh.id = mi."StoreHouse_id"
				  left join unit u2        on u2.id = m."Unit_id"
				  -- 한 입고 건에 검사결과는 하나다. 여럿이면 최신 것만 본다.
				  left join lateral (
				       select t.* from test_result t
				        where t."SourceTableName" = 'mat_inout'
				          and t."SourceDataPk"    = mi.id
				        order by t.id desc limit 1) tr on true
				  -- 종합판정·비고는 항목마다 같은 값이 들어간다. 한 줄만 집는다.
				  left join lateral (
				       select ti."JudgeCode", ti."CharResult"
				         from test_item_result ti
				        where ti."TestResult_id" = tr.id
				        order by ti.id limit 1) tir on true
				  left join lateral (
				       select count(*) cnt from mio_test_file f
				        where f."MatInout_id" = mi.id and f."_status" = 'a') pf on true
				  -- 스캐너 입고가 만든 로트. 입고 건당 보통 1건이라 가장 이른 것을 쓴다.
				  left join lateral (
				       select l."LotNumber" as lot_no, l."MakerLotNo" as maker_lot_no
				         from mat_lot l
				        where l."SourceTableName" = 'mat_inout'
				          and l."SourceDataPk"    = mi.id
				        order by l.id limit 1) ml on true
				  -- 검사자 : user_profile."Name" 이 표시명, 없으면 auth_user.username
				  left join lateral (
				       select coalesce(up."Name", au.username) as nm
				         from auth_user au
				         left join user_profile up on up."User_id" = au.id
				        where au.id = tr."Tester_id") ts on true
				  left join lateral (
				       select coalesce(up."Name", au.username) as nm
				         from auth_user au
				         left join user_profile up on up."User_id" = au.id
				        where au.id = tr."_creater_id") cu on true
				 where mi."InOut" = 'in'
				   and m."Useyn" = '0'
				   and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
				   -- 검사 대상만: 검사결과가 있거나, 아직 가입고로 남아 있는 것
				   and (tr.id is not null or coalesce(mi."PotentialInputQty", 0) > 0)
				""";

		/* 나머지 조건은 «값이 있을 때만» 붙인다.
		   getMaterialInoutReceipt 와 같은 방식이다 — 한쪽만 CAST 방식으로 쓰면
		   빈 값 처리 규칙이 두 벌이 된다. */
		if (StringUtils.isEmpty(housePk)   == false) sql += " and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(factoryId) == false) sql += " and m.\"Factory_id\" = cast(:factoryId as Integer) ";
		if (StringUtils.isEmpty(keyword)   == false) {
			sql += " and (upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') "
							 + "   or upper(m.\"Code\") like concat('%%',upper(:keyword),'%%')) ";
		}

		sql += " order by coalesce(tr.\"TestDateTime\", mi.\"InoutDate\") desc, mi.id desc ";

		return this.sqlRunner.getRows(sql, param);
	}

	public Map<String, Object> getEffectDate(Integer mioId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
				select (case when mi."EffectiveDate" = null then null else to_char(mi."EffectiveDate", 'YYYY-MM-DD') end)  as "EffectiveDate"
				from mat_inout mi 
				inner join material m on m.id = mi."Material_id"
				where mi.id = :mioId
				""";

		Map<String,Object> items = this.sqlRunner.getRow(sql, param);

		return items;
	}

	public List<Map<String, Object>> getBaljuList(Timestamp start, Timestamp end, String spjangcd) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);

		String sql = """
        select b.id
          , b."JumunNumber"
          , b."Material_id" as "Material_id"
          , mg."Name" as "MaterialGroupName"
          , mg.id as "MaterialGroup_id"
          , fn_code_name('mat_type', mg."MaterialType") as "MaterialTypeName"
          , m.id as "Material_id"
          , m."Code" as product_code
          , m."Name" as product_name
          , u."Name" as unit
          , b."Standard" as standard
          , b."SujuQty" as "SujuQty"
          , to_char(b."JumunDate", 'yyyy-mm-dd') as "JumunDate"
          , to_char(b."DueDate", 'yyyy-mm-dd') as "DueDate"
          , b."CompanyName"
          , b."Company_id"
          , b."SujuType"
          , fn_code_name('Balju_type', b."SujuType") as "BaljuTypeName"
          , to_char(b."ProductionPlanDate", 'yyyy-mm-dd') as production_plan_date
          , to_char(b."ShipmentPlanDate", 'yyyy-mm-dd') as shiment_plan_date
          , b."Description"
          , b."AvailableStock" as "AvailableStock"
          , b."ReservationStock" as "ReservationStock"
          , COALESCE(mi."SujuQty2", 0) AS "SujuQty2"
          , COALESCE(mi."PendingQty", 0) AS "PendingQty"
          , fn_code_name('balju_state', b."State") as "StateName"
          , fn_code_name('shipment_state', b."ShipmentState") as "ShipmentStateName"
          , b."State"
          , to_char(b."_created", 'yyyy-mm-dd') as create_date
          , case b."PlanTableName" when 'prod_week_term' then '주간계획' when 'bundle_head' then '임의계획' else b."PlanTableName" end as plan_state
          from balju b
          inner join material m on m.id = b."Material_id"
          inner join mat_grp mg on mg.id = m."MaterialGroup_id"
          left join unit u on m."Unit_id" = u.id
          left join company c on c.id= b."Company_id"
          LEFT JOIN (
			   /* ★ _status 필터를 WHERE 가 아니라 CASE 안에 둔다.
			      WHERE 에 두면 가입고 행(_status='t')이 통째로 빠져 셀 수가 없다.
			      SujuQty2  = 확정 입고 (검사 불필요 품목 + 적합 판정 완료분)
			      PendingQty= 가입고 대기 (수입검사 대기중, State='waiting')
			      ★ State='waiting' 조건 필수.
			        부적합 판정은 State 만 'confirmed' 로 바꾸고 _status='t',
			        PotentialInputQty 를 그대로 남긴다. 이 조건이 없으면
			        부적합 난 발주 라인이 영영 재입고 불가가 된다. */
			   SELECT
				   "SourceDataPk",
				   SUM(CASE WHEN COALESCE("_status", 'a') = 'a'
							THEN COALESCE("InputQty", 0) ELSE 0 END) AS "SujuQty2",
				   SUM(CASE WHEN COALESCE("_status", 'a') = 't'
							 AND "State" = 'waiting'
							THEN COALESCE("PotentialInputQty", 0) ELSE 0 END) AS "PendingQty"
			   FROM mat_inout
			   WHERE "SourceTableName" = 'balju'
				 AND "InOut" = 'in'
			   GROUP BY "SourceDataPk"
		   ) mi ON mi."SourceDataPk" = b.id
          where 1 = 1
          and b."JumunDate" between :start and :end 
          AND COALESCE(mi."SujuQty2", 0) + COALESCE(mi."PendingQty", 0) < b."SujuQty"
          and b.spjangcd = :spjangcd
          and "State" != 'force_completion'
			order by b."JumunDate" desc,  m."Name"
			""";

//    log.info("발주 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

		return itmes;
	}

	public List<Map<String, Object>> getBaljuInList(Timestamp start, Timestamp end, String spjangcd, Integer choComp, String keyword) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("choComp", choComp);
		dicParam.addValue("keyword", keyword);

		String sql = """
        select b.id
          , b."JumunNumber"
          , b."Material_id" as "Material_id"
          , mg."Name" as "MaterialGroupName"
          , mg.id as "MaterialGroup_id"
          , fn_code_name('mat_type', mg."MaterialType") as "MaterialTypeName"
          , m.id as "Material_id"
          , m."Code" as product_code
          , m."Name" as product_name
          , u."Name" as unit
          , b."SujuQty" as "SujuQty"
          , to_char(b."JumunDate", 'yyyy-mm-dd') as "JumunDate"
          , to_char(b."DueDate", 'yyyy-mm-dd') as "DueDate"
          , b."CompanyName"
          , b."Company_id"
          , b."SujuType"
          , fn_code_name('Balju_type', b."SujuType") as "BaljuTypeName"
          , to_char(b."ProductionPlanDate", 'yyyy-mm-dd') as production_plan_date
          , to_char(b."ShipmentPlanDate", 'yyyy-mm-dd') as shiment_plan_date
          , b."Description"
          , b."AvailableStock" as "AvailableStock"
          , b."ReservationStock" as "ReservationStock"
          , COALESCE(mi."SujuQty2", 0) AS "SujuQty2"
          , COALESCE(mi_return."ReturnQty", 0) AS "ReturnQty"
          , fn_code_name('balju_state', b."State") as "StateName"
          , fn_code_name('shipment_state', b."ShipmentState") as "ShipmentStateName"
          , b."State"
          , to_char(b."_created", 'yyyy-mm-dd') as create_date
          , case b."PlanTableName" when 'prod_week_term' then '주간계획' when 'bundle_head' then '임의계획' else b."PlanTableName" end as plan_state
          from balju b
          inner join material m on m.id = b."Material_id"
          inner join mat_grp mg on mg.id = m."MaterialGroup_id"
          left join unit u on m."Unit_id" = u.id
          left join company c on c.id= b."Company_id"
          LEFT JOIN (
			   SELECT
				   "SourceDataPk",
				   SUM("InputQty") AS "SujuQty2"
			   FROM mat_inout
			   WHERE "SourceTableName" = 'balju'
				 AND COALESCE("_status", 'a') = 'a'
				 AND "InOut" = 'in'
			   GROUP BY "SourceDataPk"
		   ) mi ON mi."SourceDataPk" = b.id
		  LEFT JOIN (
			 SELECT
				 "SourceDataPk",
				 SUM("InputQty") AS "ReturnQty"
			 FROM mat_inout
			 WHERE "SourceTableName" = 'balju'
			   AND COALESCE("_status", 'a') = 'a'
			   AND "InOut" = 'return'
			 GROUP BY "SourceDataPk"
		 ) mi_return ON mi_return."SourceDataPk" = b.id
          where 1 = 1
          and b."JumunDate" between :start and :end 
          AND COALESCE(mi."SujuQty2", 0) > 0
          and b.spjangcd = :spjangcd
         """;

		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if(choComp != null) {
			sql += """ 
					and b."Company_id" = :choComp
					""";
		}

		sql += " order by b.\"JumunDate\" desc,  m.\"Name\" ";

//    log.info("발주 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

		return itmes;
	}

	// 발주번호(JumunNumber)로 해당 발주의 미입고 잔량 > 0 인 라인만 조회
	public List<Map<String, Object>> getBaljuLinesByJumunNumber(String jumunNumber, String spjangcd) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jumunNumber", jumunNumber);
		dicParam.addValue("spjangcd", spjangcd);

		/* ★ 컬럼명 주의 : balju 에는 "StoreHouse_id" 가 없다.
		     납품창고는 "ShipmentState" 에 varchar 로 들어간다(발주 목록 화면도
		     store_house sh ON sh.id::varchar = b."ShipmentState" 로 조인한다).
		     없는 컬럼을 읽으면 SqlRunner.getRows 가 예외가 아니라 «null» 을 돌려주고,
		     화면은 그걸 「미입고 품목이 없습니다」로 표시한다 — 조용히 죽는 자리다.

		   ★ 별칭은 화면(addPoLines)이 읽는 이름과 맞춘다.
		     balju_id / baljuQty / receivedQty / remainQty 를 바꾸면 그리드가 빈다. */
		String sql = """
        select b.id                                as balju_id
          , b."BaljuHead_id"                       as bh_id
          , b."JumunNumber"
          , m.id                                   as "Material_id"
          , m."Code"                               as product_code
          , m."Name"                               as product_name
          , u."Name"                               as unit
          , b."Standard"                           as standard
          , b."CompanyName"
          , b."Company_id"
          -- 납품창고. 숫자 문자열일 때만 캐스팅한다(빈값·쓰레기값에서 터지지 않게).
          -- NULL 이면 화면이 헤더의 입고창고로 대체한다.
          , CASE WHEN b."ShipmentState" ~ '^[0-9]+$'
                 THEN b."ShipmentState"::integer END  as "StoreHouse_id"
          , to_char(b."DueDate", 'yyyy-mm-dd')     as "DueDate"
          , b."SujuQty"                            as "baljuQty"
          , COALESCE(mi."SujuQty2", 0)             as "receivedQty"
          , COALESCE(mi."PendingQty", 0)           as "pendingQty"
          , GREATEST(b."SujuQty" - COALESCE(mi."SujuQty2", 0)
                                 - COALESCE(mi."PendingQty", 0), 0) as "remainQty"
          , b."State"
          from balju b
          inner join material m on m.id = b."Material_id"
          -- mat_grp 는 LEFT. INNER 로 두면 품목그룹 없는 자재가 조용히 사라진다.
          left  join mat_grp mg on mg.id = m."MaterialGroup_id"
          left  join unit u on u.id = m."Unit_id"
          LEFT JOIN (
               -- 기입고 = 입고 - 반품. 반품 행은 InOut='return' 이면서
               -- 수량이 InputQty 에 들어간다(이 화면의 save_balju_return).
               -- 'in' 만 더하면 100 받고 100 반품한 라인이 영원히 전량입고로 남는다.
               -- ★ 가입고(_status='t')는 InputQty 가 비어 있어 위 합계에 안 잡힌다.
               --   PendingQty 로 따로 세서 remainQty 에서 함께 뺀다.
               --   안 그러면 스캔으로 같은 발주 라인을 또 입고해 이중입고가 된다.
               SELECT "SourceDataPk"
                    , SUM(CASE WHEN COALESCE("_status", 'a') <> 'a' THEN 0
                               WHEN "InOut" = 'in'     THEN COALESCE("InputQty", 0)
                               WHEN "InOut" = 'return' THEN -COALESCE("InputQty", 0)
                               ELSE 0 END) AS "SujuQty2"
                    , SUM(CASE WHEN COALESCE("_status", 'a') = 't'
                                AND "State" = 'waiting'
                                AND "InOut" = 'in'
                               THEN COALESCE("PotentialInputQty", 0) ELSE 0 END) AS "PendingQty"
               FROM mat_inout
               WHERE "SourceTableName" = 'balju'
               GROUP BY "SourceDataPk"
          ) mi ON mi."SourceDataPk" = b.id
          where b."JumunNumber" = :jumunNumber
          and b.spjangcd = :spjangcd
          -- ★ 「제외」 방식으로 판정한다. draft 는 "아직 아무것도 안 받았다" 는 뜻이라
          --   가장 받아야 할 상태다. State 가 NULL 인 행도 살려야 하므로 COALESCE.
          --   (!= 'force_completion' 은 NULL 에서 NULL 이 되어 행이 사라진다)
          and COALESCE(b."State", '') NOT IN ('canceled', 'force_completion')
          -- _status 는 삭제 플래그가 아니라 생성 경로('manual')다. 걸지 않는다.
          AND GREATEST(b."SujuQty" - COALESCE(mi."SujuQty2", 0)
                                   - COALESCE(mi."PendingQty", 0), 0) > 0
          order by m."Name"
        """;

		return this.sqlRunner.getRows(sql, dicParam);
	}

	/**
	 * GS1 / EAN — GTIN-14 로 품목 역조회.
	 *
	 * EAN-13 은 프론트(parseEan13)에서 앞에 '0' 을 붙여 GTIN-14 로 정규화해 넘어온다.
	 * 그래도 마스터에 13자리로 등록돼 있을 수 있어 양쪽을 다 본다.
	 */
	public Map<String, Object> findMaterialByGtin(String gtin14, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("gtin14", gtin14);
		p.addValue("gtin13", (gtin14 != null && gtin14.length() == 14 && gtin14.startsWith("0"))
													 ? gtin14.substring(1) : gtin14);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		return this.sqlRunner.getRow("""
            SELECT m.id                 AS material_id
                 , m."Code"             AS material_code
                 , m."Name"             AS material_name
                 , m."ValidDays"        AS valid_days
                 , COALESCE(m."LotUseYN",'N') AS lot_use_yn
                 , mb."GTIN"            AS gtin
                 , mb."PackLevel"       AS pack_level
                 , mb."PackQty"         AS pack_qty
                 , mb."Company_id"      AS company_id
              FROM material_barcode mb
              JOIN material m ON m.id = mb."Material_id"
             WHERE mb."GTIN" IN (CAST(:gtin14 AS varchar), CAST(:gtin13 AS varchar))
               AND COALESCE(mb._status,'a') = 'a'
               AND (CAST(:spjangcd AS varchar) IS NULL OR mb.spjangcd = CAST(:spjangcd AS varchar))
             LIMIT 1
            """, p);
	}

	/** HIBC / ISBT — UDI-DI 문자열로 품목 역조회 */
	public Map<String, Object> findMaterialByUdiDi(String udiDi, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("udiDi", udiDi);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		return this.sqlRunner.getRow("""
            SELECT m.id                 AS material_id
                 , m."Code"             AS material_code
                 , m."Name"             AS material_name
                 , m."ValidDays"        AS valid_days
                 , COALESCE(m."LotUseYN",'N') AS lot_use_yn
                 , mb."UdiDi"           AS udi_di
                 , mb."BarcodeType"     AS barcode_type
                 , mb."PackLevel"       AS pack_level
                 , mb."PackQty"         AS pack_qty
                 , mb."Company_id"      AS company_id
              FROM material_barcode mb
              JOIN material m ON m.id = mb."Material_id"
             WHERE mb."UdiDi" = :udiDi
               AND COALESCE(mb._status,'a') = 'a'
               AND (CAST(:spjangcd AS varchar) IS NULL OR mb.spjangcd = CAST(:spjangcd AS varchar))
             LIMIT 1
            """, p);
	}

	/**
	 * 자사 품목코드로 역조회 — 바코드가 없는 자재는 material."Code" 를 그대로 찍는다.
	 *
	 * ★ GS1 파싱을 태우지 않고 원문 그대로 대조한다.
	 *   숫자로 시작하는 코드는 파서가 AI 로 오인해 잘라먹는다.
	 */
	public Map<String, Object> findMaterialByCode(String code, String spjangcd) {
		if (code == null || code.isBlank()) return null;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("code", code.trim());
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		return this.sqlRunner.getRow("""
            SELECT m.id                 AS material_id
                 , m."Code"             AS material_code
                 , m."Name"             AS material_name
                 , m."ValidDays"        AS valid_days
                 , COALESCE(m."LotUseYN",'N') AS lot_use_yn
                 , 'CODE'               AS barcode_type
                 , 'each'               AS pack_level
                 , 1                    AS pack_qty
                 , NULL::integer        AS company_id
              FROM material m
             WHERE m."Code" = CAST(:code AS varchar)
               AND (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
             LIMIT 1
            """, p);
	}

	/** 품목의 현재 등록 바코드 1건 — 등록 화면이 「이미 걸려 있다」를 알리는 데 쓴다 */
	public Map<String, Object> getBarcodeOfMaterial(Integer materialId) {
		if (materialId == null) return null;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("materialId", materialId);
		return this.sqlRunner.getRow("""
            SELECT id, "BarcodeType" AS barcode_type, "GTIN" AS gtin, "UdiDi" AS udi_di
                 , "PackLevel" AS pack_level, "PackQty" AS pack_qty, "Company_id" AS company_id
              FROM material_barcode
             WHERE "Material_id" = :materialId
               AND COALESCE(_status,'a') <> 'd'
             LIMIT 1
            """, p);
	}

	/** 미등록 바코드 자동 학습용 — 스캔한 GTIN 을 품목에 매핑 등록 */
	public int registerBarcode(Integer materialId, String barcodeType, String gtin, String udiDi,
														 Integer companyId, String spjangcd, Integer userId) {
		return registerBarcode(materialId, barcodeType, gtin, udiDi, companyId, spjangcd, userId, null, null);
	}

	/**
	 * 바코드 등록 — 품목당 1건.
	 *
	 * ★ ux_matbc_one_per_material 이 품목당 활성 1건을 강제하므로,
	 *   기존 행이 있으면 먼저 소프트 삭제한다. 안 지우면 유니크 위반으로 죽는다.
	 *   하드 삭제가 아닌 이유는 「예전엔 이 바코드였다」를 남겨야 하기 때문이다.
	 *
	 * ★ packQty 는 「이 바코드 1건 = 품목 몇 개」다.
	 *   매입처가 인박스 바코드를 붙여 보내면 1스캔이 N개가 된다.
	 *   품목에 바코드가 몇 개냐(=1건)와는 다른 축이다.
	 *   CHECK 제약이 each 는 반드시 1이 되도록 막고 있어 실수가 걸린다.
	 */
	public int registerBarcode(Integer materialId, String barcodeType, String gtin, String udiDi,
														 Integer companyId, String spjangcd, Integer userId,
														 String packLevel, java.math.BigDecimal packQty) {
		if (materialId == null)
			throw new IllegalArgumentException("품목을 선택하세요");
		if ((gtin == null || gtin.isBlank()) && (udiDi == null || udiDi.isBlank()))
			throw new IllegalArgumentException("바코드 값이 없습니다");

		String lvl = (packLevel == null || packLevel.isBlank()) ? "each" : packLevel.trim();
		java.math.BigDecimal qty = (packQty == null) ? java.math.BigDecimal.ONE : packQty;
		if ("each".equals(lvl)) qty = java.math.BigDecimal.ONE;   // CHECK 제약과 동일 규칙
		if (qty.signum() <= 0)
			throw new IllegalArgumentException("입수량은 1 이상이어야 합니다");

		MapSqlParameterSource d = new MapSqlParameterSource();
		d.addValue("materialId", materialId);
		d.addValue("userId", userId);
		this.sqlRunner.execute("""
            UPDATE material_barcode
               SET _status = 'd', _modified = now(), _modifier_id = CAST(:userId AS integer)
             WHERE "Material_id" = :materialId
               AND COALESCE(_status,'a') <> 'd'
            """, d);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("packLevel", lvl);
		p.addValue("packQty", qty);
		p.addValue("materialId", materialId);
		p.addValue("type", (barcodeType == null || barcodeType.isBlank()) ? "GS1" : barcodeType);
		p.addValue("gtin", (gtin == null || gtin.isBlank()) ? null : gtin);
		p.addValue("udiDi", (udiDi == null || udiDi.isBlank()) ? null : udiDi);
		p.addValue("companyId", companyId);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd);
		p.addValue("userId", userId);

		return this.sqlRunner.execute("""
            INSERT INTO material_barcode
                   ("Material_id","BarcodeType","GTIN","UdiDi","PackLevel","PackQty",
                    "Company_id","PrimaryYN",_status,_created,_creater_id,spjangcd)
            VALUES (:materialId, :type, CAST(:gtin AS varchar), CAST(:udiDi AS varchar),
                    CAST(:packLevel AS varchar), CAST(:packQty AS numeric),
                    CAST(:companyId AS integer), 'Y',
                    'a', now(), CAST(:userId AS integer), CAST(:spjangcd AS varchar))
            """, p);
	}



}