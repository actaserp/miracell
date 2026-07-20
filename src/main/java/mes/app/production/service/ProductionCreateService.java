package mes.app.production.service;

import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.DateUtil;
import mes.domain.services.SqlRunner;
import mes.app.inventory.service.LotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 생산 실적 생성 공용 서비스 (①생산형: 조립·블리스터·융착·포장).
 *
 * 사람 경로(화면)와 장비 경로(OPC-UA 수집기)가 동일 로직을 공유한다.
 *   사람 : ProductionWorkController → createProduction(...) (member 포함)
 *   장비 : OPC-UA 수집기       → createProduction(...) (member 없음)
 *
 * 한 번의 호출 = 한 용기(대상 품목) 한 차수 완료:
 *   1) 작지 확보 — jrId 있으면 그대로, 없으면 반제품 심플 작지 자동생성 (saveProdOrderA 방식)
 *   2) mat_produce 차수 생성 (LotNumber = LotService "W" 채번, 산출창고 = work_center.ProcessStoreHouse_id)
 *   3) member 저장 (사람 경로만, 대표 IsLeader='Y')
 *   4) 투입자재(BOM) 소비 — 화면 편집 목록을 클린룸 FIFO 차감 (mat_lot_cons + mat_consu + mat_inout out)
 *   5) 산출 반제품 입고 — 클린룸(산출창고)에 mat_lot + mat_inout in
 *   6) equ_run 1건
 *
 * ★ 트리거/채번
 *   - job_res : BEFORE INSERT 트리거가 WorkOrderNumber 자동 채번 → 코드에서 안 넣음
 *   - mat_produce : 트리거 없음 → LotNumber = lotService.make_production_lot_in_number("W")
 *   - mat_lot_cons_after_tri / matinout_tri : 재고 집계 자동 → CurrentStock 직접 UPDATE 금지
 */
@Service
public class ProductionCreateService {

    @Autowired SqlRunner sqlRunner;
    @Autowired LotService lotService;
    @Autowired ProductionResultService productionResultService;
    @Autowired MaterialRepository materialRepository;
    @Autowired JobResRepository jobResRepository;
    @Autowired MatProduceRepository matProduceRepository;
    @Autowired MatLotRepository matLotRepository;
    @Autowired MatLotConsRepository matLotConsRepository;
    @Autowired MatConsuRepository matConsuRepository;
    @Autowired MatInoutRepository matInoutRepository;
    @Autowired MatProcInputRepository matProcInputRepository;
    @Autowired MatProcInputReqRepository matProcInputReqRepository;
    @Autowired EquRunRepository equRunRepository;
    @Autowired WorkcenterRepository workcenterRepository;

    private static final DateTimeFormatter DTM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 투입자재 1건 (화면 편집 BOM) */
    public static class BomInput {
        public Integer matId;
        public float qty;
        public BomInput() {}
        public BomInput(Integer matId, float qty) { this.matId = matId; this.qty = qty; }
    }

    /** 생산 실적 생성 요청 */
    public static class CreateReq {
        public Integer jobResId;          // 있으면 사용
        public Integer materialId;        // jobResId 없을 때 — 이 반제품으로 작지 자동생성
        public Integer workCenterId;      // 공정 워크센터 (조립=58 등)
        public Integer equipmentId;
        public Integer actorId;           // 대표 작업자(person.id)
        public List<Integer> memberIds;   // 조원(person.id) — 장비 경로면 null/빈
        public String shiftCode;
        public float goodQty;
        public float defectQty;
        public String productionDate;     // 'yyyy-MM-dd' — 작지 자동생성 시
        public String startTime;          // 'yyyy-MM-dd HH:mm' (nullable)
        public String endTime;            // (nullable → now)
        public List<BomInput> bomList;    // 투입자재(클린룸 소비) — 화면 편집분
        public String spjangcd;
    }

    // =====================================================================
    // 메인 진입점 (원샷) — OPC-UA/장비 경로. 내부적으로 시작→완료를 한 번에.
    // =====================================================================
    @Transactional
    public AjaxResult createProduction(CreateReq req, User user) {
        if (req.goodQty <= 0 && req.defectQty <= 0) {
            AjaxResult r = new AjaxResult(); r.success = false; r.message = "생산 수량을 입력하세요."; return r;
        }
        AjaxResult s = startProduction(req, user);
        if (!s.success) return s;
        Integer mpId = ((Number) ((Map<?, ?>) s.data).get("mat_produce_id")).intValue();
        return finishProduction(mpId, req, user);
    }

