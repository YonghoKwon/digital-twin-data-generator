package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.logic.FileDataTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Adapts the shared S1 file template rules to realtime payload objects. */
@Component
public class RealtimeFileSource {
    private final FileDataTemplate templates;
    private final JsonMapper mapper;

    public RealtimeFileSource(SimulatorFileProperties properties, JsonMapper mapper) {
        this.templates = new FileDataTemplate(properties, mapper);
        this.mapper = mapper;
    }

    public List<JsonNode> load(RealtimeRequest.Source source) throws IOException {
        if (!"FILE_DATA".equals(source.getType())) throw new IllegalArgumentException("source.type must be FILE_DATA.");
        String template = templates.readFile(templates.resolveFile(source.getFilePath(), source.getFormatFileName()));
        var rows = templates.readDataLines(templates.resolveFile(source.getFilePath(), source.getDataFileName()), template);
        List<JsonNode> payloads = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            String rendered = templates.renderTemplate(template, rows.get(r)).trim();
            if (!rendered.startsWith("{")) rendered = "{" + rendered + "}";
            try {
                JsonNode node = mapper.readTree(rendered);
                if (node == null || !node.isObject()) throw new IllegalArgumentException();
                payloads.add(node);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid file JSON at row " + r + ".");
            }
        }
        return List.copyOf(payloads);
    }
}
