package com.example.commercialarea.importer;

import com.example.commercialarea.store.StoreRepository;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreImportRunnerTest {

    @Autowired StoreImportRunner runner;
    @Autowired StoreRepository storeRepository;
    @Autowired LookupBuilder lookupBuilder;
    @Autowired JdbcClient jdbc;

    @TempDir Path csvDir;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
        jdbc.sql("DELETE FROM region").update();
        jdbc.sql("DELETE FROM industry").update();
    }

    private void writeFixture(String fileName) throws IOException {
        byte[] content = getClass().getResourceAsStream("/fixtures/sample-stores.csv").readAllBytes();
        Files.write(csvDir.resolve(fileName), content);
    }

    @Test
    void 디렉토리의_CSV를_적재하고_깨진_행은_건너뛴다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");

        ImportSummary summary = runner.importFrom(csvDir);

        // 픽스처 6행 중 좌표 없는 1행은 실패한다.
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(5);
        assertThat(storeRepository.countAll()).isEqualTo(5);
    }

    @Test
    void include_패턴에_맞지_않는_파일은_건너뛴다() throws Exception {
        writeFixture("소상공인_제주_202606.csv");

        ImportSummary summary = runner.importFrom(csvDir);

        assertThat(summary.succeeded()).isZero();
        assertThat(storeRepository.countAll()).isZero();
    }

    @Test
    void 두_번_적재해도_행이_중복되지_않는다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");

        runner.importFrom(csvDir);
        runner.importFrom(csvDir);

        assertThat(storeRepository.countAll()).isEqualTo(5);
    }

    @Test
    void 룩업_테이블에_건수와_bbox가_채워진다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        runner.importFrom(csvDir);

        lookupBuilder.rebuild();

        long regionTotal = jdbc.sql("SELECT SUM(store_count) FROM region").query(Long.class).single();
        long industryTotal = jdbc.sql("SELECT SUM(store_count) FROM industry").query(Long.class).single();
        assertThat(regionTotal).isEqualTo(5);
        assertThat(industryTotal).isEqualTo(5);

        var bbox = jdbc.sql("""
                SELECT MIN(min_lat) AS a, MAX(max_lat) AS b, MIN(min_lon) AS c, MAX(max_lon) AS d
                FROM region
                """).query((rs, n) -> new double[]{
                        rs.getDouble("a"), rs.getDouble("b"), rs.getDouble("c"), rs.getDouble("d")})
                .single();

        assertThat(bbox[0]).isLessThanOrEqualTo(bbox[1]);
        assertThat(bbox[2]).isLessThanOrEqualTo(bbox[3]);
        assertThat(bbox[0]).isBetween(37.0, 38.0);
        assertThat(bbox[2]).isBetween(126.0, 128.0);
    }

    @Test
    void 행정동이_없는_행도_룩업에_포함된다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        runner.importFrom(csvDir);

        lookupBuilder.rebuild();

        // dong_code가 NULL인 행은 빈 문자열로 정규화되어 PK 제약을 만족한다.
        long emptyDong = jdbc.sql("SELECT COUNT(*) FROM region WHERE dong_code = ''")
                .query(Long.class).single();
        assertThat(emptyDong).isEqualTo(1);
    }
}