    // =====================================================================
    // 시작 — mat_produce 를 'working' 으로 생성 (재고 이동 없음). 세척 washStart 패턴.
    //   작지 확보(없으면 자동생성) → 차수 채번 → working 차수 저장 → 조원 저장 → 작지 working 전환.
    //   ★ ProductionDate = 화면 작업일(req.productionDate) 우선. (작지 계획일로 밀리는 버그 방지)
    // =====================================================================
    @Transactional
    public AjaxResult startProduction(CreateReq req, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        // ── 1. 작지 확보 ──
        JobRes jr;
        if (req.jobResId != null) {
            jr = this.jobResRepository.getJobResById(req.jobResId);
            if (jr == null) { r.success = false; r.message = "작업지시를 찾을 수 없습니다."; return r; }
        } else {
            if (req.materialId == null) { r.success = false; r.message = "생산 품목이 없습니다."; return r; }
            jr = autoCreateJobRes(req, user);
        }

        Timestamp now = DateUtil.getNowTimeStamp();
        Integer wcId = (jr.getWorkCenter_id() != null) ? jr.getWorkCenter_id() : req.workCenterId;
        Workcenter wc = (wcId != null) ? this.workcenterRepository.getWorkcenterById(wcId) : null;

        Integer outStoreId = resolveProcessStore(wcId);
        if (outStoreId == null) {
            r.success = false; r.message = "산출창고(ProcessStoreHouse)가 지정되지 않았습니다. (워크센터 " + wcId + ")";
            return r;
        }

        Timestamp prodDate = prodDateTs(req, jr);
        LocalDateTime startLdt = parseOr(req.startTime, prodDate, now);

        int chasu = this.matProduceRepository.findByJobResponseId(jr.getId()).size() + 1;
        String lotNumber = this.lotService.make_production_lot_in_number("W");

        MaterialProduce mp = new MaterialProduce();
        mp.setJobResponseId(jr.getId());
        mp.setMaterialId(jr.getMaterialId());
        if (wc != null) mp.setProcessId(wc.getProcessId());
        mp.setProcessOrder(jr.getWorkIndex() != null ? jr.getWorkIndex() : 1);
        mp.setLotIndex(chasu);
        mp.setLotNumber(lotNumber);
        mp.setLastProcessYN("N");
        mp.setStoreHouseId(outStoreId);
        mp.setProductionDate(prodDate);
        mp.setShiftCode(req.shiftCode != null ? req.shiftCode : jr.getShiftCode());
        mp.setWorkCenterId(wcId);
        mp.setEquipmentId(req.equipmentId);
        mp.setActorId(req.actorId);
        mp.setInputQty(0f);
        mp.setGoodQty(req.goodQty > 0 ? req.goodQty : 0f);   // 시작 시 입력분(계획) 표시용. 확정은 완료에서.
        mp.setDefectQty(0f);
        mp.setLossQty(0f);
        mp.setScrapQty(0f);
        mp.setState("working");
        mp.setStartTime(Timestamp.valueOf(startLdt));
        mp.setDescription("생산");
        mp.set_status("a");
        mp.set_audit(user);
        mp.setSpjangcd(req.spjangcd);
        mp = this.matProduceRepository.save(mp);

        // ── 조원 저장 (사람 경로만) ──
        saveMembers(mp.getId(), req, user);

        // ── 작지 ordered → working ──
        if ("ordered".equals(jr.getState())) {
            jr.setState("working");
            jr.set_audit(user);
            this.jobResRepository.save(jr);
        }

        r.data = Map.of("mat_produce_id", mp.getId(), "job_res_id", jr.getId(),
                "lot_number", lotNumber, "chasu", chasu);
        return r;
    }

