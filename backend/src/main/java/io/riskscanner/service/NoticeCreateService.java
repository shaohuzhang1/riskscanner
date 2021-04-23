package io.riskscanner.service;

import com.fit2cloud.quartz.anno.QuartzScheduled;
import io.riskscanner.base.domain.*;
import io.riskscanner.base.mapper.*;
import io.riskscanner.base.mapper.ext.ExtTaskMapper;
import io.riskscanner.commons.constants.CloudAccountConstants;
import io.riskscanner.commons.constants.NoticeConstants;
import io.riskscanner.commons.constants.TaskConstants;
import io.riskscanner.commons.utils.BeanUtils;
import io.riskscanner.commons.utils.CommonBeanFactory;
import io.riskscanner.commons.utils.CommonThreadPool;
import io.riskscanner.commons.utils.LogUtil;
import io.riskscanner.message.MessageDetail;
import io.riskscanner.message.NoticeModel;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.config.MessageConstraints;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.fastjson.JSON.toJSONString;

/**
 * @author maguohao
 */
@Service
public class NoticeCreateService {
    // 只有一个任务在处理，防止超配
    private static ConcurrentHashMap<String, String> processingGroupIdMap = new ConcurrentHashMap<>();
    @Resource
    private TaskMapper taskMapper;
    @Resource
    private CommonThreadPool commonThreadPool;
    @Resource
    private MessageOrderMapper messageOrderMapper;
    @Resource
    private MessageOrderItemMapper messageOrderItemMapper;
    @Resource
    private ExtTaskMapper extTaskMapper;

    @QuartzScheduled(cron = "${cron.expression.notice}")
    public void handleTasks() {

        final MessageOrderExample messageOrderExample = new MessageOrderExample();
        MessageOrderExample.Criteria criteria = messageOrderExample.createCriteria();
        criteria.andStatusEqualTo(NoticeConstants.MessageOrderStatus.PROCESSING);
        if (CollectionUtils.isNotEmpty(processingGroupIdMap.keySet())) {
            criteria.andIdNotIn(new ArrayList<>(processingGroupIdMap.keySet()));
        }
        messageOrderExample.setOrderByClause("create_time limit 10");
        List<MessageOrder> messageOrderList = messageOrderMapper.selectByExample(messageOrderExample);
        if (CollectionUtils.isNotEmpty(messageOrderList)) {
            messageOrderList.forEach(messageOrder -> {
                LogUtil.info("handling messageOrder: {}", toJSONString(messageOrder));
                final MessageOrder messageOrderToBeProceed;
                try {
                    messageOrderToBeProceed = BeanUtils.copyBean(new MessageOrder(), messageOrder);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage());
                }
                if (processingGroupIdMap.get(messageOrderToBeProceed.getId()) != null) {
                    return;
                }
                processingGroupIdMap.put(messageOrderToBeProceed.getId(), messageOrderToBeProceed.getId());
                commonThreadPool.addTask(() -> {
                    try {
                        handleMessageOrder(messageOrderToBeProceed);
                    } catch (Exception e) {
                        LogUtil.error(e);
                    } finally {
                        processingGroupIdMap.remove(messageOrderToBeProceed.getId());
                    }
                });
            });
        }
    }

    public void handleMessageOrder(MessageOrder messageOrder) {
        String messageOrderId = messageOrder.getId();
        MessageOrderItemExample messageOrderItemExample = new MessageOrderItemExample();
        messageOrderItemExample.createCriteria().andMessageOrderIdEqualTo(messageOrderId);

        try {
            List<MessageOrderItem> messageOrderItemList = messageOrderItemMapper.selectByExample(messageOrderItemExample);
            if (messageOrderItemList.isEmpty()) {
                return;
            }
            int successCount = 0;
            for (MessageOrderItem item : messageOrderItemList) {
                if (LogUtil.getLogger().isDebugEnabled()) {
                    LogUtil.getLogger().debug("handling MessageOrderItem: {}", toJSONString(item));
                }
                if (handleMessageOrderItem(BeanUtils.copyBean(new MessageOrderItem(), item))) {
                    successCount++;
                }
            }
            if (!messageOrderItemList.isEmpty() && successCount == 0)
                throw new Exception("Faild to handle all messageOrderItemList, messageOrderId: " + messageOrderId);

            if (successCount != messageOrderItemList.size()) {
                return;
            }

            String status;
            if (successCount != messageOrderItemList.size()) {
                status = NoticeConstants.MessageOrderStatus.ERROR;
            } else {
                status = NoticeConstants.MessageOrderStatus.FINISHED;
            }
            messageOrder.setStatus(status);
            messageOrder.setSendTime(System.currentTimeMillis());
            messageOrderMapper.updateByPrimaryKeySelective(messageOrder);

            sendTask(messageOrder);

        } catch (Exception e) {
            messageOrder.setStatus(NoticeConstants.MessageOrderStatus.ERROR);
            messageOrder.setSendTime(System.currentTimeMillis());
            sendTask(messageOrder);
            messageOrderMapper.updateByPrimaryKeySelective(messageOrder);
            LogUtil.error("handleTask, taskId: " + messageOrderId, e);

        }
    }

    private boolean handleMessageOrderItem(MessageOrderItem item) {
        try {
            Task task = taskMapper.selectByPrimaryKey(item.getTaskId());

            if (task == null) {
                return false;
            } else {
                if (StringUtils.equalsIgnoreCase(task.getStatus(), TaskConstants.TASK_STATUS.FINISHED.name())
                || StringUtils.equalsIgnoreCase(task.getStatus(), TaskConstants.TASK_STATUS.WARNING.name())
                || StringUtils.equalsIgnoreCase(task.getStatus(), TaskConstants.TASK_STATUS.ERROR.name())) {
                    item.setStatus(NoticeConstants.MessageOrderStatus.FINISHED);
                    item.setSendTime(System.currentTimeMillis());
                    messageOrderItemMapper.updateByPrimaryKeySelective(item);
                } else {
                    handleMessageOrderItem(item);
                }
            }
            return true;
        } catch (Exception e) {
            item.setStatus(NoticeConstants.MessageOrderStatus.ERROR);
            messageOrderItemMapper.updateByPrimaryKeySelective(item);
            LogUtil.error("handleMessageOrderItem, messageOrderItemId: " + item.getId(), e);
            return false;
        }
    }

    private void sendTask(MessageOrder messageOrder) {
        SystemParameterService systemParameterService = CommonBeanFactory.getBean(SystemParameterService.class);
        NoticeSendService noticeSendService = CommonBeanFactory.getBean(NoticeSendService.class);
        assert systemParameterService != null;
        assert noticeSendService != null;

        String successContext = "success";
        String failedContext = "failed";
        String subject = "安全合规扫描结果";
        String event = NoticeConstants.Event.EXECUTE_SUCCESSFUL;

        List<Task> tasks = extTaskMapper.getTopTasksForEmail(messageOrder);
        int returnSum = extTaskMapper.getReturnSumForEmail(messageOrder);
        int resourcesSum = extTaskMapper.getResourcesSumForEmail(messageOrder);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("resources", tasks);
        paramMap.put("returnSum", returnSum);
        paramMap.put("resourcesSum", resourcesSum);
        NoticeModel noticeModel = NoticeModel.builder()
                .successContext(successContext)
                .successMailTemplate("SuccessfulNotification")
                .failedContext(failedContext)
                .failedMailTemplate("FailedNotification")
                .event(event)
                .subject(subject)
                .paramMap(paramMap)
                .build();
        noticeSendService.send(noticeModel);
    }
}
