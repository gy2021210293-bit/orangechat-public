// OrangeChat plugin runtime smoke test.
// On load it verifies notification.show; every 5 minutes it verifies ai.wake.

exports.onLoad = function(ctx) {
    console.log('[runtime-wake-test] loaded: ' + JSON.stringify(ctx));
    return {
        hostAction: 'notification.show',
        title: '插件通知测试',
        text: 'notification.show 已触发'
    };
};

exports.onEvent = function(event) {
    console.log('[runtime-wake-test] event: ' + event.type);
};

exports.onWakeTest = function(event) {
    console.log('[runtime-wake-test] scheduled hook fired: ' + JSON.stringify(event));
    return {
        hostAction: 'ai.wake',
        prompt: '这是 OrangeChat 插件运行时的自主唤醒测试。请只回复一句：插件自主唤醒测试成功。不要调用工具，不要补充其他内容。',
        allowTools: false
    };
};
