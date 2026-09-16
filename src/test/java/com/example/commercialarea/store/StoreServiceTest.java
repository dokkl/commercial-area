package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreServiceTest {

    @Autowired StoreService service;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    /** 강남 일대에 n개의 점을 만든다. */
    private void seed(int n, String largeCode, String largeName) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Store(largeCode + "-" + i, "가게" + i, null,
                    largeCode, largeName, largeCode + "01", "중분류", largeCode + "0101", "소분류",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (i % 100) * 0.0001, 37.5 + (i / 100) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    @Test
    void 임계값_이하면_point_모드다() {
        seed(StoreService.POINT_THRESHOLD, "I2", "음식");

        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("point");
        assertThat(response.total()).isEqualTo(StoreService.POINT_THRESHOLD);
        assertThat(response.points()).hasSize(StoreService.POINT_THRESHOLD);
        assertThat(response.cells()).isNull();
    }

    @Test
    void 임계값을_넘으면_cluster_모드다() {
        seed(StoreService.POINT_THRESHOLD + 1, "I2", "음식");

        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("cluster");
        assertThat(response.total()).isEqualTo(StoreService.POINT_THRESHOLD + 1);
        assertThat(response.cells()).isNotEmpty();
        assertThat(response.points()).isNull();
    }

    @Test
    void cluster_모드의_셀_합계는_total과_같다() {
        seed(StoreService.POINT_THRESHOLD + 500, "I2", "음식");

        MapResponse response = service.map(seoul());

        long sum = response.cells().stream().mapToLong(GridCell::count).sum();
        assertThat(sum).isEqualTo(response.total());
    }

    @Test
    void 필터를_걸어_건수가_줄면_같은_줌에서도_point_모드로_바뀐다() {
        seed(3000, "I2", "음식");
        seed(50, "G2", "소매");

        MapQuery unfiltered = seoul();
        MapQuery filtered = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, "G2", null, null, null);

        assertThat(service.map(unfiltered).mode()).isEqualTo("cluster");
        assertThat(service.map(filtered).mode()).isEqualTo("point");
        assertThat(service.map(filtered).total()).isEqualTo(50);
    }

    @Test
    void 결과가_없으면_point_모드에_빈_목록이다() {
        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("point");
        assertThat(response.total()).isZero();
        assertThat(response.points()).isEmpty();
    }

    @Test
    void 없는_상세를_요청하면_STORE_NOT_FOUND를_던진다() {
        assertThatThrownBy(() -> service.detail("NOPE"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
    }
}
