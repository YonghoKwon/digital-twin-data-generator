package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.config.MessageProperties;
import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileAndDataDto;
import com.dt.digitaltwinsimulator.realtime.RealtimeFileSource;
import com.dt.digitaltwinsimulator.realtime.RealtimeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FileDataTemplateContractTest {
    @TempDir Path base;
    private ActiveMQRequestLogic legacy;
    private RealtimeFileSource realtime;

    @BeforeEach
    void setup() {
        var properties = new SimulatorFileProperties();
        properties.setBaseDir(base.toString());
        var mapper = JsonMapper.builder().build();
        legacy = new ActiveMQRequestLogic(mock(TaskCancellationLogic.class), mock(TaskExecutionStatusLogic.class),
                mock(BrokerConnectionService.class), mock(MessagePublisher.class), properties,
                new MessageProperties(), new MessageBodyLogger(0), mapper);
        realtime = new RealtimeFileSource(properties, mapper);
    }

    static Stream<Arguments> invalidFiles() {
        String template = "\"a\":\"{{a}}\",\"b\":\"{{b}}\"";
        return Stream.of(
                Arguments.of(template, "\ufeff# comment\n\n", "데이터 파일에 전송할 데이터가 없습니다."),
                Arguments.of(template, "a,b\n", "header 외에 전송할 데이터 row가 없습니다."),
                Arguments.of(template, "a,other\nx,y\n", "데이터 파일 header에 placeholder 컬럼이 없습니다: b"),
                Arguments.of(template, "a,b\nx\n", "데이터 row의 컬럼 수가 header보다 적습니다. rowIndex=1"),
                Arguments.of(template, "x\n", "데이터 row의 컬럼 수가 포맷 placeholder 수보다 적습니다. rowIndex=0"),
                Arguments.of("\"a\":\"{{ }}\"", "x\n", "포맷 파일에 빈 placeholder가 있습니다."),
                Arguments.of("\"a\":1", "x\n", "포맷 파일에 placeholder가 없습니다."),
                Arguments.of(template, "a,b\nx,\"unclosed\n", "CSV 따옴표가 닫히지 않았습니다: x,\"unclosed")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidFiles")
    void bothEntryPointsPreserveExactS1ValidationMessages(String template, String csv, String expected) throws Exception {
        Files.writeString(base.resolve("format.txt"), template);
        Files.writeString(base.resolve("data.csv"), csv);
        var dto = new ActiveMQRequestFileAndDataDto();
        dto.setTcName("TC"); dto.setFilePath("."); dto.setFormatFileName("format.txt"); dto.setDataFileName("data.csv");
        var source = new RealtimeRequest.Source();
        source.setType("FILE_DATA"); source.setFilePath("."); source.setFormatFileName("format.txt"); source.setDataFileName("data.csv");
        assertThatThrownBy(() -> legacy.dryRunFileAndDataTopic(dto, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
        assertThatThrownBy(() -> realtime.load(source))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
    }
}
