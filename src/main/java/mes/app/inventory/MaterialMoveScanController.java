package mes.app.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.inventory.service.MaterialInoutService;
import mes.app.inventory.service.MaterialMoveScanService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;

/**
 * 바코드 스캔 불출 (자재창고 → 생산창고).
 *
 * 기존 /api/inventory/material_move 의 material_move · material_lot_move 는 건드리지 않는다.
 * 저쪽은 「품목 단위 부분이동(로트 미반영)」과 「로트 전량이동」이고,
 * 여기는 「로트 부분이동(로트 분할)」이라 동작이 근본적으로 다르다.
 * 한 메서드로 합치려 들면 세 가지 규칙이 한 자리에서 엉킨다.
 */
@RestController
@RequestMapping("/api/inventory/move_scan")
public class MaterialMoveScanController {

	@Autowired
	private MaterialMoveScanService moveScanService;

	@Autowired
	private MaterialInoutService materialInoutService;

	/**
	 * 스캔 1건 조회.
	 *
	 * 화면은 이 결과로 행을 하나 깐다:
	 *   found=true            → 흰 행. qty 에 stock 전량이 들어간다
	 *   found=true, stock=0   → 회색 행. 이동량 0
	 *   found=false           → 빨간 행. 스캔 원문만 표시, 불출에서 제외
	 *
	 * ★ 어느 경우에도 오류를 던지지 않는다. 연속 스캔 중 알럿이 뜨면 손이 멈춘다.
	 *
	 * 품목 매칭 순서는 입고 화면(scan_lookup)과 동일하게 간다 —
	 * GTIN → UDI-DI → 품목코드. 여기서 얻은 material_id 로 로트 조회를 좁혀,
	 * 다른 품목에 같은 로트번호가 있을 때 엉뚱한 행을 집는 것을 막는다.
	 */
	@GetMapping("/lot_lookup")
	public AjaxResult lotLookup(
			@RequestParam(value = "gtin14", required = false) String gtin14,
			@RequestParam(value = "di", required = false) String di,
			@RequestParam(value = "lot", required = false) String lot,
			@RequestParam(value = "raw", required = false) String raw,
			@RequestParam("from_store") Integer fromStore,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		AjaxResult r = new AjaxResult();
		Map<String, Object> data = new HashMap<>();

		// 1) 바코드 → 품목 (없어도 된다. 자사 로트 바코드는 품목 정보가 없다)
		Map<String, Object> mat = null;
		if (gtin14 != null && !gtin14.isEmpty())
			mat = this.materialInoutService.findMaterialByGtin(gtin14, spjangcd);
		if (mat == null && di != null && !di.isEmpty())
			mat = this.materialInoutService.findMaterialByUdiDi(di, spjangcd);
		if (mat == null && raw != null && !raw.isEmpty())
			mat = this.materialInoutService.findMaterialByCode(raw, spjangcd);

		Integer matId = null;
		if (mat != null && mat.get("material_id") != null)
			matId = ((Number) mat.get("material_id")).intValue();

		// 2) 출발창고에서 로트 조회
		List<Map<String, Object>> lots =
				this.moveScanService.findLotsInStore(lot, raw, matId, fromStore, spjangcd);

		data.put("found", !lots.isEmpty());
		data.put("lots", lots);
		data.put("material", mat);
		data.put("scan_key", (lot != null && !lot.isEmpty()) ? lot : raw);

		r.data = data;
		r.success = true;    // 조회 자체는 성공. 미발견은 found=false 로 판단
		return r;
	}

	/**
	 * 품목 + 창고의 로트 목록. 수동 불출 화면이 로트를 직접 고를 때.
	 * 재고 0 로트는 서버가 걸러 내려주지 않는다.
	 */
	@GetMapping("/lots")
	public AjaxResult lots(
			@RequestParam("mat_id") Integer matId,
			@RequestParam("store_id") Integer storeId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.moveScanService.lotsOf(matId, storeId, spjangcd);
		r.success = true;
		return r;
	}

	/**
	 * 불출 실행.
	 * body: { from_store, to_store, description, spjangcd, lines:[{mat_lot_id, qty}] }
	 */
	@PostMapping("/move")
	public AjaxResult move(@RequestBody Map<String, Object> body, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult r = new AjaxResult();

		Integer fromStore = CommonUtil.tryIntNull(body.get("from_store"));
		Integer toStore   = CommonUtil.tryIntNull(body.get("to_store"));
		String  desc      = body.get("description") == null ? null : String.valueOf(body.get("description"));
		String  spjangcd  = body.get("spjangcd") == null ? null : String.valueOf(body.get("spjangcd"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lines =
				(List<Map<String, Object>>) body.getOrDefault("lines", new ArrayList<>());

		try {
			return this.moveScanService.move(fromStore, toStore, lines, desc, spjangcd, user);
		} catch (IllegalStateException e) {
			// 재고 부족·창고 불일치. @Transactional 이 롤백한 뒤 사유만 돌려준다
			r.success = false;
			r.message = e.getMessage();
			return r;
		} catch (Exception e) {
			r.success = false;
			r.message = "불출 처리 중 오류: " + e.getMessage();
			return r;
		}
	}

	/**
	 * 수동 불출 — FIFO 배분 미리보기.
	 * body: { from_store, spjangcd, items:[{mat_id, qty}] }
	 *
	 * 재고를 건드리지 않는다. 화면이 이 결과를 확인 단계로 보여주고,
	 * 작업자가 그대로 진행하거나 로트를 바꾼 뒤 /move 로 보낸다.
	 */
	@PostMapping("/preview")
	public AjaxResult preview(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();

		Integer fromStore = CommonUtil.tryIntNull(body.get("from_store"));
		String  spjangcd  = body.get("spjangcd") == null ? null : String.valueOf(body.get("spjangcd"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items =
				(List<Map<String, Object>>) body.getOrDefault("items", new ArrayList<>());

		if (fromStore == null) {
			r.success = false; r.message = "출발창고를 확인하세요."; return r;
		}
		r.data = this.moveScanService.previewFifo(fromStore, items, spjangcd);
		r.success = true;
		return r;
	}

	/**
	 * 불출 취소.
	 * 도착 로트가 이미 세척·생산에 쓰였으면 실패한다 — 그게 맞다.
	 * 되돌릴 수 없는 것을 되돌린 척하면 재고가 조용히 부풀어 오른다.
	 */
	@PostMapping("/cancel")
	public AjaxResult cancel(
			@RequestParam("move_id") Integer moveId,
			Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult r = new AjaxResult();
		try {
			return this.moveScanService.cancel(moveId, user);
		} catch (IllegalStateException e) {
			r.success = false;
			r.message = e.getMessage();
			return r;
		} catch (Exception e) {
			r.success = false;
			r.message = "취소 처리 중 오류: " + e.getMessage();
			return r;
		}
	}

	@GetMapping("/history")
	public AjaxResult history(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "from_store", required = false) Integer fromStore,
			@RequestParam(value = "to_store", required = false) Integer toStore,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.moveScanService.history(dateFrom, dateTo, fromStore, toStore, state, spjangcd);
		r.success = true;
		return r;
	}

	@GetMapping("/history_detail")
	public AjaxResult historyDetail(@RequestParam("move_id") Integer moveId) {
		AjaxResult r = new AjaxResult();
		r.data = this.moveScanService.historyDetail(moveId);
		r.success = true;
		return r;
	}
}