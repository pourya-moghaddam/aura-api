package com.aura.catalog.color;

import com.aura.catalog.color.dto.ColorRequest;
import com.aura.catalog.color.dto.ColorResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ColorService {

    private final ColorRepository colorRepository;

    /** Everything, including retired colours — the admin needs to see what exists to manage it. */
    @Transactional(readOnly = true)
    public List<ColorResponse> listAll() {
        return colorRepository.findAllByOrderBySortOrderAscNameAsc().stream()
            .map(ColorResponse::from).toList();
    }

    /** Active only — what a seller may pick and what the storefront filter offers. */
    @Transactional(readOnly = true)
    public List<ColorResponse> listActive() {
        return colorRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc().stream()
            .map(ColorResponse::from).toList();
    }

    @Transactional
    public ColorResponse create(ColorRequest request) {
        requireNameAvailable(request.name(), null);

        Color color = Color.of(request.name().trim(), normalizeHex(request.hexCode()), request.sortOrder());
        if (request.isActive() != null) {
            color.setActive(request.isActive());
        }
        return ColorResponse.from(colorRepository.save(color));
    }

    @Transactional
    public ColorResponse update(long id, ColorRequest request) {
        Color color = require(id);
        requireNameAvailable(request.name(), id);

        color.setName(request.name().trim());
        color.setHexCode(normalizeHex(request.hexCode()));
        color.setSortOrder(request.sortOrder());
        if (request.isActive() != null) {
            color.setActive(request.isActive());
        }
        return ColorResponse.from(colorRepository.save(color));
    }

    /**
     * Deleting a colour that variants still reference would orphan them, so it is refused. The
     * admin's actual intent in that situation is nearly always to retire it — deactivate keeps
     * existing products intact while removing it from every picker.
     */
    @Transactional
    public void delete(long id) {
        Color color = require(id);

        if (colorRepository.isUsedByAnyVariant(id)) {
            throw new BusinessRuleException("color-in-use",
                "This colour is used by existing product variants. Deactivate it instead.");
        }
        colorRepository.delete(color);
    }

    private void requireNameAvailable(String name, Long excludingId) {
        colorRepository.findByNameIgnoreCase(name.trim())
            .filter(existing -> !existing.getId().equals(excludingId))
            .ifPresent(existing -> {
                throw new ConflictException("color-name-taken",
                    "A colour named '" + existing.getName() + "' already exists.");
            });
    }

    /**
     * Stored uppercase so {@code #ff0000} and {@code #FF0000} are one colour rather than two rows
     * that render identically and filter separately.
     */
    private String normalizeHex(String hexCode) {
        return hexCode.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public Color require(long id) {
        return colorRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Color", id));
    }
}
