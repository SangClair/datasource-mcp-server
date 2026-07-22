# Dameng MCP Server

## 项目介绍

基于 [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) 协议的多数据库查询服务。支持**达梦（DM）**、**Oracle**、**MySQL** 三种数据库，面向 Claude Desktop、Cursor、Cherry Studio 等 LLM 客户端，提供自然语言驱动的数据库查询、统计、分析和数据操作能力。

通过标准 MCP 协议，AI 助手可以直接连接和操作您的数据库，实现智能化的数据探索与管理。支持两种运行模式：**Stdio 模式**（本地 MCP 客户端）与 **HTTP/SSE 模式**（网络暴露 MCP 服务 + 内置 Web 数据源管理页面）。

## 功能特性

- **双运行模式**：`stdio`（默认，本地进程通信）与 `http`（SSE 传输 + Web 管理页面），通过 Spring Profile 一键切换
- **Web 动态数据源管理**：http 模式下提供内置 Web 页面 + REST API，可在运行时新增/删除/测试数据源，配置以 JSON 文件持久化，重启自动恢复
- **多数据源支持**：支持同时配置多个数据源，每个数据源可设置独立的名称和用途描述，运行时按名称路由
- **多数据库类型**：内置达梦、Oracle、MySQL 三种数据库适配器，统一接口无缝切换
- **只读查询工具（7 个）**：数据源列表、Schema 列表、表列表、表结构、SQL 查询、样本数据、表统计、列统计
- **写操作工具（3 个）**：INSERT、UPDATE、DELETE 操作，依赖 MCP 客户端确认机制保障安全
- **严格安全控制**：白名单 + 黑名单 + 注释剥离 + 多语句注入检测 + 标识符校验，多层防护
- **可扩展适配器架构**：`DatabaseAdapter` + `DatabaseDialect` 双接口设计，新增数据库类型只需实现两个接口

## 技术栈

| 组件 | 技术 | 版本 |
| ---- | ---- | ---- |
| 框架 | Spring Boot | 3.4.4 |
| MCP 协议 | Spring AI MCP Server (Stdio + WebMVC/SSE) | 1.0.0-M7 |
| 连接池 | Alibaba Druid | 1.2.23 |
| 达梦驱动 | DmJdbcDriver18 | 8.1.3.140 |
| Oracle 驱动 | ojdbc11 | 23.3.0.23.09 |
| MySQL 驱动 | mysql-connector-j | Spring Boot 管理 |
| 构建工具 | Maven | 3.x |
| JDK | Java | 17+ |

## 快速开始

### 前置条件

- JDK 17 或更高版本
- Maven 3.6+
- 至少一个可用的数据库实例（达梦 / Oracle / MySQL）
- 对应数据库的 JDBC 驱动（项目已内置）

### 构建

```bash
# 克隆项目
git clone <repository-url>
cd dameng-mcp-server

# 编译打包
mvn clean package -DskipTests
```

构建完成后，JAR 文件位于 `target/dameng-mcp-server-1.0.0-SNAPSHOT.jar`。

### 运行

本服务支持两种运行模式，通过 Spring Profile 切换（互不干扰）。

#### 模式一：Stdio 模式（默认）

面向 Claude Desktop / Cursor 等本地 MCP 客户端，通过标准输入输出通信，不监听网络端口。

```bash
# 直接运行（默认即 stdio 模式）
java -jar target/dameng-mcp-server-1.0.0-SNAPSHOT.jar

# 或通过 Maven 运行
mvn spring-boot:run
```

#### 模式二：HTTP / SSE 模式

通过 SSE (Spring AI WebMVC) 暴露 MCP 服务，并提供内置 Web 管理页面用于运行时动态管理数据源。

