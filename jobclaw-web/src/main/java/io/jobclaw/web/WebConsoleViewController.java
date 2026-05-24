package io.jobclaw.web;

import org.springframework.stereotype.Controller;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("!cli")
public class WebConsoleViewController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping({
            "/login",
            "/dashboard",
            "/chat",
            "/sessions",
            "/sessions/{id}",
            "/channels",
            "/providers",
            "/models",
            "/agent",
            "/agents",
            "/cron",
            "/skills",
            "/mcp",
            "/files",
            "/stats",
            "/settings"
    })
    public String spaRoutes() {
        return "forward:/index.html";
    }
}
