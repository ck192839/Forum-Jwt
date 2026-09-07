package com.example.service.impl;

import com.example.entity.vo.request.TopicCreateVO;
import com.example.entity.vo.request.TopicUpdateVO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicServiceTransactionContractTest {

    @Test
    void topicMutationsThatPublishIndexEventsHaveExplicitTransactionBoundaries() throws Exception {
        List<Method> mutations = List.of(
                TopicServiceImpl.class.getMethod("createTopic", int.class, TopicCreateVO.class),
                TopicServiceImpl.class.getMethod("updateTopic", int.class, TopicUpdateVO.class),
                TopicServiceImpl.class.getMethod("changeTopicType", int.class, int.class),
                TopicServiceImpl.class.getMethod("setTopicInvisible", int.class, boolean.class),
                TopicServiceImpl.class.getMethod("deleteTopic", int.class),
                TopicServiceImpl.class.getMethod("deleteTopic", int.class, int.class),
                TopicServiceImpl.class.getMethod("setTopicTop", int.class, boolean.class)
        );

        for (Method mutation : mutations) {
            assertTrue(
                    mutation.isAnnotationPresent(Transactional.class),
                    () -> mutation + " must define a transaction boundary"
            );
        }
    }
}
