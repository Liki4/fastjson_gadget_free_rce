package com.example.victim;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VictimApplication {

    public static void main(String[] args) {
        // Pure default config on JDK 21 + embedded Tomcat. The JDK 9+ lane does not use the
        // direct jar:http:.. class (its "://" internal name is rejected by defineClass since
        // JDK 9). Instead a seed request makes the JVM cache the remote jar behind an open
        // file descriptor, and a follow-up jar:file:/dev/fd/N type reads the class back from
        // that descriptor — a single-slash name that passes both JNI and JDK 9+ validation,
        // so even the Tomcat TCCL's Class.forName-based delegation resolves it.
        SpringApplication.run(VictimApplication.class, args);
        ParserConfig config = ParserConfig.getGlobalInstance();
        System.out.println("[victim] fastjson=" + JSON.VERSION
                + " autoTypeSupport=" + config.isAutoTypeSupport()
                + " safeMode=" + config.isSafeMode()
                + " classLoader=" + ParserConfig.class.getClassLoader().getClass().getName());
    }
}
