package mes.app.production;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import mes.app.production.service.DefectService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.config.Settings;
import mes.domain.services.CommonUtil;

/**
 * 부적합 등록 API.
 * 화면은 1공장/2공장 두 개지만 factory_id 파라미터만 다르고 엔드포인트는 하나다.
 *
 * 2026-08 — 원자재불량 추가.
 *   defect_source = work | material 로 갈린다.
 *   material 이면 공정·작지·작업자를 받지 않고, 대신 차감 창고를 화면이 고른다.
 *   엔드포인트는 늘리지 않았다 — 등록 경로가 둘이 되면 FIFO 차감이 두 벌이 된다.
 */
@RestController
@RequestMapping("/api/production/defect")
public class DefectController {

	@Autowired
	DefectService defectService;

	@Autowired
	Settings settings;

	/**
	 * 사진 저장 루트 = 공용 첨부와 **같은 프로퍼티**를 쓴다.
	 *
	 *   file_upload_path (= ${mes.project-path}, 예: C:\Temp\miracell)
	 *     + "defect"
	 *   → C:\Temp\miracell\defect   (리눅스면 /.../miracell/defect)
	 *
	 * ★ 전용 프로퍼티(file.upload.defect-path)를 따로 두지 않는다.
	 *   경로 규칙이 두 벌이면 서버를 옮길 때 한쪽만 고치고 다른 쪽이 조용히 깨진다.
	 *   멸균(STERIL_BATCH)과 같은 루트 아래 나란히 둔다.
	 *
	 * ★ DB 에는 상대경로만 저장한다 ("12/defect_12_1.jpg").
	 *   절대경로를 넣으면 서버 이전 시 기존 행이 전부 깨진다.
	 */
	private static final String DEFECT_DIR = "defect";

	private Path uploadRoot() {
		String root = settings.getProperty("file_upload_path");
		if (root == null || root.isBlank()) {
			// 프로퍼티가 비면 기동만 되고 저장이 조용히 엉뚱한 곳으로 간다 — 명시적으로 막는다
			throw new IllegalStateException(
					"file_upload_path 가 설정되지 않았습니다. application.properties 를 확인하세요.");
		}
		return Paths.get(root.trim(), DEFECT_DIR);
	}

	// -----------------------------------------------------------------
	// 조회
	// -----------------------------------------------------------------

	@GetMapping("/context")
	public AjaxResult context(@RequestParam int factory_id) {
		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getContext(factory_id);
		return r;
	}

	/**
	 * 자재 후보.
	 * defect_source=material 이면 공정이 없으므로 판별 축이 완전히 다르다 —
	 * 그 공장 자재 중 어느 창고든 재고가 있는 것을 내린다.
	 */
	@GetMapping("/material_list")
	public AjaxResult materialList(@RequestParam int factory_id,
								   @RequestParam(required = false) Integer process_id,
								   @RequestParam(required = false) String keyword,
								   @RequestParam(required = false, defaultValue = "false") boolean all,
								   @RequestParam(required = false, defaultValue = "work") String defect_source) {
		AjaxResult r = new AjaxResult();
		if ("material".equals(defect_source)) {
			r.data = this.defectService.getMaterialListForSource(factory_id, keyword);
		} else {
			if (process_id == null || process_id == 0) {
				r.success = false; r.message = "공정을 선택해주세요."; return r;
			}
			r.data = this.defectService.getMaterialList(factory_id, process_id, keyword, all);
		}
		return r;
	}

	/**
	 * 그 자재의 재고가 있는 창고 목록 (원자재불량 전용).
	 *
	 * ★ 창고를 서버가 계산해 주지 않는 이유 —
	 *   사오는 원자재 재고가 자재(3)·클린룸(5)·생산(17) 에 흩어져 있어
	 *   어느 규칙으로 고정해도 대부분의 품목이 "재고 부족" 으로 막힌다.
	 *   재고가 어디 있는지는 재고가 알고, 어느 자리에서 발견했는지는 사람이 안다.
	 */
	@GetMapping("/store_list")
	public AjaxResult storeList(@RequestParam int mat_id) {
		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getStoreList(mat_id);
		return r;
	}

