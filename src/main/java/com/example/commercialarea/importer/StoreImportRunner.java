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
import org.springframework.dao.DataAccessException;
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

        boolean hasStoreRows = repository.countAll() > 0;
        boolean lookupsReady = lookupBuilder.isPopulated();
        if (hasStoreRows && lookupsReady) {
            log.info("store 테이블에 데이터가 이미 있어 적재를 건너뛴다");
            return;
        }
        if (hasStoreRows) {
            // region/industry가 비어 있다는 것은 rebuild()가 끝까지 실행되지 못했다는 뜻이다
            // (이전 적재가 파일 처리 도중 중단됨). INSERT IGNORE 덕분에 이미 적재된 행을
            // 다시 시도해도 안전하므로(design.md §5 "CSV 적재" — "중단된 적재를 이어서
            // 진행할 수 있다") 처음부터 이어서 적재한다.
            log.warn("이전 적재가 끝까지 완료되지 않은 것으로 보인다(룩업 테이블이 비어 있음). 적재를 이어서 진행한다");
        }

        importFrom(Path.of(settings.dir()));
        // rebuild() 여부는 이번 실행의 삽입 건수(processed)가 아니라 store에 실제로 행이
        // 있는지로 결정한다. 재개 경로에서는 모든 행이 INSERT IGNORE로 무시되어 processed가
        // 0에 가까울 수 있지만, 그래도 store에는 이미 채워야 할 룩업 대상 행이 있기 때문이다.
        if (repository.countAll() > 0) {
            lookupBuilder.rebuild();
        }
    }

    public ImportSummary importFrom(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("CSV 디렉토리가 없다: {}", dir);
            return new ImportSummary(0, 0);
        }

        long started = System.currentTimeMillis();
        long processed = 0;
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
                processed += one.processed();
                failed += one.failed();
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV 디렉토리 탐색 실패: " + dir, e);
        }

        ImportSummary total = new ImportSummary(processed, failed);
        log.info("전체 적재 완료: 처리 {} / 실패 {} ({}초)",
                processed, failed, (System.currentTimeMillis() - started) / 1000);
        return total;
    }

    private ImportSummary importFile(Path file) {
        log.info("적재 시작: {}", file.getFileName());
        long started = System.currentTimeMillis();
        long processed = 0;
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
                    processed += flush(buffer, file, processed);
                    if (processed % PROGRESS_INTERVAL < settings.batchSize()) {
                        log.info("  {} 진행: {}행 ({}초)", file.getFileName(), processed,
                                (System.currentTimeMillis() - started) / 1000);
                    }
                }
            }
            processed += flush(buffer, file, processed);
        } catch (IOException e) {
            throw new IllegalStateException("CSV 읽기 실패: " + file, e);
        }

        ImportSummary summary = new ImportSummary(processed, failed);
        if (summary.failureRate() > 0.01) {
            log.error("{} 실패율 {}%가 1%를 초과한다. CSV 형식이 바뀌었을 수 있다.",
                    file.getFileName(), Math.round(summary.failureRate() * 1000) / 10.0);
        }
        log.info("적재 완료: {} — 처리 {} / 실패 {} ({}초)", file.getFileName(),
                processed, failed, (System.currentTimeMillis() - started) / 1000);
        return summary;
    }

    /**
     * DB 적재 실패(데드락, 연결 끊김, 디스크 풀 등)가 122만 행 적재 도중 발생하면
     * 원인 파악을 위해 파일명과 그때까지 처리한 행 수를 남기고, 원래 예외는 cause로 보존한다.
     */
    private int flush(List<Store> buffer, Path file, long processedSoFar) {
        if (buffer.isEmpty()) {
            return 0;
        }
        try {
            int n = repository.insertBatch(buffer);
            buffer.clear();
            return n;
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "DB 적재 실패: %s (%d행까지 처리한 상태에서 실패)"
                            .formatted(file.getFileName(), processedSoFar),
                    e);
        }
    }
}
