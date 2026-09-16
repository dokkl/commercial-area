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
class StoreRepositoryInsertTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    static Store store(String id, double lat, double lon) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                "서울특별시 강남구 역삼동 1-1", null, "서울특별시 강남구 테헤란로 1", null,
                lon, lat);
    }

    @Test
    void 배치로_적재하면_전부_저장된다() {
        List<Store> batch = List.of(
                store("A1", 37.50, 127.02),
                store("A2", 37.51, 127.03),
                store("A3", 37.52, 127.04));

        repository.insertBatch(batch);

        assertThat(repository.countAll()).isEqualTo(3);
    }

    @Test
    void 같은_배치를_다시_적재해도_중복되지_않는다() {
        List<Store> batch = List.of(store("B1", 37.50, 127.02), store("B2", 37.51, 127.03));

        repository.insertBatch(batch);
        repository.insertBatch(batch);

        assertThat(repository.countAll()).isEqualTo(2);
    }

    @Test
    void 한글과_좌표가_정확히_저장된다() {
        repository.insertBatch(List.of(store("C1", 37.4979, 127.0276)));

        var row = jdbc.sql("SELECT store_name, lat, lon FROM store WHERE store_id = 'C1'")
                .query((rs, n) -> new double[]{rs.getDouble("lat"), rs.getDouble("lon")})
                .single();

        assertThat(row[0]).isEqualTo(37.4979);
        assertThat(row[1]).isEqualTo(127.0276);
        assertThat(jdbc.sql("SELECT store_name FROM store WHERE store_id = 'C1'")
                .query(String.class).single()).isEqualTo("가게C1");
    }
}
