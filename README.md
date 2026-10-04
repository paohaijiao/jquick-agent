# jquick-agent

`jquick-agent` 是一个面向 Spring Boot 应用的 Java Agent 基础框架，通过 `-javaagent` 或 Attach API 挂载，在**不改业务代码实现**的前提下，为 `jquick` 系列工具提供声明式增强能力。

## 特性

- 零侵入：基于 Instrumentation + Javassist 做运行时字节码增强
- 声明式：通过注解驱动能力挂载
- 可扩展：统一 `MethodEnhancer` SPI，方便新增增强器
- 可挂载：同时支持 `premain` 与 `agentmain`

## 当前内置能力

- `@AutoExport`：方法返回后自动触发导出逻辑
- `@AuditLog`：记录方法调用前后与异常信息
- `@HttpProxy`：记录服务调用前后信息，便于后续接入 `jquick-curl`

## 包结构

```text
io.github.paohaijiao.jquick.agent
├─ agent
├─ annotation
├─ config
├─ enhancer
├─ interceptor
├─ support
└─ transformer
```

## Maven 依赖

当前项目已经内置 Agent 打包配置，核心依赖为 Javassist。

## 注解使用示例

```java
@RestController
@RequestMapping("/users")
public class UserController {

    @AutoExport(fileName = "user-list", sheetName = "Users")
    @GetMapping("/export")
    public List<UserVO> export() {
        return userService.list();
    }

    @AuditLog("query-user")
    @GetMapping("/{id}")
    public UserVO detail(@PathVariable String id) {
        return userService.detail(id);
    }
}
```

## 启动方式

### 1. 通过 `-javaagent` 挂载

```bash
java -javaagent:/path/jquick-agent-1.0.0.jar=includes=com.demo,io.github.paohaijiao -jar app.jar
```

参数说明：

- `includes`：指定需要增强的包前缀，多个值逗号分隔

### 2. 通过 Attach API 动态挂载

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("/path/jquick-agent-1.0.0.jar", "includes=com.demo,io.github.paohaijiao");
vm.detach();
```

## Agent MANIFEST

打包后 Jar Manifest 会包含以下配置：

```properties
Premain-Class: io.github.paohaijiao.jquick.agent.agent.JQuickAgent
Agent-Class: io.github.paohaijiao.jquick.agent.agent.JQuickAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

## 测试

项目内已包含单元测试，覆盖：

- agent 参数解析
- transformer 对 `@AutoExport` / `@AuditLog` / `@HttpProxy` 的增强行为
- 异常场景下的审计记录

执行：

```bash
mvn test
```

## 后续扩展建议

- 将 `AutoExportInterceptor` 对接真实的 `jquick-excel`
- 为 `HttpProxyInterceptor` 接入 `jquick-curl`
- 增加 XML 规则加载与统一声明式规则模型
- 增加 Spring MVC / Service 类型匹配策略
