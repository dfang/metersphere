package io.metersphere.track.service;

import io.metersphere.api.service.ShareInfoService;
import io.metersphere.base.domain.TestPlan;
import io.metersphere.base.domain.TestPlanReport;
import io.metersphere.base.domain.TestPlanReportContentWithBLOBs;
import io.metersphere.base.domain.TestPlanWithBLOBs;
import io.metersphere.base.mapper.TestPlanMapper;
import io.metersphere.commons.constants.*;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.CommonBeanFactory;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.dto.BaseSystemConfigDTO;
import io.metersphere.dto.UserDTO;
import io.metersphere.i18n.Translator;
import io.metersphere.notice.sender.NoticeModel;
import io.metersphere.notice.service.NoticeSendService;
import io.metersphere.service.ProjectService;
import io.metersphere.service.SystemParameterService;
import io.metersphere.service.UserService;
import io.metersphere.track.dto.TestPlanDTOWithMetric;
import org.apache.commons.beanutils.BeanMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@Transactional
public class TestPlanMessageService {
    @Resource
    private ProjectService projectService;
    @Lazy
    @Resource
    private TestPlanService testPlanService;
    @Resource
    private UserService userService;
    @Resource
    private ShareInfoService shareInfoService;
    @Resource
    private TestPlanMapper testPlanMapper;
    @Lazy
    @Resource
    private TestPlanReportService testPlanReportService;


    @Async
    public void checkTestPlanStatusAndSendMessage(TestPlanReport report, TestPlanReportContentWithBLOBs testPlanReportContent, boolean sendMessage) {
        if (testPlanReportContent != null) {
            report = testPlanReportService.checkTestPlanReportHasErrorCase(report, testPlanReportContent);
        }
        if (!report.getIsApiCaseExecuting() && !report.getIsPerformanceExecuting() && !report.getIsScenarioExecuting()) {
            //??????TestPlan???????????????
            TestPlanWithBLOBs testPlan = testPlanMapper.selectByPrimaryKey(report.getTestPlanId());
            if (testPlan != null && !StringUtils.equals(testPlan.getStatus(), TestPlanStatus.Completed.name())) {
                testPlan.setStatus(TestPlanStatus.Completed.name());
                testPlanService.editTestPlan(testPlan);
            }
            try {
                if (sendMessage && testPlan != null && StringUtils.equalsAny(report.getTriggerMode(),
                        ReportTriggerMode.MANUAL.name(),
                        ReportTriggerMode.API.name(),
                        ReportTriggerMode.SCHEDULE.name()) && !StringUtils.equalsIgnoreCase(report.getStatus(), ExecuteResult.TEST_PLAN_RUNNING.toString())
                ) {
                    //????????????
                    this.sendMessage(testPlan, report, testPlan.getProjectId());
                }
            } catch (Exception e) {
                LogUtil.error(e);
            }
        }
    }

    @Async
    public void sendMessage(TestPlan testPlan, TestPlanReport testPlanReport, String projectId) {
        assert testPlan != null;
        SystemParameterService systemParameterService = CommonBeanFactory.getBean(SystemParameterService.class);
        NoticeSendService noticeSendService = CommonBeanFactory.getBean(NoticeSendService.class);
        assert systemParameterService != null;
        assert noticeSendService != null;
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        String url = baseSystemConfigDTO.getUrl() + "/#/track/testPlan/reportList";
        String subject = "";
        String successContext = "${operator}????????? ${name} ????????????????????????, ??????: ${planShareUrl}";
        String failedContext = "${operator}????????? ${name} ????????????????????????, ??????: ${planShareUrl}";
        String context = "${operator}?????????????????????: ${name}, ??????: ${planShareUrl}";
        if (StringUtils.equals(testPlanReport.getTriggerMode(), ReportTriggerMode.API.name())) {
            subject = Translator.get("task_notification_jenkins");
        } else {
            subject = Translator.get("task_notification");
        }
        // ???????????????
        TestPlanDTOWithMetric testPlanDTOWithMetric = BeanUtils.copyBean(new TestPlanDTOWithMetric(), testPlan);
        testPlanService.calcTestPlanRate(Collections.singletonList(testPlanDTOWithMetric));

        String creator = testPlanReport.getCreator();
        UserDTO userDTO = userService.getUserDTO(creator);

        Map paramMap = new HashMap();
        paramMap.put("type", "testPlan");
        paramMap.put("url", url);
        paramMap.put("projectId", projectId);
        if (userDTO != null) {
            paramMap.put("operator", userDTO.getName());
            paramMap.put("executor", userDTO.getId());
        }
        paramMap.putAll(new BeanMap(testPlanDTOWithMetric));

        String testPlanShareUrl = shareInfoService.getTestPlanShareUrl(testPlanReport.getId(), creator);
        paramMap.put("planShareUrl", baseSystemConfigDTO.getUrl() + "/sharePlanReport" + testPlanShareUrl);

        /**
         * ??????????????????????????????????????? ????????????????????????
         * ?????????????????????????????????"??????"???????????????
         */
        Map<String, String> execStatusEventMap = new HashMap<>();
        execStatusEventMap.put(TestPlanReportStatus.COMPLETED.name(), NoticeConstants.Event.COMPLETE);
        if (StringUtils.equalsIgnoreCase(testPlanReport.getStatus(), TestPlanReportStatus.SUCCESS.name())) {
            execStatusEventMap.put(testPlanReport.getStatus(), NoticeConstants.Event.EXECUTE_SUCCESSFUL);
        } else if (StringUtils.equalsIgnoreCase(testPlanReport.getStatus(), TestPlanReportStatus.FAILED.name())) {
            execStatusEventMap.put(testPlanReport.getStatus(), NoticeConstants.Event.EXECUTE_FAILED);
        } else if (!StringUtils.equalsIgnoreCase(testPlanReport.getStatus(), TestPlanReportStatus.COMPLETED.name())) {
            execStatusEventMap.put(testPlanReport.getStatus(), NoticeConstants.Event.COMPLETE);
        }
        for (Map.Entry<String, String> entry : execStatusEventMap.entrySet()) {
            String status = entry.getKey();
            String event = entry.getValue();
            NoticeModel noticeModel = NoticeModel.builder()
                    .operator(creator)
                    .context(context)
                    .successContext(successContext)
                    .failedContext(failedContext)
                    .testId(testPlan.getId())
                    .status(status)
                    .event(event)
                    .subject(subject)
                    .paramMap(paramMap)
                    .build();

            if (StringUtils.equals(testPlanReport.getTriggerMode(), ReportTriggerMode.MANUAL.name())) {
                noticeSendService.send(projectService.getProjectById(projectId), NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
            }

            if (StringUtils.equalsAny(testPlanReport.getTriggerMode(), ReportTriggerMode.SCHEDULE.name(), ReportTriggerMode.API.name())) {
                noticeSendService.send(testPlanReport.getTriggerMode(), NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
            }
        }
    }
}
