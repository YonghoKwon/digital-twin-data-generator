package com.dt.digitaltwinsimulator.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class JmsTemplateLoadTestRequestDto {
    private String activeMQIp;
    private String id;
    private String pw;
    @NotBlank
    private String tcName;

    @Min(1)
    @Max(1_000_000)
    private int messageCount = 1;

    @Min(0)
    private int delayTime;

    @Min(0)
    @Max(1_000_000)
    private int targetTps;

    @Min(1)
    @Max(2000)
    private int workerCount = 1;

    private String payload = "{}";

    private String topic;
}
