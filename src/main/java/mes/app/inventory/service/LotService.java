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
                -- ③ 포장 라벨 테이블 — 카톤(OUT BOX) 등 mat_lot 에 없는 번호
                select ml.id, 3, 'pack_' || pl."LabelKind"
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
            , b.match_by
            , (select skey from k) as matched_key
            , case b.match_by
                when 'lot'          then '사내 LOT'
                when 'maker'        then '박스라벨'
                when 'pack_ckpk'    then 'CK·PK 라벨'
                when 'pack_inbox'   then '인박스 라벨'
                when 'pack_carton'  then '카톤(OUT BOX)'
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
                select mp.id as mp_id, ml."LotNumber" as prod_lot, ml."MakerLotNo" as maker_lot
                  from mat_lot ml
                  join mat_produce mp on mp.id = ml."SourceDataPk"
                                     and ml."SourceTableName" = 'mat_produce'
                 where ml."LotNumber" = :lotNumber
                union
                -- 이 로트가 담긴 포장 차수
                select mp.id, pl2."LotNumber", pl2."MakerLotNo"
                  from mat_lot ml
                  join mat_lot_cons mlc on mlc."MaterialLot_id" = ml.id
                                       and mlc."SourceTableName" = 'mat_produce'
                  join mat_produce mp on mp.id = mlc."SourceDataPk"
                  join mat_lot pl2 on pl2."SourceTableName" = 'mat_produce'
                                  and pl2."SourceDataPk"    = mp.id
                 where ml."LotNumber" = :lotNumber
            )
            , mp_pack as (
                -- 포장 흔적(라벨 또는 박스라벨)이 있는 차수만.
                -- 조립·검사 차수까지 뜨면 이 탭이 무의미해진다
                select k.* from mp_list k
                 where k.maker_lot is not null
                    or exists (select 1 from pack_label pl
                                where pl."MatProduce_id" = k.mp_id
                                  and coalesce(pl._status,'a') = 'a')
            )
            , pr as (
                -- ★ 라벨과 투입은 둘 다 차수에 1:N 이다. 한 조인에 섞으면 서로 곱해져
                --   CK 48 한 건이 라벨 3장 때문에 3줄로 복사된다(합계 144).
                --   그래서 행을 아예 분리해 UNION 한다 — 각 줄은 자기 칸만 채운다.

                -- ① 라벨 줄 : 이 박스에 붙은 라벨
                select k.mp_id
                     , '라벨'::text as row_type
                     , k.prod_lot
                     , case pl."LabelKind"
                         when 'ckpk'   then 'CK·PK 라벨'
                         when 'inbox'  then '인박스 라벨'
                         when 'carton' then '카톤(OUT BOX)'
                         else '박스라벨'
                       end as label_kind_name
                     , coalesce(pl."LotNo", k.maker_lot) as label_lot
                     , pl."Gtin"::text        as gtin
                     , pl."Qty"::float        as label_qty
                     , cast(pl."ExpiryDate" as text) as label_expiry
                     , null::text  as src_mat_name
                     , null::text  as src_lot
                     , null::float as src_qty
                     , coalesce(pl."LabelKind",'zz') as sort_key
                  from mp_pack k
                  left join pack_label pl on pl."MatProduce_id" = k.mp_id
                                         and coalesce(pl._status,'a') = 'a'

                union all

                -- ② 투입 줄 : 이 박스에 들어간 자재 로트 (CK·PK·인박스·카톤 …)
                select k.mp_id
                     , '투입'::text
                     , k.prod_lot
                     , null::text
                     , null::text
                     , null::text
                     , null::float
                     , null::text
                     , m2."Name"
                     , ml."LotNumber"
                     , mlc."OutputQty"::float
                     , m2."Name"
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
            , r.src_mat_name
            , r.src_lot
            , r.src_qty
            -- 차수 단위 값이라 첫 줄에만 찍는다. 매 줄에 두면 줄 수만큼 부풀려진다
            , case when row_number() over (partition by r.mp_id order by r.row_type, r.sort_key) = 1
                   then round(coalesce(mp."GoodQty",0)::numeric, 0) end as good_qty
            -- ★ 박스(카톤) 수.
            --   1공장 : PackService 가 ceil(units/cap) 을 계산해 carton 라벨의 Qty 에 저장해 둔다.
            --              계산하지 않고 그 값을 그대로 쓴다 — 출하가 이 라벨 기준이라
            --              화면에서 다시 계산하면 실물과 어긋날 수 있다.
            --   2공장 : 유닛 1대 = 박스 1개(1:1). pack_label 을 안 쓰므로 차수당 1.
            --   1공장인데 carton 라벨이 없으면 모르는 것이다 — 1 로 채우지 않는다.
            , case when row_number() over (partition by r.mp_id order by r.row_type, r.sort_key) = 1
                   then coalesce(
                          (select pl2."Qty" from pack_label pl2
                            where pl2."MatProduce_id" = r.mp_id
                              and pl2."LabelKind" = 'carton'
                              and coalesce(pl2._status,'a') = 'a'),
                          case when exists (select 1 from pack_label pl3
                                             where pl3."MatProduce_id" = r.mp_id
                                               and coalesce(pl3._status,'a') = 'a')
                               then null else 1 end)
                   end as box_cnt
            from pr r
            join mat_produce mp on mp.id = r.mp_id
            left join job_res jr on jr.id = mp."JobResponse_id"
            left join work_center wc on wc.id = jr."WorkCenter_id"
            left join process p on p.id = wc."Process_id"
            left join material m on m.id = mp."Material_id"
            order by r.prod_lot
                   , case r.row_type when '라벨' then 0 else 1 end
                   , r.sort_key
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
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
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
        --   생성일로 채우지 않는다 : 한 칸에 두 의미가 섮이면
        --   이 날짜로 회수 범위를 자를 때 조용히 오판한다.
        , to_char(coalesce(mpu."EndTime", mpu."StartTime"), 'yyyy-mm-dd hh24:mi') as used_time
        from T
        left join material m1 on m1.id = T.p_mat_pk
        inner join material m2 on m2.id = T.mat_pk
        left join unit u on u.id = m2."Unit_id"
        left join mat_produce mpu on mpu.id = T.mp_id
        order by lvl
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
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
            , jr0."WorkOrderNumber" as wo
            , null::int as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , 1 as lvl
            from mat_lot ml        
            -- left 로 둔다 : 시드처럼 생산 차수가 없는 로트도 뿌리로는 남아야 한다
            left join mat_produce mp0 on mp0.id = ml."SourceDataPk"
                                     and ml."SourceTableName" = 'mat_produce'
            left join job_res jr0 on jr0.id = mp0."JobResponse_id"
            where ml."LotNumber" = :lotNumber
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            ,mp.id as mp_id
            , jr."WorkOrderNumber" as wo
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , (t.lvl+1 ) as lvl
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            inner join T on T.lot_number = ml."LotNumber" 
            inner join job_res jr on jr.id=mp."JobResponse_id" 
        )
          -- ★ 이 재귀 CTE 는 내부 칸 이름과 의미가 엇갈린다.
          --   뿌리 행 : l_mat_pk 가 lot_number 의 품목, 부모 없음
          --   재귀 행 : p_mat_pk 가 lot_number 의 품목, l_mat_pk 가 p_lot_number 의 품목
          --   그대로 내리면 재귀 행부터 품목명 두 칸이 서로 바뀜 보인다.
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
          --   생성일로 채우지 않는다 : 한 칸에 두 의미가 섮이면
          --   이 날짜로 회수 범위를 자를 때 조용히 오판한다.
          , to_char(coalesce(mpu."EndTime", mpu."StartTime"), 'yyyy-mm-dd hh24:mi') as used_time
          from T
          left join mat_produce mpu on mpu.id = T.mp_id
          left join material mself on mself.id = coalesce(T.p_mat_pk, T.l_mat_pk)
          left join material mpar  on mpar.id  = (case when T.p_lot_number is null
                                                       then null else T.l_mat_pk end)
          left join unit u on u.id = mpar."Unit_id"
          order by lvl
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getMaterialInoutTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
	  with recursive T as (
         with P as(
            select 
            mp.id as mp_id
            , ''::text as p_lot_number
            , mp."LotNumber" as lot_number
            , jr."Material_id" as mat_pk
            from job_res jr
            left join mat_produce mp on mp."JobResponse_id" = jr.id 
            inner join mat_lot ml on ml."LotNumber" =mp."LotNumber" 
            where mp."LotNumber" = :lotNumber
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
        from P
        union all 
        select 
        T.lot_number as p_lot_number, B.lot_number, B.mp_id, B.mat_pk as mat_pk
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
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
        left join mat_inout mi on mi.id = ml."SourceDataPk"
                              and ml."SourceTableName" = 'mat_inout'
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
            from mat_lot ml
            where ml."LotNumber"= :lotNumber
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            , mp.id as mp_id
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            inner join T on T.lot_number = ml."LotNumber" 
            inner join job_res jr on jr.id=mp."JobResponse_id" 
	        ), pp as ( select lot_number from T group by lot_number)
	        select 
	        pp.lot_number 
	        , m."Name" as mat_name
	        , sh."Company_id" 
	        , c."Name" as company_name
	        , sh."ShipDate" 
	        , s."Qty" 
	        , fn_code_name('shipment_state', sh."State" ) as shipment_state
	        from pp 
	        inner join mat_lot ml on ml."LotNumber" =lot_number
	        inner join material m on m.id = ml."Material_id" 
	        inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id and mlc."SourceTableName" ='shipment'
	        inner join shipment s on s.id=mlc."SourceDataPk" 
	        inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	        left join company c on c.id = sh."Company_id"
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
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