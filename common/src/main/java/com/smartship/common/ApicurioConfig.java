package com.smartship.common;

import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Apicurio Registry-specific configuration helpers.
 */
public class ApicurioConfig {

    /**
     * Create Apicurio Serde configuration map.
     *
     * @param autoRegister Whether to auto-register schemas
     * @return Configuration map
     */
    public static Map<String, Object> createSerdeConfig(boolean autoRegister) {
        Map<String, Object> config = new HashMap<>();
        config.put(SerdeConfig.REGISTRY_URL, KafkaConfig.getApicurioRegistryUrl());
        config.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, String.valueOf(autoRegister));
        config.put(AvroSerdeConfig.USE_SPECIFIC_AVRO_READER, "true");
        return config;
    }

    /**
     * Create Apicurio Serde configuration map with auto-registration enabled.
     *
     * @return Configuration map
     */
    public static Map<String, Object> createSerdeConfig() {
        return createSerdeConfig(true);
    }
}
