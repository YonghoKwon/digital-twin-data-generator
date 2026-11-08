package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.embedded.EmbeddedBrokerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/dt/broker")
@Tag(name = "Embedded Broker")
public class EmbeddedBrokerController {
    private final EmbeddedBrokerService broker;
    public EmbeddedBrokerController(EmbeddedBrokerService broker) { this.broker = broker; }

    @GetMapping
    public Map<String, Object> status(HttpServletRequest request) { return broker.status(request.getServerName()); }
}

