# 测试

覆盖实体规范化、关系证据校验、关系去重、增量导入、冲突检测和关键用户流程。

文档内容关联专项使用 [document-association-evaluation-v1.md](./document-association-evaluation-v1.md) 和 `fixture/document-association-v1/annotations.json` 作为固定评估规程与标注答案。

阶段 1 持久化基础使用 `backend/server/src/test/java/com/flevin/knowgraph/server/association/DocumentAssociationPersistenceIntegrationTests.java`，覆盖四张文档关联表、空间隔离、关系幂等、证据反查和审核状态历史。
