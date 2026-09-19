package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class AnalysisApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private int seq = 0;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
        jdbc.sql("DELETE FROM industry").update();
        seq = 0;
    }

    private void addStores(int n, String large, String largeN,
                           String medium, String medN, String small, String smallN) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = "A" + (seq++);
            batch.add(new Store(id, "가게" + id, null,
                    large, largeN, medium, medN, small, smallN,
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (seq % 40) * 0.0001, 37.5 + (seq % 40) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    private static String bbox() {
        return "?minLat=37.5&maxLat=37.6&minLon=127.0&maxLon=127.1";
    }

    @Test
    void 분석_응답의_구조를_반환한다() throws Exception {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        mvc.perform(get("/api/analysis" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(15))
                .andExpect(jsonPath("$.areaKm2").isNumber())
                .andExpect(jsonPath("$.densityPerKm2").isNumber())
                .andExpect(jsonPath("$.composition").isArray())
                .andExpect(jsonPath("$.composition[0].level").value("large"))
                .andExpect(jsonPath("$.composition[0].code").value("I2"))
                .andExpect(jsonPath("$.ranking").isArray())
                .andExpect(jsonPath("$.ranking[0].smallCode").value("C1"));
    }

    @Test
    void 업종_필터를_존중해_구성_레벨이_내려간다() throws Exception {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        mvc.perform(get("/api/analysis" + bbox() + "&large=I2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composition[0].level").value("medium"));
    }

    @Test
    void 순위는_최대_10개까지만_반환한다() throws Exception {
        for (int i = 0; i < 12; i++) {
            addStores(12 - i, "I2", "음식", "I2" + i, "중" + i, "S" + i, "소" + i);
        }
        mvc.perform(get("/api/analysis" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranking.length()").value(10));
    }

    @Test
    void 빈_화면은_0값과_빈_배열을_반환한다() throws Exception {
        mvc.perform(get("/api/analysis?minLat=33.0&maxLat=33.1&minLon=126.0&maxLon=126.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.densityPerKm2").value(0.0))
                .andExpect(jsonPath("$.composition.length()").value(0))
                .andExpect(jsonPath("$.ranking.length()").value(0));
    }

    @Test
    void bbox가_없으면_MISSING_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/analysis?maxLat=37.6&minLon=127.0&maxLon=127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_BBOX"));
    }

    @Test
    void bbox가_뒤집히면_INVALID_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/analysis?minLat=37.6&maxLat=37.5&minLon=127.0&maxLon=127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }
}
