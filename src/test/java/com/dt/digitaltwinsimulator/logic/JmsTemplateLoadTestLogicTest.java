package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import com.dt.digitaltwinsimulator.entity.dto.JmsTemplateLoadTestRequestDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JmsTemplateLoadTestLogicTest {
    @Test
    void loadTestUsesRequestOverridesAndKeepsResultCounts() {
        var publisher = mock(MessagePublisher.class);
        var publication = mock(MessagePublisher.Publication.class);
        when(publisher.open(any())).thenReturn(publication);
        var tasks = new TaskCancellationLogic();
        var defaults = new ActiveMqBrokerProperties();
        var logic = new JmsTemplateLoadTestLogic(new BrokerConnectionService(defaults), publisher, new MessageBodyLogger(0), tasks);
        var request = new JmsTemplateLoadTestRequestDto();
        request.setTcName("TC");
        request.setMessageCount(20);
        request.setWorkerCount(3);
        request.setActiveMQIp("tcp://example:2345");
        request.setId("override");
        request.setPw("private");
        request.setTopic("custom.topic");
        var result = logic.run("load", request);
        var settings = ArgumentCaptor.forClass(BrokerSettings.class);
        verify(publisher).open(settings.capture());
        assertThat(settings.getValue()).isEqualTo(new BrokerSettings("tcp://example:2345", "override", "private", "custom.topic"));
        verify(publication, times(20)).publish(eq("custom.topic"), anyString(), eq(java.util.Map.of()));
        verify(publication).close();
        assertThat(result.getSuccessCount()).isEqualTo(20);
        assertThat(result.getFailureCount()).isZero();
        assertThat(result.getRequestedCount()).isEqualTo(20);
        assertThat(tasks.getRunningTaskIds()).isEmpty();
    }

    @Test
    void loadTestDefaultsAndSendFailuresAreCounted() {
        var publisher = mock(MessagePublisher.class);
        var publication = mock(MessagePublisher.Publication.class);
        when(publisher.open(any())).thenReturn(publication);
        doThrow(new IllegalStateException("send failed")).when(publication).publish(any(), any(), any());
        var defaults = new ActiveMqBrokerProperties();
        var tasks = new TaskCancellationLogic();
        var logic = new JmsTemplateLoadTestLogic(new BrokerConnectionService(defaults), publisher, new MessageBodyLogger(0), tasks);
        var request = new JmsTemplateLoadTestRequestDto();
        request.setTcName("TC");
        request.setMessageCount(3);
        var result = logic.run("load", request);
        verify(publisher).open(BrokerSettings.resolve(defaults, null, null, null, null));
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isEqualTo(3);
        assertThat(tasks.getRunningTaskIds()).isEmpty();
    }
}
