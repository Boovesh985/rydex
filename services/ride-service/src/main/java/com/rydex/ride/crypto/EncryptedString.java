package com.rydex.ride.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Encrypts a string column on write and decrypts it on read ({@link FieldCipher}). */
@Component
@Converter
public class EncryptedString implements AttributeConverter<String, String> {

    private final FieldCipher cipher;

    public EncryptedString(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
