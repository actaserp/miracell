package mes.app.udi.enums;

import java.util.Arrays;

/**
 * 공급 구분 코드 (suplyFlagCode)
 * 식약처 UDI OpenAPI 가이드 V3.4 - 26.공급내역 보고자료 추가 / 33.보고 현황 목록 조회
 *
 * 납품/반품/폐기 화면은 각각 별도 API가 아니라 이 코드값으로만 구분된다.
 *   납품보고 -> OUT(1)   반품보고 -> RETURN(2)   폐기보고 -> DISPOSAL(3)
 */
public enum SupplyFlagCode {

	OUT("1", "출고"),
	RETURN("2", "반품"),
	DISPOSAL("3", "폐기"),
	LEASE("4", "임대"),
	RECALL("5", "회수");

	private final String code;
	private final String label;

	SupplyFlagCode(String code, String label) {
		this.code = code;
		this.label = label;
	}

	public String code() {
		return code;
	}

	public String label() {
		return label;
	}

	public static SupplyFlagCode fromCode(String code) {
		return Arrays.stream(values())
				.filter(v -> v.code.equals(code))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("알 수 없는 공급구분 코드: " + code));
	}

	/** 거래처(bcncCode), 납품장소다름(isDiffDvyfg)이 필수인 구분 (출고/반품) */
	public boolean requiresBcnc() {
		return this == OUT || this == RETURN;
	}

	/** 공급형태(suplyTypeCode)가 필수인 구분 (출고/임대) */
	public boolean requiresSupplyType() {
		return this == OUT || this == LEASE;
	}
}
