package mes.app.udi.enums;

import java.util.Arrays;

/**
 * 공급 형태 코드 (suplyTypeCode)
 * 식약처 UDI OpenAPI 가이드 V3.4 - 26.공급내역 보고자료 추가
 */
public enum SupplyTypeCode {

	TO_INDUSTRY("1", "제조·수입·판매(임대)에 공급"),
	TO_MEDICAL_INSTITUTION("2", "의료기관에 공급"),
	TO_PHARMACY_OR_WHOLESALER("3", "약국개설자 또는 의약품도매상에 공급"),
	SAMPLE_DONATION_MILITARY("4", "견본품, 기부용 또는 군납용 등으로 공급");

	private final String code;
	private final String label;

	SupplyTypeCode(String code, String label) {
		this.code = code;
		this.label = label;
	}

	public String code() {
		return code;
	}

	public String label() {
		return label;
	}

	public static SupplyTypeCode fromCode(String code) {
		return Arrays.stream(values())
				.filter(v -> v.code.equals(code))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("알 수 없는 공급형태 코드: " + code));
	}
}
