package mes.app.production.service;

import mes.app.inventory.service.LotService;
import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.DateUtil;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 포장(bsc05) 서비스 — v3.3
 *
 * ═══ v2 → v3 에서 바뀐 것 ═══
 *   1) PK 는 세션을 만드는 순간 서버가 자동으로 잡아 DB 에 저장한다(화면 메모리 폐기)
 *   2) 인박스와 CK 를 한 단계로 합쳤다 → 완제 시점이 그 한 곳
 *   3) 라벨 스캔은 완제품이 나온 뒤 별도 단계. 스캔값은 mat_lot."MakerLotNo" 로 들어간다
 *   4) 세션 삭제 시 작지가 「작업 전」(ordered · 수량 0 · 시각 null) 로 되돌아간다
 *
 * ═══ v3 → v3.1 (CK 자체재고 패치) ═══
 *   5) ★ 세션 판별에 LastProcessYN='Y' 를 더했다.
 *      국가별 CK 산출 차수(ckMp)는 'N' 이고 세션 차수는 'Y' 다. 이 구분이 없으면
 *      ckstock 작지에서 두 차수의 Material_id 가 같아 **실적·세션 수가 2배**가 된다.
 *      (kit 세션은 완제품 vs CK 로 품목이 달라 우연히 드러나지 않았다)
 *   6) ★ ckstock 세션의 「생산완료 취소」를 열었다.
 *      packFinish 가 mp 를 finished 로 닫으므로 기존 packWorkCancel 의
 *      "완료된 세션입니다" 가드에 항상 걸려 취소가 불가능했다.
 *   7) ★ ckstock 세션의 산출창고를 생산창고(17)로 고정.
 *      워크센터(bsc05) 산출창고가 제품창고(4)라 반제품을 제품창고에 낸 것처럼 보였다.
 *   8) ★ ckstock 세션은 CK 조달 방식을 항상 'produce' 로 강제.
 *      CK 를 만드는 자리에서 CK 재고를 투입하는 것은 성립하지 않는다.
 *
 * ═══ v3.1 → v3.2 ═══
 *   9) ★★ raw SQL 앞에 entityManager.flush().
 *      recalcJobRes 는 raw SQL 로 mat_produce 를 집계하는데, 호출자가 방금 JPA 로
 *      save 한 차수는 아직 DB 에 없다. 그래서 완료 처리를 해도 작지가
 *      'working · GoodQty 0' 에 머물러 카드가 「생산중 · 진행 0%」로 남았다.
 *      packFinish / outboxFinish / packCancel / packWorkCancel 네 경로가 같은 함정.
 *      같은 이유로 nextCkLotIndex · findLotIdBySource · rollbackProduce · packDelete
 *      에도 flush 를 넣었다.
 *  10) ★ CK 자체재고 생산은 국가를 지정하지 않는다.
 *      pack_alloc."CountryCode" 가 NOT NULL 이라 표식 NO_COUNTRY('-') 를 쓴다.
 *      country."Code" 형식(^[A-Z0-9]{2,6}$)을 통과 못 하는 값이라 실제 국가와 겹치지 않는다.
 *      로그·비고·오류 문구에서는 ckTag/ckPrefix 가 이 표식을 지운다
 *      ("CK 생산(-)" 이 아니라 "CK 생산").
 *
 * ═══ v3.2 → v3.3 (BOM 배수 반영) ★★★ ═══
 *  11) ★★ PK·CK 를 완제품 1개당 1개로 세던 것을 BOM 배수로 바로잡았다.
 *      완제품(FG) BOM 직하위는 PK / CK / IN BOX / OUT BOX 4행인데,
 *      배수를 읽는 곳이 getBoxSpec(박스류) 하나뿐이었다 —
 *      m."Code" LIKE 'M-HS%' 로 걸러서 PK·CK 행이 애초에 들어오지 않았다.
 *      그래서 「인박스 1개당 PK 3 · CK 3」인 품목도 1:1 로 소비·산출됐다.
 *
 *      증상 (완제품 50개 지시, pk_per=3 기준):
 *        · 자동배정이 PK 를 150 이 아니라 50 만 잡음
 *        · 담은 PK 150 을 그대로 완제품 수량으로 써서 GoodQty 가 3배
 *        · 그 값이 recalcJobRes → 작지·헤더 작지까지 3배로 전파
 *        · IN BOX / OUT BOX 도 부풀린 units 기준으로 과소비
 *        · 반대로 CK 배분이 1/3 이라 CK 구성자재 8~12종은 1/3 만 차감
 *
 *      고친 자리 (배수는 getKitSpec 하나에서 나온다):
 *        · getKitSpec()          신규 — 완제품 BOM 에서 pk_per / ck_per 산출
 *        · unitsFromPk()         신규 — PK 합계 ÷ pk_per, 안 떨어지면 null
 *        · autoAssignPkLots()    잡을 PK = 작지 잔여(완제품) × pk_per
 *        · remainOrderQty()      진행 세션의 PK 합계를 ÷ pk_per 해서 완제품 단위로
 *        · packFinish()          units = 담은 PK ÷ pk_per / 배분 검증 = units × ck_per
 *        · savePkLots()          반환 units 를 완제품 환산 (pk_qty 는 원본 그대로)
 *        · packStartNoJob()      계획수량 = PK 합계 ÷ pk_per
 *        · getSessionDetail()    pk_per·ck_per 를 화면에 내려주고 units_planned 환산
 *
 *      ★ CK 구성자재(getCkBom 의 qty_per)는 손대지 않았다 —
 *        그건 「CK 1개당」 기준이라, 배분 수량(=완제품 × ck_per)이 바로잡히면
 *        qty_per × allocQty 가 저절로 맞는다. 여기에 ck_per 를 또 곱하면 3배가 된다.
 *      ★ pk_per 와 ck_per 는 따로 읽는다. 현재 마스터는 두 값이 같지만
 *        (1·2·3·4 네 종류) PK 6 / CK 3 같은 BOM 이 등록돼도 그대로 동작해야 한다.
 *      ★ getBoxSpec 에도 StartDate/EndDate 조건을 넣어 getKitSpec·getCkBom 과
 *        같은 BOM 을 보게 맞췄다. 셋이 다른 BOM 을 보면 배수가 어긋난다.
 *
 * ═══ 공정 흐름 (v3) ═══
 *   ① PK 담기      작지 BOM 의 블리스터(bsc03) 산출 품목을 멸균창고에서 FIFO 자동 배정.
 *                  재고는 안 움직인다. 화면에서 로트·수량 변경 가능
 *   ② 인박스 + CK  PK + IN BOX + CK 를 한 번에 소비 → 완제품 로트(제품창고 4)  ★완제 시점
 *                  국가별로 「새로 생산」 또는 「자체재고 투입」 중 택일
 *   ③ 라벨 스캔    CK·PK 라벨 + 인박스 라벨 2회. 완제품 로트에 MakerLotNo 로 붙는다
 *   ④ 아웃박스     OUT BOX 소비 + 세션 완료. 새 로트 없음
 *
 * ═══ 수량 단위 (v3.3) ═══
 *   완제품(units) 기준으로 말하는 값 : job_res."OrderQty" / mat_produce."GoodQty"
 *                                    / pack_alloc."Qty" ÷ ck_per / remainOrderQty()
 *   PK 낱개 기준으로 말하는 값       : pack_alloc_item(ItemKind='pk')."Qty" / pkTotal()
 *   CK 낱개 기준으로 말하는 값       : pack_alloc."Qty"
 *   ★ 두 단위를 섞으면 실적이 배수만큼 어긋난다. 새 코드를 넣을 때 어느 쪽인지 먼저 정할 것.
 *
 * ═══ 단계 파생 (getPhase / PHASE_SQL) ═══
 *   mat_produce."State"='finished'          → done
 *   pack_label(ckpk/inbox) 존재              → outbox   (라벨까지 끝)
 *   pack_alloc."CkState"='produced' 존재     → label    (완제품 나옴)
 *   pack_alloc_item(ItemKind='pk') 존재      → pack     (PK 담김)
 *   그 외                                    → pk
 *
 *   ★ 단계를 저장하지 않으므로 화면 단계와 실제 재고가 어긋날 수 없다.
 *
 * ═══ 왜 인박스와 CK 를 합쳤나 ═══
 *   v2 는 인박스에서 PK·IN BOX 를 소비하고 CK 에서 완제품을 냈다. 그래서 CK 취소가
 *   rollbackProduce 로 인박스 소비까지 풀어버려, 되돌린 뒤 다시 넣어주는 코드가 필요했다
 *   (keepCons 재삽입). 실물로도 인박스에 넣는 순간 CK 가 같이 들어가므로 두 단계를
 *   나눌 이유가 없었다. 합치면 취소가 rollbackProduce 한 줄로 끝난다.
 *
 * ═══ 세션 종류 ═══
 *   kit     완제품 세션 — 작지에 부모(완제품 헤더)가 있다. ①~④ 전체를 탄다
 *   ckstock CK 자체재고 세션 — Parent_id IS NULL + 산출품이 semi.
 *           ② 에서 CK 만 만들어 생산창고(17)에 재고로 남기고 끝난다
 */
@Service
public class PackService {

	/** 자재창고 */
	public static final int STORE_MAT       = 3;
	/** 제품창고 S-2 — 완제 키트 산출 */
	public static final int STORE_PRODUCT   = 4;
	/** 클린룸 */
	public static final int STORE_CLEAN     = 5;
	/** 생산창고 S-7 — CK 반제품 산출 + CK 자재 투입 */
	public static final int STORE_PROD      = 17;
	/** 멸균창고 — 멸균필(PK·필터팩) 투입 */
	public static final int STORE_STERIL    = 18;
	/** 검사완료창고(2공장) — 1공장 박스류 재고가 여기 섞여 있어 폴백에 포함 */
	public static final int STORE_INSPECTED = 19;

	/**
	 * CK 자재·박스류 투입 창고 우선순위.
	 *
	 * ★ 17 고정으로는 안 된다. IN BOX(M-HS00006)가 창고 19 에만 있어
	 *   17 고정 FIFO 는 항상 '재고 부족'으로 실패한다.
	 *   「생산창고 것만 쓴다」로 좁히려면 3·19 의 1공장 자재를 17 로 먼저 이관해야 한다.
	 *   그때 이 배열을 { STORE_PROD } 한 줄로 줄이면 된다 — 다른 곳은 손댈 필요 없다.
	 */
	private static final int[] CK_SRC_STORES = { STORE_PROD, STORE_MAT, STORE_INSPECTED };

	/** CK 반제품 산출창고 — work_center(57) 는 완제품 기준 4 라서 여기서 고정한다 */
	public static final int STORE_CK_OUT = STORE_PROD;

	/**
	 * 국가 미지정 배분 코드.
	 *
	 * ★ CK 자체재고 생산은 국가를 정하지 않는다. 그런데 pack_alloc."CountryCode" 가
	 *   NOT NULL 이라 빈 값을 넣을 수 없어 표식 하나를 쓴다.
	 *   '-' 는 country."Code" 형식(^[A-Z0-9]{2,6}$)을 통과하지 못하므로
	 *   실제 국가 코드와 절대 겹치지 않는다.
	 *   화면(prod_process_pack_t.html)의 NO_CTRY 와 같은 값이어야 한다.
	 */
	public static final String NO_COUNTRY = "-";

	/**
	 * UDI 라벨 스캔 필수 여부.
	 *
	 * ★ 화면에 「라벨 스캔」 단계를 띄울지 여부만 결정한다(context.require_label).
	 *   labelScan() 의 «거절» 조건으로는 더 이상 쓰지 않는다 — 라벨 내용 검증은
	 *   전부 경고로 내렸다. 한쪽만 스캔돼도 저장은 되고 경고만 남는다.
	 */
	private static final boolean REQUIRE_UDI_LABEL = true;

	/**
	 * 라벨 «내용» 검증을 거절로 다룰지 여부.
	 *
	 *   true  운영 — 어긋나면 저장을 막는다. 다른 물건을 포장했다는 신호이므로
	 *                여기서 잡지 않으면 틀린 UDI 가 그대로 완제품 로트에 박힌다.
	 *   false 테스트 — 경고만 남기고 통과. 마스터·시드가 실물 라벨과 안 맞는 동안 쓴다.
	 *
	 * ★ 어느 쪽이든 «검사 결과» 는 result.data.warnings 로 항상 내려간다.
	 *   거절 모드에서는 그중 첫 줄이 result.message 가 된다.
	 */
	private static final boolean STRICT_LABEL_MATCH = false;

	/**
	 * 카톤 로트 접두.
	 *
	 * ★ 'LI'(발주 입고) 채널을 쓰자는 얘기가 있었으나, 그러면 번호만 보고
	 *   입고인지 카톤인지 구분이 안 된다. 별도 접두로 분리한다.
	 *   CK 폴백 채번('CK')과도 겹치지 않게 둘 것.
	 */
	private static final String CARTON_LOT_PREFIX = "C";

	private static final float EPS = 0.0001f;

	/**
	 * 단계 파생식. mp 별칭이 mat_produce 를 가리키는 곳에 그대로 끼워 쓴다.
	 *
	 * ※ 상수 문자열이지만 텍스트 블록 결합(+)은 하지 않는다 —
	 *   여는 구분자 뒤에 줄바꿈만 허용해서 편집할 때마다 깨진다.
	 *   String.replace 로 치환한다.
	 */
	private static final String PHASE_SQL = """
        CASE WHEN mp."State" = 'finished' THEN 'done'
             WHEN EXISTS (SELECT 1 FROM pack_label pl
                           WHERE pl."MatProduce_id" = mp.id
                             AND pl."LabelKind" IN ('ckpk','inbox')) THEN 'outbox'
             WHEN EXISTS (SELECT 1 FROM pack_alloc pa
                           WHERE pa."MatProduce_id" = mp.id
                             AND COALESCE(pa."CkState",'plan') = 'produced') THEN 'label'
             WHEN EXISTS (SELECT 1 FROM pack_alloc_item pai
                           WHERE pai."MatProduce_id" = mp.id
                             AND COALESCE(pai._status,'a') = 'a'
                             AND COALESCE(pai."ItemKind",'ck') = 'pk') THEN 'pack'
             ELSE 'pk' END
        """;

	@Autowired SqlRunner  sqlRunner;
	@Autowired LotService lotService;

	@Autowired JobResRepository     jobResRepository;
	@Autowired MatProduceRepository matProduceRepository;
	@Autowired MatLotRepository     matLotRepository;
	@Autowired MatLotConsRepository matLotConsRepository;
	@Autowired MatConsuRepository   matConsuRepository;
	@Autowired MatInoutRepository   matInoutRepository;
	@Autowired MaterialRepository   materialRepository;
	@Autowired WorkcenterRepository workcenterRepository;
	@Autowired EquRunRepository     equRunRepository;

	/**
	 * ★ 이 서비스는 JPA(save)와 raw SQL(sqlRunner)을 섞어 쓴다.
	 *   JPA save 는 영속성 컨텍스트에만 올라가 있어, 곧이어 도는 raw SQL 이
	 *   그 행을 못 본다. 집계·되짚기 직전에 flush 로 DB 에 내려보낸다.
	 *   ※ 이 프로젝트는 Spring Boot 2 → javax.persistence (jakarta 아님)
	 */
	@PersistenceContext
	private EntityManager entityManager;

	private static final DateTimeFormatter DF  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TF  = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter DTM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	// =========================================================================
	// 컨텍스트
	// =========================================================================

	public Map<String, Object> getContext(Integer factoryId, String spjangcd) {
		Map<String, Object> data = new HashMap<>();

		Map<String, Object> wc = this.sqlRunner.getRow("""
            SELECT wc.id AS wc_id, wc."Process_id" AS process_id,
                   wc."ProcessStoreHouse_id" AS out_store
              FROM work_center wc
              JOIN process p ON p.id = wc."Process_id"
             WHERE p."Code" = 'bsc05' AND COALESCE(wc._status,'a') = 'a'
             ORDER BY wc.id LIMIT 1
            """, new MapSqlParameterSource());

		Integer wcId = null;
		if (wc != null) {
			wcId = toInt(wc.get("wc_id"));
			data.put("work_center_id", wcId);
			data.put("process_id",     wc.get("process_id"));
		}

		data.put("equipments",       getEquipments(wcId, spjangcd));
		data.put("steril_store_id",  STORE_STERIL);
		data.put("prod_store_id",    STORE_PROD);
		data.put("product_store_id", STORE_PRODUCT);
		data.put("ck_out_store_id",  STORE_CK_OUT);
		data.put("no_country",       NO_COUNTRY);
		data.put("require_label",    REQUIRE_UDI_LABEL);
		data.put("strict_label",     STRICT_LABEL_MATCH);   // 화면 안내 문구가 갈린다
		return data;
	}

	public List<Map<String, Object>> getEquipments(Integer workCenterId, String spjangcd) {
		if (workCenterId == null) return Collections.emptyList();
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("wcId", workCenterId);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);

