package mes.app.shipment;


import mes.app.shipment.service.OutMethodService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 품목별 출고방법 지정.
 *
 *   GET  /read   품목 목록 + 현재 출고방법
 *   POST /save   선택 품목 일괄 지정 (out_method 를 비우면 미지정으로 복귀)
 */
@RestController
@RequestMapping("/api/shipment/out_method")
public class OutMethodController {

	@Autowired
	private OutMethodService outMethodService;

	@GetMapping("/read")
	public AjaxResult read(
			@RequestParam(value = "mat_type", required = false) String matType,
			@RequestParam(value = "mat_group", required = false) Integer matGrpPk,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "unset_only", required = false) String unsetOnly,
			@RequestParam(value = "lot_only", required = false) String lotOnly,
			// 공장 필터. 빈 값 = 전체.
			@RequestParam(value = "factory_id", required = false) Integer factoryId,
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		AjaxResult r = new AjaxResult();
		r.data = this.outMethodService.getList(
				matType, matGrpPk, keyword,
				"Y".equals(unsetOnly), "Y".equals(lotOnly), factoryId, spjangcd);
		r.success = true;
		return r;
	}

	/**
	 * Q 는 품목 id 목록 — [{"id":1},{"id":2}]
	 */
	@PostMapping("/save")
	public AjaxResult save(
			@RequestParam(value = "out_method", required = false) String method,
			@RequestBody MultiValueMap<String, Object> Q,
			Authentication auth) {

		AjaxResult r = new AjaxResult();
		try {
			Object raw = Q.getFirst("Q");
			if (raw == null) {
				r.data = OutMethodService.fail("품목을 선택하세요");
				r.success = true;
				return r;
			}

			List<Integer> ids = new ArrayList<>();
			for (Map<String, Object> m : CommonUtil.loadJsonListMap(raw.toString())) {
				Object id = m.get("id");
				if (id != null) ids.add(Integer.parseInt(id.toString()));
			}

			int cnt = this.outMethodService.save(ids, method, userIdOf(auth));

			Map<String, Object> data = new HashMap<>();
			data.put("count", cnt);
			r.data = OutMethodService.ok(data);
			r.success = true;

		} catch (IllegalArgumentException e) {
			r.data = OutMethodService.fail(e.getMessage());
			r.success = true;
		}
		return r;
	}

	private static Integer userIdOf(Authentication auth) {
		if (auth == null || !(auth.getPrincipal() instanceof User)) return null;
		return ((User) auth.getPrincipal()).getId();
	}
}