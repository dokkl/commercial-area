package com.example.commercialarea.store;

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

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreQueryTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        List<Store> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(new Store("Q" + i, "가게" + i, i == 0 ? "본점" : null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "서울특별시 강남구 역삼동 1-" + i, "테스트빌딩",
                    "서울특별시 강남구 테헤란로 " + i, "2",
                    127.0 + i * 0.0001, 37.5 + i * 0.0001));
        }
        batch.add(new Store("QJEJU", "제주국수", null,
                "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                "50", "제주특별자치도", "50110", "제주시", "50110550", "일도1동",
                "제주 지번", null, "제주 도로명", null,
                126.5, 33.4));
        repository.insertBatch(batch);
    }

    @Test
    void 점_조회는_bbox_안의_행만_반환한다() {
        List<MapPoint> points = repository.findPoints(seoul());

        assertThat(points).hasSize(25);
        assertThat(points).noneMatch(p -> p.id().equals("QJEJU"));
        assertThat(points).allMatch(p -> p.large().equals("음식"));
    }

    @Test
    void 필터된_건수를_센다() {
        assertThat(repository.countFiltered(seoul())).isEqualTo(25);

        MapQuery jeju = new MapQuery(33.0, 34.0, 126.0, 127.0, 11,
                null, null, null, null, null, null, null);
        assertThat(repository.countFiltered(jeju)).isEqualTo(1);
    }

    @Test
    void 목록은_요청한_크기만큼_반환한다() {
        List<StoreSummary> page0 = repository.findPage(seoul(), 0, 20);

        assertThat(page0).hasSize(20);
        assertThat(page0.get(0).largeName()).isEqualTo("음식");
        assertThat(page0.get(0).roadAddress()).startsWith("서울특별시");
    }

    @Test
    void 마지막_페이지는_남은_만큼만_반환한다() {
        assertThat(repository.findPage(seoul(), 1, 20)).hasSize(5);
    }

    @Test
    void 범위를_넘은_페이지는_빈_목록이다() {
        assertThat(repository.findPage(seoul(), 99, 20)).isEmpty();
    }

    @Test
    void 목록_정렬은_페이지_간_안정적이다() {
        List<String> all = new ArrayList<>();
        repository.findPage(seoul(), 0, 20).forEach(s -> all.add(s.id()));
        repository.findPage(seoul(), 1, 20).forEach(s -> all.add(s.id()));

        assertThat(all).doesNotHaveDuplicates().hasSize(25);
    }

    @Test
    void 단건_조회는_전체_필드를_반환한다() {
        StoreDetail detail = repository.findById("Q0").orElseThrow();

        assertThat(detail.storeName()).isEqualTo("가게0");
        assertThat(detail.branchName()).isEqualTo("본점");
        assertThat(detail.buildingName()).isEqualTo("테스트빌딩");
        assertThat(detail.floorInfo()).isEqualTo("2");
        assertThat(detail.sggName()).isEqualTo("강남구");
        assertThat(detail.dongName()).isEqualTo("역삼1동");
        assertThat(detail.lat()).isEqualTo(37.5);
    }

    @Test
    void 없는_id는_빈_Optional이다() {
        assertThat(repository.findById("NOPE")).isEmpty();
    }
}
