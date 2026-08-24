package mes.app.production;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import mes.app.common.NotificationController_modal;
import mes.domain.entity.*;
import mes.domain.repository.*;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.ProdOrderEditService;
import mes.domain.model.AjaxResult;

@RestController
@RequestMapping("/api/production/prod_order_edit")
public class ProdOrderEditController {

	@Autowired
	private ProdOrderEditService prodOrderEditService;

	@Autowired
	MaterialRepository materialRepository;

	@Autowired
	RoutingProcRepository routingProcRepository;

	@Autowired
	JobResRepository jobResRepository;

	@Autowired
	SujuRepository sujuRepository;

	@Autowired
	WorkcenterRepository workcenterRepository;

	@Autowired
	BomProcCompRepository bomProcCompRepository;

	@Autowired
	BomRepository bomRepository;

	@Autowired
	NotificationController_modal notificationController_modal;

	// 수주 목록 조회
	@GetMapping("/suju_list")
	public AjaxResult getSujuList(
			@RequestParam(value="date_kind", required=false) String date_kind,
			@RequestParam(value="start", required=false) String start,
			@RequestParam(value="end", required=false) String end,
			@RequestParam(value="mat_group", required=false) Integer mat_group,
			@RequestParam(value="factory", required=false) Integer cboFactory,
			@RequestParam(value="mat_name", required=false) String mat_name,
			@RequestParam("spjangcd") String spjangcd,
			@RequestParam(value = "company", required = false) String company,
			@RequestParam(value="not_flag", required=false) String not_flag) {

		List<Map<String, Object>> items = this.prodOrderEditService.getSujuList(date_kind, start, end, mat_group, mat_name, not_flag, spjangcd, cboFactory, company);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 제품 지시내역 조회
	@GetMapping("/joborder_list")
	public AjaxResult getJobOrderList(
			@RequestParam(value="suju_id", required=false) Integer suju_id) {

		List<Map<String, Object>> items = this.prodOrderEditService.getJobOrderList(suju_id);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 제품 지시내역 상세조회
	@GetMapping("/joborder_detail")
	public AjaxResult getJobOrderDetail(
			@RequestParam("jobres_id") Integer jobres_id,
			HttpServletRequest request) {

		Map<String, Object> item = this.prodOrderEditService.getJobOrderDetail(jobres_id);

		AjaxResult result = new AjaxResult();
		result.data = item;

		return result;
	}

	// 반제품 작업지시 조회
	@GetMapping("/semi_list")
	public AjaxResult getSemiList(
			@RequestParam(value="data_date", required=false) String data_date,
			@RequestParam(value="mat_pk", required=false) Integer mat_pk,
			@RequestParam(value="suju_qty", required=false) Double suju_qty,
			@RequestParam(value="suju_pk", required=false) Integer suju_pk) {

		List<Map<String, Object>> items = this.prodOrderEditService.getSemiList(data_date, mat_pk, suju_qty, suju_pk);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 반제품 지시내역 조회
	@GetMapping("/semi_joborder_list")
	public AjaxResult getSemiJoborderList(
			@RequestParam(value="suju_id", required=false) Integer suju_id) {

		List<Map<String, Object>> items = this.prodOrderEditService.getSemiJoborderList(suju_id);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 작업지시 생성
	@PostMapping("/make_prod_order")
	@Transactional
	public AjaxResult makeProdOrder(
			@RequestParam(value="suju_id", required=false) Integer sujuId,
			@RequestParam(value="prod_date", required=false) String productionDate,
			@RequestParam(value="Material_id", required=false) Integer cboMaterial,
			@RequestParam(value="workshift", required=false) String cboShiftCode,
			@RequestParam(value="workcenter_id", required=false) Integer cboWorcenter,
			@RequestParam(value="equ_id", required=false) Integer cboEquipment,
			@RequestParam(value="AdditionalQty", required=false) Float txtOrderQty,
			@RequestParam("spjangcd") String spjangcd,
			HttpServletRequest request,
			Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User)auth.getPrincipal();
		return prodOrderEditService.makeProdOrder(
				sujuId,
				productionDate,
				cboMaterial,
				cboShiftCode,
				cboWorcenter,
				cboEquipment,
				txtOrderQty,
				spjangcd,
				user
		);
	}

	// 지시내역 수정 (하위 반제품 캐스케이드 + 수주량 동기화)
	@PostMapping("/update_order")
	public AjaxResult updateOrder(
			@RequestParam(value="id", required=false) Integer jobres_id,
			@RequestParam(value="ProductionDate", required=false) String productionDate,
			@RequestParam(value="ShiftCode", required=false) String ShiftCode,
			@RequestParam(value="WorkCenter_id", required=false) Integer WorkCenter_id,
			@RequestParam(value="Equipment_id", required=false) Integer Equipment_id,
			@RequestParam(value="OrderQty", required=false) Float OrderQty,
			@RequestParam(value="Description", required=false) String Description,
			@RequestParam(value="sync_suju", required=false) String syncSuju,   // 'Y' 면 수주량도 수정
			HttpServletRequest request,
			Authentication auth) {

		User user = (User) auth.getPrincipal();

		return this.prodOrderEditService.updateOrderCascade(
				jobres_id, productionDate, ShiftCode,
				WorkCenter_id, Equipment_id, OrderQty, Description, syncSuju, user);
	}

	// 수정 가능 여부 미리보기 (모달 열 때 호출)
	@GetMapping("/edit_guard")
	public AjaxResult getEditGuard(
			@RequestParam("jobres_id") Integer jobres_id) {

		AjaxResult result = new AjaxResult();
		result.data = this.prodOrderEditService.getEditGuard(jobres_id);
		result.success = true;
		return result;
	}
}