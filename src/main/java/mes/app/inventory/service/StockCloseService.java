package mes.app.inventory.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

/**
 * 재고 마감.
 *
 * ★ 마감 1건 = mat_inout 1행 (창고 × 품목). 별도 테이블을 만들지 않는다.
 *
 *    InOut            = 'close'
 *    InputQty         = 0        ← ★ 절대 채우지 않는다
 *    OutputQty        = 0        ← ★ matinout_tri 가 이 둘을 합산해
 *                                  mat_in_house / material."CurrentStock" 를 재집계한다.
 *                                  값을 넣으면 「마감했다」는 이유만으로 재고가 움직인다.
 *    LastHouseStock   = 마감 시점 창고재고 (스냅샷)
 *    LastStock        = 마감 시점 전체재고 (스냅샷)
 *    State            = 'closed'
 *    SourceTableName  = 'stock_close'   ← 마감 행 식별자. 삭제 가드에도 쓴다
 *    SourceDataPk     = 마감 회차번호
 *
 * ★ 기초/입고/출고는 저장하지 않는다.
 *    mat_inout 에 그것을 담을 컬럼이 없고(PotentialInputQty 등을 전용하면 다른 화면이
 *    오독한다), 조회 때 직전 마감 스냅샷 + 구간 이력으로 다시 만들 수 있다.
 *
 * ★ 마감재고의 진실은 mat_in_house 다. 입고/출고 합계는 참고값이다 —
 *    mat_inout 의 소비 행은 수량이 비어 있어(생산투입은 mat_lot_cons 가 진실)
 *    합산만으로는 맞지 않는다. 그래서 출고에 mat_lot_cons 를 더해서 근사치를 만들고,
 *    「기초 + 입고 - 출고 ≠ 마감재고」이면 화면에서 표시만 한다. 마감 값을 그것으로
 *    보정하지 않는다.
 */
@Service
public class StockCloseService {

	@Autowired
	SqlRunner sqlRunner;

