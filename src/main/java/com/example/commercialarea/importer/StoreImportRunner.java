package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class StoreImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreImportRunner.class);
    private static final long PROGRESS_INTERVAL = 100_000;

    private final ImportProperties settings;
    private final StoreCsvParser parser = new StoreCsvParser();
    private final StoreRepository repository;
    private final LookupBuilder lookupBuilder;

    public StoreImportRunner(ImportProperties settings, StoreRepository repository, LookupBuilder lookupBuilder) {
        this.settings = settings;
        this.repository = repository;
        this.lookupBuilder = lookupBuilder;
    }

    /**
     * 부팅 직후 실행된다. 웹 서버는 이미 요청을 받는 상태이므로 적재가
     * 화면 노출을 막지 않는다. 적재 중에도 지도는 뜨고 점이 점차 채워진다.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!settings.enabled()) {
            log.info("CSV 적재 비활성화 (app.import.enabled=false)");
            return;
        }
        if (repository.countAll() > 0) {
            log.info("store 테이블에 데이터가 이미 있어 적재를 건너뛴다");
            return;
        }
        ImportSummary summary = importFrom(Path.of(settings.dir()));
        if (summary.succeeded() > 0) {
            lookupBuilder.rebuild();
        }
    }

    public ImportSummary importFrom(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("CSV 디렉토리가 없다: {}", dir);
            return new ImportSummary(0, 0);
        }

        long started = System.currentTimeMillis();
        long succeeded = 0;
        long failed = 0;

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> targets = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .filter(p -> settings.matches(p.getFileName().toString()))
                    .sorted()
                    .toList();

            log.info("적재 대상 파일 {}개", targets.size());
            for (Path file : targets) {
                ImportSummary one = importFile(file);
                succeeded += one.succeeded();
                failed += one.failed();
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV 디렉토리 탐색 실패: " + dir, e);
        }

        ImportSummary total = new ImportSummary(succeeded, failed);
        log.info("전체 적재 완료: 성공 {} / 실패 {} ({}초)",
                succeeded, failed, (System.currentTimeMillis() - started) / 1000);
        return total;
    }

    private ImportSummary importFile(Path file) {
        log.info("적재 시작: {}", file.getFileName());
        long started = System.currentTimeMillis();
        long succeeded = 0;
        long failed = 0;
        List<Store> buffer = new ArrayList<>(settings.batchSize());

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser csv = new CSVParser(reader, StoreCsvParser.FORMAT)) {

            for (CSVRecord record : csv) {
                try {
                    buffer.add(parser.parse(record));
                } catch (MalformedRowException e) {
                    failed++;
                    if (failed <= 20) {
                        log.warn("행 건너뜀 ({}행): {}", record.getRecordNumber(), e.getMessage());
                    }
                    continue;
                }
                if (buffer.size() >= settings.batchSize()) {
                    succeeded += flush(buffer);
                    if (succeeded % PROGRESS_INTERVAL < settings.batchSize()) {
                        log.info("  {} 진행: {}행 ({}초)", file.getFileName(), succeeded,
                                (System.currentTimeMillis() - started) / 1000);
                    }
                }
            }
            succeeded += flush(buffer);
        } catch (IOException e) {
            throw new IllegalStateException("CSV 읽기 실패: " + file, e);
        }

        ImportSummary summary = new ImportSummary(succeeded, failed);
        if (summary.failureRate() > 0.01) {
            log.error("{} 실패율 {}%가 1%를 초과한다. CSV 형식이 바뀌었을 수 있다.",
                    file.getFileName(), Math.round(summary.failureRate() * 1000) / 10.0);
        }
        log.info("적재 완료: {} — 성공 {} / 실패 {} ({}초)", file.getFileName(),
                succeeded, failed, (System.currentTimeMillis() - started) / 1000);
        return summary;
    }

    private int flush(List<Store> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int n = repository.insertBatch(buffer);
        buffer.clear();
        return n;
    }
}
