package mes.app.Scheduler.SchedulerService;


import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.LogProcessor.ApiLog.ApiExecutionLogParser;
import mes.app.Scheduler.LogProcessor.ApiLog.ApiExecutionLogProcessor;
import mes.app.Scheduler.LogProcessor.LogEntry;
import mes.app.Scheduler.LogProcessor.LogEntryProcessor;
import mes.app.Scheduler.LogProcessor.LogFileReader;
import mes.app.Scheduler.LogProcessor.LogParserStrategy;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
@Deprecated
public class ApiTimeLogCollectService {

    private final LogParserStrategy logParserStrategy;
    private final LogEntryProcessor apiExecutionLogProcessor;

    public ApiTimeLogCollectService(ApiExecutionLogParser apiExecutionLogParser, ApiExecutionLogProcessor apiExecutionLogProcessor) {
        this.logParserStrategy = apiExecutionLogParser;
        this.apiExecutionLogProcessor = apiExecutionLogProcessor;
    }

    public void run(){

        try {

            LocalDateTime now = LocalDateTime.now().minusDays(1);
            String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String logPath = "logs/api-time/api_time." + formatted + ".log";

            LogFileReader reader = new LogFileReader(logParserStrategy);
            List<LogEntry> entries = reader.readLog(Paths.get(logPath));


            apiExecutionLogProcessor.process(entries);

        } catch (Exception e) {
            log.error("[api로그 스케줄러 오류 발생: ] {}", e.getMessage());
            throw new RuntimeException(e);
        }

    }
}
