package com.aura.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    @DisplayName("a page is flattened into the shape the client is promised")
    void wrapsAPage() {
        PageResponse<String> response = PageResponse.of(
            new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7));

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("an empty page still reports its paging metadata")
    void emptyPage() {
        PageResponse<String> response = PageResponse.of(
            new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
