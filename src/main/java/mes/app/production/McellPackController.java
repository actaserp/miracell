package mes.app.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.production.service.McellPackService;
import mes.app.production.service.ProductionCreateService.BomInput;
import mes.config.Settings;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * M-CELL 포장 (2공장, mc03 / 워크센터 56) 컨트롤러.
 *
 * 화면 흐름
 *   A 포장 큐 → B 작업 목록(작업자·설비 1조) → C 박스 연속 포장
 *   C : 합격 유닛 선택 → 포장자재 투입 → 박스 라벨 스캔 → 포장 완료 → 다음 박스
 *
 * ★ 카드가 두 종류다. 그래서 조회 계열이 job_res_id / pack_mat_id 를 둘 다 받는다.
 *     job_res_id  포장 작지 카드
 *     pack_mat_id 「작지 없음」 카드 (수리·반품 등 라우팅 밖 유닛)
 *   화면은 둘 중 하나만 실어 보낸다. 서버는 없는 쪽을 null 로 받는다.
 *   두 파라미터를 모두 required=false 로 두는 것이 핵심 — 하나라도 required 면
 *   반대편 카드에서 400 이 나고, AjaxUtil 이 「페이지를 찾을 수 없습니다」 를 띄운다.
 *
 * ★ AjaxUtil 은 form-urlencoded 로 보낸다. @RequestBody(JSON)를 쓰면 415.
 *   그래서 모든 파라미터가 @RequestParam 이고, 자재 목록만 bom_json 문자열로 받는다.
 */
@RestController
@RequestMapping("/api/production/mcell/pack")
public class McellPackController {

	private static final Logger log = LoggerFactory.getLogger(McellPackController.class);

	@Autowired private McellPackService mcellPackService;
	@Autowired private Settings settings;

	private final ObjectMapper om = new ObjectMapper();

	// ── 포장 자재 사진 (공용 attach_file) ────────────────
	/**
	 * ★ 'mat_produce' 는 실제 테이블명이고, DataPk 에 들어가는 값도 mat_produce.id 다.
	 *   다만 이 테이블은 조립·수리·포장(1·2공장)이 전부 쓴다 —
	 *   그래서 «어느 첨부인가» 는 AttachName 이 가른다. TableName 만으로 조회하면
	 *   다른 공정 첨부까지 딸려 온다. 반드시 둘을 함께 걸 것.
	 */
	public static final String ATT_TABLE  = "mat_produce";
	public static final String ATT_NAME   = "PACK_MCELL_MAT_PHOTO";

	/**
	 * 디스크 폴더는 TableName 과 **일부러 다르다.**
	 * mat_produce 로 폴더를 잡으면 조립·수리 첨부가 한 폴더에 쌓여
	 * 백업·정리 때 포장 사진만 떼어낼 수 없다. 부적합도 테이블은 defect_regist_file,
	 * 폴더는 defect 로 갈라져 있다(DefectController.DEFECT_DIR).
	 */
	private static final String PACK_DIR = "mcell_pack";

	/**
	 * 사진 저장 루트 = 공용 첨부와 **같은 프로퍼티**를 쓴다 (부적합과 동일).
	 *   file_upload_path (예: C:\Temp\miracell) + "mcell_pack"
	 * 전용 프로퍼티를 따로 두면 규칙이 두 벌이 되어 서버 이전 시 한쪽만 고치게 된다.
	 */
	private Path uploadRoot() {
		String root = settings.getProperty("file_upload_path");
		if (root == null || root.isBlank()) {
			// 비어 있으면 기동은 되고 저장만 엉뚱한 곳으로 간다 — 명시적으로 막는다
			throw new IllegalStateException(
				"file_upload_path 가 설정되지 않았습니다. application.properties 를 확인하세요.");
		}
		return Paths.get(root.trim(), PACK_DIR);
	}

	// ── 조회 ─────────────────────────────────────────────

	@GetMapping("/context")
	public AjaxResult context(
		@RequestParam(value = "process_code", defaultValue = "mc03") String processCode,
		@RequestParam(value = "factory_id", defaultValue = "2") Integer factoryId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getContext(processCode, factoryId);
		return r;
	}

