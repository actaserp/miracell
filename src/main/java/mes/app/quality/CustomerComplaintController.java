package mes.app.quality;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import mes.app.quality.service.CustomerComplaintService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/**
 * 고객불만 관리 컨트롤러.
 * URL: /api/quality/complaint
 */
@RestController
@RequestMapping("/api/quality/complaint")
public class CustomerComplaintController {

	@Autowired
	private CustomerComplaintService complaintService;

	/** 목록 조회 */
	@GetMapping("/list")
	public AjaxResult getList(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "action_state", required = false) String actionState,
			@RequestParam(value = "keyword", required = false) String keyword) {

		AjaxResult result = new AjaxResult();
		result.data = this.complaintService.getList(dateFrom, dateTo, actionState, keyword);
		return result;
	}

	/** 단건 조회 (팝업 상세) */
	@GetMapping("/detail")
	public AjaxResult getDetail(@RequestParam("id") Integer id) {
		AjaxResult result = new AjaxResult();
		result.data = this.complaintService.getComplaint(id);
		return result;
	}

	/** 저장 (신규/수정) */
	@Transactional
	@PostMapping("/save")
	public AjaxResult save(@RequestParam Map<String, Object> params, Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();

		// 필수 검증
		if (isBlank(params.get("receipt_date"))) {
			result.success = false;
			result.message = "접수일자는 필수입니다.";
			return result;
		}

		try {
			MapSqlParameterSource p = bind(params, user);
			String idStr = str(params.get("id"));

			if (idStr == null || idStr.isBlank() || "0".equals(idStr)) {
				// 신규 — 접수번호 자동 채번
				String complaintNo = str(params.get("complaint_no"));
				if (complaintNo == null || complaintNo.isBlank()) {
					complaintNo = this.complaintService.nextComplaintNo(str(params.get("receipt_date")));
				}
				p.addValue("complaintNo", complaintNo);
				Integer newId = this.complaintService.insert(p);
				result.data = newId;
				result.message = "저장되었습니다.";
			} else {
				p.addValue("id", Integer.parseInt(idStr));
				this.complaintService.update(p);
				result.message = "수정되었습니다.";
			}
			result.success = true;

		} catch (Exception ex) {
			result.success = false;
			result.message = "저장 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}
		return result;
	}

	/** 삭제 */
	@Transactional
	@PostMapping("/delete")
	public AjaxResult delete(
			@RequestParam(value = "ids[]", required = false) List<Integer> ids,
			Authentication auth) {

		AjaxResult result = new AjaxResult();

		if (ids == null || ids.isEmpty()) {
			result.success = false;
			result.message = "선택된 항목이 없습니다.";
			return result;
		}
		try {
			this.complaintService.delete(ids);
			result.success = true;
			result.message = "삭제되었습니다.";
		} catch (Exception ex) {
			result.success = false;
			result.message = "삭제 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}
		return result;
	}

	// ===== 내부 헬퍼 =====

	private MapSqlParameterSource bind(Map<String, Object> p, User user) {
		MapSqlParameterSource s = new MapSqlParameterSource();
		s.addValue("receiptDate", str(p.get("receipt_date")));
		s.addValue("companyId", toInt(p.get("company_id")));
		s.addValue("materialId", toInt(p.get("material_id")));
		s.addValue("lotNo", str(p.get("lot_no")));
		s.addValue("complaintType", str(p.get("complaint_type")));
		s.addValue("content", str(p.get("content")));
		s.addValue("qty", str(p.get("qty")));
		s.addValue("actionState", defaultStr(p.get("action_state"), "1"));
		s.addValue("actionContent", str(p.get("action_content")));
		s.addValue("actionDate", str(p.get("action_date")));
		s.addValue("personId", toInt(p.get("person_id")));
		s.addValue("description", str(p.get("description")));
		s.addValue("userId", user.getId());
		s.addValue("spjangcd", user.getSpjangcd() == null ? "ZZ" : user.getSpjangcd());
		return s;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private static String defaultStr(Object o, String def) {
		return (o == null || o.toString().isBlank()) ? def : o.toString();
	}

	private static boolean isBlank(Object o) {
		return o == null || o.toString().isBlank();
	}

	private static Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try { return Integer.parseInt(o.toString()); }
		catch (Exception e) { return null; }
	}
}
