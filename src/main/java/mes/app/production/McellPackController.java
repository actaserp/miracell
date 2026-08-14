package mes.app.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.production.service.McellPackService;
import mes.app.production.service.ProductionCreateService.BomInput;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M-CELL 포장 (2공장, mc03 / 워크센터 56) 컨트롤러.
 *
 * 화면 흐름
 *   A 포장 큐 → B 작업 목록(작업자·설비 1조) → C 박스 연속 포장
 *   C : 합격 유닛 선택 → 포장자재 투입 → 박스 라벨 스캔 → 포장 완료 → 다음 박스
 *
 * ★ 카드가 두 종류다. 그래서 조회 계열이 job_res_id / pack_mat_id 를 둘 다 받는다.
 *     job_res_id  포장 작지 카드
 *     pack_mat_id 「작지 없음」 카드 (수리·반품 등 라우팅 밖 유닛)
 *   화면은 둘 중 하나만 실어 보낸다. 서버는 없는 쪽을 null 로 받는다.
 *   두 파라미터를 모두 required=false 로 두는 것이 핵심 — 하나라도 required 면
 *   반대편 카드에서 400 이 나고, AjaxUtil 이 「페이지를 찾을 수 없습니다」 를 띄운다.
 *
 * ★ AjaxUtil 은 form-urlencoded 로 보낸다. @RequestBody(JSON)를 쓰면 415.
 *   그래서 모든 파라미터가 @RequestParam 이고, 자재 목록만 bom_json 문자열로 받는다.
 */
@RestController
@RequestMapping("/api/production/mcell/pack")
public class McellPackController {

	private static final Logger log = LoggerFactory.getLogger(McellPackController.class);

	@Autowired private McellPackService mcellPackService;
	private final ObjectMapper om = new ObjectMapper();

	// ── 조회 ─────────────────────────────────────────────

	@GetMapping("/context")
	public AjaxResult context(
		@RequestParam(value = "process_code", defaultValue = "mc03") String processCode,
		@RequestParam(value = "factory_id", defaultValue = "2") Integer factoryId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getContext(processCode, factoryId);
		return r;
	}

	/** A화면 — 포장 큐 (작지 카드 + 「작지 없음」 카드) */
	@GetMapping("/wo_queue")
	public AjaxResult woQueue(
		@RequestParam(value = "process_id", required = false) Integer processId,
		@RequestParam(value = "date_from", required = false) String dateFrom,
		@RequestParam(value = "date_to", required = false) String dateTo,
		@RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getWoQueue(processId, spjangcd, dateFrom, dateTo);
		return r;
	}

	/** B·C화면 — 포장 대기 유닛 (검사 합격 · 검사완료창고 · 이 카드 소속) */
	@GetMapping("/ready_units")
	public AjaxResult readyUnits(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getReadyUnits(jobResId, packMatId);
		return r;
	}

	/** B화면 — 포장 완료 목록 (작업 세션은 이 목록에서 파생시킨다) */
	@GetMapping("/packed_list")
	public AjaxResult packedList(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getPackedList(jobResId, packMatId);
		return r;
	}

	/** C화면 — 포장 자재 (완제품 BOM − 유닛품목). store_id 로 소스창고를 함께 내린다 */
	@GetMapping("/pack_materials")
	public AjaxResult packMaterials(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getPackMaterials(jobResId, packMatId);
		return r;
	}

	/** 이 카드가 포장 대상으로 삼는 유닛 품목 */
	@GetMapping("/unit_materials")
	public AjaxResult unitMaterials(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getUnitMaterials(jobResId, packMatId);
		return r;
	}

	/**
	 * 박스 라벨 중복 검사.
	 * 라벨이 필수라 한 번 쓴 값을 다시 못 쓴다. 화면 목록만으로는 다른 작지의 라벨을 못 막는다.
	 * GS1-128 파싱((10) 추출)은 화면에서 하고, 여기엔 라벨 문자열만 온다.
	 */
	@GetMapping("/label_check")
	public AjaxResult labelCheck(
		@RequestParam("key") String key,
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.checkLabel(key);
		return r;
	}

	/**
	 * 로트 직접 조회 (사내 로트번호 또는 외부 라벨).
	 * 화면 흐름이 「유닛 선택 → 라벨 스캔」으로 바뀌어 진입용으로는 쓰지 않지만,
	 * 로트를 찍어 상태를 확인하고 싶을 때를 위해 남겨 둔다.
	 */
	@GetMapping("/lot_search")
	public AjaxResult lotSearch(
		@RequestParam("key") String key,
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.searchUnitLot(jobResId, packMatId, key);
		return r;
	}

	@GetMapping("/workers")
	public AjaxResult workers() {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getWorkers();
		return r;
	}

	/** 포장 설비 목록 (워크센터 + 설비그룹 기준) */
	@GetMapping("/equipments")
	public AjaxResult equipments(
		@RequestParam(value = "workcenter_id", required = false) Integer workCenterId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getEquipments(workCenterId);
		return r;
	}