	/** A화면 — 포장 큐 (작지 카드 + 「작지 없음」 카드) */
	@GetMapping("/wo_queue")
	public AjaxResult woQueue(
		@RequestParam(value = "process_id", required = false) Integer processId,
		@RequestParam(value = "date_from", required = false) String dateFrom,
		@RequestParam(value = "date_to", required = false) String dateTo,
		@RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getWoQueue(processId, spjangcd, dateFrom, dateTo);
		return r;
	}

	/** B·C화면 — 포장 대기 유닛 (검사 합격 · 검사완료창고 · 이 카드 소속) */
	@GetMapping("/ready_units")
	public AjaxResult readyUnits(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getReadyUnits(jobResId, packMatId);
		return r;
	}

	/** B화면 — 포장 완료 목록 (작업 세션은 이 목록에서 파생시킨다) */
	@GetMapping("/packed_list")
	public AjaxResult packedList(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getPackedList(jobResId, packMatId);
		return r;
	}

	/** C화면 — 포장 자재 (완제품 BOM − 유닛품목). store_id 로 소스창고를 함께 내린다 */
	@GetMapping("/pack_materials")
	public AjaxResult packMaterials(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getPackMaterials(jobResId, packMatId);
		return r;
	}

	/** 이 카드가 포장 대상으로 삼는 유닛 품목 */
	@GetMapping("/unit_materials")
	public AjaxResult unitMaterials(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getUnitMaterials(jobResId, packMatId);
		return r;
	}

	/**
	 * 박스 라벨 중복 검사.
	 * 라벨이 필수라 한 번 쓴 값을 다시 못 쓴다. 화면 목록만으로는 다른 작지의 라벨을 못 막는다.
	 * GS1-128 파싱((10) 추출)은 화면에서 하고, 여기엔 라벨 문자열만 온다.
	 */
	@GetMapping("/label_check")
	public AjaxResult labelCheck(
		@RequestParam("key") String key,
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.checkLabel(key);
		return r;
	}

	/**
	 * 로트 직접 조회 (사내 로트번호 또는 외부 라벨).
	 * 화면 흐름이 「유닛 선택 → 라벨 스캔」으로 바뀌어 진입용으로는 쓰지 않지만,
	 * 로트를 찍어 상태를 확인하고 싶을 때를 위해 남겨 둔다.
	 */
	@GetMapping("/lot_search")
	public AjaxResult lotSearch(
		@RequestParam("key") String key,
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.searchUnitLot(jobResId, packMatId, key);
		return r;
	}

	@GetMapping("/workers")
	public AjaxResult workers() {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getWorkers();
		return r;
	}

	/** 포장 설비 목록 (워크센터 + 설비그룹 기준) */
	@GetMapping("/equipments")
	public AjaxResult equipments(
		@RequestParam(value = "workcenter_id", required = false) Integer workCenterId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getEquipments(workCenterId);
		return r;
	}