    // =====================================================================
    // 완료 — 시작된 차수(mpId, working)에 수량 확정 + BOM 클린룸 FIFO 차감 + 반제품 입고.
    //   세척 washFinish 패턴. 여기서 처음 재고가 움직인다.
    // =====================================================================
    @Transactional
    public AjaxResult finishProduction(Integer mpId, CreateReq req, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
        if (mp == null) { r.success = false; r.message = "생산 차수를 찾을 수 없습니다."; return r; }
        String st = mp.getState();
        if (!"working".equals(st) && !"wait".equals(st)) { r.success = false; r.message = "완료할 수 없는 상태입니다."; return r; }
        if (req.goodQty <= 0 && req.defectQty <= 0) { r.success = false; r.message = "생산 수량을 입력하세요."; return r; }

        JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());
        Timestamp now = DateUtil.getNowTimeStamp();
        Integer outStoreId = mp.getStoreHouseId();

        LocalDateTime startLdt = (req.startTime != null && !req.startTime.isBlank())
                ? LocalDateTime.parse(req.startTime, DTM)
                : mp.getStartTime().toLocalDateTime();
        LocalDateTime endLdt = (req.endTime != null && !req.endTime.isBlank())
                ? LocalDateTime.parse(req.endTime, DTM) : now.toLocalDateTime();
        if (endLdt.isBefore(startLdt)) { r.success = false; r.message = "종료시각이 시작시각보다 빠릅니다."; return r; }

        // ── 수량 확정 (consumeBomForChasu 가 GoodQty 로 소요량 계산하므로 먼저 저장) ──
        mp.setGoodQty(req.goodQty);
        mp.setDefectQty(req.defectQty);
        mp.setStartTime(Timestamp.valueOf(startLdt));
        mp.setEndTime(Timestamp.valueOf(endLdt));
        mp.set_audit(user);
        mp = this.matProduceRepository.save(mp);

        // ── 투입자재 소비 = 기존 로직 재사용 ──
        //   consumeBomForChasu: 마스터 BOM 전개 + mat_proc_input 예약분을 mat_lot_cons 로 차감(+mat_consu/mat_inout).
        //   예약(mat_proc_input RequestQty)은 작업시작(reserveInput)에서 생성됨.
        AjaxResult consume = this.productionResultService.consumeBomForChasu(mp.getId(), jr, user, req.spjangcd);
        if (!consume.success) { throw new IllegalStateException(consume.message); }  // 롤백

        // ── 산출 반제품 입고 = 기존 로직 재사용 ──
        Material outMat = this.materialRepository.getMaterialById(mp.getMaterialId());
        this.productionResultService.produceInForChasu(mp.getId(), outMat, user, req.spjangcd);

        // 상태 완료
        mp.setState("finished");
        mp.set_audit(user);
        mp = this.matProduceRepository.save(mp);

        // ── equ_run (실제 가동 구간) ──
        Integer eqId = (req.equipmentId != null) ? req.equipmentId : mp.getEquipmentId();
        if (eqId != null) {
            EquRun er = new EquRun();
            er.setEquipmentId(eqId);
            er.setStartDate(Timestamp.valueOf(startLdt));
            er.setEndDate(Timestamp.valueOf(endLdt));
            er.setRunState("complete");
            er.setActorId(req.actorId);
            er.setDescription("생산 - " + (outMat != null ? outMat.getName() : ""));
            er.set_audit(user);
            er.setSpjangcd(req.spjangcd);
            this.equRunRepository.save(er);
        }

