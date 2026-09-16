package com.example.commercialarea.web;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class PageAndAssetTest {

    @Autowired MockMvc mvc;

    @Test
    void 루트는_지도_컨테이너가_있는_HTML을_반환한다() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"map\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/app.js")));
    }

    @Test
    void Leaflet_에셋이_webjar에서_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet/dist/leaflet.js")).andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet/dist/leaflet.css")).andExpect(status().isOk());
    }

    @Test
    void markercluster_에셋이_webjar에서_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet.markercluster/dist/leaflet.markercluster.js"))
                .andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet.markercluster/dist/MarkerCluster.css"))
                .andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet.markercluster/dist/MarkerCluster.Default.css"))
                .andExpect(status().isOk());
    }

    @Test
    void Leaflet_기본_마커_이미지가_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet/dist/images/marker-icon.png")).andExpect(status().isOk());
    }

    @Test
    void 정적_자원이_서빙된다() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
    }
}