```bash
# 启用 http 模式（默认端口 8080）
java -jar target/dameng-mcp-server-1.0.0-SNAPSHOT.jar --spring.profiles.active=http

# 或使用环境变量
SPRING_PROFILES_ACTIVE=http java -jar target/dameng-mcp-server-1.0.0-SNAPSHOT.jar

# 自定义端口
java -jar target/dameng-mcp-server-1.0.0-SNAPSHOT.jar --spring.profiles.active=http --server.port=9090
```

启动后：

- **MCP SSE 接入地址**：`http://<host>:8080/sse`（消息端点 `/mcp/message`），配置到支持 SSE 的 MCP 客户端即可调用数据库工具。
- **Web 数据源管理页面**：浏览器打开 `http://<host>:8080/`，可查看/新增/删除/测试数据源。

## HTTP 模式与动态数据源管理

### Web 管理页面

http 模式下访问 `http://<host>:8080/`，页面提供：

- 已注册数据源列表（区分「内置」= application.yml 配置、「动态」= 运行时通过 Web 添加）
- 新增数据源表单（类型下拉、URL、用户名、密码、只读开关、描述），支持「测试连接」与「保存并注册」
- 仅「动态」数据源可通过页面删除；「内置」数据源不可删除

### REST API

所有接口基础路径 `/api/datasources`，仅在 http 模式生效：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/api/datasources` | 列出所有数据源（不含密码） |
| POST | `/api/datasources` | 新增数据源（请求体含 name/type/url/username/password/readonly/description） |
| DELETE | `/api/datasources/{name}` | 删除动态数据源 |
| POST | `/api/datasources/test` | 测试连接（不保存） |

请求/响应示例：

```bash
# 测试连接
curl -X POST http://localhost:8080/api/datasources/test \
  -H 'Content-Type: application/json' \
  -d '{"name":"pg-test","type":"mysql","url":"jdbc:mysql://host:3306/db","username":"root","password":"pwd"}'

# 新增数据源
curl -X POST http://localhost:8080/api/datasources \
  -H 'Content-Type: application/json' \
  -d '{"name":"finance-oracle","type":"oracle","url":"jdbc:oracle:thin:@host:1521:ORCL","username":"u","password":"p","readonly":true,"description":"财务库"}'
