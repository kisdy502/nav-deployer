package com.agv.navdeployer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serve the Vue entry point when an editor URL is opened or refreshed. */
@Controller
public class SpaController {

    @GetMapping({"/editor/{id}", "/map2/{id}"})
    public String editor() {
        return "forward:/index.html";
    }
}
