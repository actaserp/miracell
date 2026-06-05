package mes.app.Scheduler.LogProcessor.ApiLog;

import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.LogProcessor.LogEntry;
import mes.app.Scheduler.LogProcessor.LogEntryProcessor;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ApiExecutionLogProcessor implements LogEntryProcessor {


    @Autowired
    SqlRunner sqlRunner;

    @Override
    public void process(List<LogEntry> entries) {

        List<ApiExecutionLogEntry> apiLogs = entries.stream()
                .filter(e -> e instanceof ApiExecutionLogEntry)
                .map(e -> (ApiExecutionLogEntry) e)
                .toList();

        log.info("쓰레드 이름 : {}", Thread.currentThread());


        final int CHUNK_SIZE = 500;

        List<SqlParameterSource> batchParams = new ArrayList<>();


        String sql = """
                
                """;
    }


}
