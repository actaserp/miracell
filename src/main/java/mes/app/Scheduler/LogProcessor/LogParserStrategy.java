package mes.app.Scheduler.LogProcessor;

import java.util.Optional;

public interface LogParserStrategy {

    Optional<LogEntry> parse(String line);
}
