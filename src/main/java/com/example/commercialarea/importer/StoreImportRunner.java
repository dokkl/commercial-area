package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.snapshot.SnapshotRepository;
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
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class StoreImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreImportRunner.class);
    private static final long PROGRESS_INTERVAL = 100_000;

    private final ImportProperties settings;
    private final StoreCsvParser parser = new StoreCsvParser();
    private final StoreRepository repository;
    private final SnapshotRepository snapshotRepository;
    private final LookupBuilder lookupBuilder;

    public StoreImportRunner(ImportProperties settings, StoreRepository repository,
                             SnapshotRepository snapshotRepository, LookupBuilder lookupBuilder) {
        this.settings = settings;
        this.repository = repository;
        this.snapshotRepository = snapshotRepository;
        this.lookupBuilder = lookupBuilder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.enabled()) {
            log.info("CSV 적재 비활성화 (app.import.enabled=false)");
            return;
        }
        if (!settings.hasValidSnapshot()) {
            throw new IllegalStateException(
                    "app.import.snapshot(YYYYMM)이 필요합니다. 예: SNAPSHOT=202506. 현재 값: " + settings.snapshot());
        }
        String snapshot = settings.snapshot();

        if (snapshotRepository.hasSnapshot(snapshot)) {
            log.info("이미 적재된 스냅샷이라 건너뛴다: {}", snapshot);
            return;
        }

        Optional<String> latest = snapshotRepository.latestSnapshotYm();
        boolean isNewest = latest.isEmpty() || snapshot.compareTo(latest.get()) >= 0;

        // 중단된 이전 시도의 잔여를 지우고 이 스냅샷을 처음부터 적재한다(멱등).
        snapshotRepository.deleteSnapshotRows(snapshot);
        if (isNewest) {
            repository.deleteAllStores();   // store = 새 최신 스냅샷으로 교체
        }
        log.info("스냅샷 {} 적재 시작 (최신여부={})", snapshot, isNewest);

        ImportSummary summary = importFrom(Path.of(settings.dir()), snapshot, isNewest);

        if (isNewest && repository.countAll() > 0) {
            lookupBuilder.rebuild();
        }
        snapshotRepository.recordImport(snapshot, summary.processed());
        log.info("스냅샷 {} 적재 완료: 처리 {} / 실패 {}", snapshot, summary.processed(), summary.failed());
    }

    public ImportSummary importFrom(Path dir, String snapshot, boolean isNewest) {
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
                ImportSummary one = importFile(file, snapshot, isNewest);
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

    private ImportSummary importFile(Path file, String snapshot, boolean isNewest) {
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
                    processed += flush(buffer, snapshot, isNewest, file, processed);
                    if (processed % PROGRESS_INTERVAL < settings.batchSize()) {
                        log.info("  {} 진행: {}행 ({}초)", file.getFileName(), processed,
                                (System.currentTimeMillis() - started) / 1000);
                    }
                }
            }
            processed += flush(buffer, snapshot, isNewest, file, processed);
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
     * 슬림 스냅샷 행은 항상 적재하고, 이 스냅샷이 최신일 때만 store(전체)도 적재한다.
     * DB 적재 실패 시 파일명·진행 행수를 남기고 원 예외를 cause로 보존한다.
     */
    private int flush(List<Store> buffer, String snapshot, boolean isNewest, Path file, long processedSoFar) {
        if (buffer.isEmpty()) {
            return 0;
        }
        try {
            snapshotRepository.insertSnapshotBatch(snapshot, buffer);
            if (isNewest) {
                repository.insertBatch(buffer);
            }
            int n = buffer.size();
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
