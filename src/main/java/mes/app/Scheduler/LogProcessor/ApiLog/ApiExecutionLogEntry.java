package mes.app.Scheduler.LogProcessor.ApiLog;

import lombok.Getter;
import lombok.Setter;
import mes.app.Scheduler.LogProcessor.LogEntry;

import java.time.LocalDateTime;

@Getter
@Setter
public class ApiExecutionLogEntry implements LogEntry {

    private LocalDateTime occurrenceTime;
    private String method;
    private String apiAddress;
    private Double durationSecond;

    @Override
    public String getType() {
        return "API_EXEC_TIME";
    }

}