```

统一响应结构：`{"success": true|false, "message": "...", "data": ...}`。

### 持久化

- 通过 Web 新增的动态数据源会写入嵌入式 H2 数据库，应用重启时自动恢复。
- H2 数据库文件基础路径由 `mcp.dynamic-datasource-h2` 配置，默认 `${user.home}/.dameng-mcp/datasources`（实际生成 `datasources.mv.db`）。
- **密码以明文存储**，请务必：限制数据库文件权限（如 `chmod 600`）、将服务部署在受信内网、使用只读数据库账户。

### 访问令牌（保护 MCP 端点 + 管理 API）

配置 `mcp.web.access-token` 为非空值后，[AccessTokenFilter](src/main/java/com/dameng/mcp/web/AccessTokenFilter.java) 会对以下敏感端点强制校验请求头 `X-Access-Token`（默认关闭）：

- `/sse`、`/mcp/**`：MCP 传输端点（直接关系到数据库查询/写入能力）；
- `/api/**`：数据源管理接口。

静态管理页面（`/`）放行，页面顶部提供令牌输入框，令牌保存在浏览器本地。

```yaml
mcp:
  web:
    access-token: "用足够长的随机串，如 openssl rand -hex 32 生成"
```

MCP 客户端连接 `/sse` 时也需携带该令牌。以 Cursor 为例：

```json
{
  "mcpServers": {
    "dameng-mcp": {
      "url": "https://your-host:8080/sse",
      "headers": { "X-Access-Token": "你的令牌" }
    }
  }
}
```

> 对无法自定义请求头的客户端，可退而用查询参数 `?access_token=你的令牌`，但令牌可能出现在访问日志中，安全性弱于请求头。

### 无内网隔离时的安全加固清单

若无法做到网络隔离（服务需在公网或不可信网络暴露），务必叠加以下措施：

1. **启用 HTTPS**：令牌走明文 HTTP 会被中间人窃听。开启 `server.ssl`（`application.yml` http 段已附证书生成示例），或用 Nginx/Caddy 反向代理终止 TLS。
2. **设置强随机访问令牌**并定期轮换，切勿使用默认空值或弱口令。
3. **最小权限**：数据库账户尽量使用只读账户，数据源配置 `readonly: true`，从源头限制“写工具”的破坏面。
4. **收紧文件权限**：动态数据源 JSON 含明文密码，`chmod 600 ~/.dameng-mcp/datasources.json`，并限制运行账户。
5. **绑定与限流**：可用 `server.address` 绑定指定网卡；在反向代理层做 IP 白名单、速率限制与 fail2ban，防止令牌被暴力枚举。
6. **审计**：过滤器会以 `WARN` 记录被拒绝的未授权访问，便于监控异常来源。

> **安全提示**：访问令牌 + HTTPS 是“无内网隔离”场景下的最低要求；仅靠明文 HTTP 上的令牌不足以抵御网络嗅探。

## 多数据源配置

在 `src/main/resources/application.yml` 中通过 `mcp.datasources` 配置多个数据源：

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        name: dameng-mcp-server
        version: 2.0.0
        stdio: true

mcp:
  datasources:
    # 达梦数据库示例
    - name: dm-prod
      description: "生产环境 - 达梦数据库"
      type: dameng
      url: jdbc:dm://192.168.1.100:5236
      username: SYSDBA
      password: your_password

    # Oracle 数据库示例
    - name: finance-oracle
      description: "财务系统 - Oracle数据库"
      type: oracle
      url: jdbc:oracle:thin:@192.168.1.101:1521:ORCL
      username: finance_user
      password: your_password

    # MySQL 数据库示例
    - name: log-mysql
      description: "日志系统 - MySQL数据库"
      type: mysql
      url: jdbc:mysql://192.168.1.102:3306/logs?useSSL=false&serverTimezone=UTC
      username: root
      password: your_password

logging:
  level:
    root: OFF
```

### 配置项说明

| 属性 | 必填 | 默认值 | 说明 |
| ---- | ---- | ------ | ---- |
| name | 是 | - | 数据源名称，全局唯一，MCP 调用时定位 |
| description | 否 | - | 数据源用途描述，供 LLM 理解选择 |
| type | 是 | - | 数据库类型：`dameng` / `oracle` / `mysql` |
| url | 是 | - | JDBC 连接 URL |
| username | 是 | - | 数据库用户名 |
| password | 是 | - | 数据库密码 |
| initialSize | 否 | 5 | Druid 初始化连接数 |
| minIdle | 否 | 5 | Druid 最小空闲连接数 |
| maxActive | 否 | 20 | Druid 最大活跃连接数 |
| maxWait | 否 | 60000 | 获取连接最大等待时间（毫秒） |

> **注意**：第一个配置的数据源将作为默认数据源，当 MCP 调用未指定 datasource 参数时自动使用。

## 环境变量

支持通过环境变量覆盖数据源连接信息（适用于容器化部署）：

| 环境变量 | 说明 | 默认值 |
| -------- | ---- | ------ |
| DM_HOST | 达梦数据库主机地址 | localhost |
| DM_PORT | 达梦数据库端口 | 5236 |
| DM_USERNAME | 达梦数据库用户名 | SYSDBA |
| DM_PASSWORD | 达梦数据库密码 | SYSDBA |

配置示例（在 `application.yml` 中使用）：

```yaml
mcp:
  datasources:
    - name: default
      description: "默认达梦数据库"
      type: dameng
      url: jdbc:dm://${DM_HOST:localhost}:${DM_PORT:5236}
      username: ${DM_USERNAME:SYSDBA}
      password: ${DM_PASSWORD:SYSDBA}
```

## MCP 客户端配置

多数据源场景下，**推荐使用外部 `application.yml` 配置文件**管理所有数据源，通过 `--spring.config.location` 参数在 MCP 客户端启动 jar 时指定。

### 配置方式说明

- **推荐做法（多数据源）**：将 `application.yml` 配置文件放在独立目录（如 `~/.dameng-mcp/application.yml`），在其中配置所有数据源（达梦 / Oracle / MySQL 任意组合），启动时通过 `--spring.config.location=file:/path/to/application.yml` 指定。
- **快捷方式（单数据源）**：如果只有单个达梦数据源，可不指定外部配置文件，直接使用环境变量（`DM_HOST`、`DM_PORT`、`DM_USERNAME`、`DM_PASSWORD`）配合内置默认配置即可启动。

### 外部 application.yml 完整示例（多数据源）

以下示例展示一个达梦 + 一个 Oracle + 一个 MySQL 的完整配置，可保存为 `~/.dameng-mcp/application.yml`：

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        name: dameng-mcp-server
        version: 2.0.0
        stdio: true

mcp:
  datasources:
    # 达梦数据库
    - name: dm-prod
      description: "生产环境 - 达梦数据库"
      type: dameng
      url: jdbc:dm://192.168.1.100:5236
      username: SYSDBA
      password: your_password

    # Oracle 数据库
    - name: finance-oracle
      description: "财务系统 - Oracle数据库"
      type: oracle
      url: jdbc:oracle:thin:@192.168.1.101:1521:ORCL
      username: finance_user
      password: your_password

    # MySQL 数据库
    - name: log-mysql
      description: "日志系统 - MySQL数据库"
      type: mysql
      url: jdbc:mysql://192.168.1.102:3306/logs?useSSL=false&serverTimezone=UTC
      username: root
      password: your_password

logging:
  level:
    root: OFF
```

### Claude Desktop

编辑 `claude_desktop_config.json`，通过 `--spring.config.location` 指定外部配置文件：

```json
{
  "mcpServers": {
    "dameng-mcp-server": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/dameng-mcp-server-1.0.0-SNAPSHOT.jar",
        "--spring.config.location=file:/path/to/your-config/application.yml"
      ]
    }
  }
}
```

### Cherry Studio

在 Cherry Studio 的 MCP 服务器设置中添加：

```json
{
  "mcpServers": {
    "dameng-mcp-server": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/dameng-mcp-server-1.0.0-SNAPSHOT.jar",
        "--spring.config.location=file:/path/to/your-config/application.yml"
      ]
    }
  }
}
```

### Cursor

在 Cursor 的 MCP 配置文件 `.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "dameng-mcp-server": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/dameng-mcp-server-1.0.0-SNAPSHOT.jar",
        "--spring.config.location=file:/path/to/your-config/application.yml"
      ]
    }
  }
}
```

> **提示**：
> - 请将 `/path/to/dameng-mcp-server-1.0.0-SNAPSHOT.jar` 替换为实际的 JAR 文件绝对路径。
> - 请将 `/path/to/your-config/application.yml` 替换为外部配置文件的实际绝对路径。
> - **单数据源快捷方式**：若仅使用单个达梦数据源，可省略 `--spring.config.location` 参数，并通过 `env` 字段传入 `DM_HOST` / `DM_PORT` / `DM_USERNAME` / `DM_PASSWORD` 环境变量即可。

## 可用工具列表

### 元数据工具

| 工具名 | 描述 | 参数 |
| ------ | ---- | ---- |
| `list_datasources` | 列出所有可用的数据源连接，包含名称、类型和用途描述 | 无 |
| `list_schemas` | 列出指定数据源中所有可用的 Schema 名称 | `datasource`（可选）：数据源名称 |
| `list_tables` | 列出指定 Schema 下的所有表，包含表名和注释 | `datasource`（可选）：数据源名称<br>`schema`（必填）：模式名称 |
| `describe_table` | 获取表的详细结构信息（列名、类型、可空、默认值、注释） | `datasource`（可选）：数据源名称<br>`schema`（必填）：模式名称<br>`table`（必填）：表名称 |

### 查询工具

| 工具名 | 描述 | 参数 |
| ------ | ---- | ---- |
| `execute_query` | 执行只读 SQL 查询，仅支持 SELECT 语句 | `datasource`（可选）：数据源名称<br>`sql`（必填）：SQL 查询语句<br>`maxRows`（可选）：最大返回行数，默认 100，上限 500 |
| `get_sample_data` | 获取指定表的样本数据，快速预览表内容 | `datasource`（可选）：数据源名称<br>`schema`（必填）：模式名称<br>`table`（必填）：表名称<br>`limit`（可选）：样本行数，默认 10，上限 50 |

### 统计工具

| 工具名 | 描述 | 参数 |
| ------ | ---- | ---- |
| `get_table_statistics` | 获取表的统计概览（行数、表大小等） | `datasource`（可选）：数据源名称<br>`schema`（必填）：模式名称<br>`table`（必填）：表名称 |
| `get_column_statistics` | 获取列的统计信息（去重数、空值数、最值） | `datasource`（可选）：数据源名称<br>`schema`（必填）：模式名称<br>`table`（必填）：表名称<br>`column`（必填）：列名称 |

### 写操作工具

| 工具名 | 描述 | 参数 |
| ------ | ---- | ---- |
| `execute_insert` | 执行 INSERT 插入操作 ⚠️ | `datasource`（可选）：数据源名称<br>`sql`（必填）：INSERT SQL 语句 |
| `execute_update` | 执行 UPDATE 更新操作 ⚠️ | `datasource`（可选）：数据源名称<br>`sql`（必填）：UPDATE SQL 语句 |
| `execute_delete` | 执行 DELETE 删除操作 ⚠️ | `datasource`（可选）：数据源名称<br>`sql`（必填）：DELETE SQL 语句 |

> **⚠️ 写操作工具**会在 MCP 客户端弹出确认对话框，用户确认后才会执行。

## 安全机制

### 只读查询安全

采用**白名单 + 黑名单**双重验证策略：

**白名单（允许的起始关键字）：**
- `SELECT`
- `WITH`
- `EXPLAIN`
- `SHOW`

**黑名单（禁止的关键字）：**
- DDL：`CREATE`、`ALTER`、`DROP`、`TRUNCATE`、`RENAME`
- DML 写入：`INSERT`、`UPDATE`、`DELETE`、`MERGE`、`UPSERT`
- DCL：`GRANT`、`REVOKE`
- 危险操作：`EXEC`、`EXECUTE`、`CALL`、`INTO OUTFILE`、`LOAD DATA`

### 写操作安全

写操作使用独立的安全验证流程：

**白名单（允许的起始关键字）：**
- `INSERT`
- `UPDATE`
- `DELETE`

**黑名单（禁止的关键字）：**
- DDL：`CREATE`、`ALTER`、`DROP`、`TRUNCATE`、`RENAME`
- DCL：`GRANT`、`REVOKE`
- 危险操作：`EXEC`、`EXECUTE`、`CALL`、`INTO OUTFILE`、`LOAD DATA`

### 多层防护矩阵

| 防护层 | 机制 | 说明 |
| ------ | ---- | ---- |
| 注释剥离 | 移除 `--` 和 `/* */` 注释 | 防止通过注释绕过关键字检测 |
| 白名单检查 | SQL 起始关键字匹配 | 确保 SQL 类型符合预期 |
| 黑名单检查 | 全文禁止关键字扫描 | 拦截危险操作关键字 |
| 多语句注入检测 | 剥离字符串后检测分号 | 防止 SQL 注入攻击 |
| 标识符校验 | 仅允许字母/数字/下划线/点号 | 防止拼接注入 |
| 操作类型匹配 | 工具与 SQL 类型强绑定 | `executeInsert` 只允许 INSERT |
| WHERE 条件检测 | UPDATE/DELETE 缺少 WHERE 时告警 | 防止全表操作 |
| 行数限制 | 自动包装 LIMIT/ROWNUM | 防止大结果集 |

### MCP 客户端确认机制

写操作工具（`execute_insert`、`execute_update`、`execute_delete`）的 `@Tool` 注解中包含明确的**警告描述**。MCP 协议规范要求客户端在执行具有副作用的操作前向用户展示确认对话框。

工作流程：
1. LLM 生成写入 SQL 并调用写操作工具
2. MCP 客户端拦截请求，向用户展示 SQL 内容和警告信息
3. 用户确认后，请求才会发送到服务端执行
4. 服务端再次进行安全校验后执行

## 扩展指南

### 添加新的数据库类型（以 PostgreSQL 为例）

#### 1. 添加 JDBC 驱动依赖

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

#### 2. 实现 DatabaseDialect 接口

创建 `src/main/java/com/dameng/mcp/adapter/postgresql/PostgresqlDialect.java`：

```java
package com.dameng.mcp.adapter.postgresql;

