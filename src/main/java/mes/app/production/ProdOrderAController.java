package mes.app.production;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import mes.domain.entity.*;
import mes.domain.repository.*;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.ProdOrderAService;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;

@RestController
@RequestMapping("/api/production/prod_order_a")
public class ProdOrderAController {

	@Autowired
	private ProdOrderAService prodOrderAService;

	@Autowired
	private mes.app.production.service.ProdOrderEditService prodOrderEditService;

	@Autowired
	private mes.app.production.service.McellAssemblyService mcellAssemblyService;

	@Autowired
	MaterialRepository materialRepository;

	@Autowired
	JobResRepository jobResRepository;

	@Autowired
	RoutingProcRepository routingProcRepository;

	@Autowired
	WorkcenterRepository workcenterRepository;

	@Autowired
	BomProcCompRepository bomProcCompRepository;

	@Autowired
	BomRepository bomRepository;

	@GetMapping("/read")
	public AjaxResult getProdOrderA(
			@RequestParam(value="date_from", required = true) String dateFrom,
			@RequestParam(value="date_to", required = true) String dateTo,
			@RequestParam("workcenter_pk") String workcenterPk,
			@RequestParam("mat_type") String matType,
			@RequestParam("mat_grp_pk") String matGrpPk,
			@RequestParam("keyword") String keyword,
			// 공장 필터. 빈 값 = 전체. 화면이 소속 공장을 기본으로 채워 보낸다.
			@RequestParam(value="factory_id", required=false) String factoryId,
			@RequestParam("spjangcd") String spjangcd
	){
		List<Map<String, Object>> items = this.prodOrderAService.getProdOrderA(dateFrom,dateTo,matGrpPk,keyword,matType,workcenterPk,factoryId,spjangcd);
		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}

