package mes.app.Scheduler.LogProcessor;

import java.util.List;

public interface LogEntryProcessor {


    void process(List<LogEntry> entries);
}
