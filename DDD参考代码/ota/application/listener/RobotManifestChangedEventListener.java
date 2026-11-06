package com.zioneer.robotmanager.robot.ota.application.listener;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.domain.event.RobotManifestChangedEvent;
import com.zioneer.robotmanager.robot.ota.application.OtaUpgradeTaskService;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaUpgradeTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 清单比对的兜底完成路径：机器人装完了但状态上报丢了，只要清单对得上，任务照样收口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RobotManifestChangedEventListener {

    private final OtaUpgradeTaskRepository otaUpgradeTaskRepository;
    private final OtaUpgradeTaskService otaUpgradeTaskService;

    @EventListener
    public void onRobotManifestChanged(RobotManifestChangedEvent event) {
        var unfinishedUpgradeTask = otaUpgradeTaskRepository.findUnfinishedByTargetId(String.valueOf(event.robotId()));
        unfinishedUpgradeTask.forEach(task -> {
            try {
                otaUpgradeTaskService.compareManifest(task.getId(), event.manifest());
            } catch (BusinessException exception) {
                log.debug("upgrade task not completed by manifest, task_id={}, reason={}",
                        task.getId(), exception.getMessage());
            }
        });
    }
}
