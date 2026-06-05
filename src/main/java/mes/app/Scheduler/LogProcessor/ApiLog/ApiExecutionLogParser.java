package mes.app.Scheduler.LogProcessor.ApiLog;

import mes.app.Scheduler.LogProcessor.LogEntry;
import mes.app.Scheduler.LogProcessor.LogParserStrategy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class ApiExecutionLogParser implements LogParserStrategy {

    private final Pattern pattern = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) .*?\\[API 실행시간] (GET|POST|PUT|DELETE) (.*?) → ([\\d.]+)초"
    );

    @Override
    public Optional<LogEntry> parse(String line) {

        Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
            ApiExecutionLogEntry entry = new ApiExecutionLogEntry();
            //entry.setOccurrenceTime(matcher.group(1));
            entry.setMethod(matcher.group(2));
            entry.setApiAddress(matcher.group(3));
            entry.setDurationSecond(Double.parseDouble(matcher.group(4)));
            return Optional.of(entry);
        }

        return Optional.empty();
    }
}