import com.dameng.mcp.adapter.DatabaseDialect;

public class PostgresqlDialect implements DatabaseDialect {

    @Override
    public String wrapLimitQuery(String sql, int maxRows) {
        return sql + " LIMIT " + maxRows;
    }

    @Override
    public String buildSampleQuery(String schema, String table, int limit) {
        return "SELECT * FROM \"" + schema + "\".\"" + table + "\" LIMIT " + limit;
    }

    @Override
    public String buildCountQuery(String schema, String table) {
        return "SELECT COUNT(*) FROM \"" + schema + "\".\"" + table + "\"";
    }
}
```

#### 3. 实现 DatabaseAdapter 接口

创建 `src/main/java/com/dameng/mcp/adapter/postgresql/PostgresqlDatabaseAdapter.java`，参考已有的 `DamengDatabaseAdapter` 或 `MysqlDatabaseAdapter` 实现元数据查询、数据查询等方法。

#### 4. 注册到工厂

在 `DatabaseAdapterFactory.java` 的 `create` 方法中添加分支：

```java
case "postgresql":
    return new PostgresqlDatabaseAdapter(jdbcTemplate, new PostgresqlDialect(), securityValidator);
```

同时在 `getDriverClassName` 和 `getValidationQuery` 方法中添加对应配置：

```java
// getDriverClassName
case "postgresql":
    return "org.postgresql.Driver";

