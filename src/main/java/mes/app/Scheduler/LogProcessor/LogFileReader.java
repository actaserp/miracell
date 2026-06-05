package mes.app.Scheduler.LogProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Deprecated
public class LogFileReader {

    private final LogParserStrategy strategy;

    public LogFileReader(LogParserStrategy strategy) {
        this.strategy = strategy;
    }

    public List<LogEntry> readLog(Path logPath) throws Exception {
        List<LogEntry> result = new ArrayList<>();
        try(Stream<String> lines = Files.lines(logPath, StandardCharsets.UTF_8)){
            lines.forEach(line -> strategy.parse(line).ifPresent(result::add));
        }
        return result;
    }
}
