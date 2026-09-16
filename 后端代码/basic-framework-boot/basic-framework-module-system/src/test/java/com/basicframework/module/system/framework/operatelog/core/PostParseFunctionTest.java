package com.basicframework.module.system.framework.operatelog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.service.dept.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostParseFunctionTest {

    @Mock
    private PostService postService;

    private PostParseFunction parseFunction;

    @BeforeEach
    void setUp() {
        parseFunction = new PostParseFunction(postService);
    }

    @Test
    void functionName_returnsRegisteredName() {
        assertEquals(PostParseFunction.NAME, parseFunction.functionName());
    }

    @Test
    void apply_returnsEmptyForBlankValueWithoutQueryingService() {
        assertEquals("", parseFunction.apply(""));
        verifyNoInteractions(postService);
    }

    @Test
    void apply_returnsEmptyWhenPostDoesNotExist() {
        when(postService.getPost(9L)).thenReturn(null);

        assertEquals("", parseFunction.apply(9L));
    }

    @Test
    void apply_returnsPostName() {
        PostDO post = new PostDO();
        post.setName("架构师");
        when(postService.getPost(9L)).thenReturn(post);

        assertEquals("架构师", parseFunction.apply("9"));
    }
}
