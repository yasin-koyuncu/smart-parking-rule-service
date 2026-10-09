package com.parkview.ruleengine.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/** {@code fines.reason} is lower-case in the shared schema (CHECK constraint), unlike {@code spot_violations}. */
@Converter
public class LowercaseViolationTypeConverter implements AttributeConverter<ViolationType, String> {

    @Override
    public String convertToDatabaseColumn(ViolationType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public ViolationType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ViolationType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