	@GetMapping("/list")
	public AjaxResult list(@RequestParam int factory_id,
						   @RequestParam String date_from,
						   @RequestParam String date_to,
						   @RequestParam(required = false) Integer process_id,
						   @RequestParam(required = false) String defect_source,
						   HttpServletRequest request) {
		String spjangcd = CommonUtil.tryString(request.getSession().getAttribute("spjangcd"));
		if (spjangcd == null || spjangcd.isBlank()) spjangcd = "ZZ";

		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getList(factory_id, date_from, date_to,
				process_id, defect_source, spjangcd);
		return r;
	}

	/** 관련 작업지시 후보 (선택 항목) */
	@GetMapping("/wo_list")
	public AjaxResult woList(@RequestParam Integer process_id,
							 @RequestParam String defect_date) {
		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getWorkOrderList(process_id, defect_date);
		return r;
	}

	/**
	 * 사진 원본. 목록 카드의 썸네일과 확대보기가 같은 URL 을 쓴다.
	 * 경로를 그대로 내려주지 않고 idx 로만 접근시킨다 — 저장 경로 노출 방지.
	 */
	@GetMapping("/photo")
	public ResponseEntity<byte[]> photo(@RequestParam int defect_id,
										@RequestParam(defaultValue = "1") int idx) {
		List<Map<String, Object>> files = this.defectService.getFileList(defect_id);
		if (files.isEmpty() || idx < 1 || idx > files.size()) {
			return ResponseEntity.notFound().build();
		}
		String path = String.valueOf(files.get(idx - 1).get("file_path"));
		try {
			// 상대경로가 정상. 예전 행이 절대경로면 그대로 읽는다(이행 호환)
			Path f = Paths.get(path);
			if (!f.isAbsolute()) f = uploadRoot().resolve(path);
			byte[] bytes = Files.readAllBytes(f);
			MediaType type = path.toLowerCase().endsWith(".png")
					? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
			return ResponseEntity.ok().contentType(type)
					.cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
					.body(bytes);
		} catch (Exception e) {
			return ResponseEntity.notFound().build();
		}
	}

	@GetMapping("/detail")
	public AjaxResult detail(@RequestParam int defect_id) {
		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getDetail(defect_id);
		return r;
	}

	@GetMapping("/file_list")
	public AjaxResult fileList(@RequestParam int defect_id) {
		AjaxResult r = new AjaxResult();
		r.data = this.defectService.getFileList(defect_id);
		return r;
	}

	// -----------------------------------------------------------------
	// 등록 / 삭제
	// -----------------------------------------------------------------

