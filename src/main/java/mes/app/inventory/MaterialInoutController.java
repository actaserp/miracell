package mes.app.inventory;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.*;
import mes.domain.repository.*;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mes.app.inventory.service.LotService;
import mes.app.inventory.service.MaterialInoutService;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;

@Slf4j
@RestController
@RequestMapping("/api/inventory/material_inout")
public class MaterialInoutController {

	@Autowired
	private MaterialInoutService materialInoutService;

	@Autowired
	private LotService lotService;

	@Autowired
	MatInoutRepository matInoutRepository;

	@Autowired
	MaterialRepository materialRepository;

	@Autowired
	MatLotRepository matLotRepository;

	@Autowired
	TestResultRepository testResultRepository;

	@Autowired
	TestItemResultRepository testItemResultRepository;

	@Autowired
	BujuRepository bujuRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 입출고 전체 리스트
	@GetMapping("/read")
	public AjaxResult getMaterialInout(
		@RequestParam(value = "srchStartDt", required=false) String srchStartDt,
		@RequestParam(value = "srchEndDt", required=false) String srchEndDt,
		@RequestParam(value = "house_pk", required=false) String housePk,
		@RequestParam(value = "mat_type", required=false) String matType,
		@RequestParam(value = "mat_grp_pk", required=false) String matGrpPk,
		@RequestParam(value = "spjangcd", required=false) String spjangcd,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInout(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 입출고 전체 리스트
	@GetMapping("/read_receipt")
	public AjaxResult getMaterialInout_receipt(
		@RequestParam(value = "srchStartDt", required=false) String srchStartDt,
		@RequestParam(value = "srchEndDt", required=false) String srchEndDt,
		@RequestParam(value = "house_pk", required=false) String housePk,
		@RequestParam(value = "mat_type", required=false) String matType,
		@RequestParam(value = "mat_grp_pk", required=false) String matGrpPk,
		@RequestParam(value = "spjangcd", required=false) String spjangcd,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutReceipt(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 불출 리스트
	@GetMapping("/read_issue")
	public AjaxResult getMaterialInout_issue(
		@RequestParam(value = "srchStartDt", required=false) String srchStartDt,
		@RequestParam(value = "srchEndDt", required=false) String srchEndDt,
		@RequestParam(value = "house_pk", required=false) String housePk,
		@RequestParam(value = "mat_type", required=false) String matType,
		@RequestParam(value = "mat_grp_pk", required=false) String matGrpPk,
		@RequestParam(value = "spjangcd", required=false) String spjangcd,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutIssue(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 폐기 리스트
	@GetMapping("/read_disposal")
	public AjaxResult getMaterialInout_disposal(
		@RequestParam(value = "srchStartDt", required=false) String srchStartDt,
		@RequestParam(value = "srchEndDt", required=false) String srchEndDt,
		@RequestParam(value = "house_pk", required=false) String housePk,
		@RequestParam(value = "mat_type", required=false) String matType,
		@RequestParam(value = "mat_grp_pk", required=false) String matGrpPk,
		@RequestParam(value = "spjangcd", required=false) String spjangcd,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutDisposal(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

//	@PostMapping("/save")
//	@Transactional
//	public AjaxResult saveMaterialInout(
//			@RequestParam("Description") String description,
//			@RequestParam("InoutQty") String inoutQty,
//			@RequestParam("InoutType") String inoutType,
//			@RequestParam("Material_id") String materialId,
//			@RequestParam("StoreHouse_id") String storeHouseId,
//			@RequestParam("inoutDate") String inoutDateStr,
//			@RequestParam(value = "mio_pk", required = false) Integer mio_pk,
//			@RequestParam("cboMaterialGroup") String cboMaterialGroup,
//			@RequestParam("cboMaterialType") String cboMaterialType,
//			@RequestParam("type") String type,
//			@RequestParam("spjangcd") String spjangcd,
//			HttpServletRequest request,
//			Authentication auth) {
//
//		User user = (User)auth.getPrincipal();
//
//		AjaxResult result = new AjaxResult();
//
//		Integer matPk = Integer.parseInt(materialId);
//		String state = "confirmed";
//		String _status = "a";
//		int qty = Integer.parseInt(
//				inoutQty.replace(",", "").replaceAll("[^\\d-]", "")
//		);
//
//		result.success = false;
//
//		boolean isUpdate = false;
//
//		MaterialInout mi;
//		if (mio_pk != null) {
//			isUpdate = true;
//			mi = matInoutRepository.findById(mio_pk)
//					.orElseThrow(() -> new RuntimeException("기존 데이터 없음: " + mio_pk));
//		} else {
//            mi = new MaterialInout();
//		}
//
//		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
//		LocalDateTime dateTime = LocalDateTime.parse(inoutDateStr, formatter);
//
//		mi.setInoutDate(dateTime.toLocalDate());
//		mi.setInoutTime(dateTime.toLocalTime());
//		mi.setMaterialId(matPk);
//		mi.setStoreHouseId(Integer.parseInt(storeHouseId));
//
//		Material m = this.materialRepository.getMaterialById(matPk);
//
//		String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";
//
//		if (type.equals("in")) {
//			mi.setInOut("in");
//			mi.setInputType(inoutType);
//			if(testYn.equals("Y") && !isUpdate) {
//				mi.setPotentialInputQty((float)qty);
//				state = "waiting";
//				_status = "t";
//			} else {
//				mi.setInputQty((float)qty);
//				mi.setOutputQty(0f);
//				mi.setOutputType("");
//			}
//		} else if(type.equals("recall")){
//			mi.setInOut("recall");
//			mi.setOutputType(inoutType);
//			mi.setOutputQty((float)qty);
//			mi.setInputQty(0f);
//			mi.setInputType("");
//
//		} else if(type.equals("return")){
//			mi.setInOut("return");
//			mi.setInputType(inoutType);
//			mi.setInputQty((float)qty);
//			mi.setOutputQty(0f);
//			mi.setOutputType("");
//
//		} else  {
//			mi.setInOut("out");
//			mi.setOutputType(inoutType);
//			mi.setOutputQty((float)qty);
//			mi.setInputQty(0f);
//			mi.setInputType("");
//		}
//		mi.setDescription(description);
//		mi.setState(state);
//		mi.set_status(_status);
//		mi.set_audit(user);
//		mi.setSpjangcd(spjangcd);
//
//		this.matInoutRepository.save(mi);
//		this.matInoutRepository.flush();
//
//
////		jdbcTemplate.query(
////				"SELECT sp_update_mat_in_house_by_inout(?, ?)",
////				rs -> {},  // 결과 무시
////				matPk, Integer.parseInt(storeHouseId)
////		);
//
//		result.success = true;
//
//		return result;
//	}

	@PostMapping("/save")
	@Transactional
	public AjaxResult saveMaterialInout(
		@RequestParam("Description") String description,
		@RequestParam("InoutQty") String inoutQty,
		@RequestParam("InoutType_hidden") String inoutType,
		@RequestParam(value="cboCompany", required = false) Integer companyId,
		@RequestParam("Material_id") String materialId,
		@RequestParam("StoreHouse_id") String storeHouseId,
		@RequestParam("inoutDate") String inoutDateStr,
		@RequestParam(value = "mio_pk", required = false) Integer mio_pk,
		@RequestParam("cboMaterialGroup") String cboMaterialGroup,
		@RequestParam("cboMaterialType") String cboMaterialType,
		@RequestParam("type") String type,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		Integer matPk = Integer.parseInt(materialId);
		String state = "confirmed";
		String _status = "a";
		int qty = Integer.parseInt(
			inoutQty.replace(",", "").replaceAll("[^\\d-]", "")
		);

		result.success = false;

		boolean isUpdate = false;

		MaterialInout mi;
		if (mio_pk != null) {
			isUpdate = true;
			mi = matInoutRepository.findById(mio_pk)
						 .orElseThrow(() -> new RuntimeException("기존 데이터 없음: " + mio_pk));
		} else {
			mi = new MaterialInout();
		}

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
		LocalDateTime dateTime = LocalDateTime.parse(inoutDateStr, formatter);

		mi.setInoutDate(dateTime.toLocalDate());
		mi.setInoutTime(dateTime.toLocalTime());
		mi.setCompanyId(companyId);
		mi.setMaterialId(matPk);
		mi.setStoreHouseId(Integer.parseInt(storeHouseId));

		Material m = this.materialRepository.getMaterialById(matPk);

		String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

		if (type.equals("in")) {
			mi.setInOut("in");
			mi.setInputType(inoutType);

			boolean isWaiting = mi.getState() != null && mi.getState().equals("waiting");

			if(testYn.equals("Y") && isWaiting) {
				mi.setPotentialInputQty((float)qty);
				state = "waiting";
				_status = "t";
			} else {
				mi.setInputQty((float)qty);
				mi.setOutputQty(0f);
				mi.setOutputType("");
			}
		} else if(type.equals("recall")){
			mi.setInOut("recall");
			mi.setOutputType(inoutType);
			mi.setOutputQty((float)qty);
			mi.setInputQty(0f);
			mi.setInputType("");

		} else if(type.equals("return")){
			mi.setInOut("return");
			mi.setInputType(inoutType);
			mi.setInputQty((float)qty);
			mi.setOutputQty(0f);
			mi.setOutputType("");

		} else  {
			mi.setInOut("out");
			mi.setOutputType(inoutType);
			mi.setOutputQty((float)qty);
			mi.setInputQty(0f);
			mi.setInputType("");
		}
		mi.setDescription(description);
		mi.setState(state);
		mi.set_status(_status);
		mi.set_audit(user);
		mi.setSpjangcd(spjangcd);

		this.matInoutRepository.save(mi);
		this.matInoutRepository.flush();


//		jdbcTemplate.query(
//				"SELECT sp_update_mat_in_house_by_inout(?, ?)",
//				rs -> {},  // 결과 무시
//				matPk, Integer.parseInt(storeHouseId)
//		);

		result.success = true;

		return result;
	}

	@PostMapping("/save_nocomp")
	@Transactional
	public AjaxResult saveMaterialInout_noComp(
		@RequestParam("Description") String description,
		@RequestParam("InoutQty") String inoutQty,
		@RequestParam("InoutType_hidden") String inoutType,
		@RequestParam("Material_id") String materialId,
		@RequestParam("StoreHouse_id") String storeHouseId,
		@RequestParam("inoutDate") String inoutDateStr,
		@RequestParam(value = "mio_pk", required = false) Integer mio_pk,
		@RequestParam("cboMaterialGroup") String cboMaterialGroup,
		@RequestParam("cboMaterialType") String cboMaterialType,
		@RequestParam("type") String type,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		Integer matPk = Integer.parseInt(materialId);
		String state = "confirmed";
		String _status = "a";
		int qty = Integer.parseInt(
			inoutQty.replace(",", "").replaceAll("[^\\d-]", "")
		);

		result.success = false;

		boolean isUpdate = false;

		MaterialInout mi;
		if (mio_pk != null) {
			isUpdate = true;
			mi = matInoutRepository.findById(mio_pk)
						 .orElseThrow(() -> new RuntimeException("기존 데이터 없음: " + mio_pk));
		} else {
			mi = new MaterialInout();
		}

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
		LocalDateTime dateTime = LocalDateTime.parse(inoutDateStr, formatter);

		mi.setInoutDate(dateTime.toLocalDate());
		mi.setInoutTime(dateTime.toLocalTime());
		mi.setMaterialId(matPk);
		mi.setStoreHouseId(Integer.parseInt(storeHouseId));

		Material m = this.materialRepository.getMaterialById(matPk);

		String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

		if (type.equals("in")) {
			mi.setInOut("in");
			mi.setInputType(inoutType);

			boolean isWaiting = mi.getState() != null && mi.getState().equals("waiting");

			if(testYn.equals("Y") && isWaiting) {
				mi.setPotentialInputQty((float)qty);
				state = "waiting";
				_status = "t";
			} else {
				mi.setInputQty((float)qty);
				mi.setOutputQty(0f);
				mi.setOutputType("");
			}
		} else if(type.equals("recall")){
			mi.setInOut("recall");
			mi.setOutputType(inoutType);
			mi.setOutputQty((float)qty);
			mi.setInputQty(0f);
			mi.setInputType("");

		} else if(type.equals("return")){
			mi.setInOut("return");
			mi.setInputType(inoutType);
			mi.setInputQty((float)qty);
			mi.setOutputQty(0f);
			mi.setOutputType("");

		} else  {
			mi.setInOut("out");
			mi.setOutputType(inoutType);
			mi.setOutputQty((float)qty);
			mi.setInputQty(0f);
			mi.setInputType("");
		}
		mi.setDescription(description);
		mi.setState(state);
		mi.set_status(_status);
		mi.set_audit(user);
		mi.setSpjangcd(spjangcd);

		this.matInoutRepository.save(mi);
		this.matInoutRepository.flush();


//		jdbcTemplate.query(
//				"SELECT sp_update_mat_in_house_by_inout(?, ?)",
//				rs -> {},  // 결과 무시
//				matPk, Integer.parseInt(storeHouseId)
//		);

		result.success = true;

		return result;
	}

	@GetMapping("/matinout_detail")
	public AjaxResult getMaterialInoutDetail(
		@RequestParam(value = "mio_pk", required=false) Integer mio_pk) {

		List<Map<String, Object>> items = materialInoutService.getMaterialInoutDetail(mio_pk);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@PostMapping("/delete")
	@Transactional
	public AjaxResult getInoutDelete(@RequestBody Map<String, Object> body) {
		Integer mio_pk = Integer.valueOf(body.get("mio_pk").toString());

		AjaxResult result = new AjaxResult();

		// 1️⃣ mat_inout 존재 여부 확인
		MaterialInout mi = matInoutRepository.findById(mio_pk)
												 .orElseThrow(() -> new RuntimeException("기존 데이터 없음: " + mio_pk));

		Integer matPk = mi.getMaterialId();
		Integer storeHouseId = mi.getStoreHouseId();

		// 2️⃣ mat_lot 삭제 (참조되는 lot 데이터 제거)
		jdbcTemplate.update(
			"DELETE FROM mat_lot WHERE \"SourceTableName\" = ? AND \"SourceDataPk\" = ?",
			"mat_inout", mio_pk
		);

		// 3️⃣ mat_inout 삭제
		matInoutRepository.deleteById(mio_pk);

		// ✅ (선택) 관련 재고 보정 함수 호출 가능
		// jdbcTemplate.query("SELECT sp_update_mat_in_house_by_inout(?, ?)", rs -> {}, matPk, storeHouseId);

		result.success = true;
		return result;
	}

	// 엑셀데이터 그리드로 변환
	@SuppressWarnings("unchecked")
	@GetMapping("/trans_multi_input_data")
	public AjaxResult transMultiInputData(
		@RequestParam MultiValueMap<String,Object> Q
	) throws JSONException, JsonMappingException, JsonProcessingException {

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();

		for(int i = 0; i < data.size(); i++) {
			if(data.get(i).get("mat_code").toString().isEmpty()) {
				continue;
			}
			JSONObject row = new JSONObject();
			Material m = this.materialRepository.findByCode(data.get(i).get("mat_code").toString());
			if (m != null) {
				row.put("mat_name", m.getName());
			}
			row.put("input_qty", data.get(i).get("input_qty").toString());
			row.put("mat_code", data.get(i).get("mat_code").toString());
			Map<String, Object> map = new ObjectMapper().readValue(row.toString(), Map.class) ;
			items.add(map);

		}
		result.data = items;
		return result;
	}

	@PostMapping("/save_multi_data")
	@Transactional
	public AjaxResult saveMultiData(
		@RequestParam("Company_id") String companyId,
		@RequestParam("InoutType") String inoutType,
		@RequestParam MultiValueMap<String,Object> Q,
		@RequestParam("StoreHouse_id") String storeHouseId,
		@RequestParam("type") String type,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		// 현재 일자
		LocalDate date = LocalDate.now();
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");

		// 현재 시간
		LocalTime time = LocalTime.now();
		DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

		String state = "confirmed";
		String _status = "a";

		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		AjaxResult result = new AjaxResult();

		result.success = false;
		for (int i=0; i < data.size(); i++) {
			if(data.get(i).get("mat_code").toString().isEmpty()) {
				continue;
			}

			Material m = this.materialRepository.findByCode(data.get(i).get("mat_code").toString());
			String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";
			Integer matId = m.getId();
			Integer qty = Integer.parseInt(data.get(i).get("input_qty").toString());

			MaterialInout mi = new MaterialInout();
			mi.setMaterialId(matId);
			mi.setInoutDate(LocalDate.parse(date.format(dateFormat)));
			mi.setInoutTime(LocalTime.parse(time.format(timeFormat)));
			mi.setCompanyId(CommonUtil.tryIntNull(companyId));
			mi.setStoreHouseId(Integer.parseInt(storeHouseId));

			if (type.equals("in")) {
				mi.setInOut("in");
				mi.setInputType(inoutType);
				if(testYn.equals("Y")) {
					mi.setPotentialInputQty((float)qty);
					state = "waiting";
					_status = "t";
				} else {
					mi.setInputQty((float)qty);
					mi.setOutputQty(0f);
					mi.setOutputType("");
				}
			} else if(type.equals("recall")){
				mi.setInOut("recall");
				mi.setOutputType(inoutType);
				mi.setOutputQty((float)qty);
				mi.setInputQty(0f);
				mi.setInputType("");

			} else if(type.equals("return")){
				mi.setInOut("return");
				mi.setInputType(inoutType);
				mi.setInputQty((float)qty);
				mi.setOutputQty(0f);
				mi.setOutputType("");

			} else  {
				mi.setInOut("out");
				mi.setOutputType(inoutType);
				mi.setOutputQty((float)qty);
				mi.setInputQty(0f);
				mi.setInputType("");
			}
			mi.setState(state);
			mi.set_status(_status);
			mi.set_audit(user);
			mi.setSpjangcd(spjangcd);
			this.matInoutRepository.save(mi);

		}
		result.success = true;

		return result;
	}

	@GetMapping("/mio_lot_list")
	public AjaxResult mioLotList(
		@RequestParam("mio_id") String mioId) {

		List<Map<String, Object>> items = this.lotService.mioLotList(mioId);
		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}

	@GetMapping("/mio_test_list")
	public AjaxResult mioTestList(
		@RequestParam("mio_id") Integer mioId) {


		List<TestResult> trList = this.testResultRepository.findBySourceTableNameAndSourceDataPk("mat_inout", mioId);

		List<Map<String, Object>> items = null;
		Integer testMasterId = null;

		if (!trList.isEmpty()) {
			items = this.materialInoutService.mioTestList(mioId,trList.get(0).getId());
		} else {
			testMasterId = this.materialInoutService.getTestMasterByItem(mioId);

			if (testMasterId != null) {
				items = this.materialInoutService.prodTestListByTestMaster(testMasterId);
			} else{
				items = this.materialInoutService.mioTestDefaultList();
			}

		}

		Map<String, Object> effectDt = this.materialInoutService.getEffectDate(mioId);

		String effDt = effectDt.get("EffectiveDate") != null ? effectDt.get("EffectiveDate").toString() : null;


		Map<String, Object> item = new HashMap<>();

		item.put("EffectiveDate", effDt);
		item.put("testDate", items.get(0).get("testDate"));
		item.put("CheckName", items.get(0).get("CheckName"));
		item.put("JudgeCode", items.get(0).get("JudgeCode"));
		item.put("CharResult", items.get(0).get("CharResult"));
		item.put("testMasterId", items.get(0).get("testMasterId"));
		item.put("testResultId", items.get(0).get("testResultId"));
		item.put("mioList", items);

		AjaxResult result = new AjaxResult();
		result.data = item;
		return result;
	}

	@PostMapping("/lot_save")
	@Transactional
	public AjaxResult lotSave(
		@RequestBody MultiValueMap<String,Object> Q,
		@RequestParam("Material_id") String materialId,
		@RequestParam("StoreHouse_id") Integer storeHouseId,
		@RequestParam("mio_id") String mioId,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		Timestamp today = new Timestamp(System.currentTimeMillis());

		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		result.success = false;
		for (int i=0; i < data.size(); i++) {
			MaterialLot ml = null;
			String LotNumber = null;
			if (!data.get(i).get("LotNumber").toString().isEmpty()) {
				LotNumber = data.get(i).get("LotNumber").toString();
				ml = this.matLotRepository.getByLotNumber(LotNumber);
				if (data.get(i).get("Description") != null) {
					ml.setDescription(data.get(i).get("Description").toString());
				}
				ml.setSpjangcd(spjangcd);
				this.matLotRepository.save(ml);
			} else {
				LotNumber = this.lotService.make_lot_in_number();
				String effectiveDate = data.get(i).get("EffectiveDate").toString() + " 00:00:00";
				ml = new MaterialLot();
				ml.setLotNumber(LotNumber);
				ml.setMaterialId(Integer.parseInt(materialId));
				ml.setInputQty(Float.parseFloat(data.get(i).get("InputQty").toString()));
				ml.setCurrentStock(Float.parseFloat(data.get(i).get("InputQty").toString()));
				ml.setInputDateTime(today);
				ml.setEffectiveDate(Timestamp.valueOf(effectiveDate));
				ml.setSourceTableName("mat_inout");
				ml.setSourceDataPk(Integer.parseInt(mioId));
				if (data.get(i).get("Description") != null) {
					ml.setDescription(data.get(i).get("Description").toString());
				}
				ml.setStoreHouseId(storeHouseId);
				ml.set_audit(user);
				ml.setSpjangcd(spjangcd);
				ml = this.matLotRepository.save(ml);
			}

			result.success = true;
		}

		return result;
	}

	@PostMapping("/test_save")
	@Transactional
	public AjaxResult testSave(
		@RequestBody MultiValueMap<String,Object> Q,
		@RequestParam(value = "material_id", required = false) Integer materialId,
		@RequestParam(value = "testRemark", required = false) String testRemark,
		@RequestParam(value = "test_mast_id", required = false) String testMastId,
		@RequestParam(value = "test_result_id", required = false) String testResultId,
		@RequestParam(value = "judg_grp", required = false) String judgGrp,
		@RequestParam(value = "test_date", required = false) String test_date,
		@RequestParam(value = "effective_date", required = false) String effectiveDate,
		@RequestParam(value = "mio_id", required = false) Integer mioId,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		Timestamp testDate = Timestamp.valueOf(test_date+ " 00:00:00");

		if (StringUtils.hasText(testResultId)) {
			List<TestItemResult> trList = this.testItemResultRepository.findByTestResultId(Integer.parseInt(testResultId));

			// 결과 삭제
			if(trList.size() > 0) {
				for (int i = 0; i < trList.size(); i++) {
					this.testItemResultRepository.deleteById(trList.get(i).getId());
				}
			}

			this.testItemResultRepository.flush();
		}


		TestResult tr = new TestResult();

		if (StringUtils.hasText(testResultId)) {
			tr = this.testResultRepository.getTestResultById(Integer.parseInt(testResultId));
		} else {
			tr.setSourceDataPk(mioId);
			tr.setSourceTableName("mat_inout");
			tr.setMaterialId(materialId);
		}

		tr.setTestMasterId(Integer.parseInt(testMastId));
		tr.setTestDateTime(testDate);
		tr.set_audit(user);
		tr.setSpjangcd(spjangcd);

		this.testResultRepository.saveAndFlush(tr);


		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		for(int i = 0; i < data.size(); i++) {
			TestItemResult tir = new TestItemResult();
			tir.setJudgeCode(judgGrp);
			tir.setTestDateTime(testDate);
			tir.setSampleID(String.valueOf(materialId) + "/" +mioId);
			tir.setCharResult(testRemark);
			tir.setTestItemId(Integer.parseInt(data.get(i).get("id").toString()));
			tir.setTestResultId(tr.getId());

			if(data.get(i).get("result1") != null) {
				tir.setChar1(data.get(i).get("result1").toString());
			}
			tir.set_audit(user);
			tir.setSpjangcd(spjangcd);
			this.testItemResultRepository.save(tir);
		}

		MaterialInout mi = this.matInoutRepository.getMatInoutById(mioId);
		// 유효기간 변경
		if(StringUtils.hasText(effectiveDate)) {
			Timestamp effDt = Timestamp.valueOf(effectiveDate+ " 00:00:00");
			mi.setEffectiveDate(effDt);
		}

		mi.setState("confirmed");
		if (!"부적합".equals(judgGrp)) {
			// 적합한 경우에만 입고 처리
			mi.setInputQty(mi.getPotentialInputQty());
			mi.setPotentialInputQty((float)0);

			// 트리거 작동용 상태 변경
			mi.set_status("a");
		}

		this.matInoutRepository.save(mi);

		Map<String, Object> item = new HashMap<>();
		item.put("id", mioId);

		result.data = item;

		return result;
	}

	@PostMapping("/check_in_test")
	@Transactional
	public AjaxResult checkInTest(
		@RequestBody MultiValueMap<String,Object> Q,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		for(int i = 0; i < data.size(); i++) {
			Integer id = Integer.parseInt(data.get(i).get("id").toString());
			Float inputQty = Float.parseFloat(data.get(i).get("PotentialInputQty").toString());
			MaterialInout mi = this.matInoutRepository.getMatInoutById(id);
			mi.setInputQty(inputQty);
			mi.setPotentialInputQty((float)0);
			mi.setState("confirmed");
			mi.set_status("a");
			mi.set_audit(user);
			this.matInoutRepository.save(mi);
		}

		return result;
	}

	@GetMapping("/read_balju")
	public AjaxResult getbaljuList(
		@RequestParam(value="start", required=false) String start_date,
		@RequestParam(value="end", required=false) String end_date,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request) {

		start_date = start_date + " 00:00:00";
		end_date = end_date + " 23:59:59";

		Timestamp start = Timestamp.valueOf(start_date);
		Timestamp end = Timestamp.valueOf(end_date);

		List<Map<String, Object>> items = this.materialInoutService.getBaljuList(start, end, spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/read_balju_in")
	public AjaxResult getbaljuInList(
		@RequestParam(value="start", required=false) String start_date,
		@RequestParam(value="end", required=false) String end_date,
		@RequestParam(value="cboCompanyHidden", required=false) Integer cboCompany,
		@RequestParam(value = "keyword", required=false) String keyword,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request) {

		start_date = start_date + " 00:00:00";
		end_date = end_date + " 23:59:59";

		Timestamp start = Timestamp.valueOf(start_date);
		Timestamp end = Timestamp.valueOf(end_date);

		List<Map<String, Object>> items = this.materialInoutService.getBaljuInList(start, end, spjangcd, cboCompany, keyword);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@PostMapping("/save_balju")
	@Transactional
	public AjaxResult saveBaljuInout(
		@RequestBody List<Map<String, Object>> baljuList,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		for (Map<String, Object> item : baljuList) {
			try {
				Integer bal_pk = (Integer) item.get("id");
				String description = (String) item.get("Description2");
				if (description == null || description.trim().isEmpty()) {
					description = "발주 입고";
				}
				String inoutQtyStr = String.valueOf(item.get("inputQty")); // '입고 수량'
				String materialIdStr = String.valueOf(item.get("Material_id"));
				String storeHouseIdStr = String.valueOf(item.get("StoreHouse_id"));

				Integer matPk = Integer.parseInt(materialIdStr);
				Integer qty = Integer.parseInt(inoutQtyStr);

				MaterialInout mi = new MaterialInout();
				mi.setInoutDate(LocalDate.now());
				mi.setInoutTime(LocalTime.now());
				mi.setMaterialId(matPk);
				mi.setStoreHouseId(Integer.parseInt(storeHouseIdStr));

				Material m = materialRepository.getMaterialById(matPk);
				String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

				if ("Y".equals(testYn)) {
					mi.setPotentialInputQty((float) qty);
					mi.setState("waiting");
					mi.set_status("t");
				} else {
					mi.setInputQty((float) qty);
					mi.setState("confirmed");
					mi.set_status("a");
				}

				mi.setDescription(description);
				mi.setInOut("in");
				mi.set_audit(user);
				mi.setSourceDataPk(bal_pk);
				mi.setSourceTableName("balju");
				mi.setSpjangcd((String) item.get("spjangcd"));
				mi.setCompanyId((Integer) item.get("Company_id"));

				Balju balju = this.bujuRepository.getBujuById(bal_pk);

				double sujuQty2 = jdbcTemplate.queryForObject("""
					SELECT COALESCE(SUM("InputQty"), 0)
					FROM mat_inout
					WHERE "SourceDataPk" = ? 
					  AND "SourceTableName" = 'balju'
					  AND COALESCE("_status", 'a') = 'a'
				""", Double.class, bal_pk);

				balju.setShipmentState(storeHouseIdStr);
				mi.setInputType("order_in");

				matInoutRepository.save(mi);
				bujuRepository.save(balju);

			} catch (Exception e) {
				result.success = false;
				result.message = "처리 중 오류 발생: " + e.getMessage();
				return result;
			}
		}
		result.success = true;

		return result;
	}

	@PostMapping("/force-complete")
	@Transactional
	public AjaxResult forceCompleteSuju(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();

		List<Integer> sujuPkList = (List<Integer>) payload.get("baljuPkList");
		bujuRepository.forceCompleteBaljuList(sujuPkList);
		return result;
	}

	@PostMapping("/save_balju_return")
	@Transactional
	public AjaxResult saveBaljuReturn(
		@RequestBody List<Map<String, Object>> baljuList,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		for (Map<String, Object> item : baljuList) {
			try {
				Integer bal_pk = (Integer) item.get("id");
				String description = (String) item.get("Description2");
				if (description == null || description.trim().isEmpty()) {
					description = "발주 반품";
				}
				String inoutQtyStr = String.valueOf(item.get("returnQty")); // '반품 수량'
				String materialIdStr = String.valueOf(item.get("Material_id"));
				String storeHouseIdStr = String.valueOf(item.get("StoreHouse_id"));

				Integer matPk = Integer.parseInt(materialIdStr);
				Integer qty = Integer.parseInt(inoutQtyStr);

				MaterialInout mi = new MaterialInout();
				mi.setInoutDate(LocalDate.now());
				mi.setInoutTime(LocalTime.now());
				mi.setMaterialId(matPk);
				mi.setStoreHouseId(Integer.parseInt(storeHouseIdStr));

				mi.setInputQty((float) qty);
				mi.setState("confirmed");
				mi.set_status("a");
				mi.setDescription(description);
				mi.setInOut("return");
				mi.set_audit(user);
				mi.setSourceDataPk(bal_pk);
				mi.setSourceTableName("balju");
				mi.setSpjangcd((String) item.get("spjangcd"));
				mi.setCompanyId((Integer) item.get("Company_id"));

				Balju balju = this.bujuRepository.getBujuById(bal_pk);

				double sujuQty2 = jdbcTemplate.queryForObject("""
					SELECT COALESCE(SUM("InputQty"), 0)
					FROM mat_inout
					WHERE "SourceDataPk" = ? 
					  AND "SourceTableName" = 'balju'
					  AND COALESCE("_status", 'a') = 'a'
				""", Double.class, bal_pk);

				balju.setShipmentState(storeHouseIdStr);
				mi.setInputType("balju_return");

				matInoutRepository.save(mi);
				bujuRepository.save(balju);

			} catch (Exception e) {
				result.success = false;
				result.message = "처리 중 오류 발생: " + e.getMessage();
				return result;
			}
		}
		result.success = true;

		return result;
	}

	@PostMapping("/save_scan")
	@Transactional
	public AjaxResult saveScanInput(
		@RequestBody Map<String, Object> payload,
		Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer companyId   = CommonUtil.tryIntNull(payload.get("company_id"));
		Integer storeHouseId = CommonUtil.tryIntNull(payload.get("store_house_id"));
		String  spjangcd     = (String) payload.get("spjangcd");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

		if (storeHouseId == null) {
			result.success = false; result.message = "입고창고가 없습니다."; return result;
		}
		if (items == null || items.isEmpty()) {
			result.success = false; result.message = "스캔 항목이 없습니다."; return result;
		}

		Timestamp now = new Timestamp(System.currentTimeMillis());

		try {
			for (Map<String, Object> it : items) {
				Integer matPk = CommonUtil.tryIntNull(it.get("Material_id"));
				if (matPk == null) continue;

				int qty = (int) Double.parseDouble(String.valueOf(it.get("qty")));
				String scanLot   = CommonUtil.tryString(it.get("lot_number"));    // UDI (10), 없으면 ""
				String effStr    = CommonUtil.tryString(it.get("effective_date")); // UDI (17), yyyy-MM-dd or ""
				String barcode   = CommonUtil.tryString(it.get("barcode"));

				Material m = materialRepository.getMaterialById(matPk);
				String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

				// 1) 입고 레코드 생성 (save_balju 패턴)
				MaterialInout mi = new MaterialInout();
				mi.setInoutDate(LocalDate.now());
				mi.setInoutTime(LocalTime.now());
				mi.setMaterialId(matPk);
				mi.setStoreHouseId(storeHouseId);
				mi.setCompanyId(companyId);
				mi.setInOut("in");
				mi.setInputType("scan_in");
				mi.setDescription("스캐너 입고" + (barcode.isEmpty() ? "" : " / " + barcode));

				if ("Y".equals(testYn)) {
					mi.setPotentialInputQty((float) qty);
					mi.setState("waiting");
					mi.set_status("t");
				} else {
					mi.setInputQty((float) qty);
					mi.setState("confirmed");
					mi.set_status("a");
				}
				mi.set_audit(user);
				mi.setSpjangcd(spjangcd);
				matInoutRepository.save(mi);
				matInoutRepository.flush(); // mio_pk 확보

				// 2) 로트 생성 — 검사대기(가입고)면 로트 생성 보류 (검사 합격 후 로트입고)
				boolean isWaiting = "Y".equals(testYn);
				boolean lotManaged = "Y".equals(m.getLotUseYn());

				if (lotManaged && !isWaiting) {
					// 로트번호: UDI 있으면 원본, 없으면 채번
					String lotNumber = !scanLot.isEmpty()
															 ? scanLot
															 : lotService.make_lot_in_number();

					// 유효기한: UDI (17) 우선, 없으면 품목 ValidDays
					Timestamp effDt = null;
					if (!effStr.isEmpty()) {
						effDt = Timestamp.valueOf(effStr + " 00:00:00");
					} else if (m.getValidDays() != null) {
						effDt = Timestamp.valueOf(
							LocalDate.now().plusDays(m.getValidDays()) + " 00:00:00");
					}

					// 동일 품목+로트 중복 체크 (외부 UDI 재입고 대비)
					MaterialLot ml = (MaterialLot) matLotRepository
																					 .findByMaterialIdAndLotNumberAndSpjangcd(matPk, lotNumber, spjangcd)
																					 .orElse(null);

					if (ml == null) {
						ml = new MaterialLot();
						ml.setLotNumber(lotNumber);
						ml.setMaterialId(matPk);
						ml.setInputQty((float) qty);
						ml.setCurrentStock((float) qty);
						ml.setInputDateTime(now);
						ml.setEffectiveDate(effDt);
						ml.setSourceTableName("mat_inout");  // ★ 기존 추적/삭제와 동일
						ml.setSourceDataPk(mi.getId());       // ★ 방금 만든 입고에 연결
						ml.setStoreHouseId(storeHouseId);
						ml.set_audit(user);
						ml.setSpjangcd(spjangcd);
						matLotRepository.save(ml);
					} else {
						// 같은 외부 로트 재입고 → 입고량 누적 (트리거 공식과 동일하게 유지)
						float newInput = (ml.getInputQty() == null ? 0f : ml.getInputQty()) + qty;
						float outSum   = (ml.getOutQtySum() == null ? 0f : ml.getOutQtySum());
						ml.setInputQty(newInput);
						ml.setCurrentStock(newInput - outSum);
						if (ml.getEffectiveDate() == null && effDt != null) {
							ml.setEffectiveDate(effDt);
						}
						ml.set_audit(user);
						matLotRepository.save(ml);
					}
				}
			}

			result.success = true;
		} catch (Exception e) {
			result.success = false;
			result.message = "스캔 입고 처리 중 오류: " + e.getMessage();
			// @Transactional 이라 예외 시 롤백
			throw new RuntimeException(e);
		}
		return result;
	}

	/**
	 * 스캔 바코드 → 품목 역조회.
	 *
	 * 우선순위
	 *   1) GS1 / EAN   — GTIN-14 (material_barcode."GTIN")
	 *   2) HIBC / ISBT — UDI-DI  (material_barcode."UdiDi")
	 *   3) 내부 바코드 — 자사 발행 로트번호 (mat_lot."LotNumber")
	 *
	 * 반환 shape 은 세 경로가 동일해야 한다. 화면이 어느 경로로 매칭됐는지 모르고 쓰기 때문.
	 *   material_id / material_code / material_name / valid_days / pack_qty / effective_date
	 *
	 * data == null → 미등록 바코드. 화면에서 빨간색 처리.
	 */
	@GetMapping("/scan_lookup")
	public AjaxResult scanLookup(
		@RequestParam(value="gtin14", required=false) String gtin14,
		@RequestParam(value="di",     required=false) String di,
		@RequestParam(value="lot",    required=false) String lot,
		@RequestParam(value="barcode_type", required=false) String barcodeType,
		@RequestParam(value="raw", required=false) String raw,
		@RequestParam(value="spjangcd", required=false) String spjangcd) {

		AjaxResult result = new AjaxResult();
		Map<String,Object> data = null;

		// 1) GS1 / EAN — GTIN-14 로 품목 매칭
		if (gtin14 != null && !gtin14.isEmpty())
			data = materialInoutService.findMaterialByGtin(gtin14, spjangcd);

		// 2) HIBC / ISBT — UDI-DI 문자열로 품목 매칭
		if (data == null && di != null && !di.isEmpty())
			data = materialInoutService.findMaterialByUdiDi(di, spjangcd);

		// 3) 자사 품목코드 — 바코드가 없는 자재는 material."Code" 를 그대로 찍는다.
		//    파싱 결과(gtin/di)보다 뒤에 두되, 로트 역추적보다는 앞이다 —
		//    품목코드가 우연히 어떤 로트번호와 같을 때 품목 쪽이 맞다.
		if (data == null && raw != null && !raw.isEmpty())
			data = materialInoutService.findMaterialByCode(raw, spjangcd);

		// 4) 내부 바코드 — 자사가 발행한 로트번호로 역추적
		if (data == null && lot != null && !lot.isEmpty()) {
			MaterialLot ml = matLotRepository.getByLotNumber(lot);
			if (ml != null) {
				Material m = materialRepository.getMaterialById(ml.getMaterialId());
				data = new HashMap<>();
				data.put("material_id",   ml.getMaterialId());
				data.put("material_code", m.getCode());
				data.put("material_name", m.getName());
				data.put("gtin", gtin14 != null ? gtin14 : "");
				data.put("effective_date",
					ml.getEffectiveDate() != null
						? ml.getEffectiveDate().toLocalDateTime().toLocalDate().toString() : "");
				data.put("valid_days", m.getValidDays());
			}
		}

		// 세 경로의 shape 통일 — 화면이 qty × pack_qty 로 환산한다
		if (data != null) {
			data.putIfAbsent("pack_qty", 1);
			data.putIfAbsent("effective_date", "");
		}

		result.data = data;
		result.success = true;   // 조회 자체는 성공. 미등록 여부는 data == null 로 판단
		return result;
	}

	/**
	 * 미등록 바코드를 품목에 연결한다.
	 *
	 * ★ 품목당 1건이다. 기존 바코드가 있으면 서비스가 소프트 삭제하고 새로 넣는다.
	 *   ux_matbc_one_per_material 과 ux_matbc_gtin 이 DB 에서 한 번 더 막는다 —
	 *   두 단말이 동시에 등록해도 한 쪽만 통과한다.
	 *
	 * ★ 등록은 「발주 라인 중에서 고르기」로 하는 게 안전하다.
	 *   전체 품목에서 찾으면 엉뚱한 품목에 붙을 수 있고, 첫 연결이 틀리면
	 *   그 뒤로는 스캔이 자동이라 조용히 계속 틀린다.
	 */
	@PostMapping("/barcode_register")
	public AjaxResult barcodeRegister(
		@RequestParam("material_id") Integer materialId,
		@RequestParam(value="gtin", required=false) String gtin,
		@RequestParam(value="udi_di", required=false) String udiDi,
		@RequestParam(value="barcode_type", required=false) String barcodeType,
		@RequestParam(value="pack_level", required=false) String packLevel,
		@RequestParam(value="pack_qty", required=false) java.math.BigDecimal packQty,
		@RequestParam(value="company_id", required=false) Integer companyId,
		@RequestParam(value="spjangcd", required=false) String spjangcd,
		Authentication auth) {

		AjaxResult result = new AjaxResult();
		try {
			User user = (User) auth.getPrincipal();
			materialInoutService.registerBarcode(materialId, barcodeType, gtin, udiDi,
				companyId, spjangcd, user.getId(), packLevel, packQty);
			result.success = true;
		} catch (IllegalArgumentException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			result.success = false;
			result.message = "이미 다른 품목에 등록된 바코드입니다.";
		}
		return result;
	}

	@PostMapping("/lot_save_by_po")
	@Transactional
	public AjaxResult lotSaveByPo(
		@RequestBody Map<String, Object> payload,
		Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String spjangcd = (String) payload.get("spjangcd");
		Integer storeHouseFallback = CommonUtil.tryIntNull(payload.get("store_house_id"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lines = (List<Map<String, Object>>) payload.get("lines");

		if (lines == null || lines.isEmpty()) {
			result.success = false; result.message = "입고할 품목이 없습니다."; return result;
		}

		Timestamp now = new Timestamp(System.currentTimeMillis());

		try {
			for (Map<String, Object> line : lines) {
				Integer balPk = CommonUtil.tryIntNull(line.get("balju_id"));   // = balju.id
				Integer matPk = CommonUtil.tryIntNull(line.get("Material_id"));
				if (balPk == null || matPk == null) continue;

				int qty = (int) Double.parseDouble(String.valueOf(line.get("InputQty")).replace(",", ""));
				if (qty <= 0) continue;

				Integer storeHouseId = CommonUtil.tryIntNull(line.get("StoreHouse_id"));
				if (storeHouseId == null) storeHouseId = storeHouseFallback;

				// ★ 과입고 차단 (기입고합계 + 이번수량 > 발주수량 이면 롤백)
				double alreadyIn = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM("InputQty"), 0)
                FROM mat_inout
                WHERE "SourceDataPk" = ?
                  AND "SourceTableName" = 'balju'
                  AND COALESCE("_status", 'a') = 'a'
                  AND "InOut" = 'in'
            """, Double.class, balPk);

				Balju balju = this.bujuRepository.getBujuById(balPk);
				double baljuQty = balju.getSujuQty() != null ? balju.getSujuQty() : 0d;
				if (alreadyIn + qty > baljuQty) {
					result.success = false;
					result.message = "미입고수량 초과 (발주라인 " + balPk + ", 발주 " + (int) baljuQty
														 + " / 기입고 " + (int) alreadyIn + " / 요청 " + qty + ")";
					throw new RuntimeException(result.message);
				}

				// 1) 입고 레코드 — save_balju 와 동일
				Material m = materialRepository.getMaterialById(matPk);
				String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

				MaterialInout mi = new MaterialInout();
				mi.setInoutDate(LocalDate.now());
				mi.setInoutTime(LocalTime.now());
				mi.setMaterialId(matPk);
				mi.setStoreHouseId(storeHouseId);
				mi.setCompanyId(CommonUtil.tryIntNull(line.get("Company_id")));
				mi.setInOut("in");
				mi.setInputType("order_in");
				mi.setDescription("발주 스캔 입고");
				mi.setSourceDataPk(balPk);
				mi.setSourceTableName("balju");
				mi.set_audit(user);
				mi.setSpjangcd(spjangcd);

				boolean isWaiting = "Y".equals(testYn);
				if (isWaiting) {
					mi.setPotentialInputQty((float) qty);
					mi.setState("waiting");
					mi.set_status("t");
				} else {
					mi.setInputQty((float) qty);
					mi.setState("confirmed");
					mi.set_status("a");
				}
				matInoutRepository.save(mi);
				matInoutRepository.flush();

				balju.setShipmentState(String.valueOf(storeHouseId));
				bujuRepository.save(balju);

				// 2) 로트 — 가입고(검사대기) 아니고 로트관리 품목일 때만
				boolean lotManaged = "Y".equals(m.getLotUseYn());
				if (lotManaged && !isWaiting) {
					String lotNumber = (line.get("LotNumber") != null
																&& !String.valueOf(line.get("LotNumber")).isEmpty())
															 ? String.valueOf(line.get("LotNumber"))
															 : lotService.make_lot_in_number();

					String effStr = CommonUtil.tryString(line.get("EffectiveDate"));
					Timestamp effDt = null;
					if (!effStr.isEmpty()) {
						effDt = Timestamp.valueOf(effStr + " 00:00:00");
					} else if (m.getValidDays() != null) {
						effDt = Timestamp.valueOf(LocalDate.now().plusDays(m.getValidDays()) + " 00:00:00");
					}

					MaterialLot ml = new MaterialLot();
					ml.setLotNumber(lotNumber);
					/* ★ 제조사 로트(바코드 (10))는 LotNumber 가 아니라 여기 넣는다.
					     LotNumber 는 우리가 채번하는 사내 번호, MakerLotNo 는 외부가 붙인 번호다.
					     한 칸에 섞으면 사내 로트번호 체계가 매입처마다 제각각이 되고,
					     리콜 때 「그 제조사 로트」로 모아 보는 것도 못 한다.
					     포장(MakerLotNo=외부 UDI)·수리도 같은 규칙을 쓴다. */
					String makerLot = CommonUtil.tryString(line.get("MakerLotNo"));
					if (makerLot != null && !makerLot.isBlank()) ml.setMakerLotNo(makerLot.trim());
					ml.setMaterialId(matPk);
					ml.setInputQty((float) qty);
					ml.setCurrentStock((float) qty);
					ml.setInputDateTime(now);
					ml.setEffectiveDate(effDt);
					ml.setSourceTableName("mat_inout");
					ml.setSourceDataPk(mi.getId());
					ml.setStoreHouseId(storeHouseId);
					if (line.get("Description") != null) ml.setDescription(String.valueOf(line.get("Description")));
					ml.set_audit(user);
					ml.setSpjangcd(spjangcd);
					matLotRepository.save(ml);
				}
			}

			result.success = true;
		} catch (Exception e) {
			result.success = false;
			if (result.message == null) result.message = "발주 스캔 입고 오류: " + e.getMessage();
			throw new RuntimeException(e); // 롤백
		}
		return result;
	}

	@GetMapping("/receiving_by_barcode")
	public AjaxResult receivingByBarcode(
		@RequestParam("barcode") String barcode,
		@RequestParam("spjangcd") String spjangcd) {

		AjaxResult result = new AjaxResult();

		if (barcode == null || !barcode.toUpperCase().startsWith("PO")) {
			result.success = false; result.message = "발주 바코드가 아닙니다."; return result;
		}
		String jumunNumber = barcode.substring(2); // "PO" + JumunNumber

		List<Map<String, Object>> lines =
			this.materialInoutService.getBaljuLinesByJumunNumber(jumunNumber, spjangcd);

		Map<String, Object> data = new HashMap<>();
		data.put("JumunNumber", jumunNumber);
		data.put("lines", lines);
		if (lines != null && !lines.isEmpty()) {
			data.put("companyName", lines.get(0).get("CompanyName"));
			data.put("company_id",  lines.get(0).get("Company_id"));
		}

		result.data = data;
		result.success = true;
		return result;
	}

}