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
 * 포장(bsc05) 컨트롤러 — PK → 인박스 → CK생산/투입 → 아웃박스.
 *
 *   ★ v2 : 완제 시점이 「인박스」에서 「CK 생산/투입」으로 옮겨갔다.
 *     인박스는 PK·IN BOX 를 소비하고 라벨만 남기며, 완제품 로트는 CK 가 들어간 뒤 나온다.
 *
 *   조회
 *     GET  /context             워크센터 · 포장대 · 창고 id
 *     GET  /order_list          A화면 작지 목록(무작지 발행분 포함)
 *     GET  /steril_lots         A화면 포장 대기 + PK/필터백 로트 선택 (kind=pk|bag)
 *     GET  /product_candidates  무작지 시작 시 완제품 후보
 *     GET  /product_barcodes    완제품 GTIN
 *     GET  /box_spec            IN/OUT BOX 입수량 (카톤 입수 = 1/Amount)
 *     GET  /ck_bom              CK 반제품 구성자재 + 창고별 재고
 *     GET  /workers             포장 작업자 (1공장 재직자)
 *     GET  /session_list        B화면 세션 목록
 *     GET  /session_detail      C화면 복원 (단계·PK로트·배분·CK투입·라벨·카톤)
 *
 *   실적
 *     POST /pack_start          세션 시작 (작지 있음)
 *     POST /pack_start_nojob    세션 시작 (작지 없음 — 작지 2행 자동발행)
 *     POST /pk_save             ① PK 로트 담기 (재고 이동 없음)
 *     POST /inbox_finish        ② 인박스 = PK+IN BOX 소비 + 라벨 ★첫 재고 이동
 *     POST /inbox_cancel        ② 인박스 취소 (담은 PK 는 남긴다)
 *     POST /ck_produce          ③ CK 생산/투입 = 완제품 산출 ★완제 시점
 *     POST /ck_cancel           ③ 취소 (완제품 로트·CK 되돌림 → ②로 복귀)
 *     POST /outbox_finish       ④ 아웃박스 = OUT BOX 소비 + 세션 완료 ★재고 이동
 *     POST /pack_cancel         완료취소
 *     POST /pack_delete         세션 삭제
 *     POST /pack_update_time    시각 수정
 *
 *   국가 마스터
 *     GET  /countries , POST /country_save , POST /country_delete
 *
 *   조회 (v2 추가)
 *     GET  /ck_stock_lots       ③ 「CK 자체재고 투입」 후보 (생산창고 17 잔여)
 *
 * ═══ 세션 종류 ═══
 *   kit     완제품 세션 — 작지에 부모가 있다. ①~④ 전체
 *   ckstock CK 자체재고 세션 — 작지에 부모가 없다. ③만 하고 재고로 남긴다
 *
 * 트랜잭션: 재고를 건드리는 API 는 @Transactional + 실패 시 setRollbackOnly.
 *
 * ★ @ExceptionHandler 필수 — 안 잡으면 스프링이 HTML 에러 페이지를 내리고
 *   AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 을 띄운다.
 */
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
	 * @param exclude_mp 이 세션이 잡아둔 수량은 예약분에서 빼고 계산(자기 것은 가용)
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

	/** 무작지 시작 시 고를 완제품 후보 — 1공장 완제품 + 그 CK 반제품 */
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

	/** 세션 시작 (작지 있음) */
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
	 *                   합계가 계획수량이 된다. ★여기서는 저장하지 않는다 —
	 *                   PK 는 ②ck_produce 에서 pack_alloc_item 으로 들어간다.
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
	// ② CK 생산 — ★첫 재고 이동
	// =================================================================

	/**
	 * 국가별 CK 마련 후 완제품 산출 ★완제 시점.
	 *
	 * allocations(JSON) — 배분 + 국가별 조달 방식(mode):
	 *   mode='produce' → items 의 자재를 소비해 CK 를 새로 만든다
	 *   mode='stock'   → ck_mat_lot_id 의 CK 자체재고를 그대로 투입한다
	 *   [{ "country":"KR", "country_id":1, "country_name":"한국(국내)", "qty":14,
	 *      "mode":"produce", "ck_lot":"BMSC120-CK-KR-260730",
	 *      "items":[ {"mat_id":117,"qty":28},                       // 17→3→19 FIFO
	 *                {"mat_id":142,"qty":14,"mat_lot_id":789} ] },  // 필터백 = 지정 멸균로트
	 *    { "country":"CN", "qty":6, "mode":"stock", "ck_mat_lot_id":3105 }]
	 *
	 * 필터백은 지정 로트에서만 뺀다 — 멸균 배치 추적이 끊기면 안 되므로
	 * 부족해도 다른 로트로 자동 대체하지 않는다.
	 */
	@PostMapping("/ck_produce")
	@Transactional
	public AjaxResult ckProduce(
		@RequestParam("mp_id") Integer mpId,
		@RequestParam("allocations") String allocationsJson,
		@RequestParam("spjangcd") String spjangcd,
		Authentication auth) {

		User user = (User) auth.getPrincipal();
		List<Map<String, Object>> allocations = CommonUtil.loadJsonListMap(allocationsJson);

		AjaxResult r = this.packService.ckProduce(mpId, allocations, user, spjangcd);
		if (!r.success) rollback();
		return r;
	}

	/** ① PK 로트 담기 — 재고는 안 움직인다. 다시 부르면 통째로 교체된다 */
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

	/** ② 인박스 취소 — PK·IN BOX 소비를 되돌린다. 담은 PK 행은 남는다 */
	@PostMapping("/inbox_cancel")
	@Transactional
	public AjaxResult inboxCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult r = this.packService.inboxCancel(mpId, user);
		if (!r.success) rollback();
		return r;
	}

	/**
	 * ③ 「CK 자체재고 투입」 후보 — 생산창고(17)에 남아 있는 CK 로트.
	 * CK 자체재고 작지로 미리 만들어 둔 것이 여기 잡힌다.
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

	/** CK 생산취소 — 소비·입고를 되돌리고 배분·PK 행까지 정리해 ①로 복귀 */
	@PostMapping("/ck_cancel")
	@Transactional
	public AjaxResult ckCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult r = this.packService.ckCancel(mpId, user);
		if (!r.success) rollback();
		return r;
	}

	// =================================================================
	// ③ 인박스 (완제 시점)
	// =================================================================

	/**
	 * 인박스 포장 ★재고 이동 — PK 로트 + CK 로트 + IN BOX → 완제품 로트(제품창고 4).
	 *
	 *   ★ PK 는 화면이 아니라 pack_alloc_item(ItemKind='pk') 에서 읽는다.
	 *     ②이후 새로고침·다른 태블릿 진입에도 투입 대상이 흔들리지 않는다.
	 *
	 * labels(JSON):
	 *   [{ "kind":"ckpk",  "gtin":"08801234560056","lot":"BMJ260730","date":"260730",
	 *      "raw":"(01)...(10)...(11)..." },
	 *    { "kind":"inbox", "gtin":"08801234560056","lot":"BMJ260730","date":"260730",
	 *      "qty":1, "raw":"(01)...(10)...(30)1(11)..." }]
	 */
	@PostMapping("/inbox_finish")
	@Transactional
	public AjaxResult inboxFinish(
		@RequestParam("mp_id") Integer mpId,
		@RequestParam(value = "labels", required = false) String labelsJson,
		@RequestParam(value = "start_time", required = false) String startTime,
		@RequestParam(value = "end_time", required = false) String endTime,
		@RequestParam("spjangcd") String spjangcd,
		Authentication auth) {

		User user = (User) auth.getPrincipal();
		List<Map<String, Object>> labels =
			(labelsJson == null || labelsJson.isBlank()) ? null : CommonUtil.loadJsonListMap(labelsJson);

		AjaxResult r = this.packService.inboxFinish(mpId, labels, startTime, endTime, user, spjangcd);
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

	@PostMapping("/pack_cancel")
	@Transactional
	public AjaxResult packCancel(@RequestParam("mp_id") Integer mpId, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult r = this.packService.packCancel(mpId, user);
		if (!r.success) rollback();
		return r;
	}

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