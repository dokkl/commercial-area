package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreService {

    /** 이 건수 이하면 개별 마커를, 초과하면 격자 집계를 보낸다. */
    public static final int POINT_THRESHOLD = 2000;

    private final StoreRepository repository;

    public StoreService(StoreRepository repository) {
        this.repository = repository;
    }

    /**
     * 줌이 아니라 실제 건수로 표시 모드를 가른다.
     * 필터를 세게 걸면 줌이 낮아도 결과가 적으므로, 그때는 곧바로 마커를 보여주는 것이 옳다.
     * 총 건수는 집계 쿼리에서 이미 나오므로 별도 COUNT 쿼리가 필요 없다.
     */
    public MapResponse map(MapQuery query) {
        List<GridCell> cells = repository.aggregate(query, query.cellSize());
        long total = cells.stream().mapToLong(GridCell::count).sum();

        if (total <= POINT_THRESHOLD) {
            // 여기서는 결과가 2,000건 이하임이 확정이라 LIMIT이 필요 없다.
            return MapResponse.point(total, repository.findPoints(query));
        }
        return MapResponse.cluster(total, cells);
    }

    public PageResponse<StoreSummary> list(MapQuery query, int page, int size) {
        long total = repository.countFiltered(query);
        List<StoreSummary> items = total == 0 ? List.of() : repository.findPage(query, page, size);
        return new PageResponse<>(page, size, total, items);
    }

    public StoreDetail detail(String storeId) {
        return repository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND",
                        "상가업소번호를 찾을 수 없습니다: " + storeId));
    }
}