	/**
	 * 등록.
	 *
	 * ★ form-urlencoded 로 받는다. AjaxUtil.postAsyncData 가 그렇게 보내기 때문 —
	 *   @RequestBody(JSON) 으로 두면 HttpMediaTypeNotSupportedException 이 난다.
	 *   사진은 photos 파라미터를 여러 번 반복해 base64(data URL) 로 받는다.
	 */
	@PostMapping("/regist")
	public AjaxResult regist(@RequestParam(required = false) Integer factory_id,
							 @RequestParam(required = false, defaultValue = "work") String defect_source,
							 @RequestParam(required = false) String  defect_date,
							 @RequestParam(required = false) Integer process_id,
							 @RequestParam(required = false) Integer mat_id,
							 @RequestParam(required = false) Integer defect_type_id,
							 @RequestParam(required = false) String  defect_type_etc,
							 @RequestParam(required = false) Double  qty,
							 @RequestParam(required = false) Integer src_store_id,
							 @RequestParam(required = false) String  discovery_stage,
							 @RequestParam(required = false) Integer job_res_id,
							 @RequestParam(required = false) Integer actor_id,
							 @RequestParam(required = false) String  description,
							 HttpServletRequest request) {
		AjaxResult r = new AjaxResult();
		User user = (User) request.getAttribute("user");
		String spjangcd = CommonUtil.tryString(request.getSession().getAttribute("spjangcd"));
		if (spjangcd == null || spjangcd.isBlank()) spjangcd = "ZZ";

		int factoryId = (factory_id == null) ? 0 : factory_id;
		if (factoryId != 1 && factoryId != 2) {
			// 화면이 항상 명시로 보낸다. 여기 걸리면 호출 쪽이 잘못된 것 —
			// 조용히 1공장으로 흘려보내면 엉뚱한 공장 재고가 차감된다.
			r.success = false; r.message = "공장 정보가 올바르지 않습니다."; return r;
		}
		boolean isMaterial = "material".equals(defect_source);

		// 작업불량만 공정을 요구한다. 원자재불량은 공정 자체가 없다.
		if (!isMaterial && (process_id == null || process_id == 0)) {
			r.success = false; r.message = "공정을 선택해주세요."; return r;
		}
		if (mat_id == null || mat_id == 0) {
			r.success = false; r.message = "불량 자재를 선택해주세요."; return r;
		}
		// 원자재불량은 창고가 필수. 서버가 실재고까지 재검증한다(DefectService).
		if (isMaterial && (src_store_id == null || src_store_id == 0)) {
			r.success = false; r.message = "차감할 창고를 선택해주세요."; return r;
		}

		String ddate = (defect_date == null || defect_date.isBlank())
				? LocalDate.now().toString() : defect_date.trim();
		String dtEtc = (defect_type_etc == null || defect_type_etc.isBlank())
				? null : defect_type_etc.trim();
		String desc  = (description == null || description.isBlank())
				? null : description.trim();
		Integer woId = (job_res_id == null || job_res_id == 0) ? null : job_res_id;
		Integer actor = (actor_id == null || actor_id == 0) ? null : actor_id;

		int defectId = this.defectService.regist(
				factoryId, isMaterial ? "material" : "work", ddate,
				isMaterial ? null : process_id, mat_id,
				defect_type_id, dtEtc, qty == null ? 0 : qty,
				src_store_id, discovery_stage,
				woId, actor, desc, user, spjangcd);

		// ★ 사진은 여기서 받지 않는다.
		//   base64 를 등록 폼에 실어 보내면 사진 한 장이 수백 KB 라
		//   form-urlencoded 전체가 Tomcat 의 maxPostSize(기본 2MB)에 걸린다.
		//   걸리면 파라미터가 **조용히 잘려** 사진만 사라진다(에러도 안 난다).
		//   → 등록 후 /photo_add 로 한 장씩 따로 올린다.

		Map<String, Object> data = new HashMap<>();
		data.put("defect_id", defectId);
		r.data = data;
		return r;
	}

	/**
	 * 사진 1장 추가. 등록·수정 양쪽에서 쓴다.
	 * 한 요청에 한 장만 실어 POST 크기 제한을 피한다.
	 */
	@PostMapping("/photo_add")
	public AjaxResult photoAdd(@RequestParam Integer defect_id,
							   @RequestParam String photo,
							   HttpServletRequest request) {
		AjaxResult r = new AjaxResult();
		User user = (User) request.getAttribute("user");
		String spjangcd = CommonUtil.tryString(request.getSession().getAttribute("spjangcd"));
		if (spjangcd == null || spjangcd.isBlank()) spjangcd = "ZZ";

		if (defect_id == null || defect_id == 0) {
			r.success = false; r.message = "대상이 없습니다."; return r;
		}
		if (photo == null || photo.isBlank()) {
			r.success = false; r.message = "사진 데이터가 비어 있습니다."; return r;
		}

		try {
			// 파일명 충돌 방지 — 삭제 후 재추가해도 겹치지 않게 시각을 섞는다
			int idx = (int) (System.currentTimeMillis() % 100000);
			savePhoto(defect_id, idx, photo, user, spjangcd);
		} catch (Exception e) {
			e.printStackTrace();
			r.success = false;
			r.message = "사진 저장 실패: " + e.getMessage();
		}
		return r;
	}

