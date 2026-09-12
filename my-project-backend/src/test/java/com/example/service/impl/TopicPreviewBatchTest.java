package com.example.service.impl;

import com.example.entity.dto.Account;
import com.example.entity.dto.Topic;
import com.example.entity.dto.TopicInteractCount;
import com.example.entity.vo.response.TopicPreviewVO;
import com.example.mapper.AccountMapper;
import com.example.mapper.InteractType;
import com.example.mapper.TopicMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicPreviewBatchTest {

    @Mock
    TopicMapper topicMapper;
    @Mock
    AccountMapper accountMapper;

    TopicServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TopicServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", topicMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
    }

    private Topic topic(int id, int uid) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setUid(uid);
        topic.setTitle("帖子" + id);
        topic.setType(1);
        topic.setTime(new Date());
        topic.setContent("{\"ops\":[{\"insert\":\"正文内容\\n\"}]}");
        return topic;
    }

    @Test
    void previewsResolveAuthorsAndCountsInBatchQueries() {
        Account author = new Account();
        author.setId(10);
        author.setUsername("alice");
        lenient().when(accountMapper.selectBatchIds(any())).thenReturn(List.of(author));
        when(topicMapper.interactCountBatch(any(), org.mockito.ArgumentMatchers.eq(InteractType.LIKE)))
                .thenReturn(List.of(countRow(1, 5)));
        when(topicMapper.interactCountBatch(any(), org.mockito.ArgumentMatchers.eq(InteractType.COLLECT)))
                .thenReturn(List.of(countRow(2, 2)));

        @SuppressWarnings("unchecked")
        List<TopicPreviewVO> result = (List<TopicPreviewVO>) ReflectionTestUtils.invokeMethod(
                service, "resolveToPreviews", List.of(topic(1, 10), topic(2, 11)));

        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).getUsername());
        assertEquals(5, result.get(0).getLike());
        assertEquals(0, result.get(0).getCollect());
        assertEquals("正文内容\n", result.get(0).getText());
        // 账号缺失的帖子跳过用户字段而不是 NPE
        assertNull(result.get(1).getUsername());
        assertEquals(0, result.get(1).getLike());
        assertEquals(2, result.get(1).getCollect());

        verify(accountMapper).selectBatchIds(any());
        verify(topicMapper, never()).selectById(any(Integer.class));
        verify(topicMapper, never()).interactCount(org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private TopicInteractCount countRow(int tid, int total) {
        TopicInteractCount row = new TopicInteractCount();
        row.setTid(tid);
        row.setTotal(total);
        return row;
    }
}
