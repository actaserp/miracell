package mes.app.production;

import mes.app.production.service.McellDocService;
import mes.config.Settings;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * M-CELL 제조문서 (2공장) — 문서 데이터 · 엑셀 양식 · 첨부.
 *
 * 엑셀 양식 위치 : src/main/resources/static/mcell_doc/{M2|M4|M4A}/{F705-33|F803-6|F803-7|F803-8}.xlsx
 *   화면(mcell_doc.js)이 /template 로 받아 값을 채워 내려받게 한다(서식·그림 그대로).
 * 첨부 저장 : file_upload_path + mcell_doc/{unitId}/
 */
@RestController
@RequestMapping("/api/production/mcell/doc")
public class McellDocController {

    private static final Logger log = LoggerFactory.getLogger(McellDocController.class);
    private static final String DOC_DIR = "mcell_doc";
    private static final Set<String> MODELS = Set.of("M2", "M4", "M4A");
    private static final long MAX_SIZE = 30L * 1024 * 1024;   // 30MB

    @Autowired private McellDocService mcellDocService;
    @Autowired private Settings settings;

    private Path uploadRoot() {
        String root = settings.getProperty("file_upload_path");
        if (root == null || root.isBlank())
            throw new IllegalStateException("file_upload_path 가 설정되지 않았습니다. application.properties 를 확인하세요.");
        return Paths.get(root.trim(), DOC_DIR);
    }

    /** 문서 값 — unit_id 또는 lot_number */
    @GetMapping("/data")
    public AjaxResult data(@RequestParam(value = "unit_id", required = false) Integer unitId,
                           @RequestParam(value = "lot_number", required = false) String lotNumber) {
        AjaxResult r = new AjaxResult();
        Integer uid = this.mcellDocService.resolveUnit(unitId, lotNumber);
        if (uid == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        r.data = this.mcellDocService.getUnitDoc(uid);
        return r;
    }

    /** 엑셀 양식 원본 */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam("model") String model, @RequestParam("doc") String doc,
                                           @RequestParam(value = "ver_id", required = false) Integer verId) {
        if (!MODELS.contains(model) || !McellDocService.DOCS.contains(doc)) return ResponseEntity.badRequest().build();
        MediaType xlsx = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // ⓪ 예전 버전 — 그 버전에 보관한 파일 (최신 버전은 아래 지금 파일)
        if (verId != null) {
            try {
                Map<String, Object> v = this.mcellDocService.getVersion(verId);
                if (v != null && v.get("map_json") != null && v.get("file_path") != null
                        && model.equals(v.get("model")) && doc.equals(v.get("doc_code"))) {
                    Path f = tplRoot().resolve(String.valueOf(v.get("file_path"))).normalize();
                    if (f.startsWith(tplRoot()) && Files.exists(f))
                        return ResponseEntity.ok().contentType(xlsx).body(Files.readAllBytes(f));
                    log.warn("[mcell/doc] 버전 파일 없음 ver={} {} — 지금 양식으로 대신 냅니다", verId, f);
                }
            } catch (Exception e) { log.warn("[mcell/doc] 버전 조회 실패 ver={}", verId, e); }
        }
        // ① 서버 폴더 : file_upload_path/mcell_doc_tpl/{모델}/{문서}.xlsx  — 재배포 없이 양식 교체 가능
        try {
            String root = settings.getProperty("file_upload_path");
            if (root != null && !root.isBlank()) {
                Path f = Paths.get(root.trim(), "mcell_doc_tpl", model, doc + ".xlsx");
                if (Files.exists(f)) return ResponseEntity.ok().contentType(xlsx).body(Files.readAllBytes(f));
            }
        } catch (Exception ignore) {}
        // ② 프로젝트 안 : src/main/resources/static/mcell_doc/{모델}/{문서}.xlsx
        try (InputStream in = new ClassPathResource("static/" + DOC_DIR + "/" + model + "/" + doc + ".xlsx").getInputStream()) {
            return ResponseEntity.ok().contentType(xlsx).body(in.readAllBytes());
        } catch (Exception e) {
            log.warn("[mcell/doc] 양식 없음 {}/{} — file_upload_path/mcell_doc_tpl/ 또는 resources/static/mcell_doc/ 에 넣으세요", model, doc);
            return ResponseEntity.notFound().build();
        }
    }

    private Path tplRoot() {
        String root = settings.getProperty("file_upload_path");
        if (root == null || root.isBlank()) throw new IllegalStateException("file_upload_path 가 설정되지 않았습니다.");
        return Paths.get(root.trim(), "mcell_doc_tpl").toAbsolutePath().normalize();
    }

