package mes.app.inventory;

import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

	@Autowired
	private Environment env;

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
		// 공장 필터. 빈 값 = 전체.
		@RequestParam(value = "factory_id", required=false) String factoryId,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutReceipt(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,factoryId,spjangcd);

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
		// 공장 필터. 빈 값 = 전체.
		@RequestParam(value = "factory_id", required=false) String factoryId,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutIssue(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,factoryId,spjangcd);

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
		// 공장 필터. 빈 값 = 전체.
		@RequestParam(value = "factory_id", required=false) String factoryId,
		@RequestParam(value = "keyword", required=false) String keyword) {

		List<Map<String, Object>> items = this.materialInoutService.getMaterialInoutDisposal(srchStartDt,srchEndDt,housePk,matType,matGrpPk,keyword,factoryId,spjangcd);

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

		/* 2️⃣-b 검사 사진 — 행과 «실제 파일» 을 둘 다 지운다.
		   ★ 행만 지우면 디스크에 고아 파일이 영원히 쌓인다. 아무도 못 찾는데
		     용량만 먹는다. 파일만 지우면 목록에 깨진 이미지가 남는다.
		   ★ mat_inout 을 먼저 지우면 "MatInout_id" 로 사진을 찾을 수 없다.
		     반드시 이 순서여야 한다. */
		this.deletePhotosOfMio(mio_pk);

		/* 2️⃣-c 입고검사 기록.
		   ★ 입고 건이 사라지면 그 검사도 가리킬 대상이 없다. 남겨 두면
		     test_result."SourceDataPk" 가 존재하지 않는 mat_inout 을 가리키는
		     고아 행이 되어, 어느 화면에도 안 보이면서 집계에만 잡힌다.
		     이 API 는 이미 mat_lot 을 통째로 지우는 «되돌리기» 성격이므로
		     검사 기록도 같은 규칙으로 정리한다.
		   ★ 자식(test_item_result)을 «먼저» 지운다. 순서가 바뀌면 FK 가
		     걸려 있을 때 막힌다. */
		List<Map<String, Object>> trIds = jdbcTemplate.queryForList(
			"SELECT id FROM test_result WHERE \"SourceTableName\" = ? AND \"SourceDataPk\" = ?",
			"mat_inout", mio_pk);

		for (Map<String, Object> r : trIds) {
			Integer trId = (Integer) r.get("id");
			jdbcTemplate.update("DELETE FROM test_item_result WHERE \"TestResult_id\" = ?", trId);
			jdbcTemplate.update("DELETE FROM test_result WHERE id = ?", trId);
		}

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
		// 이미 저장된 검사 사진. 없으면 빈 배열이라 화면은 «사진 없음» 으로 그린다.
		item.put("files", this.mioPhotoList(mioId));

		AjaxResult result = new AjaxResult();
		result.data = item;
		return result;
	}

	/**
	 * 검사유형으로 검사항목 목록을 가져온다.
	 *
	 * ★ 품목에 검사유형이 매핑돼 있지 않은 건은 화면이 «기본 검사유형» 을 골라 준다.
	 *   그때 항목 목록도 그 유형 것으로 바뀌어야 한다. 예전에는 항목이
	 *   mioTestDefaultList() 로 고정이라, 고른 유형과 저장되는 항목이 어긋났다.
	 */
	@GetMapping("/test_item_list")
	public AjaxResult testItemList(
		@RequestParam(value = "test_mast_id", required = false) Integer testMastId) {

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> items = (testMastId == null)
																				? this.materialInoutService.mioTestDefaultList()
																				: this.materialInoutService.prodTestListByTestMaster(testMastId);

		result.success = true;
		result.data = (items == null) ? new ArrayList<>() : items;
		return result;
	}

	@PostMapping("/lot_save")
	@Transactional
	public AjaxResult lotSave(
		@RequestBody MultiValueMap<String,Object> Q,
		@RequestParam("Material_id") String materialId,
		@RequestParam("StoreHouse_id") Integer storeHouseId,
		@RequestParam("mio_id") String mioId,
		/* Y = 이 입고건의 «기존 로트를 지우고» 새로 발번한다.
		   스캔 입고로 이미 로트가 깔린 건에 로트계산을 다시 돌리면
		   예전에는 그 위에 얹혀 중복 입고가 됐다(스캔 33×3 + 계산 99 = 198). */
		@RequestParam(value = "replace_yn", required = false, defaultValue = "N") String replaceYn,
		@RequestParam("spjangcd") String spjangcd,
		HttpServletRequest request,
		Authentication auth) {

		User user = (User)auth.getPrincipal();

		AjaxResult result = new AjaxResult();

		Timestamp today = new Timestamp(System.currentTimeMillis());

		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

		Integer mioPk = Integer.parseInt(mioId);

		/*
		 * ★ 재발번 — 지우기 «전» 에 쓰인 로트가 있는지 본다.
		 *
		 *   화면도 같은 검사를 하지만 그건 목록을 불러온 «그 시점» 기준이다.
		 *   그 사이 다른 사람이 로트를 소비했을 수 있고, 소비된 로트를 지우면
		 *   mat_lot_cons 가 매달릴 곳을 잃는다. 최종 판정은 여기서 한다.
		 */
		if ("Y".equalsIgnoreCase(replaceYn)) {
			List<Map<String, Object>> used = this.materialInoutService.findUsedLotsByMio(mioPk);
			if (used != null && !used.isEmpty()) {
				StringBuilder sb = new StringBuilder();
				for (Map<String, Object> u : used) {
					if (sb.length() > 0) sb.append(", ");
					sb.append(String.valueOf(u.get("LotNumber")));
				}
				result.success = false;
				result.message = "이미 사용된 로트가 있어 다시 발번할 수 없습니다 — " + sb;
				return result;
			}
			// 안 쓰인 로트만 남아 있다 — 지우고 새로 만든다
			this.materialInoutService.deleteLotsByMio(mioPk);
		}

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
				ml.setSourceDataPk(mioPk);
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

		/* ★ 부적합은 «검사 대상 여부와 무관하게» 재고에서 뺀다.
		     수입검사 대상이 아닌 품목도 검사를 열어 주기로 했으므로, 판정의 결과도
		     같아야 한다. 예전에는 부적합이어도 _status 를 안 건드려서,
		     검사 대상이 아닌 건(_status='a')은 화면에 「부적합」이라고 뜨면서
		     재고와 발주 잔량은 «정상 입고» 로 계속 세어졌다.

		   ★ 되돌릴 수 없는 건은 «쓰기 전에» 막는다.
		     확정 입고된 건은 이미 mat_lot 이 만들어져 재고가 올라가 있다.
		     그 로트가 이미 소비됐거나 일부 빠져나갔으면 여기서 되돌리는 순간
		     재고가 음수가 되거나 집계와 실제가 갈린다.
		     검사 결과를 저장한 «뒤» 에 알면 롤백해도 화면은 이미 저장됐다고
		     믿고 있으므로, 판정을 받기 전에 확인한다. */
		final boolean isReject = "부적합".equals(judgGrp);
		if (isReject && mioId != null) {
			Integer consumed = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				  FROM mat_lot_cons c
				  JOIN mat_lot l ON l.id = c."MaterialLot_id"
				 WHERE l."SourceTableName" = 'mat_inout'
				   AND l."SourceDataPk"    = ?
			""", Integer.class, mioId);

			Integer moved = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				  FROM mat_lot l
				 WHERE l."SourceTableName" = 'mat_inout'
				   AND l."SourceDataPk"    = ?
				   AND COALESCE(l._status, 'a') = 'a'
				   AND COALESCE(l."CurrentStock", 0) < COALESCE(l."InputQty", 0)
			""", Integer.class, mioId);

			if ((consumed != null && consumed > 0) || (moved != null && moved > 0)) {
				result.success = false;
				result.message = "이미 사용되었거나 수량이 줄어든 로트가 있어 부적합으로 되돌릴 수 없습니다.\n"
													 + "재고를 정리한 뒤 다시 판정하거나, 불량 등록으로 처리하세요.";
				return result;   // 아직 아무 것도 쓰지 않았다
			}
		}

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
		if (!isReject) {
			/* ★ «가입고분이 있을 때만» 확정 입고로 옮긴다.
			     예전에는 조건 없이 InputQty = PotentialInputQty 를 했다.
			     수입검사 대상이 아니어서 이미 확정 입고된 건(InputQty 에 수량이
			     들어 있고 PotentialInputQty 는 null)을 검사하면,
			     InputQty 가 null 로 덮여 «입고 수량이 통째로 사라진다».
			     검사 대상이 아닌 품목도 검사할 수 있게 열면서 드러난 구멍이다. */
			Float pot = mi.getPotentialInputQty();
			if (pot != null && pot > 0) {
				mi.setInputQty(pot);
				mi.setPotentialInputQty((float)0);
			}

			// 트리거 작동용 상태 변경
			mi.set_status("a");
		} else {
			/* ── 부적합 ──────────────────────────────────────────
			   «_status='t' 이면서 State='confirmed'» 가 이 시스템의 부적합 표시다.
			   재고 집계도, 발주 잔량 계산도 이 조합을 «세지 않는다» —
			   그래서 재입고 대상으로 다시 열린다.

			   ★ 수량 칸은 건드리지 않는다. 「무엇이 얼마나 왔는가」는 사실이고,
			     그것을 재고로 «세느냐» 만 _status 가 정한다. 수량을 지우면
			     구매처와 다툴 때 근거가 사라진다. */
			mi.set_status("t");

			/* ★ 확정 입고였던 건은 mat_lot 도 함께 되돌린다.
			     mat_inout 쪽만 빼면 트리거가 mat_in_house 를 줄이는데
			     mat_lot 은 재고를 든 채 남아, 집계와 실제가 갈린다
			     (기준문서 §6 「mat_lot 이 재고의 진실」).
			   ★ 행을 지우지 않고 재고만 0 으로 만든 뒤 표시를 남긴다.
			     지우면 그 로트로 무엇이 들어왔었는지 추적이 끊긴다.
			   ★ 위에서 소비·감소를 이미 막았으므로 여기서는 안전하다.
			   ★ 조건을 걸지 않는다. 가입고(검사대기)였던 건은 애초에 mat_lot 이
			     없어 0건이 바뀐다. 「확정 입고였는가」를 코드에서 판별하려 하면
			     엔티티 게터 이름에 기대게 되고, 그 판별이 틀리면 조용히 어긋난다. */
			jdbcTemplate.update("""
				UPDATE mat_lot
				   SET "CurrentStock" = 0
				     , _status        = 'd'
				     , "Description"  = COALESCE(NULLIF("Description",''), '') || ' / 입고검사 부적합'
				     , _modified      = now()
				 WHERE "SourceTableName" = 'mat_inout'
				   AND "SourceDataPk"    = ?
				   AND COALESCE(_status, 'a') = 'a'
			""", mioId);
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

		/* ★ 새로 만든 입고건을 «보낸 순서대로» 돌려준다.
		     화면은 이 중 test_yn='Y' 인 건만 골라 입고검사 모달을 띄운다.
		     예전에는 아무것도 안 돌려줘서 화면이 저장 전후 목록의 차집합으로
		     추측해야 했다. 조회기간이나 상태 문자열이 조금만 달라도
		     «검사 모달이 조용히 안 뜨는» 상태가 됐다. */
		List<Map<String, Object>> created = new ArrayList<>();

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

				// ★ 확정 입고 + 가입고 대기 를 함께 센다.
				//    InputQty 만 세면 가입고분이 0 으로 보여 같은 라인을 또 받게 된다.
				//    부적합(_status='t' & State='confirmed')은 재입고 대상이라 세지 않는다.
				double alreadyIn = jdbcTemplate.queryForObject("""
					SELECT COALESCE(SUM(
					         CASE WHEN COALESCE("_status", 'a') = 'a'
					              THEN COALESCE("InputQty", 0)
					              WHEN COALESCE("_status", 'a') = 't' AND "State" = 'waiting'
					              THEN COALESCE("PotentialInputQty", 0)
					              ELSE 0 END), 0)
					FROM mat_inout
					WHERE "SourceDataPk" = ? 
					  AND "SourceTableName" = 'balju'
					  AND "InOut" = 'in'
				""", Double.class, bal_pk);

				double baljuQty = balju.getSujuQty() != null ? balju.getSujuQty() : 0d;
				if (alreadyIn + qty > baljuQty) {
					result.success = false;
					result.message = "미입고수량 초과 (발주라인 " + bal_pk + " · 발주 " + (int) baljuQty
														 + " · 기입고+가입고 " + (int) alreadyIn + " · 요청 " + qty + ")";
					return result;
				}

				balju.setShipmentState(storeHouseIdStr);
				mi.setInputType("order_in");

				matInoutRepository.save(mi);
				bujuRepository.save(balju);

				Map<String, Object> row = new HashMap<>();
				row.put("mio_pk",   mi.getId());
				row.put("balju_id", bal_pk);
				// ★ 화면이 «보낸 줄» 과 이 건을 맞추는 열쇠. 목록에서 못 찾아도
				//    이것만 있으면 입고검사 모달을 채울 수 있다.
				row.put("Material_id", matPk);
				// Y = 수입검사 대상(material."InTestYN"). 가입고로 남아 검사를 기다린다.
				row.put("test_yn",  "Y".equals(testYn) ? "Y" : "N");
				created.add(row);

			} catch (Exception e) {
				result.success = false;
				result.message = "처리 중 오류 발생: " + e.getMessage();
				return result;
			}
		}
		result.success = true;
		result.data = created;

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

		// 2-1) 등록된 «원문» 바코드 — 표준을 못 알아본 라벨(자체 출력 Code128 등)
		//
		// ★ 이게 없으면 «등록해도 영원히 미등록» 이 된다.
		//   화면은 표준을 못 알아본 바코드를 barcode_register 에 raw 그대로 실어
		//   material_barcode."UdiDi" 에 넣는다. 그런데 여기서는 파싱 결과인 di 로만
		//   찾았고, UNKNOWN 표준은 di 가 빈 문자열이라 위 단계를 건너뛴다.
		//   결과: 스캔할 때마다 미등록으로 잡혀 등록 창이 다시 뜨고,
		//   barcode_register 는 품목당 1건이라 직전 등록을 지우고 새로 넣는다.
		//   material_barcode 에 같은 품목·같은 바코드의 'd' 행이 계속 쌓였다.
		//   카메라 경로는 등록 직후 같은 값을 다시 태우므로 모달이 무한히 열렸다.
		//
		// ★ 자사 품목코드(3)보다 «앞» 이다. 등록은 사람이 명시적으로 맺어 준 연결이라
		//   우연히 같은 문자열을 가진 품목코드보다 우선한다.
		if (data == null && raw != null && !raw.isBlank())
			data = materialInoutService.findMaterialByUdiDi(raw.trim(), spjangcd);

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

		/* ★ save_balju 와 동일하게, 새로 만든 입고건을 돌려준다.
		     화면(스캐너 입고)은 이 목록으로 입고검사 모달을 띄운다. */
		List<Map<String, Object>> created = new ArrayList<>();

		try {
			for (Map<String, Object> line : lines) {
				Integer balPk = CommonUtil.tryIntNull(line.get("balju_id"));   // = balju.id
				Integer matPk = CommonUtil.tryIntNull(line.get("Material_id"));
				if (balPk == null || matPk == null) continue;

				int qty = (int) Double.parseDouble(String.valueOf(line.get("InputQty")).replace(",", ""));
				if (qty <= 0) continue;

				Integer storeHouseId = CommonUtil.tryIntNull(line.get("StoreHouse_id"));
				if (storeHouseId == null) storeHouseId = storeHouseFallback;

				// ★ 과입고 차단 (기입고합계 + 가입고대기 + 이번수량 > 발주수량 이면 롤백)
				//   가입고(_status='t')는 InputQty 가 비어 있어 예전 쿼리로는 0 으로 보였다.
				//   부적합(_status='t' & State='confirmed')은 재입고 대상이라 세지 않는다.
				double alreadyIn = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(
                         CASE WHEN COALESCE("_status", 'a') = 'a'
                              THEN COALESCE("InputQty", 0)
                              WHEN COALESCE("_status", 'a') = 't' AND "State" = 'waiting'
                              THEN COALESCE("PotentialInputQty", 0)
                              ELSE 0 END), 0)
                FROM mat_inout
                WHERE "SourceDataPk" = ?
                  AND "SourceTableName" = 'balju'
                  AND "InOut" = 'in'
            """, Double.class, balPk);

				Balju balju = this.bujuRepository.getBujuById(balPk);
				double baljuQty = balju.getSujuQty() != null ? balju.getSujuQty() : 0d;
				if (alreadyIn + qty > baljuQty) {
					result.success = false;
					result.message = "미입고수량 초과 (발주라인 " + balPk + ", 발주 " + (int) baljuQty
														 + " / 기입고+가입고 " + (int) alreadyIn + " / 요청 " + qty + ")";
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

				Map<String, Object> row = new HashMap<>();
				row.put("mio_pk",   mi.getId());
				row.put("balju_id", balPk);
				// ★ 화면이 «보낸 줄» 과 이 건을 맞추는 열쇠 (save_balju 와 동일)
				row.put("Material_id", matPk);
				// Y = 수입검사 대상(material."InTestYN"). 가입고로 남아 검사를 기다린다.
				row.put("test_yn",  isWaiting ? "Y" : "N");
				created.add(row);
			}

			result.success = true;
			result.data = created;
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
		String jumunNumber = barcode.substring(2).trim(); // "PO" + JumunNumber

		List<Map<String, Object>> lines =
			this.materialInoutService.getBaljuLinesByJumunNumber(jumunNumber, spjangcd);

		/* ★ SqlRunner.getRows 는 SQL 오류 시 예외가 아니라 null 을 돌려준다.
		     그대로 내려보내면 화면에는 「미입고 품목이 없습니다」로 보여서
		     쿼리가 깨진 것인지 정말 다 받은 것인지 구분이 안 된다.
		     여기서 갈라 두면 최소한 다른 문구가 뜬다. */
		if (lines == null) {
			result.success = false;
			result.message = "발주 조회에 실패했습니다. 관리자에게 문의하세요. (발주번호 " + jumunNumber + ")";
			log.error("receiving_by_barcode 조회 실패 - jumunNumber={}, spjangcd={}", jumunNumber, spjangcd);
			return result;
		}

		Map<String, Object> data = new HashMap<>();
		data.put("JumunNumber", jumunNumber);
		data.put("baljuNo",     jumunNumber);
		data.put("lines",       lines);
		if (!lines.isEmpty()) {
			Map<String, Object> first = lines.get(0);
			// 화면이 중복 스캔 방어에 쓰는 키. 없으면 같은 발주를 두 번 담을 수 있다.
			data.put("bh_id",       first.get("bh_id"));
			data.put("companyName", first.get("CompanyName"));
			data.put("company_id",  first.get("Company_id"));
		}

		result.data = data;
		result.success = true;
		return result;
	}

	/* ══════════════════════════════════════════════════════════════
	   입고검사 사진
	   ────────────────────────────────────────────────────────────
	   ★ 사진은 «입고 건(mat_inout)» 에 붙인다. 검사결과(test_result)가 아니다.
	     - 미검사로 입고하면 검사결과가 없어 붙일 데가 없다
	     - 신규 검사에서 test_result_id 를 못 받으면 사진이 뜬다
	     mio_pk 는 발주 입고 표와 입고검사 팝업이 «항상» 들고 있다.

	   ★ 한 번에 한 장씩 받는다.
	     여러 장을 한 요청에 실으면 form-urlencoded 전체가 Tomcat
	     maxPostSize(기본 2MB)에 걸리는데, 예외가 아니라 파라미터가
	     조용히 잘려 «사진만 사라진» 것처럼 보인다.
	     보험: server.tomcat.max-http-form-post-size=20MB

	   ★ DB 에는 상대경로만 넣는다. 절대경로면 서버 이전 시 전부 깨진다.
	     읽을 때도 저장된 FilePath 를 그대로 쓴다 — 다시 계산하면
	     폴더 규칙이 바뀔 때 과거 파일을 못 찾는다(FilesController 버그).
	   ══════════════════════════════════════════════════════════════ */

	/* ★ file_upload_path 는 이미 «...\miracell» 까지를 가리킨다.
	     여기에 "miracell" 을 또 붙여서 폴더가 두 번 생겼다.
	         C:\Temp\mes21\miracell\miracell\mio_test   ← 잘못
	         C:\Temp\mes21\miracell\mio_test            ← 맞음
	   ★ 잘못된 경로에 «이미 저장된 사진» 이 있다. 규칙만 바꾸면 그 사진들이
	     통째로 안 열린다. 읽기·삭제는 옛 자리도 한 번 더 본다.
	     새로 저장되는 것은 항상 새 자리로 간다 — 시간이 지나면 옛 자리는 빈다. */
	private static final String PHOTO_SUB_DIR = "mio_test";
	private static final String PHOTO_SUB_DIR_LEGACY = "miracell" + File.separator + "mio_test";

	private File photoRoot() {
		String root = this.env.getProperty("file_upload_path");
		if (!StringUtils.hasText(root)) {
			throw new IllegalStateException("file_upload_path 설정이 없습니다.");
		}
		return new File(root, PHOTO_SUB_DIR);
	}

	/** 예전에 «miracell» 을 한 번 더 붙여 저장한 자리. 읽기·삭제에서만 쓴다. */
	private File photoRootLegacy() {
		String root = this.env.getProperty("file_upload_path");
		if (!StringUtils.hasText(root)) return null;
		return new File(root, PHOTO_SUB_DIR_LEGACY);
	}

	/** 상대경로로 실제 파일을 찾는다. 새 자리 → 옛 자리 순. 없으면 null. */
	private File resolvePhotoFile(String relPath) {
		if (!StringUtils.hasText(relPath)) return null;
		String rel = relPath.replace("/", File.separator);

		File f = new File(photoRoot(), rel);
		if (f.exists()) return f;

		File legacyRoot = photoRootLegacy();
		if (legacyRoot != null) {
			File lf = new File(legacyRoot, rel);
			if (lf.exists()) return lf;
		}
		return null;
	}

	private List<Map<String, Object>> mioPhotoList(Integer mioId) {
		try {
			return this.jdbcTemplate.queryForList(
				"select id as file_id, \"FileName\" as file_name, \"FileSize\" as file_size "
					+ "  from mio_test_file "
					+ " where \"MatInout_id\" = ? and \"_status\" = 'a' "
					+ " order by id ", mioId);
		} catch (Exception e) {
			// 테이블이 아직 없어도 검사 팝업 자체는 떠야 한다.
			log.warn("mio_test_file 조회 실패 - mioId={}, msg={}", mioId, e.getMessage());
			return new ArrayList<>();
		}
	}

	/** 사진 한 장 추가. 화면이 순차로 부른다. */
	@PostMapping("/test_photo_add")
	@Transactional
	public AjaxResult testPhotoAdd(
		@RequestParam("mio_id") Integer mioId,
		@RequestParam("photo") String photo,
		@RequestParam(value = "spjangcd", required = false, defaultValue = "ZZ") String spjangcd,
		Authentication auth) {

		AjaxResult result = new AjaxResult();

		if (mioId == null) {
			result.success = false;
			result.message = "입고 건이 지정되지 않았습니다.";
			return result;
		}
		if (!StringUtils.hasText(photo)) {
			result.success = false;
			result.message = "사진 데이터가 비어 있습니다.";
			return result;
		}

		User user = (User) auth.getPrincipal();

		// data:image/jpeg;base64,.... 형태를 풀어 확장자와 본문을 나눈다
		String ext = "jpg";
		String payload = photo;
		int comma = photo.indexOf(',');
		if (photo.startsWith("data:") && comma > 0) {
			String header = photo.substring(5, comma);
			payload = photo.substring(comma + 1);
			if (header.startsWith("image/")) {
				String t = header.substring(6);
				int semi = t.indexOf(';');
				if (semi > 0) t = t.substring(0, semi);
				if ("png".equals(t) || "webp".equals(t) || "gif".equals(t)) ext = t;
			}
		}

		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException e) {
			// 잘린 base64 — POST 크기 제한에 걸리면 이렇게 온다
			result.success = false;
			result.message = "사진 데이터가 손상되었습니다. POST 크기 제한(server.tomcat.max-http-form-post-size)을 확인하세요.";
			log.error("test_photo_add base64 decode 실패 - mioId={}, len={}", mioId, payload.length());
			return result;
		}

		File dir = new File(photoRoot(), String.valueOf(mioId));
		// mkdir() 은 중간 경로가 없으면 «조용히» 실패한다. mkdirs() 를 쓴다.
		if (!dir.exists() && !dir.mkdirs()) {
			result.success = false;
			result.message = "사진 폴더를 만들지 못했습니다.";
			log.error("test_photo_add 폴더 생성 실패 - {}", dir.getAbsolutePath());
			return result;
		}

		String fileName = "mio_" + mioId + "_" + System.currentTimeMillis() + "." + ext;
		File out = new File(dir, fileName);
		try {
			Files.write(out.toPath(), bytes);
		} catch (Exception e) {
			result.success = false;
			result.message = "사진을 저장하지 못했습니다.";
			log.error("test_photo_add 파일 쓰기 실패 - {}", out.getAbsolutePath(), e);
			return result;
		}

		String relPath = mioId + "/" + fileName;   // ★ 상대경로만 저장

		Integer fileId;
		try {
			fileId = this.jdbcTemplate.queryForObject(
				"insert into mio_test_file "
					+ " (\"MatInout_id\", \"FileName\", \"FilePath\", \"FileSize\", "
					+ "  \"_status\", \"_created\", \"_creater_id\", spjangcd) "
					+ " values (?, ?, ?, ?, 'a', now(), ?, ?) returning id ",
				Integer.class,
				mioId, fileName, relPath, (long) bytes.length, user.getId(), spjangcd);
		} catch (Exception e) {
			out.delete();   // 행이 안 남았으면 파일도 남기지 않는다
			result.success = false;
			result.message = "사진 정보를 저장하지 못했습니다.";
			log.error("test_photo_add insert 실패 - mioId={}", mioId, e);
			return result;
		}

		Map<String, Object> item = new HashMap<>();
		item.put("file_id", fileId);

		result.success = true;
		result.data = item;
		return result;
	}

	/** 사진 바이트를 그대로 내린다. 화면의 img src 가 직접 문다. */
	@GetMapping("/test_photo")
	public ResponseEntity<byte[]> testPhoto(@RequestParam("file_id") Integer fileId) {

		List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
			"select \"FilePath\", \"FileName\" from mio_test_file where id = ? ", fileId);

		if (rows == null || rows.isEmpty()) return ResponseEntity.notFound().build();

		String rel = (String) rows.get(0).get("FilePath");
		File f = resolvePhotoFile(rel);
		if (f == null) {
			log.warn("test_photo 파일 없음 - fileId={}, rel={}", fileId, rel);
			return ResponseEntity.notFound().build();
		}

		try {
			byte[] bytes = Files.readAllBytes(f.toPath());
			String name = f.getName().toLowerCase();
			String type = name.endsWith(".png") ? "image/png"
											: name.endsWith(".webp") ? "image/webp"
													: name.endsWith(".gif") ? "image/gif"
															: "image/jpeg";

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.parseMediaType(type));
			headers.setCacheControl("max-age=300");
			return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
		} catch (Exception e) {
			log.error("test_photo 읽기 실패 - fileId={}", fileId, e);
			return ResponseEntity.notFound().build();
		}
	}

	/**
	 * 그 입고 건의 사진을 «행과 파일 모두» 지운다.
	 * 입고 삭제에서도 부르고, 필요하면 다른 롤백 경로에서도 그대로 부른다.
	 * ★ 규칙을 한 곳에만 둔다. 복붙해 두면 한쪽만 고쳐 고아 파일이 남는다.
	 */
	private int deletePhotosOfMio(Integer mioId) {
		List<Map<String, Object>> rows;
		try {
			rows = this.jdbcTemplate.queryForList(
				"select id, \"FilePath\" from mio_test_file where \"MatInout_id\" = ? ", mioId);
		} catch (Exception e) {
			// 테이블이 아직 없는 환경도 있다. 입고 삭제 자체를 막을 이유는 없다.
			log.warn("mio_test_file 조회 실패 - mioId={}, msg={}", mioId, e.getMessage());
			return 0;
		}
		if (rows == null || rows.isEmpty()) return 0;

		this.jdbcTemplate.update("delete from mio_test_file where \"MatInout_id\" = ? ", mioId);

		for (Map<String, Object> r : rows) {
			String rel = (String) r.get("FilePath");
			if (rel == null) continue;
			try {
				File f = resolvePhotoFile(rel);
				if (f != null) f.delete();
			} catch (Exception e) {
				// 파일이 안 지워져도 행은 지운다. 목록에 안 보이는 것이 우선이다.
				log.warn("사진 파일 삭제 실패 - mioId={}, path={}, msg={}", mioId, rel, e.getMessage());
			}
		}

		// 빈 폴더는 남기지 않는다. 입고 건마다 폴더가 쌓이면 나중에 세기도 어렵다.
		for (File base : new File[]{photoRoot(), photoRootLegacy()}) {
			if (base == null) continue;
			try {
				File dir = new File(base, String.valueOf(mioId));
				String[] left = dir.list();
				if (left != null && left.length == 0) dir.delete();
			} catch (Exception ignore) { }
		}

		return rows.size();
	}

	/** 행과 파일을 «둘 다» 지운다. 한쪽만 지우면 고아가 쌓인다. */
	@PostMapping("/test_photo_delete")
	@Transactional
	public AjaxResult testPhotoDelete(@RequestParam("file_id") Integer fileId) {

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
			"select \"FilePath\" from mio_test_file where id = ? ", fileId);

		if (rows == null || rows.isEmpty()) {
			// 이미 없는 것을 지우라는 요청이다. 화면 목표(안 보이게)는 이미 달성됐다.
			result.success = true;
			return result;
		}

		String rel = (String) rows.get(0).get("FilePath");
		this.jdbcTemplate.update("delete from mio_test_file where id = ? ", fileId);

		try {
			File f = resolvePhotoFile(rel);
			if (f != null) f.delete();
		} catch (Exception e) {
			// 파일이 안 지워져도 행은 지운다. 목록에 안 보이는 것이 우선이다.
			log.warn("test_photo_delete 파일 삭제 실패 - fileId={}, msg={}", fileId, e.getMessage());
		}

		result.success = true;
		return result;
	}

	/** 그 입고 건의 사진 목록. */
	@GetMapping("/test_photo_list")
	public AjaxResult testPhotoList(@RequestParam("mio_id") Integer mioId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = this.mioPhotoList(mioId);
		return result;
	}

	/**
	 * 입고검사 내역 목록 (읽기 전용 조회 화면 rp_input_test_list 용).
	 *
	 * ★ 쿼리는 서비스에 둔다. 이 컨트롤러의 다른 조회들과 같은 자리다 —
	 *   한쪽만 컨트롤러에 SQL 을 두면 나중에 어디를 고칠지 갈린다.
	 */
	@GetMapping("/test_result_list")
	public AjaxResult testResultList(
		@RequestParam(value = "srchStartDt", required = false) String srchStartDt,
		@RequestParam(value = "srchEndDt", required = false) String srchEndDt,
		@RequestParam(value = "house_pk", required = false) String housePk,
		@RequestParam(value = "keyword", required = false) String keyword,
		@RequestParam(value = "factory_id", required = false) String factoryId) {

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> items = this.materialInoutService.getTestResultList(
			srchStartDt, srchEndDt, housePk, keyword, factoryId);

		/* ★ SqlRunner.getRows 는 오류 시 예외가 아니라 null 을 돌려준다.
		     그대로 내려보내면 화면에는 「조회된 자료가 없습니다」로 보여서
		     자료가 없는 것인지 쿼리가 깨진 것인지 구분이 안 된다. */
		if (items == null) {
			result.success = false;
			result.message = "입고검사 내역 조회에 실패했습니다. 관리자에게 문의하세요.";
			log.error("test_result_list 조회 실패 - start={}, end={}", srchStartDt, srchEndDt);
			return result;
		}

		result.success = true;
		result.data = items;
		return result;
	}
}