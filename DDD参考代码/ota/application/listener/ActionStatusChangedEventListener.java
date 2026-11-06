package com.zioneer.robotmanager.robot.ota.application.listener;

import com.zioneer.robotmanager.robot.action.domain.Action;
import com.zioneer.robotmanager.robot.action.domain.ActionContext;
import com.zioneer.robotmanager.robot.action.domain.ActionType;
import com.zioneer.robotmanager.robot.action.domain.event.ActionStatusChangedEvent;
import com.zioneer.robotmanager.robot.action.domain.repository.ActionRepository;
import com.zioneer.robotmanager.robot.ota.application.OtaUpgradeTaskService;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaAction;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaActionMapstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Action状态变更事件监听
 */
@Component
public class ActionStatusChangedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ActionStatusChangedEventListener.class);

    private final OtaUpgradeTaskService otaUpgradeTaskService;

    private final ActionRepository actionRepository;

    private final OtaActionMapstruct otaActionMapstruct;

    @Autowired
    public ActionStatusChangedEventListener(OtaUpgradeTaskService otaUpgradeTaskService,
                                            ActionRepository actionRepository,
                                            OtaActionMapstruct otaActionMapstruct) {
        this.otaUpgradeTaskService = otaUpgradeTaskService;
        this.actionRepository = actionRepository;
        this.otaActionMapstruct = otaActionMapstruct;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActionStatusChanged(ActionStatusChangedEvent event) {
        log.debug("action status changed, action_id={}", event.actionId());
        if (event.actionType() != ActionType.OTA_UPGRADE) {
            log.debug("upgrade task not changed, action_id={}, reason=NOT_OTA_UPGRADE, type={}",
                    event.actionId(), event.actionType());
            return;
        }
        Long upgradeTaskId = upgradeTaskIdOf(event.contexts());
        if (upgradeTaskId == null) {
            log.debug("upgrade task not changed, action_id={}, reason=NO_UPGRADE_TASK_CONTEXT", event.actionId());
            return;
        }
        OtaUpgradeTask upgradeTask = otaUpgradeTaskService.findById(upgradeTaskId);
        Action action = actionRepository.findById(event.actionId());
        OtaAction otaAction = otaActionMapstruct.toOtaAction(action);
        otaUpgradeTaskService.changeStatusBy(upgradeTask, otaAction);
    }

    private static Long upgradeTaskIdOf(List<ActionContext> contexts) {
        return contexts.stream()
                .filter(context -> OtaUpgradeTask.ACTION_CONTEXT_TYPE.equals(context.type()))
                .map(ActionContext::value)
                .findFirst()
                .map(Long::parseLong)
                .orElse(null);
    }
}
