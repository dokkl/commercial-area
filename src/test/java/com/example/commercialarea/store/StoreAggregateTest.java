package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreAggregateTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    /** 서울 강남 일대를 넉넉히 덮는 bbox. */
    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    private static Store store(String id, double lat, double lon,
                               String sggCode, String sggName,
                               String largeCode, String largeName,
                               String name) {
        return new Store(id, name, null,
                largeCode, largeName, largeCode + "01", "중분류", largeCode + "0101", "소분류",
                "11", "서울특별시", sggCode, sggName, sggCode + "510", "역삼1동",
                "지번주소", null, "도로명주소", null,
                lon, lat);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        repository.insertBatch(List.of(
                store("S1", 37.5000, 127.0000, "11680", "강남구", "I2", "음식", "한밥"),
                store("S2", 37.5001, 127.0001, "11680", "강남구", "I2", "음식", "한솥"),
                store("S3", 37.5002, 127.0002, "11680", "강남구", "G2", "소매", "가나슈퍼"),
                store("S4", 37.8000, 127.5000, "11110", "종로구", "I2", "음식", "한우촌"),
                // bbox 밖 (제주)
                store("S5", 33.4000, 126.5000, "50110", "제주시", "I2", "음식", "제주국수")
        ));
    }

    @Test
    void bbox_밖의_행은_집계에서_제외된다() {
        List<GridCell> cells = repository.aggregate(seoul(), 0.1);

        long total = cells.stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(4);   // S5 제외
    }

    @Test
    void 셀_건수의_합이_전체_건수와_일치한다() {
        for (double cell : new double[]{0.5, 0.1, 0.01, 0.001}) {
            long total = repository.aggregate(seoul(), cell).stream()
                    .mapToLong(GridCell::count).sum();
            assertThat(total).as("cell=%s", cell).isEqualTo(4);
        }
    }

    @Test
    void 가까운_점들은_큰_셀에서_하나로_묶인다() {
        List<GridCell> cells = repository.aggregate(seoul(), 0.1);

        // S1,S2,S3은 37.500x/127.000x 로 같은 셀, S4는 다른 셀
        assertThat(cells).hasSize(2);
        assertThat(cells).anyMatch(c -> c.count() == 3);
        assertThat(cells).anyMatch(c -> c.count() == 1);
    }

    @Test
    void 셀_대표좌표는_셀_중심이_아니라_무게중심이다() {
        GridCell cell = repository.aggregate(seoul(), 0.1).stream()
                .filter(c -> c.count() == 3).findFirst().orElseThrow();

        // (37.5000+37.5001+37.5002)/3 = 37.5001
        assertThat(cell.lat()).isCloseTo(37.5001, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cell.lon()).isCloseTo(127.0001, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void 시군구_필터가_적용된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, "11680", null, null, null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    void 업종_대분류_필터가_적용된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, "I2", null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(3);   // S1,S2,S4
    }

    @Test
    void 지역과_업종_필터를_함께_걸면_교집합이_된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, "11680", null, "I2", null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(2);   // S1,S2
    }

    @Test
    void 상호명_앞부분_일치로_검색한다() {
        MapQuery prefix = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, "한");
        MapQuery middle = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, "밥");

        assertThat(repository.aggregate(prefix, 0.1).stream().mapToLong(GridCell::count).sum())
                .isEqualTo(3);   // 한밥, 한솥, 한우촌
        assertThat(repository.aggregate(middle, 0.1).stream().mapToLong(GridCell::count).sum())
                .isZero();       // 앞부분 일치만 지원한다
    }
}
