package com.example.commercialarea.store;

import java.util.List;

public record PageResponse<T>(int page, int size, long total, List<T> items) {
}
