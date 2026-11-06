package com.zioneer.robotmanager.robot.ota.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.common.core.result.PageResult;
import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.ota.application.cmd.OtaPackageCmd;
import com.zioneer.robotmanager.robot.ota.application.cmd.UpdateOtaPackageCmd;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaDownloadTicketVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaPackageVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaSourceDownloadUrlVO;
import com.zioneer.robotmanager.robot.ota.application.query.OtaPackageQuery;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackage;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaPackageRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaPackageMapstruct;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaSourceDownload;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogArtifactFetch;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogArtifactFetcher;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccessResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OtaPackageService {

    private static final Logger log = LoggerFactory.getLogger(OtaPackageService.class);

    private static final String CI_UPLOADER = "jenkins";

    private final OtaPackageRepository otaPackageRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OtaPackageMapstruct otaPackageMapstruct;
    private final OtaSourceAccessResolver otaSourceAccessResolver;
    private final JfrogArtifactFetcher jfrogArtifactFetcher;
    private final OtaDownloadTicketIssuer otaDownloadTicketIssuer;

    @Autowired
    public OtaPackageService(
            OtaPackageRepository otaPackageRepository,
            SnowflakeIdGenerator snowflakeIdGenerator,
            OtaPackageMapstruct otaPackageMapstruct,
            OtaSourceAccessResolver otaSourceAccessResolver,
            JfrogArtifactFetcher jfrogArtifactFetcher,
            OtaDownloadTicketIssuer otaDownloadTicketIssuer
    ) {
        this.otaPackageRepository = otaPackageRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.otaPackageMapstruct = otaPackageMapstruct;
        this.otaSourceAccessResolver = otaSourceAccessResolver;
        this.jfrogArtifactFetcher = jfrogArtifactFetcher;
        this.otaDownloadTicketIssuer = otaDownloadTicketIssuer;
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(OtaPackageCmd cmd) {
        return create(cmd, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public String createByCi(OtaPackageCmd cmd) {
        return create(cmd, CI_UPLOADER);
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(OtaPackageCmd cmd, String uploader) {
        OtaPackage otaPackage = new OtaPackage(cmd, snowflakeIdGenerator, uploader);
        OtaPackage existing = findSameCombination(otaPackage);
        if (existing != null) {
            return otaPackage.resolveDuplicateRegistration(existing);
        }
        otaPackageRepository.insert(otaPackage);
        log.info("Created ota package {}", otaPackage.getId());
        return String.valueOf(otaPackage.getId());
    }

    private OtaPackage findSameCombination(OtaPackage otaPackage) {
        return otaPackageRepository.findByKindNameVersion(
                        otaPackage.getKind(), otaPackage.getName(), otaPackage.getVersion())
                .stream().findFirst().orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public String update(Long id, UpdateOtaPackageCmd cmd) {
        var otaPackage = otaPackageRepository.findById(id);
        otaPackage.update(cmd);
        validateOtaPackageUnique(otaPackage);
        otaPackageRepository.update(otaPackage);
        log.info("Updated ota package {}", otaPackage.getId());
        return String.valueOf(otaPackage.getId());
    }

    private void validateOtaPackageUnique(OtaPackage otaPackage) {
        List<OtaPackage> exists = otaPackageRepository.findByKindNameVersion(
                otaPackage.getKind(),
                otaPackage.getName(),
                otaPackage.getVersion()
        );
        otaPackage.validateOtaPackageUnique(exists);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var otaPackage = otaPackageRepository.findById(id);
        otaPackageRepository.delete(otaPackage);
        log.info("Deleted ota package {}", otaPackage.getId());
    }

    public List<OtaSourceDownloadUrlVO> downloadUrls(Long id) {
        OtaPackage otaPackage = otaPackageRepository.findById(id);
        return otaPackage.getSources().stream()
                .map(source -> OtaSourceDownloadUrlVO.of(source, otaSourceAccessResolver))
                .toList();
    }

    public OtaSourceDownload openSourceDownload(Long id, String sourceKey, String rangeHeader) {
        OtaPackage otaPackage = otaPackageRepository.findById(id);
        OtaPackageSource source = otaPackage.sourceOf(sourceKey);
        source.requireDirectlyDownloadable();
        JfrogArtifactFetch fetch = jfrogArtifactFetcher.fetch(source, rangeHeader);
        return new OtaSourceDownload(fetch, source.filename());
    }

    public OtaDownloadTicketVO issueDownloadTicket(Long id, String sourceKey) {
        OtaPackage otaPackage = otaPackageRepository.findById(id);
        OtaPackageSource source = otaPackage.sourceOf(sourceKey);
        source.requireDirectlyDownloadable();
        return otaDownloadTicketIssuer.issue(String.valueOf(id), sourceKey);
    }

    public PageResult<OtaPackageVO> page(OtaPackageQuery query) {
        query.normalizeOrderBy();
        IPage<OtaPackage> page = otaPackageRepository.page(query);
        List<OtaPackageVO> list = page.getRecords().stream().map(otaPackageMapstruct::toVO).toList();
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
}