	// ── 쓰기 ─────────────────────────────────────────────

	/**
	 * 박스 1개 포장 = 유닛 1대 → 완제품 로트 → 제품창고(4).
	 * job_res_id 가 없으면 pack_mat_id 로 포장 작지를 자동 발행한다.
	 * maker_lot_no(박스 라벨)는 필수 — 서비스에서 막는다.
	 */
	@PostMapping("/pack_unit")
	@Transactional
	public AjaxResult packUnit(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId,
		@RequestParam("mat_lot_id") Integer matLotId,
		@RequestParam(value = "maker_lot_no", required = false) String makerLotNo,
		@RequestParam(value = "actor_id", required = false) Integer actorId,
		@RequestParam(value = "equipment_id", required = false) Integer equipmentId,
		@RequestParam(value = "start_time", required = false) String startTime,
		@RequestParam(value = "end_time", required = false) String endTime,
		@RequestParam(value = "bom_json", required = false) String bomJson,
		// ★ 스캔 원문. 화면이 GS1 에서 (10)만 뽑아 maker_lot_no 로 보내므로
		//   원문은 따로 받아 pack_label."RawData" 에 남긴다. 없으면 라벨값을 그대로 쓴다.
		@RequestParam(value = "label_raw", required = false) String labelRaw,
		@RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
		Authentication auth) {
		return this.mcellPackService.packUnit(jobResId, packMatId, matLotId, makerLotNo,
			actorId, equipmentId, startTime, endTime,
			parseBom(bomJson), labelRaw, spjangcd, (User) auth.getPrincipal());
	}

	/** 「작업 추가」 = 포장 작업 시작. 작지를 working 으로 올린다 */
	@PostMapping("/work_start")
	@Transactional
	public AjaxResult workStart(
		@RequestParam("job_res_id") Integer jobResId,
		@RequestParam(value = "actor_id", required = false) Integer actorId,
		@RequestParam(value = "start_time", required = false) String startTime,
		Authentication auth) {
		return this.mcellPackService.startWork(jobResId, actorId, startTime, (User) auth.getPrincipal());
	}

	/** 「작업 종료」 — 차수가 하나도 없으면 작지를 ordered 로 되돌린다 */
	@PostMapping("/work_end")
	@Transactional
	public AjaxResult workEnd(
		@RequestParam("job_res_id") Integer jobResId,
		Authentication auth) {
		return this.mcellPackService.endWork(jobResId, (User) auth.getPrincipal());
	}

	/** 포장 취소 — 차수 롤백 + 유닛 packed → pass */
	@PostMapping("/pack_cancel")
	@Transactional
	public AjaxResult packCancel(
		@RequestParam("mat_produce_id") Integer mpId,
		Authentication auth) {
		return this.mcellPackService.cancelPack(mpId, (User) auth.getPrincipal());
	}

	/** 시작/완료 시각 수정 */
	@PostMapping("/pack_time")
	@Transactional
	public AjaxResult packTime(
		@RequestParam("mat_produce_id") Integer mpId,
		@RequestParam("which") String which,          // start | end
		@RequestParam("value") String value,          // 'yyyy-MM-dd HH:mm'
		Authentication auth) {
		return this.mcellPackService.setPackTime(mpId, which, value, (User) auth.getPrincipal());
	}

	// ── 예외 처리 ────────────────────────────────────────
	/**
	 * SQL 오류까지 전부 AjaxResult 로 변환한다(수리 §5.9 와 동일).
	 * 안 잡으면 스프링이 HTML 에러 페이지를 내리고 AjaxUtil 이
	 * 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
	 */
	@ExceptionHandler(Exception.class)
	public AjaxResult handleError(Exception e) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		boolean business = (e instanceof IllegalStateException) || (e instanceof IllegalArgumentException);
		if (!business) log.error("[mcell/pack] 처리 오류", e);
		r.message = (e.getMessage() == null || e.getMessage().isBlank())
									? "처리 중 오류가 발생했습니다." : e.getMessage();
		return r;
	}

	// ── 유틸 ─────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private List<BomInput> parseBom(String json) {
		if (json == null || json.isBlank()) return null;   // null = BOM 기본값 사용
		List<BomInput> list = new ArrayList<>();
		try {
			List<Map<String, Object>> arr = om.readValue(json, List.class);
			for (Map<String, Object> m : arr) {
				Object mid = m.get("matId");
				Object q = m.get("qty");
				if (mid == null) continue;
				BomInput bi = new BomInput();
				bi.matId = ((Number) mid).intValue();
				bi.qty = (q == null) ? 0f : Float.parseFloat(String.valueOf(q));
				if (bi.qty > 0) list.add(bi);
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("포장자재(bom_json) 형식 오류: " + e.getMessage());
		}
		return list;
	}
}