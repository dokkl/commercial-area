package com.example.commercialarea.lookup;

import com.example.commercialarea.importer.LookupBuilder;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class LookupApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository storeRepository;
    @Autowired LookupBuilder lookupBuilder;
    @Autowired JdbcClient jdbc;

    private static Store store(String id, String sggCode, String sggName,
                               String dongCode, String dongName,
                               String large, String largeName,
                               String medium, String mediumName,
                               String small, String smallName,
                               double lat, double lon) {
        return new Store(id, "가게" + id, null,
                large, largeName, medium, mediumName, small, smallName,
                "11", "서울특별시", sggCode, sggName, dongCode, dongName,
                "지번", null, "도로명", null, lon, lat);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        storeRepository.insertBatch(List.of(
                store("R1", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20101", "백반·가정식", 37.50, 127.02),
                store("R2", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20102", "국수·면요리", 37.51, 127.03),
                store("R3", "11680", "강남구", "11680520", "역삼2동",
                        "G2", "소매", "G205", "식료품 소매", "G20501", "슈퍼마켓", 37.52, 127.04),
                store("R4", "11110", "종로구", "11110515", "사직동",
                        "I2", "음식", "I202", "중식", "I20201", "중식 일반", 37.57, 126.97)
        ));
        lookupBuilder.rebuild();
    }

    @Test
    void 시도_목록은_건수를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sido"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("11"))
                .andExpect(jsonPath("$[0].name").value("서울특별시"))
                .andExpect(jsonPath("$[0].count").value(4));
    }

    @Test
    void 시군구_목록은_행정동_행을_집계하고_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sgg?sido=11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680')].count").value(3))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLat").value(37.50))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLat").value(37.52))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLon").value(127.02))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLon").value(127.04));
    }

    @Test
    void 행정동_목록도_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/dong?sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].count").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].maxLat").value(37.51));
    }

    @Test
    void 업종_대분류는_중복_없이_집계된다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I2')].count").value(3))
                .andExpect(jsonPath("$[?(@.code=='G2')].count").value(1));
    }

    @Test
    void 업종_중분류는_대분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/medium?large=I2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I201')].count").value(2));
    }

    @Test
    void 업종_소분류는_중분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/small?medium=I201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I20101')].name").value("백반·가정식"));
    }

    @Test
    void 업종_목록에는_bbox가_없다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].minLat").doesNotExist());
    }
}
