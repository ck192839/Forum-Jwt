package com.example.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountDetails;
import com.example.entity.dto.AccountPrivacy;
import com.example.entity.dto.TopicComment;
import com.example.entity.vo.response.CommentVO;
import com.example.mapper.AccountDetailsMapper;
import com.example.mapper.AccountMapper;
import com.example.mapper.AccountPrivacyMapper;
import com.example.mapper.TopicCommentMapper;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicCommentsBatchTest {

    private static final String DELTA = "{\"ops\":[{\"insert\":\"正文内容\\n\"}]}";

    @Mock
    TopicCommentMapper commentMapper;
    @Mock
    AccountMapper accountMapper;
    @Mock
    AccountDetailsMapper accountDetailsMapper;
    @Mock
    AccountPrivacyMapper accountPrivacyMapper;

    TopicServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TopicServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "accountDetailsMapper", accountDetailsMapper);
        ReflectionTestUtils.setField(service, "accountPrivacyMapper", accountPrivacyMapper);
    }

    private TopicComment comment(int id, int uid, Integer quote) {
        TopicComment comment = new TopicComment();
        comment.setId(id);
        comment.setUid(uid);
        comment.setTid(1);
        comment.setContent(DELTA);
        comment.setQuote(quote);
        comment.setTime(new Date());
        return comment;
    }

    @Test
    void commentsResolveUsersAndQuotesInBatchQueries() {
        Account alice = new Account();
        alice.setId(10);
        alice.setUsername("alice");
        Account bob = new Account();
        bob.setId(11);
        bob.setUsername("bob");
        AccountDetails details = new AccountDetails();
        details.setId(10);
        details.setPhone("123");
        AccountPrivacy privacy = new AccountPrivacy(11);
        privacy.setPhone(false);

        doAnswer(invocation -> {
            Page<TopicComment> page = invocation.getArgument(0);
            page.setRecords(List.of(comment(100, 10, null), comment(101, 11, 100)));
            return page;
        }).when(commentMapper).selectPage(any(), any());
        when(accountMapper.selectBatchIds(any())).thenReturn(List.of(alice, bob));
        when(accountDetailsMapper.selectBatchIds(any())).thenReturn(List.of(details));
        when(accountPrivacyMapper.selectBatchIds(any())).thenReturn(List.of(privacy));
        when(commentMapper.selectBatchIds(any())).thenReturn(List.of(comment(100, 10, null)));

        List<CommentVO> result = service.comments(1, 1);

        assertEquals(2, result.size());
        // 被引用评论来自批量查询
        assertEquals("正文内容\n", result.get(1).getQuote());
        // 隐私过滤按用户各自的设置生效：无隐私记录不隐藏，phone=false 隐藏
        assertEquals("alice", result.get(0).getUser().getUsername());
        assertEquals("123", result.get(0).getUser().getPhone());
        assertEquals("bob", result.get(1).getUser().getUsername());
        assertNull(result.get(1).getUser().getPhone());
        // 不再逐条查询
        verify(commentMapper, never()).selectOne(any());
        verify(accountMapper, never()).selectById(any(Integer.class));
    }

    @Test
    void missingQuotedCommentFallsBackToDeletedText() {
        doAnswer(invocation -> {
            Page<TopicComment> page = invocation.getArgument(0);
            page.setRecords(List.of(comment(101, 11, 999)));
            return page;
        }).when(commentMapper).selectPage(any(), any());
        when(accountMapper.selectBatchIds(any())).thenReturn(List.of());
        when(accountDetailsMapper.selectBatchIds(any())).thenReturn(List.of());
        when(accountPrivacyMapper.selectBatchIds(any())).thenReturn(List.of());
        when(commentMapper.selectBatchIds(any())).thenReturn(List.of());

        List<CommentVO> result = service.comments(1, 1);

        assertEquals("此评论已被删除", result.get(0).getQuote());
    }
}
