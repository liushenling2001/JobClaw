package io.jobclaw.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebConsoleViewController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
