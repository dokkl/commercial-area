package com.example.commercialarea.snapshot;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TrendService {

    private final SnapshotRepository repository;

    public TrendService(SnapshotRepository repository) {
        this.repository = repository;
    }

    public TrendResponse trend(TrendQuery query) {
        List<SnapshotCount> counts = repository.countBySnapshot(query);

        List<SnapshotTrend> out = new ArrayList<>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            SnapshotCount cur = counts.get(i);
            Long opened = null;
            Long closed = null;
            if (i > 0) {
                String prev = counts.get(i - 1).ym();
                opened = repository.openedBetween(prev, cur.ym(), query);
                closed = repository.closedBetween(prev, cur.ym(), query);
            }
            out.add(new SnapshotTrend(cur.ym(), cur.count(), opened, closed));
        }
        return new TrendResponse(out);
    }
}
