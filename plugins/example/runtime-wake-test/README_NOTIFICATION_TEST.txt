notification.show smoke test

启用/重新加载 runtime-wake-test 插件后，onLoad 会立即请求宿主显示一条系统通知：

标题：插件通知测试
正文：notification.show 已触发

manifest 必须包含权限：notification

该动作不会调用 AI，也不会写入聊天记录。点击通知只会打开 OrangeChat。
