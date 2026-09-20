package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.snapshot.SnapshotRepository;
import com.example.commercialarea.store.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreRepository/SnapshotRepository/LookupBuilder를 목으로 대체해 run()의 분기 로직을
 * 검증한다. 실제 DB(Testcontainers)가 필요 없는 순수 단위 테스트다.
 */
class StoreImportRunnerUnitTest {

    @TempDir Path csvDir;

    private void writeFixture(String fileName) throws IOException {
        byte[] content = getClass().getResourceAsStream("/fixtures/sample-stores.csv").readAllBytes();
        Files.write(csvDir.resolve(fileName), content);
    }

    private ImportProperties props(String snapshot) {
        return new ImportProperties(true, csvDir.toString(), List.of("서울"), 100, snapshot);
    }

    private StoreImportRunner runner(ImportProperties p, StoreRepository repo,
                                     SnapshotRepository snap, LookupBuilder lookup) {
        return new StoreImportRunner(p, repo, snap, lookup);
    }

    @Test
    void snapshot이_없으면_예외를_던진다() {
        StoreImportRunner runner = runner(props(null),
                mock(StoreRepository.class), mock(SnapshotRepository.class), mock(LookupBuilder.class));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    void 이미_적재된_스냅샷이면_아무것도_하지_않는다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202606")).thenReturn(true);

        runner(props("202606"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(snap, never()).insertSnapshotBatch(eq("202606"), anyList());
        verify(repo, never()).insertBatch(anyList());
        verify(lookup, never()).rebuild();
    }

    @Test
    void 최신_스냅샷이면_store를_비우고_슬림과_store에_모두_적재하고_룩업을_재생성한다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202606")).thenReturn(false);
        when(snap.latestSnapshotYm()).thenReturn(Optional.empty()); // 첫 스냅샷 = 최신
        when(repo.insertBatch(anyList())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        when(repo.countAll()).thenReturn(3L);

        runner(props("202606"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(repo, times(1)).deleteAllStores();
        verify(snap, times(1)).insertSnapshotBatch(eq("202606"), anyList());
        verify(repo, times(1)).insertBatch(anyList());
        verify(lookup, times(1)).rebuild();
        verify(snap, times(1)).recordImport(eq("202606"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 과거_백필_스냅샷이면_store를_건드리지_않고_슬림만_적재한다() throws Exception {
        writeFixture("소상공인_서울_202503.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202503")).thenReturn(false);
        when(snap.latestSnapshotYm()).thenReturn(Optional.of("202506")); // 이미 더 최신이 있다

        runner(props("202503"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(repo, never()).deleteAllStores();
        verify(repo, never()).insertBatch(anyList());
        verify(lookup, never()).rebuild();
        verify(snap, times(1)).insertSnapshotBatch(eq("202503"), anyList());
        verify(snap, times(1)).recordImport(eq("202503"), org.mockito.ArgumentMatchers.anyLong());
    }
}
