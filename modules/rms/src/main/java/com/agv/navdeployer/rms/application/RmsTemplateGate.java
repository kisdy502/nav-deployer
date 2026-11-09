package com.agv.navdeployer.rms.application;

import com.agv.navdeployer.rms.domain.RmsTask;
import com.agv.navdeployer.rms.domain.RmsTemplateStore;
import org.springframework.stereotype.Component;

/**
 * 任务模板闸门：charge/replace_battery/quality_inspection 类任务必须在下发前完成模板下发。
 */
@Component
public class RmsTemplateGate {

    private final RmsTemplateStore templateStore;

    public RmsTemplateGate(RmsTemplateStore templateStore) {
        this.templateStore = templateStore;
    }

    public boolean templateMissing(RmsTask task) {
        if (!task.isAction()) {
            return false;
        }
        if (!(task.isTemplate("charge") || task.isTemplate("replace_battery")
                || task.isTemplate("quality_inspection"))) {
            return false;
        }
        return templateStore.find(templateCodeOf(task)).isEmpty();
    }

    public String templateMissingMessage(RmsTask task) {
        String type = task.taskTemplateType() == null ? "charge" : task.taskTemplateType();
        String code = templateCodeOf(task);
        String templateId = task.templateId();
        return "任务模板不存在：类型 " + type + " 的任务模板 " + code
                + (templateId == null || templateId.isBlank() ? "" : "（template_id=" + templateId + "）")
                + " 未下发，请先通过 task_template/add 下发模板";
    }

    private static String templateCodeOf(RmsTask task) {
        String type = task.taskTemplateType() == null ? "charge" : task.taskTemplateType();
        return task.templateCode() == null || task.templateCode().isBlank()
                ? type : task.templateCode();
    }
}
