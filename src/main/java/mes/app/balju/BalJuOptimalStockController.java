package mes.app.balju;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BalJuOptimalStockService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/balju/optimal_stock")
public class BalJuOptimalStockController {

  @Autowired
  BalJuOptimalStockService optimalStockService;

  @GetMapping("/read")
  public AjaxResult getList(@RequestParam(value = "mat_name", required = false) String mat_name,
                            @RequestParam(value = "Inventory_status", required = false) String status,
                            @RequestParam(value = "srchStartDt") String startDt,
                            @RequestParam(value = "srchEndDt") String endDt,
                            @RequestParam(value = "spjangcd") String spjangcd,
                            // 소요량 기준: suju | plan | sum | max(기본)
                            @RequestParam(value = "basis", required = false, defaultValue = "max") String basis,
                            // 품목구분: 빈값=제품 제외 전체 / raw_mat(기본) | semi | sub_mat | ...
                            @RequestParam(value = "mat_type", required = false, defaultValue = "raw_mat") String matType,
                            // 공장 필터. 빈 값 = 전체.
                            @RequestParam(value = "factory_id", required = false) String factoryId) {
    AjaxResult result = new AjaxResult();
    /*log.info("자재 적정재고 현황 mat_name:{}, Inventory_status:{}, srchStartDt:{}, srchEndDt:{}, spjangcd:{}, basis:{}"
        , mat_name, status, startDt, endDt, spjangcd, basis);*/

    startDt = startDt + " 00:00:00";
    endDt = endDt + " 23:59:59";

    Timestamp start = Timestamp.valueOf(startDt);
    Timestamp end = Timestamp.valueOf(endDt);

    List<Map<String, Object>> items =
            optimalStockService.getList(mat_name, status, start, end, spjangcd, basis, matType, factoryId);
    result.data = items;
    return result;
  }

}