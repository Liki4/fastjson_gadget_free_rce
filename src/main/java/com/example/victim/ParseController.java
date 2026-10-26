package com.example.victim;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONReader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ParseController {

    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody String body) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        System.out.println("[victim] JSON.parse() tccl=" + (tccl == null ? "null" : tccl.getClass().getName())
                + " body=" + body);
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            Object result = JSON.parse(body);
            resp.put("ok", true);
            resp.put("resultType", result == null ? null : result.getClass().getName());
            resp.put("result", String.valueOf(result));
        } catch (Throwable t) {
            resp.put("ok", false);
            resp.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return resp;
    }

    @PostMapping("/parse2")
    public Map<String, Object> parse2(@RequestBody String body) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        System.out.println("[victim] fastjson2 parseObject(SupportAutoType) tccl="
                + (tccl == null ? "null" : tccl.getClass().getName()) + " body=" + body);
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            Object result = com.alibaba.fastjson2.JSON.parseObject(body, Object.class,
                    JSONReader.Feature.SupportAutoType);
            resp.put("ok", true);
            resp.put("resultType", result == null ? null : result.getClass().getName());
            resp.put("result", String.valueOf(result));
        } catch (Throwable t) {
            resp.put("ok", false);
            resp.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return resp;
    }
}
