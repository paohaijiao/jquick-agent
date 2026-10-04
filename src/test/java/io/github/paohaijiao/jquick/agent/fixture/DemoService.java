package io.github.paohaijiao.jquick.agent.fixture;

import io.github.paohaijiao.jquick.agent.annotation.AuditLog;
import io.github.paohaijiao.jquick.agent.annotation.AutoExport;
import io.github.paohaijiao.jquick.agent.annotation.HttpProxy;

import java.util.Arrays;
import java.util.List;

public class DemoService {

    @AutoExport(fileName = "users", sheetName = "sheetA")
    public List<String> exportUsers() {
        return Arrays.asList("alice", "bob");
    }

    @AuditLog("loadUser")
    public String findUser(String id) {
        return "user-" + id;
    }

    @HttpProxy(service = "order-api")
    public String proxy(String payload) {
        return "proxy-" + payload;
    }

    @AuditLog("error")
    public String fail() {
        throw new IllegalStateException("boom");
    }
}
