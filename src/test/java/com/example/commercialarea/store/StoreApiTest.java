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
class StoreApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static String bbox() {
        return "?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11";
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        List<Store> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(new Store(String.format("L%02d", i), "가게" + i, i == 0 ? "본점" : null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "서울특별시 강남구 역삼동 1-" + i, "테스트빌딩",
                    "서울특별시 강남구 테헤란로 " + i, "2",
                    127.0 + i * 0.0001, 37.5 + i * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @Test
    void 목록은_페이징_메타와_항목을_반환한다() throws Exception {
        mvc.perform(get("/api/stores" + bbox() + "&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].roadAddress").exists())
                .andExpect(jsonPath("$.items[0].largeName").value("음식"));
    }

    @Test
    void 마지막_페이지는_남은_만큼_반환한다() throws Exception {
        mvc.perform(get("/api/stores" + bbox() + "&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void page와_size를_생략하면_기본값을_쓴다() throws Exception {
        mvc.perform(get("/api/stores" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void 목록에도_같은_bbox_검증이_적용된다() throws Exception {
        mvc.perform(get("/api/stores?minLat=38.0&maxLat=37.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }

    @Test
    void 상세는_전체_필드를_반환한다() throws Exception {
        mvc.perform(get("/api/stores/L00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("가게0"))
                .andExpect(jsonPath("$.branchName").value("본점"))
                .andExpect(jsonPath("$.sggName").value("강남구"))
                .andExpect(jsonPath("$.dongName").value("역삼1동"))
                .andExpect(jsonPath("$.roadAddress").exists())
                .andExpect(jsonPath("$.buildingName").value("테스트빌딩"))
                .andExpect(jsonPath("$.floorInfo").value("2"));
    }

    @Test
    void 없는_상세는_STORE_NOT_FOUND_404다() throws Exception {
        mvc.perform(get("/api/stores/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("STORE_NOT_FOUND"));
    }
}
