package com.stt.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React build is bundled into the jar under /static. Client-side routes are
 * forwarded to index.html so a browser refresh on e.g. /history still works.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({"/live", "/history"})
    public String forward() {
        return "forward:/index.html";
    }
}
