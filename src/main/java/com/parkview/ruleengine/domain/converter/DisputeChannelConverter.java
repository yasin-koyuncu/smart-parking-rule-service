package com.parkview.ruleengine.domain.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

import com.parkview.ruleengine.domain.type.DisputeChannel;
/** {@code fine_disputes.channel} is lower case (CHECK constraint). */
@Converter
public class DisputeChannelConverter implements AttributeConverter<DisputeChannel, String> {

    @Override
    public String convertToDatabaseColumn(DisputeChannel attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public DisputeChannel convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DisputeChannel.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
