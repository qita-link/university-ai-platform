package com.campus.platform.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.campus.platform.common.util.SensitiveDataMasker;

/**
 * Logback 消息转换器：在输出前对日志消息做脱敏（密码、Token、API Key 等）。
 * 在 logback-spring.xml 中以 {@code %maskedMsg} 使用。
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
