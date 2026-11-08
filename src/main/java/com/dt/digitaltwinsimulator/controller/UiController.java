package com.dt.digitaltwinsimulator.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Explicit directory index; Spring's static resource handler serves the other assets. */
@Controller
public class UiController {
    @GetMapping("/ui")
    public String uiDirectory() {
        return "redirect:/ui/";
    }

    @GetMapping("/ui/")
    public ResponseEntity<Resource> uiIndex() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/ui/index.html"));
    }
}
