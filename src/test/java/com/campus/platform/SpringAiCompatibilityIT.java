package com.campus.platform;

import com.campus.platform.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring AI 1.0.x 的 OpenAI 自动配置可以在 Spring Boot 3.4.x 上正常装配
 * （Spring AI 1.0.3 的构建基线是 Boot 3.5，需要实际验证兼容性）。
 * <p>
 * 只创建 Bean，不发起任何网络请求；使用的是假 API Key 和本地不可达地址。
 */
@TestPropertySource(properties = {
        "spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=test-dummy-key",
        "spring.ai.openai.base-url=http://127.0.0.1:9",
        "spring.ai.openai.chat.options.model=test-model"
})
class SpringAiCompatibilityIT extends IntegrationTestBase {

    @Autowired
    ChatModel chatModel;

    @Test
    void openAiChatModelIsAutoConfigured() {
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        assertThat(((OpenAiChatModel) chatModel).getDefaultOptions().getModel()).isEqualTo("test-model");
    }
}
