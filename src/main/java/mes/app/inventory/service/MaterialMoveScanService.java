package mes.app.inventory.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

/**
 * 바코드 스캔 불출 (자재창고 → 생산창고).
 *
 * ────────────────────────────────────────────────────────────────────
 * ★ 왜 mat_lot_cons 를 쓰지 않는가
 *
 *   불출은 「소비」가 아니라 「이동」이다. mat_lot_cons 에 넣으면 투입 이력에
 *   섞여 생산 투입량이 부풀고, 로트 추적에서 이동이 소비로 보인다.
 *
 *   대신 mat_lot 행을 쪼갠다. 총량은 보존된다:
 *
 *     [불출 전]  자재창고 로트 A : InputQty 400 / CurrentStock 400
 *     [150 불출] 자재창고 로트 A : InputQty 250 / CurrentStock 250
 *                생산창고 로트 A': InputQty 150 / CurrentStock 150   (LotNumber 동일)
 *
 *   ★ InputQty 도 같이 줄이는 것이 핵심이다.
 *     CurrentStock 만 낮추면 "InputQty - OutQtySum = CurrentStock" 이 깨지고,
 *     그 공식에 의존하는 곳이 조용히 틀린다:
 *       · WashService.itemCancel — CurrentStock < InputQty 로 「차감된 로트」를 찾는다
 *       · MaterialInoutController.saveScanInput — 재입고 시 (InputQty+qty)-OutQtySum 으로
 *         CurrentStock 을 재계산한다 → 낮춰둔 재고가 되살아나 수량이 복제된다
 *     OutQtySum 은 건드리지 않는다. 나간 것이 아니라 자리를 옮긴 것이므로.
 *
 * ★ 왜 mat_inout 만으로는 안 되는가
 *   matinout_tri → sp_update_mat_in_house_by_inout(Material_id, StoreHouse_id).
 *   인자에 로트가 없다. mat_inout 에 LotNumber 를 채워도 mat_lot 은 안 움직인다.
 *   mat_inout 은 창고 집계(mat_in_house/material)용이고, 로트는 여기서 직접 옮긴다.
 *
 * ★ 상세 테이블이 없는 이유
 *   mat_lot / mat_inout 이 "SourceTableName" + "SourceDataPk" 를 들고 있다.
 *   헤더 id 를 거기 심어두면 두 테이블에서 그대로 되찾아진다.
 *   그래서 도착창고에 같은 LotNumber 가 이미 있어도 합치지 않고 새 행을 만든다 —
 *   합치면 「이 불출이 만든 부분」을 가려낼 수 없어 취소가 불가능해진다.
 *
 * ★ 동시성
 *   재고 차감은 반드시
 *       UPDATE ... SET "CurrentStock" = "CurrentStock" - :qty
 *        WHERE id = :id AND "CurrentStock" >= :qty
 *   형태로 한 방에 쓴다. 두 태블릿이 같은 로트를 동시에 찍어도 한쪽만 통과한다.
 *   영향 행 0 = 재고 부족 → 예외 → 롤백.
 *   ★ 절대 SELECT 로 읽고 계산해서 UPDATE 하지 말 것.
 *
 * ★ FIFO
 *   도착 로트의 InputDateTime 은 「지금」이 아니라 출발 로트의 값을 물려받는다.
 *   세척(WashService.newLot)은 now() 를 넣는데, 그러면 옮긴 시점이 입고 시점이 되어
 *   오래된 자재가 FIFO 뒤로 밀린다. 유효기한이 있는 자재에서 위험하다.
 * ────────────────────────────────────────────────────────────────────
 */
@Service
public class MaterialMoveScanService {

	@Autowired
	SqlRunner sqlRunner;

	/** mat_lot / mat_inout 에 남기는 출처. 취소가 이 값으로 되찾는다. */
	public static final String SRC_TABLE = "mat_move_scan";

	// =================================================================
	// 1. 스캔 → 로트 조회
	// =================================================================