	/** 사진 1장 삭제 (메타 + 파일 실체) */
	@PostMapping("/photo_delete")
	public AjaxResult photoDelete(@RequestParam Integer file_id) {
		AjaxResult r = new AjaxResult();
		if (file_id == null || file_id == 0) {
			r.success = false; r.message = "대상이 없습니다."; return r;
		}
		Map<String, Object> row = this.defectService.deleteFile(file_id);
		try {
			String path = String.valueOf(row.get("file_path"));
			Path f = Paths.get(path);
			if (!f.isAbsolute()) f = uploadRoot().resolve(path);
			Files.deleteIfExists(f);
		} catch (Exception e) {
			// 메타는 지웠으니 목록에서는 사라진다. 파일만 남는 건 치명적이지 않다.
			e.printStackTrace();
		}
		return r;
	}

	/**
	 * 내역 수정 (재고와 무관한 항목만).
	 * 수량·자재를 바꾸려면 삭제 후 재등록 — 차감 롤백 경로를 하나로 유지하기 위함.
	 */
	@PostMapping("/update")
	public AjaxResult update(@RequestParam Integer defect_id,
							 @RequestParam(required = false) Integer defect_type_id,
							 @RequestParam(required = false) String  defect_type_etc,
							 @RequestParam(required = false) String  defect_date,
							 @RequestParam(required = false) Integer job_res_id,
							 @RequestParam(required = false) Integer actor_id,
							 @RequestParam(required = false) String  description,
							 @RequestParam(required = false) String  discovery_stage,
							 HttpServletRequest request) {
		AjaxResult r = new AjaxResult();
		User user = (User) request.getAttribute("user");
		if (defect_id == null || defect_id == 0) {
			r.success = false; r.message = "대상이 없습니다."; return r;
		}
		String dtEtc = (defect_type_etc == null || defect_type_etc.isBlank())
				? null : defect_type_etc.trim();
		String desc  = (description == null || description.isBlank())
				? null : description.trim();
		String ddate = (defect_date == null || defect_date.isBlank())
				? LocalDate.now().toString() : defect_date.trim();
		Integer woId  = (job_res_id == null || job_res_id == 0) ? null : job_res_id;
		Integer actor = (actor_id == null || actor_id == 0) ? null : actor_id;

		this.defectService.update(defect_id, defect_type_id, dtEtc, ddate,
				woId, actor, desc, discovery_stage, user);
		return r;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestParam(required = false) Integer defect_id) {
		AjaxResult r = new AjaxResult();
		if (defect_id == null || defect_id == 0) {
			r.success = false; r.message = "대상이 없습니다."; return r;
		}
		this.defectService.delete(defect_id);
		return r;
	}

	// -----------------------------------------------------------------

	private void savePhoto(int defectId, int idx, String dataUrl, User user, String spjangcd)
			throws Exception {
		int comma = dataUrl.indexOf(',');
		String meta = comma > 0 ? dataUrl.substring(0, comma) : "";
		String b64  = comma > 0 ? dataUrl.substring(comma + 1) : dataUrl;
		String ext  = meta.contains("png") ? "png" : "jpg";

		byte[] bytes = Base64.getDecoder().decode(b64);
		Path dir = uploadRoot().resolve(String.valueOf(defectId));
		Files.createDirectories(dir);
		// 저장이 안 될 때 어디까지 왔는지 보이게 남긴다 (경로 권한 문제가 흔하다)
		System.out.println("[defect] 사진 저장 → " + dir + " (" + bytes.length + " bytes)");
		String fileName = "defect_%d_%d.%s".formatted(defectId, idx, ext);
		Files.write(dir.resolve(fileName), bytes);

		// DB 에는 루트를 뺀 상대경로만 — OS/서버가 바뀌어도 살아남는다
		String rel = defectId + "/" + fileName;
		this.defectService.addFile(defectId, fileName, rel, bytes.length, user, spjangcd);
	}

	/** SQL 오류까지 AjaxResult 로 — 안 잡으면 AjaxUtil 이 네이티브 alert 를 띄운다 */
	@ExceptionHandler(Exception.class)
	public AjaxResult onError(Exception e) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		r.message = (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
				? e.getMessage()
				: "처리 중 오류가 발생했습니다.";
		if (!(e instanceof IllegalArgumentException || e instanceof IllegalStateException)) {
			e.printStackTrace();
		}
		return r;
	}
}