// getValidationQuery
case "postgresql":
    return "SELECT 1";
```

#### 5. 配置数据源

在 `application.yml` 中添加：

```yaml
mcp:
  datasources:
    - name: pg-analytics
      description: "分析系统 - PostgreSQL"
      type: postgresql
      url: jdbc:postgresql://localhost:5432/analytics
      username: postgres
      password: your_password
```

## 项目结构

```
dameng-mcp-server/
├── pom.xml                                          # Maven 构建配置
├── README.md                                        # 项目文档
├── src/main/
│   ├── java/com/dameng/mcp/
│   │   ├── DamengMcpServerApplication.java          # Spring Boot 启动类
│   │   ├── adapter/                                 # 数据库适配层
│   │   │   ├── DatabaseAdapter.java                 # 适配器接口
│   │   │   ├── DatabaseDialect.java                 # 方言接口
│   │   │   ├── DatabaseAdapterFactory.java          # 适配器工厂
│   │   │   ├── DataSourceRegistry.java              # 多数据源注册表
│   │   │   ├── dameng/                              # 达梦适配器
│   │   │   │   ├── DamengDatabaseAdapter.java
│   │   │   │   └── DamengDialect.java
│   │   │   ├── oracle/                              # Oracle 适配器
│   │   │   │   ├── OracleDatabaseAdapter.java
│   │   │   │   └── OracleDialect.java
│   │   │   └── mysql/                               # MySQL 适配器
│   │   │       ├── MysqlDatabaseAdapter.java
│   │   │       └── MysqlDialect.java
│   │   ├── config/                                  # 配置类
│   │   │   ├── DataSourceConfig.java                # 多数据源初始化（yml + 持久化动态源）
│   │   │   ├── DataSourceProperties.java            # 数据源配置属性
│   │   │   ├── DataSourcePersistence.java           # 动态数据源 H2 持久化
│   │   │   └── McpToolConfig.java                   # MCP 工具注册
│   │   ├── model/                                   # 数据模型
│   │   │   ├── ColumnInfo.java                      # 列信息
│   │   │   ├── ColumnStatistics.java                # 列统计
│   │   │   ├── DataSourceInfo.java                  # 数据源信息
│   │   │   ├── ExecuteResult.java                   # 执行结果
│   │   │   ├── QueryResult.java                     # 查询结果
│   │   │   ├── TableDefinition.java                 # 表定义
│   │   │   ├── TableInfo.java                       # 表信息
│   │   │   └── TableStatistics.java                 # 表统计
│   │   ├── security/                                # 安全模块
│   │   │   └── SqlSecurityValidator.java            # SQL 安全验证器
│   │   ├── service/                                 # MCP 工具服务
│   │   │   ├── DatabaseMetadataService.java         # 元数据工具
│   │   │   ├── DatabaseQueryService.java            # 查询工具
│   │   │   ├── DatabaseStatisticsService.java       # 统计工具
│   │   │   ├── DatabaseWriteService.java            # 写操作工具
│   │   │   └── DataSourceManager.java               # 数据源动态管理（增删/测试/持久化）
│   │   └── web/                                     # HTTP 模式 Web 层（仅 http profile）
│   │       ├── ApiResponse.java                     # 统一响应结构
│   │       └── DataSourceController.java            # 数据源管理 REST 接口
│   └── resources/
│       ├── application.yml                          # 应用配置（stdio/http 双 profile）
│       └── static/
│           └── index.html                           # 内置 Web 数据源管理页面
└── target/                                          # 构建输出
```

## 注意事项

1. **数据库驱动安装**：达梦 JDBC 驱动（`DmJdbcDriver18`）需确保 Maven 仓库可访问或手动安装到本地仓库：
   ```bash
   mvn install:install-file -DgroupId=com.dameng -DartifactId=DmJdbcDriver18 \
       -Dversion=8.1.3.140 -Dpackaging=jar -Dfile=/path/to/DmJdbcDriver18.jar
   ```

2. **日志关闭**：生产环境中日志级别默认设置为 `OFF`，因为 MCP Stdio 模式使用标准输出通信，任何日志输出都会干扰协议通信。如需调试，可临时修改为 `DEBUG` 并改用文件日志：
   ```yaml
   logging:
     level:
       root: DEBUG
     file:
       name: ./logs/mcp-server.log
   ```

3. **只读账户建议**：对于只需要查询能力的场景，强烈建议使用**只读数据库账户**连接，从数据库层面杜绝误写入的风险。

4. **连接池配置**：默认 Druid 连接池参数适用于大多数场景。高并发场景可适当增大 `maxActive`；资源受限环境可减小 `initialSize` 和 `minIdle`。

5. **网络环境**：MCP 服务以 Stdio 方式运行，无需暴露网络端口。但需确保运行环境可以访问目标数据库的网络地址和端口。

6. **字符集**：建议数据库连接 URL 中明确指定字符集，避免中文乱码：
   - MySQL：`jdbc:mysql://host:3306/db?useSSL=false&characterEncoding=UTF-8&serverTimezone=UTC`
   - 达梦/Oracle：默认 UTF-8，通常无需额外配置

7. **写操作风险**：写操作工具（INSERT/UPDATE/DELETE）会实际修改数据库数据。虽然有多层安全防护和客户端确认机制，仍建议在**非生产环境**中先行验证 SQL 的正确性。