	@GetMapping("/mat_info")
	public AjaxResult getMatInfo(
			@RequestParam("id") String id ) {

		Map<String, Object> items = this.prodOrderAService.getMatInfo(id);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/detail")
	public AjaxResult getProdOrderADetail(
			@RequestParam("jr_pk") String jrPk) {

		Map<String, Object> items = this.prodOrderAService.getProdOrderADetail(jrPk);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	/** 진행 현황 (목록 더블클릭 모달) */
	@GetMapping("/progress")
	public AjaxResult getProgress(@RequestParam("jr_pk") Integer jrPk) {
		AjaxResult result = new AjaxResult();
		result.data = this.prodOrderAService.getProgress(jrPk);
		return result;
	}

	@Transactional
	@PostMapping("/save")
	public AjaxResult saveProdOrderA(
			@RequestParam(value="id", required=false) Integer id,
			@RequestParam("production_date") String productionDate,
			@RequestParam(value = "cboEquipment", required=false) Integer cboEquipment,
			@RequestParam("cboMaterial") Integer cboMaterial,
			@RequestParam("cboShiftCode") String cboShiftCode,
			// 헤더 기본 워크센터. 라우팅이 없을 때만 쓴다.
			// 라우팅 품목은 화면에서 콤보가 잠겨 값이 오지 않으므로 필수로 두면 400 이 난다.
			@RequestParam(value="cboWorcenter", required=false) Integer cboWorcenter,
			@RequestParam("txtDescription") String txtDescription,
			@RequestParam("txtOrderQty") Integer txtOrderQty,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer matPk = cboMaterial;
		Material m = materialRepository.getMaterialById(matPk);
		Integer routingPk = m.getRoutingId();
		Integer locPk = m.getStoreHouseId();

		Timestamp prodDate = CommonUtil.tryTimestamp(productionDate);

		// 신규 or 수정 검증
		JobRes header;
		boolean isUpdate = (id != null);
		boolean matChanged = false;

		if (isUpdate) {
			header = jobResRepository.getJobResById(id);
			if (!"ordered".equals(header.getState())) {
				result.success = false;
				result.message = "지시중 상태에서만 수정 가능합니다.";
				return result;
			}
			matChanged = (header.getMaterialId() == null) || !header.getMaterialId().equals(matPk);
		} else {
			header = new JobRes();
		}

		final boolean hasRouting = (routingPk != null);

		/* ── 수정 + 품목 그대로 + 라우팅 있음 → 자식을 지우지 않고 수량만 갱신한다 ──
		   자식을 삭제·재생성하면 id 가 바뀌어, 조립 화면이 들고 있던 job_res_id 가
		   가리키는 행이 사라진다(새로고침하면 0 건, 부모부터 다시 들어가야 보임).
		   또 유닛을 전부 지웠다 다시 만들게 되어 시리얼·로트가 끊긴다.
		   updateOrderCascade 는 UPDATE 만 하므로 그대로 재사용한다. */
		if (isUpdate && !matChanged && routingPk != null) {

			AjaxResult up = prodOrderEditService.updateOrderCascade(
					id, productionDate, cboShiftCode,
					cboWorcenter, cboEquipment, (float) txtOrderQty,
					txtDescription, "N", user);   // 자체재고는 수주 동기화 대상이 아니다
			if (!up.success) return up;

			jobResRepository.flush();

			/* 이미 유닛이 만들어진 작지는 새 지시량에 맞춰 유닛 수를 맞춘다.
			   줄었으면 shrinkUnits 가 미착수분을 걷어내고,
			   늘었으면 initUnits 가 부족분만 채운다(멱등이라 줄어든 경우엔 아무 일도 안 한다). */
			for (Map<String, Object> owner : this.prodOrderAService.getUnitOwners(id)) {
				Integer ownerId = ((Number) owner.get("id")).intValue();
				int target = owner.get("order_qty") == null ? 0
						: (int) Math.floor(((Number) owner.get("order_qty")).doubleValue());

				AjaxResult sr = this.mcellAssemblyService.shrinkUnits(ownerId, target, user);
				if (!sr.success) {
					// 수량 UPDATE 까지 함께 되돌린다
					TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
					result.success = false;
					result.message = sr.message;
					return result;
				}

				AjaxResult ir = this.mcellAssemblyService.initUnits(ownerId, spjangcd, user);
				if (!ir.success) {
					TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
					result.success = false;
					result.message = ir.message;
					return result;
				}
			}

			result.success = true;
			result.data = jobResRepository.getJobResById(id);
			return result;
		}

		// ===== 헤더 저장 =====
		header.set_audit(user);
		header.setProductionDate(prodDate);
		header.setProductionPlanDate(prodDate);
		header.setMaterialId(matPk);
		header.setOrderQty((float) txtOrderQty);
		header.setDescription(txtDescription);
		header.setStoreHouse_id(locPk);
		header.setLotCount(1);
		header.setState("ordered");
		header.setSpjangcd(spjangcd);

		if (!hasRouting) {
			// 라우팅 없음 → 화면값 사용. 이때는 워크센터가 반드시 있어야 한다.
			if (cboWorcenter == null) {
				result.success = false;
				result.message = "워크센터를 선택해 주세요.";
				return result;
			}
			header.setRouting_id(null);
			header.setProcessCount(1);
			header.setWorkCenter_id(cboWorcenter);
			header.setFirstWorkCenter_id(cboWorcenter);
			header.setEquipment_id(cboEquipment);
			header.setShiftCode(cboShiftCode);
			header = jobResRepository.save(header); // 트리거가 번호 생성
			result.success = true;
			result.data = header;
			return result;
		}

		// ── 라우팅 있음 → 수주(makeProdOrder)와 같은 모양으로 만든다 ──
		//    헤더는 공정을 갖지 않는 '그릇'이고, 공정은 자식이 들고 있다.
		//    헤더를 마지막 공정(포장)에 걸면 조립 화면(getWoQueue)이 잡지 못한다.
		List<RoutingProc> steps = routingProcRepository.findByRoutingIdOrderByProcessOrder(routingPk);
		if (steps == null || steps.isEmpty()) {
			result.success = false;
			result.message = "라우팅 공정이 없습니다.";
			return result;
		}

		// 수정이면 기존 자식을 지우고 다시 전개한다.
		// (지시량·품목이 바뀌면 자식 수량도 함께 움직여야 한다)
		// 여기까지 왔다면 신규이거나 품목이 바뀐 수정이다.
		// 품목이 바뀌면 자식 구성 자체가 달라지므로 지우고 다시 전개한다.
		if (id != null) {
			int busy = this.prodOrderAService.countBusyChildren(id);
			if (busy > 0) {
				result.success = false;
				result.message = "이미 착수한 공정이 있어 수정할 수 없습니다.";
				return result;
			}

			// 자식을 지우기 전에 그 자식에 매달린 유닛(mcell_unit)을 먼저 걷어낸다.
			// 유닛이 job_res 를 FK 로 물고 있어, 남겨두면 자식 삭제가 FK 위반으로 터진다.
			// 착수한 유닛이 하나라도 있으면 shrinkUnits 가 거부하고, 그대로 수정을 중단한다.
			for (Integer childId : this.prodOrderAService.getChildIds(id)) {
				AjaxResult ur = this.mcellAssemblyService.shrinkUnits(childId, 0, user);
				if (!ur.success) {
					result.success = false;
					result.message = ur.message;
					return result;
				}
			}
			// 헤더에 직접 매달린 유닛도 정리 (라우팅 없이 만들어졌던 건)
			AjaxResult hr = this.mcellAssemblyService.shrinkUnits(id, 0, user);
			if (!hr.success) {
				result.success = false;
				result.message = hr.message;
				return result;
			}

			this.prodOrderAService.deleteChildren(id);
		}

		header.setRouting_id(routingPk);
		header.setWorkCenter_id(null);        // 헤더는 공정 없음
		header.setFirstWorkCenter_id(null);
		header.setEquipment_id(cboEquipment);
		header.setShiftCode(cboShiftCode);

		header = jobResRepository.save(header); // 트리거가 헤더 번호 생성

		// 공정 자식 전개 — 수주와 동일한 로직을 재사용한다
		int childCount = prodOrderEditService.explodeProcessRows(
				header, matPk, (float) txtOrderQty, prodDate, cboShiftCode, spjangcd, user);

		// ProcessCount 만 SQL 로 직접 갱신한다.
		// 엔티티를 다시 save 하면, 트리거가 INSERT 시 넣은 WorkOrderNumber 를
		// 영속성 컨텍스트가 모르기 때문에 null 로 덮어쓴다(헤더 번호가 사라진다).
		this.prodOrderAService.updateProcessCount(header.getId(), childCount);

		result.success = true;
		result.data = header;
		return result;
	}



	@PostMapping("/delete")
	@Transactional
	public AjaxResult deleteProdOrderA(@RequestParam("id") Integer id, Authentication auth) {
		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();

		Map<String, Object> row = this.prodOrderAService.getJopResRow(id);

		if (row == null) {
			result.success = true;
			result.code = id.toString();
			return result;
		}

		/* 실적이 있으면 여기서 막는다.
		   job_res."State" 만 보는 기존 가드로는 걸러지지 않는다 — 실적이 붙어도
		   상태가 'ordered' 로 남아 있는 경우가 있어, 그대로 DELETE 하면
		   부모 없는 실적이 남거나 FK 위반으로 500 이 난다. */
		int produced = this.prodOrderAService.countProduced(id);
		if (produced > 0) {
			result.success = false;
			result.message = "생산 실적이 있어 삭제할 수 없습니다.\n\n"
					+ "실적을 먼저 취소(분해)한 뒤 삭제해 주세요.";
			return result;
		}

		/* 미착수 유닛은 작지와 함께 정리한다.
		   유닛은 조립 화면에 들어가는 순간 initUnits 로 만들어지므로,
		   남겨두면 「지시만 내리고 화면 한 번 열어본」 작지를 영영 못 지운다.
		   착수한 유닛이 있으면 shrinkUnits 가 거부하고 삭제도 중단된다. */
		int busyUnits = this.prodOrderAService.countBusyUnits(id);
		if (busyUnits > 0) {
			result.success = false;
			result.message = "이미 착수한 유닛이 " + busyUnits + "대 있어 삭제할 수 없습니다.\n\n"
					+ "조립 화면에서 분해한 뒤 삭제해 주세요.";
			return result;
		}

		for (Integer childId : this.prodOrderAService.getChildIds(id)) {
			AjaxResult sr = this.mcellAssemblyService.shrinkUnits(childId, 0, user);
			if (!sr.success) { result.success = false; result.message = sr.message; return result; }
		}
		AjaxResult hr = this.mcellAssemblyService.shrinkUnits(id, 0, user);
		if (!hr.success) { result.success = false; result.message = hr.message; return result; }

		int deletYn = this.prodOrderAService.deleteById(id);

		if (deletYn == -1) {
			result.success = false;
			result.message = "공정은 삭제할 수 없습니다.";
			return result;
		}
		if (deletYn == -2) {
			// 어느 공정이 걸렸는지까지 알려준다.
			// 목록 화면은 부모만 보여주므로, 자식이 막고 있으면 사용자가 원인을 알 길이 없다.
			List<Map<String, Object>> blockers = this.prodOrderAService.getDeleteBlockers(id);

			StringBuilder sb = new StringBuilder("진행중인 공정이 있어 삭제할 수 없습니다.");
			if (blockers != null) {
				int shown = 0;
				for (Map<String, Object> b : blockers) {
					if (shown >= 5) {   // 너무 길어지면 알럿이 화면을 넘는다
						sb.append("\n· 외 ").append(blockers.size() - shown).append("종");
						break;
					}
					sb.append("\n· ").append(CommonUtil.tryString(b.get("process_name")))
							.append(" / ").append(CommonUtil.tryString(b.get("state_name")));

					long cnt = b.get("cnt") == null ? 0 : ((Number) b.get("cnt")).longValue();
					if (cnt > 1) sb.append(" ").append(cnt).append("건");

					shown++;
				}
			}

			sb.append("\n\n실적을 먼저 취소(분해)한 뒤 삭제해 주세요.");

			result.success = false;
			result.message = sb.toString();
			return result;
		}
		if (deletYn <= 0) {
			result.success = false;
			result.message = "삭제할 작업지시가 없습니다.";
			return result;
		}


		Integer sujuPk = 0;
		if (row.get("state").equals("ordered")) {
			if (row.get("src_table") != null) {
				if (row.get("src_table").equals("suju")) {
					sujuPk = Integer.parseInt(row.get("src_pk").toString());
				}
			} else {
				sujuPk = 0;
			}
		}


		if (sujuPk > 0 && deletYn > 0) {
			this.prodOrderAService.updateBySujuPk(sujuPk);
		}

		return result;
	}
}