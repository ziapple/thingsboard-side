# 安装环境
参考https://blog.csdn.net/ieflex/article/details/97106750

- jdk1.8+
- maven3
- nodejs8+

# 安装步骤
1. mvn clean install –DskipTests

第一次安装时间比较长，我装了2个小时，安装完成如下

![安装成功](./img/install-thingsboard-success.jpg)

2. 安装postgres
- 安装timescaledb时序数据库插件，参考https://github.com/digoal/blog/blob/master/201801/20180129_01.md
- 通过navicat运行sql文件,运行dao/src/main/resources/sql/*.sql

![install sql](./img/import_sql.jpg)

3. 安装redis

4. 修改配置

修改application/src/main/resources/thingsboard.yml文件中的postgres,redis

```yml
# SQL DAO Configuration
spring:
  data:
    jpa:
      repositories:
        enabled: "true"
  jpa:
    open-in-view: "false"
    hibernate:
      ddl-auto: "none"
    database-platform: "${SPRING_JPA_DATABASE_PLATFORM:org.hibernate.dialect.PostgreSQLDialect}"
  datasource:
    driverClassName: "${SPRING_DRIVER_CLASS_NAME:org.postgresql.Driver}"
    url: "${SPRING_DATASOURCE_URL:jdbc:postgresql://192.168.3.22:5432/thingsboard}"
    username: "${SPRING_DATASOURCE_USERNAME:postgres}"
    password: "${SPRING_DATASOURCE_PASSWORD:123456}"
    hikari:
      maximumPoolSize: "${SPRING_DATASOURCE_MAXIMUM_POOL_SIZE:50}"
```

```yml
redis:
  # standalone or cluster
  connection:
    type: standalone
  standalone:
    host: "${REDIS_HOST:192.168.3.22}"
    port: "${REDIS_PORT:6379}"
```

5. 运行applicaton
java -jar application/thingsboard.jar

## TB概念介绍
- Tenant租户，TB是多租户的，一个租户代表客户、资产、设备、仪表的集合，通过表增加tenant_id字段来实现多租户管理
- Customer客户，代表一家IOT物联网公司或IOT公司的服务商，租户和客户是一对多关系
- User用户，登陆用户，客户和用户是一对多关系
- Asset资产，公司的有形或无形资产，一般代表楼宇、大厦等，可以理解为存放设备的地理位置
- Device设备，网关或者设备，跟客户关联
- EntityView，实体视图，官方说法类似于SQL View，用于控制向客户展示的数据内容和权限，所有设备数据展示都是通过EV层来实现的
- Widget部件库，强大的控件库，有chart报表控件，control widget控制控件，alarm告警控件，maps地图控件等十多个控件，读取EV数据，可以json自定义控件
- Dashboard仪表库，用于向用户展示的UI页面，控件库的组合，含有页面布局，控件选择和样式，发布页面等功能
- Audit审计日志，所有管理员和用户的操作都会记录到Audit库


## 创建设备
1.使用默认系统管理员登陆系统创建租户管理员，使用默认系统账户登陆：Systen Administrator: sysadmin@thingsboard.org / sysadmin

租户->创建租户->创建租户管理员->使用租户管理员身份管理系统


2.使用租户管理员创建监控设备，新增一个监控设备，复制访问令牌

3.创建仪表盘
- 仪表板库->创建仪表板
- 添加仪表板部件->添加实体
- 添加别名，类型选择设备类型->然后就会出现你刚刚创建的设备
- 创建新部件->选择Charts类型

4.绑定仪表盘和监控设备传递来的数据
- 双击要使用的图表->点击添加数据源->选择之前创建的实体->选择具体数据字段（数据来源于下一步MQTT发送来的数据，可以先跳过）->添加

5.导入部件库

部件库->导入，选择application/src/main/resources/data/json/system/widget_bundles,需要用管理员账号导入 

6. 发送设备数据

- http curl -v -X POST -d @telemetry-data-as-object.json http://localhost:8080/api/v1/$ACCESS_TOKEN/telemetry --header "Content-Type:application/json"