        r.data = Map.of("mat_produce_id", mp.getId(), "job_res_id", jr.getId(),
                "lot_number", mp.getLotNumber(), "chasu", mp.getLotIndex());
        return r;
    }

    // =====================================================================
    // 담기 — mat_produce 를 'wait' 로 생성 (재고 이동 없음). 세척 itemAdd 패턴.
    //   작지 확보(없으면 자동생성) → 차수 채번 → wait 차수 저장 → 조원(mat_produce_member) 저장.
    //   ★ 여기서 처음 서버에 저장된다. 작업조는 (날짜·shift·Actor) 로 파생되므로 별도 테이블 없음.
    // =====================================================================
    @Transactional
    public AjaxResult addWaitItem(CreateReq req, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        JobRes jr;
        if (req.jobResId != null) {
            jr = this.jobResRepository.getJobResById(req.jobResId);
            if (jr == null) { r.success = false; r.message = "작업지시를 찾을 수 없습니다."; return r; }
        } else {
            if (req.materialId == null) { r.success = false; r.message = "생산 품목이 없습니다."; return r; }
            jr = autoCreateJobRes(req, user);
        }

        Integer wcId = (jr.getWorkCenter_id() != null) ? jr.getWorkCenter_id() : req.workCenterId;
        Workcenter wc = (wcId != null) ? this.workcenterRepository.getWorkcenterById(wcId) : null;
        Integer outStoreId = resolveProcessStore(wcId);
        if (outStoreId == null) {
            r.success = false; r.message = "산출창고(ProcessStoreHouse)가 지정되지 않았습니다. (워크센터 " + wcId + ")";
            return r;
        }
        Timestamp prodDate = prodDateTs(req, jr);

        int chasu = this.matProduceRepository.findByJobResponseId(jr.getId()).size() + 1;
        String lotNumber = this.lotService.make_production_lot_in_number("W");

        MaterialProduce mp = new MaterialProduce();
        mp.setJobResponseId(jr.getId());
        mp.setMaterialId(jr.getMaterialId());
        if (wc != null) mp.setProcessId(wc.getProcessId());
        mp.setProcessOrder(jr.getWorkIndex() != null ? jr.getWorkIndex() : 1);
        mp.setLotIndex(chasu);
        mp.setLotNumber(lotNumber);
        mp.setLastProcessYN("N");
        mp.setStoreHouseId(outStoreId);
        mp.setProductionDate(prodDate);
        mp.setShiftCode(req.shiftCode != null ? req.shiftCode : jr.getShiftCode());
        mp.setWorkCenterId(wcId);
        mp.setEquipmentId(req.equipmentId);
        mp.setActorId(req.actorId);
        mp.setInputQty(0f);
        mp.setGoodQty(0f);
        mp.setDefectQty(0f);
        mp.setLossQty(0f);
        mp.setScrapQty(0f);
        mp.setState("wait");
        mp.setDescription("생산");
        mp.set_status("a");
        mp.set_audit(user);
        mp.setSpjangcd(req.spjangcd);
        mp = this.matProduceRepository.save(mp);

        // 조원 저장 (대표 IsLeader='Y' + 조원) — 작업조는 이 명단으로 파생
        saveMembers(mp.getId(), req, user);

        r.data = Map.of("mat_produce_id", mp.getId(), "job_res_id", jr.getId(),
                "lot_number", lotNumber, "chasu", chasu);
        return r;
    }

    // =====================================================================
    // 작업시작 예약 — BOM 소요분만큼 클린룸 로트를 FIFO 로 잡아 mat_proc_input(RequestQty) 생성.
    //   완료 시 consumeBomForChasu 가 이 예약분을 읽어 mat_lot_cons 로 실제 차감(InputQty 확정).
    //   (기존 add_lot_input 과 동일 엔티티/컬럼)
    // =====================================================================
    @Transactional
    public AjaxResult reserveInput(Integer mpId, List<BomInput> bomList, Integer cleanStore, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (bomList == null || bomList.isEmpty()) return r;

        MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
        if (mp == null) { r.success = false; r.message = "차수를 찾을 수 없습니다."; return r; }
        JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());
        Integer store = (cleanStore == null) ? 5 : cleanStore;

        // 1) mat_proc_input_req 확보 (작지당 1개)
        Integer reqId = jr.getMaterialProcessInputRequestId();
        if (reqId == null) {
            MatProcInputReq mir = new MatProcInputReq();
            mir.setRequestDate(DateUtil.getNowTimeStamp());
            mir.setRequesterId(user.getId());
            mir.set_audit(user);
            mir = this.matProcInputReqRepository.save(mir);
            reqId = mir.getId();
            jr.setMaterialProcessInputRequestId(reqId);
            jr.set_audit(user);
            this.jobResRepository.save(jr);
        }

        // 2) BOM 자재별 FIFO 예약
        java.util.List<String> shortMsgs = new java.util.ArrayList<>();
        for (BomInput bi : bomList) {
            if (bi.matId == null || bi.qty <= 0) continue;

            MapSqlParameterSource lp = new MapSqlParameterSource();
            lp.addValue("matId", bi.matId);
            lp.addValue("store", store);
            List<Map<String, Object>> lots = this.sqlRunner.getRows("""
                SELECT id AS ml_id, "CurrentStock" AS cs, "StoreHouse_id" AS sh
                  FROM mat_lot
                 WHERE "Material_id" = :matId AND "StoreHouse_id" = :store AND "CurrentStock" > 0
                 ORDER BY "InputDateTime" ASC, id ASC
                """, lp);

            float remain = bi.qty;
            for (Map<String, Object> lot : lots) {
                if (remain <= 0) break;
                int mlId = ((Number) lot.get("ml_id")).intValue();
                float cs = toF(lot.get("cs"));
                int sh = (lot.get("sh") != null) ? ((Number) lot.get("sh")).intValue() : store;
                float take = Math.min(cs, remain);
                if (take <= 0) continue;

                // 이미 이 (요청, 로트) 예약돼 있으면 스킵 (재시작 중복 방지)
                List<MatProcInput> dup = this.matProcInputRepository
                        .findByMaterialProcessInputRequestIdAndMaterialLotId(reqId, mlId);
                if (dup != null && !dup.isEmpty()) { remain -= take; continue; }

                MatProcInput mpi = new MatProcInput();
                mpi.setMaterialProcessInputRequestId(reqId);
                mpi.setMaterialId(bi.matId);
                mpi.setRequestQty(take);
                mpi.setInputQty(0f);
                mpi.setMaterialLotId(mlId);
                mpi.setMaterialStoreHouseId(sh);
                mpi.setState("requested");
                mpi.setInputDateTime(DateUtil.getNowTimeStamp());
                mpi.setActorId(user.getId());
                mpi.set_audit(user);
                this.matProcInputRepository.save(mpi);
                remain -= take;
            }
            if (remain > 0) {
                // ★ 재고 부족해도 막지 않는다 — 있는 만큼만 예약(RequestQty), 부족분은 알림만.
                //   (투입은 예약이라 시작 시점엔 재고가 없어도 됨. 실제 차감은 완료에서.)
                Material m = this.materialRepository.getMaterialById(bi.matId);
                shortMsgs.add((m != null ? m.getName() : ("자재" + bi.matId)) + " 부족 " + fmtQty(remain));
            }
        }
        if (!shortMsgs.isEmpty()) {
            r.message = "재고 부족(예약만): " + String.join(", ", shortMsgs);
        }
        return r;
    }

    private static String fmtQty(float q) {
        return (q == Math.floor(q)) ? String.valueOf((long) q) : String.valueOf(q);
    }

    /** 생산일 결정 — 화면 작업일(req.productionDate) 우선, 없으면 작지 생산일 */
    private Timestamp prodDateTs(CreateReq req, JobRes jr) {
        if (req.productionDate != null && !req.productionDate.isBlank()) {
            return Timestamp.valueOf(LocalDate.parse(req.productionDate).atStartOfDay());
        }
        return jr.getProductionDate();
    }

    // =====================================================================
    // 1. 작지 자동생성 (반제품 심플 — saveProdOrderA 심플모드 방식)
    // =====================================================================
    private JobRes autoCreateJobRes(CreateReq req, User user) {
        Material m = this.materialRepository.getMaterialById(req.materialId);
        Timestamp prodDate = (req.productionDate != null && !req.productionDate.isBlank())
                ? Timestamp.valueOf(LocalDate.parse(req.productionDate).atStartOfDay())
                : DateUtil.getNowTimeStamp();

        JobRes jr = new JobRes();
        jr.set_audit(user);
        jr.setProductionDate(prodDate);
        jr.setProductionPlanDate(prodDate);
        jr.setMaterialId(req.materialId);
        jr.setOrderQty(req.goodQty + req.defectQty);
        jr.setStoreHouse_id(m.getStoreHouseId());
        jr.setLotCount(1);
        jr.setProcessCount(1);
        jr.setWorkCenter_id(req.workCenterId);
        jr.setFirstWorkCenter_id(req.workCenterId);
        jr.setEquipment_id(req.equipmentId);
        jr.setShiftCode(req.shiftCode);
        jr.setRouting_id(null);                 // 반제품 개별 작지 = 라우팅 없음(심플)
        jr.setState("ordered");
        jr.setSpjangcd(req.spjangcd);
        // WorkOrderNumber 는 job_res BEFORE INSERT 트리거가 채번
        return this.jobResRepository.save(jr);
    }

    // =====================================================================
    // 3. 조원 저장 (mat_produce_member) — 대표 IsLeader='Y'
    // =====================================================================
    private void saveMembers(Integer mpId, CreateReq req, User user) {
        if (req.memberIds == null || req.memberIds.isEmpty()) return;
        for (Integer pid : req.memberIds) {
            if (pid == null) continue;
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("mpId", mpId);
            p.addValue("pid", pid);
            p.addValue("leader", pid.equals(req.actorId) ? "Y" : "N");
            p.addValue("userId", user.getId());
            p.addValue("spjangcd", req.spjangcd);
            this.sqlRunner.execute("""
                INSERT INTO mat_produce_member
                    ("MatProduce_id","Person_id","IsLeader","_status","_created","_creater_id",spjangcd)
                VALUES (:mpId,:pid,:leader,'a',now(),:userId,:spjangcd)
                """, p);
        }
        // 대표가 memberIds 에 없으면 대표도 1행 추가
        if (req.actorId != null && !req.memberIds.contains(req.actorId)) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("mpId", mpId);
            p.addValue("pid", req.actorId);
            p.addValue("userId", user.getId());
            p.addValue("spjangcd", req.spjangcd);
            this.sqlRunner.execute("""
                INSERT INTO mat_produce_member
                    ("MatProduce_id","Person_id","IsLeader","_status","_created","_creater_id",spjangcd)
                VALUES (:mpId,:pid,'Y','a',now(),:userId,:spjangcd)
                """, p);
        }
    }

    // =====================================================================
    // 4. 투입자재 클린룸 FIFO 차감 (mat_lot_cons + mat_consu + mat_inout out)
    //    화면 편집 BOM 목록을 그대로 소비. (BOM 마스터 없음 → 목록 기반)
    // =====================================================================
    private AjaxResult consumeBomList(MaterialProduce mp, JobRes jr, List<BomInput> bomList,
                                      Integer inStore, User user, String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (bomList == null || bomList.isEmpty()) return r;   // 투입 없이 산출만 하는 공정도 허용

        Timestamp now = DateUtil.getNowTimeStamp();
        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();

        for (BomInput bi : bomList) {
            if (bi.matId == null || bi.qty <= 0) continue;
            Material consMat = this.materialRepository.getMaterialById(bi.matId);
            float need = bi.qty;

            // 클린룸(inStore) FIFO 로트 차감
            MapSqlParameterSource lp = new MapSqlParameterSource();
            lp.addValue("matId", bi.matId);
            lp.addValue("inStore", inStore);
            List<Map<String, Object>> lots = this.sqlRunner.getRows("""
                SELECT ml.id AS ml_id, ml."LotNumber" AS lot_no, ml."CurrentStock" AS cs
                  FROM mat_lot ml
                 WHERE ml."Material_id" = :matId AND ml."StoreHouse_id" = :inStore
                   AND ml."CurrentStock" > 0
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                """, lp);

            float remain = need;
            for (Map<String, Object> lot : lots) {
                if (remain <= 0) break;
                int mlId = ((Number) lot.get("ml_id")).intValue();
                float cs = toF(lot.get("cs"));
                float take = Math.min(cs, remain);
                if (take <= 0) continue;

                MatLotCons mlc = new MatLotCons();
                mlc.setMaterialLotId(mlId);
                mlc.setOutputDateTime(now);
                mlc.setSourceDataPk(mp.getId());
                mlc.setSourceTableName("mat_produce");
                mlc.setCurrentStock(cs);
                mlc.setOutputQty(take);
                mlc.set_audit(user);
                mlc.setSpjangcd(spjangcd);
                this.matLotConsRepository.save(mlc);
                remain -= take;
            }
            if (remain > 0) {
                r.success = false;
                r.message = "클린룸 재고 부족: " + (consMat != null ? consMat.getName() : ("자재" + bi.matId))
                        + " (부족 " + remain + ")";
                return r;
            }

            // mat_consu (투입 실적)
            MaterialConsume mc = new MaterialConsume();
            mc.setJobResponseId(jr.getId());
            mc.setMaterialId(bi.matId);
            mc.setProcessOrder(mp.getProcessOrder());
            mc.setLotIndex(mp.getLotIndex());
            mc.setStartTime(now);
            mc.setEndTime(now);
            mc.setDescription("차수생산분");
            mc.setBomQty(bi.qty);
            mc.setConsumedQty(bi.qty);
            mc.setState("finished");
            mc.set_status("a");
            mc.setStoreHouseId(inStore);
            mc.set_audit(user);
            mc.setSpjangcd(spjangcd);
            mc = this.matConsuRepository.save(mc);

            // mat_inout out
            MaterialInout mic = new MaterialInout();
            mic.setMaterialId(bi.matId);
            mic.setStoreHouseId(inStore);
            mic.setLotNumber(mp.getLotNumber());
            mic.setInoutDate(today);
            mic.setInoutTime(nowT);
            mic.setInOut("out");
            mic.setOutputType("consumed_out");
            mic.setOutputQty(bi.qty);
            mic.setSourceDataPk(mc.getId());
            mic.setSourceTableName("mat_consu");
            mic.setState("confirmed");
            mic.set_status("a");
            mic.setDescription("차수생산 투입재고 차감");
            mic.set_audit(user);
            mic.setSpjangcd(spjangcd);
            this.matInoutRepository.save(mic);
        }
        return r;
    }

    // =====================================================================
    // 5. 산출 반제품 입고 (produceInForChasu 방식 — 클린룸 입고)
    // =====================================================================
    private void produceIn(MaterialProduce mp, Material outMat, Integer outStore,
                           User user, String spjangcd, Timestamp now) {
        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();

        MaterialLot ml = new MaterialLot();
        ml.setLotNumber(mp.getLotNumber());
        ml.setMaterialId(outMat.getId());
        ml.setInputDateTime(now);
        ml.setInputQty(mp.getGoodQty());
        ml.setCurrentStock(mp.getGoodQty());
        ml.setDescription(mp.getLotIndex() + "차수생산");
        ml.setSourceDataPk(mp.getId());
        ml.setSourceTableName("mat_produce");
        ml.setStoreHouseId(outStore);
        ml.set_audit(user);
        ml.setSpjangcd(spjangcd);
        this.matLotRepository.save(ml);

        MaterialInout mip = new MaterialInout();
        mip.setMaterialId(outMat.getId());
        mip.setStoreHouseId(outStore);
        mip.setLotNumber(mp.getLotNumber());
        mip.setInoutDate(today);
        mip.setInoutTime(nowT);
        mip.setInOut("in");
        mip.setInputQty(mp.getGoodQty());
        mip.setInputType("produced_in");
        mip.setSourceDataPk(mp.getId());
        mip.setSourceTableName("mat_produce");
        mip.setState("confirmed");
        mip.set_status("a");
        mip.setDescription("차수생산입고");
        mip.set_audit(user);
        mip.setSpjangcd(spjangcd);
        this.matInoutRepository.save(mip);
    }

    // =====================================================================
    // 유틸
    // =====================================================================
    private Integer resolveProcessStore(Integer wcId) {
        if (wcId == null) return null;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("wcId", wcId);
        Map<String, Object> row = this.sqlRunner.getRow(
                "SELECT \"ProcessStoreHouse_id\" AS sh FROM work_center WHERE id = :wcId", p);
        return (row != null && row.get("sh") != null) ? ((Number) row.get("sh")).intValue() : null;
    }

    private LocalDateTime parseOr(String s, Timestamp fallbackDate, Timestamp now) {
        if (s != null && !s.isBlank()) return LocalDateTime.parse(s, DTM);
        // 작지 생산일 + 현재 시각
        if (fallbackDate != null) {
            return LocalDateTime.of(fallbackDate.toLocalDateTime().toLocalDate(), LocalTime.now().withNano(0));
        }
        return now.toLocalDateTime();
    }

    private float toF(Object o) { return (o == null) ? 0f : Float.parseFloat(String.valueOf(o)); }
}