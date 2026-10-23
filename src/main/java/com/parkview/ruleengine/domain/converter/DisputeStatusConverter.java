package com.parkview.ruleengine.domain.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.parkview.ruleengine.domain.type.DisputeStatus;
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