	/**
	 * 스캔값으로 출발창고의 로트를 찾는다.
	 *
	 * 결과가 비면 화면이 「없는 로트」 행을 빨갛게 깐다. 알럿을 띄우지 않는다 —
	 * 연속 스캔 중 알럿이 뜨면 손이 멈춘다.
	 * 재고 0 이어도 행은 내려준다. 실물이 손에 있는데 화면에서 사라지면
	 * 작업자가 "왜 없지" 로 헤맨다. 0 으로 찍히고 불출에서 제외될 뿐이다.
	 */
	public List<Map<String, Object>> findLotsInStore(String lot, String raw, Integer matId,
													 Integer storeId, String spjangcd) {

		String key = StringUtils.hasText(lot) ? lot.trim() : (raw == null ? "" : raw.trim());
		if (key.isEmpty()) return new ArrayList<>();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("key", key);
		p.addValue("storeId", storeId);
		p.addValue("matId", matId);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		// 같은 LotNumber 가 창고마다 갈라져 있으므로 출발창고로 반드시 좁힌다.
		// 그러지 않으면 이미 생산창고로 옮겨간 행을 다시 집어 이중 불출이 된다.
		// 같은 창고 안에서도 행이 여러 개일 수 있어(불출·세척이 쪼갠 흔적) 전부 내려준다.
		String sql = """
            SELECT ml.id                AS mat_lot_id
                 , ml."LotNumber"       AS lot_number
                 , ml."CurrentStock"    AS stock
                 , ml."InputQty"        AS input_qty
                 , ml."InputDateTime"   AS input_dt
                 , ml."EffectiveDate"   AS effective_date
                 , ml."MakerLotNo"      AS maker_lot_no
                 , m.id                 AS mat_id
                 , m."Code"             AS mat_code
                 , m."Name"             AS mat_name
                 , u."Name"             AS unit_name
                 , sh."Name"            AS store_name
              FROM mat_lot ml
              JOIN material m     ON m.id  = ml."Material_id"
              LEFT JOIN unit u    ON u.id  = m."Unit_id"
              JOIN store_house sh ON sh.id = ml."StoreHouse_id"
             WHERE UPPER(ml."LotNumber") = UPPER(CAST(:key AS varchar))
               AND ml."StoreHouse_id" = CAST(:storeId AS integer)
               AND COALESCE(ml._status,'a') = 'a'
               AND (CAST(:matId AS integer) IS NULL OR ml."Material_id" = CAST(:matId AS integer))
               AND (CAST(:spjangcd AS varchar) IS NULL OR ml.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
			""";

		List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, p);
		return rows == null ? new ArrayList<>() : rows;   // ★ getRows 는 오류 시 null 을 준다
	}

	// =================================================================
	// 2. 불출
	// =================================================================

	/**
	 * @param lines [{mat_lot_id, qty}] — 화면 목록에서 유효한 행만
	 */
	@Transactional
	public AjaxResult move(Integer fromStore, Integer toStore,
						   List<Map<String, Object>> lines,
						   String description, String spjangcd, User user) {

		AjaxResult r = new AjaxResult();

		if (fromStore == null || toStore == null) {
			r.success = false; r.message = "출발/도착 창고를 확인하세요."; return r;
		}
		if (fromStore.equals(toStore)) {
			r.success = false; r.message = "출발창고와 도착창고가 같습니다."; return r;
		}
		if (lines == null || lines.isEmpty()) {
			r.success = false; r.message = "불출할 항목이 없습니다."; return r;
		}

		LocalDate nowD = LocalDate.now();
		LocalTime nowT = LocalTime.now();

		Integer headId = insertHead(fromStore, toStore, nowD, nowT, description, spjangcd, user);
		String  moveNo = makeMoveNo(headId, nowD);
		updateMoveNo(headId, moveNo);

		int cnt = 0;
		float totalQty = 0f;

		for (Map<String, Object> ln : lines) {
			Integer srcLotId = toInt(ln.get("mat_lot_id"));
			float   qty      = toF(ln.get("qty"));
			if (srcLotId == null || qty <= 0) continue;   // 없는 로트 행 / 0 수량 행은 건너뛴다

			Map<String, Object> src = getLot(srcLotId);
			if (src == null) {
				throw new IllegalStateException("로트를 찾을 수 없습니다. (id=" + srcLotId + ")");
			}
			Integer matId    = toInt(src.get("Material_id"));
			String  lotNo    = str(src.get("LotNumber"));
			Integer srcStore = toInt(src.get("StoreHouse_id"));

			if (srcStore == null || !srcStore.equals(fromStore)) {
				// 다른 단말이 먼저 옮겼거나 화면이 낡았다. 막지 않으면 엉뚱한 창고가 깎인다.
				throw new IllegalStateException(
						"로트 " + lotNo + " 이(가) 출발창고에 없습니다. 화면을 새로고침하세요.");
			}

			// ── (1) 출발 로트 차감 — 원자적. 부족하면 0행 → 롤백 ──
			if (decreaseLot(srcLotId, qty) == 0) {
				throw new IllegalStateException(
						"재고가 부족합니다. (로트 " + lotNo + ", 요청 " + fmt(qty) + ")");
			}

			// ── (2) 도착 로트 신규 — 합치지 않는다(취소를 위해) ──
			insertDstLot(src, toStore, qty, headId, moveNo, user, spjangcd);

			// ── (3) mat_inout — 창고 집계(mat_in_house/material)는 이쪽 트리거 소관 ──
			insertInout(matId, fromStore, lotNo, "out", qty, nowD, nowT, headId, moveNo, user, spjangcd, src);
			insertInout(matId, toStore,   lotNo, "in",  qty, nowD, nowT, headId, moveNo, user, spjangcd, src);

			cnt++;
			totalQty += qty;
		}

		if (cnt == 0) {
			throw new IllegalStateException("불출할 항목이 없습니다.");   // 헤더만 남지 않도록 롤백
		}

		Map<String, Object> data = new HashMap<>();
		data.put("move_id", headId);
		data.put("move_no", moveNo);
		data.put("line_cnt", cnt);
		data.put("total_qty", totalQty);
		r.data = data;
		r.success = true;
		return r;
	}

	// =================================================================
	// 2-B. 수동 불출 — 품목·수량만 받아 로트를 FIFO 로 배분
	//
	// ★ 왜 로트를 화면에 보여주는가
	//   로트를 안 옮기면 mat_lot."StoreHouse_id" 가 자재창고에 남아,
	//   세척·조립·부적합이 그 로트를 못 찾는다(전부 이 컬럼을 진실로 본다).
	//   그렇다고 서버가 조용히 FIFO 로 골라 옮기면, 나중에 작업자가 실물 바코드를
	//   찍었을 때 "재고 0" 으로 뜬다 — 시스템이 이미 그 로트를 대신 빼갔기 때문이다.
	//   그래서 배분 결과를 확인 단계에서 보여주고, 다르면 고칠 수 있게 한다.
	//   작업자는 로트를 「고르는」 것이 아니라 「확인하는」 것이다.
	// =================================================================

	/**
	 * FIFO 배분 미리보기. 재고를 건드리지 않는다 — 확인 화면 전용.
	 *
	 * @param items [{mat_id, qty}]
	 * @return [{mat_id, mat_code, mat_name, req_qty, shortage, lots:[{mat_lot_id, ...,  qty}]}]
	 */
	public List<Map<String, Object>> previewFifo(Integer fromStore,
												 List<Map<String, Object>> items,
												 String spjangcd) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (items == null) return out;

		for (Map<String, Object> it : items) {
			Integer matId = toInt(it.get("mat_id"));
			float   req   = toF(it.get("qty"));
			if (matId == null || req <= 0) continue;

			Map<String, Object> row = new HashMap<>();
			row.put("mat_id", matId);
			row.put("req_qty", req);

			List<Map<String, Object>> lots = fifoLots(matId, fromStore, spjangcd);
			List<Map<String, Object>> alloc = new ArrayList<>();
			float remain = req;

			for (Map<String, Object> l : lots) {
				if (remain <= 0) break;
				float cs   = toF(l.get("stock"));
				float take = Math.min(cs, remain);
				if (take <= 0) continue;

				Map<String, Object> a = new HashMap<>(l);
				a.put("qty", take);
				alloc.add(a);
				remain -= take;

				if (row.get("mat_code") == null) {
					row.put("mat_code", l.get("mat_code"));
					row.put("mat_name", l.get("mat_name"));
					row.put("unit_name", l.get("unit_name"));
				}
			}

			// 재고가 모자라면 여기서 숨기지 않는다. 화면이 빨갛게 알리고 작업자가 판단한다.
			row.put("shortage", remain > 0 ? remain : 0f);
			row.put("lots", alloc);

			if (row.get("mat_code") == null) {
				Map<String, Object> m = this.sqlRunner.getRow("""
                    SELECT m."Code" AS mat_code, m."Name" AS mat_name, u."Name" AS unit_name
                      FROM material m LEFT JOIN unit u ON u.id = m."Unit_id"
                     WHERE m.id = :id
					""", new MapSqlParameterSource().addValue("id", matId));
				if (m != null) row.putAll(m);
			}
			out.add(row);
		}
		return out;
	}

	/**
	 * 품목 + 창고의 로트 목록(FIFO 순). 수동 불출 화면이 로트를 직접 고를 때 쓴다.
	 *
	 * ★ 수량을 먼저 받지 않는다.
	 *   로트별 재고를 보여주고 거기서 꺼낼 만큼 적게 하는 편이,
	 *   총량을 입력받아 서버가 쪼개는 것보다 실물과 어긋날 여지가 적다.
	 */
	public List<Map<String, Object>> lotsOf(Integer matId, Integer storeId, String spjangcd) {
		if (matId == null || storeId == null) return new ArrayList<>();
		return fifoLots(matId, storeId, spjangcd);
	}

	/** 출발창고의 로트를 FIFO 순으로. 재고 0 은 제외한다(배분 대상이 아니므로). */
	private List<Map<String, Object>> fifoLots(Integer matId, Integer storeId, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("storeId", storeId);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		// ★ 정렬 기준은 세척·조립(ProductionCreateService)과 반드시 같아야 한다.
		//   한쪽만 FEFO 로 바꾸면 "생산은 A 로트를 쓰는데 불출은 B 를 보낸다" 가 된다.
		//   유효기한 우선(FEFO)으로 갈 거라면 여기와 저쪽을 같이 바꿀 것.
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            SELECT ml.id              AS mat_lot_id
                 , ml."LotNumber"     AS lot_number
                 , ml."CurrentStock"  AS stock
                 , ml."InputDateTime" AS input_dt
                 , ml."EffectiveDate" AS effective_date
                 , m.id               AS mat_id
                 , m."Code"           AS mat_code
                 , m."Name"           AS mat_name
                 , u."Name"           AS unit_name
              FROM mat_lot ml
              JOIN material m  ON m.id = ml."Material_id"
              LEFT JOIN unit u ON u.id = m."Unit_id"
             WHERE ml."Material_id" = :matId
               AND ml."StoreHouse_id" = :storeId
               AND COALESCE(ml."CurrentStock",0) > 0
               AND COALESCE(ml._status,'a') = 'a'
               AND (CAST(:spjangcd AS varchar) IS NULL OR ml.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
			""", p);
		return rows == null ? new ArrayList<>() : rows;
	}

	/**
	 * 확인 단계에서 돌려보낸 로트 배분으로 실제 이동.
	 *
	 * ★ 화면이 준 배분을 그대로 쓴다 — 서버가 다시 FIFO 를 돌리지 않는다.
	 *   작업자가 확인 화면에서 로트를 바꿨을 수 있기 때문이다. 여기서 재계산하면
	 *   화면에 보여준 것과 다른 로트가 나가고, 그 사실을 아무도 모른다.
	 *   대신 move() 안에서 로트별 재고를 조건부 UPDATE 로 다시 검증한다.
	 */
	@Transactional
	public AjaxResult moveByLots(Integer fromStore, Integer toStore,
								 List<Map<String, Object>> lines,
								 String description, String spjangcd, User user) {
		return move(fromStore, toStore, lines,
				(description == null || description.isBlank()) ? "수동 불출" : description,
				spjangcd, user);
	}

	// =================================================================
	// 3. 취소 — 정확히 반대로
	// =================================================================

	/**
	 * 되돌리는 순서가 중요하다. 도착 로트를 먼저 깎아보고, 그게 되는 경우에만
	 * 출발 로트를 되살린다. 반대로 하면 도착 재고가 이미 소진됐을 때
	 * 출발 재고만 늘어나 총량이 부풀어 오른다.
	 *
	 * ★ 전량 검사를 먼저 한 바퀴 돈다.
	 *   한 줄이라도 못 되돌리면 아무것도 건드리지 않는다. 부분 취소는 만들지 않는다 —
	 *   "3건 중 2건만 취소됨" 상태는 현장에서 아무도 재구성할 수 없다.
	 */
	@Transactional
	public AjaxResult cancel(Integer moveId, User user) {

		AjaxResult r = new AjaxResult();

		Map<String, Object> head = this.sqlRunner.getRow("""
            SELECT id, "MoveNo", "State", "FromStoreHouse_id", "ToStoreHouse_id"
              FROM mat_move_scan
             WHERE id = :id AND COALESCE(_status,'a') = 'a'
			""", new MapSqlParameterSource().addValue("id", moveId));

		if (head == null) {
			r.success = false; r.message = "불출 이력을 찾을 수 없습니다."; return r;
		}
		if ("canceled".equals(str(head.get("State")))) {
			r.success = false; r.message = "이미 취소된 불출입니다."; return r;
		}
		Integer fromStore = toInt(head.get("FromStoreHouse_id"));

		// 이 불출이 만든 도착 로트들
		List<Map<String, Object>> dstLots = this.sqlRunner.getRows("""
            SELECT id, "Material_id", "LotNumber", "InputQty", "CurrentStock", "OutQtySum"
              FROM mat_lot
             WHERE "SourceTableName" = :src AND "SourceDataPk" = :id
               AND COALESCE(_status,'a') = 'a'
			""", new MapSqlParameterSource().addValue("src", SRC_TABLE).addValue("id", moveId));

		if (dstLots == null || dstLots.isEmpty()) {
			r.success = false; r.message = "불출 상세를 찾을 수 없습니다."; return r;
		}

		// ── (0) 사전 검사 — 하나라도 못 되돌리면 전부 중단 ──
		//   도착 로트는 이 불출이 통째로 만든 행이므로, 아직 손대지 않았다면
		//   CurrentStock == InputQty 다. 줄어 있으면 누군가 이미 가져갔다는 뜻.
		for (Map<String, Object> d : dstLots) {
			float input = toF(d.get("InputQty"));
			float cur   = toF(d.get("CurrentStock"));
			if (cur < input) {
				throw new IllegalStateException(
						"로트 " + str(d.get("LotNumber")) + " 은(는) 이미 사용되어 취소할 수 없습니다. "
								+ "(불출 " + fmt(input) + " 중 잔량 " + fmt(cur) + ")");
			}
		}

		// ── (1) 도착 로트 회수 + 출발 로트 복구 ──
		for (Map<String, Object> d : dstLots) {
			Integer dstId = toInt(d.get("id"));
			Integer matId = toInt(d.get("Material_id"));
			String  lotNo = str(d.get("LotNumber"));
			float   qty   = toF(d.get("InputQty"));

			// 사전 검사와 실제 차감 사이에 누가 가져갈 수 있다. 조건부 UPDATE 로 한 번 더 막는다.
			if (decreaseLot(dstId, qty) == 0) {
				throw new IllegalStateException(
						"로트 " + lotNo + " 의 재고가 방금 변경되었습니다. 다시 시도하세요.");
			}

			// 출발 로트 복구 — 같은 품목·로트번호로 출발창고에 남아 있는 행.
			// 출발 행은 수량이 0 이 되어도 삭제하지 않으므로 반드시 존재한다.
			Integer srcId = findLotIdInStore(matId, lotNo, fromStore);
			if (srcId == null) {
				throw new IllegalStateException(
						"출발창고에서 로트 " + lotNo + " 을(를) 찾을 수 없어 되돌릴 수 없습니다.");
			}
			increaseLot(srcId, qty);

			// 빈 껍데기가 된 도착 행 제거. 이 불출이 만든 행만 지운다.
			this.sqlRunner.execute("""
                DELETE FROM mat_lot
                 WHERE id = :id
                   AND "SourceTableName" = :src AND "SourceDataPk" = :moveId
                   AND COALESCE("CurrentStock",0) <= 0
                   AND COALESCE("InputQty",0)     <= 0
				""", new MapSqlParameterSource()
					.addValue("id", dstId).addValue("src", SRC_TABLE).addValue("moveId", moveId));
		}

		// ── (2) mat_inout 삭제 → 트리거가 창고 집계를 되돌린다 ──
		this.sqlRunner.execute("""
            DELETE FROM mat_inout
             WHERE "SourceTableName" = :src AND "SourceDataPk" = :id
			""", new MapSqlParameterSource().addValue("src", SRC_TABLE).addValue("id", moveId));

		// ── (3) 헤더 ──
		this.sqlRunner.execute("""
            UPDATE mat_move_scan
               SET "State" = 'canceled', "CanceledAt" = now(),
                   "Canceler_id" = :uid, "_modified" = now(), "_modifier_id" = :uid
             WHERE id = :id
			""", new MapSqlParameterSource()
				.addValue("id", moveId)
				.addValue("uid", user == null ? null : user.getId()));

		r.success = true;
		return r;
	}

	// =================================================================
	// 4. 이력 조회
	// =================================================================

	public List<Map<String, Object>> history(String dateFrom, String dateTo,
											 Integer fromStore, Integer toStore,
											 String state, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("dateFrom", dateFrom);
		p.addValue("dateTo", dateTo);
		p.addValue("fromStore", fromStore);
		p.addValue("toStore", toStore);
		p.addValue("state", (state == null || state.isBlank()) ? null : state);
		p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? null : spjangcd);

		// 건수·수량은 mat_inout 의 out 행에서 센다.
		// 취소된 건은 mat_inout 이 지워져 0 으로 나온다 — 그게 사실이라 맞다.
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            SELECT h.id                        AS move_id
                 , h."MoveNo"                  AS move_no
                 , h."MoveDate"                AS move_date
                 , h."MoveTime"                AS move_time
                 , h."State"                   AS state
                 , h."Description"             AS description
                 , sf."Name"                   AS from_store
                 , st."Name"                   AS to_store
                 , h."_creater_id"             AS creater_id
                 , COALESCE(x.line_cnt, 0)     AS line_cnt
                 , COALESCE(x.total_qty, 0)    AS total_qty
              FROM mat_move_scan h
              LEFT JOIN store_house sf ON sf.id = h."FromStoreHouse_id"
              LEFT JOIN store_house st ON st.id = h."ToStoreHouse_id"
              LEFT JOIN (
                    SELECT "SourceDataPk" AS move_id
                         , COUNT(*)                     AS line_cnt
                         , COALESCE(SUM("OutputQty"),0) AS total_qty
                      FROM mat_inout
                     WHERE "SourceTableName" = 'mat_move_scan' AND "InOut" = 'out'
                     GROUP BY "SourceDataPk"
              ) x ON x.move_id = h.id
             WHERE COALESCE(h._status,'a') = 'a'
               AND (CAST(:dateFrom  AS date)    IS NULL OR h."MoveDate" >= CAST(:dateFrom AS date))
               AND (CAST(:dateTo    AS date)    IS NULL OR h."MoveDate" <= CAST(:dateTo   AS date))
               AND (CAST(:fromStore AS integer) IS NULL OR h."FromStoreHouse_id" = CAST(:fromStore AS integer))
               AND (CAST(:toStore   AS integer) IS NULL OR h."ToStoreHouse_id"   = CAST(:toStore   AS integer))
               AND (CAST(:state     AS varchar) IS NULL OR h."State" = CAST(:state AS varchar))
               AND (CAST(:spjangcd  AS varchar) IS NULL OR h.spjangcd = CAST(:spjangcd AS varchar))
             ORDER BY h."MoveDate" DESC, h."MoveTime" DESC, h.id DESC
			""", p);
		return rows == null ? new ArrayList<>() : rows;
	}

	/** 상세 — mat_inout 의 out 행이 곧 불출 명세다 */
	public List<Map<String, Object>> historyDetail(Integer moveId) {
		List<Map<String, Object>> rows = this.sqlRunner.getRows("""
            SELECT mi.id
                 , mi."LotNumber"   AS lot_number
                 , mi."OutputQty"   AS move_qty
                 , m."Code"         AS mat_code
                 , m."Name"         AS mat_name
                 , u."Name"         AS unit_name
              FROM mat_inout mi
              JOIN material m  ON m.id = mi."Material_id"
              LEFT JOIN unit u ON u.id = m."Unit_id"
             WHERE mi."SourceTableName" = :src AND mi."SourceDataPk" = :id
               AND mi."InOut" = 'out'
             ORDER BY mi.id
			""", new MapSqlParameterSource().addValue("src", SRC_TABLE).addValue("id", moveId));
		return rows == null ? new ArrayList<>() : rows;
	}

	// =================================================================
	// 내부
	// =================================================================

	/**
	 * ★ 재고 차감의 유일한 형태.
	 *   조건부 UPDATE 한 방. 영향 행 0 = 재고 부족(또는 동시 차감에 밀림).
	 *   InputQty 를 함께 줄여 "InputQty - OutQtySum = CurrentStock" 을 유지한다.
	 */
	private int decreaseLot(Integer lotId, float qty) {
		if (lotId == null) return 0;
		return this.sqlRunner.execute("""
            UPDATE mat_lot
               SET "CurrentStock" = COALESCE("CurrentStock",0) - :qty
                 , "InputQty"     = COALESCE("InputQty",0)     - :qty
                 , "_modified"    = now()
             WHERE id = :id
               AND COALESCE("CurrentStock",0) >= :qty
               AND COALESCE("InputQty",0)     >= :qty
			""", new MapSqlParameterSource().addValue("id", lotId).addValue("qty", qty));
	}

	private void increaseLot(Integer lotId, float qty) {
		if (lotId == null) return;
		this.sqlRunner.execute("""
            UPDATE mat_lot
               SET "CurrentStock" = COALESCE("CurrentStock",0) + :qty
                 , "InputQty"     = COALESCE("InputQty",0)     + :qty
                 , "_modified"    = now()
             WHERE id = :id
			""", new MapSqlParameterSource().addValue("id", lotId).addValue("qty", qty));
	}

	private Map<String, Object> getLot(Integer lotId) {
		return this.sqlRunner.getRow("""
            SELECT id, "Material_id", "LotNumber", "StoreHouse_id", "CurrentStock"
                 , "InputQty", "OutQtySum", "InputDateTime", "EffectiveDate"
                 , "MakerLotNo", "Description", spjangcd
              FROM mat_lot WHERE id = :id AND COALESCE(_status,'a') = 'a'
			""", new MapSqlParameterSource().addValue("id", lotId));
	}

	/** 취소 시 출발 로트 되찾기. 여러 행이면 가장 오래된 것에 되돌린다(총량은 동일). */
	private Integer findLotIdInStore(Integer matId, String lotNo, Integer storeId) {
		Map<String, Object> row = this.sqlRunner.getRow("""
            SELECT id FROM mat_lot
             WHERE "Material_id" = :matId
               AND "LotNumber" = CAST(:lotNo AS varchar)
               AND "StoreHouse_id" = :storeId
               AND COALESCE(_status,'a') = 'a'
             ORDER BY "InputDateTime" ASC, id ASC LIMIT 1
			""", new MapSqlParameterSource()
				.addValue("matId", matId).addValue("lotNo", lotNo).addValue("storeId", storeId));
		return row == null ? null : toInt(row.get("id"));
	}

	/**
	 * 도착 로트 신규.
	 * ★ InputDateTime 은 출발 로트의 값을 물려받는다 — FIFO 순서 보존.
	 * ★ EffectiveDate·MakerLotNo 도 그대로. 옮겼다고 유효기한이 새로 생기지 않는다.
	 * ★ OutQtySum = 0. 새 행은 아직 아무것도 내보내지 않았다.
	 */
	private Integer insertDstLot(Map<String, Object> src, Integer toStore, float qty,
								 Integer headId, String moveNo, User user, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId",    toInt(src.get("Material_id")));
		p.addValue("lotNo",    str(src.get("LotNumber")));
		p.addValue("storeId",  toStore);
		p.addValue("qty",      qty);
		p.addValue("inputDt",  src.get("InputDateTime"));
		p.addValue("effDt",    src.get("EffectiveDate"));
		p.addValue("makerLot", src.get("MakerLotNo"));
		p.addValue("srcTable", SRC_TABLE);
		p.addValue("srcPk",    headId);
		p.addValue("desc",     "바코드 불출 " + moveNo);
		p.addValue("uid",      user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);

		Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO mat_lot
                ("Material_id", "LotNumber", "StoreHouse_id", "InputQty", "CurrentStock",
                 "OutQtySum", "InputDateTime", "EffectiveDate", "MakerLotNo",
                 "SourceTableName", "SourceDataPk", "Description",
                 _status, _created, _creater_id, spjangcd)
            VALUES
                (:matId, CAST(:lotNo AS varchar), :storeId, :qty, :qty,
                 0, :inputDt, :effDt, CAST(:makerLot AS varchar),
                 CAST(:srcTable AS varchar), :srcPk, CAST(:desc AS varchar),
                 'a', now(), :uid, CAST(:spjangcd AS varchar))
            RETURNING id
			""", p);
		return row == null ? null : toInt(row.get("id"));
	}

	/**
	 * ★ InputQty 와 OutputQty 를 헷갈리면 재고가 반대로 간다.
	 *   out 인데 InputQty 에 넣으면 가산된다 (부적합 등록에서 한 번 겪은 함정).
	 */
	private Integer insertInout(Integer matId, Integer storeId, String lotNo, String inOut,
								float qty, LocalDate d, LocalTime t,
								Integer headId, String moveNo, User user, String spjangcd,
								Map<String, Object> src) {
		boolean isIn = "in".equals(inOut);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("matId", matId);
		p.addValue("storeId", storeId);
		p.addValue("lotNo", lotNo);
		p.addValue("inOut", inOut);
		p.addValue("inType",  isIn ? "move_in"  : null);
		p.addValue("outType", isIn ? null       : "move_out");
		p.addValue("inQty",   isIn ? qty        : null);
		p.addValue("outQty",  isIn ? null       : qty);
		p.addValue("effDt", src.get("EffectiveDate"));
		p.addValue("d", d);
		p.addValue("t", t);
		p.addValue("srcTable", SRC_TABLE);
		p.addValue("srcPk", headId);
		p.addValue("desc", "바코드 불출 " + moveNo);
		p.addValue("uid", user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);

		Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO mat_inout
                ("Material_id", "StoreHouse_id", "LotNumber", "InOut",
                 "InputType", "OutputType", "InputQty", "OutputQty",
                 "InoutDate", "InoutTime", "EffectiveDate", "Description", "State",
                 "SourceTableName", "SourceDataPk",
                 _status, _created, _creater_id, spjangcd)
            VALUES
                (:matId, :storeId, CAST(:lotNo AS varchar), CAST(:inOut AS varchar),
                 CAST(:inType AS varchar), CAST(:outType AS varchar),
                 :inQty, :outQty,
                 :d, :t, :effDt, CAST(:desc AS varchar), 'confirmed',
                 CAST(:srcTable AS varchar), :srcPk,
                 'a', now(), :uid, CAST(:spjangcd AS varchar))
            RETURNING id
			""", p);
		return row == null ? null : toInt(row.get("id"));
	}

	private Integer insertHead(Integer from, Integer to, LocalDate d, LocalTime t,
							   String desc, String spjangcd, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("from", from);
		p.addValue("to", to);
		p.addValue("d", d);
		p.addValue("t", t);
		p.addValue("desc", desc);
		p.addValue("uid", user == null ? null : user.getId());
		p.addValue("spjangcd", spjangcd);

		Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO mat_move_scan
                ("MoveDate", "MoveTime", "FromStoreHouse_id", "ToStoreHouse_id",
                 "Description", "State", _status, _created, _creater_id, spjangcd)
            VALUES (:d, :t, :from, :to, CAST(:desc AS varchar), 'confirmed',
                    'a', now(), :uid, CAST(:spjangcd AS varchar))
            RETURNING id
			""", p);
		return row == null ? null : toInt(row.get("id"));
	}

	private void updateMoveNo(Integer headId, String moveNo) {
		this.sqlRunner.execute("""
            UPDATE mat_move_scan SET "MoveNo" = CAST(:no AS varchar) WHERE id = :id
			""", new MapSqlParameterSource().addValue("id", headId).addValue("no", moveNo));
	}

	/** 채번은 id 기반. 카운트 조회 후 +1 방식은 동시 접수에서 겹친다. */
	private String makeMoveNo(Integer headId, LocalDate d) {
		return "MV" + d.toString().replace("-", "") + "-" + String.format("%04d", headId);
	}

	// ── 캐스팅 유틸 ──
	private static Integer toInt(Object o) {
		if (o == null) return null;
		if (o instanceof Number) return ((Number) o).intValue();
		try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return null; }
	}

	private static float toF(Object o) {
		if (o == null) return 0f;
		if (o instanceof Number) return ((Number) o).floatValue();
		try { return Float.parseFloat(String.valueOf(o).trim()); } catch (Exception e) { return 0f; }
	}

	private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

	private static String fmt(float f) {
		return (f == Math.floor(f)) ? String.valueOf((long) f) : String.valueOf(f);
	}
}