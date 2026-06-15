package mes.app.balju;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.client.j2se.MatrixToImageWriter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public class BarcodeUtil {

	// ZXing Code128Writer 에서 FNC1 을 의미하는 제어 문자 (ASCII 241)
	private static final char FNC1 = '\u00f1';

	/**
	 * GS1-128 형식의 raw 문자열을 CODE128 바코드 PNG 바이트로 변환.
	 *
	 * @param gs1Data  AI 가 적용된 GS1 데이터.
	 *                 - 괄호 없는 순수 데이터로 전달 (예: "01088012345...1012345...11250601")
	 *                 - 첫 FNC1 + 가변길이 AI 뒤 FNC1 구분자는 호출부에서 buildGs1Raw() 로 구성
	 * @param widthPx  바코드 이미지 가로 px
	 * @param heightPx 바코드 이미지 세로 px
	 */
	public static byte[] createCode128Png(String gs1Data, int widthPx, int heightPx) throws Exception {
		Code128Writer writer = new Code128Writer();

		Map<EncodeHintType, Object> hints = new HashMap<>();
		hints.put(EncodeHintType.MARGIN, 2); // 좌우 여백(quiet zone)

		BitMatrix matrix = writer.encode(gs1Data, BarcodeFormat.CODE_128, widthPx, heightPx, hints);

		BufferedImage img = MatrixToImageWriter.toBufferedImage(matrix);
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			javax.imageio.ImageIO.write(img, "png", baos);
			return baos.toByteArray();
		}
	}

	/**
	 * AI 조각들을 받아 ZXing 인코딩용 GS1 raw 문자열 구성.
	 * GS1-128 규칙:
	 *  - 맨 앞에 FNC1 (GS1 시작 식별)
	 *  - 고정길이 AI(01,11) 뒤에는 구분자 불필요
	 *  - 가변길이 AI(10=로트) 뒤에 다음 AI 가 오면 FNC1 구분자 필요
	 *
	 * 여기서는 (01)GTIN-14 → (10)로트 → (11)제조일자 순서로 가정.
	 * (10)이 가변이라 그 뒤(=11 앞)에 FNC1 을 넣는다.
	 *
	 * @param gtin14   14자리 GTIN (placeholder: 880+회사+제품+CD)
	 * @param lot      로트번호 (가변, null/empty 가능)
	 * @param mfgDate  제조일자 YYMMDD (null/empty 가능)
	 */
	public static String buildGs1Raw(String gtin14, String lot, String mfgDate) {
		StringBuilder sb = new StringBuilder();
		sb.append(FNC1);                 // GS1 시작
		sb.append("01").append(gtin14);  // (01) 고정 14자리

		if (lot != null && !lot.isEmpty()) {
			sb.append("10").append(lot); // (10) 로트 (가변)
			// 뒤에 또 다른 AI 가 오면 FNC1 구분자 삽입
			if (mfgDate != null && !mfgDate.isEmpty()) {
				sb.append(FNC1);
			}
		}
		if (mfgDate != null && !mfgDate.isEmpty()) {
			sb.append("11").append(mfgDate); // (11) 제조일자 고정 6자리
		}
		return sb.toString();
	}

	/** 사람이 읽는 코드값 표기: (01)... (10)... (11)... */
	public static String buildHumanReadable(String gtin14, String lot, String mfgDate) {
		StringBuilder sb = new StringBuilder();
		sb.append("(01)").append(gtin14);
		if (lot != null && !lot.isEmpty())     sb.append("(10)").append(lot);
		if (mfgDate != null && !mfgDate.isEmpty()) sb.append("(11)").append(mfgDate);
		return sb.toString();
	}
}
