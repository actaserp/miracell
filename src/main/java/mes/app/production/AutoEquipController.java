package mes.app.production;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.AutoEquipService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/**
 * 자동화 장비 가동 구간 API — 조립 화면 패널이 쓴다.
 *
 * ★ AjaxUtil 은 form-urlencoded 로 보낸다. @RequestBody 금지(415).
 * ★ 구간 → 차수 매칭만 한다. 실적 확정·BOM 차감은 기존 /api/production/work/item_finish.
 */
@RestController
@RequestMapping("/api/production/auto")
public class AutoEquipController {

	@Autowired
	private AutoEquipService autoEquipService;

	@GetMapping("/seg_list")
	public AjaxResult segList(@RequestParam("equipment_id") Integer equipmentId) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = autoEquipService.getSegList(equipmentId);
		return r;
	}

	/** 설비별 미매칭 구간 요약 — 조립 화면 A·B 의 누락 배지 */
	@GetMapping("/free_summary")
	public AjaxResult freeSummary(@RequestParam(value = "spjangcd", required = false) String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = autoEquipService.getFreeSummary(spjangcd);
		return r;
	}

	/** 자동화 설비 목록 — 장비 가동 이력 화면 */
	@GetMapping("/equ_list")
	public AjaxResult equList(@RequestParam(value = "spjangcd", required = false) String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = autoEquipService.getEquipList(spjangcd);
		return r;
	}

	/** 장비 가동 이력 — 기간 · 설비 · 상태(all/free/working/done) */
	@GetMapping("/history")
	public AjaxResult history(@RequestParam(value = "equipment_id", required = false) Integer equipmentId,
							  @RequestParam("from") String from,
							  @RequestParam("to") String to,
							  @RequestParam(value = "state", required = false) String state) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = autoEquipService.getHistory(equipmentId, from, to, state);
		return r;
	}

	@PostMapping("/seg_link")
	public AjaxResult segLink(@RequestParam("seg_ids") String segIds,
							  @RequestParam("mp_id") Integer mpId,
							  @RequestParam(value = "qty", required = false) Double qty,
							  Authentication auth) {
		List<Integer> ids = new ArrayList<>();
		for (String s : segIds.split(",")) {
			if (!s.isBlank()) ids.add(Integer.parseInt(s.trim()));
		}
		if (ids.isEmpty()) {
			AjaxResult r = new AjaxResult();
			r.success = false;
			r.message = "구간을 선택하세요.";
			return r;
		}
		/* 구간 하나 + 수량 = 일부만 붙이고 나머지는 남긴다 */
		if (ids.size() == 1 && qty != null) {
			return autoEquipService.linkQty(ids.get(0), mpId, qty, userId(auth));
		}
		return autoEquipService.link(ids, mpId, userId(auth));
	}

	@PostMapping("/seg_unlink")
	public AjaxResult segUnlink(@RequestParam("seg_id") Integer segId) {
		return autoEquipService.unlink(segId);
	}

	@PostMapping("/seg_split")
	public AjaxResult segSplit(@RequestParam("seg_id") Integer segId,
							   @RequestParam("at_time") String atTime) {
		return autoEquipService.split(segId, atTime);
	}

	private Integer userId(Authentication auth) {
		if (auth != null && auth.getPrincipal() instanceof User) return ((User) auth.getPrincipal()).getId();
		return null;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		e.printStackTrace();
		AjaxResult r = new AjaxResult();
		r.success = false;
		r.message = "장비 구간 처리 중 오류가 발생했습니다: " + e.getMessage();
		return r;
	}
}