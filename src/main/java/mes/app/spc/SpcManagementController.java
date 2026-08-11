package mes.app.spc;

import lombok.RequiredArgsConstructor;
import mes.app.spc.Service.SpcManagementService;
import mes.domain.entity.Tb_spc_std01;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/spc-management")
@RequiredArgsConstructor
public class SpcManagementController {

    private final SpcManagementService service;

    /** ✅ 목록 조회 */
    @GetMapping("/list")
    public AjaxResult list(
            @RequestParam(value ="srchProcess", required = false) String srchProcess,
            @RequestParam(value ="srchMeasure", required = false) String srchMeasure
    ) {
        AjaxResult result = new AjaxResult();
        try {
            List<Map<String, Object>> items = this.service.getList(srchProcess, srchMeasure);
            result.success = true;
            result.data = items;
        } catch (Exception e) {
            result.success = false;
            result.message = "목록 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** ✅ 단건 조회 */
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Integer id) {
        AjaxResult result = new AjaxResult();
        Optional<Tb_spc_std01> data = service.findById(id);
        if (data.isPresent()) {
            result.success = true;
            result.data = data.get();
        } else {
            result.success = false;
            result.message = "해당 데이터가 존재하지 않습니다.";
        }
        return result;
    }

    /** ✅ 등록 / 수정 */
    @PostMapping("/save")
    public AjaxResult save(@RequestParam Map<String, String> params,
                           Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        OffsetDateTime now = OffsetDateTime.now();

        try {
            Tb_spc_std01 entity;

            // ✅ 수정 여부 확인
            //   1) id 가 오면 id 로 조회
            //   2) id 가 없으면 유니크키(process_code+measure_code+item_code)로 기존 행을 찾는다.
            //      화면에서 id 가 누락돼도 신규 INSERT 로 처리해 유니크 제약을 위반하는 것을 막는다.
            if (params.get("id") != null && !params.get("id").isEmpty()) {
                entity = service.findById(Integer.valueOf(params.get("id")))
                        .orElse(new Tb_spc_std01());
            } else {
                entity = service.findByKey(
                            params.get("process_code"),
                            params.get("measure_code"),
                            params.get("item_code"))
                        .orElse(new Tb_spc_std01());
            }

            // ✅ form 데이터 매핑
            entity.setRecipe(params.get("recipe"));
            entity.setItemCode(params.get("item_code"));
            entity.setItemName(params.get("item_name"));
            entity.setProcessCode(params.get("process_code"));
            entity.setMeasureCode(params.get("measure_code"));
            entity.setUnitCode(params.get("unit_code"));

            // ✅ select의 표시 텍스트(name) 값도 함께 저장 (option text 전송 시)
            entity.setProcessName(params.getOrDefault("process_name", ""));
            entity.setMeasureName(params.getOrDefault("measure_name", ""));
            entity.setUnitName(params.getOrDefault("unit_name", ""));

            // ✅ 수치 데이터 매핑
            entity.setTargetValue(parseBigDecimal(params.get("target_value")));
            entity.setUsl(parseBigDecimal(params.get("usl")));
            entity.setLsl(parseBigDecimal(params.get("lsl")));
            entity.setUcl(parseBigDecimal(params.get("ucl")));
            entity.setLcl(parseBigDecimal(params.get("lcl")));
            entity.setSampleSize(parseInt(params.get("sample_size"), 1));
            entity.setMeasureCycleValue(parseInt(params.get("measure_cycle_value"), 1));
            entity.setMeasureCycleUnit(params.getOrDefault("measure_cycle_unit", "MIN"));
            entity.setUseYn(params.getOrDefault("use_yn", "Y"));

            // ✅ 생성/수정자 및 시간 정보 세팅
            if (entity.getId() == null) {
                entity.setCreatedAt(now);
                entity.setCreatedBy(user.getUsername());
                entity.setUpdatedAt(now);
                entity.setUpdatedBy(user.getUsername());
            } else {
                entity.setUpdatedAt(now);
                entity.setUpdatedBy(user.getUsername());
            }

            // ✅ 저장
            Tb_spc_std01 saved = service.save(entity);

            result.success = true;
            result.data = saved;

        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }

        return result;
    }

    // ----------------------
// 🔹 안전한 파싱 유틸 메서드
// ----------------------
    private BigDecimal parseBigDecimal(String val) {
        try {
            return (val != null && !val.isEmpty()) ? new BigDecimal(val) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(String val, int defaultVal) {
        try {
            return (val != null && !val.isEmpty()) ? Integer.valueOf(val) : defaultVal;
        } catch (Exception e) {
            return defaultVal;
        }
    }




    /** ✅ 삭제 */
    // [변경] @RequestBody(JSON) -> @RequestParam(form-urlencoded).
    //   화면의 AjaxUtil.postSyncData 는 form-urlencoded 로 전송하므로
    //   @RequestBody 로 받으면 415(Content type not supported) 가 난다.
    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam Map<String, String> params) {
        AjaxResult result = new AjaxResult();
        try {
            String idStr = params.get("id");
            if (idStr == null || idStr.isEmpty()) {
                result.success = false;
                result.message = "삭제할 ID가 없습니다.";
                return result;
            }
            service.delete(Integer.valueOf(idStr));
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }
}