	/* =================================================================
	 * 1. 마감 내역 목록
	 * ================================================================= */
	public List<Map<String, Object>> getCloseList(String srchStartDt, String srchEndDt,
																								String housePk, String matType,
																								String matGrpPk, String keyword,
																								String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
                select mi.id                                        as mio_pk
                     , mi."SourceDataPk"                            as close_no
                     , to_char(mi."InoutDate", 'yyyy-mm-dd')        as "InoutDate"
                     , to_char(mi."_created", 'yyyy-mm-dd hh24:mi') as created
                     , mi."StoreHouse_id"
                     , sh."Name"                                    as store_house_name
                     , mi."Material_id"
                     , m."Code"                                     as material_code
                     , m."Name"                                     as material_name
                     , fn_code_name('mat_type', mg."MaterialType")  as material_type
                     , u."Name"                                     as unit_name
                     , coalesce(pv.prev_qty, 0)                     as "BeginQty"
                     , coalesce(io.in_qty, 0)                       as "InSumQty"
                     , coalesce(io.out_qty, 0) + coalesce(cs.cons_qty, 0) as "OutSumQty"
                     , coalesce(mi."LastHouseStock", 0)             as "LastHouseStock"
                     , coalesce(mi."LastStock", 0)                  as "LastStock"
                     , coalesce(mi."LastHouseStock",0) - coalesce(pv.prev_qty,0) as "DiffQty"
                     , coalesce(lot.cnt, 0)                         as lot_count
                     , up."Name"                                    as actor_name
                     , mi."Description"
                  from mat_inout mi
                 inner join material    m  on m.id  = mi."Material_id"
                  left join mat_grp     mg on mg.id = m."MaterialGroup_id"
                 inner join store_house sh on sh.id = mi."StoreHouse_id"
                  left join unit        u  on u.id  = m."Unit_id"
                  left join user_profile up on up."User_id" = mi."_creater_id"
                  -- 직전 마감 (같은 창고 × 품목)
                  left join lateral (
                        select p."InoutDate" as prev_date
                             , coalesce(p."LastHouseStock", 0) as prev_qty
                          from mat_inout p
                         where p."SourceTableName" = 'stock_close'
                           and p."Material_id"     = mi."Material_id"
                           and p."StoreHouse_id"   = mi."StoreHouse_id"
                           and p."InoutDate"       < mi."InoutDate"
                         order by p."InoutDate" desc, p.id desc
                         limit 1
                  ) pv on true
                  -- 구간 입출고 (마감 행 자신은 제외)
                  left join lateral (
                        select sum(coalesce(x."InputQty", 0))  as in_qty
                             , sum(coalesce(x."OutputQty", 0)) as out_qty
                          from mat_inout x
                         where x."Material_id"   = mi."Material_id"
                           and x."StoreHouse_id" = mi."StoreHouse_id"
                           and coalesce(x."SourceTableName", '') <> 'stock_close'
                           and coalesce(x._status, 'a') = 'a'
                           and x."InoutDate" >  coalesce(pv.prev_date, date '1900-01-01')
                           and x."InoutDate" <= mi."InoutDate"
                  ) io on true
                  -- 구간 로트 소비 (mat_inout 소비행은 수량이 비어 있다)
                  left join lateral (
                        select sum(coalesce(mlc."OutputQty", 0)) as cons_qty
                          from mat_lot_cons mlc
                         inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                         where ml."Material_id"   = mi."Material_id"
                           and ml."StoreHouse_id" = mi."StoreHouse_id"
                           and cast(mlc."OutputDateTime" as date) >  coalesce(pv.prev_date, date '1900-01-01')
                           and cast(mlc."OutputDateTime" as date) <= mi."InoutDate"
                  ) cs on true
                  left join lateral (
                        select count(*) as cnt
                          from mat_lot ml
                         where ml."Material_id"   = mi."Material_id"
                           and ml."StoreHouse_id" = mi."StoreHouse_id"
                           and coalesce(ml."CurrentStock", 0) <> 0
                  ) lot on true
                 where mi."SourceTableName" = 'stock_close'
                   and m."Useyn" = '0'
                   and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                   and mi.spjangcd = :spjangcd
                """;

		if (!StringUtils.isEmpty(housePk))  sql += " and sh.id = cast(:housePk as Integer) ";
		if (!StringUtils.isEmpty(matType))  sql += " and mg.\"MaterialType\" = :matType ";
		if (!StringUtils.isEmpty(matGrpPk)) sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (!StringUtils.isEmpty(keyword))  sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by mi.\"InoutDate\" desc, sh.\"Name\", m.\"Code\" ";

		return this.sqlRunner.getRows(sql, param);
	}

	/* =================================================================
	 * 2. 마감 대상 (미리보기)
	 *    현재고 기준. mat_in_house 가 화면이 보는 집계이고 마감도 그것을 찍는다.
	 * ================================================================= */
	public List<Map<String, Object>> getPreviewList(String closeDate, String housePk,
																									String matType, String matGrpPk,
																									String zeroYN, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("closeDate", closeDate);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("zeroYN", zeroYN);
		param.addValue("spjangcd", spjangcd);

		String sql = """
                select m.id                                        as "Material_id"
                     , sh.id                                       as "StoreHouse_id"
                     , sh."Name"                                   as store_house_name
                     , m."Code"                                    as material_code
                     , m."Name"                                    as material_name
                     , fn_code_name('mat_type', mg."MaterialType") as material_type
                     , u."Name"                                    as unit_name
                     , coalesce(pv.prev_qty, 0)                    as "BeginQty"
                     , to_char(pv.prev_date, 'yyyy-mm-dd')         as prev_close_date
                     , coalesce(io.in_qty, 0)                      as "InSumQty"
                     , coalesce(io.out_qty, 0) + coalesce(cs.cons_qty, 0) as "OutSumQty"
                     , coalesce(mih."CurrentStock", 0)             as "HouseStock"
                     , coalesce(m."CurrentStock", 0)               as "CurrentStock"
                     , coalesce(lot.cnt, 0)                        as lot_count
                     , case when exists (
                            select 1 from mat_inout c
                             where c."SourceTableName" = 'stock_close'
                               and c."Material_id"     = m.id
                               and c."StoreHouse_id"   = sh.id
                               and c."InoutDate"       = cast(:closeDate as date)
                       ) then 'Y' else 'N' end                     as closed_yn
                     -- 이 창고×품목에 더 나중 마감이 이미 있으면 과거 마감을 새로 걸 수 없다
                     , case when exists (
                            select 1 from mat_inout c
                             where c."SourceTableName" = 'stock_close'
                               and c."Material_id"     = m.id
                               and c."StoreHouse_id"   = sh.id
                               and c."InoutDate"       > cast(:closeDate as date)
                       ) then 'Y' else 'N' end                     as later_close_yn
                  from mat_in_house mih
                 inner join material    m  on m.id  = mih."Material_id"
                  left join mat_grp     mg on mg.id = m."MaterialGroup_id"
                 inner join store_house sh on sh.id = mih."StoreHouse_id"
                  left join unit        u  on u.id  = m."Unit_id"
                  left join lateral (
                        select p."InoutDate" as prev_date
                             , coalesce(p."LastHouseStock", 0) as prev_qty
                          from mat_inout p
                         where p."SourceTableName" = 'stock_close'
                           and p."Material_id"     = m.id
                           and p."StoreHouse_id"   = sh.id
                           and p."InoutDate"       < cast(:closeDate as date)
                         order by p."InoutDate" desc, p.id desc
                         limit 1
                  ) pv on true
                  left join lateral (
                        select sum(coalesce(x."InputQty", 0))  as in_qty
                             , sum(coalesce(x."OutputQty", 0)) as out_qty
                          from mat_inout x
                         where x."Material_id"   = m.id
                           and x."StoreHouse_id" = sh.id
                           and coalesce(x."SourceTableName", '') <> 'stock_close'
                           and coalesce(x._status, 'a') = 'a'
                           and x."InoutDate" >  coalesce(pv.prev_date, date '1900-01-01')
                           and x."InoutDate" <= cast(:closeDate as date)
                  ) io on true
                  left join lateral (
                        select sum(coalesce(mlc."OutputQty", 0)) as cons_qty
                          from mat_lot_cons mlc
                         inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                         where ml."Material_id"   = m.id
                           and ml."StoreHouse_id" = sh.id
                           and cast(mlc."OutputDateTime" as date) >  coalesce(pv.prev_date, date '1900-01-01')
                           and cast(mlc."OutputDateTime" as date) <= cast(:closeDate as date)
                  ) cs on true
                  left join lateral (
                        select count(*) as cnt
                          from mat_lot ml
                         where ml."Material_id"   = m.id
                           and ml."StoreHouse_id" = sh.id
                           and coalesce(ml."CurrentStock", 0) <> 0
                  ) lot on true
                 where m."Useyn" = '0'
                   and m.spjangcd = :spjangcd
                """;

		if (!StringUtils.isEmpty(housePk))  sql += " and sh.id = cast(:housePk as Integer) ";
		if (!StringUtils.isEmpty(matType))  sql += " and mg.\"MaterialType\" = :matType ";
		if (!StringUtils.isEmpty(matGrpPk)) sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (!"Y".equals(zeroYN))            sql += " and coalesce(mih.\"CurrentStock\", 0) <> 0 ";

		sql += " order by sh.\"Name\", m.\"Code\" ";

		return this.sqlRunner.getRows(sql, param);
	}

	/* =================================================================
	 * 3. 마감 1건 상세
	 * ================================================================= */
	public Map<String, Object> getCloseDetail(Integer mioPk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioPk", mioPk);

		String sql = """
                select mi.id                                        as mio_pk
                     , mi."SourceDataPk"                            as close_no
                     , to_char(mi."InoutDate", 'yyyy-mm-dd')        as "InoutDate"
                     , to_char(mi."_created", 'yyyy-mm-dd hh24:mi') as created
                     , sh."Name"                                    as store_house_name
                     , m."Code"                                     as material_code
                     , m."Name"                                     as material_name
                     , u."Name"                                     as unit_name
                     , coalesce(pv.prev_qty, 0)                     as "BeginQty"
                     , coalesce(io.in_qty, 0)                       as "InSumQty"
                     , coalesce(io.out_qty, 0) + coalesce(cs.cons_qty, 0) as "OutSumQty"
                     , coalesce(mi."LastHouseStock", 0)             as "LastHouseStock"
                     , coalesce(mi."LastStock", 0)                  as "LastStock"
                     , up."Name"                                    as actor_name
                     , mi."Description"
                  from mat_inout mi
                 inner join material    m  on m.id  = mi."Material_id"
                 inner join store_house sh on sh.id = mi."StoreHouse_id"
                  left join unit        u  on u.id  = m."Unit_id"
                  left join user_profile up on up."User_id" = mi."_creater_id"
                  left join lateral (
                        select p."InoutDate" as prev_date
                             , coalesce(p."LastHouseStock", 0) as prev_qty
                          from mat_inout p
                         where p."SourceTableName" = 'stock_close'
                           and p."Material_id"     = mi."Material_id"
                           and p."StoreHouse_id"   = mi."StoreHouse_id"
                           and p."InoutDate"       < mi."InoutDate"
                         order by p."InoutDate" desc, p.id desc
                         limit 1
                  ) pv on true
                  left join lateral (
                        select sum(coalesce(x."InputQty", 0))  as in_qty
                             , sum(coalesce(x."OutputQty", 0)) as out_qty
                          from mat_inout x
                         where x."Material_id"   = mi."Material_id"
                           and x."StoreHouse_id" = mi."StoreHouse_id"
                           and coalesce(x."SourceTableName", '') <> 'stock_close'
                           and coalesce(x._status, 'a') = 'a'
                           and x."InoutDate" >  coalesce(pv.prev_date, date '1900-01-01')
                           and x."InoutDate" <= mi."InoutDate"
                  ) io on true
                  left join lateral (
                        select sum(coalesce(mlc."OutputQty", 0)) as cons_qty
                          from mat_lot_cons mlc
                         inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                         where ml."Material_id"   = mi."Material_id"
                           and ml."StoreHouse_id" = mi."StoreHouse_id"
                           and cast(mlc."OutputDateTime" as date) >  coalesce(pv.prev_date, date '1900-01-01')
                           and cast(mlc."OutputDateTime" as date) <= mi."InoutDate"
                  ) cs on true
                 where mi.id = :mioPk
                   and mi."SourceTableName" = 'stock_close'
                """;

		return this.sqlRunner.getRow(sql, param);
	}

	/* =================================================================
	 * 4. 마감 회차번호 채번
	 *    SourceDataPk 가 int4 라 날짜문자를 못 담는다. 단순 증가로 간다.
	 * ================================================================= */
	public Integer nextCloseNo() {
		Map<String, Object> row = this.sqlRunner.getRow("""
                select coalesce(max("SourceDataPk"), 0) + 1 as no
                  from mat_inout
                 where "SourceTableName" = 'stock_close'
                """, new MapSqlParameterSource());
		return row == null ? 1 : ((Number) row.get("no")).intValue();
	}

	/* =================================================================
	 * 5. 마감 INSERT
	 *
	 *    ★ 수량을 화면에서 받지 않고 mat_in_house / material 에서 다시 읽는다.
	 *      조회~저장 사이에 생긴 입출고가 스냅샷에서 빠지지 않게 하기 위함.
	 * ================================================================= */
	public int insertClose(Integer matId, Integer houseId, String closeDate,
												 Integer closeNo, String description,
												 Integer userId, String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("houseId", houseId);
		p.addValue("closeDate", closeDate);
		p.addValue("closeNo", closeNo);
		p.addValue("description", description);
		p.addValue("userId", userId);
		p.addValue("spjangcd", spjangcd);

		return this.sqlRunner.execute("""
                insert into mat_inout (
                       _status, _created, _creater_id,
                       "InoutDate", "InoutTime", "InOut",
                       "InputQty", "OutputQty",
                       "State", "SourceTableName", "SourceDataPk",
                       "LastHouseStock", "LastStock",
                       "Material_id", "StoreHouse_id", "Actor_id",
                       "Description", spjangcd)
                select 'a', now(), cast(:userId as integer)
                     , cast(:closeDate as date), cast(now() as time), 'close'
                     , 0, 0
                     , 'closed', 'stock_close', cast(:closeNo as integer)
                     , coalesce(mih."CurrentStock", 0)
                     , coalesce(m."CurrentStock", 0)
                     , m.id, cast(:houseId as integer), cast(:userId as integer)
                     , cast(:description as varchar), cast(:spjangcd as varchar)
                  from material m
                  left join mat_in_house mih
                         on mih."Material_id"   = m.id
                        and mih."StoreHouse_id" = cast(:houseId as integer)
                 where m.id = cast(:matId as integer)
                """, p);
	}

	/** 같은 (마감일 × 창고 × 품목) 기존 마감 제거 — 재마감(덮어쓰기)용 */
	public int deleteSameKey(Integer matId, Integer houseId, String closeDate) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("houseId", houseId);
		p.addValue("closeDate", closeDate);

		return this.sqlRunner.execute("""
                delete from mat_inout
                 where "SourceTableName" = 'stock_close'
                   and "Material_id"     = cast(:matId as integer)
                   and "StoreHouse_id"   = cast(:houseId as integer)
                   and "InoutDate"       = cast(:closeDate as date)
                """, p);
	}

	/** 더 나중 마감이 이미 있는가 — 과거로 소급 마감하는 것을 막는다 */
	public boolean hasLaterClose(Integer matId, Integer houseId, String closeDate) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("houseId", houseId);
		p.addValue("closeDate", closeDate);

		Map<String, Object> row = this.sqlRunner.getRow("""
                select count(*) as cnt
                  from mat_inout
                 where "SourceTableName" = 'stock_close'
                   and "Material_id"     = cast(:matId as integer)
                   and "StoreHouse_id"   = cast(:houseId as integer)
                   and "InoutDate"       > cast(:closeDate as date)
                """, p);
		return row != null && ((Number) row.get("cnt")).intValue() > 0;
	}

	/* =================================================================
	 * 6. 마감 취소
	 *
	 *    ★ 두 겹의 가드
	 *      ① SourceTableName='stock_close' 인 행만 — 실입출고를 지우면 재고가 날아간다
	 *      ② 그 창고×품목의 최신 마감만 — 중간 마감을 지우면 다음 마감의 기초가
	 *        근거를 잃는다
	 *    조건에 안 맞으면 0 행이 지워지고, 컨트롤러가 그 수를 세어 사용자에게 알린다.
	 * ================================================================= */
	public int deleteClose(Integer mioPk) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mioPk", mioPk);

		return this.sqlRunner.execute("""
                delete from mat_inout mi
                 where mi.id = cast(:mioPk as integer)
                   and mi."SourceTableName" = 'stock_close'
                   and not exists (
                       select 1 from mat_inout l
                        where l."SourceTableName" = 'stock_close'
                          and l."Material_id"     = mi."Material_id"
                          and l."StoreHouse_id"   = mi."StoreHouse_id"
                          and l."InoutDate"       > mi."InoutDate"
                   )
                """, p);
	}

	/** 비고만 수정. 수량 스냅샷은 손대지 않는다. */
	public int updateMemo(Integer mioPk, String description, Integer userId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mioPk", mioPk);
		p.addValue("description", description);
		p.addValue("userId", userId);

		return this.sqlRunner.execute("""
                update mat_inout
                   set "Description" = cast(:description as varchar)
                     , _modified     = now()
                     , _modifier_id  = cast(:userId as integer)
                 where id = cast(:mioPk as integer)
                   and "SourceTableName" = 'stock_close'
                """, p);
	}

	/* =================================================================
	 * 7. ★ 다른 화면이 쓸 가드
	 *
	 *    「이 창고×품목이 언제까지 마감됐는가」. 마감일 이전 날짜로 입출고를
	 *    등록하려 하면 막아야 한다. 전역으로 걸면 생산이 멈추므로 창고×품목 단위다.
	 *
	 *    MaterialInoutController.saveMaterialInout 등에서 호출:
	 *
	 *      LocalDate closed = stockCloseService.getClosedDate(matId, houseId);
	 *      if (closed != null && !inoutDate.isAfter(closed)) {
	 *          result.success = false;
	 *          result.message = closed + " 로 마감된 재고입니다. 마감 이후 날짜로 등록하세요.";
	 *          return result;
	 *      }
	 * ================================================================= */
	public java.time.LocalDate getClosedDate(Integer matId, Integer houseId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("houseId", houseId);

		Map<String, Object> row = this.sqlRunner.getRow("""
                select max("InoutDate") as closed_date
                  from mat_inout
                 where "SourceTableName" = 'stock_close'
                   and "Material_id"     = cast(:matId as integer)
                   and "StoreHouse_id"   = cast(:houseId as integer)
                """, p);

		if (row == null || row.get("closed_date") == null) return null;
		return ((java.sql.Date) row.get("closed_date")).toLocalDate();
	}
}