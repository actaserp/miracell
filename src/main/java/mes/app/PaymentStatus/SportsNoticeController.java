package mes.app.PaymentStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.PaymentStatus.Service.SportsNoticeService;
import mes.config.Settings;
import mes.domain.entity.User;
import mes.domain.entity.approval.TB_BBSINFO;
import mes.domain.entity.approval.TB_FILEINFO;
import mes.domain.model.AjaxResult;
import mes.domain.repository.approval.BBSINFORepository;
import mes.domain.repository.approval.FILEINFORepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/announcement")
public class SportsNoticeController {
    @Autowired
    SportsNoticeService noticeService;

    @Autowired
    BBSINFORepository bbsinfoRepository;

    @Autowired
    FILEINFORepository fileinfoRepository;

    @Autowired
    private Settings settings;

    // 문의 리스트
    @GetMapping("/read")
    public AjaxResult getBBSList(@RequestParam(value = "search_text", required = false) String searchText){
        List<Map<String, Object>> items = noticeService.getBBSList(searchText);

        for(Map<String, Object> item : items){
            item.put("no", items.indexOf(item)+1);

            // 날짜 형식 변환 (BBSDATE)
            if (item.containsKey("BBSDATE")) {
                String setupdt = (String) item.get("BBSDATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSDATE", formattedDate);
                }
            }
            // 날짜 형식 변환 (BBSFRDATE)
            if (item.containsKey("BBSFRDATE")) {
                String setupdt = (String) item.get("BBSFRDATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSFRDATE", formattedDate);
                }
            }
            // 날짜 형식 변환 (BBSTODATE)
            if (item.containsKey("BBSTODATE")) {
                String setupdt = (String) item.get("BBSTODATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSTODATE", formattedDate);
                }
            }
            ObjectMapper objectMapper = new ObjectMapper();
            if (item.get("fileinfos") != null) {
                try {
                    // JSON 문자열을 List<Map<String, Object>>로 변환
                    List<Map<String, Object>> fileitems = objectMapper.readValue((String) item.get("fileinfos"), new TypeReference<List<Map<String, Object>>>() {});

                    for (Map<String, Object> fileitem : fileitems) {
                        if (fileitem.get("filepath") != null && fileitem.get("fileornm") != null) {
                            String filenames = (String) fileitem.get("fileornm");
                            String filepaths = (String) fileitem.get("filepath");
                            String filesvnms = (String) fileitem.get("filesvnm");

                            List<String> fileornmList = filenames != null ? Arrays.asList(filenames.split(",")) : Collections.emptyList();
                            List<String> filepathList = filepaths != null ? Arrays.asList(filepaths.split(",")) : Collections.emptyList();
                            List<String> filesvnmList = filesvnms != null ? Arrays.asList(filesvnms.split(",")) : Collections.emptyList();

                            item.put("isdownload", !fileornmList.isEmpty() && !filepathList.isEmpty());
                        } else {
                            item.put("isdownload", false);
                        }
                    }

                    // fileitems를 다시 item에 넣어 업데이트
                    item.remove("fileinfos");
                    item.put("filelist", fileitems);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
    @PostMapping("/uploadEditor")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        String uploadDir = "c:\\temp\\editorFile\\";
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        try {
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            File destinationFile = new File(uploadDir + fileName);
            file.transferTo(destinationFile);

            String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            String fileUrl = baseUrl + "/editorFile/" + fileName; // 클라이언트 접근 URL

            return ResponseEntity.ok(Collections.singletonMap("location", fileUrl));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "파일 업로드 실패: " + e.getMessage()));
        }
    }
    // 공지사항 등록
    @PostMapping("/save")
    public AjaxResult saveBBS(@ModelAttribute TB_BBSINFO BBSINFO,
                              @RequestParam(value = "filelist", required = false) MultipartFile[] files,
                              @RequestPart(value = "deletedFiles2", required = false) MultipartFile[] deletedFiles,
                              Authentication auth
    ) {
        AjaxResult result = new AjaxResult();
        ObjectMapper objectMapper = new ObjectMapper();
        User user = (User) auth.getPrincipal();
        // 유저정보 TB_BBSINFO 객체에 바인드
        BBSINFO.setBBSUSER(user.getUsername());
        BBSINFO.setINUSERID(user.getUsername());
        BBSINFO.setINDATEM(LocalDateTime.now());

        try {
            // Repository를 통해 데이터 저장
            bbsinfoRepository.save(BBSINFO);
            // 파일 저장 처리
            if(files != null){
                for (MultipartFile multipartFile : files) {
                    String path = settings.getProperty("file_upload_path") + "공지사항";
                    MultipartFile file = multipartFile;
                    int fileSize = (int) file.getSize();

                    if(fileSize > 52428800){
                        result.message = "파일의 크기가 초과하였습니다.";
                        return result;
                    }
                    String fileName = file.getOriginalFilename();
                    String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
                    String file_uuid_name = UUID.randomUUID().toString() + "." + ext;
                    String saveFilePath = path;
                    File saveDir = new File(saveFilePath);



                    //디렉토리 없으면 생성
                    if(!saveDir.isDirectory()){
                        saveDir.mkdirs();
                    }
                    File saveFile = new File(path + File.separator + file_uuid_name);
                    file.transferTo(saveFile);
                    TB_FILEINFO fileinfo = new TB_FILEINFO();

                    fileinfo.setFILEPATH(saveFilePath);
                    fileinfo.setFiledate(BBSINFO.getBBSDATE());
                    fileinfo.setFILEORNM(fileName);
                    fileinfo.setFILESIZE(BigDecimal.valueOf(fileSize));
                    //fileinfo.setINDATEM(); // ("reqdate".replaceAll("-","")
                    fileinfo.setINUSERID(String.valueOf(user.getId()));
                    fileinfo.setFILEEXTNS(ext);
                    fileinfo.setFILEURL(saveFilePath);
                    fileinfo.setFILESVNM(file_uuid_name);
                    fileinfo.setCHECKSEQ("01");

                    try {
                        fileinfo.setBbsseq(BBSINFO.getBBSSEQ());
                        fileinfoRepository.save(fileinfo);
                    }catch (Exception e) {
                        result.success = false;
                        result.message = "저장에 실패하였습니다.";
                    }
                }
            }
            // 삭제된 파일 처리
            if (deletedFiles != null && deletedFiles.length > 0) {
                List<TB_FILEINFO> FileList = new ArrayList<>();

                for (MultipartFile deletedFile : deletedFiles) {
                    String content = new String(deletedFile.getBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> deletedFileMap = new ObjectMapper().readValue(content, new TypeReference<Map<String, Object>>() {});

                    String fileid = (String) deletedFileMap.get("name");

                    TB_FILEINFO File = fileinfoRepository.findBySvnmAndSeq(fileid, BBSINFO.getBBSSEQ());
                    // id : fileid
                    if (File != null) {
                        // 파일 삭제
                        String filePath = File.getFILEPATH();
                        String fileName = File.getFILESVNM();
                        File file = new File(filePath, fileName);
                        if (file.exists()) {
                            file.delete();
                        }
                        FileList.add(File);
                    }
                }
                fileinfoRepository.deleteAll(FileList);
            }
//            // 웹 에디터 이미지 수정시 기존 이미지 삭제 작업
//            if (BBSINFO.getBBSSEQ() != null) {
//                bbsinfoRepository.findById(BBSINFO.getBBSSEQ()).ifPresent(bbsinfo -> {
//                    // 기존 및 새로운 이미지 URL 목록 추출
//                    List<String> originImages = extractImageUrlsFromHtml(bbsinfo.getBBSTEXT());
//                    List<String> newImages = extractImageUrlsFromHtml(BBSINFO.getBBSTEXT());
//
//                    // 삭제할 이미지 목록 추출 (기존 이미지 중 새로운 이미지에 없는 것)
//                    List<String> imagesToDelete = new ArrayList<>(originImages);
//                    imagesToDelete.removeAll(newImages);
//
//                    // 서버 디렉토리에서 삭제
//                    for (String imageUrl : imagesToDelete) {
//                        deleteImageFromServer(imageUrl);
//                    }
//                });
//            }
            result.message = "저장되었습니다.";
        } catch (Exception e) {
            e.printStackTrace();
            result.message = "공지사항 저장 중 오류가 발생했습니다.";
        }

        return result;
    }
    // 공지사항 삭제
    @PostMapping("/delete")
    public AjaxResult deleteBBS(@RequestBody Map<String, Object> requestData) {
        AjaxResult result = new AjaxResult();
        int BBSSEQ = Integer.parseInt((String) requestData.get("BBSSEQ"));

        try {
            // Repository를 통해 데이터 저장
            noticeService.deleteBBS(BBSSEQ);

            bbsinfoRepository.findById(BBSSEQ).ifPresent(bbsinfo -> {
            });
            // 파일 서버에서 삭제
            List<TB_FILEINFO> filelist = fileinfoRepository.findAllByCheckseqAndBbsseq("01", BBSSEQ);
            for (TB_FILEINFO fileinfo : filelist) {
                String filePath = fileinfo.getFILEPATH();
                String fileName = fileinfo.getFILESVNM();
                File file = new File(filePath, fileName);
                if (file.exists()) {
                    file.delete();
                }
            }
            noticeService.deleteFile(BBSSEQ);
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            e.printStackTrace();
            result.message = "공지사항 삭제 중 오류가 발생했습니다.";
        }

        return result;
    }

//    // 에디터 파일 파싱 메서드
//    private List<String> extractImageUrlsFromHtml(String htmlContent) {
//        List<String> imageUrls = new ArrayList<>();
//
//        try {
//            // JSoup 라이브러리를 사용해 HTML 파싱
//            Document doc = Jsoup.parse(htmlContent);
//            Elements images = doc.select("img"); // <img> 태그 선택
//
//            for (Element img : images) {
//                String src = img.attr("src"); // src 속성 값 추출
//                if (src != null && !src.isEmpty()) {
//                    imageUrls.add(src);
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        return imageUrls;
//    }
//    // 에디터 파일 삭제 메서드
//    private boolean deleteImageFromServer(String imageUrl) {
//        try {
//            // 이미지 URL에서 파일 경로 추출
//            String uploadDir = "c:/temp/editorFile/"; // 업로드된 파일이 저장된 디렉토리
//            String fileName = imageUrl.substring(imageUrl.lastIndexOf("/") + 1); // 파일 이름 추출
//            File file = new File(uploadDir + fileName);
//
//            // 파일 존재 여부 확인 후 삭제
//            if (file.exists()) {
//                return file.delete();
//            } else {
//                System.err.println("삭제할 파일이 존재하지 않습니다: " + file.getAbsolutePath());
//                return false;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        }
//    }
    @PostMapping("/downloader")
    public ResponseEntity<?> downloadFile(@RequestBody List<Map<String, Object>> downloadList) throws IOException {

        // 파일 목록과 파일 이름을 담을 리스트 초기화
        List<File> filesToDownload = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        // ZIP 파일 이름을 설정할 변수 초기화
        String tketcrdtm = null;
        String tketnm = null;

        // 파일을 메모리에 쓰기
        for (Map<String, Object> fileInfo : downloadList) {
            String filePath = (String) fileInfo.get("filepath");    // 파일 경로
            String fileName = (String) fileInfo.get("filesvnm");    // 파일 이름(uuid)
            String originFileName = (String) fileInfo.get("fileornm");  //파일 원본이름(origin Name)

            File file = new File(filePath + File.separator + fileName);

            // 파일이 실제로 존재하는지 확인
            if (file.exists()) {
                filesToDownload.add(file);
                fileNames.add(originFileName); // 다운로드 받을 파일 이름을 originFileName으로 설정
            }
        }

        // 파일이 없는 경우
        if (filesToDownload.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 파일이 하나인 경우 그 파일을 바로 다운로드
        if (filesToDownload.size() == 1) {
            File file = filesToDownload.get(0);
            String originFileName = fileNames.get(0); // originFileName 가져오기

            HttpHeaders headers = new HttpHeaders();
            String encodedFileName = URLEncoder.encode(originFileName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=*''" + encodedFileName);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(file.length());

            ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(file.toPath()));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
        }

        String zipFileName = (tketcrdtm != null && tketnm != null) ? tketcrdtm + "_" + tketnm + ".zip" : "download.zip";

        // 파일이 두 개 이상인 경우 ZIP 파일로 묶어서 다운로드
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(zipBaos)) {

            Set<String> addedFileNames = new HashSet<>(); // 이미 추가된 파일 이름을 저장할 Set
            int fileCount = 1;

            for (int i = 0; i < filesToDownload.size(); i++) {
                File file = filesToDownload.get(i);
                String originFileName = fileNames.get(i); // originFileName 가져오기

                // 파일 이름이 중복될 경우 숫자를 붙여 고유한 이름으로 만듦
                String uniqueFileName = originFileName;
                while (addedFileNames.contains(uniqueFileName)) {
                    uniqueFileName = originFileName.replace(".", "_" + fileCount++ + ".");
                }

                // 고유한 파일 이름을 Set에 추가
                addedFileNames.add(uniqueFileName);

                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(originFileName);
                    zipOut.putNextEntry(zipEntry);

                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zipOut.write(buffer, 0, len);
                    }

                    zipOut.closeEntry();
                } catch (IOException e) {
                    e.printStackTrace();
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                }
            }

            zipOut.finish();
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        ByteArrayResource zipResource = new ByteArrayResource(zipBaos.toByteArray());

        HttpHeaders headers = new HttpHeaders();
        String encodedZipFileName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=*''" + encodedZipFileName);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(zipResource.contentLength());

        return ResponseEntity.ok()
                .headers(headers)
                .body(zipResource);
    }
}
