package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.store.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreRepository/LookupBuilder를 목으로 대체해 run(ApplicationArguments)의 분기 로직과
 * DB 적재 실패 시 예외 래핑을 검증한다. 실제 DB(Testcontainers)가 필요 없는 순수 단위 테스트다.
 */
class StoreImportRunnerUnitTest {

    @TempDir Path csvDir;

    private void writeFixture(String fileName) throws IOException {
        byte[] content = getClass().getResourceAsStream("/fixtures/sample-stores.csv").readAllBytes();
        Files.write(csvDir.resolve(fileName), content);
    }

    private ImportProperties props() {
        return new ImportProperties(true, csvDir.toString(), List.of("서울"), 100);
    }

    @Test
    void store에_행이_있어도_룩업이_비어있으면_적재를_이어서_진행한다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repository = mock(StoreRepository.class);
        LookupBuilder lookupBuilder = mock(LookupBuilder.class);
        when(repository.countAll()).thenReturn(5L); // store에 이미 행이 있다
        when(lookupBuilder.isPopulated()).thenReturn(false); // 그러나 이전 적재는 끝까지 못 갔다
        when(repository.insertBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        StoreImportRunner runner = new StoreImportRunner(props(), repository, lookupBuilder);
        runner.run(new DefaultApplicationArguments());

        // 건너뛰지 않고 실제로 적재를 이어서 진행했다.
        verify(repository, times(1)).insertBatch(anyList());
        verify(lookupBuilder, times(1)).rebuild();
    }

    /**
     * 재개 경로에서는 INSERT IGNORE 때문에 이번 실행이 실제로 저장한 행 수가 0일 수 있다
     * (모든 행이 이미 store에 있었으므로). insertBatch는 오늘 "시도 건수"를 돌려주지만,
     * rebuild() 여부가 그 값에 기대면 안 된다 — 나중에 누군가 insertBatch를 "정확한 저장
     * 건수"를 돌려주도록 고치면 이 시나리오에서 processed==0이 되어 rebuild()가 영영
     * 호출되지 않고 룩업 테이블이 영구히 비게 된다. 그래서 게이트는 processed가 아니라
     * store에 행이 있는지(countAll() > 0)로 걸어야 한다.
     */
    @Test
    void insertBatch가_저장_대신_시도_건수만_돌려줘도_재개_후_룩업을_재생성한다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repository = mock(StoreRepository.class);
        LookupBuilder lookupBuilder = mock(LookupBuilder.class);
        when(repository.countAll()).thenReturn(5L); // store에 이미 5행이 있다(이전 적재가 완주함)
        when(lookupBuilder.isPopulated()).thenReturn(false); // 그러나 룩업 테이블은 비어 있다 → 재개 경로
        // 이번 재개 실행에서는 모든 행이 INSERT IGNORE로 무시되어 실제 저장 건수는 0이다.
        when(repository.insertBatch(anyList())).thenReturn(0);

        StoreImportRunner runner = new StoreImportRunner(props(), repository, lookupBuilder);
        runner.run(new DefaultApplicationArguments());

        verify(lookupBuilder, times(1)).rebuild();
    }

    @Test
    void store에_행이_있고_룩업도_채워져_있으면_적재를_건너뛴다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repository = mock(StoreRepository.class);
        LookupBuilder lookupBuilder = mock(LookupBuilder.class);
        when(repository.countAll()).thenReturn(5L);
        when(lookupBuilder.isPopulated()).thenReturn(true); // 이전 적재가 끝까지 완료된 상태

        StoreImportRunner runner = new StoreImportRunner(props(), repository, lookupBuilder);
        runner.run(new DefaultApplicationArguments());

        verify(repository, never()).insertBatch(anyList());
        verify(lookupBuilder, never()).rebuild();
    }

    @Test
    void DB_적재_실패시_파일명과_성공행수를_포함한_예외로_감싸_원인을_보존한다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repository = mock(StoreRepository.class);
        LookupBuilder lookupBuilder = mock(LookupBuilder.class);
        DataIntegrityViolationException dbFailure = new DataIntegrityViolationException("connection reset");
        when(repository.insertBatch(anyList())).thenThrow(dbFailure);

        StoreImportRunner runner = new StoreImportRunner(props(), repository, lookupBuilder);

        // batchSize=100 > fixture 6행이므로 딱 한 번, 파일 끝에서 플러시되며 그 시점에 실패한다.
        assertThatThrownBy(() -> runner.importFrom(csvDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("소상공인_서울_202606.csv")
                .hasMessageContaining("0행까지 처리한 상태에서 실패")
                .hasCause(dbFailure);
    }
}
