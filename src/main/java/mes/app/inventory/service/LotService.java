package mes.app.inventory.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.entity.Company;
import mes.domain.entity.JobRes;
import mes.domain.entity.MaterialProduce;
import mes.domain.entity.SeqMaker;
import mes.domain.entity.Shipment;
import mes.domain.entity.ShipmentHead;
import mes.domain.repository.CompanyRepository;
import mes.domain.repository.JobResRepository;
import mes.domain.repository.MatProduceRepository;
import mes.domain.repository.SeqMakerRepository;
import mes.domain.repository.ShipmentHeadRepository;
import mes.domain.repository.ShipmentRepository;
import mes.domain.services.SqlRunner;

/**
 * LOT 트래킹 서비스.
 *
 * ★ 재귀 CTE 4개(재료추적·제품추적·입출고추적·출하추적)는 모두 「지나온 로트번호 배열(path)」로
 *   순환을 막는다. PK·FK 가 로트번호를 공유하므로(공정도메인 기준 §1) 이 안전망이 없으면
 *   무한 재귀로 서버가 멈춘다.
 *
 *   ⚠ path 를 이어붙일 때 반드시 ::text 로 캐스팅한다.
 *      앵커에서 path 타입이 text[] 로 고정되는데 "LotNumber" 는 varchar 라
 *      text[] || varchar 는 PostgreSQL 의 anyarray||anyelement 로 해석되지 않는다
 *      → "연산자 없음: text[] || character varying" 로 쿼리 전체가 죽는다.
 */
@Service
public class LotService {

	@Autowired
	SeqMakerRepository seqMakerRepository;

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MatProduceRepository matProduceRepository;

	@Autowired
	JobResRepository jobResRepository;

	@Autowired
	ShipmentRepository shipmentRepository;

	@Autowired
	ShipmentHeadRepository shipmentHeadRepository;

