package mes.app.SpringBatch.ApiTimeLogProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.LogProcessor.ApiLog.ApiExecutionLogEntry;
import mes.domain.services.SqlRunner;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ApiTimeLogProceesorStepListener implements StepExecutionListener {

    private final List<ApiExecutionLogEntry> allItems = new ArrayList<>();


    private final SqlRunner sqlRunner;

    public ApiTimeLogProceesorStepListener(SqlRunner sqlRunner) {
        this.sqlRunner = sqlRunner;
    }


    @Override
    public void beforeStep(StepExecution stepExecution) {

    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {

        String sql = """
                INSERT INTO api_log_entry (endpoint, avg_response_time_ms, max_response_time_ms, min_response_time_ms, avg_call_cnt)
                    VALUES (
                    :endpoint, :avg_response_time_ms, :max_response_time_ms, :min_response_time_ms, :avg_call_cnt
                )
                """;

        Map<String, List<ApiExecutionLogEntry>> grouped = allItems.stream()
                        .collect(Collectors.groupingBy(ApiExecutionLogEntry::getApiAddress));

        List<SqlParameterSource> batchParams = new ArrayList<>();

        grouped.forEach((api, logs) -> {
            int count = logs.size();
            double sum = 0.0;
            double max = Double.MAX_VALUE;
            double min = Double.MIN_VALUE;

            for(ApiExecutionLogEntry log : logs){
                double duration = log.getDurationSecond();
                sum += duration;

                if(duration > max) max = duration;
                if(duration < min) min = duration;
            }

            double avg = count > 0 ? sum / count : 0.0;

            MapSqlParameterSource param = new MapSqlParameterSource()
                    .addValue("endpoint", api)
                    .addValue("avg_response_time_ms", round(avg))
                    .addValue("max_response_time_ms", round(max))
                    .addValue("min_response_time_ms", round(min))
                    .addValue("avg_call_cnt", count);
            batchParams.add(param);

        });

        log.info("[스프링 배치 쿼리 실행 - {}건]", batchParams.size());

        sqlRunner.batchUpdate(sql, batchParams.toArray(new SqlParameterSource[0]));


        allItems.clear();

        return stepExecution.getExitStatus();
    }

    public void collect(List<ApiExecutionLogEntry> items) {
        allItems.addAll(items);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
