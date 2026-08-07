package mes.app.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.inventory.service.LotService;
import mes.domain.model.AjaxResult;

/**
 * LOT 트래킹.
 *
 * ★ lot_number 는 「사내 로트번호」가 아니라 「스캔한 값」이다.
 *   사내 LOT · 박스라벨(외부 UDI) · 카톤 번호 중 무엇이 들어와도 서비스가 사내 로트로 환산한다.
 *   포장 화면이 실물에 붙이는 번호는 사내 로트번호와 다르기 때문에,
 *   현장에서 박스를 찍었을 때 조회가 되려면 이 환산이 반드시 앞단에 있어야 한다.
 */
@RestController
@RequestMapping("/api/lot/lot_tracking")
public class LotTrackingController {

	private static final Logger log = LoggerFactory.getLogger(LotTrackingController.class);

	@Autowired
	public LotService lotService;

	/**
	 * @param lotNumber 스캔 키 (사내 LOT / 박스라벨 / 카톤번호)
	 * @param exact     'Y' 면 사내 로트번호로만 찾는다.
	 *                  한 박스라벨에 여러 완제품 로트가 걸렸을 때 화면이 하나를 고른 뒤 쓰는 값 —
	 *                  다시 라벨로 풀면 고른 것과 다른 로트가 첫 행으로 올라올 수 있다.
	 */
	@GetMapping("/lot_detail")
	public AjaxResult lotDetail(
		@RequestParam("lot_number") String lotNumber,
		@RequestParam(value = "exact", required = false, defaultValue = "N") String exact) {

		List<Map<String, Object>> items = this.lotService.lotDetail(lotNumber, "Y".equalsIgnoreCase(exact));
		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}

	/** 이력 — 여기 오는 lot_number 는 lot_detail 이 환산해 준 「사내 로트번호」다 */
	@GetMapping("/lot_history")
	public AjaxResult lotHistory(
		@RequestParam("mat_type") String matType,
		@RequestParam("lot_number") String lotNumber) {

		Map<String, Object> items = new HashMap<>();
		List<Map<String, Object>> m1 = lotService.getMaterialTracking(lotNumber);
		List<Map<String, Object>> m2 = lotService.getProductTracking(lotNumber);
		List<Map<String, Object>> m3 = lotService.getMaterialInoutTracking(lotNumber);
		List<Map<String, Object>> m4 = lotService.getProductShipmentTracking(lotNumber);
		List<Map<String, Object>> m5 = lotService.getPackTracking(lotNumber);
		items.put("m_item", m1);
		items.put("p_item", m2);
		items.put("inout_list", m3);
		items.put("shipment_list", m4);
		items.put("pack_list", m5);
		AjaxResult result = new AjaxResult();
		result.data =  items;
		return result;
	}

	/**
	 * SQL 오류까지 AjaxResult 로 변환한다.
	 * 없으면 스프링이 HTML 에러 페이지를 내리고 AjaxUtil 이
	 * 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
	 */
	@ExceptionHandler(Exception.class)
	public AjaxResult handleError(Exception e) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		boolean business = (e instanceof IllegalStateException) || (e instanceof IllegalArgumentException);
		if (!business) log.error("[lot_tracking] 처리 오류", e);
		r.message = (e.getMessage() == null || e.getMessage().isBlank())
									? "처리 중 오류가 발생했습니다." : e.getMessage();
		return r;
	}
}