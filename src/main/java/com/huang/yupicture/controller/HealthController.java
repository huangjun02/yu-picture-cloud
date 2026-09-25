package com.huang.yupicture.controller;

import com.huang.yupicture.common.BaseResponse;
import com.huang.yupicture.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping("")
    public BaseResponse<String> health() {
        return ResultUtils.success("ok");
    }
}
