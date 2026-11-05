package mes.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 윈도우·리눅스 공용 파일 경로.
 *
 * 기준 폴더 = BASE_PATH
 *   1) JVM 시스템 프로퍼티 (DotenvEnvironmentPostProcessor 가 server.env / .env 값을 넣어 둔다)
 *   2) OS 환경변수
 *   3) 없으면 OS 기본값 — 윈도우 C:/Temp/mes21, 리눅스 /opt/mes21
 *
 * ★ 예전 코드의 "C:/Temp/mes21/..." 하드코딩을 이걸로 바꾼다.
 *   윈도우는 BASE_PATH=C:\Temp\mes21 이라 경로가 그대로이고,
 *   리눅스는 /opt/mes21 아래 같은 구조로 두면 된다.
 */
public final class MesPaths {

    private MesPaths() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static String base() {
        String p = System.getProperty("BASE_PATH");
        if (p == null || p.isBlank()) p = System.getenv("BASE_PATH");
        if (p == null || p.isBlank()) p = isWindows() ? "C:/Temp/mes21" : "/opt/mes21";
        return p.trim();
    }

    /** base() 아래 하위 경로. 예: MesPaths.path("문서", "VacDemoFile.xlsx") */
    public static Path path(String... more) {
        return Paths.get(base(), more);
    }
}