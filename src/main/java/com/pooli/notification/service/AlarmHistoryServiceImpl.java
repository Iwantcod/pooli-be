package com.pooli.notification.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.dto.response.NotiSendResDto;
import com.pooli.notification.domain.dto.response.UnreadCountsResDto;
import com.pooli.notification.domain.entity.AlarmHistory;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.exception.NotificationErrorCode;
import com.pooli.notification.mapper.AlarmHistoryMapper;
import com.pooli.notification.mapper.NotificationLineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmHistoryServiceImpl implements AlarmHistoryService {
    private final AlarmHistoryMapper alarmHistoryMapper;
    private final ObjectMapper objectMapper;
    private final NotificationLineMapper notificationLineMapper;

    @Transactional
    public void createAlarm(
            Long lineId,
            AlarmCode alarmCode,
            AlarmType alarmType
    ) {


        Map<String, String> values = new HashMap<>();


        // AlarmType의 이름을 문자열로 넣어 나중에 JSON 파싱이 쉽도록 합니다.
        values.put("type", alarmType.name());

        try {
            String jsonValue = objectMapper.writeValueAsString(values);

            alarmHistoryMapper.insertAlarmHistory(
                    lineId,
                    alarmCode.name(),
                    jsonValue
            );

        } catch (JsonProcessingException e) {
            log.warn("알림 JSON 변환 실패 - lineId: {}, alarmCode: {}, error: {}", lineId, alarmCode.name(), e.getMessage());
        }
    }

    @Transactional
    @Override
    public void createAlarm(
            Long lineId,
            AlarmCode alarmCode,
            AlarmType alarmType,
            Map<String, String> values
    ) {
        Map<String, String> mergedValues = new HashMap<>();
        mergedValues.put("type", alarmType.name());
        if (values != null) {
            mergedValues.putAll(values);
        }

        try {
            String jsonValue = objectMapper.writeValueAsString(mergedValues);

            int result = alarmHistoryMapper.insertAlarmHistory(
                    lineId,
                    alarmCode.name(),
                    jsonValue
            );

            if (result != 1) {
                System.out.println("회선 Id :" + lineId + "의 " + alarmCode.name() + " : 알람 저장 실패");
            }

        } catch (JsonProcessingException e) {
            System.out.println("알림 JSON 변환 실패 : " + e.getMessage());
        }
    }

    @Transactional
    @Override
    public void sendNotification(NotiSendReqDto request) {

        List<Long> lineIds = request.getLineId();

        if (request.getTargetType() != NotificationTargetType.DIRECT
                && lineIds != null && !lineIds.isEmpty()) {
            throw new ApplicationException(NotificationErrorCode.INVALID_TARGET_CONDITION);
        }

        List<Long> targetLineIds;

        switch (request.getTargetType()) {

            case DIRECT -> {

                if (lineIds == null || lineIds.isEmpty()) {
                    throw new ApplicationException(NotificationErrorCode.LINE_ID_REQUIRED);
                }

                // 1. 중복 제거
                Set<Long> uniqueLineIds = new HashSet<>(lineIds);

                // 2. 존재하는 ID 조회
                List<Long> existingLineIds =
                        notificationLineMapper.findExistingLineIds(new ArrayList<>(uniqueLineIds));

                // 3. 존재 여부 검증
                if (existingLineIds.size() != uniqueLineIds.size()) {
                    throw new ApplicationException(
                            NotificationErrorCode.NOTIFICATION_TARGET_NOT_FOUND
                    );
                }

                targetLineIds = existingLineIds;
            }
            case ALL -> targetLineIds = notificationLineMapper.findAllLineIds();

            case OWNER -> targetLineIds =
                    notificationLineMapper.findLineIdsByRole("OWNER");

            case MEMBER -> targetLineIds =
                    notificationLineMapper.findLineIdsByRole("MEMBER");

            default -> throw new ApplicationException(
                    NotificationErrorCode.INVALID_TARGET_CONDITION
            );
        }

        if (targetLineIds == null || targetLineIds.isEmpty()) {
            throw new ApplicationException(
                    NotificationErrorCode.NOTIFICATION_TARGET_NOT_FOUND
            );
        }
        try {
            ObjectNode jsonNode;

            if (request.getValue() == null) {
                jsonNode = objectMapper.createObjectNode();
            } else if (request.getValue().isObject()) {
                jsonNode = (ObjectNode) request.getValue();
            } else {
                throw new ApplicationException(
                        NotificationErrorCode.INVALID_TARGET_CONDITION
                );
            }

            jsonNode.put("type", "NOTIFICATION");

            String jsonValue = objectMapper.writeValueAsString(jsonNode);

            alarmHistoryMapper.insertNotificationAlarms(
                    targetLineIds,
                    AlarmCode.OTHERS.name(),
                    jsonValue
            );

        } catch (Exception e) {
            throw new ApplicationException(
                    NotificationErrorCode.NOTIFICATION_SAVE_FAILED
            );
        }
    }

    @Transactional(readOnly = true)
    @Override
    public PagingResDto<NotiSendResDto> getNotifications(
            Long userId,
            Long lineId,
            Integer page,
            Integer size,
            Boolean isRead,
            AlarmCode code
    ) {

        if (page == null || page < 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_NUMBER);
        }
        if (size == null || size <= 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_SIZE);
        }

        int offset = page * size;

        List<AlarmHistory> entities = alarmHistoryMapper.findAlarmHistoryPage(
                userId,
                lineId,
                isRead,
                code != null ? code.name() : null,
                offset,
                size
        );

        List<NotiSendResDto> content = entities.stream()
                .map(entity -> NotiSendResDto.builder()
                        .alarmHistoryId(entity.getAlarm_historyId())
                        .lineId(entity.getLineId())
                        .alarmCode(entity.getAlarmCode())
                        .value(parseJson(entity.getValue()))
                        .isRead(entity.getReadAt() != null)
                        .createdAt(entity.getCreatedAt())
                        .build()
                )
                .toList();

        Long totalElements =
                alarmHistoryMapper.countAlarmHistory(
                        userId,
                        lineId,
                        isRead,
                        code != null ? code.name() : null
                );

        int totalPages =
                (int) Math.ceil((double) totalElements / size);

        return PagingResDto.<NotiSendResDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public UnreadCountsResDto getUnreadCounts(Long userId, Long lineId) {

        Long count = alarmHistoryMapper.countUnreadByLineId(userId,lineId);

        return UnreadCountsResDto.builder()
                .lineId(lineId)
                .unreadCount(count != null ? count : 0L)
                .build();
    }

    @Transactional
    @Override
    public NotiSendResDto readOne(Long alarmHistoryId, Long lineId) {

        // 알람이 존재하는지 먼저 확인 (삭제되지 않은 것, 본인 소유)
        NotiSendResDto alarm = alarmHistoryMapper.findOneByAlarmHistoryIdAndLineId(alarmHistoryId, lineId);
        if (alarm == null) {
            throw new ApplicationException(NotificationErrorCode.ALARM_HISTORY_NOT_FOUND);
        }

        // 이미 읽은 경우에는 UPDATE 없이 그대로 반환 (멱등성)
        if (Boolean.TRUE.equals(alarm.getIsRead())) {
            return alarm;
        }

        alarmHistoryMapper.updateReadOne(alarmHistoryId, lineId);

        return alarmHistoryMapper.findOneByAlarmHistoryIdAndLineId(alarmHistoryId, lineId);
    }

    @Transactional
    @Override
    public UnreadCountsResDto readAll(Long lineId) {

        int readCount = alarmHistoryMapper.updateReadAll(lineId);

        return UnreadCountsResDto.builder()
                .lineId(lineId)
                .unreadCount(0L)
                .readCount((long) readCount)
                .build();
    }

    private JsonNode parseJson(String value) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new ApplicationException(NotificationErrorCode.INVALID_NOTIFICATION_JSON);
        }
    }
}
