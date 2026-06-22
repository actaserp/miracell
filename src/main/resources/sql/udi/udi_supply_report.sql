-- =====================================================================
-- UDI 공급내역 보고자료 테이블 (제안)
-- 식약처 UDI OpenAPI V3.4 - 26.공급내역 보고자료 추가 서식 기준
--
-- 납품/반품/폐기/임대/회수를 "SupplyFlagCode" 하나로 구분하며,
-- "ReportState" 로 임시(추가) / 보고확정 / 취소 2단계를 관리한다.
--   t : 임시(보고자료 추가만 됨, 식약처 미보고 상태)
--   r : 보고확정(식약처 보고 및 재보고 처리 완료)
--   c : 보고취소
--
-- ※ 이 DDL은 제안안입니다. 컬럼명/타입은 팀 컨벤션에 맞춰 조정하세요.
-- =====================================================================
CREATE TABLE IF NOT EXISTS udi_supply_report (
    id                    SERIAL PRIMARY KEY,

    "StdMonth"            VARCHAR(6)   NOT NULL,            -- 보고 기준월(YYYYMM) suplyContStdmt
    "SupplyFlagCode"      VARCHAR(1)   NOT NULL,            -- 공급구분 1출고 2반품 3폐기 4임대 5회수
    "SupplyTypeCode"      VARCHAR(1),                       -- 공급형태 1~4 (출고/임대 시 필수)

    -- 품목/식별자
    "MeddevItemSeq"       VARCHAR(20)  NOT NULL,            -- 의료기기 품목 일련번호
    "ModelSeq"            VARCHAR(20)  NOT NULL,            -- 모델명 일련번호(seq)
    "UdiDiSeq"            VARCHAR(20)  NOT NULL,            -- UDI-DI 코드 일련번호
    "StdCode"             VARCHAR(200) NOT NULL,            -- 표준코드
    "UdiDiCode"           VARCHAR(60)  NOT NULL,            -- UDI-DI 코드
    "UdiPiCode"           VARCHAR(100) NOT NULL,            -- UDI-PI 코드

    -- 생산식별자 구성요소(코드 포함 시 필수)
    "LotNo"               VARCHAR(100),                     -- 로트번호
    "ItemSerialNo"        VARCHAR(50),                      -- 일련번호(itemSeq)
    "ManufYm"             VARCHAR(6),                       -- 제조연월 YYMMDD
    "UseTmlmt"            VARCHAR(6),                       -- 사용기한 YYMMDD

    -- 거래처/납품장소(출고·반품 시 필수)
    "BcncCode"            VARCHAR(30),                      -- 거래처 코드
    "IsDiffDvyfg"         BOOLEAN,                          -- 납품장소 다름 여부
    "DvyfgPlaceBcncCode"  VARCHAR(30),                      -- 납품장소 거래처 코드(다름=true 시 필수)

    -- 거래/수량/금액
    "SupplyDate"          VARCHAR(8)   NOT NULL,            -- 출고·반품·폐기 일자 YYYYMMDD
    "SupplyQty"           NUMERIC(18,3) NOT NULL,           -- 수량
    "IndvdlzSupplyQty"    NUMERIC(18,3),                    -- 포장내 낱개 수량
    "SupplyUnitPrice"     NUMERIC(18,2),                    -- 공급단가(출고+요양기관 시 필수)
    "SupplyAmt"           NUMERIC(18,2),                    -- 공급금액(출고+요양기관 시 필수)

    "Remark"              VARCHAR(650),                     -- 비고

    -- 식약처 연동 결과
    "ReportState"         VARCHAR(1)   NOT NULL DEFAULT 't',-- t임시 r보고확정 c취소
    "ReportedAt"          TIMESTAMP,                        -- 보고확정 일시
    "MfdsResultMessage"   VARCHAR(1000),                    -- 식약처 응답 메시지

    -- 감사 컬럼 (mes 공통)
    "_status"             VARCHAR(1),
    "_created"            TIMESTAMP,
    "_modified"           TIMESTAMP,
    "_creater_id"         INTEGER,
    "_modifier_id"        INTEGER
);

CREATE INDEX IF NOT EXISTS ix_udi_supply_report_std
    ON udi_supply_report ("StdMonth", "SupplyFlagCode", "ReportState");
CREATE INDEX IF NOT EXISTS ix_udi_supply_report_date
    ON udi_supply_report ("SupplyDate");
