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
        public Integer cleanStore;        // 투입 소스창고(클린룸=5). 공정/화면별로 지정. null → 5
        public String spjangcd;
        public String  lotNumber;
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
        String lotNumber = (req.lotNumber != null && !req.lotNumber.isBlank())
                ? req.lotNumber
                : this.lotService.make_production_lot_in_number("W");

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
                ? parseTimeFlexible(req.startTime, mp.getStartTime() != null
                ? mp.getStartTime().toLocalDateTime() : now.toLocalDateTime())
                : (mp.getStartTime() != null ? mp.getStartTime().toLocalDateTime() : now.toLocalDateTime());
        LocalDateTime endLdt = (req.endTime != null && !req.endTime.isBlank())
                ? parseTimeFlexible(req.endTime, startLdt) : now.toLocalDateTime();
        // 날짜+시각 전체로 비교 (시각만이 아니라 날짜까지). 시각만 들어온 경우 위에서 기준일을 붙임.
        if (endLdt.isBefore(startLdt)) { r.success = false; r.message = "종료시각이 시작시각보다 빠릅니다."; return r; }

        // ── 수량 확정 (consumeBomForChasu 가 GoodQty 로 소요량 계산하므로 먼저 저장) ──
        mp.setGoodQty(req.goodQty);
        mp.setDefectQty(req.defectQty);
        mp.setStartTime(Timestamp.valueOf(startLdt));
        mp.setEndTime(Timestamp.valueOf(endLdt));
        mp.set_audit(user);
        mp = this.matProduceRepository.save(mp);

        // ── 투입자재 소비 = 클린룸(inStore) FIFO 차감 (화면 편집 BOM = 용기 반제품 + 부가자재) ──
        //   외부 consumeBomForChasu 는 자재 기본창고에서 빼는 문제가 있어, 소스창고(클린룸)를
        //   명시하는 로컬 consumeBomList 를 사용한다. 예약(mat_proc_input)은 그대로 두어(soft hold)
        //   완료취소 시 '리퀘스트' 상태가 유지되도록 한다.
        Integer inStore = (req.cleanStore != null) ? req.cleanStore : 5;
        AjaxResult consume = consumeBomList(mp, jr, req.bomList, inStore, user, req.spjangcd);
        if (!consume.success) { throw new IllegalStateException(consume.message); }  // 롤백

        // ── 산출 반제품 입고 (산출창고 = work_center.ProcessStoreHouse_id) ──
        Material outMat = this.materialRepository.getMaterialById(mp.getMaterialId());
        produceIn(mp, outMat, outStoreId, user, req.spjangcd, now);

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

        // ── 작지 롤업 (★ 이게 없으면 생산실적현황·작업일보에 0건으로 나온다) ──
        //   jr."GoodQty"/"DefectQty" 합계 갱신 + 지시량 충족 시 State='finished'.
        //   ProdResultListService 두 쿼리 모두 jr."State"='finished' 를 요구한다.
        recalcJobRes(jr.getId(), user);

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
        String lotNumber = (req.lotNumber != null && !req.lotNumber.isBlank())
                ? req.lotNumber
                : this.lotService.make_production_lot_in_number("W");

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
        // 소스창고는 자재별로 resolveSourceStore 로 결정(아래 루프). cleanStore 파라미터는 미사용(호환 유지).

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

            // 자재 속성으로 소스창고 결정(멸균/반제품·세척=클린룸 / 그 외=생산창고)
            Integer store = resolveSourceStore(bi.matId);
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

        // 산출품목(용기/PK 등) 이름 — mat_inout 비고에 "{산출품목} 차수생산 투입재고 차감" 형태로 표기
        Material outMatForDesc = this.materialRepository.getMaterialById(mp.getMaterialId());
        String outName = (outMatForDesc != null && outMatForDesc.getName() != null)
                ? outMatForDesc.getName() : ("품목" + mp.getMaterialId());

        for (BomInput bi : bomList) {
            if (bi.matId == null || bi.qty <= 0) continue;
            Material consMat = this.materialRepository.getMaterialById(bi.matId);
            float need = bi.qty;

            // 자재 속성으로 소스창고 결정(멸균18 / 반제품·세척=클린룸5 / 그 외 생산창고17)
            Integer store = resolveSourceStore(bi.matId);
            MapSqlParameterSource lp = new MapSqlParameterSource();
            lp.addValue("matId", bi.matId);
            lp.addValue("inStore", store);
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
                r.message = "재고 부족(창고 " + store + "): " + (consMat != null ? consMat.getName() : ("자재" + bi.matId))
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
            mc.setStoreHouseId(store);
            mc.set_audit(user);
            mc.setSpjangcd(spjangcd);
            mc = this.matConsuRepository.save(mc);

            // mat_inout out
            MaterialInout mic = new MaterialInout();
            mic.setMaterialId(bi.matId);
            mic.setStoreHouseId(store);
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
            mic.setDescription(outName + " 차수생산 투입재고 차감");
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

    /**
     * 자재별 투입 소스창고 판별(2번안). 소비/예약이 이 리졸버 하나만 보게 격리한다.
     *   SterilizationYN='Y'            → 18 (멸균창고: 포장의 PK·필터팩)
     *   MaterialType IN (semi,product) → 5  (클린룸: 조립·블리스터 산출 반제품 = 용기·PK)
     *   WashYN='Y'                     → 5  (클린룸: 세척 용기 부품)
     *   그 외(raw 구매 부자재·CK)        → 17 (생산창고)
     * ※ 나중에 1번(불출 화면으로 부자재도 클린룸화)로 전환 시, 마지막 줄만 17→5 로 바꾸면 됨.
     */
    private Integer resolveSourceStore(Integer matId) {
        if (matId == null) return 17;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("matId", matId);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT (CASE
                             -- 2공장(M-CELL) : 클린룸/멸균 개념 없음
                             WHEN COALESCE(m."Factory_id",1) = 2 THEN
                                  (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19   -- 검사완료창고
                                        ELSE 17 END)                                   -- 생산창고
                             -- 1공장(키트) : 기존 규칙 유지
                             WHEN COALESCE(m."SterilizationYN",'N')='Y'   THEN 18
                             WHEN mg."MaterialType" IN ('semi','product') THEN 5
                             WHEN COALESCE(m."WashYN",'N')='Y'            THEN 5
                             ELSE 17 END) AS store
                  FROM material m
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                 WHERE m.id = :matId
                """, p);
        return (row != null && row.get("store") != null) ? ((Number) row.get("store")).intValue() : 17;
    }

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

    /**
     * 'yyyy-MM-dd HH:mm' 은 그대로 파싱. 'HH:mm' 처럼 시각만 오면 기준(base)의 '날짜' 에 그 시각을 결합한다.
     * → 종료/시작 비교가 날짜 없이 시각만으로 이뤄지는 것을 방지(자정 넘김 등 오탐 방지).
     */
    private LocalDateTime parseTimeFlexible(String s, LocalDateTime base) {
        String v = s.trim();
        try {
            return LocalDateTime.parse(v, DTM);            // 날짜+시각 완전형
        } catch (Exception ignore) {
            try {
                return LocalDateTime.of(base.toLocalDate(), LocalTime.parse(v));  // 시각만 → 기준일 날짜 결합
            } catch (Exception ignore2) {
                return base;                                // 파싱 불가 시 기준값 사용(오류 대신 안전)
            }
        }
    }

    // =====================================================================
//  ★ 공용 파일 패치 5·6 — ProductionCreateService.java 에 그대로 추가
//     (기존 4곳 패치에 이어지는 것. 클래스 안 아무 곳에나, produceIn 아래 권장)
//
//  왜 여기에 두는가
//    수리는 "특정 로트 1건을 지정해서 소비" 하고 "생산 아닌 입고" 를 한다.
//    둘 다 mat_lot_cons / mat_consu / mat_lot / mat_inout 을 건드리는데,
//    이걸 화면 서비스에서 raw SQL 로 쓰면 §5 함정(InputQty/OutputQty)을
//    또 재현한다. 엔티티를 쓰는 공용 메서드로 내려 한 곳에서만 틀리게 한다.
//    1공장 포장(PK+CK 결합)도 "지정 로트 소비" 라서 그대로 재사용된다.
// =====================================================================

    /**
     * 패치 5 — 지정 로트 직접 소비.
     *
     * consumeBomList 는 자재 id 로 FIFO 를 돈다. 하지만 수리의 '원 M-CELL 로트 투입'처럼
     * 소비 대상이 특정 로트 1건으로 이미 확정된 경우가 있다. 그때 이 메서드를 쓴다.
     *
     * ★ resolveSourceStore 를 타지 않는다. 창고는 로트 행이 들고 있는 값을 그대로 쓴다.
     *   (수리 대상은 InspectYN='Y' 라 resolveSourceStore 가 19 로 오판한다. 그 함정 회피)
     *
     * mat_lot_cons 의 SourceTableName/SourceDataPk 를 'mat_produce'/mpId 로 남기므로,
     * 롤백은 기존 rollbackProduce 와 똑같은 한 줄(DELETE ... SourceDataPk=:mpId)로 정리된다.
     *
     * @param mpId      startProduction 이 만든 mat_produce id (아직 finishProduction 전)
     * @param matLotId  소비할 mat_lot.id
     * @param qty       소비 수량
     */
    @Transactional
    public AjaxResult consumeLot(Integer mpId, Integer matLotId, float qty,
                                 User user, String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (matLotId == null || qty <= 0) {
            r.success = false; r.message = "소비할 로트/수량이 없습니다."; return r;
        }

        MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
        if (mp == null) { r.success = false; r.message = "생산 차수를 찾을 수 없습니다."; return r; }
        JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", matLotId);
        Map<String, Object> lot = this.sqlRunner.getRow("""
            SELECT ml.id, ml."Material_id" AS mat_id, ml."StoreHouse_id" AS store,
                   ml."LotNumber" AS lot_no, COALESCE(ml."CurrentStock",0) AS cs
              FROM mat_lot ml WHERE ml.id = :id
            """, p);
        if (lot == null) { r.success = false; r.message = "로트를 찾을 수 없습니다. (mat_lot " + matLotId + ")"; return r; }

        float cs = toF(lot.get("cs"));
        if (cs < qty) {
            r.success = false;
            r.message = "로트 재고 부족: " + lot.get("lot_no") + " (보유 " + fmtQty(cs) + " / 필요 " + fmtQty(qty) + ")";
            return r;
        }

        Integer matId = ((Number) lot.get("mat_id")).intValue();
        Integer store = ((Number) lot.get("store")).intValue();
        Timestamp now = DateUtil.getNowTimeStamp();
        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();

        // 1) mat_lot_cons — 트리거가 mat_lot.CurrentStock 을 깎는다
        MatLotCons mlc = new MatLotCons();
        mlc.setMaterialLotId(matLotId);
        mlc.setOutputDateTime(now);
        mlc.setSourceDataPk(mp.getId());
        mlc.setSourceTableName("mat_produce");
        mlc.setCurrentStock(cs);
        mlc.setOutputQty(qty);
        mlc.set_audit(user);
        mlc.setSpjangcd(spjangcd);
        this.matLotConsRepository.save(mlc);

        // 2) mat_consu — 투입 실적
        MaterialConsume mc = new MaterialConsume();
        mc.setJobResponseId(jr.getId());
        mc.setMaterialId(matId);
        mc.setProcessOrder(mp.getProcessOrder());
        mc.setLotIndex(mp.getLotIndex());
        mc.setStartTime(now);
        mc.setEndTime(now);
        mc.setDescription("지정로트 투입 " + lot.get("lot_no"));
        mc.setBomQty(qty);
        mc.setConsumedQty(qty);
        mc.setState("finished");
        mc.set_status("a");
        mc.setStoreHouseId(store);
        mc.set_audit(user);
        mc.setSpjangcd(spjangcd);
        mc = this.matConsuRepository.save(mc);

        // 3) mat_inout out  ★ out 은 OutputQty (§5)
        MaterialInout mio = new MaterialInout();
        mio.setMaterialId(matId);
        mio.setStoreHouseId(store);
        mio.setLotNumber((String) lot.get("lot_no"));
        mio.setInoutDate(today);
        mio.setInoutTime(nowT);
        mio.setInOut("out");
        mio.setOutputType("consumed_out");
        mio.setOutputQty(qty);
        mio.setSourceDataPk(mc.getId());
        mio.setSourceTableName("mat_consu");
        mio.setState("confirmed");
        mio.set_status("a");
        mio.setDescription("지정로트 투입 차감");
        mio.set_audit(user);
        mio.setSpjangcd(spjangcd);
        this.matInoutRepository.save(mio);

        r.data = Map.of("mat_lot_id", matLotId, "lot_number", String.valueOf(lot.get("lot_no")),
                "material_id", matId, "qty", qty);
        return r;
    }


    /**
     * 패치 6 — 생산이 아닌 입고(로트 신규 생성).
     *
     * 쓰는 곳
     *   · 수리 접수 : 이미 출하되어 재고가 0 인 반품품을 수리창고로 되받는다
     *   · 수리 완료 : 뜯어낸 부품(−회수)을 생산창고(17)로 되돌린다
     *
     * mat_produce 를 만들지 않는다 = 생산 수량에 잡히지 않는다.
     * SourceTableName/SourceDataPk 를 호출자가 지정하므로 롤백 시 정확히 되짚을 수 있다.
     *
     * @return data.mat_lot_id
     */
    @Transactional
    public AjaxResult receiveLot(Integer matId, String lotNumber, String makerLotNo, float qty,
                                 Integer storeId, String srcTable, Integer srcPk,
                                 String memo, User user, String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (matId == null || qty <= 0 || storeId == null) {
            r.success = false; r.message = "입고 품목/수량/창고가 없습니다."; return r;
        }
        Material m = this.materialRepository.getMaterialById(matId);
        if (m == null) { r.success = false; r.message = "품목을 찾을 수 없습니다. (" + matId + ")"; return r; }

        Timestamp now = DateUtil.getNowTimeStamp();
        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();

        MaterialLot ml = new MaterialLot();
        ml.setLotNumber(lotNumber);
        ml.setMaterialId(matId);
        ml.setInputDateTime(now);
        ml.setInputQty(qty);
        ml.setCurrentStock(qty);
        ml.setDescription(memo);
        ml.setSourceDataPk(srcPk);
        ml.setSourceTableName(srcTable);
        ml.setStoreHouseId(storeId);
        ml.set_audit(user);
        ml.setSpjangcd(spjangcd);
        ml = this.matLotRepository.save(ml);

        // MakerLotNo 는 엔티티에 없을 수 있어 별도 UPDATE (db_setup_repair.sql 에서 추가한 컬럼)
        if (makerLotNo != null && !makerLotNo.isBlank()) {
            this.sqlRunner.execute("UPDATE mat_lot SET \"MakerLotNo\"=:mk WHERE id=:id",
                    new MapSqlParameterSource().addValue("mk", makerLotNo).addValue("id", ml.getId()));
        }

        // ★ in 은 InputQty (§5)
        MaterialInout mio = new MaterialInout();
        mio.setMaterialId(matId);
        mio.setStoreHouseId(storeId);
        mio.setLotNumber(lotNumber);
        mio.setInoutDate(today);
        mio.setInoutTime(nowT);
        mio.setInOut("in");
        mio.setInputQty(qty);
        mio.setInputType("etc_in");
        mio.setSourceDataPk(ml.getId());
        mio.setSourceTableName("mat_lot");
        mio.setState("confirmed");
        mio.set_status("a");
        mio.setDescription(memo);
        mio.set_audit(user);
        mio.setSpjangcd(spjangcd);
        this.matInoutRepository.save(mio);

        r.data = Map.of("mat_lot_id", ml.getId(), "lot_number", String.valueOf(lotNumber));
        return r;
    }

    /**
     * 패치 7 — 이미 만들어진 차수에 자재를 더 소비한다.
     *
     * 왜 필요한가
     *   수리가 검사에서 걸리면 자재를 더 넣어 계속 손본다. 실물은 갈라지지 않으므로
     *   새 차수를 만들거나 기존 차수를 되돌리는 게 아니라, 그 차수에 소비를 더 얹는 게 맞다.
     *   finishProduction 은 산출 입고까지 함께 하므로 재사용할 수 없고,
     *   consumeBomList 는 private 이라 밖에서 못 부른다. 그 사이를 여는 얇은 창구.
     *
     * 1공장 포장에서 자재를 나중에 보강할 때도 그대로 쓸 수 있다.
     */
    @Transactional
    public AjaxResult consumeAdditional(Integer mpId, List<BomInput> bomList,
                                        Integer cleanStore, User user, String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (bomList == null || bomList.isEmpty()) return r;

        MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
        if (mp == null) {
            r.success = false;
            r.message = "생산 차수를 찾을 수 없습니다.";
            return r;
        }
        JobRes jr = this.jobResRepository.getJobResById(mp.getJobResponseId());

        return consumeBomList(mp, jr, bomList, cleanStore, user, spjangcd);
    }

    // =====================================================================
    // 작지 롤업 — mat_produce 실적을 job_res 로 되올린다.
    //
    // ★ 이 호출이 없으면 job_res."State" 가 'working' 에 영구히 남아
    //   ProdResultListService(생산실적현황·작업일보)에서 0건으로 나온다.
    //   v3 문서의 "손대지 않음 = 자동 노출" 은 mat_produce 만으로는 성립하지 않는다.
    //
    // 판정 축이 두 가지다.
    //   1공장 (조립·블리스터·융착·포장) : 차수 합계가 지시량을 채우면 완료
    //   2공장 (M-CELL 조립·수리)        : mcell_unit 이 진행률의 진실
    //
    // ★ 2공장을 1공장 규칙으로 판정하면 안 된다.
    //   유닛 5대 작지에서 1대만 완료해도 그 시점엔 mat_produce 가 1건뿐이라
    //   "미완료 차수 없음"이 성립해 작지가 조기에 finished 로 닫힌다.
    //   검사 wo_queue 가 같은 함정에 빠졌던 것과 동일한 구조다.
    // =====================================================================
    public void recalcJobRes(Integer jrId, User user) {
        if (jrId == null) return;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jrId);
        Map<String, Object> u = this.sqlRunner.getRow("""
                SELECT COUNT(*)                                   AS unit_cnt
                     , COUNT(*) FILTER (WHERE "State" = 'packed')  AS packed_cnt
                  FROM mcell_unit
                 WHERE "JobResponse_id" = :jrId
                """, p);

        int unitCnt = (u == null || u.get("unit_cnt") == null)
                ? 0 : ((Number) u.get("unit_cnt")).intValue();

        if (unitCnt == 0) {
            // 1공장 — 기존 롤업 그대로 재사용
            this.productionResultService.recalcJobResAndCheckComplete(jrId, user);
            patchJobResState(jrId, user);
            return;
        }

        int packedCnt = (u.get("packed_cnt") == null)
                ? 0 : ((Number) u.get("packed_cnt")).intValue();
        recalcJobResByUnits(jrId, unitCnt, packedCnt, user);
    }

    /**
     * 유닛 기반 작지(M-CELL) 롤업.
     * 수량 합계는 mat_produce 에서 가져오되, 완료 판정만 mcell_unit 으로 한다.
     *
     * ★ 완료 기준을 'packed' 로 둔 이유 — 포장까지 끝나야 완제품이다.
     *   포장(mc03)이 아직 미구현이라 지금은 M-CELL 작지가 finished 로 닫히지 않는다.
     *   = 생산실적현황에 M-CELL 이 안 뜬다. 버그가 아니라 공정이 안 끝난 것이다.
     *   포장 전에도 보고 싶으면 아래 'packed' 를 'pass' 로 바꾼다 — 다만 그러면
     *   "포장 안 된 것도 생산 완료"가 되어 제품창고(4) 재고와 의미가 어긋난다.
     *
     * 1공장 롤업과 달리 **되돌리기도 한다.** 분해·재작업으로 유닛이 packed 에서
     * 빠지면 작지도 working 으로 열어준다. 안 하면 취소했는데 실적에 계속 남는다.
     */
    private void recalcJobResByUnits(Integer jrId, int unitCnt, int packedCnt, User user) {
        JobRes jr = this.jobResRepository.getJobResById(jrId);
        if (jr == null) return;

        Map<String, Object> sum = this.productionResultService.getJobResponseGoodDefectQty(jrId);
        jr.setGoodQty(toF(sum == null ? null : sum.get("good_qty")));
        jr.setDefectQty(toF(sum == null ? null : sum.get("defect_qty")));

        boolean complete = (packedCnt >= unitCnt);
        if (complete) {
            jr.setState("finished");
            jr.setEndTime(DateUtil.getNowTimeStamp());
        } else if ("finished".equals(jr.getState())) {
            jr.setState("working");
            jr.setEndTime(null);
        }
        jr.set_audit(user);
        this.jobResRepository.save(jr);
    }

    /**
     * 기존 롤업(recalcJobResAndCheckComplete)이 못 덮는 두 경우를 보정한다.
     * 그 메서드는 다른 화면도 쓰므로 직접 고치지 않고 여기서 후처리한다.
     *
     * 1) ordered 인데 차수가 있다
     *    「담기」(addWaitItem) 는 mp 만 만들고 jr 을 working 으로 올리지 않는다.
     *    ordered → working 전환은 startProduction 에만 있어서,
     *    담기 → 완료 순으로 작업하면 작지가 영구히 ordered 로 남는다.
     *
     * 2) OrderQty <= 0 인 작지
     *    기존 판정이 `orderQty > 0` 를 요구해 아무리 생산해도 finished 가 안 된다.
     *    지시량이 없는 작지는 "모든 차수가 끝났으면 끝난 것"으로 본다.
     */
    private void patchJobResState(Integer jrId, User user) {
        JobRes jr = this.jobResRepository.getJobResById(jrId);
        if (jr == null || "finished".equals(jr.getState())) return;

        Map<String, Object> c = this.sqlRunner.getRow("""
                SELECT COUNT(*)                                        AS cnt
                     , COUNT(*) FILTER (WHERE "State" <> 'finished')    AS open_cnt
                  FROM mat_produce
                 WHERE "JobResponse_id" = :jrId
                   AND COALESCE(_status, 'a') = 'a'
                """, new MapSqlParameterSource().addValue("jrId", jrId));

        int cnt     = (c == null || c.get("cnt") == null) ? 0 : ((Number) c.get("cnt")).intValue();
        int openCnt = (c == null || c.get("open_cnt") == null) ? 0 : ((Number) c.get("open_cnt")).intValue();
        if (cnt == 0) return;

        float orderQty = (jr.getOrderQty() == null) ? 0f : jr.getOrderQty().floatValue();

        if (orderQty <= 0 && openCnt == 0) {
            jr.setState("finished");
            jr.setEndTime(DateUtil.getNowTimeStamp());
        } else if ("ordered".equals(jr.getState())) {
            jr.setState("working");
        } else {
            return;                       // 바뀔 것 없음 — 저장하지 않는다
        }
        jr.set_audit(user);
        this.jobResRepository.save(jr);
    }
}