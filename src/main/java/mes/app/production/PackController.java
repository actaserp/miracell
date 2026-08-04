package mes.app.production;

import mes.app.production.service.CountryService;
import mes.app.production.service.PackService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 포장(bsc05) 컨트롤러 — v3.
 *
 *   ① PK 담기      작지 BOM 의 PK 품목을 서버가 멸균창고에서 FIFO 자동 배정 · DB 저장
 *   ② 인박스 · CK  PK + IN BOX + CK 를 한 번에 소비 → 완제품 로트  ★완제 시점
 *   ③ 라벨 스캔    CK·PK + 인박스 2회 → 완제품 mat_lot."MakerLotNo" 에 외부 UDI 기록
 *   ④ 아웃박스     OUT BOX 소비 + 세션 완료
 *
 * ═══ v2 → v3 에서 없어진 엔드포인트 ═══
 *   /inbox_finish + /ck_produce → **/
	@RestController
	@RequestMapping("/api/production/pack")
	public class PackController {

		@Autowired private PackService    packService;
		@Autowired private CountryService countryService;

		// =================================================================
		// 조회
		// =================================================================

		@GetMapping("/context")
		public AjaxResult context(
			@RequestParam(value = "factory_id", required = false) Integer factoryId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getContext(factoryId, spjangcd);
			r.success = true;
			return r;
		}

		/**
		 * 포장 작지 목록.
		 *
		 * ※ 공용 /read_by_process 를 안 쓴다 — 포장 카드는 단계 진행률과 「작지 없음」
		 *   배지를 보여줘야 한다. 작지 필터 규칙(Parent_id NOT NULL / 워크센터 조인 /
		 *   완료 포함)은 getJobResByProcess 와 글자 그대로 맞춰 두었다.
		 */
		@GetMapping("/order_list")
		public AjaxResult orderList(
			@RequestParam("date_from") String dateFrom,
			@RequestParam("date_to")   String dateTo,
			@RequestParam(value = "item", required = false) String item,
			@RequestParam(value = "is_include_comp", required = false, defaultValue = "true") boolean includeComp,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getOrderList(dateFrom, dateTo, item, includeComp, spjangcd);
			r.success = true;
			return r;
		}

		/**
		 * 멸균창고(18) 잔여 로트.
		 *
		 * kind = 'pk'  → 블리스터(bsc03) 산출 = 포장 대기 PK
		 *        'bag' → 융착(bsc06) 산출 = 필터백
		 *
		 * ★ SterilizationYN 으로 나누지 않는다. PK 도 멸균을 타야 해서 'Y' 가 되므로
		 *   필터백과 값이 같아진다. 산출 공정(WorkCenter_id → process."Code")으로 나눈다.
		 *
		 * @param excludeMp 이 세션이 잡아둔 수량은 예약분에서 빼고 계산(자기 것은 가용).
		 *                  ★ v3 는 세션 생성과 동시에 PK 를 잡으므로 예약이 바로 반영된다.
		 */
		@GetMapping("/steril_lots")
		public AjaxResult sterilLots(
			@RequestParam(value = "kind", required = false) String kind,
			@RequestParam(value = "material_id", required = false) Integer materialId,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "exclude_mp", required = false) Integer excludeMp,
			@RequestParam(value = "exclude_ordered", required = false, defaultValue = "false") boolean excludeOrdered,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getSterilizedLots(kind, materialId, keyword, spjangcd, excludeMp, excludeOrdered);
			r.success = true;
			return r;
		}

		/** 무작지 시작 시 고를 완제품 후보 — 1공장 완제품 + 그 CK 반제품 / PK 품목 */
		@GetMapping("/product_candidates")
		public AjaxResult productCandidates(
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getProductCandidates(keyword, spjangcd);
			r.success = true;
			return r;
		}

		@GetMapping("/product_barcodes")
		public AjaxResult productBarcodes(
			@RequestParam(value = "material_id", required = false) Integer materialId,
			@RequestParam(value = "jr_pk", required = false) Integer jrPk,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getProductBarcodes(materialId, jrPk, spjangcd);
			r.success = true;
			return r;
		}

		/** IN/OUT BOX 입수량 — 카톤 입수 = 1 / bom_comp."Amount" (0.25 → 4) */
		@GetMapping("/box_spec")
		public AjaxResult boxSpec(
			@RequestParam("material_id") Integer materialId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getBoxSpec(materialId, spjangcd);
			r.success = true;
			return r;
		}

		/**
		 * CK 반제품 구성자재.
		 *
		 * ★ 완제품 BOM 이 아니라 CK 반제품 BOM 을 본다.
		 *   완제품 BOM 직하위는 PK/CK/IN BOX/OUT BOX 4행뿐이고, 실제 투입 12종은
		 *   CK 반제품 BOM 에 있다.
		 */
		@GetMapping("/ck_bom")
		public AjaxResult ckBom(
			@RequestParam(value = "jr_pk", required = false) Integer jrPk,
			@RequestParam(value = "ck_material_id", required = false) Integer ckMaterialId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getCkBom(ckMaterialId, jrPk, spjangcd);
			r.success = true;
			return r;
		}

		/**
		 * ② 「CK 자체재고 투입」 후보 — 생산창고(17)에 남아 있는 CK 로트.
		 * CK 자체재고 작지로 미리 만들어 둔 것이 여기 잡힌다.
		 *
		 * ※ session_detail 이 같은 목록을 함께 내려주므로 화면은 보통 이 API 를 따로 안 부른다.
		 *   재고만 다시 확인하고 싶을 때를 위해 남겨 둔다.
		 */
		@GetMapping("/ck_stock_lots")
		public AjaxResult ckStockLots(
			@RequestParam(value = "jr_pk", required = false) Integer jrPk,
			@RequestParam(value = "ck_material_id", required = false) Integer ckMaterialId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getCkStockLots(ckMaterialId, jrPk, spjangcd);
			r.success = true;
			return r;
		}

		@GetMapping("/workers")
		public AjaxResult workers(
			@RequestParam(value = "factory_id", required = false) Integer factoryId,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getWorkers(factoryId, keyword, spjangcd);
			r.success = true;
			return r;
		}

		@GetMapping("/session_list")
		public AjaxResult sessionList(@RequestParam("jr_pk") Integer jrPk) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getSessionList(jrPk);
			r.success = true;
			return r;
		}

		/**
		 * C화면 복원.
		 *
		 *   ★ v3 는 화면 메모리가 없다 — 여기서 내려주는 값이 곧 화면 상태다.
		 *     pk_lots / allocations / items / labels / ck_stock_lots / ck_material_id /
		 *     order_remain / cartons(계산)
		 */
		@GetMapping("/session_detail")
		public AjaxResult sessionDetail(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
			AjaxResult r = new AjaxResult();
			r.data = this.packService.getSessionDetail(mpId, spjangcd);
			r.success = true;
			return r;
		}

		// =================================================================
		// 세션 시작
		// =================================================================

		/**
		 * 세션 시작 (작지 있음).
		 *
		 *   ★ v3 : 세션을 만든 직후 서버가 PK 를 자동 배정한다.
		 *     대상 품목 = 완제품 BOM 직하위 중 블리스터(bsc03) 산출물
		 *     수량      = 작지 잔여(지시량 − 다른 세션이 이미 잡은 양)
		 *     로트      = 멸균창고 FIFO
		 *     못 잡아도 실패로 만들지 않는다 — 세션은 열리고 화면에서 담으면 된다.
		 *     응답의 auto_pk_qty / auto_pk_cnt / auto_pk_note 로 결과를 알린다.
		 */
		@PostMapping("/pack_start")
		@Transactional
		public AjaxResult packStart(
			@RequestParam("jr_pk") Integer jrPk,
			@RequestParam("equipment_id") Integer equipmentId,
			@RequestParam("worker_id") Integer workerId,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.packStart(jrPk, equipmentId, workerId, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		/**
		 * 세션 시작 (작지 없음).
		 *
		 *   멸균창고 PK 로트를 골라 바로 시작한다. mat_produce."JobResponse_id" 가
		 *   NOT NULL 이라 포장 전용 작지 2행(헤더=완제품 + 자식=CK/포장)을 자동발행한다.
		 *
		 *   pk_lots(JSON) : [{"mat_lot_id":301,"qty":8},{"mat_lot_id":277,"qty":2}]
		 *                   합계가 계획수량이 된다.
		 *
		 *   ★ v3 : 고른 PK 를 여기서 바로 DB 에 저장한다(화면 메모리 폐기).
		 *     작지를 만들기 전에 로트를 먼저 검증하므로, 실패해도 지울 껍데기가 안 생긴다.
		 */
		@PostMapping("/pack_start_nojob")
		@Transactional
		public AjaxResult packStartNoJob(
			@RequestParam("product_mat_id") Integer productMatId,
			@RequestParam(value = "order_qty", required = false) Float orderQty,
			@RequestParam("pk_lots") String pkLotsJson,
			@RequestParam("equipment_id") Integer equipmentId,
			@RequestParam("worker_id") Integer workerId,
			@RequestParam(value = "prod_date", required = false) String prodDate,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			List<Map<String, Object>> pkLots = CommonUtil.loadJsonListMap(pkLotsJson);

			AjaxResult r = this.packService.packStartNoJob(
				productMatId, orderQty, pkLots, equipmentId, workerId, prodDate, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// ① PK 담기 — 재고 이동 없음
		// =================================================================

		/**
		 * PK 로트 교체 저장.
		 *
		 *   자동 배정된 결과를 사람이 고치는 경로다. 다시 부르면 통째로 교체된다.
		 *   로트를 전부 빼면 단계가 ①로 돌아간다.
		 *   편집 가능 단계는 'pk' / 'pack' 뿐 — ② 반영 후에는 먼저 포장을 취소해야 한다.
		 *
		 *   pk_lots(JSON) : [{"mat_lot_id":301,"qty":8}]  · 빈 배열이면 비우기
		 */
		@PostMapping("/pk_save")
		@Transactional
		public AjaxResult pkSave(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam("pk_lots") String pkLotsJson,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			List<Map<String, Object>> pkLots = CommonUtil.loadJsonListMap(pkLotsJson);

			AjaxResult r = this.packService.savePkLots(mpId, pkLots, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// ② 인박스 + CK — ★완제 시점
		// =================================================================

		/**
		 * 국가 배분·CK 투입자재를 계획(plan)으로 저장 — 화면이 값을 바꿀 때마다 부른다.
		 *
		 *   ★ 재고는 전혀 움직이지 않는다. 그래서 새로고침·다른 태블릿 진입에도
		 *     입력이 그대로 남는다(v2 의 화면 메모리를 대체하는 자리).
		 *   ★ 이미 반영된(CkState='produced') 배분은 건드리지 않는다.
		 *
		 *   allocations(JSON) — /pack_finish 와 같은 모양. 검증은 하지 않는다.
		 */
		@PostMapping("/alloc_save")
		@Transactional
		public AjaxResult allocSave(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam(value = "allocations", required = false) String allocationsJson,
			@RequestParam(value = "spjangcd", required = false) String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			List<Map<String, Object>> allocations =
				(allocationsJson == null || allocationsJson.isBlank())
					? java.util.Collections.emptyList()
					: CommonUtil.loadJsonListMap(allocationsJson);

			AjaxResult r = this.packService.saveAllocations(mpId, allocations, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		/**
		 * 인박스 + CK 반영 ★완제 시점.
		 *
		 *   소비 : 담아둔 PK(멸균창고 18) + IN BOX(17→3→19 FIFO) + CK(국가별)
		 *   산출 : 완제품 로트 1건 → 제품창고(4). MakerLotNo 는 비워둔다(③에서 채운다)
		 *
		 *   ckstock 세션이면 CK 만 만들어 생산창고(17)에 남기고 세션을 닫는다.
		 *
		 * allocations(JSON) — 배분 + 국가별 조달 방식(mode):
		 *   mode='produce' → items 의 자재를 소비해 CK 를 새로 만든다
		 *   mode='stock'   → ck_mat_lot_id 의 CK 자체재고를 그대로 투입한다
		 *   [{ "country":"KR", "country_id":1, "country_name":"한국(국내)", "qty":14,
		 *      "mode":"produce", "ck_lot":"BMSC120-CK-KR-260730",
		 *      "items":[ {"mat_id":117,"qty":28},                       // 17→3→19 FIFO
		 *                {"mat_id":142,"qty":14,"mat_lot_id":789} ] },  // 필터백 = 지정 멸균로트
		 *    { "country":"CN", "qty":6, "mode":"stock", "ck_mat_lot_id":3105,
		 *      "items":[ {"mat_id":<CK품목>,"qty":6,"mat_lot_id":3105} ] }]  // ★복원용 한 줄
		 *
		 *   ★ mode 를 담는 컬럼은 없다. 「자체재고 투입」은 items 에 CK 품목 한 줄로
		 *     저장되고, 화면이 session_detail 의 ck_material_id 와 대조해 방식을 복원한다.
		 *     투입 자재 자체가 곧 조달 방식이다.
		 *
		 *   필터백은 지정 로트에서만 뺀다 — 멸균 배치 추적이 끊기면 안 되므로
		 *   부족해도 다른 로트로 자동 대체하지 않는다.
		 */
		@PostMapping("/pack_finish")
		@Transactional
		public AjaxResult packFinish(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam("allocations") String allocationsJson,
			@RequestParam(value = "start_time", required = false) String startTime,
			@RequestParam(value = "end_time", required = false) String endTime,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			List<Map<String, Object>> allocations = CommonUtil.loadJsonListMap(allocationsJson);

			AjaxResult r = this.packService.packFinish(mpId, allocations, startTime, endTime, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		/**
		 * ② 취소 — 완제품 로트와 PK·IN BOX·CK 소비를 통째로 되돌린다.
		 *
		 *   ★ 담아둔 PK 와 국가 배분은 지우지 않는다. CkState 만 'plan' 으로 돌린다 —
		 *     다시 입력하게 만들 이유가 없다.
		 *   ★ 라벨이 이미 스캔됐으면 그것부터 지운다(③을 먼저 취소한 셈).
		 *   ★ 완제품이 이미 출하·사용됐으면 거부한다.
		 *
		 *   ckstock 세션의 「완료취소」도 이 엔드포인트를 쓴다.
		 */
		@PostMapping("/pack_work_cancel")
		@Transactional
		public AjaxResult packWorkCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.packWorkCancel(mpId, user);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// ③ 라벨 스캔 — 완제품이 나온 뒤에 찍는다
		// =================================================================

		/**
		 * CK·PK 라벨 + 인박스 라벨 2회 스캔 저장.
		 *
		 *   ★ 스캔한 LOT 은 완제품 mat_lot."MakerLotNo" 로 들어간다 —
		 *     사내 로트(P…)와 외부 UDI 로트를 잇는 유일한 고리다.
		 *
		 *   서버 검증(화면과 같은 규칙):
		 *     1. 수량(30) 유무로 두 라벨을 가른다 (반대로 찍으면 막는다)
		 *     2. 두 라벨의 GTIN·LOT 이 서로 같아야 한다
		 *     3. material_barcode 의 기대 GTIN 과 같아야 한다
		 *     4. 투입 PK 로트의 MakerLotNo 어느 하나와 맞아야 한다(여러 로트를 섞을 수 있다)
		 *
		 * labels(JSON):
		 *   [{ "kind":"ckpk",  "gtin":"08801234560056","lot":"BMJ260730","date":"260730",
		 *      "raw":"(01)...(10)...(11)..." },
		 *    { "kind":"inbox", "gtin":"08801234560056","lot":"BMJ260730","date":"260730",
		 *      "qty":1, "raw":"(01)...(10)...(30)1(11)..." }]
		 */
		@PostMapping("/label_scan")
		@Transactional
		public AjaxResult labelScan(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam(value = "labels", required = false) String labelsJson,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			List<Map<String, Object>> labels =
				(labelsJson == null || labelsJson.isBlank()) ? null : CommonUtil.loadJsonListMap(labelsJson);

			AjaxResult r = this.packService.labelScan(mpId, labels, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		/**
		 * ③ 취소 — 라벨만 지우고 완제품 MakerLotNo 를 비운다.
		 *   완제품 로트와 카톤 라벨은 그대로 둔다(실물 박스에 이미 붙어 있다).
		 */
		@PostMapping("/label_cancel")
		@Transactional
		public AjaxResult labelCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.labelCancel(mpId, user);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// ④ 아웃박스 (카톤) → 세션 완료
		// =================================================================

		/**
		 * 아웃박스 포장 후 세션 완료 ★재고 이동.
		 *
		 *   새 품목/로트를 만들지 않는다 — 물류 묶음이라 완제품 로트는 그대로다.
		 *   OUT BOX 자재를 카톤 수만큼 소비한다.
		 *
		 *   ★ 카톤 목록을 받지 않는다. 저장할 테이블(pack_carton)이 없고,
		 *     카톤 수는 완제품수량 ÷ 입수 로 서버가 항상 같은 답을 낸다.
		 *     화면의 카톤 라벨은 인쇄용이라 DB 에 남길 필요가 없다.
		 *     (카톤 로트번호만 ②에서 발번해 pack_label(kind='carton') 에 1행 남는다)
		 */
		@PostMapping("/outbox_finish")
		@Transactional
		public AjaxResult outboxFinish(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam(value = "end_time", required = false) String endTime,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.outboxFinish(mpId, endTime, user, spjangcd);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// 취소 / 삭제 / 시각
		// =================================================================

		/**
		 * 완료취소 — ④ 아웃박스만 되돌린다.
		 *
		 *   ★ 완제품 로트는 ②에서 나왔으므로 건드리지 않는다.
		 *     되돌릴 대상은 OUT BOX 소비 하나뿐이다. 라벨도 남긴다.
		 */
		@PostMapping("/pack_cancel")
		@Transactional
		public AjaxResult packCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.packCancel(mpId, user);
			if (!r.success) rollback();
			return r;
		}

		/**
		 * 세션 삭제 ★작지가 「작업 전」으로 돌아간다.
		 *
		 *   ② 반영분 롤백 → 라벨·배분·PK 행 삭제 → equ_run 삭제 → mat_produce 삭제
		 *   → 자동발행 작지면 통째 삭제 / 아니면 recalcJobRes 가 'ordered' 로 복원
		 *     (State='ordered', GoodQty 0, StartTime/EndTime null)
		 *
		 *   ★ mat_produce 는 raw SQL 로 지운다. JPA deleteById 는 flush 전이라
		 *     뒤따르는 raw SQL 이 그 행을 그대로 보고, 세션 수를 잘못 세어
		 *     「작업 0건인데 생산완료 100%」 유령 카드가 남았다(fix_pack_ghost_job.sql).
		 */
		@PostMapping("/pack_delete")
		@Transactional
		public AjaxResult packDelete(@RequestParam("mp_id") Integer mpId, Authentication auth) {
			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.packDelete(mpId, user);
			if (!r.success) rollback();
			return r;
		}

		@PostMapping("/pack_update_time")
		@Transactional
		public AjaxResult packUpdateTime(
			@RequestParam("mp_id") Integer mpId,
			@RequestParam(value = "start_time", required = false) String startTime,
			@RequestParam(value = "end_time", required = false) String endTime,
			Authentication auth) {

			User user = (User) auth.getPrincipal();
			AjaxResult r = this.packService.packUpdateTime(mpId, startTime, endTime, user);
			if (!r.success) rollback();
			return r;
		}

		// =================================================================
		// 국가 마스터
		// =================================================================

		@GetMapping("/countries")
		public AjaxResult countries(
			@RequestParam(value = "spjangcd", required = false) String spjangcd,
			@RequestParam(value = "all", required = false, defaultValue = "false") boolean all) {
			AjaxResult r = new AjaxResult();
			r.data = this.countryService.getList(spjangcd, all);
			r.success = true;
			return r;
		}

		@PostMapping("/country_save")
		public AjaxResult countrySave(
			@RequestParam(value = "id", required = false) Integer id,
			@RequestParam(value = "code", required = false) String code,
			@RequestParam("name") String name,
			@RequestParam(value = "flag", required = false) String flag,
			@RequestParam(value = "iso3", required = false) String iso3,
			@RequestParam(value = "sort_no", required = false) Integer sortNo,
			@RequestParam(value = "use_yn", required = false) String useYn,
			@RequestParam(value = "spjangcd", required = false) String spjangcd,
			@RequestParam(value = "user_id", required = false) Integer userId) {

			AjaxResult r = new AjaxResult();
			try {
				Object row = (id == null)
											 ? this.countryService.insert(code, name, flag, iso3, sortNo, spjangcd, userId)
											 : this.countryService.update(id, name, flag, iso3, sortNo, useYn, userId);
				r.data = CountryService.ok(row);
				r.success = true;
			} catch (IllegalArgumentException e) {
				r.data = CountryService.fail(e.getMessage());
				r.success = true;   // HTTP 는 정상. 실패 여부는 data.ok 로 판단
			}
			return r;
		}

		@PostMapping("/country_delete")
		public AjaxResult countryDelete(@RequestParam("id") Integer id) {
			AjaxResult r = new AjaxResult();
			try {
				r.data = CountryService.ok(this.countryService.delete(id));
				r.success = true;
			} catch (IllegalArgumentException e) {
				r.data = CountryService.fail(e.getMessage());
				r.success = true;
			}
			return r;
		}

		// =================================================================
		// 공통
		// =================================================================

		private static void rollback() {
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}

		/**
		 * SQL 오류까지 전부 AjaxResult 로 변환한다.
		 *
		 * ★ 안 잡으면 스프링이 HTML 에러 페이지를 내리고 AjaxUtil 이
		 *   「페이지를 찾을 수 없습니다」 네이티브 alert 을 띄운다.
		 *   (M-CELL 수리 화면에서 겪은 것과 같은 문제)
		 */
		@ExceptionHandler(Exception.class)
		public AjaxResult onError(Exception e) {
			AjaxResult r = new AjaxResult();
			r.success = false;
			String msg = e.getMessage();
			r.message = (msg == null || msg.isBlank())
										? "처리 중 오류가 발생했습니다."
										: (msg.length() > 300 ? msg.substring(0, 300) : msg);
			if (!(e instanceof IllegalArgumentException)) e.printStackTrace();
			return r;
		}
	}