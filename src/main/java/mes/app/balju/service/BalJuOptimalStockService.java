package mes.app.balju.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BalJuOptimalStockService {

  @Autowired
  SqlRunner sqlRunner;

  /**
   * 수주량 · 생산계획 대비 자재 적정재고 현황
   *
   * 표시 대상 : 완제품(mat_grp.MaterialType='product')을 뺀 모든 품목
   *            → 반제품 · 가공품 · 원부자재가 모두 나온다
   * 소요량   : 수주 · 생산계획을 각각 BOM 다단 전개해 하위 품목 단위로 환산한 값
   *
   * @param basis   소요량 산정 기준
   *                suju = 수주만 / plan = 생산계획만 / sum = 둘의 합 / max(기본) = 둘 중 큰 값
   * @param matType 품목구분(mat_grp.MaterialType). 빈값이면 완제품만 뺀 전체
   * @param factoryId 공장 필터. 빈값/null 이면 전체 공장
   */
  public List<Map<String, Object>> getList(String matName, String status,
                                           Timestamp start, Timestamp end,
                                           String spjangcd, String basis, String matType,
                                           String factoryId) {
    MapSqlParameterSource paramMap = new MapSqlParameterSource();
    paramMap.addValue("matName", matName);
    paramMap.addValue("status", status);
    paramMap.addValue("start", start);
    paramMap.addValue("end", end);
    paramMap.addValue("spjangcd", spjangcd);
    // :basis 는 CTE 안에서 처음 등장해 타입 단서가 없다 → SQL 쪽 CAST(:basis AS text) 필수
    paramMap.addValue("basis", (basis == null || basis.isBlank()) ? "max" : basis.trim());
    // 옵션값에 공백이 섞여 들어오는 경우가 있어 trim 필수(예: ' commodity')
    paramMap.addValue("matType", (matType == null) ? "" : matType.trim());
    // 공장. null 이면 CTE 조건이 통째로 참이 되어 전체가 나온다.
    paramMap.addValue("factoryId",
            (factoryId == null || factoryId.isBlank()) ? null : Integer.valueOf(factoryId.trim()));

    String sql = """
      WITH RECURSIVE
        -- 0) 표시 대상 품목(완제품 제외)
        --    기존 Mtyn='1' 은 '자재'만 남기는 조건이라 반제품이 빠졌다.
        --    MaterialType 으로 완제품만 걷어내고 나머지는 모두 보여준다.
        mat AS (
          SELECT
            m.id,
            m."Code"  AS material_code,
            m."Name"  AS material_name,
            COALESCE(u."Name",'') AS unit_name,
            m.spjangcd,
            COALESCE(m."CurrentStock",0)::numeric AS current_stock,
            COALESCE(
              NULLIF(regexp_replace(m."Avrqty", '[^0-9.-]', '', 'g'), ''),
              '0'
            )::numeric AS optimal_stock
          FROM material m
          LEFT JOIN unit u    ON u.id = m."Unit_id"
          LEFT JOIN mat_grp g ON g.id = m."MaterialGroup_id"
          WHERE m.spjangcd = :spjangcd
            AND m."Useyn" = '0'
            AND (CAST(:factoryId AS integer) IS NULL
                 OR m."Factory_id" = CAST(:factoryId AS integer))
            AND CASE
                  -- 미선택(전체)이면 완제품만 제외
                  WHEN COALESCE(CAST(:matType AS text),'') = ''
                    THEN COALESCE(g."MaterialType",'') <> 'product'
                  -- 특정 구분을 고르면 그 구분만
                  ELSE COALESCE(g."MaterialType",'') = CAST(:matType AS text)
                END
        ),

        -- 1) 수주 집계(납품예정일 기준: head.DeliveryDate 우선, 없으면 line.DueDate)
        --    ※ Standard 를 GROUP BY 에서 뺐다. 화면에 규격 컬럼이 없는데 묶음에만 남아
        --      같은 자재가 규격별로 여러 줄로 쪼개져 나왔다.
        orders AS (
          SELECT
            s."Material_id" AS material_id,
            SUM(
              CASE WHEN COALESCE(s."SujuQty2",0) > 0 THEN s."SujuQty2"
                   ELSE COALESCE(s."SujuQty",0) END
            )::numeric AS qty
          FROM suju_head h
          JOIN suju s ON s."SujuHead_id" = h.id
          WHERE h.spjangcd = :spjangcd
            AND COALESCE(h."DeliveryDate", s."DueDate")
                BETWEEN CAST(:start AS date) AND CAST(:end AS date)
          GROUP BY s."Material_id"
        ),

        -- 2) 생산계획 집계(계획기간이 조회기간과 겹치는 건)
        --    job_plan_head.stdate/eddate 는 varchar 'YYYYMMDD' 다.
        --    TO_DATE 로 캐스팅하면 포맷이 어긋난 행 하나에 쿼리 전체가 죽으므로
        --    숫자만 남겨 앞 8자리를 잘라 문자열로 비교한다(JobPlanService 와 동일 방식).
        plan AS (
          SELECT
            jp.material_id,
            SUM(COALESCE(jp.qty,0))::numeric AS qty
          FROM job_plan jp
          JOIN job_plan_head jh ON jh.id = jp.head_id
          WHERE jh.spjangcd = :spjangcd
            AND jp.material_id IS NOT NULL
            AND LEFT(regexp_replace(COALESCE(jh.stdate,''), '[^0-9]', '', 'g'), 8)
                <= TO_CHAR(CAST(:end   AS date), 'YYYYMMDD')
            AND LEFT(regexp_replace(COALESCE(jh.eddate,''), '[^0-9]', '', 'g'), 8)
                >= TO_CHAR(CAST(:start AS date), 'YYYYMMDD')
          GROUP BY jp.material_id
        ),

        -- 3) 두 수요를 한 축으로 (전개 로직을 한 벌만 두기 위함)
        demand_src AS (
          SELECT 'suju'::text AS src, material_id, qty FROM orders
          UNION ALL
          SELECT 'plan'::text AS src, material_id, qty FROM plan
        ),

        -- 4) BOM 다단 전개 : 완제품/반제품 수량 → 하위 품목 소요량
        --    ※ 소요량 컬럼은 bom_comp."Amount" 이고 double precision 이다.
        --      캐스팅 없이 곱하면 재귀항이 double 로 승격돼
        --      "재귀 쿼리의 N번째 칼럼은 비재귀 조건에 numeric" 오류가 난다.
        ex AS (
          SELECT d.src, d.material_id, d.qty, 0 AS lvl
          FROM demand_src d
          WHERE d.material_id IS NOT NULL
          UNION ALL
          SELECT e.src,
                 bc."Material_id",
                 e.qty * COALESCE(bc."Amount",1)::numeric,
                 e.lvl + 1
          FROM ex e
          JOIN bom b       ON b."Material_id" = e.material_id
                          AND b."BOMType" = 'manufacturing'
          JOIN bom_comp bc ON bc."BOM_id" = b.id
                          AND COALESCE(bc._status,'a') = 'a'
          WHERE e.lvl < 10                       -- BOM 순환 방어
        ),

        -- 5) 품목별 수요 (중간 반제품도 그대로 남긴다)
        --    완제품은 mat 조인에서 빠지므로 별도 제외 조건이 필요 없다.
        need AS (
          SELECT
            e.material_id,
            COALESCE(SUM(e.qty) FILTER (WHERE e.src = 'suju'), 0)::numeric AS order_qty,
            COALESCE(SUM(e.qty) FILTER (WHERE e.src = 'plan'), 0)::numeric AS plan_qty
          FROM ex e
          GROUP BY e.material_id
        ),

        -- 6) 표시 대상  ※ incoming 제외
        base AS (
          SELECT
            m.id AS material_id,
            m.material_code,
            m.material_name,
            m.unit_name,
            n.order_qty,
            n.plan_qty,
            0::numeric AS incoming_qty,   -- ← 계산 제외(표시만 0)
            m.current_stock,
            m.optimal_stock
          FROM need n
          JOIN mat m ON m.id = n.material_id
        ),

        -- 7) 소요량 확정(기준 분기는 여기 한 곳에서만)
        calc AS (
          SELECT
            b.*,
            CASE CAST(:basis AS text)
              WHEN 'suju' THEN b.order_qty
              WHEN 'plan' THEN b.plan_qty
              WHEN 'sum'  THEN b.order_qty + b.plan_qty
              ELSE GREATEST(b.order_qty, b.plan_qty)
            END AS demand_qty
          FROM base b
        )
        SELECT *
        FROM (
          SELECT
            material_code,
            material_name,
            unit_name,
            order_qty,
            plan_qty,
            demand_qty,
            current_stock,
            optimal_stock,
            /* 필요수량 = (소요량 + 적정재고) - 현재고 의 양수부 */
            GREATEST(
              (demand_qty + COALESCE(optimal_stock,0)) - COALESCE(current_stock,0),
              0
            )::numeric AS need_more_qty,
            CASE
              WHEN COALESCE(current_stock,0) - (demand_qty + COALESCE(optimal_stock,0)) < 0 THEN '부족'
              WHEN COALESCE(current_stock,0) - (demand_qty + COALESCE(optimal_stock,0)) = 0 THEN '적정'
              ELSE '여유'
            END AS state
          FROM calc
        ) t
        WHERE 1=1
      """;

    // 품명(키워드) 필터: 이름/코드 모두 검색
    if (matName != null && !matName.isEmpty()) {
      sql += " AND (t.material_name ILIKE :matName OR t.material_code ILIKE :matName) ";
      paramMap.addValue("matName", "%" + matName + "%");
    }

    // 상태 필터
    if (status != null && !status.isBlank() && !"전체".equals(status.trim())) {
      String st = status.trim();
      switch (st.toLowerCase()) {
        case "shortage":
        case "lack":
        case "insufficient": st = "부족"; break;
        case "proper":
        case "ok":
        case "equal":       st = "적정"; break;
        case "excess":
        case "surplus":     st = "여유"; break;
        default: break;
      }
      sql += " AND t.state = :status ";
      paramMap.addValue("status", st);
    }

    // standard 를 뺐으므로 정렬도 코드만 사용
    sql += " ORDER BY t.material_code";

//    log.info("paramMap:{}", paramMap);
//    log.info("적정재고 현황(수주+생산계획) sql:{}", sql);

    return sqlRunner.getRows(sql, paramMap);
  }

}