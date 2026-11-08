package com.dt.digitaltwinsimulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePropertiesTest {
    @Test
    void bindsExtraFieldsWithoutLosingDefaultOrderEmptyStringsOrSpaces() {
        var source = new MapConfigurationPropertySource(Map.of(
                "simulator.message.file-data-extra-fields[works_code]", "CUSTOM",
                "simulator.message.file-data-extra-fields[extra_field]", "value"));
        var properties = new MessageProperties();
        new Binder(source).bind("simulator.message", Bindable.ofInstance(properties));
        var fields = properties.getFileDataExtraFields();
        assertThat(fields).containsEntry("works_code", "CUSTOM").containsEntry("sndr_inform_edit_pgm_id", "")
                .containsEntry("attribute", " ").containsEntry("extra_field", "value");
        assertThat(fields.keySet().iterator().next()).isEqualTo("works_code");
        assertThat(fields.sequencedKeySet().getLast()).isEqualTo("extra_field");
    }
}
