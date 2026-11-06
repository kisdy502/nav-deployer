package com.zioneer.robotmanager.robot.ota.domain.config;

import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaStoragePlatform;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Setter
@Getter
@Component("otaStorageProperties")
@ConfigurationProperties(prefix = "ota.storage")
public class OtaStorageProperties {

    /** 当前存储平台；本期只实现 JFROG。 */
    private OtaStoragePlatform platform = OtaStoragePlatform.JFROG;

    private Jfrog jfrog = new Jfrog();

    @Setter
    @Getter
    public static class Jfrog {

        /** 存储平台地址：拼进下载地址与平台自用（换凭据、取流）共用，选用内外网络均可达的入口（当前为外网入口），含 scheme 与 context path */
        private String baseUrl;

        /** 只读账号名 */
        private String username;

        /** 只读账号密码 */
        private String password;

        /** 下载凭据有效期（秒），默认 1 小时 */
        private long credentialTtlSeconds = 3600;
    }

    /** 代下载票据签名密钥；未配置时每次启动随机生成（重启后已发票据全部失效，可接受） */
    private String downloadTicketSecret;

    /** 代下载票据有效期（秒），默认 60 */
    private long downloadTicketTtlSeconds = 60;
}
