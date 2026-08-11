package mes.app.dashboard.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 주간 납품현황 대시보드.
 *
 * ★ 축이 두 개다. 하나로 못 합친다.
 *   - 수주가 있는 건  : suju_head."DeliveryDate"(납기일) 기준. 「나가야 할 것」
 *   - 수주가 없는 출고 : shipment_head."ShipDate"(출고일) 기준. 「나간 것」
 *
 *   현장에서 수주 등록을 거의 안 한다. 수주만 기준으로 잡으면 화면이 통째로 빈다.
 *   그래서 두 축을 UNION 으로 합치고, 행마다 어느 쪽인지 src 로 구분한다.
 *
 * ★ 계획(p1~p7)은 납기일 기준 수주량이다. 별도 계획 테이블은 없다.
 *   영업계획·생산계획을 여기 끌어오지 않는다 —
 *   영업계획은 월 단위라 요일로 못 쪼개고(7 로 나누면 그냥 평균선이다),
 *   생산계획은 「만든 날」이라 「나가는 날」과 다른 사건이다.
 *   나란히 두면 창고에 있는 물건이 미달로 보인다.
 *
 * ★ 수주-출고 연결은 SourceTableName='rela_data' 를 반드시 건다.
 *   SourceDataPk 만으로 조인하면 다른 출처의 출고가 섞인다.
 *   (기존 SujuDeliveryStatusService 는 이 조건이 빠져 있다)
 */
@Slf4j
@Service
public class DeliveryWeeklyService {

	@Autowired
	SqlRunner sqlRunner;

	/* ================================================================
	 * 주간 라인
	 * ================================================================ */

	/**
	 * 한 행 = 수주 1건(품목 단위) 또는 수주 없는 출고 1건.
	 *
	 * d1~d7 : 요일별 출고 실적
	 * p1~p7 : 요일별 계획(납기일 기준 수주량). 수주 없는 행은 전부 0
	 */
	public List<Map<String, Object>> getLines(LocalDate start, LocalDate end, Integer factoryId) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("start", start)
				.addValue("end", end)
				.addValue("factory_id", factoryId);

