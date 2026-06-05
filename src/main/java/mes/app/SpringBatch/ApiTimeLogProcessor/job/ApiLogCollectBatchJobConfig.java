package mes.app.SpringBatch.ApiTimeLogProcessor.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.LogProcessor.ApiLog.ApiExecutionLogEntry;
import mes.app.SpringBatch.ApiTimeLogProcessor.ApiTimeLogProceesorStepListener;
import mes.domain.services.SqlRunner;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class ApiLogCollectBatchJobConfig {

    private final JobBuilderFactory jobBuilderFactory;

    private final StepBuilderFactory stepBuilderFactory;
    private final PlatformTransactionManager transactionManager;
    private final SqlRunner sqlRunner;


    private int CHUNK_SIZE = 100;


    @Bean
    public ApiTimeLogProceesorStepListener listener() {
        return new ApiTimeLogProceesorStepListener(sqlRunner);
    }

    @Bean
    public Job ApiTimeLogProcesserJob(ApiTimeLogProceesorStepListener listener) {
        return jobBuilderFactory.get("ApiTimeLogProcesserJob")
                .start(readLogFileStep(listener))
                .build();
    }

    @Bean
    public Step readLogFileStep(ApiTimeLogProceesorStepListener listener) {
        return stepBuilderFactory.get("readLogFileStep")
                .<String, ApiExecutionLogEntry>chunk(CHUNK_SIZE)
                .reader(logFileItemReader())
                .processor(logFileItemProcessor())
                .writer(apiExecutionLogWriter(listener))
                .listener(listener)
                .transactionManager(transactionManager)
                .build();
    }

    //FlatFileItemReader : Spring Batch에서 파일을 한 줄씩 읽어서 지정된 타입 T로 반환해주는 ItemReader
    //파일의 각 줄(line)을 하나의 T 객체로 매핑해서 읽어주는 컴포넌트
    @Bean
    @StepScope
    public FlatFileItemReader<String> logFileItemReader() {

        LocalDateTime now = LocalDateTime.now().minusDays(1);
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        String logPath = "logs/api-time/api_time." + formatted + ".log";


        return new FlatFileItemReaderBuilder<String>()
                .name("logFileItemReader")
                .resource(new FileSystemResource(logPath))
                .lineMapper((line, lineNumber) -> line)
                .saveState(true)
                .build();
    }

    @Bean
    public ItemProcessor<String, ApiExecutionLogEntry> logFileItemProcessor(){

        Pattern pattern = Pattern.compile(
                "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) .*?\\[API 실행시간] (GET|POST|PUT|DELETE) (.*?) → ([\\d.]+)초"
        );

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        return line -> {

            Matcher mathcer = pattern.matcher(line);

            if(mathcer.find()){
                ApiExecutionLogEntry entry = new ApiExecutionLogEntry();
                entry.setOccurrenceTime(LocalDateTime.parse(mathcer.group(1), formatter));
                entry.setMethod(mathcer.group(2));
                entry.setApiAddress(mathcer.group(3));
                entry.setDurationSecond(Double.parseDouble(mathcer.group(4)));
                return entry;
            }
            return null;
        };
    }

    @Bean
    public ItemWriter<ApiExecutionLogEntry> apiExecutionLogWriter(ApiTimeLogProceesorStepListener listener){


        String sql = """
                INSERT INTO api_log_entry (endpoint, avg_response_time_ms, max_response_time_ms, min_response_time_ms, avg_call_cnt)
                    VALUES (
                    :endpoint, :avg_response_time_ms, :max_response_time_ms, :min_response_time_ms, :avg_call_cnt
                )
                """;

        return items -> {

            if(!items.isEmpty()){
                listener.collect(new ArrayList<>(items));
            }
            /*if(items.isEmpty()) return;

            Map<String, List<ApiExecutionLogEntry>> grouped = items.stream()
                    .collect(Collectors.groupingBy(ApiExecutionLogEntry::getApiAddress));

            List<SqlParameterSource> batchParams = new ArrayList<>();

            grouped.forEach((api, logs) -> {
                DoubleSummaryStatistics stats = logs.stream()
                        .collect(Collectors.summarizingDouble(ApiExecutionLogEntry::getDurationSecond));

                int count = (int) stats.getCount();
                double avg = BigDecimal.valueOf(stats.getAverage()).setScale(3, RoundingMode.HALF_UP).doubleValue();
                double max = BigDecimal.valueOf(stats.getMax()).setScale(3, RoundingMode.HALF_UP).doubleValue();
                double min = BigDecimal.valueOf(stats.getMin()).setScale(3, RoundingMode.HALF_UP).doubleValue();

                MapSqlParameterSource param = new MapSqlParameterSource()
                        .addValue("endpoint", api)
                        .addValue("avg_response_time_ms", avg)
                        .addValue("max_response_time_ms", max)
                        .addValue("min_response_time_ms", min)
                        .addValue("avg_call_cnt", count);
                batchParams.add(param);


            });
            log.info("[스프링 배치 쿼리 실행 - {}건]", batchParams.size());

            sqlRunner.batchUpdate(sql, batchParams.toArray(new SqlParameterSource[0]));*/
        };

    }
}
