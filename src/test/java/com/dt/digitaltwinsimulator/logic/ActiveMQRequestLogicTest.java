package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveMQRequestLogicTest {
    private final ActiveMQRequestLogic logic = new ActiveMQRequestLogic(
            new TaskCancellationLogic(),
            new TaskExecutionStatusLogic(),
            new ActiveMqBrokerProperties(),
            JsonMapper.builder().build()
    );
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void resolveMessageCountUsesExplicitMessageCountFirst() throws Exception {
        int count = invokeResolveMessageCount(true, 60_000, 1_000, 123);

        assertThat(count).isEqualTo(123);
    }

    @Test
    void resolveMessageCountReturnsOneWhenRepeatIsDisabled() throws Exception {
        int count = invokeResolveMessageCount(false, 60_000, 1_000, 0);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void resolveMessageCountUsesRepeatTimeAndDelayTimeAsDuration() throws Exception {
        int count = invokeResolveMessageCount(true, 10_000, 2_000, 0);

        assertThat(count).isEqualTo(5);
    }

    @Test
    void renderTemplateReplacesPlaceholdersByDataOrder() throws Exception {
        String rendered = invokeRenderTemplate("\"a\": \"{{a}}\", \"b\": \"{{b}}\"", new String[]{"A", "B"});

        assertThat(rendered).isEqualTo("\"a\": \"A\", \"b\": \"B\"");
    }

    @Test
    void renderTemplateFailsWhenDataColumnsAreInsufficient() {
        assertThatThrownBy(() -> invokeRenderTemplate("\"a\": \"{{a}}\", \"b\": \"{{b}}\"", new String[]{"A"}))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("placeholder");
    }

    @Test
    void createStructuredMessageProducesValidJson() throws Exception {
        String json = invokeCreateStructuredMessage("TC001", Map.of("temperature", "25.5", "status", "OK"));

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("CREATE_TIMESTAMP").asString()).matches("\\d{17}");
        assertThat(root.get("MESSAGE_ID").asString()).isEqualTo("TC001");
        assertThat(root.get("DATA_MAP").properties()).isNotEmpty();
        assertThat(json).startsWith("{" + System.lineSeparator() + "  \"CREATE_TIMESTAMP\" : ");
    }

    private int invokeResolveMessageCount(boolean repeatBoolean, int repeatTime, int delayTime, int explicitMessageCount) throws Exception {
        Method method = ActiveMQRequestLogic.class.getDeclaredMethod(
                "resolveMessageCount",
                boolean.class,
                int.class,
                int.class,
                int.class
        );
        method.setAccessible(true);
        return (int) method.invoke(logic, repeatBoolean, repeatTime, delayTime, explicitMessageCount);
    }

    private String invokeRenderTemplate(String template, String[] data) throws Exception {
        Method method = ActiveMQRequestLogic.class.getDeclaredMethod("renderTemplate", String.class, String[].class);
        method.setAccessible(true);
        return (String) method.invoke(logic, template, data);
    }

    @SuppressWarnings("unchecked")
    private String invokeCreateStructuredMessage(String tcName, Map<String, String> payload) throws Exception {
        Method method = ActiveMQRequestLogic.class.getDeclaredMethod("createStructuredMessage", String.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(logic, tcName, payload);
    }
}
