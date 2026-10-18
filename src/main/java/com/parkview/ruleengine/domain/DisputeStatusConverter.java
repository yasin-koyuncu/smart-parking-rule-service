package com.parkview.ruleengine.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code fine_disputes.status} is lower case (CHECK constraint). */
@Converter
public class DisputeStatusConverter implements AttributeConverter<DisputeStatus, String> {

    @Override
    public String convertToDatabaseColumn(DisputeStatus attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public DisputeStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DisputeStatus.parse(dbData).orElseThrow();
    }
}
