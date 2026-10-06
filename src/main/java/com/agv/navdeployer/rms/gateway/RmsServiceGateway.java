package com.agv.navdeployer.rms.gateway;

import com.agv.navdeployer.rms.protocol.dto.service.RegisterRequest;
import com.agv.navdeployer.rms.protocol.keys.RmsServiceKeys;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 【B 模式网关】机器人主动查询 RMS：注册（会话建立后一次）、心跳查询（周期）。
 * 这里所有操作都是 query——发出去、等 RMS 应答、校验回复内容。
 */
public class RmsServiceGateway {

    private static final Logger log = LoggerFactory.getLogger(RmsServiceGateway.class);

    private volatile RmsServiceKeys keys;
    private final ObjectMapper mapper;
    private volatile ZenohChannel channel;

    public RmsServiceGateway(RmsServiceKeys keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    /** 注册后更新 key（RMS 分配了新 robot_code 时调用） */
    public void updateKeys(RmsServiceKeys newKeys) {
        this.keys = newKeys;
    }

    /** 会话建立后注入通道（重建时重调）。 */
    public void attach(ZenohChannel channel) {
        this.channel = channel;
    }

    /** 注册结果：成功标志 + RMS 分配的 robot_code（可能与请求的不同） */
    public record RegisterResult(boolean success, String assignedRobotCode) {
    }

    /**
     * 注册：query register key，解析 RMS 应答。
     * 返回 RegisterResult，包含 RMS 分配的 robot_code（后续通信用这个值，
     * 否则 RMS 按心跳里的 code 查不到机器人 → 永远离线，mock 同样在注册后切换）。
     */
    public RegisterResult register(RegisterRequest request, long timeoutMs) {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            return new RegisterResult(false, null);
        }
        try {
            Optional<String> reply = current.query(keys.register(),
                    mapper.writeValueAsString(request), timeoutMs);
            if (reply.isEmpty()) {
                log.warn("RMS registration no reply, key={}", keys.register());
                return new RegisterResult(false, null);
            }
            JsonNode root = mapper.readTree(reply.get());
            int status = root.path("status").asInt(0);
            if (status != 0) {
                log.warn("RMS registration rejected: status={} msg={}",
                        status, root.path("msg").asText(""));
                return new RegisterResult(false, null);
            }
            String assignedCode = root.path("data").path("robot_code").asText(null);
            log.info("RMS registration ok: assigned_robot_code={} register_action={}",
                    assignedCode, root.path("data").path("register_action").asText(""));
            return new RegisterResult(true, assignedCode);
        } catch (Exception exception) {
            log.warn("RMS registration query failed: {}", exception.getMessage());
            return new RegisterResult(false, null);
        }
    }

    /** 心跳查询：发 heartbeat query，返回是否拿到 RMS 应答。 */
    public boolean heartbeatQuery(String heartbeatJson, long timeoutMs) {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            return false;
        }
        try {
            return current.query(keys.heartbeatQuery(), heartbeatJson, timeoutMs).isPresent();
        } catch (Exception exception) {
            log.debug("rms heartbeat query failed: {}", exception.getMessage());
            return false;
        }
    }
}
