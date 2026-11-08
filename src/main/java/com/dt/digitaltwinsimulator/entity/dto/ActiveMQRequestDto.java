package com.dt.digitaltwinsimulator.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@ToString
@Getter
@Setter
public class ActiveMQRequestDto {
    private String activeMQIp;
    private String id;
    private String pw;
    private String topic;
    private boolean typedValues;

    @NotBlank
    private String tcName;

    @Min(0)
    private int delayTime;

    @Min(0)
    @Max(1_000_000)
    private int targetTps;

    private boolean repeatBoolean;

    @Min(0)
    private int repeatTime;

    @Min(0)
    private int messageCount;

    @NotEmpty
    private List<Map<String, String>> format;

    private List<Map<Integer, String>> value;

    @Min(1)
    @Max(2000)
    private int concurrentTasks = 1;
}
