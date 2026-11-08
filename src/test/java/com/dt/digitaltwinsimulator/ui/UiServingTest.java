package com.dt.digitaltwinsimulator.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"simulator.embedded-broker.enabled=false", "simulator.http-logging.enabled=false"})
@AutoConfigureMockMvc
class UiServingTest {
    @Autowired MockMvc mvc;

    @Test
    void rootAndBareDirectoryRedirectToUi() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/ui/"));
        mvc.perform(get("/ui")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/ui/"));
    }

    @Test
    void swaggerAliasRemainsAvailable() throws Exception {
        mvc.perform(get("/swagger")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    @Test
    void uiDirectoryAndIndexServeKoreanHtmlWithLocalAssets() throws Exception {
        for (String path : new String[]{"/ui/", "/ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("<html lang=\"ko\">")))
                    .andExpect(content().string(containsString("type=\"module\" src=\"/ui/app.js\"")))
                    .andExpect(content().string(containsString("href=\"/ui/styles.css\"")));
        }
    }

    @Test
    void cssAndEveryJavascriptModuleAreServedWithCorrectMediaTypes() throws Exception {
        mvc.perform(get("/ui/styles.css")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/css")));
        var modules = new PathMatchingResourcePatternResolver().getResources("classpath*:/static/ui/**/*.js");
        assertThat(modules.length).isGreaterThanOrEqualTo(12);
        for (var module : modules) {
            String url = module.getURL().toString();
            String path = url.substring(url.indexOf("/static/ui/") + "/static".length());
            var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/javascript", "application/javascript");
            String source = module.getContentAsString(StandardCharsets.UTF_8);
            assertThat(source).doesNotContain("innerHTML", "https://cdn.", "http://cdn.");
        }
    }
}
