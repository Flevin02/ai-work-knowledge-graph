package com.flevin.knowgraph.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/application.sqlite",
        "app.upload-dir=target/test-data/application-uploads"
})
@AutoConfigureMockMvc
class KnowledgeGraphApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void missingFaviconReturnsNotFoundResponse() throws Exception {
        // 请求当前后端未配置的浏览器站点图标
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("请求的资源不存在"));
    }

    @Test
    void firstDatabaseHasNoKnowledgeSpace() throws Exception {
        // 清理旧测试运行遗留的空间，模拟生产数据库首次建表后的空状态
        jdbcTemplate.update("DELETE FROM knowledge_spaces");

        // 新数据库首次启动不应自动创建默认知识空间
        mockMvc.perform(get("/v1/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