	@Autowired
	CompanyRepository companyRepository;


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
            from mat_lot ml  
                left join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id" 
                left join unit u on u.id = m."Unit_id" 
                left join mat_inout mi on ml."SourceDataPk" = mi.id and ml."SourceTableName" ='mat_inout'
            where mi.id = cast(:mioId as Integer) 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);
		return items;
	}

	// Lot 번호 만들기
	public String make_lot_in_number() {

		// 현재 날,시간
		Timestamp today = new Timestamp(System.currentTimeMillis());

		// 현재 일자
		LocalDate date = LocalDate.now();
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd");

		List<SeqMaker> sm = this.seqMakerRepository.findByCodeAndBaseDate("LOT_IN",date.format(dateFormat));

		SeqMaker s = new SeqMaker();

		if (sm.size() > 0) {
			s = sm.get(0);
		} else {
			s.setCode("LOT_IN");
			s.setBaseDate(date.format(dateFormat));
			s.setCurrVal(0);
			s.set_modified(today);
		}
		s.setCurrVal(s.getCurrVal() + 1);
		this.seqMakerRepository.save(s);

		String lotNumber = "LI-" + date.format(dateFormat) + "-" +String.format("%04d", s.getCurrVal());

		return lotNumber;
	}

	// Lot 번호 만들기
	public String make_production_lot_in_number(String type) {

		// 현재 날,시간
		Timestamp today = new Timestamp(System.currentTimeMillis());

		// 현재 일자
		LocalDate date = LocalDate.now();
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd");

		List<SeqMaker> sm = this.seqMakerRepository.findByCodeAndBaseDate("PROD_LOT_IN",date.format(dateFormat));

		SeqMaker s = new SeqMaker();

		if (sm.size() > 0) {
			s = sm.get(0);
		} else {
			s.setCode("PROD_LOT_IN");
			s.setBaseDate(date.format(dateFormat));
			s.setCurrVal(0);
			s.set_modified(today);
		}
		s.setCurrVal(s.getCurrVal() + 1);
		this.seqMakerRepository.save(s);

		String lotNumber = type + "-" + date.format(dateFormat) + "-" +String.format("%04d", s.getCurrVal());

		return lotNumber;
	}

	/**
	 * LOT 기본정보 — ★스캔 키 하나로 세 갈래를 모두 찾는다.
	 *
	 *   ① 사내 로트번호   mat_lot."LotNumber"     (기존 동작)
	 *   ② 박스/외부 라벨  mat_lot."MakerLotNo"    1공장 CK·PK/인박스 UDI, 2공장 박스 라벨
	 *   ③ 포장 라벨 번호  pack_label."LotNo"      카톤(OUT BOX)처럼 mat_lot 에 없는 번호
	 *
	 * ②③이 「포장된 로트」로 들어오는 입구다. 포장 화면이 실물에 붙이는 번호는
	 * 사내 로트번호와 다르다 — 이 갈래가 없으면 현장에서 박스를 찍는 순간
	 * 「해당 LOT정보가 없습니다」 가 뜬다. 트래킹 본체(재료·제품 추적)는
	 * 사내 로트번호만 알면 되므로, 여기서 사내 번호로 환산해 넘긴다.
	 *
	 * @param exact 'Y' 면 ①만 본다. 후보가 여러 건일 때 화면이 하나를 골라 다시 부르는 용도 —
	 *              ②로 다시 풀면 고른 것과 다른 로트가 [0] 으로 올라올 수 있다.
	 */
	public List<Map<String, Object>> lotDetail(String key, boolean exact) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("key", key == null ? null : key.trim());
		dicParam.addValue("exact", exact);

		String sql = """
            with k as (
                select cast(:key as varchar) as skey
                     , cast(:exact as boolean) as exact_only
            )
            , hit as (
                -- ① 사내 로트번호
                select ml.id as ml_id, 1 as pri, 'lot'::text as match_by
                  from mat_lot ml, k
                 where ml."LotNumber" = k.skey

                union all
                -- ② 박스/외부 라벨 (1공장 UDI · 2공장 박스 라벨)
                select ml.id, 2, 'maker'
                  from mat_lot ml, k
                 where k.exact_only is not true
                   and ml."MakerLotNo" = k.skey

                union all
                -- ③ 카톤 개체 — 실물 박스에 붙는 바코드(C-…-KR-01).
                --    pack_carton 이 국가별 완제품 로트를 직접 들고 있어
                --    차수를 거치지 않고 바로 이어진다 — 국가가 안 섞인다.
                select ml.id, 3, 'carton'
                  from pack_carton pc
                  join mat_lot ml on ml.id = pc."MatLot_id"
                 cross join k
                 where k.exact_only is not true
                   and coalesce(pc._status,'a') = 'a'
                   and pc."CartonLotNo" = k.skey

                union all
                -- ④ 포장 라벨 테이블 — 대표 카톤 번호·구 데이터용 폴백.
                --    국가별로 나누기 전에 만들어진 번호는 여기서만 잡힌다.
                select ml.id, 4, 'pack_' || pl."LabelKind"
                  from pack_label pl
                  join mat_lot ml on ml."SourceTableName" = 'mat_produce'
                                 and ml."SourceDataPk"    = pl."MatProduce_id"
                 cross join k
                 where k.exact_only is not true
                   and coalesce(pl._status,'a') = 'a'
                   and pl."LotNo" = k.skey
            )
            , best as (
                -- 같은 로트가 여러 갈래로 걸리면 가장 강한 근거만 남긴다
                select distinct on (ml_id) ml_id, pri, match_by
                  from hit
                 order by ml_id, pri
            )
            select
              ml.id as ml_id
            , ml."LotNumber"
            , ml."MakerLotNo"
            , ml."InputQty"
            , ml."CurrentStock"
            , m."Name" as mat_name
            , mg."Name" as mat_group_name
            , mg."MaterialType" as mat_type
            , to_char(ml."InputDateTime", 'yyyy-mm-dd hh24:mi') as "InputDateTime"
            , to_char(ml."EffectiveDate" , 'yyyy-mm-dd hh24:mi') as "EffectiveDate"
            , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
            , u."Name" as unit_name
            , sh."Name" as store_name
            , ml."SourceTableName"
            , ml."SourceDataPk"
            , ml."Material_id" as mat_id
            , b.match_by
            , (select skey from k) as matched_key
            , case b.match_by
                when 'lot'          then '사내 LOT'
                when 'maker'        then '박스라벨'
                when 'pack_ckpk'    then 'CK·PK 라벨'
                when 'pack_inbox'   then '인박스 라벨'
                when 'carton'       then '카톤 박스'
                when 'pack_carton'  then '카톤(대표번호)'
                else '포장 라벨'
              end as match_by_name
            from best b
            inner join mat_lot ml on ml.id = b.ml_id
            inner join material m on m.id = ml."Material_id"
            left join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            left join store_house sh on sh.id = ml."StoreHouse_id"
            order by b.pri, ml.id desc
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}

	/** 하위호환 — 기존 호출부(사내 로트번호 정확 조회) */
	public List<Map<String, Object>> lotDetail(String lotNumber) {
		return lotDetail(lotNumber, true);
	}

	/**
	 * 포장 정보 — 이 로트가 「어느 박스로 나갔나 / 어느 박스에 담겼나」.
	 *
	 *   산출 : 이 로트 자체가 포장 차수의 산출물     → 그 차수에 붙은 라벨(CK·PK/인박스/카톤)
	 *   투입 : 이 로트가 포장에 소비됨(PK·CK·유닛)   → 담긴 완제품 로트와 그 박스 라벨
	 *
	 * 각 차수는 「라벨」 줄과 「투입」 줄로 나눠 내린다. 둘 다 차수에 1:N 이라
	 * 한 조인에 섞으면 서로 곱해져 수량이 부풀려진다.
	 *
	 * 제품추적 트리는 부모 로트까지만 보여줘서 "이 PK 가 실물 어느 박스에 있나" 를
	 * 답하지 못한다. 그 답은 박스 라벨(UDI)에 있다.
	 *
	 * 2공장(M-CELL)은 pack_label 을 쓰지 않고 박스 라벨을 mat_lot."MakerLotNo" 에만
	 * 기록하므로, 라벨 조인은 LEFT 로 두고 없으면 MakerLotNo 를 대신 내린다.
	 */
	public List<Map<String, Object>> getPackTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
            with mp_list as (
                -- 이 로트를 산출한 포장 차수
                select mp.id as mp_id, ml.id as prod_lot_id
                     , ml."LotNumber" as prod_lot, ml."MakerLotNo" as maker_lot
                  from mat_lot ml
                  join mat_produce mp on mp.id = ml."SourceDataPk"
                                     and ml."SourceTableName" = 'mat_produce'
                 where ml."LotNumber" = :lotNumber
                union
                -- 이 로트가 담긴 포장 차수
                select mp.id, pl2.id, pl2."LotNumber", pl2."MakerLotNo"
                  from mat_lot ml
                  join mat_lot_cons mlc on mlc."MaterialLot_id" = ml.id
                                       and mlc."SourceTableName" = 'mat_produce'
                  join mat_produce mp on mp.id = mlc."SourceDataPk"
                  join mat_lot pl2 on pl2."SourceTableName" = 'mat_produce'
                                  and pl2."SourceDataPk"    = mp.id
                 where ml."LotNumber" = :lotNumber
            )
            , mp_pack as (
                select k.* from mp_list k
                 where k.maker_lot is not null
                    or exists (select 1 from pack_label pl
                                where pl."MatProduce_id" = k.mp_id
                                  and coalesce(pl._status,'a') = 'a')
                    or exists (select 1 from pack_carton pc
                                where pc."MatProduce_id" = k.mp_id
                                  and coalesce(pc._status,'a') = 'a')
            )
            , pr as (
                -- ★ 라벨·카톤·투입은 모두 차수에 1:N 이다. 한 조인에 섞으면 서로 곱해져
                --   CK 48 한 건이 라벨 3장 때문에 3줄로 복사된다(합계 144).
                --   그래서 행을 분리해 UNION 한다 — 각 줄은 자기 칸만 채운다.

                -- ① 라벨 줄
                select k.mp_id, k.prod_lot_id
                     , '라벨'::text as row_type
                     , k.prod_lot
                     , case pl."LabelKind"
                         when 'ckpk'   then 'CK·PK 라벨'
                         when 'inbox'  then '인박스 라벨'
                         when 'carton' then '카톤(대표번호)'
                         else '박스라벨'
                       end as label_kind_name
                     , coalesce(pl."LotNo", k.maker_lot) as label_lot
                     , pl."Gtin"::text        as gtin
                     , pl."Qty"::float        as label_qty
                     , cast(pl."ExpiryDate" as text) as label_expiry
                     , null::text  as country_code
                     , null::text  as ship_state
                     , null::text  as src_mat_name
                     , null::text  as src_lot
                     , null::float as src_qty
                     , '1' || coalesce(pl."LabelKind",'zz') as sort_key
                  from mp_pack k
                  left join pack_label pl on pl."MatProduce_id" = k.mp_id
                                         and coalesce(pl._status,'a') = 'a'

                union all

                -- ② 카톤 개체 줄 — 실물 박스 하나가 한 줄. 출고에서 찍는 값이 여기 있다.
                --   MatLot_id 로 걸어 국가별 로트를 조회했을 때 그 국가 박스만 나온다.
                select k.mp_id, k.prod_lot_id
                     , '카톤'::text
                     , k.prod_lot
                     , '카톤 박스'::text
                     , pc."CartonLotNo"
                     , null::text
                     , pc."Qty"::float
                     , null::text
                     , pc."CountryCode"
                     , pc."ShipState"
                     , null::text, null::text, null::float
                     , '2' || lpad(pc."CartonNo"::text, 4, '0')
                  from mp_pack k
                  join pack_carton pc on pc."MatProduce_id" = k.mp_id
                                     and coalesce(pc._status,'a') = 'a'
                                     and (pc."MatLot_id" is null
                                          or pc."MatLot_id" = k.prod_lot_id)

                union all

                -- ③ 투입 줄 — 이 박스에 들어간 자재 로트 (CK·PK·인박스·카톤 …)
                select k.mp_id, k.prod_lot_id
                     , '투입'::text
                     , k.prod_lot
                     , null::text, null::text, null::text, null::float, null::text
                     , null::text, null::text
                     , m2."Name"
                     , ml."LotNumber"
                     , mlc."OutputQty"::float
                     , '3' || m2."Name"
                  from mp_pack k
                  join mat_lot_cons mlc on mlc."SourceDataPk"    = k.mp_id
                                       and mlc."SourceTableName" = 'mat_produce'
                  join mat_lot ml on ml.id = mlc."MaterialLot_id"
                  join material m2 on m2.id = ml."Material_id"
            )
            select
              r.row_type as role
            , p."Code" as process_code
            , coalesce(p."Name", '포장') as process_name
            , jr."WorkOrderNumber"
            , r.prod_lot
            , m."Name" as mat_name
            , to_char(coalesce(mp."EndTime", mp."StartTime"), 'yyyy-mm-dd hh24:mi') as pack_time
            , r.label_kind_name
            , r.label_lot
            , r.gtin
            , r.label_qty
            , r.label_expiry
            , r.country_code
            , r.ship_state
            , r.src_mat_name
            , r.src_lot
            , r.src_qty
            -- 차수 단위 값이라 첫 줄에만 찍는다. 매 줄에 두면 줄 수만큼 부풀려진다
            -- ★ 차수 전체(mp."GoodQty")가 아니라 이 로트의 수량이다.
            --   국가별로 로트가 갈렸으므로 JP 로트를 보면서 차수 합계 10 을 띄우면
            --   바로 옆 박스수(국가별 2)와 짝이 안 맞아 어느 쪽이 맞는지 알 수 없다.
            , case when row_number() over (partition by r.prod_lot_id order by r.sort_key) = 1
                   then round(coalesce((select ml3."InputQty" from mat_lot ml3
                                         where ml3.id = r.prod_lot_id),
                                       mp."GoodQty", 0)::numeric, 0) end as good_qty
            -- ★ 박스수는 pack_carton 을 센다. 국가별 로트를 조회하면 그 국가 박스만 세어진다.
            --   pack_label 의 carton "Qty" 는 차수 전체라 국가별 화면에서는 과하게 나온다.
            --   pack_carton 이 없으면(구 데이터) 대표 라벨 값으로 떨어진다.
            , case when row_number() over (partition by r.prod_lot_id order by r.sort_key) = 1
                   then coalesce(
                          nullif((select count(*) from pack_carton pc2
                                   where pc2."MatProduce_id" = r.mp_id
                                     and coalesce(pc2._status,'a') = 'a'
                                     and (pc2."MatLot_id" is null
                                          or pc2."MatLot_id" = r.prod_lot_id)), 0),
                          (select pl2."Qty"::bigint from pack_label pl2
                            where pl2."MatProduce_id" = r.mp_id
                              and pl2."LabelKind" = 'carton'
                              and coalesce(pl2._status,'a') = 'a'))
                   end as box_cnt
            from pr r
            join mat_produce mp on mp.id = r.mp_id
            left join job_res jr on jr.id = mp."JobResponse_id"
            left join work_center wc on wc.id = jr."WorkCenter_id"
            left join process p on p.id = wc."Process_id"
            left join material m on m.id = mp."Material_id"
            order by r.prod_lot, r.sort_key
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}

	/** SqlRunner.getRows 는 오류 시 null 을 준다 — 화면이 length 로 죽지 않게 감싼다 */
	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	public List<Map<String, Object>> getMaterialTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
with recursive T as (    
         with P as(
            select 
            jr.id as jr_id
            , jr."WorkOrderNumber" 
            , mp.id as mp_id
            , ''::text as p_lot_number
            , mp."LotNumber" as lot_number
            , jr."Material_id" as mat_pk
            , ml."EffectiveDate"
            from job_res jr
            inner join mat_produce mp on mp."JobResponse_id" = jr.id            
            inner join material m on m.id=jr."Material_id" 
            left join mat_grp mg on mg.id = m."MaterialGroup_id" 
            inner join mat_lot ml on ml."SourceDataPk" =mp.id and ml."SourceTableName" ='mat_produce'
            where ml."LotNumber" = :lotNumber
         ) 
         ,A as(
          select 
           jr.jr_id
          , jr."WorkOrderNumber" 
          , mp."LotNumber" p_lot_number
          , mc."Material_id" as mat_pk
          -- ★ 차수가 산출한 품목. 아래 재귀에서 부모 품목을 맞추는 데 쓴다.
          --   로트번호만으로 이으면 번호를 공유하는 다른 품목의 가지가 섞인다
          , mp."Material_id" as p_mat_id
          , mp.id as mp_id
          , mc.id as mc_id
          , mc."BomQty"
          , mc."ConsumedQty" 
          from p as jr
          left join mat_consu mc on mc."JobResponse_id" = jr.jr_id
          left join mat_produce mp on mp."JobResponse_id" =jr.jr_id
        ) , B as(
        select 
        A.*
        ,ml."LotNumber" as lot_number
        , ml."Material_id"  
        , ml."EffectiveDate"
        from mat_lot ml 
        inner join mat_lot_cons mlc on mlc."MaterialLot_id" =ml.id
        inner join A on A.mp_id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce' and ml."Material_id" =A.mat_pk  
        )        
        select 
        jr_id
        , "WorkOrderNumber" 
        ,p_lot_number
        , lot_number
        , null::integer as mp_id
        , null::integer as p_mat_pk        
        , mat_pk 
        , 1 as lvl 
        , "EffectiveDate"
        -- ★ 순환 방지. PK·FK 가 로트번호를 공유하므로(공정도메인 §1)
        --   산출 로트번호와 소비 로트번호가 같아지는 순간 자기를 다시 불러
        --   무한 재귀가 된다. 지나온 로트를 배열로 들고 다니며 막는다.
        -- ⚠ 원소를 text 로 캐스팅한다. 여기서 path 타입이 text[] 로 굳는데
        --   "LotNumber" 는 varchar 라, 아래 재귀에서 ::text 없이 이어붙이면
        --   「연산자 없음: text[] || character varying」 로 쿼리가 통째로 죽는다.
        -- ★ 원소는 「로트|품목」이다. 로트번호만 넣으면 아래 node_key(로트|품목)와
        --   기준이 어긋난다 — 2공장은 포장이 원 로트번호를 그대로 물려받아
        --   (조립품 789 → 완제품 853, 번호 동일) 번호만 보는 차단이
        --   품목이 다른 정상 가지를 순환으로 오인해 통째로 자른다.
        --   품목까지 같아야 진짜 순환이므로 무한재귀 방어는 그대로다.
        , array[concat(lot_number,'|',mat_pk)::text] as path
        from P
        union all 
        select 
        B.jr_id
        , B."WorkOrderNumber" 
        , T.lot_number as p_lot_number
        , B.lot_number 
        , B.mp_id
        , T.mat_pk as p_mat_pk
        , B.mat_pk as mat_pk
        , t.lvl +1 as lvl
        , B."EffectiveDate"
        , T.path || concat(B.lot_number,'|',B.mat_pk)::text   -- ★ ::text 필수 (위 주석)
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
                      -- ★ 부모 품목도 맞춘다. 번호를 공유하는 로트에서
                      --   완제품 가지와 조립품 가지가 서로 섞이는 것을 막는다.
                      --   is null 을 여는 이유 : 구 데이터에 Material_id 가 없는
                      --   차수가 있으면 조건 전체가 NULL 이 되어 조용히 사라진다
                      and (B.p_mat_id is null or B.p_mat_id = T.mat_pk)
         where not (concat(B.lot_number,'|',B.mat_pk)::text = any(T.path))   -- 순환 차단
           and T.lvl < 20                               -- 깊이 상한(안전망)
        )        
        -- ★ 같은 node_key(로트|품목)가 뿌리와 자식 양쪽에 생기는 것을 정리한다.
        --   2공장은 포장이 원 로트번호를 그대로 물려받으므로 조회한 번호로
        --   ① 조립품이 자기 뿌리로 한 번 ② 완제품의 자식으로 한 번, 두 번 나온다.
        --   키가 겹치면 트리가 한쪽을 삼키므로 부모가 있는 쪽(깊은 lvl)을 남긴다.
        --   1공장처럼 번호가 겹치지 않으면 rn 이 전부 1 이라 아무것도 안 바뀐다.
        , D as (
            select * from (
                select T.*
                     , row_number() over (partition by T.lot_number, T.mat_pk
                                              order by T.lvl desc) as rn
                  from T
            ) z where z.rn = 1
        )
        select 
        jr_id 
        , "WorkOrderNumber" 
        , p_mat_pk
        , mat_pk
        , p_lot_number
        , m1."Name" as p_mat_name
        , lot_number
        , m2."Name" as mat_name
        , (
            select 
            sum(mlc."OutputQty")
            from mat_lot ml 
            inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id 
            where T.mat_pk=ml."Material_id" and T.mp_id=mlc."SourceDataPk" and mlc."SourceTableName"='mat_produce'
            group by ml."Material_id" 
            ) as lot_consume_qty
        , T.mp_id
        , T.lvl
        , u."Name" as unit_name
        -- ★ 트리 키. 로트번호만으로는 부족하다 — PK·FK 가 같은 로트번호를 공유하므로
        --   (공정도메인 기준 §1) 로트번호 단독을 키로 쓰면 한쪽 가지가 조용히 사라진다
        , concat(T.lot_number,'|',T.mat_pk) as node_key
        , case when coalesce(T.p_lot_number,'') = '' then null
               else concat(T.p_lot_number,'|',T.p_mat_pk) end as parent_key
        -- ★ 사용일시 = 이 로트가 소비된 차수의 종료 시각.
        --   리콜 범위를 자를 때 쓴다 — 「8/3 이후 투입분」 같은 판단이 이 칸 없이는 불가능하다.
        --   진행 중이면 EndTime 이 비므로 StartTime 으로 떨군다.
        --   뿌리 행은 mp_id 가 없어 빈칸이다 — 「소비된 시각」이 없는 게 맞다.
        --   생성일로 채우지 않는다 : 한 칸에 두 의미가 섞이면
        --   이 날짜로 회수 범위를 자를 때 조용히 오판한다.
        -- ★ 잔여 = 지금 이 로트에 남은 재고(mat_lot."CurrentStock").
        --   PK·CK 를 다 안 쓰고 남기는 경우가 많아 「얼마 쓰고 얼마 남았나」를 같이 본다.
        --   join 이 아니라 스칼라 서브쿼리인 이유 : 같은 번호·품목으로
        --   mat_lot 행이 여러 개면 트리 행이 그만큼 복사된다.
        --   ⚠ 투입 당시가 아니라 「조회 시점」 잔여다.
        , (select sum(ml2."CurrentStock") from mat_lot ml2
            where ml2."LotNumber" = T.lot_number and ml2."Material_id" = T.mat_pk) as remain_qty
        , to_char(coalesce(mpu."EndTime", mpu."StartTime"), 'yyyy-mm-dd hh24:mi') as used_time
        from D T
        left join material m1 on m1.id = T.p_mat_pk
        inner join material m2 on m2.id = T.mat_pk
        left join unit u on u.id = m2."Unit_id"
        left join mat_produce mpu on mpu.id = T.mp_id
        order by lvl
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}

	public List<Map<String, Object>> getProductTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
		  with recursive T as(
            select 
            null::text as p_lot_number
            , ml."LotNumber" as lot_number  
            , null::float as mp_id
            -- ★ 뿌리는 「이 로트를 만든 작지」를 본다.
            --   재귀 행의 wo 는 「이 로트를 소비한 작지」라 의미가 다르지만,
            --   화면에선 둘 다 「그 줄의 생산 작지」로 읽혀 같은 칸이 맞다.
            --   안 채우면 상위 제품이 없는 완제품은 영원히 빈칸이 된다.
            -- ⚠ max 인 이유는 아래 group by 다. 생산 행(작지 있음)과
            --   세척 행(작지 없음)이 한 번호로 섞이면 값이 있는 쪽을 남긴다
            --   (max 는 null 을 건너뛴다).
            , max(jr0."WorkOrderNumber") as wo
            , null::int as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , 1 as lvl
            -- ★ 순환 방지. 이 재귀는 T.lot_number = ml."LotNumber" 로 이어지고
            --   새 행의 lot_number 는 mp."LotNumber" 다. 둘이 같으면 자기를 다시 불러
            --   무한 재귀가 된다 — PK·FK 가 로트번호를 공유하는 구조에서 실제로 발생한다.
            -- ⚠ 원소를 text 로 캐스팅한다 (재료추적 주석 참고).
            -- ★ 원소는 「로트|품목」. 재료추적과 같은 이유다 —
            --   포장이 원 로트번호를 물려받으면 번호만 보는 차단이
            --   「조립품 → 완제품」 가지를 순환으로 오인해 잘라낸다
            , array[concat(ml."LotNumber",'|',ml."Material_id")::text] as path
            from mat_lot ml        
            -- left 로 둔다 : 시드처럼 생산 차수가 없는 로트도 뿌리로는 남아야 한다
            left join mat_produce mp0 on mp0.id = ml."SourceDataPk"
                                     and ml."SourceTableName" = 'mat_produce'
            left join job_res jr0 on jr0.id = mp0."JobResponse_id"
            where ml."LotNumber" = :lotNumber
            -- ★ 로트번호+품목으로 접는다. 안 접으면 mat_lot 행 수만큼 뿌리가 복사된다 —
            --   세척은 새 채번 없이 원 번호로 클린룸 행을 만들므로(WashService.newLot)
            --   4회 세척한 로트는 똑같은 줄이 4번 찍히고, node_key 까지 같아
            --   트리에서도 안 접힌다. 품목을 함께 묶는 이유는 번호를 공유하는
            --   서로 다른 품목까지 한 줄로 합치지 않기 위해서다.
            group by ml."LotNumber", ml."Material_id"
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            ,mp.id as mp_id
            , jr."WorkOrderNumber" as wo
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , (t.lvl+1 ) as lvl
            , T.path || concat(mp."LotNumber",'|',mp."Material_id")::text   -- ★ ::text 필수
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            -- ★ 품목까지 맞춰 잇는다. 번호만 보면 같은 번호를 쓰는 완제품 행까지
            --   같은 부모로 물려 자식이 두 번 붙는다
            inner join T on T.lot_number = ml."LotNumber" 
                        and ml."Material_id" = coalesce(T.p_mat_pk, T.l_mat_pk)
            inner join job_res jr on jr.id=mp."JobResponse_id" 
            -- mp 가 left join 이라 LotNumber 가 NULL 이면 조건 전체가 NULL 이 되어
            -- 그 행이 조용히 사라진다. is null 을 먼저 열어 의도를 명시한다
            where (mp."LotNumber" is null
                   or not (concat(mp."LotNumber",'|',mp."Material_id")::text
                           = any(T.path)))                        -- 순환 차단
              and T.lvl < 20                                      -- 깊이 상한(안전망)
        )
        -- ★ 뿌리와 자식에 같은 node_key 가 생기는 것을 정리한다 (재료추적과 동일).
        --   포장이 원 번호를 물려받으면 완제품 로트가 ① 자기 뿌리 ② 조립품의 자식
        --   두 번 나온다. 부모가 있는 쪽(깊은 lvl)을 남긴다.
        , D as (
            select * from (
                select T.*
                     , row_number() over (
                           partition by T.lot_number, coalesce(T.p_mat_pk, T.l_mat_pk)
                               order by T.lvl desc) as rn
                  from T
            ) z where z.rn = 1
        )
          -- ★ 이 재귀 CTE 는 내부 칸 이름과 의미가 엇갈린다.
          --   뿌리 행 : l_mat_pk 가 lot_number 의 품목, 부모 없음
          --   재귀 행 : p_mat_pk 가 lot_number 의 품목, l_mat_pk 가 p_lot_number 의 품목
          --   그대로 내리면 재귀 행부터 품목명 두 칸이 서로 바뀌어 보인다.
          --   여기서 재료추적과 같은 규칙(mat_pk = 자기, p_mat_pk = 부모)으로 맞춘다.
          select 
          concat(p_lot_number , lot_number) as id
          , p_lot_number 
          , lot_number
          , mpar."Name" as p_mat_name
          , mself."Name" as mat_name
          , coalesce(T.p_mat_pk, T.l_mat_pk) as mat_pk
          , case when T.p_lot_number is null then null else T.l_mat_pk end as p_mat_pk
          , wo      
          , lvl
          -- 투입수량은 「재료LOT 이 이 차수에 몇 개 들어갔나」다 → 부모 품목 기준
          ,(select sum(mlc."OutputQty") from mat_lot ml 
            inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id
            where T.l_mat_pk=ml."Material_id" and T.mp_id=mlc."SourceDataPk" and mlc."SourceTableName"='mat_produce'
            group by ml."Material_id") as lot_consume_qty
          -- 단위도 투입수량을 따라간다. 산출품 단위를 붙이면 숫자와 단위가 어긋난다
          , u."Name" as unit_name
          -- ★ 트리 키. PK·FK 가 로트번호를 공유하므로 품목 id 를 붙여 가른다
          , concat(T.lot_number,'|',coalesce(T.p_mat_pk, T.l_mat_pk)) as node_key
          , case when T.p_lot_number is null then null
                 else concat(T.p_lot_number,'|',T.l_mat_pk) end as parent_key
            -- ★ 사용일시 = 이 로트가 소비된 차수의 종료 시각.
          --   리콜 범위를 자를 때 쓴다 — 「8/3 이후 투입분」 같은 판단이 이 칸 없이는 불가능하다.
          --   진행 중이면 EndTime 이 비므로 StartTime 으로 떨군다.
          --   뿌리 행은 mp_id 가 없어 빈칸이다 — 「소비된 시각」이 없는 게 맞다.
          --   생성일로 채우지 않는다 : 한 칸에 두 의미가 섞이면
          --   이 날짜로 회수 범위를 자를 때 조용히 오판한다.
            -- ★ 잔여 = 지금 이 로트에 남은 재고(mat_lot."CurrentStock").
          --   PK·CK 를 다 안 쓰고 남기는 경우가 많아 「얼마 쓰고 얼마 남았나」를 같이 본다.
          --   join 이 아니라 스칼라 서브쿼리인 이유 : 같은 번호·품목으로
          --   mat_lot 행이 여러 개면 트리 행이 그만큼 복사된다.
          --   ⚠ 투입 당시가 아니라 「조회 시점」 잔여다.
          , (select sum(ml2."CurrentStock") from mat_lot ml2
              where ml2."LotNumber" = T.lot_number
                and ml2."Material_id" = coalesce(T.p_mat_pk, T.l_mat_pk)) as remain_qty
          , to_char(coalesce(mpu."EndTime", mpu."StartTime"), 'yyyy-mm-dd hh24:mi') as used_time
          from D T
          left join mat_produce mpu on mpu.id = T.mp_id
          left join material mself on mself.id = coalesce(T.p_mat_pk, T.l_mat_pk)
          left join material mpar  on mpar.id  = (case when T.p_lot_number is null
                                                       then null else T.l_mat_pk end)
          left join unit u on u.id = mpar."Unit_id"
          order by lvl
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}

	public List<Map<String, Object>> getMaterialInoutTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
	  with recursive T as (
         with P as(
            -- ★ 로트번호 → mat_lot → 차수 순으로 찾는다.
            --   예전엔 mp."LotNumber" 을 직접 비교했는데, 포장 완제품이
            --   국가별로 나뉘면서(P-…-KR) 차수 로트번호와 재고 로트번호가
            --   달라졌다. 그대로 두면 트리가 통째로 비어 화면이 백지가 된다.
            -- ★ lot_number 는 mp."LotNumber" 를 그대로 둔다 —
            --   아래 재귀가 B.p_lot_number(= 차수 로트) 와 이어붙는다.
            select 
            mp.id as mp_id
            , ''::text as p_lot_number
            , mp."LotNumber" as lot_number
            , jr."Material_id" as mat_pk
            from mat_lot ml
            inner join mat_produce mp on mp.id = ml."SourceDataPk"
                                     and ml."SourceTableName" = 'mat_produce'
            inner join job_res jr on jr.id = mp."JobResponse_id"
            where ml."LotNumber" = :lotNumber

            union all

            -- ★ 세척 로트도 뿌리로 받는다.
            --   세척은 로트번호를 유지한 채 mat_lot 행을 새로 만든다
            --   (WashService.newLot — 새 채번 없음). 원 로트와 번호·품목이 같고
            --   창고만 생산(17) → 클린룸(5) 로 다르다.
            --   위 갈래는 SourceTableName='mat_produce' 만 앵커로 잡으므로
            --   세척 로트를 넣으면 P 가 0건이 되어 재귀 전체가 비고
            --   「원재료입고정보」 탭이 백지가 된다.
            --
            --   차수를 거치지 않고 자기 로트번호를 그대로 lot_number 로 실어
            --   아래 LL → mat_lot 조인에서 생산창고 원본 행을 만나게 한다.
            --   클린룸 행 자신은 맨 아래 where 절
            --   (SourceTableName 이 mat_inout 인 것만)에서 걸러지므로
            --   원재료 한 줄만 남는다.
            select null::integer  as mp_id
                 , ''::text        as p_lot_number
                 , ml."LotNumber"  as lot_number
                 , ml."Material_id" as mat_pk
              from mat_lot ml
             where ml."SourceTableName" = 'wash_work_item'
               and ml."LotNumber" = :lotNumber
         ) 
         ,A as(
          select 
          mp."LotNumber" p_lot_number
          , mc."Material_id" as mat_pk
          , mp.id as mp_id
          from job_res jr
          left join mat_consu mc on mc."JobResponse_id" = jr.id
          left join mat_produce mp on mp."JobResponse_id" =jr.id
        ), B as(
        select 
        A.*
        ,ml."LotNumber" as lot_number
        , ml."Material_id"
        from mat_lot ml 
        inner join mat_lot_cons mlc on mlc."MaterialLot_id" =ml.id 
        inner join A on A.mp_id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce' and ml."Material_id" =A.mat_pk 
        )
        select  
        p_lot_number, lot_number, null::integer as mp_id, mat_pk  
        , 1 as lvl
        -- ★ 순환 방지. A 가 job_res 전체를 훑기 때문에 이 재귀가 제일 깊게 내려간다 —
        --   로트번호가 한 번이라도 돌면 무한루프로 서버가 멈춘다.
        -- ⚠ 원소를 text 로 캐스팅한다 (재료추적 주석 참고).
        , array[lot_number::text] as path
        from P
        union all 
        select 
        T.lot_number as p_lot_number, B.lot_number, B.mp_id, B.mat_pk as mat_pk
        , (T.lvl+1) as lvl
        , T.path || B.lot_number::text          -- ★ ::text 필수
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
         where not (B.lot_number::text = any(T.path))   -- 순환 차단
           and T.lvl < 20                               -- 깊이 상한(안전망)
        ), LL as
        (
        -- 품목 id 를 함께 들고 나온다. 로트번호만으로 다시 찾으면
        -- PK·FK 처럼 번호를 공유하는 로트에서 엉뚱한 행이 붙는다
        select T.lot_number, min(T.mat_pk) as mat_pk
          from T
         where coalesce(T.lot_number,'') <> ''
         group by T.lot_number
        )
        -- ★ 품목명·유효기간·수량은 mat_lot 에서 가져온다.
        --   예전엔 입고(mat_inout) 조인이 맞을 때만 채워져서,
        --   입고 출처가 아닌 로트는 번호만 남고 전부 빈칸으로 보였다.
        select 
        LL.lot_number
        , m."Name" as mat_name
        , to_char(ml."EffectiveDate" ,'yyyy-MM-dd hh24:mi:ss') as "EffectiveDate"
        , to_char(coalesce(mi."InoutDate", ml."InputDateTime"),'yyyy-MM-dd') as "InoutDate"
        , to_char(mi."InoutTime",'hh24:mi') as "InoutTime"
        , coalesce(mi."InputQty", ml."InputQty") as "InputQty"
        -- ★ 구분명은 입출고 방향마다 코드 마스터가 다르다 (MaterialInoutService 와 동일).
        --   input_type 하나로만 풀면 반품입고·회수가 빈칸이 된다.
        --   세부 구분이 없으면 inout_type(입고/반품/회수)까지만 떨구고,
        --   입고 이력 자체가 없으면 빈칸으로 둔다.
        , coalesce(
            case when mi."InOut" = 'in'     then fn_code_name('input_type',  mi."InputType")
                 when mi."InOut" = 'out'    then fn_code_name('output_type', mi."OutputType")
                 when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
                 when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
            end,
            fn_code_name('inout_type', mi."InOut"))
          as input_type_name
        , c."Name" as company_name
        -- ★ 시드로 들어온 로트는 mat_inout 행 자체가 없어 매입처·구분이 빈다.
        --   그 실체는 mat_lot."Description" 에 있다 (「초기입고 시드」·「발주 입고」 등).
        --   구분 칸에 섞지 않고 따로 내린다 — 코드값이 아니라 섞으면
        --   나중에 진짜 구분이 들어올 때 어느 쪽이 진실인지 알 수 없게 된다
        , ml."Description" as lot_desc
        , ml."MakerLotNo" as maker_lot_no
        , sh."Name" as store_name
        from LL 
        inner join material m on m.id = LL.mat_pk
        inner join mat_lot ml on ml."LotNumber" = LL.lot_number
                             and ml."Material_id" = LL.mat_pk
        -- ★ 입고 행이 로트를 가리키는 방향이 두 가지다.
        --   ① ml."SourceTableName"='mat_inout'  → 로트가 입고를 가리킨다
        --   ② mi."SourceTableName"='mat_lot'    → 입고가 로트를 가리킨다
        --   ②가 초기입고 시드 282건 전부다(InOut='in', InputType='order_in').
        --   ①만 걸면 입고 행이 멀쩡히 있는데도 입고일·수량·구분이 전부 빈칸이 된다.
        --   lateral + limit 1 인 이유 : 한 로트에 입고 행이 여럿이면
        --   조인이 트리 행을 그만큼 복제한다. 가장 이른 것(=최초 입고)을 잡는다.
        left join lateral (
            select mi2.*
              from mat_inout mi2
             where (ml."SourceTableName" = 'mat_inout' and mi2.id = ml."SourceDataPk")
                or (mi2."SourceTableName" = 'mat_lot'  and mi2."SourceDataPk" = ml.id
                    and mi2."InOut" = 'in')
             order by mi2."InoutDate", mi2.id
             limit 1
        ) mi on true
        left join company c on c.id = mi."Company_id"
        left join store_house sh on sh.id = ml."StoreHouse_id"
        -- 세척·멸균·생산이 만든 로트는 「원재료 입고」가 아니다 — 빈칸 행의 정체.
        -- 출처가 비어 있는 것(초기 시드)은 외부에서 들어온 것으로 본다.
        -- 공정 테이블을 나열하지 않고 뒤집은 이유 : 공정이 늘 때마다 이 목록을 고치지 않게
        -- nullif 가 필요하다 : 출처가 NULL 이 아니라 빈 문자열이면
        -- coalesce 가 안 걸려 시드 로트가 통째로 사라지고 화면이 빈다
        where coalesce(nullif(ml."SourceTableName",''),'mat_inout') = 'mat_inout'
        order by m."Name", LL.lot_number
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}

	/**
	 * 제품 출하 추적 — 이 로트가 어느 출하 건으로, 어느 박스에 담겨 나갔나.
	 *
	 * ★ 출하량은 shipment."Qty" 가 아니라 mat_lot_cons."OutputQty" 다.
	 *   전자는 출하 상세(품목) 전체 수량이라, 완제품이 국가별로 갈린 뒤에는
	 *   -JP 로 조회해도 KR 몫까지 더해진 값이 나온다.
	 *   (초기수량 25 인 로트에 출하량 50 이 찍히던 원인)
	 *
	 * ★ 카톤(pack_carton)을 함께 낸다. 출고를 박스 단위로 하는데 추적에 박스가 없으면
	 *   회수 때 「어느 상자를 찾아라」를 낼 수 없다.
	 *   박스가 여럿이면 줄이 늘어난다 — 그게 목적이라 접지 않는다.
	 *   대신 출하량은 첫 줄에만 찍는다. 매 줄에 두면 줄 수만큼 부풀려진다.
	 */
	public List<Map<String, Object>> getProductShipmentTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);
		String sql = """
		 with recursive T as(
            select 
            null::text as p_lot_number
            , ml."LotNumber" as lot_number  
            , null::float as mp_id
            , null::int as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , 1 as lvl
            -- ★ 순환 방지 — 위 두 추적과 같은 구조라 같은 함정이 있다.
            -- ⚠ 원소를 text 로 캐스팅한다 (재료추적 주석 참고).
            , array[ml."LotNumber"::text] as path
            from mat_lot ml
            where ml."LotNumber"= :lotNumber
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            , mp.id as mp_id
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , (T.lvl+1) as lvl
            , T.path || mp."LotNumber"::text          -- ★ ::text 필수
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            inner join T on T.lot_number = ml."LotNumber" 
            inner join job_res jr on jr.id=mp."JobResponse_id" 
            where (mp."LotNumber" is null
                   or not (mp."LotNumber"::text = any(T.path)))   -- 순환 차단
              and T.lvl < 20                                      -- 깊이 상한(안전망)
	        )
	        -- ★ 품목까지 함께 묶는다. 로트번호만으로 다시 찾으면 PK·FK 처럼
	        --   번호를 공유하는 로트에서 엉뚱한 출하가 붙는다
	        , pp as (
	            -- ★ 품목별로 남긴다. min() 으로 하나만 고르면 같은 번호를 쓰는
	            --   두 품목 중 한쪽만 남는다 — 2공장은 조립품(789)과 완제품(853)이
	            --   번호를 공유하는데 출하는 완제품 쪽에 걸려 있어서,
	            --   min 이 조립품을 고르면 출하 이력이 통째로 사라진다.
	            select distinct lot_number, coalesce(p_mat_pk, l_mat_pk) as mat_pk
	              from T
	             where coalesce(lot_number,'') <> ''
	               and coalesce(p_mat_pk, l_mat_pk) is not null
	        )
	        , r as (
	        select 
	          pp.lot_number 
	        , m."Name" as mat_name
	        , sh."Company_id" 
	        , c."Name" as company_name
	        , sh."ShipDate" 
	        , mlc."OutputQty" as lot_ship_qty      -- ★ 이 로트가 나간 양
	        , s."Qty" as order_ship_qty            -- 참고 : 출하 상세(품목) 전체
	        , fn_code_name('shipment_state', sh."State" ) as shipment_state
	        , pc."CartonLotNo"  as carton_lot_no
	        , pc."CountryCode"  as carton_country
	        , pc."Qty"          as carton_qty
	        , pc."ShipState"    as carton_state
	        , row_number() over (partition by pp.lot_number, s.id
	                                 order by pc."CartonNo" nulls first) as rn
	        from pp 
	        inner join mat_lot ml on ml."LotNumber" = pp.lot_number
	                             and ml."Material_id" = pp.mat_pk
	        inner join material m on m.id = ml."Material_id" 
	        inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id and mlc."SourceTableName" ='shipment'
	        inner join shipment s on s.id=mlc."SourceDataPk" 
	        inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	        left join company c on c.id = sh."Company_id"
	        -- 이 출하 건으로 나간 박스. 없으면(로트만 지정한 구 방식) 한 줄로 남는다
	        left join pack_carton pc on pc."Shipment_id" = s.id
	                                and coalesce(pc._status,'a') = 'a'
	                                and pc."MatLot_id" = ml.id
	        )
	        select r.lot_number, r.mat_name, r."Company_id", r.company_name, r."ShipDate"
	             , r.shipment_state
	             , r.carton_lot_no, r.carton_country, r.carton_qty, r.carton_state
	             , case when r.rn = 1 then r.lot_ship_qty end as "Qty"
	             , r.order_ship_qty
	          from r
	         order by r."ShipDate", r.lot_number, r.carton_lot_no
				""";

		return nz(this.sqlRunner.getRows(sql, dicParam));
	}


	public List<Map<String, Object>> getMatLotList(String mat_type, Integer mat_group, Integer material, String lot_num, String date_from, String date_to, String cond) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("mat_type", mat_type);
		dicParam.addValue("mat_group", mat_group);
		dicParam.addValue("material", material);
		dicParam.addValue("lot_num", lot_num);

		if (StringUtils.isEmpty(date_from) == false && StringUtils.isEmpty(date_to) == false) {
			dicParam.addValue("date_from", Timestamp.valueOf(date_from + " 00:00:00"));
			dicParam.addValue("date_to", Timestamp.valueOf(date_to + " 23:59:59"));
		}

		String sql = """
        		select ml.id
                , to_char(ml."InputDateTime", 'yyyy-mm-dd hh24:mi') as prod_date
                , ml."LotNumber" as lot_num
                , fn_code_name('mat_type', mg."MaterialType" ) as mat_type
                , mg."Name" as mat_group
                , m."Code" as mat_code
                , m."Name" as mat_name
                , ml."InputQty" as input_qty
                , ml."OutQtySum" as out_qty
                , ml."CurrentStock" as current_stock
                , ml."Description" as description
                , ml."SourceDataPk" as source_id
                , ml."SourceTableName" as source_table
                from mat_lot ml 
                inner join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id"
                where 1=1
            """;

		if (StringUtils.isEmpty(mat_type)==false) {
			sql += " and mg.\"MaterialType\" = :mat_type ";
		}

		if (mat_group != null) {
			sql += " and mg.id = :mat_group ";
		}

		if (material != null) {
			sql += " and m.id = :material ";
		}

		if (StringUtils.isEmpty(lot_num) == false) {
			sql += " and ml.\"LotNumber\" ilike concat('%%',:lot_num,'%%') ";
		}

		if (StringUtils.isEmpty(date_from) == false && StringUtils.isEmpty(date_to) == false) {
			sql += " and ml.\"InputDateTime\" between :date_from and :date_to ";
		}

		if ("remain".equals(cond)) {
			sql += " and ml.\"CurrentStock\" > 0 ";
		}

		sql += " order by prod_date desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		for (int i = 0; i < items.size(); i++) {

			if ("mat_produce".equals((String) items.get(i).get("source_table"))) {

				Integer source_id = (Integer) items.get(i).get("source_id");
				MaterialProduce mpList = this.matProduceRepository.getMatProduceById(source_id);

				if (mpList != null) {

					Integer jobres_id = mpList.getJobResponseId();
					JobRes jr = this.jobResRepository.getJobResById(jobres_id);

					if (jr != null) {

						String work_order_num = jr.getWorkOrderNumber();

						if (work_order_num != null) {
							items.get(i).put("reg_history", "생산 (작지번호: " + work_order_num + ")");
						}
					}
				}
			}
		}

		return items;
	}

	// LOT 소비내역 조회
	public List<Map<String, Object>> getConsumedList(Integer matlot_id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("matlot_id", matlot_id);

		String sql = """
        		select mlc.id 
                , ml."LotNumber" as lot_num
                , to_char(mlc."OutputDateTime", 'yyyy-mm-dd hh24:mi') as consumed_date
                , mlc."OutputQty" as consumed_qty
                , mlc."Description" as description
                , mlc."SourceDataPk" as source_id
                , mlc."SourceTableName" as source_table
                from mat_lot_cons mlc 
                inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                where mlc."MaterialLot_id" = :matlot_id
            """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		for (int i = 0; i < items.size(); i++) {

			if ("shipment".equals((String) items.get(i).get("source_table"))) {

				Integer source_id = (Integer) items.get(i).get("source_id");
				Shipment spList = this.shipmentRepository.getShipmentById(source_id);

				if (spList != null) {
					Integer shipment_head_id = spList.getShipmentHeadId();

					if (shipment_head_id != null) {
						ShipmentHead sh =  this.shipmentHeadRepository.getShipmentHeadById(shipment_head_id);

						if (sh != null) {
							Integer company_id = sh.getCompanyId();

							if (company_id != null) {
								Company company = this.companyRepository.getCompanyById(company_id);

								if (company != null) {
									String company_name = company.getName();

									items.get(i).put("consumed_history", "출하 (고객사: " + company_name + ")");
								}
							}
						}
					}
				}
			}
		}

		return items;
	}
}