    /** 지금 쓰는 양식 파일 바이트 (서버 폴더 → 프로젝트 안) */
    private byte[] currentTemplate(String model, String doc) {
        try {
            Path f = tplRoot().resolve(model).resolve(doc + ".xlsx");
            if (Files.exists(f)) return Files.readAllBytes(f);
        } catch (Exception ignore) {}
        try (InputStream in = new ClassPathResource("static/" + DOC_DIR + "/" + model + "/" + doc + ".xlsx").getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) { return null; }
    }

    /**
     * ★ 엑셀 양식 파일 교체 = 새 버전 등록.
     *   1) 지금 버전의 양식 파일을 mcell_doc_tpl/{모델}/v/{문서}_{버전id}.xlsx 에 보관하고, 지금 칸 매핑을 그 버전에 얼려 둔다
     *   2) 새 파일을 지금 양식(mcell_doc_tpl/{모델}/{문서}.xlsx)으로 쓰고 새 Rev 를 만든다
     *   적용일(effective, 기본 오늘) 이후 조립 시작한 유닛부터 새 양식 · 그 전 유닛은 예전 양식 그대로.
     *   model=ALL 이면 M2·M4·M4A 모두 (모델마다 Rev 따로).
     */
    @PostMapping("/template_upload")
    public AjaxResult templateUpload(@RequestParam("model") String model, @RequestParam("doc") String doc,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "effective", required = false) String effective,
                                     @RequestParam(value = "note", required = false) String note,
                                     Authentication auth) {
        AjaxResult r = new AjaxResult();
        if (!McellDocService.DOCS.contains(doc)) { r.success = false; r.message = "문서 구분 오류"; return r; }
        java.util.List<String> models = "ALL".equals(model) ? java.util.List.of("M2", "M4", "M4A") : java.util.List.of(model);
        if (!MODELS.containsAll(models)) { r.success = false; r.message = "모델 구분 오류"; return r; }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                r.success = false; r.message = "xlsx 파일만 올릴 수 있습니다 (엑셀에서 «Excel 통합 문서»로 저장)"; return r;
            }
            java.time.LocalDate eff = (effective == null || effective.isBlank()) ? java.time.LocalDate.now()
                    : java.time.LocalDate.parse(effective.trim());
            Integer userId = null;
            if (auth != null && auth.getPrincipal() instanceof User u) userId = u.getId();
            java.util.List<String> done = new java.util.ArrayList<>();
            for (String m : models) {
                Integer prevId = this.mcellDocService.ensureLatest(m, doc);
                Path dir = tplRoot().resolve(m);
                Files.createDirectories(dir.resolve("v"));
                String rel = null;
                byte[] old = currentTemplate(m, doc);
                if (old != null) {
                    rel = m + "/v/" + doc + "_" + prevId + ".xlsx";
                    Path keep = tplRoot().resolve(rel);
                    if (!Files.exists(keep)) Files.write(keep, old);
                }
                Map<String, Object> nv = this.mcellDocService.addVersion(m, doc, eff, note, prevId, rel, userId);
                Files.write(dir.resolve(doc + ".xlsx"), bytes);
                done.add(m + " Rev." + nv.get("rev_no"));
            }
            r.message = "새 양식 버전을 등록했습니다 (" + doc + " · " + String.join(", ", done) + " · 적용일 " + eff + ")"
                    + " · 그 전에 조립한 제품은 예전 양식 그대로 나옵니다. 줄이 바뀌었다면 «엑셀 화면에서 칸 연결»로 다시 맞추세요";
        } catch (IllegalArgumentException e) {
            r.success = false; r.message = e.getMessage();
        } catch (Exception e) {
            log.error("[mcell/doc] 양식 교체 실패", e);
            r.success = false; r.message = "양식 교체 실패: " + e.getMessage();
        }
        return r;
    }

    /** 버전 목록 */
    @GetMapping("/versions")
    public AjaxResult versions(@RequestParam("model") String model, @RequestParam("doc") String doc) {
        AjaxResult r = new AjaxResult();
        try { r.data = this.mcellDocService.listVersions(model, doc); }
        catch (Exception e) { r.success = false; r.message = "버전 표가 없습니다 — patch_mcell_doc_version.sql 을 먼저 실행하세요"; }
        return r;
    }

    /** 최신 버전 «사용 안 함» — 바로 앞 버전(파일 + 매핑)으로 되돌린다 */
    @PostMapping("/version_disable")
    public AjaxResult versionDisable(@RequestParam("ver_id") Integer verId) {
        AjaxResult r = new AjaxResult();
        try {
            Map<String, Object> pv = this.mcellDocService.disableLatest(verId);
            String model = String.valueOf(pv.get("model")), doc = String.valueOf(pv.get("doc"));
            Path cur = tplRoot().resolve(model).resolve(doc + ".xlsx");
            Object fp = pv.get("file_path");
            Path src = fp == null ? null : tplRoot().resolve(String.valueOf(fp)).normalize();
            if (src != null && src.startsWith(tplRoot()) && Files.exists(src)) {
                Files.copy(src, cur, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (fp == null) {
                Files.deleteIfExists(cur);   // 원래 프로젝트 안 기본 양식을 쓰던 버전
            }
            r.message = "Rev." + pv.get("rev_no") + " 으로 되돌렸습니다 (양식 파일 · 칸 매핑)";
        } catch (IllegalArgumentException e) {
            r.success = false; r.message = e.getMessage();
        } catch (Exception e) {
            log.error("[mcell/doc] 버전 되돌리기 실패", e);
            r.success = false; r.message = "되돌리기 실패: " + e.getMessage();
        }
        return r;
    }

    /** 양식 파일 정보 — 어디서 읽는지 · 마지막 교체 시각 */
    @GetMapping("/template_info")
    public AjaxResult templateInfo(@RequestParam("model") String model, @RequestParam("doc") String doc) {
        AjaxResult r = new AjaxResult();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        try {
            String root = settings.getProperty("file_upload_path");
            Path f = (root == null || root.isBlank()) ? null : Paths.get(root.trim(), "mcell_doc_tpl", model, doc + ".xlsx");
            if (f != null && Files.exists(f)) {
                m.put("source", "folder");
                m.put("modified", java.time.LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            } else {
                m.put("source", new ClassPathResource("static/" + DOC_DIR + "/" + model + "/" + doc + ".xlsx").exists() ? "builtin" : "none");
            }
        } catch (Exception e) { m.put("source", "none"); }
        r.data = m;
        return r;
    }

    /**
     * ★ 인쇄용 PDF — 화면이 양식에 값을 채운 xlsx 를 올리면 LibreOffice 로 PDF 로 바꿔 돌려준다.
     *   엑셀 양식 그대로(병합·테두리·사진·인쇄설정) 인쇄되게 하려는 것.
     *   서버에 LibreOffice 가 있어야 한다. 경로는 application.properties 의 soffice_path
     *   (없으면 Windows 기본 설치 경로 → PATH 의 soffice 순으로 찾는다).
     *   동시에 여러 건이 와도 꼬이지 않게 요청마다 LibreOffice 사용자 프로필을 따로 쓴다.
     */
    @PostMapping("/to_pdf")
    public ResponseEntity<byte[]> toPdf(@RequestParam("file") MultipartFile file) {
        Path work = null;
        try {
            work = Files.createTempDirectory("mcell_pdf_");
            Path in = work.resolve("doc.xlsx");
            file.transferTo(in);
            String soffice = sofficePath();
            String profile = work.resolve("lo_profile").toUri().toString();
            ProcessBuilder pb = new ProcessBuilder(soffice, "-env:UserInstallation=" + profile,
                    "--headless", "--norestore", "--convert-to", "pdf", "--outdir", work.toString(), in.toString());
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            pr.getInputStream().readAllBytes();
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); throw new IllegalStateException("PDF 변환 시간 초과"); }
            Path pdf = work.resolve("doc.pdf");
            if (!Files.exists(pdf)) throw new IllegalStateException("PDF 변환 실패 (LibreOffice 확인)");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("doc.pdf").build().toString())
                    .body(Files.readAllBytes(pdf));
        } catch (Exception e) {
            log.warn("[mcell/doc] PDF 변환 실패 : {}", e.getMessage());
            return ResponseEntity.status(503).contentType(MediaType.TEXT_PLAIN)
                    .body(("PDF 변환 실패 : " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            if (work != null) deleteQuietly(work);
        }
    }

    private String sofficePath() {
        String p = settings.getProperty("soffice_path");
        if (p != null && !p.isBlank()) return p.trim();
        for (String c : new String[]{"C:\\Program Files\\LibreOffice\\program\\soffice.exe",
                "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
                "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice", "/opt/libreoffice/program/soffice"}) {
            if (Files.exists(Paths.get(c))) return c;
        }
        // 리눅스 공식 rpm/deb 패키지는 /opt/libreoffice7.x/ 처럼 버전 폴더에 깔린다
        try (var ds = Files.newDirectoryStream(Paths.get("/opt"), "libreoffice*")) {
            for (Path d : ds) {
                Path s = d.resolve("program").resolve("soffice");
                if (Files.exists(s)) return s.toString();
            }
        } catch (Exception ignore) {}
        return "soffice";
    }

    private void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignore) {} });
        } catch (Exception ignore) {}
    }

    // ── 첨부 ─────────────────────────────────────────────

    @GetMapping("/files")
    public AjaxResult files(@RequestParam("unit_id") Integer unitId, @RequestParam("doc") String doc) {
        AjaxResult r = new AjaxResult();
        if (!McellDocService.DOCS.contains(doc)) { r.success = false; r.message = "문서 구분 오류"; return r; }
        r.data = this.mcellDocService.getFiles(unitId, doc);
        return r;
    }

    /**
     * 파일 1개 업로드 (multipart).
     * ★ base64 form 전송은 Tomcat maxPostSize(2MB)에 걸려 조용히 잘린다 — 스캔본·PDF 는 multipart 로 받는다.
     *   multipart 한도는 spring.servlet.multipart.max-file-size 설정을 따른다.
     */
    @PostMapping("/file_add")
    @Transactional
    public AjaxResult fileAdd(@RequestParam("unit_id") Integer unitId, @RequestParam("doc") String doc,
                              @RequestParam("file") MultipartFile file,
                              @RequestParam(value = "spjangcd", defaultValue = "ZZ") String spjangcd,
                              Authentication auth) {
        AjaxResult r = new AjaxResult();
        if (!McellDocService.DOCS.contains(doc)) { r.success = false; r.message = "문서 구분 오류"; return r; }
        if (file == null || file.isEmpty()) { r.success = false; r.message = "파일이 비어 있습니다."; return r; }
        if (file.getSize() > MAX_SIZE) { r.success = false; r.message = "30MB 이하 파일만 올릴 수 있습니다."; return r; }
        try {
            String orig = file.getOriginalFilename() == null ? "file" : Paths.get(file.getOriginalFilename()).getFileName().toString();
            int dot = orig.lastIndexOf('.');
            String ext = dot > 0 ? orig.substring(dot + 1).toLowerCase() : "";
            int idx = this.mcellDocService.nextIndex(unitId, doc);
            Path dir = uploadRoot().resolve(String.valueOf(unitId));
            Files.createDirectories(dir);
            String physic = "%s_%d_%d%s".formatted(doc, idx, System.currentTimeMillis(), ext.isEmpty() ? "" : "." + ext);
            file.transferTo(dir.resolve(physic));
            String rel = unitId + "/" + physic;          // DB 에는 상대경로만
            Integer id = this.mcellDocService.addFile(unitId, doc, idx, orig, physic, rel, ext, file.getSize(),
                    (User) auth.getPrincipal(), spjangcd);
            r.data = Map.of("file_id", id);
            r.message = "첨부했습니다.";
        } catch (Exception e) {
            log.error("[mcell/doc] 첨부 실패 unit={} doc={}", unitId, doc, e);
            r.success = false; r.message = "첨부 실패: " + e.getMessage();
        }
        return r;
    }

    /** 첨부 내려받기 (이미지·PDF 는 브라우저에서 바로 열린다) */
    @GetMapping("/file")
    public ResponseEntity<byte[]> file(@RequestParam("file_id") Integer fileId) {
        Map<String, Object> row = this.mcellDocService.getFile(fileId);
        if (row == null) return ResponseEntity.notFound().build();
        try {
            String path = String.valueOf(row.get("file_path"));
            Path f = Paths.get(path);
            if (!f.isAbsolute()) f = uploadRoot().resolve(path);
            byte[] bytes = Files.readAllBytes(f);
            String name = String.valueOf(row.get("file_name"));
            String ext = String.valueOf(row.get("ext_name")).toLowerCase();
            MediaType type = switch (ext) {
                case "pdf" -> MediaType.APPLICATION_PDF;
                case "png" -> MediaType.IMAGE_PNG;
                case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
                default -> MediaType.APPLICATION_OCTET_STREAM;
            };
            boolean inline = type != MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok().contentType(type)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                                    .filename(name, StandardCharsets.UTF_8).build().toString())
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/file_delete")
    @Transactional
    public AjaxResult fileDelete(@RequestParam("file_id") Integer fileId) {
        AjaxResult r = new AjaxResult();
        Map<String, Object> row = this.mcellDocService.deleteFile(fileId);
        try {
            String path = String.valueOf(row.get("file_path"));
            Path f = Paths.get(path);
            if (!f.isAbsolute()) f = uploadRoot().resolve(path);
            Files.deleteIfExists(f);
        } catch (Exception e) {
            log.warn("[mcell/doc] 파일 삭제 실패 (메타는 삭제됨)", e);
        }
        r.message = "첨부를 삭제했습니다.";
        return r;
    }

    @ExceptionHandler(Exception.class)
    public AjaxResult handleError(Exception e) {
        AjaxResult r = new AjaxResult();
        r.success = false;
        if (!(e instanceof IllegalStateException || e instanceof IllegalArgumentException)) log.error("[mcell/doc] 처리 오류", e);
        r.message = (e.getMessage() == null || e.getMessage().isBlank()) ? "처리 중 오류가 발생했습니다." : e.getMessage();
        return r;
    }
}