package mes.app.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import mes.app.inventory.service.StockCloseService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;

/**
 * 재고 마감.
 *
 * ★ @ExceptionHandler 로 SQL 오류까지 AjaxResult 로 변환한다.
 *   안 잡으면 스프링이 HTML 에러 페이지를 내리고 AjaxUtil 이
 *   「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
 */
@Slf4j
@RestController
@RequestMapping("/api/inventory/stock_close")
public class StockCloseController {

	@Autowired
	private StockCloseService stockCloseService;

	/* ---------------- 마감 내역 ---------------- */
	@GetMapping("/read")
	public AjaxResult read(
		@RequestParam(value = "srchStartDt", required = false) String srchStartDt,
		@RequestParam(value = "srchEndDt",   required = false) String srchEndDt,
		@RequestParam(value = "house_pk",    required = false) String housePk,
		@RequestParam(value = "mat_type",    required = false) String matType,
		@RequestParam(value = "mat_grp_pk",  required = false) String matGrpPk,
		@RequestParam(value = "keyword",     required = false) String keyword,
		@RequestParam(value = "spjangcd",    required = false) String spjangcd) {

		AjaxResult result = new AjaxResult();
		result.data = this.stockCloseService.getCloseList(
			srchStartDt, srchEndDt, housePk, matType, matGrpPk, keyword, spjangcd);
		result.success = true;
		return result;
	}

	/* ---------------- 마감 대상 ---------------- */
	@GetMapping("/preview")
	public AjaxResult preview(
		@RequestParam(value = "closeDate")                     String closeDate,
		@RequestParam(value = "house_pk",   required = false)  String housePk,
		@RequestParam(value = "mat_type",   required = false)  String matType,
		@RequestParam(value = "mat_grp_pk", required = false)  String matGrpPk,
		@RequestParam(value = "zeroYN",     required = false)  String zeroYN,
		@RequestParam(value = "spjangcd",   required = false)  String spjangcd) {

		AjaxResult result = new AjaxResult();
		result.data = this.stockCloseService.getPreviewList(
			closeDate, housePk, matType, matGrpPk, zeroYN, spjangcd);
		result.success = true;
		return result;
	}

	/* ---------------- 상세 ---------------- */
	@GetMapping("/detail")
	public AjaxResult detail(@RequestParam("mio_pk") Integer mioPk) {
		AjaxResult result = new AjaxResult();
		result.data = this.stockCloseService.getCloseDetail(mioPk);
		result.success = true;
		return result;
	}

	/* ---------------- 마감 등록 ----------------
	 *
	 * payload
	 *   closeDate   : yyyy-MM-dd
	 *   Description : 비고
	 *   overwriteYN : 'Y' 면 같은 (마감일 × 창고 × 품목) 기존 마감을 지우고 다시 넣는다
	 *   spjangcd
	 *   list        : [ { Material_id, StoreHouse_id }, ... ]
	 *
	 * ★ 수량은 받지 않는다. 서버가 저장 시점의 mat_in_house 를 다시 읽는다.
	 */
	@PostMapping("/save")
	@Transactional
	public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String closeDate   = CommonUtil.tryString(payload.get("closeDate"));
		String description = CommonUtil.tryString(payload.get("Description"));
		String overwriteYN = CommonUtil.tryString(payload.get("overwriteYN"));
		String spjangcd    = CommonUtil.tryString(payload.get("spjangcd"));
		if (spjangcd == null || spjangcd.isBlank()) spjangcd = "ZZ";

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list = (List<Map<String, Object>>) payload.get("list");

		if (closeDate == null || closeDate.isBlank()) {
			result.success = false;
			result.message = "마감 기준일이 없습니다.";
			return result;
		}
		if (list == null || list.isEmpty()) {
			result.success = false;
			result.message = "마감할 대상이 없습니다.";
			return result;
		}

		Integer closeNo = this.stockCloseService.nextCloseNo();

		int saved = 0;
		int skipped = 0;
		List<String> blocked = new ArrayList<>();

		for (Map<String, Object> it : list) {
			Integer matId   = CommonUtil.tryIntNull(it.get("Material_id"));
			Integer houseId = CommonUtil.tryIntNull(it.get("StoreHouse_id"));
			if (matId == null || houseId == null) { skipped++; continue; }

			// ★ 더 나중 마감이 있으면 과거로 소급하지 않는다.
			//   허용하면 그 뒤 마감의 기초재고 근거가 뒤바뀐다.
			if (this.stockCloseService.hasLaterClose(matId, houseId, closeDate)) {
				blocked.add(String.valueOf(matId));
				continue;
			}

			if ("Y".equals(overwriteYN)) {
				this.stockCloseService.deleteSameKey(matId, houseId, closeDate);
			}

			saved += this.stockCloseService.insertClose(
				matId, houseId, closeDate, closeNo, description, user.getId(), spjangcd);
		}

		result.success = true;
		result.data = Map.of("close_no", closeNo, "saved", saved,
			"skipped", skipped, "blocked", blocked.size());

		if (!blocked.isEmpty()) {
			result.message = saved + "건 마감. " + blocked.size()
												 + "건은 이후 마감이 이미 있어 건너뛰었습니다.";
		}
		return result;
	}

	/* ---------------- 비고 수정 ---------------- */
	@PostMapping("/save_memo")
	@Transactional
	public AjaxResult saveMemo(
		@RequestParam("mio_pk") Integer mioPk,
		@RequestParam(value = "Description", required = false) String description,
		Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		int n = this.stockCloseService.updateMemo(mioPk, description, user.getId());
		result.success = n > 0;
		if (n == 0) result.message = "마감 데이터를 찾을 수 없습니다.";
		return result;
	}

	/* ---------------- 마감 취소 ----------------
	 *
	 * payload : { pk_list: [1,2,3] }
	 *
	 * ★ 서비스의 DELETE 가 두 겹으로 막는다 (stock_close 행만 / 최신 마감만).
	 *   0 행이 지워지면 「최신 마감이 아니다」는 뜻이므로 그대로 알려준다.
	 */
	@PostMapping("/delete")
	@Transactional
	public AjaxResult delete(@RequestBody Map<String, Object> payload) {

		AjaxResult result = new AjaxResult();

		@SuppressWarnings("unchecked")
		List<Object> pkList = (List<Object>) payload.get("pk_list");

		if (pkList == null || pkList.isEmpty()) {
			result.success = false;
			result.message = "취소할 마감이 없습니다.";
			return result;
		}

		int deleted = 0;
		int blocked = 0;

		for (Object o : pkList) {
			Integer pk = CommonUtil.tryIntNull(o);
			if (pk == null) continue;

			int n = this.stockCloseService.deleteClose(pk);
			if (n > 0) deleted += n; else blocked++;
		}

		result.success = deleted > 0;
		result.data = Map.of("deleted", deleted, "blocked", blocked);

		if (blocked > 0) {
			result.message = deleted + "건 취소. " + blocked
												 + "건은 이후 마감이 있어 취소할 수 없습니다. 최근 마감부터 취소하세요.";
		}
		if (deleted == 0) {
			result.message = "취소된 마감이 없습니다. 이후 마감이 있으면 그것부터 취소해야 합니다.";
		}
		return result;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		log.error("재고마감 오류", e);
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = e.getMessage();
		return result;
	}
}