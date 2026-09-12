package com.example.service.impl;

import com.example.entity.dto.TopicType;
import com.example.entity.vo.request.TopicTypeCreateVO;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicTypesCacheTest {

    private TopicTypeMapper typeMapper;
    private TopicMapper topicMapper;
    private TopicServiceImpl service;

    @BeforeEach
    void setUp() {
        typeMapper = mock(TopicTypeMapper.class);
        topicMapper = mock(TopicMapper.class);
        service = new TopicServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", typeMapper);
        ReflectionTestUtils.setField(service, "baseMapper", topicMapper);
    }

    private TopicType type(int id) {
        TopicType t = new TopicType();
        t.setId(id);
        return t;
    }

    @Test
    void initLoadsAllTypeIds() {
        when(typeMapper.selectList(null)).thenReturn(List.of(type(1), type(2)));

        ReflectionTestUtils.invokeMethod(service, "initTypes");

        assertEquals(Set.of(1, 2), ReflectionTestUtils.getField(service, "types"));
    }

    @Test
    void createTopicTypeRefreshesValidationCache() {
        when(typeMapper.selectList(null)).thenReturn(List.of(type(1)));
        ReflectionTestUtils.invokeMethod(service, "initTypes");

        // 数据库里已新增 id=3 的类型
        when(typeMapper.selectList(null)).thenReturn(List.of(type(1), type(3)));
        service.createTopicType(new TopicTypeCreateVO());

        assertEquals(Set.of(1, 3), ReflectionTestUtils.getField(service, "types"));
    }

    @Test
    void deleteTopicTypeRefreshesValidationCache() {
        when(typeMapper.selectList(null)).thenReturn(List.of(type(1), type(2)));
        ReflectionTestUtils.invokeMethod(service, "initTypes");

        when(typeMapper.selectById(2)).thenReturn(type(2));
        when(typeMapper.deleteById(2)).thenReturn(1);
        when(topicMapper.selectList(any())).thenReturn(List.of());
        // 删除后数据库里只剩 id=1
        when(typeMapper.selectList(null)).thenReturn(List.of(type(1)));

        service.deleteTopicType(2);

        assertEquals(Set.of(1), ReflectionTestUtils.getField(service, "types"));
    }
}