	/**
	 * 「＋ 자재」 후보 — BOM 에 없는 자재를 작업자가 직접 넣을 때 쓴다.
	 *
	 * ★ 후보 출처는 getPackMaterials 의 폴백과 **같은 규칙**이다 (생산창고 17 실재고).
	 *   여기만 다른 창고를 열어 주면 완료 단계에서 「소스창고가 17이 아니다」로 막혀
	 *   작업자는 «목록에 있는데 왜 안 되지» 상태가 된다.
	 * ★ 이미 BOM 목록에 있는 자재는 exclude_ids 로 빼고 내린다 — 같은 자재가 두 줄이 되면
	 *   수량이 갈려 어느 쪽이 반영되는지 알 수 없다.
	 */
	@GetMapping("/mat_candidates")
	public AjaxResult matCandidates(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId,
		@RequestParam(value = "keyword", required = false) String keyword,
		@RequestParam(value = "exclude_ids", required = false) String excludeIds) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getMatCandidates(jobResId, packMatId, keyword, excludeIds);
		return r;
	}

	// ── 쓰기 ─────────────────────────────────────────────

	/**
	 * 박스 1개 포장 = 유닛 1대 → 완제품 로트 → 제품창고(4).
	 * job_res_id 가 없으면 pack_mat_id 로 포장 작지를 자동 발행한다.
	 * maker_lot_no(박스 라벨)는 필수 — 서비스에서 막는다.
	 */
	@PostMapping("/pack_unit")
	@Transactional
	public AjaxResult packUnit(
		@RequestParam(value = "job_res_id", required = false) Integer jobResId,
		@RequestParam(value = "pack_mat_id", required = false) Integer packMatId,
		@RequestParam("mat_lot_id") Integer matLotId,
		@RequestParam(value = "maker_lot_no", required = false) String makerLotNo,
		@RequestParam(value = "actor_id", required = false) Integer actorId,
		@RequestParam(value = "member_ids", required = false) String memberIds,
		@RequestParam(value = "equipment_id", required = false) Integer equipmentId,
		@RequestParam(value = "start_time", required = false) String startTime,
		@RequestParam(value = "end_time", required = false) String endTime,
		@RequestParam(value = "bom_json", required = false) String bomJson,
		// ★ 스캔 원문. 화면이 GS1 에서 (10)만 뽑아 maker_lot_no 로 보내므로
		//   원문은 따로 받아 pack_label."RawData" 에 남긴다. 없으면 라벨값을 그대로 쓴다.
		@RequestParam(value = "label_raw", required = false) String labelRaw,
		@RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
		Authentication auth) {
		return this.mcellPackService.packUnit(jobResId, packMatId, matLotId, makerLotNo,
			actorId, memberIds, equipmentId, startTime, endTime,
			parseBom(bomJson), labelRaw, spjangcd, (User) auth.getPrincipal());
	}

	/** 「작업 추가」 = 포장 작업 시작. 작지를 working 으로 올린다 */
	@PostMapping("/work_start")
	@Transactional
	public AjaxResult workStart(
		@RequestParam("job_res_id") Integer jobResId,
		@RequestParam(value = "actor_id", required = false) Integer actorId,
		@RequestParam(value = "member_ids", required = false) String memberIds,
		@RequestParam(value = "start_time", required = false) String startTime,
		Authentication auth) {
		return this.mcellPackService.startWork(jobResId, actorId, memberIds, startTime,
			(User) auth.getPrincipal());
	}

	/** 「작업 종료」 — 차수가 하나도 없으면 작지를 ordered 로 되돌린다 */
	@PostMapping("/work_end")
	@Transactional
	public AjaxResult workEnd(
		@RequestParam("job_res_id") Integer jobResId,
		Authentication auth) {
		return this.mcellPackService.endWork(jobResId, (User) auth.getPrincipal());
	}

	/**
	 * 포장 취소 — 차수 롤백 + 유닛 packed → pass.
	 *
	 * ★ 첨부 사진도 같이 정리한다. 안 지우면 mat_produce 행이 State='wait', _status='d' 로
	 *   남고 사진만 살아 있어, 같은 유닛을 다시 포장했을 때 옛 사진이 새 박스에 안 붙고
	 *   디스크에만 고아로 남는다.
	 * ★ 파일 경로는 attach_file 행이 지워지기 «전에» 읽어야 한다.
	 */
	@PostMapping("/pack_cancel")
	@Transactional
	public AjaxResult packCancel(
		@RequestParam("mat_produce_id") Integer mpId,
		Authentication auth) {
		List<Map<String, Object>> photos = this.mcellPackService.getPhotoList(mpId);
		AjaxResult r = this.mcellPackService.cancelPack(mpId, (User) auth.getPrincipal());
		if (r != null && r.success) {
			this.mcellPackService.deletePhotosOf(mpId);
			for (Map<String, Object> f : photos) removeFileQuietly(f);
		}
		return r;
	}

	/** 시작/완료 시각 수정 */
	@PostMapping("/pack_time")
	@Transactional
	public AjaxResult packTime(
		@RequestParam("mat_produce_id") Integer mpId,
		@RequestParam("which") String which,          // start | end
		@RequestParam("value") String value,          // 'yyyy-MM-dd HH:mm'
		Authentication auth) {
		return this.mcellPackService.setPackTime(mpId, which, value, (User) auth.getPrincipal());
	}

	// ── 포장 자재 사진 ───────────────────────────────────

	/**
	 * 사진 목록. 화면은 여기서 받은 **file_id 로만** 이미지를 요청한다.
	 *
	 * ★ 부적합(/photo?defect_id=&idx=N)처럼 «목록의 몇 번째» 로 접근하면
	 *   중간 한 장을 지웠을 때 뒤 사진이 앞으로 밀려 같은 URL 이 다른 사진을 가리킨다.
	 *   브라우저 캐시까지 얹히면 지운 사진이 계속 보인다.
	 *   attach_file.id 는 serial PK 라 한 번 정해지면 안 변한다 — 그걸 키로 쓴다.
	 */
	@GetMapping("/photo_list")
	public AjaxResult photoList(@RequestParam("mat_produce_id") Integer mpId) {
		AjaxResult r = new AjaxResult();
		r.data = this.mcellPackService.getPhotoList(mpId);
		return r;
	}

	/** 사진 원본. 저장 경로는 내려주지 않는다 — file_id 로만 접근시킨다. */
	@GetMapping("/photo")
	public ResponseEntity<byte[]> photo(@RequestParam("file_id") Integer fileId) {
		Map<String, Object> row = this.mcellPackService.getPhoto(fileId);
		if (row == null) return ResponseEntity.notFound().build();
		try {
			String path = String.valueOf(row.get("file_path"));
			// 상대경로가 정상. 혹시 절대경로로 들어온 행이 있으면 그대로 읽는다(이행 호환)
			Path f = Paths.get(path);
			if (!f.isAbsolute()) f = uploadRoot().resolve(path);
			byte[] bytes = Files.readAllBytes(f);
			MediaType type = path.toLowerCase().endsWith(".png")
												 ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
			// file_id 가 불변이라 캐시가 안전하다(부적합처럼 t= 로 깨뜨릴 필요가 없다)
			return ResponseEntity.ok().contentType(type)
							 .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
							 .body(bytes);
		} catch (Exception e) {
			return ResponseEntity.notFound().build();
		}
	}

	/**
	 * 사진 1장 추가.
	 *
	 * ★ 한 요청에 한 장만 싣는다. base64 여러 장을 form-urlencoded 로 함께 보내면
	 *   Tomcat maxPostSize(기본 2MB)에 걸리는데, 예외가 아니라 **파라미터가 조용히 잘려**
	 *   사진만 사라진다(부적합 §7 과 같은 이유).
	 *   그래서 화면도 포장 완료(/pack_unit) 로 mat_produce_id 를 받은 뒤 한 장씩 올린다.
	 */
	@PostMapping("/photo_add")
	@Transactional
	public AjaxResult photoAdd(@RequestParam("mat_produce_id") Integer mpId,
														 @RequestParam("photo") String photo,
														 @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
														 Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (mpId == null || mpId == 0) {
			r.success = false; r.message = "대상 포장 건이 없습니다."; return r;
		}
		if (photo == null || photo.isBlank()) {
			r.success = false; r.message = "사진 데이터가 비어 있습니다."; return r;
		}
		try {
			r.data = savePhoto(mpId, photo, (User) auth.getPrincipal(), spjangcd);
		} catch (Exception e) {
			log.error("[mcell/pack] 사진 저장 실패 mpId={}", mpId, e);
			r.success = false;
			r.message = "사진 저장 실패: " + e.getMessage();
		}
		return r;
	}

	/** 사진 1장 삭제 (attach_file 행 + 파일 실체) */
	@PostMapping("/photo_delete")
	@Transactional
	public AjaxResult photoDelete(@RequestParam("file_id") Integer fileId) {
		AjaxResult r = new AjaxResult();
		if (fileId == null || fileId == 0) {
			r.success = false; r.message = "대상이 없습니다."; return r;
		}
		Map<String, Object> row = this.mcellPackService.deletePhoto(fileId);
		removeFileQuietly(row);
		r.message = "사진을 삭제했습니다.";
		return r;
	}

	// ── 유틸 : 사진 ──────────────────────────────────────

	/**
	 * base64 data URL → 디스크 + attach_file 1행.
	 *
	 * 파일이 겹치지 않게 하는 층이 셋이다. 하나라도 빠지면 조용히 덮어쓴다.
	 *   ① 폴더 mcell_pack/   다른 모듈(defect·STERIL_BATCH)과 분리
	 *   ② 하위 폴더 <mpId>/  박스끼리 분리
	 *   ③ 파일명 FileIndex + millis
	 *      FileIndex 만 쓰면 삭제 후 재추가 때 번호가 재사용돼 겹친다.
	 *      millis 만 쓰면 부적합처럼 %100000 순환 문제가 생긴다 — 둘을 같이 쓴다.
	 */
	private Map<String, Object> savePhoto(int mpId, String dataUrl, User user, String spjangcd)
		throws Exception {
		int comma = dataUrl.indexOf(',');
		String meta = comma > 0 ? dataUrl.substring(0, comma) : "";
		String b64  = comma > 0 ? dataUrl.substring(comma + 1) : dataUrl;
		String ext  = meta.contains("png") ? "png" : "jpg";

		byte[] bytes = Base64.getDecoder().decode(b64);
		int fileIndex = this.mcellPackService.nextPhotoIndex(mpId);

		Path dir = uploadRoot().resolve(String.valueOf(mpId));
		Files.createDirectories(dir);          // mkdir 이 아니라 mkdirs — 중간 경로가 없으면 조용히 실패한다
		String fileName = "pack_%d_%d_%d.%s".formatted(mpId, fileIndex, System.currentTimeMillis(), ext);
		Files.write(dir.resolve(fileName), bytes);

		// DB 에는 루트를 뺀 상대경로만 — 서버를 옮겨도 과거 행이 살아남는다
		String rel = mpId + "/" + fileName;
		Integer fileId = this.mcellPackService.addPhoto(
			mpId, fileIndex, fileName, rel, ext, bytes.length, user, spjangcd);
		return Map.of("file_id", fileId, "file_index", fileIndex, "file_name", fileName);
	}

	/** 파일 실체 삭제. 실패해도 목록에서는 이미 사라졌으므로 치명적이지 않다. */
	private void removeFileQuietly(Map<String, Object> row) {
		if (row == null) return;
		try {
			String path = String.valueOf(row.get("file_path"));
			if (path == null || path.isBlank() || "null".equals(path)) return;
			Path f = Paths.get(path);
			if (!f.isAbsolute()) f = uploadRoot().resolve(path);
			Files.deleteIfExists(f);
		} catch (Exception e) {
			log.warn("[mcell/pack] 사진 파일 삭제 실패 (메타는 삭제됨)", e);
		}
	}

	// ── 예외 처리 ────────────────────────────────────────
	/**
	 * SQL 오류까지 전부 AjaxResult 로 변환한다(수리 §5.9 와 동일).
	 * 안 잡으면 스프링이 HTML 에러 페이지를 내리고 AjaxUtil 이
	 * 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
	 */
	@ExceptionHandler(Exception.class)
	public AjaxResult handleError(Exception e) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		boolean business = (e instanceof IllegalStateException) || (e instanceof IllegalArgumentException);
		if (!business) log.error("[mcell/pack] 처리 오류", e);
		r.message = (e.getMessage() == null || e.getMessage().isBlank())
									? "처리 중 오류가 발생했습니다." : e.getMessage();
		return r;
	}

	// ── 유틸 ─────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private List<BomInput> parseBom(String json) {
		if (json == null || json.isBlank()) return null;   // null = BOM 기본값 사용
		List<BomInput> list = new ArrayList<>();
		try {
			List<Map<String, Object>> arr = om.readValue(json, List.class);
			for (Map<String, Object> m : arr) {
				Object mid = m.get("matId");
				Object q = m.get("qty");
				if (mid == null) continue;
				BomInput bi = new BomInput();
				bi.matId = ((Number) mid).intValue();
				bi.qty = (q == null) ? 0f : Float.parseFloat(String.valueOf(q));
				if (bi.qty > 0) list.add(bi);
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("포장자재(bom_json) 형식 오류: " + e.getMessage());
		}
		return list;
	}
}