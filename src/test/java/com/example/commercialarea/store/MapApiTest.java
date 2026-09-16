package com.example.commercialarea.store;

import com.example.commercialarea.config.GlobalExceptionHandler;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Boot 4.0.3 moved MockMvc test auto-configuration (@AutoConfigureMockMvc) out of
 * spring-boot-test-autoconfigure into a new spring-boot-starter-webmvc-test module, which
 * build.gradle.kts does not declare (and per task constraints, must not gain a new dependency
 * for this task). So MockMvc is built by hand here against the real, container-managed
 * MapController and GlobalExceptionHandler beans. Everything else — real StoreService,
 * real StoreRepository, real MySQL testcontainer — is identical to what @AutoConfigureMockMvc
 * would have wired; only DispatcherServlet's own auto-registration is replaced by
 * MockMvcBuilders.standaloneSetup, which drives the same argument-resolution and
 * exception-handling machinery.
 */
@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class MapApiTest {

    @Autowired MapController controller;
    @Autowired GlobalExceptionHandler exceptionHandler;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private MockMvc mvc;

    private void seed(int n) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Store("M" + i, "가게" + i, null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (i % 100) * 0.0001, 37.5 + (i / 100) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    private static String bbox() {
        return "?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11";
    }

    @Test
    void 임계값_이하면_point_모드로_응답한다() throws Exception {
        seed(StoreService.POINT_THRESHOLD);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("point"))
                .andExpect(jsonPath("$.total").value(StoreService.POINT_THRESHOLD))
                .andExpect(jsonPath("$.points.length()").value(StoreService.POINT_THRESHOLD))
                .andExpect(jsonPath("$.cells").doesNotExist());
    }

    @Test
    void 임계값을_넘으면_cluster_모드로_응답한다() throws Exception {
        seed(StoreService.POINT_THRESHOLD + 1);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("cluster"))
                .andExpect(jsonPath("$.total").value(StoreService.POINT_THRESHOLD + 1))
                .andExpect(jsonPath("$.cells").isArray())
                .andExpect(jsonPath("$.points").doesNotExist());
    }

    @Test
    void point_응답에_주소가_들어있지_않다() throws Exception {
        seed(3);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].id").exists())
                .andExpect(jsonPath("$.points[0].name").exists())
                .andExpect(jsonPath("$.points[0].lat").exists())
                .andExpect(jsonPath("$.points[0].lon").exists())
                .andExpect(jsonPath("$.points[0].large").value("음식"))
                .andExpect(jsonPath("$.points[0].roadAddress").doesNotExist());
    }

    @Test
    void bbox가_없으면_MISSING_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/map?maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_BBOX"));
    }

    @Test
    void bbox가_뒤집히면_INVALID_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/map?minLat=38.0&maxLat=37.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }

    @Test
    void 줌이_범위_밖이면_INVALID_ZOOM_400이다() throws Exception {
        mvc.perform(get("/api/map?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=23"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ZOOM"));
    }

    @Test
    void 필터_파라미터가_반영된다() throws Exception {
        seed(10);

        mvc.perform(get("/api/map" + bbox() + "&large=G2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/map" + bbox() + "&large=I2&sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10));
    }

    /**
     * zoom이 숫자로 변환될 수 없으면 Spring이 MethodArgumentTypeMismatchException을 던져야 하고,
     * GlobalExceptionHandler가 이를 400/INVALID_PARAMETER로 매핑해야 한다.
     * Task 7에서는 핸들러를 단위 테스트로만 검증했으므로, 컨트롤러가 실제로 이 경로를 타는지는
     * 여기서 엔드투엔드로 처음 증명한다.
     */
    @Test
    void 줌이_숫자가_아니면_INVALID_PARAMETER_400이다() throws Exception {
        mvc.perform(get("/api/map?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }
}
