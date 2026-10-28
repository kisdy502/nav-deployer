package com.agv.navdeployer.rms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rms.robot.robot_sn 覆盖机制验证：用 Spring Boot 真实 Binder + 占位符解析复现
 * application.yaml → 环境变量 → setter 的完整链路。
 * 结论应满足：非空配置强制覆盖固定种子生成的默认 SN；空串/未配置回退共享默认值。
 */
class RmsPropertiesTest {

    /** 与 application.yaml 完全一致的写法：robot_sn: ${RMS_ROBOT_SN:} */
    private static final String PLACEHOLDER = "${RMS_ROBOT_SN:}";

    /** 与 RmsProperties.Robot.DEFAULT_SN_SEED 相同的种子（改动生产种子会让此测试失败，属有意防护） */
    private static final String DEFAULT_SN = java.util.UUID.nameUUIDFromBytes(
            "nav-deployer-sim-chassis-001".getBytes()).toString().replace("-", "");

    private RmsProperties bind(Map<String, Object> yaml, Map<String, Object> env) {
        MutablePropertySources envSources = new MutablePropertySources();
        envSources.addLast(new MapPropertySource("env", env));
        Binder binder = new Binder(
                ConfigurationPropertySources.from(new MapPropertySource("yml", yaml)),
                new PropertySourcesPlaceholdersResolver(envSources));
        return binder.bind("rms", Bindable.of(RmsProperties.class)).get();
    }

    @Test
    void yamlLiteralValueOverridesGeneratedSn() {
        // yml 直接写值（可随时改动），含前后空格应被 trim
        Map<String, Object> yaml = new HashMap<>();
        yaml.put("rms.robot.robot_sn", "  dev-think-01  ");
        RmsProperties props = bind(yaml, Map.of());

        assertThat(props.getRobot().getRobotSn()).isEqualTo("dev-think-01");
        assertThat(props.getRobot().isRobotSnConfigured()).isTrue();
    }

    @Test
    void envVarThroughPlaceholderOverridesGeneratedSn() {
        // ${RMS_ROBOT_SN:} + 环境变量存在 → 占位符展开后强制覆盖
        RmsProperties props = bind(
                Map.of("rms.robot.robot_sn", (Object) PLACEHOLDER),
                Map.of("RMS_ROBOT_SN", "sn-from-env"));

        assertThat(props.getRobot().getRobotSn()).isEqualTo("sn-from-env");
        assertThat(props.getRobot().isRobotSnConfigured()).isTrue();
    }

    @Test
    void emptyPlaceholderFallsBackToSharedDefaultSn() {
        // ${RMS_ROBOT_SN:} + 环境变量未设置 → 展开为空串 → 不覆盖默认 SN
        RmsProperties props = bind(
                Map.of("rms.robot.robot_sn", (Object) PLACEHOLDER), Map.of());

        assertThat(props.getRobot().getRobotSn()).isEqualTo(DEFAULT_SN);
        assertThat(props.getRobot().isRobotSnConfigured()).isFalse();
    }

    @Test
    void missingKeyFallsBackToSharedDefaultSn() {
        // yml 有其他 rms.* 配置但未配置 robot_sn → 回退默认 SN，且多次读取稳定不漂移
        RmsProperties.Robot robot = bind(Map.of("rms.enabled", (Object) "true"), Map.of()).getRobot();
        assertThat(robot.getRobotSn()).isEqualTo(DEFAULT_SN).isEqualTo(robot.getRobotSn());
        assertThat(robot.isRobotSnConfigured()).isFalse();
    }
}
