# 공정별 생산실적 화면 - 파일 구성 및 적용 가이드

## 파일 구조

```
prod_process/
├── README.md                          ← 이 파일
├── BACKEND_GUIDE.java                 ← 백엔드 추가 필요사항 (API, 메뉴, 라우팅)
├── fragments/
│   └── prod_process_common.html       ← 공용 프래그먼트 (CSS, 탭, JS 베이스클래스)
├── prod_process_default.html          ← 기본 공정 페이지 (레이저/와이어/선반/측정/조립)
└── prod_process_machining.html        ← 머시닝 전용 페이지 (PLC 모니터링 탭 추가)
```

## 구조 설명

### 공용 프래그먼트 (`prod_process_common.html`)
- **processStyles**: 반응형 CSS (데스크탑/태블릿/모바일/키오스크)
- **tabConsumed**: 투입내역 탭 HTML
- **tabDefect**: 부적합 탭 HTML
- **processCommonJS**: `ProcPageBase` 클래스 (공통 로직)
  - 메인 그리드 (공정 필터 + step 게이팅)
  - 시작/종료/일시중지/완료취소
  - 차수 자동 생성 (완료 시 지시량 = 양품량)
  - 투입/부적합/차수 탭 데이터 로드

### 기본 공정 페이지 (`prod_process_default.html`)
- URL: `/production/prod_process_default?processCode=p01`
- `ProcDefaultPage extends ProcPageBase`
- 공정코드에 따라 테마 색상/아이콘/이름 자동 변경
- 레이저/와이어컷/선반/측정/조립 등 특화 없는 공정에 사용

### 머시닝 전용 (`prod_process_machining.html`)
- URL: `/production/prod_process_machining`
- `ProcMachiningPage extends ProcPageBase`
- 추가 탭: PLC 실시간 모니터링 (RPM, 온도, 부하, 가동률)
- 설비 선택 시 5초 간격 PLC 폴링 시작
- PLC API: `/api/equipment/plc/realtime?equ_id=XX` (나중에 구현)

## 기존 대비 변경점

| 항목 | 기존 (prod_result_lot) | 신규 (prod_process) |
|------|----------------------|-------------------|
| 공정 선택 | 드롭다운으로 공정 변경 | URL 파라미터로 고정 |
| 작업자 | shift(근무조) 선택 | person(작업자) 드롭다운 |
| 차수 | 수동 추가/관리 | 완료 시 자동 생성 (지시량=양품량) |
| 그리드 | 전체 작업지시 표시 | 해당 공정 row만 표시 |
| step 게이팅 | 없음 | 전공정 미완료 시 locked 표시 |
| consumed_mode | PLAN/ACTUAL 전환 | ACTUAL 고정 (row 미리 생성) |
| 반응형 | 부분적 | 풀 반응형 (모바일/태블릿/키오스크) |
| PLC | 없음 | 머시닝 페이지에 실시간 모니터링 |

## 적용 순서

1. `fragments/prod_process_common.html` → 프로젝트 templates/fragments/ 에 배치
2. `prod_process_default.html` → templates/production/ 에 배치
3. `prod_process_machining.html` → templates/production/ 에 배치
4. BACKEND_GUIDE.java 참고하여:
   - `read_by_process` API 엔드포인트 추가
   - `person_list` SelectOption 추가
   - Controller 라우팅 추가
   - 메뉴 등록
5. 테스트: 작업지시 생성 → 공정별 job_res row 확인 → 공정 페이지에서 시작/완료

## 나중에 공정별 전용 페이지 추가 시

예: 조립 전용 (체크리스트 추가)
1. `prod_process_assembly.html` 생성
2. `ProcAssemblyPage extends ProcPageBase` 에서 조립 전용 로직 추가
3. 메뉴 URL 변경: `/production/prod_process_assembly`
4. 공통 로직은 건드리지 않음