		return this.sqlRunner.getRows("""
            SELECT e.id AS equipment_id, e."Code" AS code, e."Name" AS name,
                   e."Status" AS status
              FROM equ e
             WHERE e."WorkCenter_id" = :wcId
               AND COALESCE(e._status,'a') <> 'd'
               AND e."DisposalDate" IS NULL
               AND (CAST(:spjangcd AS varchar) IS NULL OR e.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY e."Code", e.id
            """, p);
	}

	public List<Map<String, Object>> getWorkers(Integer factoryId, String keyword, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("factoryId", factoryId == null ? 1 : factoryId);
		p.addValue("keyword",  isBlank(keyword)  ? null : "%" + keyword + "%");
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);

		return this.sqlRunner.getRows("""
            SELECT p.id AS worker_id, p."Name" AS name, p."Code" AS code, d."Name" AS dept_name
              FROM person p
              LEFT JOIN depart d ON d.id = p."Depart_id"
             WHERE p."Factory_id" = :factoryId
               AND COALESCE(p._status,'a') <> 'd'
               AND (p."Exitdate" IS NULL OR p."Exitdate" > CURRENT_DATE)
               AND (CAST(:spjangcd AS varchar) IS NULL OR p.spjangcd = CAST(:spjangcd AS varchar))
               AND (CAST(:keyword AS varchar) IS NULL
                    OR p."Name" LIKE CAST(:keyword AS varchar)
                    OR p."Code" LIKE CAST(:keyword AS varchar))
             ORDER BY p."Name"
            """, p);
	}

	// =========================================================================
	// 멸균필 로트 (= 멸균창고 재고)
	// =========================================================================

	/**
	 * 포장 투입 후보 = 멸균창고(18) 잔여 로트.
	 *
	 * @param kind 'pk'(블리스터 산출) | 'bag'(융착 필터백) | null 전체
	 *
	 * avail = CurrentStock − (다른 세션이 ①에서 담아두고 아직 ②로 소비하지 않은 예약분)
	 *
	 *   ★ 종류 판별은 SterilizationYN 으로 못 한다. PK 가 멸균 대기목록에 뜨려면
	 *     SterilizationYN='Y' 여야 하고, 그러면 필터백과 값이 같아진다.
	 *     PK = 블리스터(bsc03) 산출 / 필터백 = 융착(bsc06) 산출 → 산출 공정으로 나눈다.
	 *     판별 기준을 바꾸려면 아래 CASE 두 곳(SELECT·WHERE)을 함께 고칠 것.
	 *
	 *   ★ v3 에서는 PK 가 세션 생성과 동시에 담기므로 예약분이 늘 존재한다.
	 *     자기 세션(excludeMpId)은 가용으로 친다.
	 */
	public List<Map<String, Object>> getSterilizedLots(String kind, Integer materialId,
																										 String keyword, String spjangcd,
																										 Integer excludeMpId, boolean excludeOrdered) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sterilStore", STORE_STERIL);
		p.addValue("kind",     isBlank(kind)    ? null : kind);
		p.addValue("matId",    materialId);
		p.addValue("keyword",  isBlank(keyword) ? null : "%" + keyword + "%");
		p.addValue("spjangcd", isBlank(spjangcd)? null : spjangcd);
		p.addValue("exMp",     excludeMpId);
		p.addValue("exclOrdered", excludeOrdered);

		return this.sqlRunner.getRows("""
            SELECT ml.id                                    AS mat_lot_id
                 , ml."LotNumber"                           AS lot_no
                 , ml."MakerLotNo"                          AS udi_lot
                 , ml."Material_id"                         AS mat_id
                 , m."Code"                                 AS mat_code
                 , m."Name"                                 AS mat_name
                 , u."Name"                                 AS unit
                 , COALESCE(ml."CurrentStock",0)            AS stock
                 , GREATEST(COALESCE(ml."CurrentStock",0) - COALESCE(rp.rq,0), 0) AS avail
                 , to_char(ml."EffectiveDate",'yyyy-mm-dd') AS expiry
                 , to_char(ml."InputDateTime",'yyyy-mm-dd') AS steril_date
                 , sb."BatchNo"                             AS batch_no
                 , CASE kpr."Code" WHEN 'bsc06' THEN 'bag'
                                   WHEN 'bsc03' THEN 'pk'
                                   ELSE 'etc' END           AS kind
              FROM mat_lot ml
              JOIN material m           ON m.id   = ml."Material_id"
              LEFT JOIN unit u          ON u.id   = m."Unit_id"
              LEFT JOIN work_center kwc ON kwc.id = m."WorkCenter_id"
              LEFT JOIN process     kpr ON kpr.id = kwc."Process_id"
              LEFT JOIN steril_batch_item sbi
                     ON ml."SourceTableName" = 'steril_batch_item' AND sbi.id = ml."SourceDataPk"
              LEFT JOIN steril_batch sb ON sb.id = sbi."SterilBatch_id"
              -- 다른 세션이 ①에서 담아두고 아직 ②로 소비하지 않은 PK 수량
              LEFT JOIN LATERAL (
                  SELECT COALESCE(SUM(pai."Qty"),0) AS rq
                    FROM pack_alloc_item pai
                    JOIN mat_produce mp2 ON mp2.id = pai."MatProduce_id"
                   WHERE pai."MatLot_id" = ml.id
                     AND COALESCE(pai._status,'a') = 'a'
                     AND COALESCE(pai."ItemKind",'ck') = 'pk'
                     AND mp2."State" = 'working'
                     AND NOT EXISTS (SELECT 1 FROM pack_alloc pa2
                                      WHERE pa2."MatProduce_id" = mp2.id
                                        AND COALESCE(pa2."CkState",'plan') = 'produced')
                     AND (CAST(:exMp AS integer) IS NULL OR mp2.id <> CAST(:exMp AS integer))
              ) rp ON true
             WHERE ml."StoreHouse_id" = :sterilStore
               AND COALESCE(ml."CurrentStock",0) > 0
               -- ★ A화면 대기 카드에서만: 이미 포장 작지가 걸린 PK 품목은 뺀다.
               --   작지로 관리되는 물건을 낱개 카드로 또 띄우면 같은 걸 두 경로로 시작하게 된다.
               --   (:exclOrdered=false 인 담기 시트에서는 그대로 다 보여준다)
               AND (NOT :exclOrdered OR NOT EXISTS (
                     SELECT 1
                       FROM job_res jr2
                       JOIN work_center wc2 ON wc2.id = jr2."WorkCenter_id"
                       JOIN process     pr2 ON pr2.id = wc2."Process_id"
                       JOIN job_res     h2  ON h2.id  = jr2."Parent_id"
                       JOIN bom      b2  ON b2."Material_id" = h2."Material_id"
                                        AND COALESCE(b2._status,'a') <> 'd'
                       JOIN bom_comp bc2 ON bc2."BOM_id" = b2.id
                                        AND COALESCE(bc2._status,'a') <> 'd'
                      WHERE pr2."Code" = 'bsc05'
                        AND jr2."State" IN ('ordered','wait','working','stopped')
                        AND bc2."Material_id" = ml."Material_id"))
               AND (CAST(:spjangcd AS varchar) IS NULL OR ml.spjangcd = CAST(:spjangcd AS varchar))
               AND (CAST(:kind AS varchar) IS NULL
                    OR (CASE kpr."Code" WHEN 'bsc06' THEN 'bag'
                                        WHEN 'bsc03' THEN 'pk'
                                        ELSE 'etc' END) = CAST(:kind AS varchar))
               AND (CAST(:matId AS integer) IS NULL OR ml."Material_id" = CAST(:matId AS integer))
               AND (CAST(:keyword AS varchar) IS NULL
                    OR m."Name"        LIKE CAST(:keyword AS varchar)
                    OR m."Code"        LIKE CAST(:keyword AS varchar)
                    OR ml."LotNumber"  LIKE CAST(:keyword AS varchar)
                    OR ml."MakerLotNo" LIKE CAST(:keyword AS varchar))
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
            """, p);
	}

	// =========================================================================
	// 작업 목록 (화면 A)
	// =========================================================================

	/**
	 * 포장 작지 목록.
	 *
	 * ※ 공용 /read_by_process 를 안 쓰는 이유:
	 *   포장 카드는 단계 진행률과 「작지 없음」 배지를 보여줘야 한다.
	 *   대신 작지 필터 규칙(Parent_id NOT NULL, 워크센터 조인, 완료 포함 여부)은
	 *   getJobResByProcess 와 글자 그대로 맞춰 두었다. 한쪽만 고치면 목록이 갈린다.
	 *
	 * ★ 목록에 뜨는 조건 = jr."WorkCenter_id" 가 bsc05 워크센터.
	 *   [작업 지시 등록(수주)] 은 explodeProcessRows 가 bsc05 자식 작지를 만들어 준다.
	 *   [자체 재고 생산 지시] 는 CK 품목에 라우팅이 있으면 라우팅 마지막 공정의 워크센터,
	 *   없으면 화면에서 고른 워크센터를 쓴다(ProdOrderAController.saveProdOrderA).
	 *   → CK 지시가 목록에 안 뜨면 그 작지의 WorkCenter_id 부터 확인할 것.
	 */
	public List<Map<String, Object>> getOrderList(String dateFrom, String dateTo,
																								String item, boolean includeComp,
																								String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("dateFrom", dateFrom);
		p.addValue("dateTo",   dateTo);
		p.addValue("item",     item == null ? "" : item.trim());
		p.addValue("includeComp", includeComp);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);

		return this.sqlRunner.getRows("""
            WITH proc_wc AS (
                SELECT wc.id AS wc_id, p.id AS proc_id
                  FROM work_center wc JOIN process p ON p.id = wc."Process_id"
                 WHERE p."Code" = 'bsc05'
            ), rows AS (
            SELECT jr.id                        AS id
                 , jr."WorkOrderNumber"         AS order_num
                 , jr."State"                   AS state
                 /* ★ 지시량은 «완제품 단위» 다.
                      jr 은 포장 자식 작지이고 그 Material_id 는 CK 반제품이라,
                      OrderQty 도 CK 낱개(= 완제품 × ck_per)로 들어 있다.
                      그대로 쓰면 완제품 11개 지시가 카드에 «33» 으로 뜬다.
                      부모(완제품 헤더)의 지시량이 곧 완제품 수량이므로 그쪽을 쓴다.
                    ★ ckstock(CK 자체재고) 지시는 부모가 없다 — 그때는 CK 가 산출물
                      자체라 jr 값이 맞는다. COALESCE 가 두 경우를 함께 받는다. */
                 , COALESCE(hdr."OrderQty", jr."OrderQty") AS order_qty
                 /* ★ 완료 수량은 «차수 합계» 를 쓴다(아래 s.units).
                      jr."GoodQty" 는 recalcJobRes 가 채우는 «집계 결과» 인데,
                      부모·자식 작지가 서로를 갱신하는 경로가 겹치면 두 번 더해진다 —
                      카드엔 20, 상세엔 10 으로 갈렸다(같은 차수 1건짜리 작지에서).
                      상세 화면(getSessionList)은 차수를 직접 세므로,
                      카드도 같은 출처를 봐야 두 화면이 어긋나지 않는다.
                    ★ s 는 아래 LATERAL 에서 세션 차수만 골라 합산한다
                      (LastProcessYN='Y' + 산출품 일치) — 국가별 CK 산출 차수는 빠진다. */
                 , COALESCE(s.units, 0)         AS good_qty
                 , jr."DefectQty"               AS defect_qty
                 , jr."ProductionDate"          AS prod_date
                 , jr."Material_id"             AS wip_material_id   -- 이 공정 WIP(=CK)
                 , tm.id                        AS product_mat_id    -- 산출 품목
                 , tm."Code"                    AS mat_code
                 , tm."Name"                    AS mat_name
                 , u."Name"                     AS unit
                 , wc."Name"                    AS workcenter_name
                 -- ★ 세션 종류 : 부모가 없고 품목이 반제품이면 CK 자체재고 지시다.
                 --   자체재고로 '완제품'을 지시한 건도 Parent_id 가 없으므로 품목 유형을 함께 본다.
                 , CASE WHEN jr."Parent_id" IS NULL AND tmg."MaterialType" = 'semi'
                        THEN 'ckstock' ELSE 'kit' END AS job_kind
                 -- ★ 출처 배지
                 --   'suju'  → 수주 지시 (ProdOrderEditService 가 헤더에 남긴다)
                 --   'pack'  → 이 화면이 자동발행한 무작지 건
                 --   NULL    → 자체재고 지시 (ProdOrderAController 는 출처를 안 남긴다)
                 , CASE WHEN COALESCE(hdr."SourceTableName", jr."SourceTableName") = 'pack' THEN 'auto'
                        WHEN COALESCE(hdr."SourceTableName", jr."SourceTableName") IS NULL  THEN 'stock'
                        ELSE 'order' END        AS order_src
                 -- 한 부모에 bsc05 자식이 여럿일 때 대표 1건만 남기기 위한 순번.
                 -- explodeProcessRows 가 워크센터 있는 semi·product 를 전부 자식으로 만들어서
                 -- 완제품(FG)과 CK 가 나란히 포장 공정에 걸린다. 산출품과 같은 쪽을 대표로 삼는다.
                 , ROW_NUMBER() OVER (
                     PARTITION BY COALESCE(jr."Parent_id", jr.id)
                     ORDER BY CASE WHEN jr."Material_id" = COALESCE(hdr."Material_id", jr."Material_id")
                                   THEN 0 ELSE 1 END, jr.id) AS rn
                 , COALESCE(hdr."SourceTableName", jr."SourceTableName") AS src_table
                 , CASE jr."State"
                     WHEN 'working'  THEN '생산중'
                     WHEN 'finished' THEN '생산완료'
                     WHEN 'stopped'  THEN '일시중지'
                     WHEN 'wait'     THEN '대기'
                     ELSE '작업지시' END        AS job_state
                 -- 작지 없이 시작한 건인지
                 , CASE WHEN COALESCE(hdr."SourceTableName",'') = 'pack'
                        THEN 'Y' ELSE 'N' END   AS no_job_yn
                 , COALESCE(s.sess_cnt, 0)      AS sess_cnt
                 , COALESCE(s.working_cnt, 0)   AS working_cnt
                 , COALESCE(s.units, 0)         AS packed_units
                 , s.phases                     AS phases
              FROM job_res jr
              JOIN proc_wc pw          ON pw.wc_id = jr."WorkCenter_id"
              LEFT JOIN job_res hdr    ON hdr.id = jr."Parent_id"
              -- 산출 품목 = 부모가 있으면 완제품, 없으면(CK 자체재고) 자기 품목
              LEFT JOIN material tm    ON tm.id  = COALESCE(hdr."Material_id", jr."Material_id")
              LEFT JOIN mat_grp tmg    ON tmg.id = tm."MaterialGroup_id"
              LEFT JOIN unit u         ON u.id   = tm."Unit_id"
              LEFT JOIN work_center wc ON wc.id  = jr."WorkCenter_id"
              LEFT JOIN LATERAL (
                  SELECT COUNT(*) AS sess_cnt
                       , COUNT(*) FILTER (WHERE mp."State" = 'working') AS working_cnt
                       , COALESCE(SUM(mp."GoodQty"),0) AS units
                       , string_agg(DISTINCT (__PHASE__), ',') AS phases
                    FROM mat_produce mp
                   WHERE mp."JobResponse_id" = jr.id
                     AND COALESCE(mp._status,'a') = 'a'
                     -- ★ 세션 차수만. 국가별 CK 산출 차수(ckMp)는 LastProcessYN='N' 이다.
                     --   빼지 않으면 ckstock 작지에서 품목이 같아 실적·세션 수가 2배가 된다.
                     AND COALESCE(mp."LastProcessYN",'Y') = 'Y'
                     AND COALESCE(mp."Material_id",0) = COALESCE(tm.id,0)   -- 산출품 세션만
              ) s ON true
             WHERE (jr."ProductionDate" BETWEEN CAST(:dateFrom AS date) AND CAST(:dateTo AS date)
                    OR jr."ProductionDate" IS NULL)
               AND (:item = '' OR tm."Code" ILIKE '%' || :item || '%'
                             OR tm."Name" ILIKE '%' || :item || '%')
               AND (:includeComp OR jr."State" <> 'finished')
               AND (CAST(:spjangcd AS varchar) IS NULL OR jr.spjangcd = CAST(:spjangcd AS varchar))
            )
            SELECT * FROM rows
             WHERE rn = 1
             ORDER BY CASE state WHEN 'working' THEN 1 WHEN 'stopped' THEN 2
                                 WHEN 'finished' THEN 4 ELSE 3 END
                    , order_num DESC, mat_code
            """.replace("__PHASE__", PHASE_SQL), p);
	}

	/** 무작지 진입 시 고를 완제품 후보 — 1공장 완제품 + 그 CK 반제품 */
	public List<Map<String, Object>> getProductCandidates(String keyword, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("keyword",  isBlank(keyword)  ? null : "%" + keyword + "%");
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);
		return this.sqlRunner.getRows("""
            SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                 , u."Name" AS unit
                 -- 이 완제품의 CK 반제품 (BOM 직하위 semi 중 포장 워크센터 산출)
                 , (SELECT bc."Material_id" FROM bom b
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                      JOIN material cm ON cm.id = bc."Material_id"
                      JOIN work_center wc2 ON wc2.id = cm."WorkCenter_id"
                      JOIN process pr2 ON pr2.id = wc2."Process_id"
                     WHERE b."Material_id" = m.id
                       AND COALESCE(b._status,'a') <> 'd'
                       AND pr2."Code" = 'bsc05'
                     ORDER BY bc._order LIMIT 1)          AS ck_mat_id
                 -- 이 완제품의 PK 품목 (BOM 직하위 중 블리스터 산출)
                 , (SELECT bc."Material_id" FROM bom b
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                      JOIN material cm ON cm.id = bc."Material_id"
                      JOIN work_center wc3 ON wc3.id = cm."WorkCenter_id"
                      JOIN process pr3 ON pr3.id = wc3."Process_id"
                     WHERE b."Material_id" = m.id
                       AND COALESCE(b._status,'a') <> 'd'
                       AND pr3."Code" = 'bsc03'
                     ORDER BY bc._order LIMIT 1)          AS pk_mat_id
              FROM material m
              JOIN mat_grp mg  ON mg.id = m."MaterialGroup_id"
              LEFT JOIN unit u ON u.id  = m."Unit_id"
             WHERE mg."MaterialType" = 'product'
               AND COALESCE(m."Factory_id",1) = 1
               AND COALESCE(m._status,'a') <> 'd'
               AND (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
               AND (CAST(:keyword AS varchar) IS NULL
                    OR m."Name" LIKE CAST(:keyword AS varchar)
                    OR m."Code" LIKE CAST(:keyword AS varchar))
             ORDER BY m."Code"
            """, p);
	}

	// =========================================================================
	// CK BOM / 박스 규격 / 키트 배수
	// =========================================================================

	/**
	 * CK 반제품의 구성자재.
	 *
	 * ★ 완제품 BOM 이 아니라 CK 반제품 BOM 을 본다.
	 *   완제품(FG) BOM 직하위는 PK / CK / IN BOX / OUT BOX 4행뿐이고,
	 *   실제 포장에서 투입하는 주사기·니들·스티커 12종은 CK 반제품 BOM 에 있다.
	 *
	 * ★ qty_per 는 「CK 1개당」 이다 — 완제품 1개당이 아니다.
	 *   그래서 호출부는 qty_per × 배분수량(=완제품 × ck_per) 으로 계산해야 한다.
	 *   여기에 ck_per 를 또 곱하면 배수만큼 과소비된다(v3.3 에서 확인).
	 */
	public List<Map<String, Object>> getCkBom(Integer ckMaterialId, Integer jrPk, String spjangcd) {
		if (ckMaterialId == null && jrPk != null) ckMaterialId = resolveCkMaterial(jrPk);
		if (ckMaterialId == null) return Collections.emptyList();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("ckMatId",     ckMaterialId);
		p.addValue("sterilStore", STORE_STERIL);
		p.addValue("prodStore",   STORE_PROD);
		p.addValue("matStore",    STORE_MAT);
		p.addValue("inspStore",   STORE_INSPECTED);
		p.addValue("cleanStore",  STORE_CLEAN);
		p.addValue("spjangcd",    isBlank(spjangcd) ? null : spjangcd);

		return this.sqlRunner.getRows("""
            WITH b AS (
                SELECT bo.id, NULLIF(bo."OutputAmount",0) AS out_amt
                  FROM bom bo
                 WHERE bo."Material_id" = :ckMatId
                   AND COALESCE(bo._status,'a') <> 'd'
                   AND (bo."StartDate" IS NULL OR bo."StartDate" <= now())
                   AND (bo."EndDate"   IS NULL OR bo."EndDate"   >= now())
                   AND (CAST(:spjangcd AS varchar) IS NULL OR bo.spjangcd = CAST(:spjangcd AS varchar))
                 ORDER BY bo."StartDate" DESC NULLS LAST, bo.id DESC
                 LIMIT 1
            )
            SELECT c."Material_id"                    AS mat_id
                 , m."Code"                           AS mat_code
                 , m."Name"                           AS mat_name
                 , u."Name"                           AS unit
                 , c."Amount" / COALESCE(b.out_amt,1) AS qty_per
                 , c._order                           AS sort_no
                 -- 필터백 = 융착 산출 → 멸균창고 로트 지정
                 , CASE WHEN kpr."Code" = 'bsc06' THEN 'Y' ELSE 'N' END AS sterile_yn
                 , COALESCE(m."WashYN",'N')           AS wash_yn
                 , COALESCE(st.prod,   0)             AS prod_stock
                 , COALESCE(st.mat,    0)             AS mat_stock
                 , COALESCE(st.insp,   0)             AS insp_stock
                 , COALESCE(st.clean,  0)             AS clean_stock
                 , COALESCE(st.steril, 0)             AS steril_stock
                 , CASE WHEN kpr."Code" = 'bsc06' THEN COALESCE(st.steril,0)
                        ELSE COALESCE(st.prod,0) + COALESCE(st.mat,0) + COALESCE(st.insp,0)
                   END                                AS usable_stock
              FROM bom_comp c
              JOIN b           ON b.id  = c."BOM_id"
              JOIN material m  ON m.id  = c."Material_id"
              LEFT JOIN unit u ON u.id  = m."Unit_id"
              LEFT JOIN work_center kwc ON kwc.id = m."WorkCenter_id"
              LEFT JOIN process     kpr ON kpr.id = kwc."Process_id"
              LEFT JOIN LATERAL (
                  SELECT COALESCE(SUM(CASE WHEN ml."StoreHouse_id" = :prodStore   THEN ml."CurrentStock" END),0) AS prod
                       , COALESCE(SUM(CASE WHEN ml."StoreHouse_id" = :matStore    THEN ml."CurrentStock" END),0) AS mat
                       , COALESCE(SUM(CASE WHEN ml."StoreHouse_id" = :inspStore   THEN ml."CurrentStock" END),0) AS insp
                       , COALESCE(SUM(CASE WHEN ml."StoreHouse_id" = :cleanStore  THEN ml."CurrentStock" END),0) AS clean
                       , COALESCE(SUM(CASE WHEN ml."StoreHouse_id" = :sterilStore THEN ml."CurrentStock" END),0) AS steril
                    FROM mat_lot ml
                   WHERE ml."Material_id" = c."Material_id"
                     AND COALESCE(ml."CurrentStock",0) > 0
              ) st ON true
             WHERE COALESCE(c._status,'a') <> 'd'
             ORDER BY c._order NULLS LAST, m."Code"
            """, p);
	}

	/**
	 * 완제품 BOM 에서 박스류 입수량 — IN BOX 1.0→1개당1, OUT BOX 0.25→카톤 4입.
	 *
	 * ★ v3.3 : StartDate/EndDate 조건을 넣어 getKitSpec·getCkBom 과 같은 BOM 을 보게 맞췄다.
	 *   셋이 다른 BOM 을 집으면 「units 는 A BOM, IN BOX 는 B BOM」이 되어
	 *   배수 오류보다 훨씬 찾기 어려운 어긋남이 생긴다.
	 */
	public Map<String, Object> getBoxSpec(Integer productMatId, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", productMatId);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);

		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            WITH b AS (
                SELECT bo.id, NULLIF(bo."OutputAmount",0) AS out_amt
                  FROM bom bo
                 WHERE bo."Material_id" = :matId
                   AND COALESCE(bo._status,'a') <> 'd'
                   AND (bo."StartDate" IS NULL OR bo."StartDate" <= now())
                   AND (bo."EndDate"   IS NULL OR bo."EndDate"   >= now())
                   AND (CAST(:spjangcd AS varchar) IS NULL OR bo.spjangcd = CAST(:spjangcd AS varchar))
                 ORDER BY bo."StartDate" DESC NULLS LAST, bo.id DESC LIMIT 1
            )
            SELECT c."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                 , c."Amount" / COALESCE(b.out_amt,1) AS qty_per
              FROM bom_comp c
              JOIN b ON b.id = c."BOM_id"
              JOIN material m ON m.id = c."Material_id"
             WHERE COALESCE(c._status,'a') <> 'd'
               AND m."Code" LIKE 'M-HS%'
             ORDER BY m."Code"
            """, p);

		Map<String, Object> out = new HashMap<>();
		out.put("inbox",  null);
		out.put("outbox", null);
		out.put("outbox_cap", 4f);   // 폴백

		for (Map<String, Object> r : rows) {
			String name = str(r.get("mat_name"));
			float per   = toFloat(r.get("qty_per"));
			boolean isOut = name != null && name.toUpperCase().contains("OUT");
			if (isOut) {
				out.put("outbox", r);
				// 카톤 1개당 인박스 = 1 / 개당수량 (0.25 → 4)
				if (per > 0) out.put("outbox_cap", (float) Math.round(1f / per));
			} else {
				out.put("inbox", r);
			}
		}
		return out;
	}

	/**
	 * 완제품 1개당 PK · CK 소요 배수. ★v3.3 신규
	 *
	 * ★ getBoxSpec 은 m."Code" LIKE 'M-HS%' 로 박스류만 걸러내므로 PK·CK 가 안 잡힌다.
	 *   그래서 v3.2 까지는 배수 3 이 시스템 어디에도 존재하지 않았고, PK·CK 가 1:1 로 돌았다.
	 *
	 * ★ 판별은 코드 접두가 아니라 산출 공정으로 한다 —
	 *   PK = 블리스터(bsc03) 산출 / CK = 포장(bsc05) 산출.
	 *   같은 BOM 에 PK 가 여러 행이면(차수 분리) 합산한다.
	 *   ※ 다만 resolvePkMaterial 은 첫 행만 집으므로, PK 가 복수 품목이면
	 *     자동배정이 반쪽이 된다. 현재 마스터는 전부 단일 행이라 문제 없다.
	 *
	 * ★ pk_per 와 ck_per 는 반드시 따로 읽는다. 지금은 두 값이 같지만
	 *   (마스터 전수 확인 결과 1·2·3·4 네 종류, 모두 pk=ck)
	 *   PK 6 / CK 3 같은 BOM 이 들어와도 그대로 동작해야 한다.
	 *
	 * @return pk_per / ck_per (기본 1) + pk_found / ck_found (BOM 에 실제로 있었으면 1)
	 *         ★ found 를 함께 주는 이유 : BOM 누락을 조용히 1:1 로 흘려보내면
	 *           v3.2 와 똑같은 증상이 재발하고 원인 추적이 어렵다.
	 */
	public Map<String, Float> getKitSpec(Integer productMatId, String spjangcd) {
		Map<String, Float> out = new HashMap<>();
		out.put("pk_per", 1f);
		out.put("ck_per", 1f);
		out.put("pk_found", 0f);
		out.put("ck_found", 0f);
		if (productMatId == null) return out;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", productMatId);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);

		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            WITH b AS (
                SELECT bo.id, NULLIF(bo."OutputAmount",0) AS out_amt
                  FROM bom bo
                 WHERE bo."Material_id" = :matId
                   AND COALESCE(bo._status,'a') <> 'd'
                   AND (bo."StartDate" IS NULL OR bo."StartDate" <= now())
                   AND (bo."EndDate"   IS NULL OR bo."EndDate"   >= now())
                   AND (CAST(:spjangcd AS varchar) IS NULL OR bo.spjangcd = CAST(:spjangcd AS varchar))
                 ORDER BY bo."StartDate" DESC NULLS LAST, bo.id DESC LIMIT 1
            )
            SELECT CASE pr."Code" WHEN 'bsc03' THEN 'pk' ELSE 'ck' END AS kind
                 , SUM(bc."Amount" / COALESCE(b.out_amt,1))            AS qty_per
              FROM bom_comp bc
              JOIN b ON b.id = bc."BOM_id"
              JOIN material cm    ON cm.id = bc."Material_id"
              JOIN work_center wc ON wc.id = cm."WorkCenter_id"
              JOIN process pr     ON pr.id = wc."Process_id"
             WHERE COALESCE(bc._status,'a') <> 'd'
               AND pr."Code" IN ('bsc03','bsc05')
             GROUP BY 1
            """, p);

		for (Map<String, Object> r : rows) {
			float per = toFloat(r.get("qty_per"));
			String kind = str(r.get("kind"));
			if (per > 0) {
				out.put(kind + "_per", per);
				out.put(kind + "_found", 1f);
			}
		}
		return out;
	}

	/**
	 * PK 합계 → 완제품 수량. ★v3.3 신규
	 *
	 * ★ 나눠떨어지지 않으면 null 을 돌려 호출자가 거부하게 한다.
	 *   PK 29개(배수 3)로 완제품 9.67개를 내면 로트·재고가 전부 어긋난다.
	 *   ①(담기) 단계에서는 막지 않는다 — 나눠 담다가 채우는 게 정상이다.
	 *   거부는 ②(packFinish) 와 무작지 시작(packStartNoJob)에서만 한다.
	 */
	private static Float unitsFromPk(float pkSum, float pkPer) {
		if (pkPer <= 0) return null;
		float u = pkSum / pkPer;
		return (Math.abs(u - Math.rint(u)) > EPS) ? null : (float) Math.rint(u);
	}

	/** 이 포장 작지의 CK 반제품 = 자식 작지의 Material_id (포장 워크센터 산출품) */
	private Integer resolveCkMaterial(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jrPk);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT jr."Material_id" AS ck_mat_id
              FROM job_res jr
              JOIN material m ON m.id = jr."Material_id"
              JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
             WHERE jr.id = :jrId AND mg."MaterialType" = 'semi'
            """, p);
		if (r != null) return toInt(r.get("ck_mat_id"));

		// 자식 작지가 완제품으로 지시된 경우 → 완제품 BOM 에서 포장 워크센터 산출 반제품
		Map<String, Object> r2 = this.sqlRunner.getRow("""
            SELECT bc."Material_id" AS ck_mat_id
              FROM job_res jr
              LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
              JOIN bom b       ON b."Material_id" = COALESCE(hdr."Material_id", jr."Material_id")
              JOIN bom_comp bc ON bc."BOM_id" = b.id
              JOIN material cm ON cm.id = bc."Material_id"
              JOIN work_center wc2 ON wc2.id = cm."WorkCenter_id"
              JOIN process pr2 ON pr2.id = wc2."Process_id"
             WHERE jr.id = :jrId AND pr2."Code" = 'bsc05'
             ORDER BY bc._order LIMIT 1
            """, p);
		return r2 == null ? null : toInt(r2.get("ck_mat_id"));
	}

	/** 완제품 = 부모 작지의 Material_id. 부모가 없으면 자기 것. */
	private Integer resolveProductMaterial(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jrPk);
		Map<String, Object> row = this.sqlRunner.getRow("""
            SELECT COALESCE(hdr."Material_id", jr."Material_id") AS product_mat_id
              FROM job_res jr
              LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
             WHERE jr.id = :jrId
            """, p);
		return row == null ? null : toInt(row.get("product_mat_id"));
	}

	// =========================================================================
	// 세션 조회
	// =========================================================================

	public List<Map<String, Object>> getSessionList(Integer jrId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jrId);
		return this.sqlRunner.getRows("""
            SELECT mp.id                        AS mp_id
                 , mp."LotIndex"                AS chasu
                 , mp."LotNumber"               AS lot_no
                 , mp."State"                   AS state
                 , ROUND(COALESCE(mp."GoodQty",0)::numeric,0) AS units
                 , mp."Equipment_id"            AS equipment_id
                 , eq."Name"                    AS equipment_name
                 , mp."Actor_id"                AS worker_id
                 , per."Name"                   AS worker_name
                 , to_char(mp."StartTime",'YYYY-MM-DD HH24:MI') AS start_time
                 , to_char(mp."EndTime",  'YYYY-MM-DD HH24:MI') AS end_time
                 , (__PHASE__)                  AS phase
                 , CASE WHEN COALESCE(hdr."SourceTableName",'') = 'pack'
                        THEN 'Y' ELSE 'N' END   AS no_job_yn
                 , CASE WHEN jr."Parent_id" IS NULL AND smg."MaterialType" = 'semi'
                        THEN 'ckstock' ELSE 'kit' END AS job_kind
                 -- 담아둔 PK (①에서 자동/수동으로 저장된다) ★PK 낱개 단위
                 , COALESCE(pk.pk_qty,0)        AS pk_qty
                 , COALESCE(pk.pk_cnt,0)        AS pk_cnt
              FROM mat_produce mp
              LEFT JOIN person per      ON per.id = mp."Actor_id"
              LEFT JOIN equ eq          ON eq.id  = mp."Equipment_id"
              LEFT JOIN LATERAL (
                  SELECT COALESCE(SUM(pai."Qty"),0) AS pk_qty, COUNT(*) AS pk_cnt
                    FROM pack_alloc_item pai
                   WHERE pai."MatProduce_id" = mp.id
                     AND COALESCE(pai._status,'a') = 'a'
                     AND COALESCE(pai."ItemKind",'ck') = 'pk'
              ) pk ON true
              JOIN job_res jr  ON jr.id = mp."JobResponse_id"
              LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
              LEFT JOIN material sm  ON sm.id  = mp."Material_id"
              LEFT JOIN mat_grp smg  ON smg.id = sm."MaterialGroup_id"
             WHERE mp."JobResponse_id" = :jrId
               AND COALESCE(mp._status,'a') = 'a'
               -- ★ 세션 차수만. 국가별 CK 산출 차수(ckMp)는 LastProcessYN='N' 이다.
               AND COALESCE(mp."LastProcessYN",'Y') = 'Y'
               -- 산출품 세션만. CK 산출 mat_produce(국가별)는 세션 카드가 아니다
               AND COALESCE(mp."Material_id",0) = COALESCE(hdr."Material_id", jr."Material_id")
             ORDER BY mp."LotIndex"
            """.replace("__PHASE__", PHASE_SQL), p);
	}

	/**
	 * 세션 상세 — 단계 + PK 로트 + 국가배분 + CK 투입 + 라벨 + 카톤(계산).
	 *
	 *   ★ v3 : 화면 메모리가 없다. 여기서 내려주는 값이 곧 화면 상태다.
	 *   ★ 카톤은 저장하지 않는다. 완제품수량 ÷ 입수 로 만들어 내려준다.
	 *   ★ v3.3 : session.pk_per / ck_per 를 함께 내려준다.
	 *     화면의 pkTotal()·allocRemain()·ckNeedTotal() 이 서버와 같은 배수를 써야 한다.
	 */
	public Map<String, Object> getSessionDetail(Integer mpId, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
		Map<String, Object> data = new HashMap<>();

		Map<String, Object> session = this.sqlRunner.getRow("""
            SELECT mp.id AS mp_id, mp."LotNumber" AS lot_no, mp."State" AS state
                 , mp."Material_id" AS product_mat_id
                 , ROUND(COALESCE(mp."GoodQty",0)::numeric,0) AS units
                 , mp."JobResponse_id" AS jr_pk
                 -- 목록과 같은 이유로 부모(완제품) 지시량을 쓴다. §getOrderList 주석 참고
                 , COALESCE(hdr."OrderQty", jr."OrderQty") AS order_qty
                 , to_char(mp."StartTime",'YYYY-MM-DD HH24:MI') AS start_time
                 , to_char(mp."EndTime",  'YYYY-MM-DD HH24:MI') AS end_time
                 , (__PHASE__) AS phase
                 , CASE WHEN COALESCE(hdr."SourceTableName",'') = 'pack'
                        THEN 'Y' ELSE 'N' END AS no_job_yn
                 , CASE WHEN jr."Parent_id" IS NULL AND smg."MaterialType" = 'semi'
                        THEN 'ckstock' ELSE 'kit' END AS job_kind
                 , (SELECT ml."MakerLotNo" FROM mat_lot ml
                     WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk" = mp.id
                     ORDER BY ml.id DESC LIMIT 1) AS maker_lot
              FROM mat_produce mp
              JOIN job_res jr ON jr.id = mp."JobResponse_id"
              LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
              LEFT JOIN material sm  ON sm.id  = mp."Material_id"
              LEFT JOIN mat_grp smg  ON smg.id = sm."MaterialGroup_id"
             WHERE mp.id = :mpId
            """.replace("__PHASE__", PHASE_SQL), p);

		Integer productMatId = (session == null) ? null : toInt(session.get("product_mat_id"));
		float cap = (productMatId == null) ? 4f : toFloat(getBoxSpec(productMatId, spjangcd).get("outbox_cap"));
		if (cap <= 0) cap = 4f;

		// ★ v3.3 : PK·CK 배수. 아래 order_remain / units_planned 와 화면이 함께 쓴다
		Map<String, Float> kit = getKitSpec(productMatId, spjangcd);
		float pkPer = kit.get("pk_per");
		if (pkPer <= 0) pkPer = 1f;

		if (session != null) {
			session.put("outbox_cap", cap);
			session.put("gtin", getExpectedGtin(productMatId, spjangcd));
			session.put("pk_per",   pkPer);              // ★ 완제품 1개당 PK
			session.put("ck_per",   kit.get("ck_per"));  // ★ 완제품 1개당 CK
			session.put("pk_found", kit.get("pk_found"));
			session.put("ck_found", kit.get("ck_found"));
		}
		data.put("session", session);

		data.put("pk_lots", this.sqlRunner.getRows("""
            SELECT pai.id, pai."MatLot_id" AS mat_lot_id, pai."Qty" AS qty
                 , ml."LotNumber" AS lot_no, ml."MakerLotNo" AS udi_lot
                 , ml."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                 , COALESCE(ml."CurrentStock",0) AS avail
              FROM pack_alloc_item pai
              JOIN mat_lot ml    ON ml.id = pai."MatLot_id"
              JOIN material m    ON m.id  = ml."Material_id"
             WHERE pai."MatProduce_id" = :mpId
               AND COALESCE(pai._status,'a') = 'a'
               AND COALESCE(pai."ItemKind",'ck') = 'pk'
             ORDER BY pai.id
            """, p));

		data.put("allocations", this.sqlRunner.getRows("""
            SELECT pa.id AS alloc_id, pa."CountryCode" AS country, pa."CountryName" AS country_name
                 , pa."Country_id" AS country_id, pa."Qty" AS qty, pa."CkLotNumber" AS ck_lot
                 , pa."CkMatProduce_id" AS ck_mp_id, COALESCE(pa."CkState",'plan') AS ck_state
                 , pa."CkMaterial_id" AS ck_mat_id
              FROM pack_alloc pa
             WHERE pa."MatProduce_id" = :mpId AND COALESCE(pa._status,'a')='a'
             ORDER BY pa.id
            """, p));

		data.put("items", this.sqlRunner.getRows("""
            SELECT pai.id, pa."CountryCode" AS country
                 , pai."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                 , pai."Qty" AS qty, pai."SterileYN" AS sterile_yn
                 , pai."MatLot_id" AS mat_lot_id, ml."LotNumber" AS lot_no
              FROM pack_alloc_item pai
              JOIN pack_alloc pa ON pa.id = pai."PackAlloc_id"
              JOIN material m    ON m.id  = pai."Material_id"
              LEFT JOIN mat_lot ml ON ml.id = pai."MatLot_id"
             WHERE pa."MatProduce_id" = :mpId
               AND COALESCE(pai._status,'a') = 'a'
               AND COALESCE(pai."ItemKind",'ck') = 'ck'
             ORDER BY pa.id, m."Code"
            """, p));

		data.put("labels", this.sqlRunner.getRows("""
            SELECT "LabelKind" AS kind, "Gtin" AS gtin, "LotNo" AS lot
                 , "MakeDate" AS date, "ExpiryDate" AS expiry, "Qty" AS qty, "RawData" AS raw
              FROM pack_label WHERE "MatProduce_id" = :mpId ORDER BY "LabelKind"
            """, p));

		// ① 자동선택 대상 — 이 세션이 담아야 할 PK 품목 (산출품 BOM 의 블리스터 산출물)
		data.put("pk_material_id", resolvePkMaterial(mpId));

		// ② 「CK 자체재고 투입」 후보 (생산창고 17 잔여)
		Integer jrPk = (session == null) ? null : toInt(session.get("jr_pk"));
		data.put("ck_stock_lots", getCkStockLots(null, jrPk, spjangcd));

		// ★ CK 반제품 품목 id.
		//   「자체재고 투입」을 고른 국가는 items 에 (mat_id = 이 값, mat_lot_id = 고른 로트)
		//   한 줄로 저장된다. 화면은 그 줄을 보고 조달 방식을 복원한다 —
		//   mode 를 담을 컬럼을 따로 두지 않은 이유다(투입 자재 자체가 곧 방식이다).
		Integer ckMatIdForUi = "ckstock".equals(str(session == null ? null : session.get("job_kind")))
														 ? toInt(session.get("product_mat_id"))
														 : (jrPk == null ? null : resolveCkMaterial(jrPk));
		data.put("ck_material_id", ckMatIdForUi);

		// 작지 잔여 (이 세션 제외) — 화면이 PK 수량 상한 안내에 쓴다
		//   ★ 완제품 단위다. 화면에서 PK 개수로 보여주려면 × pk_per 할 것
		data.put("order_remain", jrPk == null ? 0f : remainOrderQty(jrPk, mpId, pkPer));

		// 카톤 — 저장하지 않고 계산해서 내려준다 (pack_carton 없음)
		//   ② 전에는 GoodQty 가 0 이라 담은 PK 로 대신 센다 → ★반드시 ÷ pk_per
		float units = (session == null) ? 0f : toFloat(session.get("units"));
		if (units <= 0) units = pkTotal(mpId) / pkPer;
		data.put("units_planned", units);
		/*
		 * ★ 카톤은 «저장된 개체가 있으면 그것이 진실» 이다.
		 *
		 *   ②(완제) 시점에 issueCountryLotsAndCartons 가 pack_carton 에 박스마다
		 *   실제 번호(C-…-0016-JP-01)를 넣어 둔다. 그런데 여기서 buildCartons 로
		 *   매번 다시 계산해 내려주는 바람에 화면은 번호를 못 받았고,
		 *   화면이 제 나름대로 «기준로트 + -01» 을 지어내 보여 주고 있었다 —
		 *   국가별로 쪼갠 세션에서는 그 값이 DB 와 달라진다(국가 구간이 빠진다).
		 *
		 *   ② 전에는 개체가 아직 없으므로 예전처럼 계산해서 «미리보기» 로 내린다.
		 */
		List<Map<String, Object>> cartons = getCartonRows(mpId);
		data.put("cartons", cartons.isEmpty() ? buildCartons(units, cap) : cartons);
		return data;
	}

	/**
	 * 카톤 목록 생성 — 완제품수량 ÷ 입수.
	 * 저장하지 않는 이유: 두 값에서 100% 복원되고, pack_label 은 (MatProduce_id, LabelKind)
	 * UNIQUE 라 카톤 N행을 담을 수 없다.
	 */
	/**
	 * 저장된 카톤 개체 — pack_carton. ②(완제) 이후에만 행이 있다.
	 *
	 * ★ 화면이 쓰는 키 이름을 buildCartons 와 맞춘다(carton_no / inbox_qty / full_yn).
	 *   여기에 lot_no · country 가 더 붙는다 — 라벨 출력이 이 번호를 그대로 찍어야
	 *   실물 박스와 DB 가 어긋나지 않는다.
	 */
	private List<Map<String, Object>> getCartonRows(Integer mpId) {
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            SELECT pc."CartonNo"    AS carton_no
                 , pc."CartonLotNo" AS lot_no
                 , pc."Qty"         AS inbox_qty
                 , pc."CountryCode" AS country
                 , pc."MatLot_id"   AS mat_lot_id
                 , ml."LotNumber"   AS product_lot
              FROM pack_carton pc
              LEFT JOIN mat_lot ml ON ml.id = pc."MatLot_id"
             WHERE pc."MatProduce_id" = :mpId
               AND COALESCE(pc._status,'a') = 'a'
             ORDER BY pc."PackAlloc_id", pc."CartonNo"
            """, new MapSqlParameterSource().addValue("mpId", mpId));
		if (rows == null) return new ArrayList<>();

		// 국가 안에서 마지막 박스만 잔여일 수 있다 — 그 국가의 최대 수량과 비교해 판정한다
		Map<String, Float> capByCountry = new HashMap<>();
		for (Map<String, Object> r : rows) {
			String c = str(r.get("country"));
			capByCountry.merge(c == null ? "" : c, toFloat(r.get("inbox_qty")), Math::max);
		}
		for (Map<String, Object> r : rows) {
			String c = str(r.get("country"));
			float mx = capByCountry.getOrDefault(c == null ? "" : c, 0f);
			r.put("full_yn", (toFloat(r.get("inbox_qty")) >= mx - EPS) ? "Y" : "N");
		}
		return rows;
	}

	private List<Map<String, Object>> buildCartons(float units, float cap) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (units <= 0 || cap <= 0) return out;
		int no = 1;
		float remain = units;
		while (remain > EPS) {
			float q = Math.min(cap, remain);
			Map<String, Object> c = new HashMap<>();
			c.put("carton_no", no++);
			c.put("inbox_qty", q);
			c.put("full_yn", (q >= cap) ? "Y" : "N");
			out.add(c);
			remain -= q;
		}
		return out;
	}

	// =========================================================================
	// 세션 시작 — (A) 작지 있음  ★PK 자동배정
	// =========================================================================

	public AjaxResult packStart(Integer jrId, Integer equipmentId, Integer workerId,
															User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		JobRes jr = this.jobResRepository.getJobResById(jrId);
		if (jr == null)          return fail(result, "작업지를 찾을 수 없습니다.");
		if (equipmentId == null) return fail(result, "포장대를 선택해주세요.");
		if (workerId == null)    return fail(result, "작업자를 선택해주세요.");

		Integer productMatId = resolveProductMaterial(jrId);
		if (productMatId == null) return fail(result, "완제품 품목을 확인할 수 없습니다.");

		MaterialProduce mp = createSessionMp(jr, productMatId, equipmentId, workerId, user, spjangcd);
		startEquRun(jr, equipmentId, workerId, user, spjangcd);

		// ★ PK 자동배정 — 작지에 연결된 PK 품목을 멸균창고에서 FIFO 로 잡는다
		//   CK 자체재고 지시는 PK 품목이 없으므로 note 만 남고 넘어간다.
		Map<String, Object> auto = autoAssignPkLots(mp, jr, user, spjangcd);

		if ("ordered".equals(jr.getState()) || "wait".equals(jr.getState())) {
			jr.setState("working");
			if (jr.getStartTime() == null) jr.setStartTime(DateUtil.getNowTimeStamp());
			jr.set_audit(user);
			this.jobResRepository.save(jr);
		}

		Map<String, Object> data = new HashMap<>();
		data.put("mp_id", mp.getId());
		data.put("lot_number", mp.getLotNumber());
		data.putAll(auto);      // auto_pk_qty / auto_pk_cnt / auto_pk_note / auto_pk_per
		result.data = data;
		return result;
	}

	/**
	 * ★ PK 자동배정.
	 *
	 *   대상 품목 = 산출품(완제품) BOM 직하위 중 블리스터(bsc03) 산출물 → resolvePkMaterial
	 *   수량      = 작지 잔여(완제품) × pk_per      ← ★v3.3
	 *   로트      = 멸균창고(18) FIFO. 다른 세션 예약분은 빼고 본다
	 *
	 *   ★ 재고를 움직이지 않는다. pack_alloc_item(ItemKind='pk') 행만 만든다.
	 *     그 행의 존재가 곧 예약이고, 다른 세션의 avail 계산에서 빠진다.
	 *   ★ 못 잡아도 실패로 만들지 않는다 — 세션은 열리고 화면에서 직접 담으면 된다.
	 *     (멸균이 아직 안 끝났거나 작지가 이미 다 채워진 경우, CK 자체재고 지시인 경우)
	 *
	 *   ★★ v3.3 : 배수를 remainOrderQty 보다 먼저 구해야 한다.
	 *      remainOrderQty 가 진행 세션의 PK 합계를 완제품으로 환산할 때 같은 값을 쓴다.
	 *      순서를 바꾸면 잔여가 배수만큼 빨리 소진돼 두 번째 세션이 PK 를 못 잡는다.
	 */
	private Map<String, Object> autoAssignPkLots(MaterialProduce mp, JobRes jr,
																							 User user, String spjangcd) {
		Map<String, Object> out = new HashMap<>();
		out.put("auto_pk_qty", 0f);
		out.put("auto_pk_cnt", 0);

		Integer pkMatId = resolvePkMaterial(mp.getId());
		if (pkMatId == null) {
			out.put("auto_pk_note", "완제품 BOM 에서 PK 품목(블리스터 산출)을 찾지 못했습니다. 직접 담아주세요.");
			return out;
		}

		// ★ 배수를 먼저 구한다 — remainOrderQty 가 PK 를 완제품으로 환산할 때 쓴다
		float pkPer = getKitSpec(mp.getMaterialId(), spjangcd).get("pk_per");
		if (pkPer <= 0) pkPer = 1f;
		out.put("auto_pk_per", pkPer);

		float remainUnits = remainOrderQty(jr.getId(), mp.getId(), pkPer);
		if (remainUnits <= EPS) {
			out.put("auto_pk_note", "작지 지시량이 이미 채워져 있습니다. 필요하면 직접 담아주세요.");
			return out;
		}

		// 완제품 잔여 → 잡아야 할 PK 낱개
		float need = remainUnits * pkPer;

		List<Map<String, Object>> lots =
			getSterilizedLots("pk", pkMatId, null, spjangcd, mp.getId(), false);

		float assigned = 0f;
		int cnt = 0;
		for (Map<String, Object> lot : lots) {
			if (need <= EPS) break;
			float avail = toFloat(lot.get("avail"));
			if (avail <= 0) continue;
			float take = Math.min(avail, need);
			insertAllocItem(null, mp.getId(), toInt(lot.get("mat_id")), take,
				toInt(lot.get("mat_lot_id")), "N", "pk", spjangcd, user);
			assigned += take;
			need     -= take;
			cnt++;
		}

		out.put("auto_pk_qty", assigned);
		out.put("auto_pk_cnt", cnt);
		if (cnt == 0)
			out.put("auto_pk_note", "멸균창고에 사용 가능한 PK 로트가 없습니다. 멸균 BI 판정을 먼저 완료하세요.");
		else if (need > EPS)
			out.put("auto_pk_note", "멸균 재고가 부족해 PK " + fmt(assigned) + " 만 잡았습니다. (부족 "
																+ fmt(need) + " · 완제품 1개당 PK " + fmt(pkPer) + ")");
		return out;
	}

	/**
	 * 작지 잔여 = 지시량 − 산출품 세션들이 이미 잡은 양. ★완제품 단위로 돌려준다
	 *
	 *   완료 세션은 GoodQty, 진행 세션은 담아둔 PK 합계로 센다.
	 *   ★ 진행 세션의 PK 를 세지 않으면 두 번째 세션이 지시량 전체를 또 자동배정한다.
	 *   ★ 국가별 CK 산출 차수(LastProcessYN='N')는 세지 않는다 — 세면 잔여가 0 이 되어
	 *     두 번째 세션이 PK 를 못 잡는다.
	 *
	 *   ★★ v3.3 : pk.q 는 PK 낱개 합계라 pkPer 로 나눠야 OrderQty(완제품)와 단위가 맞는다.
	 *      안 나누면 작지 50개짜리에서 첫 세션이 PK 60(=완제품 20)을 담았을 때
	 *      used=60 이 되어 잔여가 0 으로 잘리고, 두 번째 세션이 PK 를 한 개도 못 잡았다.
	 *      GREATEST 왼쪽의 GoodQty 는 packFinish 가 완제품 단위로 넣으므로 그대로 둔다.
	 *
	 * @param pkPer 완제품 1개당 PK 개수 (getKitSpec 의 pk_per)
	 */
	private float remainOrderQty(Integer jrId, Integer exceptMpId, float pkPer) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("jrId", jrId);
		p.addValue("exMp", exceptMpId);
		p.addValue("pkPer", pkPer <= 0 ? 1f : pkPer);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT COALESCE(jr."OrderQty",0) AS order_qty
                 , COALESCE(u.used,0)        AS used
              FROM job_res jr
              LEFT JOIN LATERAL (
                  SELECT SUM(CASE WHEN mp."State" = 'finished'
                                  THEN COALESCE(mp."GoodQty",0)
                                  ELSE GREATEST(COALESCE(mp."GoodQty",0),
                                                COALESCE(pk.q,0) / CAST(:pkPer AS float8)) END) AS used
                    FROM mat_produce mp
                    LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
                    LEFT JOIN LATERAL (
                        SELECT COALESCE(SUM(pai."Qty"),0) AS q
                          FROM pack_alloc_item pai
                         WHERE pai."MatProduce_id" = mp.id
                           AND COALESCE(pai._status,'a') = 'a'
                           AND COALESCE(pai."ItemKind",'ck') = 'pk'
                    ) pk ON true
                   WHERE mp."JobResponse_id" = jr.id
                     AND COALESCE(mp._status,'a') = 'a'
                     AND COALESCE(mp."LastProcessYN",'Y') = 'Y'
                     AND COALESCE(mp."Material_id",0) = COALESCE(hdr."Material_id", jr."Material_id")
                     AND (CAST(:exMp AS integer) IS NULL OR mp.id <> CAST(:exMp AS integer))
              ) u ON true
             WHERE jr.id = :jrId
            """, p);
		if (r == null) return 0f;
		return Math.max(0f, toFloat(r.get("order_qty")) - toFloat(r.get("used")));
	}

	// =========================================================================
	// 세션 시작 — (B) 작지 없음
	// =========================================================================

	/**
	 * 작지 없이 포장 시작.
	 *
	 *   mat_produce."JobResponse_id" 가 NOT NULL 이라 작지 없이는 생산 실적을 만들 수 없다.
	 *   → 포장 전용 작지 2행을 그 자리에서 발행한다.
	 *        헤더 : Material_id = 완제품, SourceTableName='pack', Parent_id = null
	 *        자식 : Material_id = CK 반제품, WorkCenter_id = 포장, Parent_id = 헤더
	 *
	 *   ★ ProdOrderEditService.makeProdOrder 를 쓰지 않는다.
	 *     완제품에 Routing_id 가 있으면 explodeProcessRows 가 BOM 트리를 전부 펼쳐
	 *     세척·조립·블리스터·융착 작지까지 만든다. 포장만 하려는 건에 유령 작지가 남는다.
	 *
	 *   ★ v3 : 고른 PK 로트를 여기서 바로 DB 에 저장한다(화면 메모리 없음).
	 *   ★ v3.3 : 작지 지시량(OrderQty)은 완제품 단위다 → 고른 PK 합계 ÷ pk_per.
	 *     여기서는 나눠떨어지지 않으면 거부한다 — 작지를 발행하는 자리라
	 *     어중간한 지시량이 남으면 나중에 잔여 계산이 계속 어긋난다.
	 */
	public AjaxResult packStartNoJob(Integer productMatId, Float orderQty,
																	 List<Map<String, Object>> pkLots,
																	 Integer equipmentId, Integer workerId,
																	 String prodDateStr, User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		if (productMatId == null) return fail(result, "완제품 품목을 선택해주세요.");
		if (equipmentId == null)  return fail(result, "포장대를 선택해주세요.");
		if (workerId == null)     return fail(result, "작업자를 선택해주세요.");
		if (pkLots == null || pkLots.isEmpty())
			return fail(result, "포장할 PK 로트를 선택해주세요.");

		Integer ckMatId = resolveCkMaterialByProduct(productMatId);
		if (ckMatId == null)
			return fail(result, "이 완제품의 CK 반제품을 BOM 에서 찾을 수 없습니다. BOM 구성을 확인해주세요.");

		// ── 작지를 만들기 전에 로트를 먼저 검증한다 ──
		//   실패한 뒤 지울 껍데기를 애초에 안 만드는 편이 낫다
		float pkSum = 0f;
		Integer pkMatId = null;
		List<Map<String, Object>> resolved = new ArrayList<>();
		for (Map<String, Object> pl : pkLots) {
			Integer lotId = toInt(pl.get("mat_lot_id"));
			float qty     = toFloat(pl.get("qty"));
			if (lotId == null || qty <= 0) continue;
			Map<String, Object> lot = getUsableLot(lotId, STORE_STERIL, null);
			if (lot == null)
				return fail(result, "선택한 PK 로트를 사용할 수 없습니다. (id " + lotId + ")");
			float avail = toFloat(lot.get("avail"));
			if (avail < qty)
				return fail(result, "PK 로트 재고 부족 — " + lot.get("lot_no")
															+ " (필요 " + fmt(qty) + " / 가용 " + fmt(avail) + ")");
			Integer mid = toInt(lot.get("mat_id"));
			if (pkMatId == null) pkMatId = mid;
			else if (!pkMatId.equals(mid))
				return fail(result, "서로 다른 PK 품목은 한 세션에 섞을 수 없습니다.");
			Map<String, Object> row = new HashMap<>(lot);
			row.put("_qty", qty);
			resolved.add(row);
			pkSum += qty;
		}
		if (resolved.isEmpty()) return fail(result, "포장할 PK 로트를 선택해주세요.");

		// ★ v3.3 : PK 낱개 합계 → 완제품 수량
		Map<String, Float> kit = getKitSpec(productMatId, spjangcd);
		float pkPer = kit.get("pk_per");
		if (pkPer <= 0) pkPer = 1f;
		Float pkUnits = unitsFromPk(pkSum, pkPer);
		if (pkUnits == null)
			return fail(result, "PK 수량(" + fmt(pkSum) + ")이 완제품 1개당 " + fmt(pkPer)
														+ "개로 나눠떨어지지 않습니다. 담은 수량을 조정해주세요.");

		float planQty = (orderQty != null && orderQty > 0) ? orderQty : pkUnits;
		if (planQty <= 0) return fail(result, "포장 수량이 0 입니다.");

		Timestamp now = DateUtil.getNowTimeStamp();
		Timestamp prodDate = isBlank(prodDateStr)
													 ? now : Timestamp.valueOf(LocalDate.parse(prodDateStr).atStartOfDay());

		Material product = this.materialRepository.getMaterialById(productMatId);
		Integer wcId = getPackWorkCenterId();
		if (wcId == null) return fail(result, "포장 워크센터(bsc05)가 없습니다.");

		// ── 헤더 작지 ──
		JobRes hdr = new JobRes();
		hdr.set_audit(user);
		hdr.setMaterialId(productMatId);
		hdr.setOrderQty(planQty);
		hdr.setProductionDate(prodDate);
		hdr.setProductionPlanDate(prodDate);
		hdr.setStoreHouse_id(product != null ? product.getStoreHouseId() : STORE_PRODUCT);
		hdr.setLotCount(1);
		hdr.setProcessCount(1);
		hdr.setState("working");
		hdr.setStartTime(now);
		hdr.setSourceTableName("pack");      // ← 「작지 없음」 판별 키
		hdr.setSourceDataPk(null);
		hdr.setSpjangcd(spjangcd);
		hdr.setDescription("포장 직접 시작(작지 없음)");
		hdr = this.jobResRepository.save(hdr);

		// ── 자식 작지 (포장 공정) ──
		JobRes child = new JobRes();
		child.set_audit(user);
		child.setParentId(hdr.getId());
		child.setMaterialId(ckMatId);
		child.setOrderQty(planQty);
		child.setProductionDate(prodDate);
		child.setProductionPlanDate(prodDate);
		child.setWorkCenter_id(wcId);
		child.setFirstWorkCenter_id(wcId);
		child.setWorkIndex(getPackProcessOrder(product));
		child.setProcessCount(1);
		child.setStoreHouse_id(STORE_PRODUCT);
		child.setState("working");
		child.setStartTime(now);
		child.setSpjangcd(spjangcd);
		child.setDescription("포장 직접 시작(작지 없음)");
		child = this.jobResRepository.save(child);

		MaterialProduce mp = createSessionMp(child, productMatId, equipmentId, workerId, user, spjangcd);
		startEquRun(child, equipmentId, workerId, user, spjangcd);

		// ★ 고른 PK 를 바로 저장 — 화면 메모리로 들고 가지 않는다
		for (Map<String, Object> lot : resolved)
			insertAllocItem(null, mp.getId(), toInt(lot.get("mat_id")), toFloat(lot.get("_qty")),
				toInt(lot.get("mat_lot_id")), "N", "pk", spjangcd, user);

		Map<String, Object> data = new HashMap<>();
		data.put("mp_id",      mp.getId());
		data.put("jr_pk",      child.getId());
		data.put("header_id",  hdr.getId());
		data.put("lot_number", mp.getLotNumber());
		data.put("units",      pkUnits);   // ★ 완제품 단위
		data.put("pk_qty",     pkSum);     // ★ 담은 PK 낱개
		data.put("pk_per",     pkPer);
		result.data = data;
		return result;
	}

	/** 완제품 → CK 반제품 (BOM 직하위 중 포장 워크센터 산출) */
	private Integer resolveCkMaterialByProduct(Integer productMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("matId", productMatId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT bc."Material_id" AS ck_mat_id
              FROM bom b
              JOIN bom_comp bc ON bc."BOM_id" = b.id
              JOIN material cm ON cm.id = bc."Material_id"
              JOIN work_center wc ON wc.id = cm."WorkCenter_id"
              JOIN process pr  ON pr.id = wc."Process_id"
             WHERE b."Material_id" = :matId
               AND COALESCE(b._status,'a') <> 'd'
               AND COALESCE(bc._status,'a') <> 'd'
               AND pr."Code" = 'bsc05'
             ORDER BY bc._order LIMIT 1
            """, p);
		return r == null ? null : toInt(r.get("ck_mat_id"));
	}

	private Integer getPackWorkCenterId() {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT wc.id FROM work_center wc JOIN process p ON p.id = wc."Process_id"
             WHERE p."Code" = 'bsc05' ORDER BY wc.id LIMIT 1
            """, new MapSqlParameterSource());
		return r == null ? null : toInt(r.get("id"));
	}

	private Integer getPackProcessOrder(Material product) {
		if (product == null || product.getRoutingId() == null) return 1;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", product.getRoutingId());
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT rp."ProcessOrder" FROM routing_proc rp
              JOIN process p ON p.id = rp."Process_id"
             WHERE rp."Routing_id" = :routingId AND p."Code" = 'bsc05' LIMIT 1
            """, p);
		Integer po = (r == null) ? null : toInt(r.get("ProcessOrder"));
		return po != null ? po : 1;
	}

	/**
	 * 세션 mat_produce 1행 — 산출품목 = 완제품, 산출창고 = 제품창고.
	 *
	 * ★ LastProcessYN='Y' 가 「세션 차수」의 표식이다.
	 *   국가별 CK 산출 차수(packFinish 안의 ckMp)는 'N' 으로 들어간다.
	 *   목록·세션·롤업 쿼리가 전부 이 값으로 둘을 가른다.
	 * ★ ckstock(반제품 산출) 세션은 산출창고를 생산창고(17)로 바꾼다.
	 *   워크센터(bsc05) 산출창고는 완제품 기준 제품창고(4)라, 그대로 두면
	 *   반제품을 제품창고에 낸 것처럼 실적에 남는다.
	 */
	private MaterialProduce createSessionMp(JobRes jr, Integer productMatId,
																					Integer equipmentId, Integer workerId,
																					User user, String spjangcd) {
		Timestamp now = DateUtil.getNowTimeStamp();
		Workcenter wc = this.workcenterRepository.getWorkcenterById(jr.getWorkCenter_id());

		int chasu = this.matProduceRepository.findByJobResponseId(jr.getId()).size() + 1;
		String lotNumber = this.lotService.make_production_lot_in_number("P");

		Integer outStoreId = resolveOutStore(jr.getWorkCenter_id());
		if (isSemiMaterial(productMatId)) outStoreId = STORE_CK_OUT;

		MaterialProduce mp = new MaterialProduce();
		mp.setJobResponseId(jr.getId());
		mp.setMaterialId(productMatId);
		if (wc != null) mp.setProcessId(wc.getProcessId());
		mp.setProcessOrder(jr.getWorkIndex() != null ? jr.getWorkIndex() : 1);
		mp.setLotIndex(chasu);
		mp.set_status("a");
		mp.setStoreHouseId(outStoreId);
		mp.setProductionDate(jr.getProductionDate());
		mp.setShiftCode(jr.getShiftCode());
		mp.setWorkCenterId(jr.getWorkCenter_id());
		mp.setEquipmentId(equipmentId);
		mp.setActorId(workerId);
		mp.setLastProcessYN("Y");        // ★ 세션 차수 표식
		mp.setLotNumber(lotNumber);
		mp.setSpjangcd(spjangcd);
		mp.setInputQty(0f);
		mp.setGoodQty(0f);
		mp.setDefectQty(0f);
		mp.setState("working");
		mp.setStartTime(now);
		mp.setDescription("포장");
		mp.set_audit(user);
		return this.matProduceRepository.save(mp);
	}

	private void startEquRun(JobRes jr, Integer equipmentId, Integer workerId,
													 User user, String spjangcd) {
		EquRun er = new EquRun();
		er.setEquipmentId(equipmentId);
		er.setStartDate(DateUtil.getNowTimeStamp());
		er.setWorkOrderNumber(jr.getWorkOrderNumber());
		er.setJobResponseId(jr.getId());
		er.setActorId(workerId);
		er.setRunState("run");
		er.setSpjangcd(spjangcd);
		er.set_audit(user);
		this.equRunRepository.save(er);
	}

	// =========================================================================
	// ① PK 담기 — 재고를 안 건드린다. 로트만 확정해 둔다
	// =========================================================================

	/**
	 * 이 세션이 담을 PK 로트를 저장(교체)한다.
	 *
	 *   재고는 움직이지 않는다. ②에서 소비된다.
	 *   ★ 자동배정 결과를 사람이 고치는 경로다. 로트를 전부 빼면 단계가 ①로 돌아간다.
	 *   ★ 국가 배분이 아직 없을 수 있으므로 pack_alloc_item 을 세션(MatProduce_id)에 직접 맨다.
	 *
	 *   ★ v3.3 : 여기서는 배수로 나눠떨어지지 않아도 막지 않는다 —
	 *     여러 로트를 나눠 담다가 채우는 게 정상이다.
	 *     대신 divisible=false 를 내려 화면이 ② 버튼을 잠그게 한다.
	 *     실제 거부는 packFinish 에서 한다.
	 *
	 * @param pkLots [{mat_lot_id, qty}]  ★qty 는 PK 낱개
	 */
	public AjaxResult savePkLots(Integer mpId, List<Map<String, Object>> pkLots,
															 User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                       return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState()))  return fail(result, "완료된 세션입니다.");

		String phase = getPhase(mpId);
		if (!"pk".equals(phase) && !"pack".equals(phase))
			return fail(result, "인박스·CK 반영 이후에는 PK 를 바꿀 수 없습니다. 먼저 포장을 취소해주세요.");

		// ── 검증 (같은 품목만) ──
		float pkSum = 0f;
		Integer pkMatId = null;
		List<Map<String, Object>> resolved = new ArrayList<>();
		for (Map<String, Object> pl : (pkLots == null ? Collections.<Map<String,Object>>emptyList() : pkLots)) {
			Integer lotId = toInt(pl.get("mat_lot_id"));
			float qty     = toFloat(pl.get("qty"));
			if (lotId == null || qty <= 0) continue;

			Map<String, Object> lot = getUsableLot(lotId, STORE_STERIL, mpId);
			if (lot == null)
				return fail(result, "선택한 PK 로트를 사용할 수 없습니다. 다시 선택해주세요. (id " + lotId + ")");
			float avail = toFloat(lot.get("avail"));
			if (avail < qty)
				return fail(result, "PK 로트 재고 부족 — " + lot.get("lot_no")
															+ " (필요 " + fmt(qty) + " / 가용 " + fmt(avail) + ")");
			Integer mid = toInt(lot.get("mat_id"));
			if (pkMatId == null) pkMatId = mid;
			else if (!pkMatId.equals(mid))
				return fail(result, "서로 다른 PK 품목은 한 세션에 섞을 수 없습니다.");

			Map<String, Object> row = new HashMap<>(lot);
			row.put("_qty", qty);
			resolved.add(row);
			pkSum += qty;
		}

		// ── 교체 저장 ──
		MapSqlParameterSource dp = new MapSqlParameterSource().addValue("mpId", mpId);
		this.sqlRunner.execute("""
            DELETE FROM pack_alloc_item
             WHERE "MatProduce_id" = :mpId AND COALESCE("ItemKind",'ck') = 'pk'
            """, dp);

		for (Map<String, Object> lot : resolved)
			insertAllocItem(null, mpId, toInt(lot.get("mat_id")), toFloat(lot.get("_qty")),
				toInt(lot.get("mat_lot_id")), "N", "pk", spjangcd, user);

		// ★ 완제품 환산 — 화면 표시·검증용
		float pkPer = getKitSpec(mp.getMaterialId(), spjangcd).get("pk_per");
		if (pkPer <= 0) pkPer = 1f;
		Float u = unitsFromPk(pkSum, pkPer);

		Map<String, Object> data = new HashMap<>();
		data.put("pk_qty",    pkSum);                          // 담은 PK 낱개
		data.put("units",     u == null ? pkSum / pkPer : u);  // 완제품 환산
		data.put("pk_per",    pkPer);
		data.put("divisible", u != null);                      // false 면 ② 진행 불가
		data.put("lot_cnt",   resolved.size());
		data.put("phase",     resolved.isEmpty() ? "pk" : "pack");
		result.data = data;
		return result;
	}

	/**
	 * 이 세션이 자동으로 담아야 할 PK 품목 — 산출품 BOM 직하위 중 블리스터(bsc03) 산출.
	 *
	 * ★ _order 첫 행 하나만 집는다. PK 가 차수별로 여러 품목이면 나머지를 놓친다.
	 *   (현재 마스터는 완제품마다 PK 단일 행이라 문제 없음 — 바뀌면 여기부터 고칠 것)
	 */
	public Integer resolvePkMaterial(Integer mpId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT bc."Material_id" AS pk_mat_id
              FROM mat_produce mp
              JOIN bom b       ON b."Material_id" = mp."Material_id"
                              AND COALESCE(b._status,'a') <> 'd'
              JOIN bom_comp bc ON bc."BOM_id" = b.id
                              AND COALESCE(bc._status,'a') <> 'd'
              JOIN material cm ON cm.id = bc."Material_id"
              JOIN work_center wc ON wc.id = cm."WorkCenter_id"
              JOIN process pr  ON pr.id = wc."Process_id"
             WHERE mp.id = :mpId AND pr."Code" = 'bsc03'
             ORDER BY bc._order LIMIT 1
            """, p);
		return r == null ? null : toInt(r.get("pk_mat_id"));
	}

	// =========================================================================
	// ② 계획 저장 — 국가 배분 + CK 투입자재 (수시 자동저장)
	// =========================================================================

	/**
	 * 배분·CK 투입자재를 계획(plan) 상태로 저장한다.
	 *
	 *   ★ 화면이 값을 바꿀 때마다 부른다. 재고는 전혀 움직이지 않는다.
	 *     그래서 새로고침·다른 태블릿 진입에도 입력이 그대로 남는다.
	 *   ★ 이미 생산된(produced) 배분은 건드리지 않는다 — writeAllocations 가 plan 만 교체한다.
	 *   ★ 여기서는 합계 검증을 하지 않는다(입력 중이므로). 검증은 packFinish 에서.
	 */
	public AjaxResult saveAllocations(Integer mpId, List<Map<String, Object>> allocations,
																		User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                       return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState()))  return fail(result, "완료된 세션입니다.");

		String phase = getPhase(mpId);
		if (!"pk".equals(phase) && !"pack".equals(phase))
			return fail(result, "이미 반영된 세션입니다.");

		writeAllocations(mpId, allocations, spjangcd, user);

		Map<String, Object> data = new HashMap<>();
		data.put("alloc_qty", sumAlloc(allocations));   // ★ CK 낱개 합계
		result.data = data;
		return result;
	}

	// =========================================================================
	// ② 인박스 + CK 반영 — ★완제 시점
	// =========================================================================

	/**
	 * 인박스와 CK 를 한 번에 반영한다.
	 *
	 *   소비 : 담아둔 PK(멸균창고) + IN BOX(생산·자재·검사완료) + CK(국가별)
	 *   산출 : 완제품 로트 1건 → 제품창고(4)
	 *
	 *   ★★ v3.3 수량 규칙 (완제품 50 · pk_per 3 · ck_per 3 예시)
	 *        담은 PK      150  (pack_alloc_item ItemKind='pk' 합계)
	 *        units         50  = 150 ÷ pk_per      ← mat_produce."GoodQty"
	 *        CK 배분      150  = units × ck_per    ← pack_alloc."Qty" 합계
	 *        IN BOX        50  = qty_per × units
	 *        OUT BOX       13  = ceil(units ÷ 4)
	 *        CK 구성자재  각150 = getCkBom.qty_per × 배분수량(150)   ★ck_per 재곱 금지
	 *
	 *   국가 1건당 CK 두 갈래 (화면이 mode 로 지정):
	 *     produce — CK 반제품을 새로 생산(구성자재 소비) 후 그 로트를 소비
	 *     stock   — 이미 생산창고(17)에 있는 CK 자체재고 로트를 그대로 소비
	 *
	 *   ★ ckstock 세션이면 CK 생산까지만 하고 재고로 남긴 뒤 세션을 닫는다.
	 *     이때 units 는 배분 합계 그대로다 — CK 자체가 산출물이라 환산이 없다.
	 *     mode 는 항상 'produce' 다(CK 를 만드는 자리에서 CK 재고 투입은 성립하지 않음).
	 *   ★ 라벨은 여기서 받지 않는다 — 완제품이 나온 뒤 ③에서 스캔한다.
	 *
	 * @param allocations [{country, country_id, country_name, qty, ck_lot, mode,
	 *                      ck_mat_lot_id,                        // mode='stock'
	 *                      items:[{mat_id, qty, mat_lot_id}]}]   // mode='produce'
	 */
	public AjaxResult packFinish(Integer mpId, List<Map<String, Object>> allocations,
															 String startTimeStr, String endTimeStr,
															 User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                      return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState())) return fail(result, "완료할 수 없는 상태입니다.");
		if (allocations == null || allocations.isEmpty())
			return fail(result, "생산 수량이 지정되지 않았습니다.");

		JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());
		if (jr == null) return fail(result, "작업지를 찾을 수 없습니다.");

		boolean ckStock = isCkStockSession(mp, jr);
		String phase = getPhase(mpId);
		if (ckStock) {
			if (!"pk".equals(phase) && !"pack".equals(phase))
				return fail(result, "이미 생산한 세션입니다.");
		} else if (!"pack".equals(phase)) {
			return fail(result, "PK 로트를 먼저 담아주세요.");
		}

		Integer ckMatId = ckStock ? mp.getMaterialId() : resolveCkMaterial(jr.getId());
		if (ckMatId == null) return fail(result, "CK 반제품 품목을 확인할 수 없습니다.");

		// ★ v3.3 : 완제품 1개당 PK·CK 배수. 아래 units 산출과 배분 검증의 기준이다
		Map<String, Float> kit = getKitSpec(mp.getMaterialId(), spjangcd);
		float pkPer = kit.get("pk_per");
		float ckPer = kit.get("ck_per");
		if (pkPer <= 0) pkPer = 1f;
		if (ckPer <= 0) ckPer = 1f;
		if (!ckStock && kit.get("pk_found") < 1f)
			return fail(result, "완제품 BOM 에 PK 구성이 없습니다. BOM 을 확인해주세요.");

		Timestamp now = DateUtil.getNowTimeStamp();
		Timestamp startTs = isBlank(startTimeStr) ? mp.getStartTime()
													: Timestamp.valueOf(LocalDateTime.parse(startTimeStr, DTM));
		if (startTs == null) startTs = now;
		Timestamp endTs = isBlank(endTimeStr) ? now
												: Timestamp.valueOf(LocalDateTime.parse(endTimeStr, DTM));

		// ── PK 검증 (kit 세션만) ──
		List<Map<String, Object>> pkResolved = new ArrayList<>();
		float units;
		if (ckStock) {
			// CK 자체재고 : 배분 합계가 곧 산출 CK 수량 (환산 없음)
			units = sumAlloc(allocations);
		} else {
			List<Map<String, Object>> pkRows = this.sqlRunner.getRows("""
                SELECT pai.id, pai."MatLot_id" AS mat_lot_id, pai."Material_id" AS mat_id, pai."Qty" AS qty
                  FROM pack_alloc_item pai
                 WHERE pai."MatProduce_id" = :mpId
                   AND COALESCE(pai._status,'a')='a'
                   AND COALESCE(pai."ItemKind",'ck') = 'pk'
                """, new MapSqlParameterSource().addValue("mpId", mpId));
			if (pkRows == null || pkRows.isEmpty()) return fail(result, "담긴 PK 로트가 없습니다.");

			float pkSum = 0f;
			for (Map<String, Object> pr : pkRows) {
				Integer lotId = toInt(pr.get("mat_lot_id"));
				float qty     = toFloat(pr.get("qty"));
				Map<String, Object> lot = getUsableLot(lotId, STORE_STERIL, mpId);
				if (lot == null)
					return fail(result, "PK 로트를 사용할 수 없습니다. 다시 담아주세요. (id " + lotId + ")");
				float avail = toFloat(lot.get("avail"));
				if (avail < qty)
					return fail(result, "PK 로트 재고 부족 — " + lot.get("lot_no")
																+ " (필요 " + fmt(qty) + " / 가용 " + fmt(avail) + ")");
				Map<String, Object> row = new HashMap<>(lot);
				row.put("_qty", qty);
				pkResolved.add(row);
				pkSum += qty;
			}

			// ★★ 담은 PK 낱개 → 완제품 수량. 안 떨어지면 거부한다
			//    (PK 29개 / 배수 3 → 완제품 9.67 개는 로트·재고를 통째로 어긋나게 한다)
			Float u = unitsFromPk(pkSum, pkPer);
			if (u == null)
				return fail(result, "담긴 PK 수량(" + fmt(pkSum) + ")이 완제품 1개당 " + fmt(pkPer)
															+ "개로 나눠떨어지지 않습니다. PK 로트를 다시 담아주세요.");
			units = u;
		}
		if (units <= 0) return fail(result, "수량이 0 입니다.");

		// ── CK 배분 합계 검증 ── ★완제품 × ck_per 와 같아야 한다
		float allocSum = sumAlloc(allocations);
		float ckNeed   = units * ckPer;
		if (!ckStock && Math.abs(allocSum - ckNeed) > EPS)
			return fail(result, "CK 배분 합계(" + fmt(allocSum) + ")가 필요 수량("
														+ fmt(ckNeed) + " = 완제품 " + fmt(units) + " × " + fmt(ckPer)
														+ ")과 다릅니다.");

		// ── IN BOX 검증 (kit 세션만) ── qty_per 는 완제품 1개당이라 units 를 곱한다
		Map<String, Object> boxSpec = getBoxSpec(mp.getMaterialId(), spjangcd);
		@SuppressWarnings("unchecked")
		Map<String, Object> inbox = (Map<String, Object>) boxSpec.get("inbox");
		Integer inboxMatId = ckStock ? null : (inbox == null ? null : toInt(inbox.get("mat_id")));
		float inboxNeed = 0f;
		if (inboxMatId != null) {
			inboxNeed = toFloat(inbox.get("qty_per")) * units;
			float have = stockInStores(inboxMatId, CK_SRC_STORES);
			if (have < inboxNeed)
				return fail(result, "IN BOX 재고가 부족합니다. (필요 " + fmt(inboxNeed)
															+ " / 재고 " + fmt(have) + ")");
		}

		// ── CK produce 모드 자재 사전 검증 (한꺼번에) ──
		//   items 의 qty 는 화면이 getCkBom.qty_per × 배분수량 으로 이미 계산해 보낸다.
		//   ★ 여기서 ck_per 를 또 곱하면 안 된다 — 배분수량에 이미 배수가 들어 있다.
		Map<Integer, Float> need = new LinkedHashMap<>();
		for (Map<String, Object> a : allocations) {
			if (!ckStock && "stock".equals(str(a.get("mode")))) continue;
			for (Map<String, Object> it : itemsOf(a)) {
				Integer matId = toInt(it.get("mat_id"));
				float q = toFloat(it.get("qty"));
				if (matId == null || q <= 0) continue;
				if (toInt(it.get("mat_lot_id")) != null) continue;   // 지정로트는 따로 검증
				need.merge(matId, q, Float::sum);
			}
		}
		List<String> shortages = new ArrayList<>();
		for (Map.Entry<Integer, Float> e : need.entrySet()) {
			float have = stockInStores(e.getKey(), CK_SRC_STORES);
			if (have < e.getValue()) {
				Material m = this.materialRepository.getMaterialById(e.getKey());
				shortages.add((m != null ? m.getName() : ("자재" + e.getKey()))
												+ " (필요 " + fmt(e.getValue()) + " / 재고 " + fmt(have) + ")");
			}
		}
		if (!shortages.isEmpty())
			return fail(result, "재고 부족 — " + String.join(", ", shortages));

		// ── 배분 + CK 투입자재 확정 저장 ──
		writeAllocations(mpId, allocations, spjangcd, user);

		// ── 소비 : PK ── ★낱개 그대로 차감한다(환산 금지)
		for (Map<String, Object> lot : pkResolved) {
			float qty = toFloat(lot.get("_qty"));
			consumeFixedLot(toInt(lot.get("mat_lot_id")), qty, toFloat(lot.get("avail")),
				mp, user, spjangcd);
			writeConsumeRecord(jr, mp, toInt(lot.get("mat_id")), qty, STORE_STERIL,
				str(lot.get("lot_no")), "PK 투입(멸균창고)", startTs, endTs, user, spjangcd);
		}

		// ── 소비 : IN BOX ──
		if (inboxMatId != null && inboxNeed > 0) {
			ConsumeResult cr = consumeFifoMulti(inboxMatId, CK_SRC_STORES, inboxNeed, mp, user, spjangcd);
			if (cr.remain > 0)
				return fail(result, "IN BOX 재고가 부족합니다. (부족 " + fmt(cr.remain) + ")");
			for (Map.Entry<Integer, Float> e : cr.byStore.entrySet())
				writeConsumeRecord(jr, mp, inboxMatId, e.getValue(), e.getKey(), null,
					"인박스 포장자재 투입", startTs, endTs, user, spjangcd);
		}

		// ── 국가별 CK 생산 / 투입 ──
		List<Map<String, Object>> allocRows = this.sqlRunner.getRows("""
            SELECT pa.id AS alloc_id, pa."CountryCode" AS country, pa."Qty" AS qty,
                   pa."CkLotNumber" AS ck_lot, COALESCE(pa."CkState",'plan') AS ck_state
              FROM pack_alloc pa
             WHERE pa."MatProduce_id" = :mpId AND COALESCE(pa._status,'a')='a'
             ORDER BY pa.id
            """, new MapSqlParameterSource().addValue("mpId", mpId));

		// ★ 화면이 보낸 배분을 국가코드로 찾을 수 있게 맵으로. 빈 값은 NO_COUNTRY 로 맞춘다
		//   (writeAllocations 가 DB 에 같은 규칙으로 넣었으므로 키가 맞아떨어진다)
		Map<String, Map<String, Object>> byCountry = new HashMap<>();
		for (Map<String, Object> a : allocations) byCountry.put(countryCodeOf(a), a);

		for (Map<String, Object> ar : allocRows) {
			Integer allocId = toInt(ar.get("alloc_id"));
			String country  = str(ar.get("country"));
			String ctag     = ckTag(country);      // "(KR)" 또는 "" (국가 미지정)
			float ckQty     = toFloat(ar.get("qty"));   // ★ CK 낱개
			if (ckQty <= 0 || "produced".equals(str(ar.get("ck_state")))) continue;

			Map<String, Object> src = byCountry.get(country);
			String mode = (src == null) ? "produce" : str(src.get("mode"));
			if (isBlank(mode)) mode = "produce";
			// ★ CK 자체재고 세션은 항상 새로 생산한다. 화면이 잘못 보내도 서버가 바로잡는다.
			if (ckStock) mode = "produce";

			Integer ckMpId = null;
			String  ckLotNo;
			Integer ckLotId;
			Integer usedCkMatId = ckMatId;

			if ("stock".equals(mode)) {
				// ── CK 자체재고 투입 ── 새로 만들지 않고 창고 17 로트를 그대로 쓴다
				ckLotId = toInt(src.get("ck_mat_lot_id"));
				if (ckLotId == null)
					return fail(result, ckPrefix(country) + "투입할 CK 재고 로트를 선택해주세요.");
				Map<String, Object> lot = getUsableLot(ckLotId, STORE_CK_OUT, mpId);
				if (lot == null || toFloat(lot.get("avail")) < ckQty)
					return fail(result, ckPrefix(country) + "CK 재고가 부족합니다. (필요 " + fmt(ckQty) + ")");
				ckLotNo = str(lot.get("lot_no"));
				usedCkMatId = toInt(lot.get("mat_id"));
			} else {
				// ── CK 새로 생산 ── 국가별 mat_produce 1행 + 구성자재 소비 + 로트 입고
				MaterialProduce ckMp = new MaterialProduce();
				ckMp.setJobResponseId(jr.getId());
				ckMp.setMaterialId(ckMatId);
				ckMp.setProcessId(mp.getProcessId());
				ckMp.setProcessOrder(mp.getProcessOrder());
				ckMp.setLotIndex(nextCkLotIndex(jr.getId()));
				ckMp.set_status("a");
				ckMp.setStoreHouseId(STORE_CK_OUT);
				ckMp.setProductionDate(mp.getProductionDate());
				ckMp.setShiftCode(mp.getShiftCode());
				ckMp.setWorkCenterId(mp.getWorkCenterId());
				ckMp.setEquipmentId(mp.getEquipmentId());
				ckMp.setActorId(mp.getActorId());
				// ★ 'N' = 세션 차수가 아니라 CK 산출 차수. 목록·롤업이 이 값으로 가른다.
				ckMp.setLastProcessYN("N");
				ckLotNo = !isBlank(str(ar.get("ck_lot")))
										? str(ar.get("ck_lot"))
										: this.lotService.make_production_lot_in_number("CK");
				ckMp.setLotNumber(ckLotNo);
				ckMp.setSpjangcd(spjangcd);
				ckMp.setInputQty(ckQty);
				ckMp.setGoodQty(ckQty);
				ckMp.setDefectQty(0f);
				ckMp.setState("finished");
				ckMp.setStartTime(startTs);
				ckMp.setEndTime(endTs);
				ckMp.setDescription("CK 생산" + ctag);
				ckMp.set_audit(user);
				ckMp = this.matProduceRepository.save(ckMp);
				ckMpId = ckMp.getId();

				List<Map<String, Object>> items = this.sqlRunner.getRows("""
                    SELECT pai.id, pai."Material_id" AS mat_id, pai."Qty" AS qty,
                           pai."MatLot_id" AS mat_lot_id, pai."SterileYN" AS sterile_yn
                      FROM pack_alloc_item pai
                     WHERE pai."PackAlloc_id" = :allocId
                       AND COALESCE(pai._status,'a')='a'
                       AND COALESCE(pai."ItemKind",'ck') = 'ck'
                    """, new MapSqlParameterSource().addValue("allocId", allocId));

				for (Map<String, Object> it : items) {
					Integer matId = toInt(it.get("mat_id"));
					float qty     = toFloat(it.get("qty"));
					if (matId == null || qty <= 0) continue;

					Integer fixedLotId = toInt(it.get("mat_lot_id"));
					Material cm = this.materialRepository.getMaterialById(matId);
					String matName = (cm != null ? cm.getName() : ("자재" + matId));

					if (fixedLotId != null) {
						// 필터백 — 지정한 멸균창고 로트에서만
						Map<String, Object> lot = getUsableLot(fixedLotId, STORE_STERIL, mpId);
						if (lot == null)
							return fail(result, ckPrefix(country) + matName + " — 지정한 멸균 로트를 사용할 수 없습니다.");
						float avail = toFloat(lot.get("avail"));
						if (avail < qty)
							return fail(result, ckPrefix(country) + matName + " — 멸균 로트 재고 부족 (필요 "
																		+ fmt(qty) + " / 가용 " + fmt(avail) + ")");
						consumeFixedLot(fixedLotId, qty, avail, ckMp, user, spjangcd);
						writeConsumeRecord(jr, ckMp, matId, qty, STORE_STERIL, str(lot.get("lot_no")),
							"CK 생산 투입" + ctag + " 멸균로트", startTs, endTs, user, spjangcd);
					} else {
						ConsumeResult cr = consumeFifoMulti(matId, CK_SRC_STORES, qty, ckMp, user, spjangcd);
						if (cr.remain > 0)
							return fail(result, ckPrefix(country) + matName
																		+ " — 재고가 부족합니다. (부족 " + fmt(cr.remain) + ")");
						for (Map.Entry<Integer, Float> e : cr.byStore.entrySet())
							writeConsumeRecord(jr, ckMp, matId, e.getValue(), e.getKey(), null,
								"CK 생산 투입" + ctag, startTs, endTs, user, spjangcd);
					}
				}

				// ★ 국가는 로트번호(BSC60-…-CK-JP-260805)에 이미 들어 있다.
				//   여기엔 어느 공장에서 나온 로트인지를 남긴다 — 2공장(M-CELL)도
				//   같은 mat_lot 에 로트를 만들기 때문에 이 구분이 없으면 섞여 보인다.
				receiveLot(ckMatId, ckLotNo, null, ckQty, STORE_CK_OUT,
					"mat_produce", ckMp.getId(), "CK 생산 입고(1공장)", user, spjangcd);
				ckLotId = findLotIdBySource(ckMp.getId(), STORE_CK_OUT);
			}

			// ── kit 세션이면 그 CK 로트를 완제품으로 소비한다 ──
			if (!ckStock) {
				if (ckLotId == null)
					return fail(result, "CK 로트(" + ckLotNo + ")를 생산창고에서 찾을 수 없습니다.");
				Map<String, Object> lot = getUsableLot(ckLotId, STORE_CK_OUT, mpId);
				if (lot == null || toFloat(lot.get("avail")) < ckQty)
					return fail(result, "CK 로트 재고 부족 — " + ckLotNo);
				consumeFixedLot(ckLotId, ckQty, toFloat(lot.get("avail")), mp, user, spjangcd);
				writeConsumeRecord(jr, mp, usedCkMatId, ckQty, STORE_CK_OUT, ckLotNo,
					("stock".equals(mode) ? "CK 자체재고 투입" : "CK 투입") + ctag,
					startTs, endTs, user, spjangcd);
			}

			MapSqlParameterSource up = new MapSqlParameterSource();
			up.addValue("allocId", allocId);
			up.addValue("ckMpId", ckMpId);
			up.addValue("ckMatId", usedCkMatId);
			up.addValue("ckLot", ckLotNo);
			this.sqlRunner.execute("""
                UPDATE pack_alloc
                   SET "CkMatProduce_id" = CAST(:ckMpId AS integer)
                     , "CkMaterial_id"   = :ckMatId
                     , "CkLotNumber"     = :ckLot
                     , "CkState"         = 'produced'
                     , _modified         = now()
                 WHERE id = :allocId
                """, up);
		}

		Map<String, Object> data = new HashMap<>();

		if (ckStock) {
			// ── CK 자체재고 세션 : 재고로 남기고 종료 ──
			//   ★ 세션 mp 는 산출 로트를 만들지 않는다. 실물 CK 는 위 ckMp 가 창고 17 에 넣었다.
			//     그래서 「생산완료 취소」의 사용 여부 검사도 ckMp 쪽 로트를 봐야 한다
			//     (findUsedCkLot). 세션 mp 기준으로 보면 검사가 통째로 헛돈다.
			mp.setInputQty(units);
			mp.setGoodQty(units);
			mp.setDefectQty(0f);
			mp.setState("finished");
			mp.setStartTime(startTs);
			mp.setEndTime(endTs);
			mp.set_audit(user);
			this.matProduceRepository.save(mp);
			closeEquRun(jr, endTs, user);
			recalcJobRes(jr, user);
			data.put("phase", "done");
			data.put("units", units);
			result.data = data;
			return result;
		}

		// ── kit 세션 : 완제품 로트 산출 ★완제 시점 ──
		//   MakerLotNo(외부 UDI)는 아직 없다 — ③ 라벨 스캔에서 채운다
		//   ★ units 는 완제품 단위다(PK 낱개 아님). 여기가 어긋나면 작지 실적까지 전파된다.
		//
		//   ★★ 국가별로 로트를 나눈다.
		//      하나로 합치면 출고에서 국가를 가를 수 없다 — 출고는 mat_lot 에서 재고를
		//      차감하므로, 로트가 하나면 카톤만 국가를 알고 차감은 뭉뚱그려진 로트에서
		//      일어난다. 재고와 실물이 어긋나고 국가별 잔량도 못 낸다.
		//      CK 가 이미 국가별로 로트를 나누고 있으므로(BSC60-…-CK-JP-260805) 규칙도 같다.
		Integer outStoreId = (mp.getStoreHouseId() != null) ? mp.getStoreHouseId() : STORE_PRODUCT;

		// ── 카톤 발번도 여기서 함께 한다 ──
		//   ④가 아니라 여기서 낸다. 작업 순서가 「완제품 산출 → 카톤 라벨 출력 → ④완료」라
		//   ④에서 내면 출력 시점에 번호가 없다.
		float cap = toFloat(boxSpec.get("outbox_cap"));
		float cartonCnt = issueCountryLotsAndCartons(mp, units, cap, ckPer, outStoreId, user, spjangcd);
		String cartonLot = getCartonLot(mpId);

		mp.setInputQty(units);
		mp.setGoodQty(units);
		mp.setDefectQty(0f);
		mp.setStartTime(startTs);
		mp.set_audit(user);
		this.matProduceRepository.save(mp);

		data.put("phase", "label");
		data.put("lot_number", mp.getLotNumber());
		data.put("units", units);
		data.put("pk_per", pkPer);
		data.put("ck_per", ckPer);
		data.put("outbox_cap", cap);
		data.put("carton_cnt", cartonCnt);
		data.put("carton_lot", cartonLot);
		result.data = data;
		return result;
	}

	/**
	 * ② 취소 — 완제품 로트와 PK·IN BOX·CK 소비를 통째로 되돌리고 ①(PK 담김) 으로 복귀.
	 *
	 *   ★ v2 의 keepCons 재삽입이 사라졌다. 인박스와 CK 가 한 단계라 되돌릴 때도 한 덩어리다.
	 *   ★ 국가 배분과 CK 투입자재는 지우지 않고 plan 으로만 되돌린다 —
	 *     다시 입력하게 만들 이유가 없다. 담아둔 PK 도 그대로 남는다.
	 *   ★ 라벨이 이미 스캔됐으면 그것부터 지운다(단계상 ③을 먼저 취소한 셈).
	 *
	 *   ★★ ckstock(CK 자체재고) 세션은 별도 경로로 받는다.
	 *      packFinish 가 mp 를 finished 로 닫으므로 아래 kit 경로의
	 *      "완료된 세션입니다" 가드에 걸려 취소가 영원히 불가능했다.
	 *      kit 의 ④ 완료취소(pack_cancel)와 달리 되돌릴 것이 CK 산출 하나뿐이라
	 *      여기서 상태까지 working 으로 다시 열어준다.
	 */
	public AjaxResult packWorkCancel(Integer mpId, User user) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null) return fail(result, "포장 세션을 찾을 수 없습니다.");

		JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());

		// ── CK 자체재고 세션 : 생산완료 취소 ──
		if (isCkStockSession(mp, jr)) {
			// ★ 만들어 둔 CK 가 이미 포장에 투입됐으면 막는다.
			//   세션 mp 는 로트를 만들지 않으므로 CkMatProduce_id 쪽 로트를 봐야 한다.
			String used = findUsedCkLot(mpId);
			if (used != null)
				return fail(result, "이 CK 로트(" + used + ")가 이미 포장에 투입되어 취소할 수 없습니다.");

			AjaxResult rb = rollbackPackWork(mp, user);
			if (!rb.success) return rb;

			mp.setState("working");
			mp.setEndTime(null);
			mp.set_audit(user);
			this.matProduceRepository.save(mp);

			reopenEquRun(mp);
			recalcJobRes(jr, user);
			return result;
		}

		// ── kit 세션 : ② 반영분만 되돌린다 ──
		if ("finished".equals(mp.getState()))
			return fail(result, "완료된 세션입니다. 완료취소를 먼저 해주세요.");

		String phase = getPhase(mpId);
		if (!"label".equals(phase) && !"outbox".equals(phase))
			return fail(result, "인박스·CK 반영 상태에서만 취소할 수 있습니다.");

		AjaxResult r = rollbackPackWork(mp, user);
		if (!r.success) return r;
		return result;
	}

	/**
	 * 이 세션이 만든 CK 로트 중 이미 소비된 것이 있으면 그 로트번호를 돌려준다.
	 *
	 * ★ rollbackPackWork 의 「출하·사용 확인」은 세션 mp 의 산출 로트만 본다.
	 *   ckstock 세션은 세션 mp 가 로트를 만들지 않으므로 그 검사가 통째로 헛돈다.
	 *   실제 재고는 pack_alloc."CkMatProduce_id" 가 만든 로트에 있다.
	 *
	 * ※ kit 세션에는 쓰지 않는다 — kit 은 CK 를 만든 그 자리에서 바로 소비하므로
	 *   항상 "사용 중"으로 잡혀 정상 취소까지 막힌다.
	 */
	private String findUsedCkLot(Integer mpId) {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT ml."LotNumber" AS lot_no
              FROM pack_alloc pa
              JOIN mat_lot ml ON ml."SourceTableName" = 'mat_produce'
                             AND ml."SourceDataPk"    = pa."CkMatProduce_id"
              JOIN mat_lot_cons mlc ON mlc."MaterialLot_id" = ml.id
             WHERE pa."MatProduce_id" = :mpId
               AND pa."CkMatProduce_id" IS NOT NULL
             LIMIT 1
            """, new MapSqlParameterSource().addValue("mpId", mpId));
		return r == null ? null : str(r.get("lot_no"));
	}

	/** 완료취소 시 equ_run 재개 — packCancel / packWorkCancel(ckstock) 공용 */
	private void reopenEquRun(MaterialProduce mp) {
		MapSqlParameterSource ep = new MapSqlParameterSource();
		ep.addValue("jrId", mp.getJobResponseId());
		ep.addValue("eqId", mp.getEquipmentId());
		this.sqlRunner.execute("""
            UPDATE equ_run SET "RunState"='run', "EndDate"=NULL
             WHERE "JobResponse_id"=:jrId AND "Equipment_id"=:eqId AND "RunState"='complete'
            """, ep);
	}

	/** ②의 되돌리기 본체 — packWorkCancel 과 packDelete 가 함께 쓴다 */
	private AjaxResult rollbackPackWork(MaterialProduce mp, User user) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		Integer mpId = mp.getId();
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);

		// 완제품 로트가 이미 출하·사용됐으면 막는다
		//   (ckstock 세션은 여기에 로트가 없다 → packWorkCancel 이 findUsedCkLot 으로 별도 확인)
		Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS cnt FROM mat_lot ml
              JOIN mat_lot_cons mlc ON mlc."MaterialLot_id" = ml.id
             WHERE ml."SourceTableName" = 'mat_produce' AND ml."SourceDataPk" = :mpId
            """, p);
		if (used != null && toFloat(used.get("cnt")) > 0)
			return fail(result, "완제품이 이미 출하·사용되어 취소가 불가합니다.");

		// 라벨(ckpk/inbox/carton) 제거 + 완제품 MakerLotNo 해제
		this.sqlRunner.execute("DELETE FROM pack_label WHERE \"MatProduce_id\" = :mpId", p);

		// ★ 카톤 개체도 함께 지운다. 남겨두면 ②를 다시 돌릴 때
		//   ux_pack_carton_lot 유니크에 걸려 발번이 실패한다.
		this.sqlRunner.execute("DELETE FROM pack_carton WHERE \"MatProduce_id\" = :mpId", p);

		// ★★ 완제품 로트 연결을 rollbackProduce 보다 먼저 끊는다.
		//    rollbackProduce 가 mat_lot 을 지우는데 pack_alloc."MatLot_id" 가 아직
		//    그 로트를 물고 있으면 pack_alloc_matlot_fk 위반으로 트랜잭션이 통째로 죽는다.
		//    (CkState 되돌리기는 CK 세션 정리 뒤에 하므로 아래에 따로 남아 있다)
		this.sqlRunner.execute("""
            UPDATE pack_alloc
               SET "MatLot_id" = NULL, "LotNumber" = NULL, _modified = now()
             WHERE "MatProduce_id" = :mpId
            """, p);

		// 완제품 입고 + 이 세션의 모든 소비(PK·IN BOX·CK)를 되돌린다
		rollbackProduce(mpId);

		// 생산했던 CK 세션 제거 (자체재고 투입분은 CkMatProduce_id 가 NULL 이라 건너뛴다)
		for (Map<String, Object> r : this.sqlRunner.getRows("""
            SELECT "CkMatProduce_id" AS ck_mp_id FROM pack_alloc
             WHERE "MatProduce_id" = :mpId AND "CkMatProduce_id" IS NOT NULL
            """, p)) {
			Integer ckMpId = toInt(r.get("ck_mp_id"));
			if (ckMpId == null) continue;
			rollbackProduce(ckMpId);
			this.sqlRunner.execute("DELETE FROM mat_produce WHERE id = :ckMpId",
				new MapSqlParameterSource().addValue("ckMpId", ckMpId));
		}

		// 배분은 지우지 않고 계획으로 되돌린다 — 입력을 잃지 않는다
		this.sqlRunner.execute("""
            UPDATE pack_alloc
               SET "CkState"         = 'plan'
                 , "CkMatProduce_id" = NULL
                 , "CkMaterial_id"   = NULL
                 , _modified         = now()
             WHERE "MatProduce_id" = :mpId
            """, p);

		mp.setInputQty(0f);
		mp.setGoodQty(0f);
		mp.setDefectQty(0f);
		mp.set_audit(user);
		this.matProduceRepository.save(mp);
		return result;
	}

	/**
	 * CK 자체재고 세션인가.
	 *
	 *   부모(완제품 헤더)가 없고 + 산출품이 반제품일 때만 그렇다.
	 *   ★ 자체재고로 '완제품'을 지시한 건도 Parent_id 가 없다 —
	 *     그건 일반 포장(kit)이므로 품목 유형을 함께 봐야 한다.
	 */
	private boolean isCkStockSession(MaterialProduce mp, JobRes jr) {
		if (jr == null || jr.getParentId() != null) return false;
		return isSemiMaterial(mp.getMaterialId());
	}

	/** 품목이 반제품인가 — ckstock 판별과 산출창고 결정이 함께 쓴다 */
	private boolean isSemiMaterial(Integer matId) {
		if (matId == null) return false;
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT mg."MaterialType" AS t FROM material m
              JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
             WHERE m.id = :matId
            """, new MapSqlParameterSource().addValue("matId", matId));
		return r != null && "semi".equals(str(r.get("t")));
	}

	/** 배분 합계 — ★CK 낱개 단위 (kit 세션에서는 완제품 × ck_per 와 같아야 한다) */
	private static float sumAlloc(List<Map<String, Object>> allocations) {
		float s = 0f;
		if (allocations != null)
			for (Map<String, Object> a : allocations) s += toFloat(a.get("qty"));
		return s;
	}

	// ── 국가 표기 헬퍼 ────────────────────────────────────────────────────
	//   CK 자체재고 생산은 국가를 쓰지 않는다. 그런데 pack_alloc."CountryCode" 가
	//   NOT NULL 이라 표식 '-' 를 넣는다. 로그·비고·오류 문구에는 그 표식이 보이면 안 된다.

	/** 배분 1건의 국가코드 — 비어 있으면 NO_COUNTRY 로 정규화 */
	private static String countryCodeOf(Map<String, Object> alloc) {
		String c = (alloc == null) ? null : str(alloc.get("country"));
		return isBlank(c) ? NO_COUNTRY : c;
	}

	/** 로그·비고용 꼬리표 — "(KR)" / 국가 미지정이면 "" */
	private static String ckTag(String country) {
		return (country == null || NO_COUNTRY.equals(country)) ? "" : "(" + country + ")";
	}

	/** 오류 메시지용 접두 — "KR · " / 국가 미지정이면 "" */
	private static String ckPrefix(String country) {
		return (country == null || NO_COUNTRY.equals(country)) ? "" : country + " · ";
	}

	// =========================================================================
	// ③ 라벨 스캔 — 완제품 로트에 외부 UDI 를 붙인다
	// =========================================================================

	/**
	 * CK·PK 라벨 + 인박스 라벨 2회 스캔을 저장한다.
	 *
	 *   ★ 완제품이 이미 나온 뒤에 찍는다. 실물 순서(포장 → 라벨 부착 → 확인)와 같다.
	 *   ★ 스캔한 LOT 은 완제품 mat_lot."MakerLotNo" 로 들어간다 —
	 *     사내 로트(P…)와 외부 UDI 로트를 잇는 유일한 고리다.
	 *     (2공장 포장의 "유닛 로트 ↔ 실물 라벨 로트 매칭"과 같은 자리)
	 *
	 * @param labels [{kind:'ckpk'|'inbox', gtin, lot, date, expiry, qty, raw}]
	 */
	public AjaxResult labelScan(Integer mpId, List<Map<String, Object>> labels,
															User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                      return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState())) return fail(result, "완료된 세션입니다.");
		if (!"label".equals(getPhase(mpId)))
			return fail(result, "인박스·CK 반영을 먼저 완료해주세요.");

		Map<String, Object> ck = findLabel(labels, "ckpk");
		Map<String, Object> ib = findLabel(labels, "inbox");

		/*
		 * ★ 라벨 «내용» 검증은 한 곳(warns)에 모으고, 거절 여부는
		 *   STRICT_LABEL_MATCH 하나가 정한다 (2026-08).
		 *
		 *   예전에는 조건마다 그 자리에서 fail 로 빠져나갔다. 그래서
		 *     · 첫 번째로 걸린 것 하나만 알려 주고 나머지는 안 보였고
		 *     · 테스트 중 잠깐 풀려면 조건 다섯 곳을 각각 손대야 했다.
		 *   모아 두면 「무엇이 몇 개 어긋났는지」를 한 번에 보여 줄 수 있고,
		 *   켜고 끄는 자리도 상수 한 곳으로 줄어든다.
		 *
		 *   ⚠ false 로 두면 다른 품목의 라벨을 붙였어도 통과한다.
		 *      스캔값이 곧 완제품 mat_lot."MakerLotNo" 가 되므로, 틀린 값이
		 *      그대로 시스템의 정답이 된다. 운영 배포 전에 true 로 돌릴 것.
		 */
		List<String> warns = new ArrayList<>();

		if (ck == null || ib == null)
			warns.add("라벨 2종(CK·PK / 인박스) 중 한쪽만 스캔되었습니다.");

		// 수량(30)의 유무로 두 라벨을 가르지만, 거래처·기종에 따라 다르게 찍혀 나오기도 한다
		if (ck != null && ck.get("qty") != null)
			warns.add("CK·PK 라벨에 수량(30)이 있습니다 — 인박스 라벨일 수 있습니다.");
		if (ib != null && ib.get("qty") == null)
			warns.add("인박스 라벨에 수량(30)이 없습니다 — CK·PK 라벨일 수 있습니다.");

		String ckGtin = ck == null ? null : str(ck.get("gtin"));
		String ibGtin = ib == null ? null : str(ib.get("gtin"));
		String ckLot  = ck == null ? null : str(ck.get("lot"));
		String ibLot  = ib == null ? null : str(ib.get("lot"));

		if (!isBlank(ckGtin) && !isBlank(ibGtin) && !gtinLike(ckGtin, ibGtin))
			warns.add("CK·PK와 인박스의 GTIN이 다릅니다.");
		if (!isBlank(ckLot) && !isBlank(ibLot) && !lotSame(ckLot, ibLot))
			warns.add("CK·PK와 인박스의 로트가 다릅니다. (" + ckLot + " / " + ibLot + ")");

		String scanGtin = !isBlank(ibGtin) ? ibGtin : ckGtin;
		String udiLot   = !isBlank(ibLot)  ? ibLot  : ckLot;

		String expectGtin = getExpectedGtin(mp.getMaterialId(), spjangcd);
		if (expectGtin != null && !isBlank(scanGtin) && !gtinLike(expectGtin, scanGtin))
			warns.add("GTIN이 작업지시와 다릅니다. (기대 " + expectGtin + " / 스캔 " + scanGtin + ")");

		// 투입한 PK 로트의 UDI 와 대조 — 여러 로트를 섞었으면 '그중 하나와' 맞으면 조용하다
		Set<String> pkUdiSet = getPkUdiSet(mpId);
		if (!isBlank(udiLot) && !pkUdiSet.isEmpty() && !containsLot(pkUdiSet, udiLot))
			warns.add("스캔 라벨(" + udiLot + ")이 투입 PK 로트의 UDI("
									+ String.join(", ", pkUdiSet) + ") 어느 것과도 다릅니다.");

		/*
		 * ★ 거절 모드면 여기서 멈춘다 — 저장 전이라 아무 흔적도 남지 않는다.
		 *   경고 목록은 그대로 실어 보낸다. 화면이 「무엇이 몇 개 어긋났는지」를
		 *   한 번에 보여 줄 수 있어야 작업자가 라벨을 다시 확인할 수 있다.
		 */
		if (STRICT_LABEL_MATCH && !warns.isEmpty()) {
			Map<String, Object> wd = new HashMap<>();
			wd.put("warnings", warns);
			result.data = wd;
			return fail(result, warns.get(0));
		}

		saveScanLabels(mpId, labels, spjangcd, user);

		// ★ 완제품 로트에 외부 UDI 를 붙인다
		if (!isBlank(udiLot)) updateProductMakerLot(mpId, udiLot);

		Map<String, Object> data = new HashMap<>();
		data.put("phase", "outbox");
		data.put("udi_lot", udiLot);
		data.put("warnings", warns);          // 화면이 노란 문구로 보여 준다
		result.data = data;
		return result;
	}

	/**
	 * ③ 취소 — 라벨만 지우고 ②(완제품 산출 직후) 로 복귀.
	 *   완제품 로트는 그대로 두고 MakerLotNo 만 비운다.
	 *   카톤 라벨은 남긴다 — 실물 박스에 이미 붙어 있고, 번호가 바뀌면 어긋난다.
	 */
	public AjaxResult labelCancel(Integer mpId, User user) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                      return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState())) return fail(result, "완료된 세션입니다. 완료취소를 먼저 해주세요.");
		if (!"outbox".equals(getPhase(mpId)))
			return fail(result, "라벨 스캔이 끝난 상태에서만 취소할 수 있습니다.");

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
		this.sqlRunner.execute("""
            DELETE FROM pack_label
             WHERE "MatProduce_id" = :mpId AND "LabelKind" IN ('ckpk','inbox')
            """, p);
		updateProductMakerLot(mpId, null);
		return result;
	}

	/** 투입한 PK 로트들의 외부 UDI 집합 (소비 후에도 mat_lot 행은 남으므로 조회 가능) */
	private Set<String> getPkUdiSet(Integer mpId) {
		Set<String> out = new LinkedHashSet<>();
		for (Map<String, Object> r : this.sqlRunner.getRows("""
            SELECT DISTINCT ml."MakerLotNo" AS udi
              FROM pack_alloc_item pai
              JOIN mat_lot ml ON ml.id = pai."MatLot_id"
             WHERE pai."MatProduce_id" = :mpId
               AND COALESCE(pai._status,'a') = 'a'
               AND COALESCE(pai."ItemKind",'ck') = 'pk'
               AND ml."MakerLotNo" IS NOT NULL
            """, new MapSqlParameterSource().addValue("mpId", mpId))) {
			String v = str(r.get("udi"));
			if (!isBlank(v)) out.add(v);
		}
		return out;
	}

	/** 완제품 로트의 외부 UDI 갱신 (udiLot=null 이면 해제) */
	private void updateProductMakerLot(Integer mpId, String udiLot) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", mpId);
		p.addValue("udi", udiLot);
		this.sqlRunner.execute("""
            UPDATE mat_lot SET "MakerLotNo" = CAST(:udi AS varchar)
             WHERE "SourceTableName" = 'mat_produce' AND "SourceDataPk" = :mpId
            """, p);
	}

	private static Map<String, Object> findLabel(List<Map<String, Object>> labels, String kind) {
		if (labels == null) return null;
		for (Map<String, Object> l : labels)
			if (kind.equals(str(l.get("kind")))) return l;
		return null;
	}

	/**
	 * 생산창고(17)에 남아 있는 CK 자체재고 로트 — ②에서 「투입」으로 고를 후보.
	 *
	 * ★ [자체 재고 생산 지시]로 미리 만들어 둔 CK 가 여기 잡힌다.
	 *   창고를 STORE_CK_OUT(17) 하나로 고정한다 — CK 는 이 자리에만 산출되므로
	 *   폴백 창고를 두면 엉뚱한 데 있는 동일 품목까지 후보로 뜬다.
	 */
	public List<Map<String, Object>> getCkStockLots(Integer ckMaterialId, Integer jrPk, String spjangcd) {
		if (ckMaterialId == null && jrPk != null) ckMaterialId = resolveCkMaterial(jrPk);
		if (ckMaterialId == null) return Collections.emptyList();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", ckMaterialId);
		p.addValue("store", STORE_CK_OUT);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);
		return this.sqlRunner.getRows("""
            SELECT ml.id AS mat_lot_id, ml."LotNumber" AS lot_no
                 , ml."Material_id" AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                 , COALESCE(ml."CurrentStock",0) AS avail
                 , to_char(ml."InputDateTime",'yyyy-mm-dd') AS made_date
                 , ml."Description" AS memo
              FROM mat_lot ml
              JOIN material m ON m.id = ml."Material_id"
             WHERE ml."Material_id" = :matId
               AND ml."StoreHouse_id" = :store
               AND COALESCE(ml."CurrentStock",0) > 0
               AND (CAST(:spjangcd AS varchar) IS NULL OR ml.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
            """, p);
	}

	private void closeEquRun(JobRes jr, Timestamp endTs, User user) {
		try {
			this.equRunRepository.findLatestRunningByJobResponseId(jr.getId()).ifPresent(er -> {
				er.setEndDate(endTs);
				er.setRunState("complete");
				er.set_audit(user);
				this.equRunRepository.save(er);
			});
		} catch (Exception e) {
			// equ_run 종료 실패가 공정 완료를 막지는 않는다
		}
	}

	// =========================================================================
	// ④ 아웃박스 = 카톤 묶기 → 세션 완료
	// =========================================================================

	/**
	 * 아웃박스(카톤) 포장 후 세션 완료.
	 *
	 *   새 품목/로트를 만들지 않는다 — 물류 묶음이라 완제품 로트는 그대로다.
	 *   OUT BOX 자재를 카톤 수만큼 소비한다.
	 *
	 *   ★ 카톤 수는 화면이 보낸 값을 믿지 않고 서버가 완제품수량 ÷ 입수 로 계산한다.
	 *     → pack_carton 이 생기면서 이 전제는 깨졌다. 국가별로 쪼개면 두 값이 갈리므로
	 *       ②가 만든 개체 수(cartonCountOf)를 세어 쓴다.
	 *   ★ GoodQty 는 ②에서 이미 완제품 단위로 들어갔으므로 여기선 환산이 없다.
	 */
	public AjaxResult outboxFinish(Integer mpId, String endTimeStr, User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                      return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"working".equals(mp.getState())) return fail(result, "완료할 수 없는 상태입니다.");
		if (!"outbox".equals(getPhase(mpId)))
			return fail(result, "③ 라벨 스캔을 먼저 완료해주세요.");

		JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());
		if (jr == null) return fail(result, "작업지를 찾을 수 없습니다.");

		Timestamp now = DateUtil.getNowTimeStamp();
		Timestamp endTs = isBlank(endTimeStr) ? now
												: Timestamp.valueOf(LocalDateTime.parse(endTimeStr, DTM));

		Map<String, Object> boxSpec = getBoxSpec(mp.getMaterialId(), spjangcd);
		float units = mp.getGoodQty() == null ? 0f : mp.getGoodQty();   // ★ 완제품 단위
		float cap   = toFloat(boxSpec.get("outbox_cap"));
		// ★ ②에서 실제로 만든 카톤 개수를 센다. 여기서 다시 계산하면 안 된다 —
		//   국가별로 쪼개면 ceil(전체/cap) 과 Σceil(국가별/cap) 이 달라져
		//   ②가 만든 박스 수와 ④가 소비하는 OUT BOX 수가 어긋난다.
		float cartonCnt = cartonCountOf(mpId, units, cap);

		// ── OUT BOX 소비 ──
		@SuppressWarnings("unchecked")
		Map<String, Object> outbox = (Map<String, Object>) boxSpec.get("outbox");
		Integer outboxMatId = outbox == null ? null : toInt(outbox.get("mat_id"));

		if (outboxMatId != null && cartonCnt > 0) {
			float have = stockInStores(outboxMatId, CK_SRC_STORES);
			if (have < cartonCnt)
				return fail(result, "OUT BOX 재고가 부족합니다. (필요 " + fmt(cartonCnt)
															+ " / 재고 " + fmt(have) + ")");
			ConsumeResult cr = consumeFifoMulti(outboxMatId, CK_SRC_STORES, cartonCnt, mp, user, spjangcd);
			if (cr.remain > 0)
				return fail(result, "OUT BOX 재고가 부족합니다. (부족 " + fmt(cr.remain) + ")");
			for (Map.Entry<Integer, Float> e : cr.byStore.entrySet())
				writeConsumeRecord(jr, mp, outboxMatId, e.getValue(), e.getKey(), null,
					"아웃박스(카톤) 포장자재 투입", mp.getStartTime(), endTs, user, spjangcd);
		}

		// ── 세션 완료 ──
		mp.setState("finished");
		mp.setEndTime(endTs);
		mp.set_audit(user);
		this.matProduceRepository.save(mp);

		closeEquRun(jr, endTs, user);
		recalcJobRes(jr, user);

		Map<String, Object> data = new HashMap<>();
		data.put("mp_id", mp.getId());
		data.put("lot_number", mp.getLotNumber());
		data.put("units", mp.getGoodQty());
		data.put("carton_cnt", cartonCnt);
		data.put("carton_lot", getCartonLot(mpId));   // ②에서 발번된 것
		result.data = data;
		return result;
	}

	/**
	 * 국가별 완제품 로트 + 카톤 개체를 만든다. ②(완제) 시점에 한 번 호출.
	 *
	 * 왜 국가별로 나누나:
	 *   출고는 mat_lot 에서 재고를 차감한다. 로트가 차수당 하나면 카톤 라벨만 국가를
	 *   알고 차감은 뭉뚱그려진 로트에서 일어나, 재고와 실물이 어긋난다.
	 *
	 * ★ mat_produce 는 그대로 1행이다. 차수를 국가별로 쪼개면 작지 실적·설비가동·
	 *   단계 파생이 전부 걸린다. 대신 mat_lot."SourceDataPk" = mp.id 가 이제 N행이 된다 —
	 *   이걸 1:1 로 가정하고 조인하는 코드가 있으면 행이 부풀려지므로 함께 점검할 것.
	 *
	 * ★ 카톤 수 계산이 바뀐다.
	 *     예전   ceil(전체유닛 / cap)
	 *     지금   Σ ceil(국가별유닛 / cap)
	 *   국가를 섞어 담을 수 없으니 후자가 실물이다. cap 4 에 JP 6 · KR 6 이면
	 *   예전 3박스, 지금 4박스 — 예전 값이 틀렸던 것이고 OUT BOX 소비도 덜 빠졌다.
	 *
	 * ★ pack_alloc."Qty" 는 CK 낱개 기준이라 ckPer 로 나눠 완제품 단위로 환산한다.
	 *   나눗셈 오차로 합계가 units 와 어긋나면 마지막 국가에서 보정한다 —
	 *   보정하지 않으면 로트 재고 합계가 GoodQty 와 달라진다.
	 *
	 * @return 이 차수의 총 카톤 수
	 */
	private float issueCountryLotsAndCartons(MaterialProduce mp, float units, float cap,
																					 float ckPer, Integer outStoreId,
																					 User user, String spjangcd) {
		Integer mpId = mp.getId();
		if (cap <= 0) cap = 4f;
		if (ckPer <= 0) ckPer = 1f;

		List<Map<String, Object>> allocs = this.sqlRunner.getRows("""
            SELECT pa.id AS alloc_id, pa."CountryCode" AS country, pa."Qty" AS qty
              FROM pack_alloc pa
             WHERE pa."MatProduce_id" = :mpId AND COALESCE(pa._status,'a') = 'a'
               AND pa."Qty" > 0
             ORDER BY pa.id
            """, new MapSqlParameterSource().addValue("mpId", mpId));

		// 배분이 없으면 예전처럼 로트 하나. 국가를 안 쓰는 현장도 있으므로 막지 않는다
		if (allocs == null || allocs.isEmpty()) {
			receiveLot(mp.getMaterialId(), mp.getLotNumber(), null, units, outStoreId,
				"mat_produce", mpId, "포장 완제품 입고", user, spjangcd);
			float cnt = cartonCount(units, cap);
			issueCartonLabel(mp, cnt, null, spjangcd, user);
			return cnt;
		}

		// 국가별 완제품 수량 — 마지막 국가에서 잔여를 보정
		int n = allocs.size();
		float[] qtys = new float[n];
		float assigned = 0f;
		for (int i = 0; i < n; i++) {
			float q = Math.round(toFloat(allocs.get(i).get("qty")) / ckPer);
			if (i == n - 1) q = units - assigned;
			if (q < 0) q = 0f;
			qtys[i] = q;
			assigned += q;
		}

		String cartonBase = this.lotService.make_production_lot_in_number(CARTON_LOT_PREFIX);
		float totalCartons = 0f;

		for (int i = 0; i < n; i++) {
			Map<String, Object> a = allocs.get(i);
			Integer allocId = toInt(a.get("alloc_id"));
			String country  = str(a.get("country"));
			float qty       = qtys[i];
			if (qty <= 0) continue;

			boolean named = !(isBlank(country) || NO_COUNTRY.equals(country));
			String lotNo  = named ? mp.getLotNumber() + "-" + country : mp.getLotNumber();

			receiveLot(mp.getMaterialId(), lotNo, null, qty, outStoreId,
				"mat_produce", mpId, "포장 완제품 입고" + ckTag(country), user, spjangcd);

			Integer matLotId = findLotIdByNumber(lotNo, mpId);

			MapSqlParameterSource up = new MapSqlParameterSource();
			up.addValue("allocId", allocId);
			up.addValue("lotId", matLotId);
			up.addValue("lotNo", lotNo);
			this.sqlRunner.execute("""
                UPDATE pack_alloc
                   SET "MatLot_id" = CAST(:lotId AS integer)
                     , "LotNumber" = :lotNo
                     , _modified   = now()
                 WHERE id = :allocId
                """, up);

			// ── 카톤 개체 ──
			int cnt = (int) cartonCount(qty, cap);
			float remain = qty;
			for (int no = 1; no <= cnt; no++) {
				float boxQty = Math.min(cap, remain);
				remain -= boxQty;
				String cartonNo = cartonBase
														+ (named ? "-" + country : "")
														+ String.format("-%02d", no);

				MapSqlParameterSource cp = new MapSqlParameterSource();
				cp.addValue("mpId", mpId);
				cp.addValue("allocId", allocId);
				cp.addValue("country", isBlank(country) ? NO_COUNTRY : country);
				cp.addValue("no", no);
				cp.addValue("lotNo", cartonNo);
				cp.addValue("qty", boxQty);
				cp.addValue("matLotId", matLotId);
				// ★ 사람이 읽는 출처 기록. mat_lot."Description"(「포장 완제품 입고」 등)과 같은 규칙 —
				//   조인이 끊기거나 코드 마스터가 비어도 이 한 줄이면 어디서 생긴 행인지 안다.
				cp.addValue("memo", "카톤 포장" + ckTag(country)
															+ " · " + lotNo + " · " + no + "/" + cnt + "박스");
				cp.addValue("spjangcd", spjangcd);
				cp.addValue("userId", user == null ? null : user.getId());
				this.sqlRunner.execute("""
                    INSERT INTO pack_carton
                           ("MatProduce_id","PackAlloc_id","CountryCode","CartonNo",
                            "CartonLotNo","Qty","MatLot_id","Description",
                            _status,_created,_creater_id,spjangcd)
                    VALUES (:mpId, :allocId, :country, :no,
                            :lotNo, CAST(:qty AS float8), CAST(:matLotId AS integer), :memo,
                            'a', now(), CAST(:userId AS integer), CAST(:spjangcd AS varchar))
                    """, cp);
			}
			totalCartons += cnt;
		}

		// ★ 대표 라벨도 개체와 같은 채번(cartonBase)을 쓴다.
		//   따로 발번하면 대표번호 C-…-0014 와 실물 C-…-0013-JP-01 의 접두가 어긋나
		//   화면에서 서로 다른 카톤처럼 보인다.
		issueCartonLabel(mp, totalCartons, cartonBase, spjangcd, user);
		return totalCartons;
	}

	/**
	 * pack_label 의 carton 행 — 차수 대표 번호 + 총 박스 수.
	 *
	 * ★ 개체는 pack_carton 이 들고, 이 행은 대표값으로만 남긴다.
	 *   ux_pack_label (MatProduce_id, LabelKind) 유니크가 차수당 1행을 강제하는데,
	 *   그 유니크는 ckpk/inbox 의 UPSERT(saveLabel 의 ON CONFLICT)를 지탱하고 있어
	 *   풀면 라벨 저장 전체가 흔들린다. 그래서 건드리지 않는다.
	 */
	private void issueCartonLabel(MaterialProduce mp, float cartonCnt, String base,
																String spjangcd, User user) {
		if (cartonCnt <= 0) return;
		String exist = getCartonLot(mp.getId());
		String lot = !isBlank(exist) ? exist
									 : !isBlank(base)          ? base
											 : this.lotService.make_production_lot_in_number(CARTON_LOT_PREFIX);
		saveLabel(mp.getId(), "carton",
			getExpectedGtin(mp.getMaterialId(), spjangcd), lot,
			null, null, cartonCnt, null, spjangcd, user);
	}

	/** 로트번호로 이 차수의 산출 로트 id. 국가별로 N행이라 findLotIdBySource 로는 못 가른다 */
	private Integer findLotIdByNumber(String lotNumber, Integer mpId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("lotNo", lotNumber);
		p.addValue("mpId", mpId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT ml.id FROM mat_lot ml
             WHERE ml."LotNumber" = :lotNo
               AND ml."SourceTableName" = 'mat_produce'
               AND ml."SourceDataPk" = :mpId
             ORDER BY ml.id DESC LIMIT 1
            """, p);
		return (r == null) ? null : toInt(r.get("id"));
	}

	/** 이 차수의 카톤 개수 — pack_carton 이 진실. 없으면(구 데이터) 계산으로 떨어진다 */
	private float cartonCountOf(Integer mpId, float units, float cap) {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS cnt FROM pack_carton
             WHERE "MatProduce_id" = :mpId AND COALESCE(_status,'a') = 'a'
            """, new MapSqlParameterSource().addValue("mpId", mpId));
		float cnt = (r == null) ? 0f : toFloat(r.get("cnt"));
		return (cnt > 0) ? cnt : cartonCount(units, cap);
	}

	private static float cartonCount(float units, float cap) {
		if (units <= 0 || cap <= 0) return 0f;
		return (float) Math.ceil(units / cap);
	}

	// =========================================================================
	// 완료취소 / 삭제 / 시간수정
	// =========================================================================

	/**
	 * 완료취소 — ④ 아웃박스만 되돌린다.
	 *
	 *   ★ 완제품 로트는 ②에서 나왔으므로 건드리지 않는다.
	 *     되돌릴 대상은 OUT BOX 소비 하나뿐이다.
	 *   ★ 라벨도 남긴다 — 실물에 이미 붙어 있다.
	 *   ※ ckstock 세션은 여기 오지 않는다. 화면이 /pack_work_cancel 로 보낸다.
	 */
	public AjaxResult packCancel(Integer mpId, User user) {
		AjaxResult result = new AjaxResult();
		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                        return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!"finished".equals(mp.getState()))  return fail(result, "완료 상태가 아닙니다.");

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);

		Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS cnt FROM mat_lot ml
              JOIN mat_lot_cons mlc ON mlc."MaterialLot_id" = ml.id
             WHERE ml."SourceTableName" = 'mat_produce' AND ml."SourceDataPk" = :mpId
            """, p);
		if (used != null && toFloat(used.get("cnt")) > 0)
			return fail(result, "완제품이 이미 출하·사용되어 완료취소가 불가합니다.");

		// ── OUT BOX 소비만 되돌린다 ──
		Map<String, Object> boxSpec = getBoxSpec(mp.getMaterialId(), mp.getSpjangcd());
		@SuppressWarnings("unchecked")
		Map<String, Object> outbox = (Map<String, Object>) boxSpec.get("outbox");
		Integer outboxMatId = outbox == null ? null : toInt(outbox.get("mat_id"));

		if (outboxMatId != null) {
			MapSqlParameterSource op = new MapSqlParameterSource();
			op.addValue("mpId", mpId);
			op.addValue("matId", outboxMatId);

			for (Map<String, Object> r : this.sqlRunner.getRows("""
                    SELECT mlc.id FROM mat_lot_cons mlc
                      JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                     WHERE mlc."SourceTableName"='mat_produce' AND mlc."SourceDataPk" = :mpId
                       AND ml."Material_id" = :matId
                    """, op)) {
				this.matLotConsRepository.deleteById(((Number) r.get("id")).intValue());
			}

			MapSqlParameterSource cp = new MapSqlParameterSource();
			cp.addValue("jrId", mp.getJobResponseId());
			cp.addValue("po",   mp.getProcessOrder());
			cp.addValue("li",   mp.getLotIndex());
			cp.addValue("matId", outboxMatId);
			this.sqlRunner.execute("""
                DELETE FROM mat_inout
                 WHERE "SourceTableName"='mat_consu' AND "InOut"='out'
                   AND "SourceDataPk" IN (
                       SELECT id FROM mat_consu
                        WHERE "JobResponse_id" = :jrId AND "ProcessOrder" = :po
                          AND "LotIndex" = :li AND "Material_id" = :matId)
                """, cp);
			this.sqlRunner.execute("""
                DELETE FROM mat_consu
                 WHERE "JobResponse_id" = :jrId AND "ProcessOrder" = :po
                   AND "LotIndex" = :li AND "Material_id" = :matId
                """, cp);
		}

		mp.setState("working");
		mp.setEndTime(null);
		mp.set_audit(user);
		this.matProduceRepository.save(mp);

		reopenEquRun(mp);

		JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());
		recalcJobRes(jr, user);

		result.success = true;
		return result;
	}

	/**
	 * 세션 삭제 — working 상태만. ★삭제하면 작지가 「작업 전」으로 돌아간다.
	 *
	 *   되돌리는 순서
	 *     ② 반영분(완제품·PK·IN BOX·CK) → 라벨 → 배분/PK 행 → equ_run → mat_produce
	 *     → 자동발행 작지면 통째 삭제 / 아니면 recalcJobRes 가 ordered 로 복원
	 *
	 *   ★ mat_produce 를 JPA deleteById 로 지우면 뒤따르는 raw SQL 이 flush 되지 않은
	 *     그 행을 그대로 본다. 세션 수를 잘못 세어 작지 정리를 건너뛰고
	 *     「작업 0건인데 생산완료」 유령 카드가 남았다. 그래서 raw SQL 로 지운다.
	 */
	public AjaxResult packDelete(Integer mpId, User user) {
		AjaxResult result = new AjaxResult();
		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null)                       return fail(result, "포장 세션을 찾을 수 없습니다.");
		if ("finished".equals(mp.getState()))  return fail(result, "완료된 포장은 삭제할 수 없습니다. (완료취소 후 삭제)");

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
		String phase = getPhase(mpId);

		// ② 이후면 완제품·소비를 먼저 되돌린다
		if ("label".equals(phase) || "outbox".equals(phase)) {
			AjaxResult rb = rollbackPackWork(mp, user);
			if (!rb.success) return rb;
		} else {
			// ★ 여기도 mat_lot 을 지우는 경로다. pack_carton 과 pack_alloc."MatLot_id" 가
			//   물고 있으면 FK 위반으로 죽으므로 먼저 끊는다.
			this.sqlRunner.execute("DELETE FROM pack_carton WHERE \"MatProduce_id\" = :mpId", p);
			this.sqlRunner.execute("""
                UPDATE pack_alloc
                   SET "MatLot_id" = NULL, "LotNumber" = NULL, _modified = now()
                 WHERE "MatProduce_id" = :mpId
                """, p);
			// ①/② 단계라도 혹시 남은 소비가 있으면 정리
			rollbackProduce(mpId);
		}

		// 담아둔 PK · 배분 · 라벨 정리
		this.sqlRunner.execute("""
            DELETE FROM pack_alloc_item WHERE "PackAlloc_id" IN
                   (SELECT id FROM pack_alloc WHERE "MatProduce_id" = :mpId)
            """, p);
		this.sqlRunner.execute("DELETE FROM pack_alloc_item WHERE \"MatProduce_id\" = :mpId", p);
		// ★ pack_carton 이 PackAlloc_id 로 pack_alloc 을 물고 있다 — 먼저 지워야 FK 에 안 걸린다
		this.sqlRunner.execute("DELETE FROM pack_carton     WHERE \"MatProduce_id\" = :mpId", p);
		this.sqlRunner.execute("DELETE FROM pack_alloc      WHERE \"MatProduce_id\" = :mpId", p);
		this.sqlRunner.execute("DELETE FROM pack_label      WHERE \"MatProduce_id\" = :mpId", p);

		MapSqlParameterSource ep = new MapSqlParameterSource();
		ep.addValue("jrId", mp.getJobResponseId());
		ep.addValue("eqId", mp.getEquipmentId());
		this.sqlRunner.execute("""
            DELETE FROM equ_run
             WHERE "JobResponse_id"=:jrId AND "Equipment_id"=:eqId AND "RunState"='run'
            """, ep);

		Integer jrId = mp.getJobResponseId();

		// ★ raw SQL 삭제 — flush 타이밍에 걸리지 않는다.
		//   영속성 컨텍스트에 남은 mp 를 먼저 떼어내야 커밋 때 되살아나지 않는다.
		this.entityManager.flush();
		this.entityManager.detach(mp);
		this.sqlRunner.execute("DELETE FROM mat_produce WHERE id = :mpId", p);

		// 자동발행 작지면 통째로 정리. 아니면 실적을 다시 집계해 「작업 전」으로 되돌린다
		if (!cleanupAutoJobRes(jrId)) {
			JobRes jr2 = this.jobResRepository.getJobResById(jrId);
			recalcJobRes(jr2, user);
		}

		result.success = true;
		return result;
	}

	/**
	 * 자동발행 작지 정리 — 세션이 하나도 안 남았고 SourceTableName='pack' 일 때만.
	 * 남겨두면 포장 큐에 실적 0건 카드가 영구히 뜬다.
	 *
	 * @return 작지를 실제로 지웠으면 true
	 */
	private boolean cleanupAutoJobRes(Integer jrId) {
		if (jrId == null) return false;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jrId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT jr."Parent_id" AS hdr_id
                 , COALESCE(hdr."SourceTableName",'') AS src
                 , (SELECT COUNT(*) FROM mat_produce mp2
                     WHERE mp2."JobResponse_id" = jr.id) AS mp_cnt
              FROM job_res jr
              LEFT JOIN job_res hdr ON hdr.id = jr."Parent_id"
             WHERE jr.id = :jrId
            """, p);
		if (r == null) return false;
		if (!"pack".equals(str(r.get("src")))) return false;
		if (toFloat(r.get("mp_cnt")) > 0) return false;

		Integer hdrId = toInt(r.get("hdr_id"));
		this.sqlRunner.execute("DELETE FROM job_res WHERE id = :jrId", p);
		if (hdrId != null) {
			MapSqlParameterSource hp = new MapSqlParameterSource().addValue("hdrId", hdrId);
			this.sqlRunner.execute("""
                DELETE FROM job_res
                 WHERE id = :hdrId
                   AND NOT EXISTS (SELECT 1 FROM job_res c WHERE c."Parent_id" = :hdrId)
                   AND NOT EXISTS (SELECT 1 FROM mat_produce mp3 WHERE mp3."JobResponse_id" = :hdrId)
                """, hp);
		}
		return true;
	}

	public AjaxResult packUpdateTime(Integer mpId, String startTimeStr, String endTimeStr, User user) {
		AjaxResult result = new AjaxResult();
		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null) return fail(result, "포장 세션을 찾을 수 없습니다.");
		if (!isBlank(startTimeStr)) mp.setStartTime(Timestamp.valueOf(LocalDateTime.parse(startTimeStr, DTM)));
		if (!isBlank(endTimeStr))   mp.setEndTime(Timestamp.valueOf(LocalDateTime.parse(endTimeStr, DTM)));
		mp.set_audit(user);
		this.matProduceRepository.save(mp);
		result.success = true;
		return result;
	}

	// =========================================================================
	// 내부 — 단계(파생)
	// =========================================================================

	/** 단계 조회 — 저장된 값이 아니라 재고 흔적에서 파생 */
	private String getPhase(Integer mpId) {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT (__PHASE__) AS phase FROM mat_produce mp WHERE mp.id = :mpId
            """.replace("__PHASE__", PHASE_SQL),
			new MapSqlParameterSource().addValue("mpId", mpId));
		return r == null ? "pk" : str(r.get("phase"));
	}

	/**
	 * 이 세션이 담은 PK 합계. ★PK 낱개 단위다 — 완제품 수량이 아니다.
	 *
	 * ★ v3.2 까지는 이 값이 그대로 완제품 수량이었다(배수 1 가정).
	 *   호출부는 반드시 ÷ pk_per 해서 쓸 것.
	 */
	private float pkTotal(Integer mpId) {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT COALESCE(SUM(pai."Qty"),0) AS q
              FROM pack_alloc_item pai
             WHERE pai."MatProduce_id" = :mpId
               AND COALESCE(pai._status,'a')='a'
               AND COALESCE(pai."ItemKind",'ck') = 'pk'
            """, new MapSqlParameterSource().addValue("mpId", mpId));
		return r == null ? 0f : toFloat(r.get("q"));
	}

	private int nextCkLotIndex(Integer jrId) {
		// ★ raw SQL 이라 방금 만든 세션 차수가 안 보이면 차수 번호가 겹친다
		this.entityManager.flush();
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT COALESCE(MAX("LotIndex"),0) + 1 AS n FROM mat_produce
             WHERE "JobResponse_id" = :jrId
            """, new MapSqlParameterSource().addValue("jrId", jrId));
		return r == null ? 1 : (int) toFloat(r.get("n"));
	}

	// =========================================================================
	// 내부 — 배분 저장
	// =========================================================================

	private void writeAllocations(Integer mpId, List<Map<String, Object>> allocations,
																String spjangcd, User user) {
		MapSqlParameterSource dp = new MapSqlParameterSource().addValue("mpId", mpId);
		// 생산되지 않은(plan) 배분만 교체한다.
		// ★ PK 행은 PackAlloc_id 가 NULL 이고 MatProduce_id 로 매달려 있어 여기서 안 지워진다.
		this.sqlRunner.execute("""
            DELETE FROM pack_alloc_item WHERE "PackAlloc_id" IN
                   (SELECT id FROM pack_alloc
                     WHERE "MatProduce_id" = :mpId AND COALESCE("CkState",'plan') = 'plan')
            """, dp);
		this.sqlRunner.execute("""
            DELETE FROM pack_alloc
             WHERE "MatProduce_id" = :mpId AND COALESCE("CkState",'plan') = 'plan'
            """, dp);

		if (allocations == null) return;
		Integer userId = (user == null ? null : user.getId());

		for (Map<String, Object> a : allocations) {
			float qty = toFloat(a.get("qty"));
			if (qty <= 0) continue;

			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("mpId", mpId);
			p.addValue("countryId", toInt(a.get("country_id")));
			p.addValue("code", countryCodeOf(a));       // ★ 빈 값 → NO_COUNTRY (NOT NULL 회피)
			p.addValue("name", str(a.get("country_name")));
			p.addValue("qty", qty);
			p.addValue("ckLot", str(a.get("ck_lot")));
			// ★ 출처 기록 — Qty 가 CK 낱개 기준이라는 점을 행 자체에 남긴다.
			//   완제품 단위와 헷갈려 잘못 읽는 사고가 반복되는 값이다.
			p.addValue("memo", "포장 국가배분" + ckTag(countryCodeOf(a))
													 + " · CK 낱개 " + fmt(qty));
			p.addValue("spjangcd", spjangcd);
			p.addValue("userId", userId);

			Map<String, Object> row = this.sqlRunner.getRow("""
                INSERT INTO pack_alloc
                       ("MatProduce_id","Country_id","CountryCode","CountryName","Qty","CkLotNumber",
                        "CkState","Description",_status,_created,_creater_id,spjangcd)
                VALUES (:mpId, CAST(:countryId AS integer), :code, :name, CAST(:qty AS float8), :ckLot,
                        'plan',:memo,'a',now(),CAST(:userId AS integer),CAST(:spjangcd AS varchar))
                RETURNING id
                """, p);
			if (row == null) continue;
			Integer allocId = ((Number) row.get("id")).intValue();

			for (Map<String, Object> it : itemsOf(a)) {
				float iq = toFloat(it.get("qty"));
				if (iq <= 0) continue;
				Integer lotId = toInt(it.get("mat_lot_id"));
				insertAllocItem(allocId, null, toInt(it.get("mat_id")), iq, lotId,
					lotId != null ? "Y" : "N", "ck", spjangcd, user);
			}
		}
	}

	/**
	 * 배분 하위 행 1건.
	 *
	 * @param itemKind 'ck' = CK 구성자재 / 'pk' = 투입 PK 로트
	 * @param sterile  'Y' = 지정 멸균로트(필터백). PK 행은 'N' — PK 여부는 itemKind 가 말한다
	 */
	private void insertAllocItem(Integer allocId, Integer mpId, Integer matId, float qty,
															 Integer matLotId, String sterile, String itemKind,
															 String spjangcd, User user) {
		if (matId == null || qty <= 0) return;
		if (allocId == null && mpId == null) return;   // CHECK 제약(둘 중 하나)
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("allocId", allocId);
		p.addValue("mpId", mpId);
		p.addValue("matId", matId);
		p.addValue("qty", qty);
		p.addValue("lotId", matLotId);
		p.addValue("sterile", sterile);
		p.addValue("kind", itemKind);
		// ★ 출처 기록. 이 테이블은 한 몸에 성격이 다른 두 행이 산다 —
		//   ck = 배분에 매달린 CK 구성자재 / pk = 세션에 직접 매달린 투입 PK 로트.
		//   ItemKind 만 보고 판단하다 헷갈리는 일이 많아 행 자체에 남긴다.
		p.addValue("memo", ("pk".equals(itemKind) ? "포장 담긴 PK 로트" : "CK 구성자재")
												 + " · " + fmt(qty)
												 + ("Y".equals(sterile) ? " · 지정 멸균로트" : ""));
		p.addValue("spjangcd", spjangcd);
		p.addValue("userId", user == null ? null : user.getId());
		this.sqlRunner.execute("""
            INSERT INTO pack_alloc_item
                   ("PackAlloc_id","MatProduce_id","Material_id","Qty","MatLot_id","SterileYN","ItemKind",
                    "Description",_status,_created,_creater_id,spjangcd)
            VALUES (CAST(:allocId AS integer), CAST(:mpId AS integer), :matId,
                    CAST(:qty AS float8), CAST(:lotId AS integer), :sterile, :kind,
                    :memo,'a',now(),CAST(:userId AS integer),CAST(:spjangcd AS varchar))
            """, p);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> itemsOf(Map<String, Object> alloc) {
		Object o = alloc.get("items");
		return (o instanceof List) ? (List<Map<String, Object>>) o : Collections.emptyList();
	}

	// =========================================================================
	// 내부 — 재고
	// =========================================================================

	/** 여러 창고 합계 재고 */
	private float stockInStores(Integer matId, int[] stores) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		List<Integer> list = new ArrayList<>();
		for (int s : stores) list.add(s);
		p.addValue("stores", list);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT COALESCE(SUM(ml."CurrentStock"),0) AS q
              FROM mat_lot ml
             WHERE ml."Material_id" = :matId
               AND ml."StoreHouse_id" IN (:stores)
               AND COALESCE(ml."CurrentStock",0) > 0
            """, p);
		return r == null ? 0f : toFloat(r.get("q"));
	}

	private static class ConsumeResult {
		float remain;
		/** 창고별 실제 차감량 — mat_consu/mat_inout 을 창고 단위로 남기기 위함 */
		LinkedHashMap<Integer, Float> byStore = new LinkedHashMap<>();
	}

	/**
	 * 창고 우선순위 FIFO 차감.
	 *
	 *   ★ 17 고정으로는 안 된다. IN BOX(M-HS00006)가 창고 19 에만 있어
	 *     17 고정 FIFO 는 항상 '재고 부족'으로 실패한다.
	 *     stores 순서대로 훑고, 각 창고 안에서는 InputDateTime ASC.
	 */
	private ConsumeResult consumeFifoMulti(Integer matId, int[] stores, float qty,
																				 MaterialProduce mp, User user, String spjangcd) {
		ConsumeResult out = new ConsumeResult();
		float remain = qty;

		for (int storeId : stores) {
			if (remain <= EPS) break;
			MapSqlParameterSource lp = new MapSqlParameterSource();
			lp.addValue("matId", matId);
			lp.addValue("storeId", storeId);
			List<Map<String, Object>> lots = this.sqlRunner.getRows("""
                SELECT ml.id AS ml_id, COALESCE(ml."CurrentStock",0) AS cs
                  FROM mat_lot ml
                 WHERE ml."Material_id" = :matId
                   AND ml."StoreHouse_id" = :storeId
                   AND COALESCE(ml."CurrentStock",0) > 0
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                """, lp);
			if (lots == null) continue;

			for (Map<String, Object> lot : lots) {
				if (remain <= EPS) break;
				float cs = toFloat(lot.get("cs"));
				float take = Math.min(cs, remain);
				if (take <= 0) continue;
				consumeFixedLot(((Number) lot.get("ml_id")).intValue(), take, cs, mp, user, spjangcd);
				out.byStore.merge(storeId, take, Float::sum);
				remain -= take;
			}
		}
		out.remain = Math.max(0f, remain);
		return out;
	}

	/**
	 * 지정 로트 1건의 가용재고 (다른 세션이 잡아둔 PK 예약분 제외).
	 *
	 *   예약 = PK 를 담아두고 아직 ②를 안 한 세션의 PK 행.
	 *   자기 세션(excludeMpId)은 자기 것이므로 가용으로 친다.
	 */
	private Map<String, Object> getUsableLot(Integer matLotId, Integer storeId, Integer excludeMpId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matLotId", matLotId);
		p.addValue("storeId", storeId);
		p.addValue("exMp", excludeMpId);
		return this.sqlRunner.getRow("""
            SELECT ml.id                        AS mat_lot_id
                 , ml."LotNumber"               AS lot_no
                 , ml."MakerLotNo"              AS udi_lot
                 , ml."Material_id"             AS mat_id
                 , GREATEST(COALESCE(ml."CurrentStock",0) - COALESCE(rp.rq,0), 0) AS avail
              FROM mat_lot ml
              LEFT JOIN LATERAL (
                  SELECT COALESCE(SUM(pai."Qty"),0) AS rq
                    FROM pack_alloc_item pai
                    JOIN mat_produce mp2 ON mp2.id = pai."MatProduce_id"
                   WHERE pai."MatLot_id" = ml.id
                     AND COALESCE(pai._status,'a')='a'
                     AND COALESCE(pai."ItemKind",'ck') = 'pk'
                     AND mp2."State" = 'working'
                     AND NOT EXISTS (SELECT 1 FROM pack_alloc pa2
                                      WHERE pa2."MatProduce_id" = mp2.id
                                        AND COALESCE(pa2."CkState",'plan') = 'produced')
                     AND (CAST(:exMp AS integer) IS NULL OR mp2.id <> CAST(:exMp AS integer))
              ) rp ON true
             WHERE ml.id = :matLotId
               AND ml."StoreHouse_id" = :storeId
               AND COALESCE(ml."CurrentStock",0) > 0
            """, p);
	}

	/**
	 * 특정 mat_produce 가 산출한 로트 id.
	 *
	 * ★ receiveLot 이 JPA 로 저장한 직후에 raw SQL 로 되짚으므로 flush 가 필요하다.
	 *   안 하면 방금 만든 CK 로트를 못 찾아 "생산창고에서 찾을 수 없습니다" 가 뜬다.
	 */
	private Integer findLotIdBySource(Integer srcMpId, Integer storeId) {
		if (srcMpId == null) return null;
		this.entityManager.flush();
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", srcMpId);
		p.addValue("storeId", storeId);
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT ml.id FROM mat_lot ml
             WHERE ml."SourceTableName" = 'mat_produce' AND ml."SourceDataPk" = :mpId
               AND ml."StoreHouse_id" = :storeId
               AND COALESCE(ml."CurrentStock",0) > 0
             ORDER BY ml.id DESC LIMIT 1
            """, p);
		return r == null ? null : toInt(r.get("id"));
	}

	/** 지정 로트에서만 차감 (FIFO 대체 없음) */
	private void consumeFixedLot(Integer matLotId, float qty, float currentStock,
															 MaterialProduce mp, User user, String spjangcd) {
		MatLotCons mlc = new MatLotCons();
		mlc.setMaterialLotId(matLotId);
		mlc.setOutputDateTime(DateUtil.getNowTimeStamp());
		mlc.setSourceDataPk(mp.getId());
		mlc.setSourceTableName("mat_produce");
		mlc.setCurrentStock(currentStock);
		mlc.setOutputQty(qty);
		mlc.setSpjangcd(spjangcd);
		mlc.set_audit(user);
		this.matLotConsRepository.save(mlc);
	}

	/**
	 * 생산 입고 — mat_lot + mat_inout(in).
	 *
	 * ★ InOut='in' 이면 InputQty, 'out' 이면 OutputQty.
	 *   matinout_tri 가 이 컬럼으로 mat_in_house / material.CurrentStock 를 집계한다.
	 *   out 에 InputQty 를 넣으면 차감이 아니라 가산된다(2공장에서 재고가 2배로 부푼 사고).
	 */
	private void receiveLot(Integer matId, String lotNumber, String makerLotNo, float qty,
													Integer storeId, String srcTable, Integer srcPk, String memo,
													User user, String spjangcd) {
		Timestamp now = DateUtil.getNowTimeStamp();

		MaterialLot lot = new MaterialLot();
		lot.setLotNumber(lotNumber);
		lot.setMakerLotNo(makerLotNo);
		lot.setMaterialId(matId);
		lot.setInputDateTime(now);
		lot.setInputQty(qty);
		lot.setCurrentStock(qty);
		lot.setDescription(memo);
		lot.setSourceDataPk(srcPk);
		lot.setSourceTableName(srcTable);
		lot.setStoreHouseId(storeId);
		lot.setSpjangcd(spjangcd);
		lot.set_audit(user);
		this.matLotRepository.save(lot);

		MaterialInout io = new MaterialInout();
		io.setMaterialId(matId);
		io.setStoreHouseId(storeId);
		io.setLotNumber(lotNumber);
		io.setInOut("in");
		io.setInputType("produced_in");
		io.setInputQty(qty);                 // ★ in → InputQty
		io.setInoutDate(LocalDate.parse(LocalDate.now().format(DF)));
		io.setInoutTime(LocalTime.parse(LocalTime.now().format(TF)));
		io.setDescription(memo);
		io.setState("confirmed");
		io.set_status("a");
		io.setSourceDataPk(srcPk);
		io.setSourceTableName(srcTable);
		io.setSpjangcd(spjangcd);
		io.set_audit(user);
		this.matInoutRepository.save(io);
	}

	/** mat_consu + mat_inout(out) — 자재 1종 × 창고 1곳당 1건 */
	private void writeConsumeRecord(JobRes jr, MaterialProduce mp, Integer matId, float qty,
																	Integer storeId, String lotNumber, String desc,
																	Timestamp startTs, Timestamp endTs,
																	User user, String spjangcd) {
		MaterialConsume mc = new MaterialConsume();
		mc.setJobResponseId(jr.getId());
		mc.setMaterialId(matId);
		mc.setProcessOrder(mp.getProcessOrder());
		mc.setLotIndex(mp.getLotIndex());
		mc.setStartTime(startTs);
		mc.setEndTime(endTs);
		mc.setDescription(desc);
		mc.setBomQty(qty);
		mc.setConsumedQty(qty);
		mc.setState("finished");
		mc.set_status("a");
		mc.setStoreHouseId(storeId);      // ★ 실제 차감 창고의 진실은 여기다
		mc.setSpjangcd(spjangcd);
		mc.set_audit(user);
		mc = this.matConsuRepository.save(mc);

		MaterialInout io = new MaterialInout();
		io.setMaterialId(matId);
		io.setStoreHouseId(storeId);
		io.setLotNumber(lotNumber);
		io.setInOut("out");
		io.setOutputType("consumed_out");
		io.setOutputQty(qty);                // ★ out → OutputQty
		io.setInoutDate(LocalDate.parse(LocalDate.now().format(DF)));
		io.setInoutTime(LocalTime.parse(LocalTime.now().format(TF)));
		io.setDescription(desc);
		io.setState("confirmed");
		io.set_status("a");
		io.setSourceDataPk(mc.getId());
		io.setSourceTableName("mat_consu");
		io.setSpjangcd(spjangcd);
		io.set_audit(user);
		this.matInoutRepository.save(io);
	}

	/**
	 * 한 mat_produce 의 입고·차감 롤백.
	 *
	 * ★ mat_lot 과 mat_inout 을 양쪽 다 지운다.
	 *   이력만 남으면 트리거 재집계가 이동을 잊어 집계와 실제가 갈린다.
	 */
	private void rollbackProduce(Integer mpId) {
		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		if (mp == null) return;

		// ★ raw SQL 로 지운다. 방금 JPA 로 만든 행이 컨텍스트에 남아 있으면
		//   커밋 시점에 되살아나므로 먼저 내려보낸다.
		this.entityManager.flush();

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);

		this.sqlRunner.execute("""
            DELETE FROM mat_lot
             WHERE "SourceTableName"='mat_produce' AND "SourceDataPk" = :mpId
            """, p);
		this.sqlRunner.execute("""
            DELETE FROM mat_inout
             WHERE "SourceTableName"='mat_produce' AND "SourceDataPk" = :mpId AND "InOut"='in'
            """, p);

		// 차감 롤백 — 트리거가 원 로트 CurrentStock 을 복원한다
		for (Map<String, Object> r : this.sqlRunner.getRows("""
            SELECT id FROM mat_lot_cons
             WHERE "SourceTableName"='mat_produce' AND "SourceDataPk" = :mpId
            """, p)) {
			this.matLotConsRepository.deleteById(((Number) r.get("id")).intValue());
		}

		// mat_consu + 그 소비 이력(mat_inout out)
		//
		// ★ mat_consu 는 mp id 가 아니라 (JobResponse_id, ProcessOrder, LotIndex) 로 묶인다.
		//   설명 문자열로 찾으면 같은 작지의 다른 세션 이력까지 지운다.
		//   반드시 차수 키로 특정할 것.
		MapSqlParameterSource cp = new MapSqlParameterSource();
		cp.addValue("jrId", mp.getJobResponseId());
		cp.addValue("po",   mp.getProcessOrder());
		cp.addValue("li",   mp.getLotIndex());

		this.sqlRunner.execute("""
            DELETE FROM mat_inout
             WHERE "SourceTableName"='mat_consu' AND "InOut"='out'
               AND "SourceDataPk" IN (
                   SELECT id FROM mat_consu
                    WHERE "JobResponse_id" = :jrId
                      AND "ProcessOrder"   = :po
                      AND "LotIndex"       = :li)
            """, cp);
		this.sqlRunner.execute("""
            DELETE FROM mat_consu
             WHERE "JobResponse_id" = :jrId AND "ProcessOrder" = :po AND "LotIndex" = :li
            """, cp);
	}

	// =========================================================================
	// 내부 — 라벨 / GTIN
	// =========================================================================

	// ── 라벨 값 비교 (화면 prod_process_opack_bsc_t.html 의 lotSame / gtinLike 와 같은 규칙) ──

	/** 로트 비교용 정규화 — 대소문자·하이픈·공백 차이로 어긋나지 않게 */
	private static String lotKey(String v) {
		return v == null ? "" : v.toUpperCase().replaceAll("[\\s\\-_./]", "");
	}

	private static boolean lotSame(String a, String b) {
		return lotKey(a).equals(lotKey(b));
	}

	private static boolean containsLot(Set<String> set, String lot) {
		for (String s : set) if (lotSame(s, lot)) return true;
		return false;
	}

	/**
	 * GTIN 비교 — 앞자리 «포장 인디케이터»와 선행 0 차이를 무시한다.
	 *
	 *   (0)8806…789 낱개 / 18806…789 인박스 / 28806…789 카톤 은 같은 품목이다.
	 *   문자열을 그대로 비교하던 시절엔 정상 라벨도 「GTIN 불일치」로 튕겼다.
	 */
	private static String gtinCore(String v) {
		if (v == null) return "";
		String s = v.replaceAll("\\D", "");
		if (s.length() >= 14) s = s.substring(s.length() - 13);
		return s.replaceFirst("^0+", "");
	}

	private static boolean gtinLike(String a, String b) {
		String x = gtinCore(a), y = gtinCore(b);
		if (x.isEmpty() || y.isEmpty()) return true;
		if (x.equals(y)) return true;
		if (x.length() >= 12 && y.length() >= 12)
			return x.substring(x.length() - 12).equals(y.substring(y.length() - 12));
		return false;
	}

	private String getExpectedGtin(Integer materialId, String spjangcd) {
		if (materialId == null) return null;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("materialId", materialId);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);
		Map<String, Object> row = this.sqlRunner.getRow("""
            SELECT mb."GTIN" AS gtin FROM material_barcode mb
             WHERE mb."Material_id" = :materialId
               AND mb."Company_id" IS NULL
               AND mb."GTIN" IS NOT NULL
               AND COALESCE(mb._status,'a') = 'a'
               AND (CAST(:spjangcd AS varchar) IS NULL OR mb.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY CASE WHEN COALESCE(mb."PrimaryYN",'Y')='Y' THEN 0 ELSE 1 END
                    , CASE mb."PackLevel" WHEN 'each' THEN 0 WHEN 'inbox' THEN 1 ELSE 2 END
                    , mb.id
             LIMIT 1
            """, p);
		return row == null ? null : str(row.get("gtin"));
	}

	public List<Map<String, Object>> getProductBarcodes(Integer materialId, Integer jrPk, String spjangcd) {
		if (materialId == null && jrPk != null) materialId = resolveProductMaterial(jrPk);
		if (materialId == null) return Collections.emptyList();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("materialId", materialId);
		p.addValue("spjangcd", isBlank(spjangcd) ? null : spjangcd);
		return this.sqlRunner.getRows("""
            SELECT mb."PackLevel" AS pack_level, mb."GTIN" AS gtin, mb."UdiDi" AS udi_di
                 , mb."BarcodeType" AS barcode_type
                 , COALESCE(mb."PackQty",1)     AS pack_qty
                 , COALESCE(mb."PrimaryYN",'Y') AS primary_yn
              FROM material_barcode mb
             WHERE mb."Material_id" = :materialId
               AND mb."Company_id" IS NULL
               AND COALESCE(mb._status,'a') = 'a'
               AND (CAST(:spjangcd AS varchar) IS NULL OR mb.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY CASE mb."PackLevel" WHEN 'each' THEN 0 WHEN 'inbox' THEN 1 ELSE 2 END, mb.id
            """, p);
	}

	/**
	 * 라벨 1행 저장 (UPSERT) — ckpk / inbox / carton.
	 *
	 * ★ ux_pack_label (MatProduce_id, LabelKind) 유니크가 중복을 막는다.
	 *   DELETE 후 INSERT 로 하면 kind 를 나눠 저장할 수 없다 —
	 *   carton 을 넣는 순간 ckpk/inbox 가 날아가고 단계 파생이 되돌아간다.
	 * ★ ckpk/inbox 라벨 행의 존재가 곧 '③ 라벨 완료' 표식이다(단계 파생).
	 *   labelCancel / packWorkCancel / packDelete 만 의도적으로 지운다.
	 */
	private void saveLabel(Integer mpId, String kind, String gtin, String lot,
												 String date, String expiry, Float qty, String raw,
												 String spjangcd, User user) {
		if (mpId == null || isBlank(kind)) return;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", mpId);
		p.addValue("kind", kind);
		p.addValue("gtin", gtin);
		p.addValue("lot", lot);
		p.addValue("date", date);
		p.addValue("expiry", expiry);
		p.addValue("qty", qty);
		p.addValue("raw", raw);
		p.addValue("spjangcd", spjangcd);
		p.addValue("userId", user == null ? null : user.getId());

		this.sqlRunner.execute("""
            INSERT INTO pack_label
                   ("MatProduce_id","LabelKind","Gtin","LotNo","MakeDate","ExpiryDate","Qty","RawData",
                    _status,_created,_creater_id,spjangcd)
            VALUES (:mpId,:kind,:gtin,:lot,:date,:expiry,CAST(:qty AS float8),:raw,
                    'a',now(),CAST(:userId AS integer),CAST(:spjangcd AS varchar))
            ON CONFLICT ("MatProduce_id","LabelKind") DO UPDATE
               SET "Gtin"       = EXCLUDED."Gtin"
                 , "LotNo"      = EXCLUDED."LotNo"
                 , "MakeDate"   = EXCLUDED."MakeDate"
                 , "ExpiryDate" = EXCLUDED."ExpiryDate"
                 , "Qty"        = EXCLUDED."Qty"
                 , "RawData"    = EXCLUDED."RawData"
            """, p);
	}

	/** 스캔 라벨 2행 (ckpk + inbox). carton 은 여기로 안 들어온다 */
	private void saveScanLabels(Integer mpId, List<Map<String, Object>> labels,
															String spjangcd, User user) {
		if (labels == null) return;
		for (Map<String, Object> l : labels) {
			String kind = str(l.get("kind"));
			if (!"ckpk".equals(kind) && !"inbox".equals(kind)) continue;
			saveLabel(mpId, kind, str(l.get("gtin")), str(l.get("lot")),
				str(l.get("date")), str(l.get("expiry")),
				l.get("qty") == null ? null : toFloat(l.get("qty")),
				str(l.get("raw")), spjangcd, user);
		}
	}

	/** ②에서 발번한 카톤 로트번호 */
	private String getCartonLot(Integer mpId) {
		Map<String, Object> r = this.sqlRunner.getRow("""
            SELECT "LotNo" AS lot FROM pack_label
             WHERE "MatProduce_id" = :mpId AND "LabelKind" = 'carton'
            """, new MapSqlParameterSource().addValue("mpId", mpId));
		return r == null ? null : str(r.get("lot"));
	}

	// =========================================================================
	// 내부 — 작지
	// =========================================================================

	private Integer resolveOutStore(Integer workCenterId) {
		if (workCenterId == null) return STORE_PRODUCT;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("wcId", workCenterId);
		Map<String, Object> row = this.sqlRunner.getRow(
			"SELECT \"ProcessStoreHouse_id\" AS sh FROM work_center WHERE id = :wcId", p);
		Integer sh = (row == null) ? null : toInt(row.get("sh"));
		return (sh != null) ? sh : STORE_PRODUCT;
	}

	/**
	 * 작지 양품 합계 재계산. 헤더 작지도 같이 맞춘다.
	 *
	 * ★ 세션이 하나도 없으면 「작업 전」으로 되돌린다 —
	 *   State='ordered', 수량 0, StartTime/EndTime null.
	 *   안 하면 세션을 다 지웠는데 작지가 working/finished 로 남아
	 *   포장 큐에 유령 카드가 계속 뜬다(fix_pack_ghost_job.sql 로 치웠던 그 증상).
	 *
	 * ★ 국가별 CK 산출 차수(LastProcessYN='N')는 세지 않는다.
	 *   ckstock 작지에서는 세션 차수와 품목이 같아, 빼지 않으면 GoodQty 가 2배가 된다.
	 *
	 * ★ v3.3 : mp."GoodQty" 가 완제품 단위이므로 여기 집계도 완제품 단위다.
	 *   OrderQty 와 단위가 같아 (good+defect) >= orderQty 비교가 성립한다.
	 *   v3.2 까지는 GoodQty 에 PK 낱개가 들어가 배수 품목의 작지가
	 *   지시량의 1/배수 만 채워도 'finished' 로 닫혔다.
	 */
	private void recalcJobRes(JobRes jr, User user) {
		if (jr == null) return;

		// ★★ 아래 집계는 raw SQL 이다. 호출자가 방금 JPA 로 save 한 mat_produce 가
		//    아직 DB 에 없으면 이전 값을 집계해 작지가 'working · GoodQty 0' 으로 남는다.
		//    (CK 생산 완료했는데 카드가 「생산중 · 진행 0%」로 보이던 증상)
		this.entityManager.flush();

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jr.getId());
		Map<String, Object> sum = this.sqlRunner.getRow("""
            SELECT COALESCE(SUM(mp."GoodQty"),0)   AS good_qty
                 , COALESCE(SUM(mp."DefectQty"),0) AS defect_qty
                 , COUNT(*)                                          AS sess_cnt
                 , COUNT(*) FILTER (WHERE mp."State" <> 'finished')   AS unfinished
              FROM mat_produce mp
              JOIN job_res j ON j.id = mp."JobResponse_id"
              LEFT JOIN job_res hdr ON hdr.id = j."Parent_id"
             WHERE mp."JobResponse_id" = :jrId
               AND COALESCE(mp._status,'a') = 'a'
               AND COALESCE(mp."LastProcessYN",'Y') = 'Y'
               AND COALESCE(mp."Material_id",0) = COALESCE(hdr."Material_id", j."Material_id")
            """, p);
		if (sum == null) return;

		float good    = toFloat(sum.get("good_qty"));
		float defect  = toFloat(sum.get("defect_qty"));
		int   sessCnt = (int) toFloat(sum.get("sess_cnt"));
		jr.setGoodQty(good);
		jr.setDefectQty(defect);

		float orderQty = jr.getOrderQty() == null ? 0f : jr.getOrderQty();
		boolean allDone = toFloat(sum.get("unfinished")) == 0;
		boolean met = sessCnt > 0 && allDone && orderQty > 0 && (good + defect) >= orderQty;

		if (met) {
			jr.setState("finished");
			jr.setEndTime(DateUtil.getNowTimeStamp());
		} else if (sessCnt > 0) {
			jr.setState("working");
			jr.setEndTime(null);
		} else {
			// ★ 세션 0건 = 작업 전
			jr.setState("ordered");
			jr.setStartTime(null);
			jr.setEndTime(null);
		}
		jr.set_audit(user);
		this.jobResRepository.save(jr);

		// 헤더 작지도 맞춘다 — 안 하면 작지 화면 진행률이 영원히 0 또는 100
		if (jr.getParentId() != null) {
			JobRes hdr = this.jobResRepository.getJobResById(jr.getParentId());
			if (hdr != null) {
				hdr.setGoodQty(good);
				hdr.setDefectQty(defect);
				hdr.setState(jr.getState());
				hdr.setEndTime("finished".equals(jr.getState()) ? DateUtil.getNowTimeStamp() : null);
				if (sessCnt == 0) hdr.setStartTime(null);
				hdr.set_audit(user);
				this.jobResRepository.save(hdr);
			}
		}
	}

	// ── 작은 유틸 ──
	private static AjaxResult fail(AjaxResult r, String msg) {
		r.success = false;
		r.message = msg;
		return r;
	}
	private static boolean isBlank(String s) { return s == null || s.isBlank(); }
	private static String  str(Object o)     { return o == null ? null : String.valueOf(o); }
	private static String  fmt(float f) {
		return (f == Math.rint(f)) ? String.valueOf((long) f) : String.valueOf(f);
	}
	private static float toFloat(Object o) {
		if (o == null) return 0f;
		if (o instanceof Number) return ((Number) o).floatValue();
		try { return Float.parseFloat(String.valueOf(o)); } catch (Exception e) { return 0f; }
	}
	private static Integer toInt(Object o) {
		if (o == null) return null;
		if (o instanceof Number) return ((Number) o).intValue();
		try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
	}
}