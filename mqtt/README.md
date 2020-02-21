# Mqtt客户端测试
- 安装node.js,设置node的环境变量
- 安装mqtt客户端
  - cd ${NODE_PATH}, npm install mqtt,这样就可以使用mqtt批处理命令
1. 推送时序消息, 注意$ACCESS_TOKEN不需要单引号，在设备->最新遥测会显示
```shell script
mqtt pub -v -h "127.0.0.1" -t "v1/devices/me/telemetry" -u '$ACCESS_TOKEN' -m {'key':'value'}
```
2. 发送设备客户端属性，在设备的属性->客户端属性会显示出来
```shell script
mqtt pub -d -h "127.0.0.1" -t "v1/devices/me/attributes" -u '$ACCESS_TOKEN' -m {'key':'value'}
```
3. 获取设备属性
```jshelllanguage
var mqtt = require('mqtt')
var client  = mqtt.connect('mqtt://127.0.0.1',{
    username: process.env.TOKEN
})

client.on('connect', function () {
    console.log('connected')
    client.subscribe('v1/devices/me/attributes/response/+')
    client.publish('v1/devices/me/attributes/request/1', '{"clientKeys":"attribute1,attribute2", "sharedKeys":"shared1,shared2"}')
})

client.on('message', function (topic, message) {
    console.log('response.topic: ' + topic)
    console.log('response.body: ' + message.toString())
    client.end()
})
```
```shell script
export TOKEN=$ACCESS_TOKEN
node mqtt-js-attributes-request.js
```
4. 订阅设备属性更新
```shell script
# Subscribes to attribute updates
mqtt sub -v "127.0.0.1" -t "v1/devices/me/attributes" -u '$ACCESS_TOKEN'
```
5. 订阅服务端RPC
```jshelllanguage
var mqtt = require('mqtt');
var client  = mqtt.connect('mqtt://127.0.0.1',{
    username: process.env.TOKEN
});

client.on('connect', function () {
    console.log('connected');
    client.subscribe('v1/devices/me/rpc/request/+')
});

client.on('message', function (topic, message) {
    console.log('request.topic: ' + topic);
    console.log('request.body: ' + message.toString());
    var requestId = topic.slice('v1/devices/me/rpc/request/'.length);
    //client acts as an echo service
    client.publish('v1/devices/me/rpc/response/' + requestId, message);
});
```
6. 订阅客户端RPC
```jshelllanguage
var mqtt = require('mqtt');
var client = mqtt.connect('mqtt://127.0.0.1', {
    username: process.env.TOKEN
});

client.on('connect', function () {
    console.log('connected');
    client.subscribe('v1/devices/me/rpc/response/+');
    var requestId = 1;
    var request = {
        "method": "getTime",
        "params": {}
    };
    client.publish('v1/devices/me/rpc/request/' + requestId, JSON.stringify(request));
});

client.on('message', function (topic, message) {
    console.log('response.topic: ' + topic);
    console.log('response.body: ' + message.toString());
});
```