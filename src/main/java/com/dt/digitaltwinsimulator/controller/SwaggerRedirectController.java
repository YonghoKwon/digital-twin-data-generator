package com.dt.digitaltwinsimulator.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SwaggerRedirectController {
    @GetMapping("/")
    public String redirectToUi() {
        return "redirect:/ui/";
    }

    @GetMapping("/swagger")
    public String redirectToSwaggerUi() {
        return "redirect:/swagger-ui/index.html";
    }
}
