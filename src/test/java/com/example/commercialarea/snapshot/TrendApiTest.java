package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
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
class TrendApiTest {

    @Autowired MockMvc mvc;
    @Autowired SnapshotRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store_snapshot").update();
        jdbc.sql("DELETE FROM import_log").update();
    }

    private static Store s(String id, String sgg, String small) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "중분류", small, "소분류",
                "11", "서울특별시", sgg, "구", sgg + "510", "동",
                "지번", null, "도로명", null, 127.0, 37.5);
    }

    private void snapshot(String ym, List<Store> rows) {
        repository.insertSnapshotBatch(ym, rows);
        repository.recordImport(ym, rows.size());
    }

    @Test
    void 추이_응답_구조를_반환한다() throws Exception {
        snapshot("202503", List.of(s("A1", "11680", "C1"), s("A2", "11680", "C1")));
        snapshot("202506", List.of(s("A2", "11680", "C1"), s("A3", "11680", "C1")));

        mvc.perform(get("/api/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].ym").value("202503"))
                .andExpect(jsonPath("$.snapshots[0].count").value(2))
                .andExpect(jsonPath("$.snapshots[0].opened").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.snapshots[1].opened").value(1))
                .andExpect(jsonPath("$.snapshots[1].closed").value(1));
    }

    @Test
    void 지역_필터가_반영된다() throws Exception {
        snapshot("202506", List.of(s("A1", "11680", "C1"), s("A2", "11110", "C1")));

        mvc.perform(get("/api/trend?sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].count").value(1));
    }

    @Test
    void 스냅샷이_없으면_빈_배열이다() throws Exception {
        mvc.perform(get("/api/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(0));
    }
}