		String sql = """
            WITH
            /* 수주별 총 출고량 — 지연 판정에 먼저 필요하다 */
            shipped_all AS (
                SELECT s."SourceDataPk" AS suju_id, COALESCE(SUM(s."Qty"), 0) AS qty
                  FROM shipment s
                 WHERE s."SourceTableName" = 'rela_data'
                 GROUP BY s."SourceDataPk"
            ),
            /* ── 1) 수주 축 ──
               ★ 이번 주 납기 + 「납기가 지났는데 아직 안 나간 것」.
                 후자를 빼면, 지난주 납기 건에 이번 주 출고가 붙었을 때
                 그 출고가 수주 축에서 빠져 free_ship 으로 새고
                 화면에는 「수주와 무관한 출고」처럼 보인다. */
            suju_line AS (
                SELECT su.id                       AS suju_id
                     , sh."Company_id"             AS company_id
                     , su."Material_id"            AS material_id
                     , sh."DeliveryDate"           AS due_date
                     , su."SujuQty"                AS order_qty
                     , su."UnitPrice"              AS unit_price
                     , su."Standard"               AS standard
                     , sh."Description"            AS description
                     /* Y=지연(납기 지남) / N=이번 주 납기 / F=선출고(납기가 아직 미래) */
                     , CASE WHEN sh."DeliveryDate" < :start THEN 'Y'
                            WHEN sh."DeliveryDate" > :end   THEN 'F'
                            ELSE 'N' END                    AS is_late
                  FROM suju_head sh
                  JOIN suju su ON su."SujuHead_id" = sh.id
                  JOIN material mm ON mm.id = su."Material_id"
                  LEFT JOIN shipped_all sa ON sa.suju_id = su.id
                 /* ★ 공장은 품목(material."Factory_id")으로 가른다.
                    수주·출고에는 공장 컬럼이 없다 — 납품은 「누가 언제 무엇을」이지
                    「어디서 만들었나」가 아니기 때문이다.
                    한 수주에 두 공장 품목이 섞인 건은 현재 0 건이라 안전하다. */
                 WHERE (CAST(:factory_id AS integer) IS NULL
                        OR mm."Factory_id" = CAST(:factory_id AS integer))
                   AND (
                        /* ① 이번 주 납기 */
                        sh."DeliveryDate" BETWEEN :start AND :end
                        /* ② 납기가 지났는데 아직 안 나간 것 */
                     OR (sh."DeliveryDate" < :start
                         AND su."SujuQty" > COALESCE(sa.qty, 0))
                        /* ③ ★ 납기는 아직인데 이번 주에 미리 나간 것.
                              이걸 빼면 그 출고가 free_ship 으로 새어
                              「수주와 무관한 출고」로 분류되고, 달성률 분자에서 빠져
                              16 개가 나갔는데 0% 가 되는 일이 생긴다. */
                     OR su.id IN (SELECT s2."SourceDataPk"
                                    FROM shipment s2
                                    JOIN shipment_head sh2 ON sh2.id = s2."ShipmentHead_id"
                                   WHERE s2."SourceTableName" = 'rela_data'
                                     AND sh2."ShipDate" BETWEEN :start AND :end)
                       )
            ),
            /* ── 2) 그 수주에 물린 출고 (기간 무관 — 총 출고량을 알아야 미납이 나온다) ── */
            suju_ship AS (
                SELECT s."SourceDataPk"                                   AS suju_id
                     , COALESCE(SUM(s."Qty"), 0)                          AS total_qty
                     , COALESCE(SUM(s."Qty") FILTER (
                           WHERE shh."ShipDate" BETWEEN :start AND :end), 0) AS week_qty
                     , MAX(shh."ShipDate") FILTER (
                           WHERE shh."ShipDate" BETWEEN :start AND :end)  AS last_ship
                     /* 요일별. 월=1 … 일=7 (ISO) */
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 1), 0) AS d1
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 2), 0) AS d2
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 3), 0) AS d3
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 4), 0) AS d4
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 5), 0) AS d5
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 6), 0) AS d6
                     , COALESCE(SUM(s."Qty") FILTER (WHERE shh."ShipDate" BETWEEN :start AND :end
                           AND EXTRACT(ISODOW FROM shh."ShipDate") = 7), 0) AS d7
                  FROM shipment s
                  JOIN shipment_head shh ON shh.id = s."ShipmentHead_id"
                 WHERE s."SourceTableName" = 'rela_data'
                   AND s."SourceDataPk" IN (SELECT suju_id FROM suju_line)
                 GROUP BY s."SourceDataPk"
            ),
            /* ── 3) 수주 없는 출고 축 : 이번 주에 나간 것 중 수주에 안 물린 것 ──
                   ★ 이 블록이 없으면 수주 미등록 현장에서 화면이 통째로 빈다.  */
            free_ship AS (
                SELECT shh."Company_id"        AS company_id
                     , s."Material_id"         AS material_id
                     , MAX(shh."ShipDate")     AS last_ship
                     , COALESCE(SUM(s."Qty"), 0) AS week_qty
                     , MAX(s."UnitPrice")      AS unit_price
                     , MAX(s."Description")    AS description
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 1), 0) AS d1
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 2), 0) AS d2
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 3), 0) AS d3
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 4), 0) AS d4
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 5), 0) AS d5
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 6), 0) AS d6
                     , COALESCE(SUM(s."Qty") FILTER (WHERE EXTRACT(ISODOW FROM shh."ShipDate") = 7), 0) AS d7
                  FROM shipment s
                  JOIN shipment_head shh ON shh.id = s."ShipmentHead_id"
                  JOIN material mm ON mm.id = s."Material_id"
                 WHERE (CAST(:factory_id AS integer) IS NULL
                        OR mm."Factory_id" = CAST(:factory_id AS integer))
                   AND shh."ShipDate" BETWEEN :start AND :end
                   /* ★ NOT (... IN ...) 을 쓰면 안 된다.
                      SourceTableName 이나 SourceDataPk 가 NULL 인 출고행에서
                      조건이 NULL 이 되고 NOT NULL 도 NULL 이라 그 행이 통째로 빠진다.
                      수주 없이 나간 출고가 바로 그런 행이라 정확히 잘못된 대상이 사라진다.
                      NOT EXISTS 는 NULL 을 「없음」으로 제대로 처리한다. */
                   AND NOT EXISTS (
                         SELECT 1 FROM suju_line sl2
                          WHERE s."SourceTableName" = 'rela_data'
                            AND s."SourceDataPk"    = sl2.suju_id)
                 GROUP BY shh."Company_id", s."Material_id"
            )
            /* ── 수주 축 ── */
            SELECT 'suju'                                   AS src
                 , sl.suju_id                               AS suju_id
                 , c."Name"                                 AS "CompanyName"
                 , m."Code"                                 AS product_code
                 , m."Name"                                 AS product_name
                 , COALESCE(sl.standard,  m."Standard1")    AS "Standard"
                 , u."Name"                                AS "UnitName"
                 , to_char(sl.due_date, 'yyyy-mm-dd')       AS "DueDate"
                 , sl.order_qty                             AS "OrderQty"
                 , COALESCE(sl.unit_price, 0)               AS "UnitPrice"
                 , COALESCE(ss.total_qty, 0)                AS "TotalDeliveryQty"
                 , GREATEST(sl.order_qty - COALESCE(ss.total_qty, 0), 0) AS "RemainQty"
                 , to_char(ss.last_ship, 'yyyy-mm-dd')      AS "LastShipDate"
                 , COALESCE(ss.d1, 0) AS d1, COALESCE(ss.d2, 0) AS d2
                 , COALESCE(ss.d3, 0) AS d3, COALESCE(ss.d4, 0) AS d4
                 , COALESCE(ss.d5, 0) AS d5, COALESCE(ss.d6, 0) AS d6
                 , COALESCE(ss.d7, 0) AS d7
                 /* 계획 = 납기일 요일에 수주량을 통째로 얹는다.
                    ★ 지연 건(is_late)은 납기가 이번 주 밖이라 계획을 0 으로 둔다.
                      요일만 뽑아 얹으면 지난달 납기가 이번 주 수요일 계획으로 둔갑한다. */
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=1 THEN sl.order_qty ELSE 0 END AS p1
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=2 THEN sl.order_qty ELSE 0 END AS p2
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=3 THEN sl.order_qty ELSE 0 END AS p3
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=4 THEN sl.order_qty ELSE 0 END AS p4
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=5 THEN sl.order_qty ELSE 0 END AS p5
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=6 THEN sl.order_qty ELSE 0 END AS p6
                 , CASE WHEN sl.is_late<>'N' THEN 0 WHEN EXTRACT(ISODOW FROM sl.due_date)=7 THEN sl.order_qty ELSE 0 END AS p7
                 , sl.is_late                               AS is_late
                 , sl.description                           AS "Description"
              FROM suju_line sl
              LEFT JOIN suju_ship ss ON ss.suju_id = sl.suju_id
              LEFT JOIN company  c   ON c.id = sl.company_id
              LEFT JOIN material m   ON m.id = sl.material_id
              LEFT JOIN unit     u   ON u.id = m."Unit_id"

            UNION ALL

            /* ── 수주 없는 출고 축 ── */
            SELECT 'ship'                                   AS src
                 , NULL                                     AS suju_id
                 , c."Name"                                 AS "CompanyName"
                 , m."Code"                                 AS product_code
                 , m."Name"                                 AS product_name
                 , m."Standard1"                            AS "Standard"
                 , u."Name"                                 AS "UnitName"
                 , NULL                                     AS "DueDate"
                 , 0                                        AS "OrderQty"
                 , COALESCE(fs.unit_price, 0)               AS "UnitPrice"
                 , fs.week_qty                              AS "TotalDeliveryQty"
                 , 0                                        AS "RemainQty"
                 , to_char(fs.last_ship, 'yyyy-mm-dd')      AS "LastShipDate"
                 , fs.d1, fs.d2, fs.d3, fs.d4, fs.d5, fs.d6, fs.d7
                 , 0 AS p1, 0 AS p2, 0 AS p3, 0 AS p4, 0 AS p5, 0 AS p6, 0 AS p7
                 , 'N'                                      AS is_late
                 , fs.description                           AS "Description"
              FROM free_ship fs
              LEFT JOIN company  c ON c.id = fs.company_id
              LEFT JOIN material m ON m.id = fs.material_id
              LEFT JOIN unit     u ON u.id = m."Unit_id"

             ORDER BY 8, 3, 5
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/* ================================================================
	 * 전주 대비
	 * ================================================================ */

	/** KPI 의 「전주 대비」에만 쓴다. 출고 기준(실제로 나간 것) */
	public Map<String, Object> getPrevWeek(LocalDate start, LocalDate end, Integer factoryId) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("start", start.minusDays(7))
				.addValue("end", end.minusDays(7))
				.addValue("factory_id", factoryId);

		String sql = """
            SELECT COALESCE(SUM(s."Qty"), 0) AS "DeliveryQty"
              FROM shipment s
              JOIN shipment_head shh ON shh.id = s."ShipmentHead_id"
              JOIN material mm ON mm.id = s."Material_id"
             WHERE shh."ShipDate" BETWEEN :start AND :end
               AND (CAST(:factory_id AS integer) IS NULL
                    OR mm."Factory_id" = CAST(:factory_id AS integer))
            """;

		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row;
	}

	/* ================================================================
	 * 최근 N 주 추이
	 * ================================================================ */

	/**
	 * ★ 추이는 출고 기준이다. 납기 기준이면 「예정」의 추이가 되어
	 *   실제로 나간 양을 못 본다.
	 */
	public List<Map<String, Object>> getTrend(LocalDate end, int weeks, Integer factoryId) {

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("end", end)
				.addValue("start", end.minusDays(7L * weeks - 1))
				.addValue("factory_id", factoryId);

		String sql = """
            WITH wk AS (
                SELECT generate_series(
                           date_trunc('week', CAST(:start AS date)),
                           date_trunc('week', CAST(:end   AS date)),
                           interval '7 day')::date AS w_start
            )
            /* ★ 공장 필터는 「조인 대상 자체」를 걸러야 한다.
               LEFT JOIN material 의 ON 에 조건을 걸면 행이 걸러지지 않는다 —
               조건이 안 맞으면 mm 만 NULL 이 되고 shipment 행은 남아
               SUM 에 그대로 들어간다. 2공장을 골라도 전체 합계가 그려진다.
               그래서 필터를 적용한 출고를 먼저 만들어 두고 주차에 붙인다.
               (이렇게 해야 출고가 없는 주차도 wk 에 남아 그래프에 빈칸으로 그려진다) */
            , ship AS (
                SELECT shh."ShipDate" AS ship_date, s."Qty" AS qty
                  FROM shipment s
                  JOIN shipment_head shh ON shh.id = s."ShipmentHead_id"
                  JOIN material mm ON mm.id = s."Material_id"
                 WHERE (CAST(:factory_id AS integer) IS NULL
                        OR mm."Factory_id" = CAST(:factory_id AS integer))
            )
            SELECT to_char(wk.w_start, 'yyyy-mm-dd')                       AS week_start
                 , to_char(wk.w_start, 'IW') || '주'                       AS label
                 , COALESCE(SUM(ship.qty), 0)                              AS qty
              FROM wk
              LEFT JOIN ship
                     ON ship.ship_date >= wk.w_start
                    AND ship.ship_date <  wk.w_start + 7
             GROUP BY wk.w_start
             ORDER BY wk.w_start
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/* ================================================================
	 * 내부
	 * ================================================================ */

	/** ★ SqlRunner.getRows 는 오류 시 null 을 반환한다. 예외가 안 올라온다. */
	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return rows == null ? new ArrayList<>() : rows;
	}
}