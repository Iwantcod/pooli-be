package com.pooli.family.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Family", description = "가족 관련 API")
@RestController
@RequestMapping("/api/families")
public class FamilyController {

}